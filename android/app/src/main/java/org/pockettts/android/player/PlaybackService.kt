package org.pockettts.android.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.pockettts.android.R
import org.pockettts.android.ui.MainActivity

/**
 * Keeps reading alive once the user leaves the app.
 *
 * Selecting text in another app and tapping "Read aloud" pops up a small
 * activity; dismissing it should not cut the voice off mid-sentence. A
 * foreground service is what buys the process the right to keep producing audio
 * after its last activity is gone.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var watcher: Job? = null

    /**
     * Held for as long as this service is playing.
     *
     * The service already owns "reading is allowed to continue"; the right to
     * be heard while doing it belongs in the same place, and is given back in
     * onDestroy along with everything else.
     */
    private val focus by lazy { AudioFocus(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> Reader.togglePause()
            ACTION_SKIP_BACK -> Reader.skipBack()
            ACTION_SKIP_FORWARD -> Reader.skipForward()
            ACTION_STOP -> {
                Reader.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Going foreground is allowed to fail: the system refuses it outright
        // when the app is judged to be in the background, and the exception is
        // thrown straight out of onStartCommand where nothing catches it. Losing
        // the notification means reading stops when the app is swapped away,
        // which is a far better outcome than taking the process down mid-word.
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(paused = false, audible = false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }
        if (foreground.isFailure) {
            Log.w(TAG, "Could not go foreground; reading continues unprotected", foreground.exceptionOrNull())
            stopSelf()
            return START_NOT_STICKY
        }

        focus.request()

        if (watcher == null) {
            watcher = scope.launch {
                Reader.state.collectLatest { state ->
                    when (state) {
                        is Reader.State.Speaking ->
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildNotification(state.paused, audible = state.audible),
                            )

                        is Reader.State.Preparing ->
                            notificationManager.notify(
                                NOTIFICATION_ID,
                                buildNotification(paused = false, audible = false),
                            )

                        // Idle means the reader has not started yet - the
                        // service is deliberately started first, so that the
                        // process is already protected when synthesis begins.
                        // Only an utterance that actually ended means stop.
                        else -> if (state.isTerminal) stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        focus.release()
        super.onDestroy()
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(paused: Boolean, audible: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                getString(if (audible) R.string.reading_aloud else R.string.composing),
            )
            .setContentText(Reader.speakableText.take(NOTIFICATION_PREVIEW_CHARS))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(Reader.speakableText.take(NOTIFICATION_PREVIEW_CHARS)),
            )
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            // Back and forward first, so the two most-used controls sit where
            // a media notification puts them.
            .addAction(
                R.drawable.ic_skip_previous,
                getString(R.string.skip_back),
                command(ACTION_SKIP_BACK),
            )
            .addAction(
                0,
                getString(if (paused) R.string.resume else R.string.pause),
                command(ACTION_TOGGLE_PAUSE),
            )
            .addAction(
                R.drawable.ic_skip_next,
                getString(R.string.skip_forward),
                command(ACTION_SKIP_FORWARD),
            )
            .addAction(0, getString(R.string.stop), command(ACTION_STOP))
            .build()

    private fun command(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_PREVIEW_CHARS = 200

        const val ACTION_TOGGLE_PAUSE = "org.pockettts.android.TOGGLE_PAUSE"
        const val ACTION_SKIP_BACK = "org.pockettts.android.SKIP_BACK"
        const val ACTION_SKIP_FORWARD = "org.pockettts.android.SKIP_FORWARD"
        const val ACTION_STOP = "org.pockettts.android.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
