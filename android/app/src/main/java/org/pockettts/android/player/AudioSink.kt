package org.pockettts.android.player

/**
 * Where synthesised audio goes.
 *
 * Exists so the reader can be driven in a test without an `AudioTrack`;
 * [StreamingPlayer] is the only implementation that makes a sound.
 */
interface AudioSink {

    val isPaused: Boolean

    /**
     * Times the output ran dry, if the implementation can tell.
     *
     * The one number that settles whether synthesis is keeping up with
     * playback; a fake has nothing to report.
     */
    val underruns: Int get() = 0

    /**
     * Frames the listener has heard so far, or -1 if the implementation
     * cannot tell. Behind what has been written by whatever the output
     * buffers, which is what puts a skip a sentence off if it is not
     * accounted for.
     */
    val playedFrames: Long get() = -1

    fun start()

    /** @return false if playback was stopped and the caller should give up. */
    fun write(samples: FloatArray): Boolean

    /** Writes [seconds] of silence, for the gap between paragraphs. */
    fun writeSilence(seconds: Float): Boolean

    fun pause()

    fun resume()

    /** Blocks until everything already written has been played. */
    fun drain()

    fun stop()

    fun release()

    fun interface Factory {
        fun create(sampleRate: Int): AudioSink
    }
}
