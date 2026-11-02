package org.pockettts.android.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.pockettts.android.R
import org.pockettts.android.ui.MainActivity

/**
 * The read as the rest of the phone sees it: the media notification, the
 * media session behind the lock-screen and headset controls, and audio focus.
 *
 * All three follow [Reader.busy], from the moment a read is asked for until
 * nothing is playing or queued. They used to belong to [PlaybackService] and
 * live exactly as long as it did, which was wrong three ways, each of them
 * seen on an emulator:
 *
 * - A read asked for over the broadcast starts in the background, where the
 *   system refuses a foreground service. The read played with no
 *   notification, no controls and no focus, so a phone call did not pause it.
 * - The service stopped on the first read's ending, and every queued read
 *   after it played the same way.
 * - A read started just after another had ended met that ending in the
 *   state, and the service shut down under it.
 *
 * None of the three needs a foreground service. A notification can be posted,
 * a session made active and focus requested from the background. What the
 * service adds is protection from the process being killed, so it is still
 * started, and it adopts this notification when it is allowed to run.
 */
object NowPlaying {

    private const val TAG = "NowPlaying"
    private const val CHANNEL_ID = "playback"
    private const val FAILURE_CHANNEL_ID = "failures"
    private const val NOTIFICATION_PREVIEW_CHARS = 200

    /** Shared with [PlaybackService], which runs in the foreground under it. */
    const val NOTIFICATION_ID = 1

    @VisibleForTesting
    internal const val FAILURE_NOTIFICATION_ID = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var app: Context
    private var following: Job? = null
    private var transport: Transport? = null
    private var focus: AudioFocus? = null

    /** The utterance whose failure was last posted, so a re-emission does not post it twice. */
    private var failureShown = 0L

    /**
     * Starts following the reader. Called from the application, so it holds
     * for every way a read can begin.
     *
     * A second application only exists under Robolectric, which makes one per
     * test. Following the first one's main looper left every later test
     * without a follower.
     */
    fun attach(context: Context) {
        val application = context.applicationContext
        if (::app.isInitialized && app === application) return
        following?.cancel()
        transport = null
        focus = null
        app = application
        createChannels()
        following = scope.launch {
            combine(Reader.state, Reader.busy) { state, busy -> state to busy }
                .collect { (state, busy) -> follow(state, busy) }
        }
    }

    private fun follow(state: Reader.State, busy: Boolean) {
        if (busy) begin() else end()
        val transport = transport
        if (transport != null) {
            transport.update(state)
            notificationManager.notify(NOTIFICATION_ID, notification(state))
        }
        when (state) {
            // A new read makes the last one's failure old news.
            is Reader.State.Preparing, is Reader.State.Speaking ->
                notificationManager.cancel(FAILURE_NOTIFICATION_ID)
            // Said where it outlasts the read: there may be no window to say
            // it in (an agent's broadcast, a dismissed popup), and the media
            // notification goes when the read does.
            is Reader.State.Failed -> if (state.utterance != failureShown) {
                failureShown = state.utterance
                notifyFailure(state)
            }
            else -> {}
        }
    }

    private fun begin() {
        if (transport != null) return
        transport = Transport(app)
        // Asked once per stretch of reading, so a queued read does not ask
        // again, and the next read after a refusal does. Refused during a
        // call, reading over it is what focus exists to prevent, so the read
        // stops. Refused for any other reason, which on Android 16 includes
        // asking from the background, the read goes ahead without focus, as
        // every read did before focus was asked for.
        val focus = AudioFocus(app).also { focus = it }
        if (!focus.request()) {
            if (focus.callInProgress) {
                Log.i(TAG, "Audio focus refused during a call; stopping the read")
                Reader.stop()
            } else {
                Log.i(TAG, "Audio focus refused outside a call; reading without it")
            }
        }
    }

    private fun end() {
        val transport = transport ?: return
        this.transport = null
        focus?.release()
        focus = null
        transport.release()
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /**
     * The notification for the read in progress, for [PlaybackService] to go
     * foreground with. Brings the session up first if the reader is busy and
     * the collector has not caught up yet, so the notification names it.
     */
    fun foregroundNotification(): Notification {
        if (Reader.isBusy) begin()
        return notification(Reader.state.value)
    }

    private val notificationManager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            app.getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                FAILURE_CHANNEL_ID,
                app.getString(R.string.failure_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** A read that failed, as a notification that stays until it is dismissed. */
    private fun notifyFailure(state: Reader.State.Failed) {
        val notification = Notification.Builder(app, FAILURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.read_failed))
            .setContentText(state.message)
            .setStyle(Notification.BigTextStyle().bigText(state.message))
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        notificationManager.notify(FAILURE_NOTIFICATION_ID, notification)
    }

    /**
     * The read, as a media notification.
     *
     * A media notification rather than an ordinary one because of what it buys:
     * the same controls appear in the lock screen and in the quick-settings
     * player, where they can be reached without unlocking the phone. That
     * placement is the whole point of a listening app, and it is granted only
     * to a notification that names an active session.
     *
     * The framework builder rather than NotificationCompat because MediaStyle
     * lives in a support library this app does not depend on, and everything
     * used here has been in the platform since well before minSdk.
     *
     * The buttons start [PlaybackService] with a command. A tap on a
     * notification lets the app start a foreground service from the
     * background, so a read whose service was refused at the start gets one
     * the first time its notification is used.
     */
    private fun notification(state: Reader.State): Notification {
        val speaking = state as? Reader.State.Speaking
        val paused = speaking?.paused == true
        val audible = speaking?.audible == true
        val style = Notification.MediaStyle()
            // The collapsed notification has room for three, and Stop has to
            // be one of them. It used to be the one dropped, on the theory
            // that swiping the notification away already stopped the read -
            // but a media notification for a read in progress cannot be
            // swiped away, and on the device the broadcast path was tested on
            // there was no way to end a read from the shade at all. Forward is
            // the one to drop: it is the control that ends a read by accident,
            // and it is still there once the notification is expanded. On
            // Android 13 and later the system draws the session's actions
            // instead, and Transport makes the same choice.
            .setShowActionsInCompactView(0, 1, 3)
        transport?.let { style.setMediaSession(it.token) }
        return Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                app.getString(if (audible) R.string.reading_aloud else R.string.composing),
            )
            .setContentText(Reader.speakableText.take(NOTIFICATION_PREVIEW_CHARS))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
            // Back and forward first, so the two most-used controls sit where
            // a media notification puts them.
            .addAction(action(R.drawable.ic_skip_previous, R.string.skip_back, PlaybackService.ACTION_SKIP_BACK))
            .addAction(
                action(
                    if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                    if (paused) R.string.resume else R.string.pause,
                    PlaybackService.ACTION_TOGGLE_PAUSE,
                ),
            )
            .addAction(action(R.drawable.ic_skip_next, R.string.skip_forward, PlaybackService.ACTION_SKIP_FORWARD))
            .addAction(action(R.drawable.ic_stop, R.string.stop, PlaybackService.ACTION_STOP))
            .setStyle(style)
            .build()
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun action(icon: Int, title: Int, action: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(app, icon),
            app.getString(title),
            PendingIntent.getService(
                app,
                action.hashCode(),
                Intent(app, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
}
