package org.pockettts.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The two settings that decide *who* is speaking.
 *
 * They travel in `GenerationConfig.extra`, an untyped string map read on the
 * C++ side by name. Nothing about that is checked by a compiler: a wrong key,
 * a wrong spelling, or no map at all all compile, and all produce the same
 * result - sherpa-onnx's own defaults, silently. This app shipped that way, and
 * the symptom was a voice that changed from sentence to sentence, which is
 * indistinguishable by ear from a dozen other faults.
 *
 * The keys and their defaults, from `offline-tts-pocket-impl.h`:
 *
 *     float temperature = gen_config.GetExtraFloat("temperature", 0.7f);
 *     float stddev = std::sqrt(temperature);
 *     int32_t seed = gen_config.GetExtraInt("seed", -1);
 *     NormalDataGenerator normal_gen(0, stddev, seed);
 *
 * inside `GenerateSingleSentence`, which sherpa-onnx calls once per sentence
 * after re-splitting whatever text it was given. Per sentence, not per call:
 * that is why a fixed seed is what holds a voice still across a paragraph, and
 * why the chunk-level machinery this replaced could never have done it.
 */
class GenerationConfigTest {

    private val voice = PocketTts.LoadedVoice("alba", FloatArray(240), 24_000)

    private fun config(temperature: Float = 0.3f, seed: Int = 1) =
        PocketTts.generationConfig(voice, speed = 1f, numSteps = 5, temperature = temperature, seed = seed)

    @Test
    fun `temperature and seed are actually sent`() {
        val extra = config().extra
        assertNotNull("no extra map at all, so sherpa-onnx uses its own defaults", extra)
        assertEquals("0.3", extra!!["temperature"])
        assertEquals("1", extra["seed"])
    }

    @Test
    fun `the keys are the ones the engine reads`() {
        // Spelled out rather than referenced, so that renaming a constant
        // cannot quietly rename the key the C++ is looking for.
        assertEquals(setOf("temperature", "seed"), config().extra?.keys)
    }

    @Test
    fun `a random draw is asked for as minus one`() {
        assertEquals("-1", config(seed = Settings.RANDOM_SEED).extra?.get("seed"))
    }

    @Test
    fun `the voice prompt is passed at its own sample rate`() {
        val prompt = PocketTts.LoadedVoice("anna", FloatArray(64_000), 32_000)
        val built = PocketTts.generationConfig(prompt, 1f, 5, 0.3f, 1)

        assertEquals(32_000, built.referenceSampleRate)
        assertEquals(64_000, built.referenceAudio?.size)
    }
}
