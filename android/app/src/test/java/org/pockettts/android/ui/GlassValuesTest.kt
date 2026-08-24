package org.pockettts.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.MathContext

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

    /**
     * Material's own rule, reproduced from `BaseSlider.isMultipleOfStepSize`.
     *
     * It validates during layout and throws rather than rounding, so an
     * off-grid value does not look slightly wrong - it destroys the screen as
     * it opens. Asserting against the real rule is the only way to know a value
     * is safe without a device.
     */
    private fun isOnGrid(value: Float, from: Float, step: Float): Boolean {
        val multiple = BigDecimal(value.toString())
            .subtract(BigDecimal(from.toString()))
            .divide(BigDecimal(step.toString()), MathContext.DECIMAL64)
            .toDouble()
        return Math.abs(Math.round(multiple) - multiple) < 1e-4
    }

    @Test
    fun `the AOSP-derived alpha default is on the grid`() {
        // 170f/255f is 0.6666667, which is not a multiple of 0.01. Shipping it
        // straight to the slider threw IllegalStateException during layout and
        // crashed the appearance screen the instant it was opened.
        val snapped = GlassValues.snapToStep(170f / 255f, 0f, 1f, 0.01f)
        assertTrue("$snapped is off the 0.01 grid", isOnGrid(snapped, 0f, 0.01f))
        assertEquals(0.67f, snapped, 1e-6f)
    }

    @Test
    fun `snapping lands on the grid across the whole range`() {
        var value = 0f
        while (value <= 1f) {
            val snapped = GlassValues.snapToStep(value, 0f, 1f, 0.01f)
            assertTrue("$value snapped to $snapped, off grid", isOnGrid(snapped, 0f, 0.01f))
            value += 0.0037f
        }
    }

    @Test
    fun `snapping respects whole-number steps`() {
        assertEquals(48f, GlassValues.snapToStep(47.6f, 0f, 80f, 1f), 1e-6f)
        assertEquals(28f, GlassValues.snapToStep(28.4f, 0f, 48f, 1f), 1e-6f)
    }

    @Test
    fun `snapping clamps to the slider bounds`() {
        assertEquals(0f, GlassValues.snapToStep(-5f, 0f, 1f, 0.01f), 1e-6f)
        assertEquals(1f, GlassValues.snapToStep(9f, 0f, 1f, 0.01f), 1e-6f)
        assertEquals(80f, GlassValues.snapToStep(120f, 0f, 80f, 1f), 1e-6f)
    }

    @Test
    fun `a zero step is passed through rather than dividing by zero`() {
        assertEquals(0.42f, GlassValues.snapToStep(0.42f, 0f, 1f, 0f), 1e-6f)
    }
}
