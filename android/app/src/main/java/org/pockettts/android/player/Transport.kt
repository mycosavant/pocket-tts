package org.pockettts.android.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.annotation.VisibleForTesting
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
class Transport(context: Context) {

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
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(playbackStateOf(state), PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    companion object {
        private const val TAG = "PocketTTS"

        /** How much of the text the system player shows as the title. */
        private const val TITLE_CHARS = 80

        private const val ACTIONS = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
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
