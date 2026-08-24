package org.pockettts.android.ui

import kotlin.math.roundToInt

/**
 * Formats glass values so a number read off a slider can be typed into source.
 *
 * The tuning screen exists so values get chosen on a real device rather than
 * guessed here, which only works if what the slider reports is the same number
 * the code wants. A label reading "62%" is useless when the constant is a float
 * and the drawable wants a 0-255 alpha, so every label carries both.
 */
object GlassValues {

    /** e.g. `0.667f  ·  alpha 170  ·  67%` */
    fun alphaLabel(fraction: Float): String {
        val clamped = fraction.coerceIn(0f, 1f)
        return "${format(clamped)}f  ·  alpha ${(clamped * 255).roundToInt()}  ·  ${(clamped * 100).roundToInt()}%"
    }

    /** e.g. `48dp  ·  144px @3.0x` */
    fun blurLabel(dp: Float, density: Float): String =
        "${format(dp)}dp  ·  ${(dp * density).roundToInt()}px @${format(density)}x"

    /** e.g. `0.35f` */
    fun dimLabel(fraction: Float): String = "${format(fraction.coerceIn(0f, 1f))}f"

    /** e.g. `28dp` */
    fun cornerLabel(dp: Float): String = "${format(dp)}dp"

    /**
     * The whole set as pasteable Kotlin, so a good-looking panel can be turned
     * into defaults without transcribing four numbers by hand.
     */
    fun asKotlin(alpha: Float, blurDp: Float, dim: Float, cornerDp: Float): String = buildString {
        appendLine("const val DEFAULT_GLASS_ALPHA = ${format(alpha.coerceIn(0f, 1f))}f")
        appendLine("const val DEFAULT_GLASS_BLUR_DP = ${format(blurDp)}f")
        appendLine("const val DEFAULT_GLASS_DIM = ${format(dim.coerceIn(0f, 1f))}f")
        append("const val DEFAULT_GLASS_CORNER_DP = ${format(cornerDp)}f")
    }

    /**
     * Rounds [value] onto the slider's step grid.
     *
     * Material's `Slider` rejects any value that is not exactly `valueFrom`
     * plus a whole number of steps, and it validates during layout - so an
     * off-grid value does not misbehave, it throws `IllegalStateException` and
     * takes the screen down as it opens. `170f / 255f` is 0.6666667, which is
     * not a multiple of 0.01, and that is precisely how it happened here.
     *
     * The arithmetic runs in Double and narrows once at the end: doing it in
     * Float accumulates error that puts the result back off-grid, which
     * Material catches because it validates via `BigDecimal(Float.toString(v))`.
     *
     * Stored settings can hold any float - an older default, a hand-edited
     * preference - so every value handed to a slider goes through this.
     */
    fun snapToStep(value: Float, from: Float, to: Float, step: Float): Float {
        if (step <= 0f) return value.coerceIn(from, to)
        val steps = Math.round((value.toDouble() - from) / step)
        return (from + steps * step.toDouble()).toFloat().coerceIn(from, to)
    }

    /** Trims trailing zeros so labels read `48dp`, not `48.000dp`. */
    private fun format(value: Float): String {
        val rounded = (value * 1000).roundToInt() / 1000f
        return if (rounded == rounded.toInt().toFloat()) {
            rounded.toInt().toString()
        } else {
            rounded.toString().trimEnd('0').trimEnd('.')
        }
    }
}
