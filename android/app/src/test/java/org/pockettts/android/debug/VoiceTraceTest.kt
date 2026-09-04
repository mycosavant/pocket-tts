package org.pockettts.android.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The record of who actually spoke.
 *
 * Several distinct faults all sound like "it used the wrong voice", and the
 * phone this runs on has no logcat within reach. What separates them is not
 * audible and not inferable - it is which id was asked for, which was found,
 * and whether the file behind it is the one that voice ships as.
 */
class VoiceTraceTest {

    @Before
    fun setUp() = VoiceTrace.clear()

    @Test
    fun `a fallback is called a fallback`() {
        // Silent is how "it used the wrong voice" becomes a mystery.
        VoiceTrace.resolved("reader", "my-recording", "alba", 958_542, 958_542)
        assertTrue(VoiceTrace.report(), "FELL BACK" in VoiceTrace.report())
    }

    @Test
    fun `a prompt of the wrong size is called out`() {
        // The one that is permanent: an import overwrote a stock prompt, so
        // this voice is a different person on every read until the file goes.
        VoiceTrace.resolved("reader", "alba", "alba", 41_234, 958_542)
        assertTrue(VoiceTrace.report(), "EXPECTED 958542" in VoiceTrace.report())
    }

    @Test
    fun `the expected case says nothing alarming`() {
        VoiceTrace.resolved("reader", "alba", "alba", 958_542, 958_542)
        val report = VoiceTrace.report()
        assertFalse(report, "FELL BACK" in report)
        assertFalse(report, "EXPECTED" in report)
    }

    @Test
    fun `a generation records its prompt, temperature and seed`() {
        VoiceTrace.resolved("reader", "alba", "alba", 958_542, 958_542)
        VoiceTrace.generated("alba", promptSamples = 240_000, promptRate = 24_000, promptHash = 0x1234, temperature = 0.3f, seed = 1)
        val report = VoiceTrace.report()

        assertTrue(report, "prompt=10.00s" in report)
        assertTrue(report, "temp=0.30" in report)
        assertTrue(report, "seed=1" in report)
    }

    @Test
    fun `a random seed is reported as random rather than as minus one`() {
        VoiceTrace.generated("alba", 240_000, 24_000, 0, 0.7f, -1)
        assertTrue(VoiceTrace.report(), "seed=random" in VoiceTrace.report())
    }

    @Test
    fun `chunks are numbered from the start of each read`() {
        VoiceTrace.resolved("reader", "alba", "alba", 1, 1)
        repeat(3) { VoiceTrace.generated("alba", 240_000, 24_000, 0, 0.3f, 1) }
        VoiceTrace.resolved("reader", "jane", "jane", 1, 1)
        VoiceTrace.generated("jane", 240_000, 24_000, 0, 0.3f, 1)

        assertTrue(VoiceTrace.report(), VoiceTrace.report().trimEnd().endsWith(
            VoiceTrace.report().lines().last(),
        ))
        assertTrue(VoiceTrace.report(), VoiceTrace.report().lines().last().startsWith("[chunk 0]"))
    }

    @Test
    fun `a long read does not grow without bound`() {
        repeat(500) { VoiceTrace.generated("alba", 240_000, 24_000, 0, 0.3f, 1) }
        assertTrue(VoiceTrace.report().lines().size <= 60)
    }
}
