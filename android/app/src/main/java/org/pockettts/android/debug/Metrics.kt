package org.pockettts.android.debug

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What the engine actually did, in numbers, on the device it did it on.
 *
 * Every performance question about this app has the same shape: inference runs
 * on the CPU at roughly real time, and whether that is *acceptable* depends on
 * timings nobody here can measure. There is no emulator in the build
 * environment, so the only instrument available is the phone in the owner's
 * hand - which makes reporting these worth more than reasoning about them.
 *
 * Three numbers, chosen because each answers a specific open question:
 *
 * - **Time to first audio.** The wait everybody feels. Everything else about
 *   the reading experience is downstream of it.
 * - **Generation speed on the first chunk**, before the buffer fills and
 *   blocking writes make every subsequent measurement come out at exactly real
 *   time by construction. Below 1.0 means the model cannot keep up with its own
 *   playback and gaps are inevitable rather than incidental.
 * - **Underruns.** `AudioTrack` counts the times it ran dry. Synthesis blocks
 *   inside the audio callback, so sherpa-onnx cannot begin the next sentence
 *   until the buffer has drained to a couple of seconds; if that theory is
 *   right this number climbs once per sentence. If it stays at zero the theory
 *   is wrong and the fix it implies is not worth making.
 */
object Metrics {

    private val modelLoad = AtomicLong(0)
    private val firstAudio = AtomicLong(0)
    private val generationRtfMilli = AtomicLong(0)
    private val underrunCount = AtomicInteger(0)
    private val utterances = AtomicInteger(0)

    /** Milliseconds spent loading the model, the last time it was loaded. */
    var modelLoadMillis: Long
        get() = modelLoad.get()
        set(value) = modelLoad.set(value)

    /** Milliseconds from asking for speech to the first sample being written. */
    var timeToFirstAudioMillis: Long
        get() = firstAudio.get()
        set(value) {
            firstAudio.set(value)
            utterances.incrementAndGet()
        }

    /**
     * Audio seconds produced per wall second, on the first chunk of the last
     * utterance. Above 1.0 is faster than real time.
     */
    var generationRealTimeFactor: Float
        get() = generationRtfMilli.get() / 1000f
        set(value) = generationRtfMilli.set((value * 1000).toLong())

    /** Times the audio track ran dry during the last utterance. */
    var underruns: Int
        get() = underrunCount.get()
        set(value) = underrunCount.set(value)

    val utterancesRead: Int get() = utterances.get()

    fun reset() {
        modelLoad.set(0)
        firstAudio.set(0)
        generationRtfMilli.set(0)
        underrunCount.set(0)
        utterances.set(0)
    }

    /**
     * The numbers, as something that can be read off a screen or pasted into a
     * message. Unmeasured values say so rather than reporting a confident zero.
     */
    fun report(): String = buildString {
        appendLine("Pocket TTS timings")
        appendLine("utterances read: ${utterancesRead}")
        appendLine("model load: ${millis(modelLoadMillis)}")
        appendLine("time to first audio: ${millis(timeToFirstAudioMillis)}")
        appendLine("generation speed: ${factor(generationRealTimeFactor)}")
        append("audio underruns: ${if (utterancesRead == 0) "not measured yet" else "$underruns"}")
    }

    private fun millis(value: Long): String =
        if (value <= 0) "not measured yet" else "$value ms"

    private fun factor(value: Float): String = when {
        value <= 0f -> "not measured yet"
        else -> "%.2fx real time".format(value) +
            if (value < 1f) " - slower than playback" else ""
    }
}
