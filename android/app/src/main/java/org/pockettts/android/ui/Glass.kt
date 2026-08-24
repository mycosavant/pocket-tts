package org.pockettts.android.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.util.TypedValue
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
 * - Behind our own content: `RenderEffect.createBlurEffect` blurs any view we
 *   own, on any API 31+ device, with no vendor opt-in. This is the same
 *   technique Haze uses on Android; Haze is Compose-only, so the technique is
 *   borrowed rather than the dependency.
 *
 * [applyToWindow] takes the first path, [blur] the second. Both read their
 * numbers from [Settings] so they can be tuned on the device instead of guessed
 * here.
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
     * Blurs one of our own views, for an overlay drawn on top of it.
     *
     * Works on every API 31+ device regardless of vendor blur support, which is
     * what makes the in-app case succeed where the cross-app one cannot. The
     * blurred view must not contain the overlay, or it blurs itself.
     *
     * @param radiusDp blur radius; 0 removes the effect.
     */
    fun blur(view: View, radiusDp: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            radiusDp,
            view.resources.displayMetrics,
        )
        view.setRenderEffect(
            if (radius <= 0f) {
                null
            } else {
                // CLAMP repeats edge pixels outward; DECAL would fade the view's
                // borders to transparent and betray the trick at the edges.
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            },
        )
    }

    fun clearBlur(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.setRenderEffect(null)
    }

    /** Builds the rounded translucent surface used by in-app overlay panels. */
    fun panelBackground(context: Context, alpha: Float, cornerDp: Float): MaterialShapeDrawable {
        val density = context.resources.displayMetrics.density
        val surface = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.DKGRAY,
        )
        val outline = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorOutlineVariant,
            Color.GRAY,
        )
        return MaterialShapeDrawable(
            ShapeAppearanceModel.builder().setAllCornerSizes(cornerDp * density).build(),
        ).apply {
            fillColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(surface, (alpha.coerceIn(0f, 1f) * 255).toInt()),
            )
            setStroke(density, ColorUtils.setAlphaComponent(outline, STROKE_ALPHA))
        }
    }

    /** Alpha used when nothing is blurred behind the panel and it must carry legibility alone. */
    const val OPAQUE_FALLBACK_ALPHA = 0.94f
}
