package org.pockettts.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole point of the tuning screen is that a number read off a slider can be
 * typed straight into source. An off-by-one in the alpha conversion, or a label
 * that rounds differently from what gets stored, would quietly send back a value
 * that does not reproduce what was on screen.
 */
class GlassValuesTest {

    @Test
    fun `alpha label carries the float, the 0-255 alpha and the percentage`() {
        val label = GlassValues.alphaLabel(170f / 255f)
        assertTrue(label, label.contains("alpha 170"))
        assertTrue(label, label.contains("67%"))
        assertTrue(label, label.contains("f"))
    }

    @Test
    fun `alpha endpoints convert exactly`() {
        assertTrue(GlassValues.alphaLabel(0f).contains("alpha 0"))
        assertTrue(GlassValues.alphaLabel(1f).contains("alpha 255"))
    }

    @Test
    fun `alpha is clamped rather than producing an impossible channel value`() {
        assertTrue(GlassValues.alphaLabel(-0.5f).contains("alpha 0"))
        assertTrue(GlassValues.alphaLabel(2f).contains("alpha 255"))
    }

    @Test
    fun `blur label converts dp to px at the given density`() {
        val label = GlassValues.blurLabel(48f, 3f)
        assertTrue(label, label.contains("48dp"))
        assertTrue(label, label.contains("144px"))
        assertTrue(label, label.contains("@3x"))
    }

    @Test
    fun `whole numbers do not render trailing zeros`() {
        assertEquals("28dp", GlassValues.cornerLabel(28f))
        assertEquals("0.35f", GlassValues.dimLabel(0.35f))
    }

    @Test
    fun `the kotlin snippet names every constant and is pasteable`() {
        val snippet = GlassValues.asKotlin(alpha = 0.667f, blurDp = 48f, dim = 0.35f, cornerDp = 28f)
        listOf(
            "DEFAULT_GLASS_ALPHA",
            "DEFAULT_GLASS_BLUR_DP",
            "DEFAULT_GLASS_DIM",
            "DEFAULT_GLASS_CORNER_DP",
        ).forEach { assertTrue(snippet, snippet.contains(it)) }

        // Every value has to carry the f suffix, or it will not compile as a Float.
        snippet.lines().forEach { assertTrue(it, it.trimEnd().endsWith("f")) }
    }

    @Test
    fun `the snippet round-trips the values it was given`() {
        val snippet = GlassValues.asKotlin(alpha = 0.5f, blurDp = 20f, dim = 0.2f, cornerDp = 16f)
        assertTrue(snippet, snippet.contains("= 0.5f"))
        assertTrue(snippet, snippet.contains("= 20f"))
        assertTrue(snippet, snippet.contains("= 0.2f"))
        assertTrue(snippet, snippet.contains("= 16f"))
    }
}
