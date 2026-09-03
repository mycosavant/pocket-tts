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
    private var capabilityWatch: AutoCloseable? = null

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

        showCapability(Glass.capability(this))

        // The panel reports which branch it drew on every frame. Without this
        // the capture can decline silently and the screen looks identical to
        // one whose sliders are not wired up at all - which is exactly how the
        // sibling-backdrop bug survived a release.
        binding.previewPanel.onDrawModeChanged = { mode -> showDrawMode(mode) }
        showDrawMode(binding.previewPanel.lastDraw)

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

    override fun onStart() {
        super.onStart()
        // Followed rather than read once: battery saver and the developer
        // option both take cross-window blur away and give it back, and a line
        // that answered from whenever the screen opened would be stale in a way
        // nobody could see.
        capabilityWatch = Glass.followCapability(this) { showCapability(it) }
        showCapability(Glass.capability(this))
    }

    override fun onStop() {
        super.onStop()
        capabilityWatch?.close()
        capabilityWatch = null
    }

    private fun showCapability(capability: Glass.Capability) {
        binding.capability.text = getString(
            when (capability) {
                Glass.Capability.CROSS_WINDOW -> R.string.capability_cross_window
                Glass.Capability.IN_APP_ONLY -> R.string.capability_in_app_only
                Glass.Capability.NONE -> R.string.capability_none
            },
        )
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

        // The backdrop stays sharp. The panel blurs only the slice behind
        // itself, which is what the real overlay does and what the CSS
        // equivalent, backdrop-filter, means.
        binding.previewPanel.backdrop = binding.previewBackdrop
        binding.previewPanel.configure(
            alpha = settings.glassAlpha,
            blurDp = settings.glassBlurDp,
            dim = settings.glassDim,
            cornerDp = settings.glassCornerDp,
            surface = Glass.surfaceColour(this),
            outline = Glass.outlineColour(this),
        )
    }

    private fun showDrawMode(mode: GlassPanelView.DrawMode) {
        binding.drawMode.text = getString(
            R.string.draw_mode,
            getString(
                when (mode) {
                    GlassPanelView.DrawMode.BLURRED -> R.string.draw_blurred
                    GlassPanelView.DrawMode.BELOW_API_31 -> R.string.draw_below_api_31
                    GlassPanelView.DrawMode.SOFTWARE_CANVAS -> R.string.draw_software_canvas
                    GlassPanelView.DrawMode.NO_BLUR_RADIUS -> R.string.draw_no_blur_radius
                    GlassPanelView.DrawMode.NO_BACKDROP -> R.string.draw_no_backdrop
                    GlassPanelView.DrawMode.BACKDROP_UNRELATED -> R.string.draw_backdrop_unrelated
                    GlassPanelView.DrawMode.NOT_LAID_OUT -> R.string.draw_not_laid_out
                    GlassPanelView.DrawMode.CAPTURE_FAILED -> R.string.draw_capture_failed
                },
            ),
        )
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
}
