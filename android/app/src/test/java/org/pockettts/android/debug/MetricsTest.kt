package org.pockettts.android.debug

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The engine's timings, as they will be read off a phone.
 *
 * These exist to decide something specific: synthesis blocks inside the audio
 * callback, so sherpa-onnx cannot begin the next sentence until the buffer has
 * drained, and the prediction is a gap at every sentence boundary. Nothing in
 * this build environment can test that - there is no emulator - so the fix has
 * to wait on a number from a device.
 *
 * Which makes the one unforgivable thing here a confident zero. "0 underruns"
 * from a session that never played anything reads as evidence and is not.
 */
class MetricsTest {

    @Before
    fun clear() = Metrics.reset()

    @Test
    fun `an unmeasured value says so rather than reporting zero`() {
        val report = Metrics.report()
        listOf("model load", "time to first audio", "generation speed", "audio underruns")
            .forEach { line ->
                val value = report.lineSequence().first { it.startsWith(line) }
                assertTrue("$line reported a number it never measured: $value", "not measured yet" in value)
            }
    }

    @Test
    fun `measured values are reported with their units`() {
        Metrics.modelLoadMillis = 2400
        Metrics.timeToFirstAudioMillis = 1850
        Metrics.generationRealTimeFactor = 1.4f
        Metrics.underruns = 3

        val report = Metrics.report()
        assertTrue(report, "2400 ms" in report)
        assertTrue(report, "1850 ms" in report)
        assertTrue(report, "1.40x real time" in report)
        assertTrue(report, "audio underruns: 3" in report)
    }

    @Test
    fun `generation slower than playback says so, because that is the finding`() {
        Metrics.timeToFirstAudioMillis = 1
        Metrics.generationRealTimeFactor = 0.8f
        assertTrue(Metrics.report(), "slower than playback" in Metrics.report())

        Metrics.generationRealTimeFactor = 1.6f
        assertTrue(Metrics.report(), "slower than playback" !in Metrics.report())
    }

    @Test
    fun `underruns are only credible once something has been read`() {
        // Zero underruns before a single utterance is not a measurement.
        Metrics.underruns = 0
        assertTrue("not measured yet" in Metrics.report())

        Metrics.timeToFirstAudioMillis = 900
        assertTrue("audio underruns: 0" in Metrics.report())
    }
}
