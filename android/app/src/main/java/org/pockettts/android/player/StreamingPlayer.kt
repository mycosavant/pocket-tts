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

    /**
     * Blocks until everything already written has been played. Time spent
     * paused does not count: a pause during the last buffer used to let the
     * wait run out while the end of the read was still unheard, and the
     * release that followed flushed it.
     */
    override fun drain() {
        val active = track ?: return
        awaitPlayout(
            written = writtenFrames,
            head = { active.playbackHeadPosition.toLong() and 0xFFFFFFFFL },
            paused = paused::get,
            stopped = stopped::get,
            awaitResume = ::awaitResume,
        )
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

        /**
         * How long the playback head may stand still, while playing, before a
         * drain gives up on the frames it has not reached. Some devices stop
         * reporting a few frames short of the end of a stream.
         */
        const val STALL_MILLIS = 500L

        /**
         * Waits until the playback [head] reaches [written] frames, or [stopped].
         * While [paused] it waits in [awaitResume] instead, and that time does
         * not count towards a stall.
         */
        internal fun awaitPlayout(
            written: Long,
            head: () -> Long,
            paused: () -> Boolean,
            stopped: () -> Boolean,
            awaitResume: () -> Unit,
            now: () -> Long = System::currentTimeMillis,
            sleep: (Long) -> Unit = Thread::sleep,
        ) {
            var lastHead = -1L
            var movedAt = now()
            while (!stopped()) {
                if (paused()) {
                    awaitResume()
                    movedAt = now()
                    continue
                }
                val played = head()
                if (played >= written) return
                if (played != lastHead) {
                    lastHead = played
                    movedAt = now()
                } else if (now() - movedAt > STALL_MILLIS) {
                    return
                }
                sleep(DRAIN_POLL_MILLIS)
            }
        }
    }
}
