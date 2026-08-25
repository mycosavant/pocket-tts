package org.pockettts.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample trim, which is the only part of previewing a voice that is
 * arithmetic rather than audio hardware.
 *
 * It matters because the reference prompts are not uniform: most run a few
 * seconds, but the catalogue includes ones far longer, and a preview that plays
 * the whole file is not a preview.
 */
class VoiceSampleTest {

    private val rate = 24_000

    private fun tone(seconds: Float) = FloatArray((seconds * rate).toInt()) { 0.5f }

    @Test
    fun `leaves a prompt shorter than the limit alone`() {
        // Not merely equal - the same array. A short prompt should not be copied,
        // and more importantly its own ending must not be faded into silence.
        val short = tone(2f)
        assertSame(short, VoiceSample.trim(short, rate, maxSeconds = 6f, fadeSeconds = 0.12f))
    }

    @Test
    fun `cuts a long prompt to the limit`() {
        val long = tone(30f)
        val cut = VoiceSample.trim(long, rate, maxSeconds = 6f, fadeSeconds = 0.12f)
        assertEquals(6 * rate, cut.size)
    }

    @Test
    fun `fades the cut so it does not click`() {
        val cut = VoiceSample.trim(tone(30f), rate, maxSeconds = 6f, fadeSeconds = 0.12f)
        val fade = (0.12f * rate).toInt()

        // Silent at the very end, untouched before the fade begins, and
        // monotonically decreasing in between: a step discontinuity is
        // broadband, and it is audible as a click on every single preview.
        assertEquals(0f, cut.last(), 1e-4f)
        assertEquals(0.5f, cut[cut.size - fade - 1], 1e-6f)
        for (index in cut.size - fade until cut.size - 1) {
            assertTrue("sample $index rises during the fade", cut[index] >= cut[index + 1])
        }
    }

    @Test
    fun `survives a prompt with no usable sample rate`() {
        val samples = tone(2f)
        assertSame(samples, VoiceSample.trim(samples, sampleRate = 0, maxSeconds = 6f, fadeSeconds = 0.12f))
    }

    @Test
    fun `never fades past the start of the audio`() {
        // A fade longer than what is left after the cut would run off the front
        // of the array.
        val cut = VoiceSample.trim(tone(10f), rate, maxSeconds = 0.05f, fadeSeconds = 1f)
        assertEquals((0.05f * rate).toInt(), cut.size)
        assertEquals(0f, cut.last(), 1e-4f)
    }
}
