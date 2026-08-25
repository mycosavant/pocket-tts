package org.pockettts.android.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.pockettts.android.engine.Settings
import java.util.function.Consumer

/**
 * The frosted-glass treatment, and an honest account of when it can exist.
 *
 * There are two ways to blur on Android, and which one is available depends
 * entirely on what is *behind* the panel:
 *
 * - Behind another app: only `Window.setBackgroundBlurRadius` can do it, because
 *   an app may not read another app's pixels. That API is gated on a device
 *   opt-in (`ro.surface_flinger.supports_background_blur`), and Samsung does not
 *   set it - their developer forum confirms cross-window blur is unsupported
 *   across One UI, S24 Ultra included. On those devices this effect is simply
 *   unavailable and no radius or alpha will conjure it.
 *
 * - Behind our own content: capturing the backdrop into a `RenderNode` and
 *   blurring that works on any API 31+ device with no vendor opt-in. This is
 *   the same technique Haze uses on Android; Haze is Compose-only, so the
 *   technique is borrowed rather than the dependency.
 *
 * [applyToWindow] takes the first path. [GlassPanelView] takes the second, and
 * is the one that produces a real `backdrop-filter` - blur confined to the
 * panel's own bounds rather than smeared across the whole screen. Both read
 * their numbers from [Settings] so they can be tuned on the device.
 */
object Glass {

    private const val TAG = "Glass"
    private const val STROKE_ALPHA = 90

    /** Which blur, if any, is actually running. Surfaced in the tuning screen. */
    enum class Capability {
        /** Cross-window blur is on: a panel over another app really frosts it. */
        CROSS_WINDOW,

        /** Only our own views can blur. Panels over other apps get a scrim. */
        IN_APP_ONLY,

        /** Below API 31. No blur of any kind. */
        NONE,
    }

    fun capability(context: Context): Capability = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> Capability.NONE
        crossWindowEnabled(context) -> Capability.CROSS_WINDOW
        else -> Capability.IN_APP_ONLY
    }

    private fun crossWindowEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        }.getOrDefault(false)
    }

    /**
     * Dresses a floating window as a glass panel.
     *
     * When cross-window blur is unavailable the same surface is drawn much more
     * opaque: a barely-tinted panel with nothing blurred behind it is just
     * unreadable text sitting on someone else's app.
     */
    fun applyToWindow(activity: Activity) {
        val window = activity.window
        val settings = Settings(activity)
        val density = activity.resources.displayMetrics.density

        val surface = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorSurface,
            Color.DKGRAY,
        )
        val outline = MaterialColors.getColor(
            activity,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.GRAY,
        )

        val background = MaterialShapeDrawable(
            ShapeAppearanceModel.builder()
                .setAllCornerSizes(settings.glassCornerDp * density)
                .build(),
        ).apply {
            setStroke(density, ColorUtils.setAlphaComponent(outline, STROKE_ALPHA))
        }
        window.setBackgroundDrawable(background)

        fun paint(blurred: Boolean) {
            val alpha = if (blurred) settings.glassAlpha else OPAQUE_FALLBACK_ALPHA
            background.fillColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(surface, (alpha * 255).toInt()),
            )
            Log.i(TAG, if (blurred) "cross-window blur active" else "no cross-window blur; opaque panel")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            paint(blurred = false)
            return
        }
        runCatching { enableWindowBlur(activity, window, settings, density, ::paint) }
            .onFailure { paint(blurred = false) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun enableWindowBlur(
        activity: Activity,
        window: Window,
        settings: Settings,
        density: Float,
        paint: (Boolean) -> Unit,
    ) {
        val windowManager = activity.getSystemService(WindowManager::class.java)
        val radius = (settings.glassBlurDp * density).toInt()

        window.addFlags(LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply {
            blurBehindRadius = radius
            dimAmount = settings.glassDim
        }
        window.setBackgroundBlurRadius(radius)

        paint(windowManager.isCrossWindowBlurEnabled)

        // Blur can be switched off under us - battery saver does it - so track
        // it rather than sampling once and leaving the panel see-through.
        val listener = Consumer<Boolean> { enabled -> activity.runOnUiThread { paint(enabled) } }
        windowManager.addCrossWindowBlurEnabledListener(listener)
        window.decorView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    windowManager.removeCrossWindowBlurEnabledListener(listener)
                }
            },
        )
    }

    /**
     * Theme colours for a glass panel.
     *
     * Read at draw time rather than baked into a drawable so the panel follows
     * the wallpaper-derived dynamic palette.
     */
    fun surfaceColour(context: Context): Int = MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorSurface,
        Color.DKGRAY,
    )

    fun outlineColour(context: Context): Int = MaterialColors.getColor(
        context,
        com.google.android.material.R.attr.colorOutlineVariant,
        Color.GRAY,
    )

    /** Alpha used when nothing is blurred behind the panel and it must carry legibility alone. */
    const val OPAQUE_FALLBACK_ALPHA = 0.94f
}
