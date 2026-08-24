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
