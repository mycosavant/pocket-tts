package org.pockettts.android.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plays float PCM as it arrives.
 *
 * Writes are blocking on purpose. Synthesis on a phone runs close to real time,
 * so letting the writer block when the buffer is full applies exactly the back
 * pressure we want: the model generates roughly as fast as the speaker drains,
 * and memory stays flat no matter how long the text is.
 */
class StreamingPlayer(private val sampleRate: Int) : AudioSink {

    private var track: AudioTrack? = null
    private val stopped = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val pauseLock = Object()

    override val isPaused: Boolean get() = paused.get()

    override val underruns: Int get() = track?.underrunCount ?: 0

    override fun start() {
        check(track == null) { "StreamingPlayer already started" }
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        ).coerceAtLeast(sampleRate * BYTES_PER_SAMPLE / 2)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    // A buffer of a couple of seconds rides out the pauses
                    // between chunks without adding audible latency at the
                    // start, since playback begins as soon as the first write
                    // lands.
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * BUFFER_MULTIPLIER)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /**
     * Writes [samples], blocking while the buffer is full.
     *
     * @return false if playback was stopped and the caller should give up.
     */
    override fun write(samples: FloatArray): Boolean {
        val active = track ?: return false
        var offset = 0
        while (offset < samples.size) {
            if (stopped.get()) return false
            awaitResume()
            if (stopped.get()) return false

            val written = active.write(
                samples,
                offset,
                samples.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written < 0) return false
            offset += written
            writtenFrames += written
        }
        return true
    }

    /** Writes [seconds] of silence, for the gap between paragraphs. */
    override fun writeSilence(seconds: Float): Boolean {
        if (seconds <= 0f) return true
        return write(FloatArray((seconds * sampleRate).toInt()))
    }

    override fun pause() {
        if (paused.compareAndSet(false, true)) track?.pause()
    }

    override fun resume() {
        if (paused.compareAndSet(true, false)) {
            track?.play()
            synchronized(pauseLock) { pauseLock.notifyAll() }
        }
    }

    /** Blocks until everything already written has been played. */
    override fun drain() {
        val active = track ?: return
        if (stopped.get()) return
        // There is no "wait for drain" on a streaming AudioTrack, so wait out
        // the samples we know are still queued.
        val queued = queuedFrames(active)
        if (queued > 0) {
            val millis = (queued * 1000L / sampleRate).coerceAtMost(MAX_DRAIN_MILLIS)
            val deadline = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < deadline && !stopped.get()) {
                Thread.sleep(DRAIN_POLL_MILLIS)
            }
        }
    }

    private fun queuedFrames(active: AudioTrack): Long {
        val written = writtenFrames
        val played = active.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return (written - played).coerceAtLeast(0)
    }

    private var writtenFrames: Long = 0

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        resume()
        track?.runCatching {
            pause()
            flush()
            stop()
        }
    }

    override fun release() {
        stop()
        track?.release()
        track = null
    }

    private fun awaitResume() {
        while (paused.get() && !stopped.get()) {
            synchronized(pauseLock) {
                if (paused.get() && !stopped.get()) pauseLock.wait(PAUSE_POLL_MILLIS)
            }
        }
    }

    companion object : AudioSink.Factory {
        override fun create(sampleRate: Int): AudioSink = StreamingPlayer(sampleRate)

        const val BYTES_PER_SAMPLE = 4
        const val BUFFER_MULTIPLIER = 4
        const val PAUSE_POLL_MILLIS = 200L
        const val DRAIN_POLL_MILLIS = 50L
        const val MAX_DRAIN_MILLIS = 30_000L
    }
}
