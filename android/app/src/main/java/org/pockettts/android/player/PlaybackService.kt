package org.pockettts.android.player

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.pockettts.android.debug.Metrics

/**
 * Keeps reading alive once the user leaves the app.
 *
 * Selecting text in another app and tapping "Read aloud" pops up a small
 * activity; dismissing it should not cut the voice off mid-sentence. A
 * foreground service is what buys the process the right to keep producing audio
 * after its last activity is gone.
 *
 * That is all it does. The notification, the media session and audio focus
 * belong to [NowPlaying], which holds them whether or not the system let this
 * service start, and this service goes foreground under the same notification.
 */
class PlaybackService : Service() {

    // Not immediate: a service started with nothing to read stops on the next
    // turn of the looper, not one line after it went foreground.
    private val scope = CoroutineScope(Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> Reader.togglePause()
            ACTION_SKIP_BACK -> Reader.skipBack()
            ACTION_SKIP_FORWARD -> Reader.skipForward()
            ACTION_NEXT_ITEM -> Reader.next()
            ACTION_CYCLE_SPEED -> Reader.cycleSpeed(this)
            ACTION_STOP -> {
                Reader.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Going foreground is allowed to fail: the system refuses it outright
        // when the app is judged to be in the background, and the exception is
        // thrown straight out of onStartCommand where nothing catches it. Losing
        // the protection means reading stops if the process is reclaimed, which
        // is a far better outcome than taking the process down mid-word.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NowPlaying.NOTIFICATION_ID,
                NowPlaying.foregroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }
        if (foreground.isFailure) {
            Log.w(TAG, "Could not go foreground; reading continues unprotected", foreground.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }

        // Busy, not the state: between two queued reads the state is the
        // first one's ending, and just after a speak it can still be the
        // previous read's. Either made this service stop under a read that
        // was still to come. Idle before reading begins is covered too: the
        // read is counted as soon as it is asked for, which is before this
        // service is started.
        if (watcher == null) {
            watcher = scope.launch {
                Reader.busy.collect { busy -> if (!busy) stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PlaybackService"

        const val ACTION_TOGGLE_PAUSE = "org.pockettts.android.TOGGLE_PAUSE"
        const val ACTION_SKIP_BACK = "org.pockettts.android.SKIP_BACK"
        const val ACTION_SKIP_FORWARD = "org.pockettts.android.SKIP_FORWARD"
        const val ACTION_STOP = "org.pockettts.android.STOP"
        const val ACTION_NEXT_ITEM = "org.pockettts.android.NEXT_ITEM"
        const val ACTION_CYCLE_SPEED = "org.pockettts.android.CYCLE_SPEED"

        /**
         * Starts the service, or carries on without it.
         *
         * The system refuses a foreground service started from the background
         * outright, and a read asked for over
         * [SpeakReceiver][org.pockettts.android.SpeakReceiver] begins there by
         * design - phone in a pocket, screen off. The refusal costs the read
         * its protection from the process being reclaimed. The notification
         * and the controls are [NowPlaying]'s and stay. It does not stop the
         * reader, which is already running. Taking the caller down over it
         * would.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, PlaybackService::class.java))
            }.onSuccess {
                Metrics.playbackProtection = "started"
            }.onFailure {
                Log.w(TAG, "Could not start playback; reading continues unprotected", it)
                // Recorded where it can be read. A phone has no logcat within
                // reach, and a read that is cut off when the process is
                // reclaimed looks like a crash; it is a refused background
                // start, and the exception names it.
                Metrics.playbackProtection =
                    "refused (${it.javaClass.simpleName}) - a long read may be cut off in the background; " +
                        "allow background activity for Pocket TTS in battery settings"
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
