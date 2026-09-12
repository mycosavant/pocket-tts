package org.pockettts.android.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.VisibleForTesting
import org.pockettts.android.R
import org.pockettts.android.engine.Settings

/**
 * Publishes the current read as a media session.
 *
 * Until now the only controls were the four buttons in our own notification,
 * which means they were reachable only by pulling down the shade and looking at
 * it. That is the wrong place for an app whose whole point is that you are
 * doing something else while it talks: the controls people actually reach for
 * are the button on the headphones and the player in quick settings, and
 * neither of those knows an app exists unless it owns a media session.
 *
 * The session is also what makes the notification a media notification. Without
 * a token, `MediaStyle` degrades to an ordinary notification and the system
 * media player stays empty.
 *
 * Framework `MediaSession` rather than a support library: everything used here
 * has been in the platform since API 21 and this app is at 26.
 */
class Transport(private val context: Context) {

    private val settings = Settings(context)
    private val session = MediaSession(context, TAG)

    val token: MediaSession.Token get() = session.sessionToken

    /**
     * What the system asks for, translated into what the reader does.
     *
     * The base class turns a headset's single play/pause button into onPlay or
     * onPause by looking at the published playback state, which is why [update]
     * is careful to publish paused and playing distinctly.
     *
     * Held as a field so a test can call it. Delivering a real media button
     * through the framework needs a live session router, but the mapping below
     * is ours, and the mapping is what can be wrong.
     */
    @VisibleForTesting
    internal val callback = object : MediaSession.Callback() {
        override fun onPlay() { Reader.resume() }
        override fun onPause() { Reader.pause() }
        override fun onSkipToNext() { Reader.skipForward() }
        override fun onSkipToPrevious() { Reader.skipBack() }
        override fun onStop() { Reader.stop() }

        // The system player draws standard actions it has a button for, and
        // ACTION_STOP is not one of them - so a read started from the broadcast
        // offered back, pause and forward and no way to end it short of waiting
        // it out or finding the app. A custom action is the supported way to
        // put a button there, and it lands on the same Reader.stop as the
        // sheet's own Stop. Forward is a custom action too; see [ACTIONS].
        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACTION_STOP -> Reader.stop()
                ACTION_SKIP_FORWARD -> Reader.skipForward()
            }
        }

        // The framework only forwards a headset's "next" to onSkipToNext when
        // ACTION_SKIP_TO_NEXT is in the published actions, and it is
        // deliberately not (see [ACTIONS]). Taking the key here keeps the
        // button on the headphones doing what it always did.
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val key = keyEventOf(mediaButtonIntent)
            if (key != null && key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0 &&
                key.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
            ) {
                Reader.skipForward()
                return true
            }
            return super.onMediaButtonEvent(mediaButtonIntent)
        }
    }

    init {
        session.setCallback(callback)
        session.isActive = true
    }

    /** Mirrors [state] into the session, so the system controls match the app. */
    fun update(state: Reader.State) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titleOf(Reader.speakableText))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, settings.voice().displayName)
                // No duration. The length of a read is not known until it has
                // been synthesised, and a made-up one would be drawn as a
                // progress bar and a clock that both lie.
                .build(),
        )
        session.setPlaybackState(playbackState(context, state))
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    companion object {
        private const val TAG = "PocketTTS"

        /** The custom action id for Stop; see [callback]. */
        const val ACTION_STOP = "org.pockettts.android.transport.STOP"

        /** The custom action id for skipping forward; see [ACTIONS]. */
        const val ACTION_SKIP_FORWARD = "org.pockettts.android.transport.SKIP_FORWARD"

        /** How much of the text the system player shows as the title. */
        private const val TITLE_CHARS = 80

        /**
         * What the session says it can do - and, on Android 13 and later,
         * which buttons the system draws.
         *
         * The system player has three slots in its collapsed form: previous,
         * play/pause, next. A standard action in this mask claims its slot;
         * a slot left unclaimed goes to the first custom action. Everything
         * else, custom actions included, is only reachable by expanding the
         * player, and on the lock screen there is nothing to expand.
         *
         * So Stop as a custom action *alone* bought no visible button: on the
         * device it was tested on, the shade and the lock screen showed back,
         * pause and forward, and no way to end the read. Not publishing
         * ACTION_SKIP_TO_NEXT leaves that slot to Stop, and forward moves to
         * the expanded view as a custom action. For a reader controlled from a
         * pocket, ending the read is the control that has to be there; forward
         * is the one that ends it by accident, since a skip past the last chunk
         * finishes the utterance.
         *
         * The headset's "next" button is unaffected: [callback] takes the key
         * itself, because the framework's default only forwards it when the
         * action is published.
         */
        private const val ACTIONS = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP

        /**
         * What the reader is doing, in the vocabulary the system understands.
         *
         * Buffering rather than playing while [Reader.State.Preparing] and
         * while a chunk is still being composed: the model is silent for the
         * first seconds of a read, and a player that says it is playing during
         * a silence is reporting a fault that is not there.
         */
        /**
         * The whole published state, not just its integer.
         *
         * Built here rather than inline so a test can read what was published
         * without a live session router - the same reason [playbackStateOf] is
         * a function. What a test needs to see is the custom action: the system
         * player has buttons for play, pause and the two skips and nothing
         * else, so ACTION_STOP in the mask below buys no button at all, and a
         * read started from the broadcast had no way to be ended from the
         * shade.
         */
        @VisibleForTesting
        fun playbackState(context: Context, state: Reader.State): PlaybackState =
            PlaybackState.Builder()
                .setActions(ACTIONS)
                // Order matters: the first custom action takes the collapsed
                // slot that ACTION_SKIP_TO_NEXT leaves free. See [ACTIONS].
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        ACTION_STOP,
                        context.getString(R.string.stop),
                        R.drawable.ic_stop,
                    ).build(),
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        ACTION_SKIP_FORWARD,
                        context.getString(R.string.skip_forward),
                        R.drawable.ic_skip_next,
                    ).build(),
                )
                .setState(playbackStateOf(state), PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()

        /** The key a media-button intent carries, if it carries one. */
        @VisibleForTesting
        fun keyEventOf(intent: Intent): KeyEvent? =
            if (intent.action != Intent.ACTION_MEDIA_BUTTON) {
                null
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }

        fun playbackStateOf(state: Reader.State): Int = when {
            state is Reader.State.Preparing -> PlaybackState.STATE_BUFFERING
            state is Reader.State.Speaking && state.paused -> PlaybackState.STATE_PAUSED
            state is Reader.State.Speaking && !state.audible -> PlaybackState.STATE_BUFFERING
            state is Reader.State.Speaking -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_STOPPED
        }

        /**
         * The first line of what is being read, as a title.
         *
         * Media titles are drawn on one line and truncated hard, so a
         * paragraph's worth of text would show as its first few words followed
         * by an ellipsis whatever we do; cutting at a word boundary at least
         * makes those words whole.
         */
        fun titleOf(text: String): String {
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (firstLine.length <= TITLE_CHARS) return firstLine
            val cut = firstLine.lastIndexOf(' ', TITLE_CHARS)
            return firstLine.take(if (cut > 0) cut else TITLE_CHARS).trimEnd() + "…"
        }
    }
}
