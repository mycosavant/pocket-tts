package org.pockettts.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import org.pockettts.android.R
import org.pockettts.android.databinding.ActivityAppearanceBinding
import org.pockettts.android.engine.Settings

/**
 * Dials in the glass panel on the device it will actually run on.
 *
 * How this looks depends on the wallpaper-derived palette, the display density,
 * and whether the vendor supports blur at all - none of which can be judged from
 * source. So the values are sliders rather than constants, the preview uses the
 * same code path as the real panel, and every label reports the number in the
 * form the source wants it.
 */
class AppearanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceBinding
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAppearanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        Insets.apply(top = binding.appBar, bottom = binding.content)

        binding.capability.text = getString(
            when (Glass.capability(this)) {
                Glass.Capability.CROSS_WINDOW -> R.string.capability_cross_window
                Glass.Capability.IN_APP_ONLY -> R.string.capability_in_app_only
                Glass.Capability.NONE -> R.string.capability_none
            },
        )

        applyToSliders()

        onSlide(binding.alphaSlider) { settings.glassAlpha = it }
        onSlide(binding.blurSlider) { settings.glassBlurDp = it }
        onSlide(binding.dimSlider) { settings.glassDim = it }
        onSlide(binding.cornerSlider) { settings.glassCornerDp = it }

        binding.copyButton.setOnClickListener { copyAsKotlin() }
        binding.resetButton.setOnClickListener {
            settings.resetGlass()
            applyToSliders()
            render()
        }

        render()
    }

    /**
     * Every value goes through the step grid on the way in. A slider handed an
     * off-grid value throws during layout rather than rounding, so a stored
     * setting from another build would otherwise crash this screen on open.
     */
    private fun applyToSliders() {
        listOf(
            binding.alphaSlider to settings.glassAlpha,
            binding.blurSlider to settings.glassBlurDp,
            binding.dimSlider to settings.glassDim,
            binding.cornerSlider to settings.glassCornerDp,
        ).forEach { (slider, value) ->
            slider.value = GlassValues.snapToStep(
                value,
                slider.valueFrom,
                slider.valueTo,
                slider.stepSize,
            )
        }
    }

    private fun onSlide(slider: Slider, store: (Float) -> Unit) {
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            store(value)
            render()
        }
    }

    private fun render() {
        val density = resources.displayMetrics.density
        binding.alphaLabel.text =
            getString(R.string.label_opacity, GlassValues.alphaLabel(settings.glassAlpha))
        binding.blurLabel.text =
            getString(R.string.label_blur, GlassValues.blurLabel(settings.glassBlurDp, density))
        binding.dimLabel.text =
            getString(R.string.label_dim, GlassValues.dimLabel(settings.glassDim))
        binding.cornerLabel.text =
            getString(R.string.label_corner, GlassValues.cornerLabel(settings.glassCornerDp))

        // Exactly what the overlay does: blur the backdrop, float an unblurred
        // panel over it. Blurring the panel's own parent would blur the panel.
        Glass.blur(binding.previewBackdrop, settings.glassBlurDp)
        binding.previewPanel.background =
            Glass.panelBackground(this, settings.glassAlpha, settings.glassCornerDp)
        binding.previewBackdrop.alpha = 1f - settings.glassDim * DIM_PREVIEW_SCALE
    }

    private fun copyAsKotlin() {
        val snippet = GlassValues.asKotlin(
            alpha = settings.glassAlpha,
            blurDp = settings.glassBlurDp,
            dim = settings.glassDim,
            cornerDp = settings.glassCornerDp,
        )
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.appearance), snippet))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /**
         * Dim applies to a whole window at runtime; in a 220dp preview the full
         * amount would read as a black box, so it is shown proportionally.
         */
        const val DIM_PREVIEW_SCALE = 0.6f
    }
}
