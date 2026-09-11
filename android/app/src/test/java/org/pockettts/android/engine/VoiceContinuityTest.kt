package org.pockettts.android.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Carrying the voice from one sentence to the next.
 *
 * The failure this is written against is not hypothetical: it shipped once. An
 * earlier attempt replaced the voice prompt with generated audio and never
 * returned to it, so a long read walked away from the voice that was chosen -
 * and it acted at chunk boundaries, which are every second or third sentence
 * boundary, so it skipped most of the seams it existed to close. Both of those
 * are properties of the reference array, which is why these assert on it.
 */
class VoiceContinuityTest {

    private val rate = 24_000

    private fun voice(seconds: Float, sampleRate: Int = rate) = PocketTts.LoadedVoice(
        id = "alba",
        samples = FloatArray((seconds * sampleRate).toInt()) { 0.5f },
        sampleRate = sampleRate,
    )

    @Test
    fun `the first sentence is read from the prompt alone`() {
        val prompt = voice(seconds = 4f)
        val continuity = VoiceContinuity(prompt, rate)

        // Nothing has been spoken, so there is nothing to continue from and the
        // first sentence of a read has to sound exactly as it always did.
        assertFalse(continuity.hasContext)
        assertArrayEquals(prompt.samples, continuity.reference(), 0f)
    }

    @Test
    fun `the prompt stays at the head of every reference`() {
        // The whole of the previous attempt's failure, in one assertion: the
        // selected voice has to be in the reference every time, or a long read
        // drifts off it permanently.
        val prompt = voice(seconds = 4f)
        val continuity = VoiceContinuity(prompt, rate)

        repeat(50) { continuity.record(FloatArray(rate) { -1f }) }

        val reference = continuity.reference()
        assertTrue("reference is shorter than nothing", reference.isNotEmpty())
        assertEquals("the prompt is not at the head", 0.5f, reference.first(), 0f)
        assertEquals("the recent audio is not at the tail", -1f, reference.last(), 0f)
    }

    @Test
    fun `the reference is capped however much has been spoken`() {
        val prompt = voice(seconds = 9f)
        val continuity = VoiceContinuity(prompt, rate)

        repeat(20) { continuity.record(FloatArray(rate) { -1f }) }

        val cap = (VoiceContinuity.MAX_REFERENCE_SECONDS * rate).toInt()
        assertTrue(
            "reference grew past the cap: ${continuity.reference().size} > $cap",
            continuity.reference().size <= cap,
        )
    }

    @Test
    fun `only the recent tail is carried, not the whole read`() {
        val prompt = voice(seconds = 1f)
        val continuity = VoiceContinuity(prompt, rate)

        // A minute of audio through a two second tail.
        repeat(60) { continuity.record(FloatArray(rate) { -1f }) }

        val carried = continuity.reference().size - prompt.samples.size
        val expected = (VoiceContinuity.TAIL_SECONDS * rate).toInt()
        assertEquals("carried more than the tail", expected, carried)
    }

    @Test
    fun `a prompt at another rate is carried anyway, with the tail resampled`() {
        // This guard used to disable the feature outright, and the stock
        // prompts are not recorded at the rate the model generates at - so on a
        // real device it turned the whole thing off silently while the
        // per-sentence splitting it pays for went on happening.
        val prompt = voice(seconds = 4f, sampleRate = 48_000)
        val continuity = VoiceContinuity(prompt, outputSampleRate = rate)

        assertTrue("stood down instead of resampling", continuity.usable)
        continuity.record(FloatArray(rate) { -1f })
        assertTrue("nothing was carried", continuity.hasContext)

        val reference = continuity.reference()
        assertTrue(
            "the reference is no longer than the prompt, so nothing was added",
            reference.size > 0,
        )
        assertEquals("the prompt is not at the head", 0.5f, reference.first(), 0f)
        assertEquals("the recent audio is not at the tail", -1f, reference.last(), 0.01f)
    }

    @Test
    fun `resampling changes the length by the ratio of the rates, not the content`() {
        val continuity = VoiceContinuity(voice(seconds = 1f), rate)
        val source = FloatArray(1000) { 0.25f }

        val up = continuity.resample(source, from = 24_000, to = 48_000)
        assertEquals(2000, up.size)
        assertEquals("a constant signal did not survive upsampling", 0.25f, up[500], 1e-6f)

        val down = continuity.resample(source, from = 48_000, to = 24_000)
        assertEquals(500, down.size)
        assertEquals("a constant signal did not survive downsampling", 0.25f, down[250], 1e-6f)

        assertArrayEquals(
            "equal rates copied instead of passing through",
            source,
            continuity.resample(source, from = rate, to = rate),
            0f,
        )
    }

    @Test
    fun `a missing rate is the only thing that stands down`() {
        val prompt = voice(seconds = 4f, sampleRate = 0)
        val continuity = VoiceContinuity(prompt, outputSampleRate = rate)

        assertFalse(continuity.usable)
        continuity.record(FloatArray(rate) { -1f })
        assertFalse(continuity.hasContext)
    }

    @Test
    fun `a reset read starts from the prompt again`() {
        val prompt = voice(seconds = 4f)
        val continuity = VoiceContinuity(prompt, rate)

        continuity.record(FloatArray(rate) { -1f })
        assertTrue(continuity.hasContext)

        continuity.reset()
        assertFalse(continuity.hasContext)
        assertArrayEquals(prompt.samples, continuity.reference(), 0f)
    }
}
