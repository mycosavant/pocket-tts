package org.pockettts.android.ui

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import java.util.function.Consumer

/**
 * Gives a floating window the frosted, Mica-like surface treatment.
 *
 * The background is assembled here rather than declared as a drawable so it can
 * pull `colorSurface` and `colorOutline` from the live theme - which on Android
 * 12 and up means the user's wallpaper-derived dynamic palette - and so the
 * opacity can react to whether a real blur is actually running.
 *
 * Cross-window blur is genuinely conditional at runtime: it needs API 31, and
 * the system switches it off under battery saver, in power-save modes, and on
 * devices whose GPU cannot afford it. When it is off, a mostly-transparent panel
 * would just be unreadable text floating over someone else's app, so the same
 * surface is drawn nearly opaque instead. The shape and colour stay identical
 * either way; only the alpha moves.
 */
object Glass {

    /**
     * Alpha for the panel when a real blur is rendering behind it. Low enough
     * that the blur is doing the work rather than the fill - at higher values
     * the panel reads as flat tint and you cannot tell blur is on at all.
     */
    private const val ALPHA_BLURRED = 0.30f

    /** Alpha when it is not, where the panel has to carry legibility alone. */
    private const val ALPHA_OPAQUE = 0.92f

    private const val CORNER_RADIUS_DP = 28f

    /** Backdrop blur under the panel, the `backdrop-filter: blur()` equivalent. */
    private const val BACKGROUND_BLUR_RADIUS_DP = 20f
    private const val BEHIND_BLUR_RADIUS_DP = 20f

    /**
     * Applies the treatment to [activity]'s window and keeps it in step with the
     * system's blur setting for as long as the window is showing.
     */
    fun apply(activity: Activity) {
        val window = activity.window
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

        val radius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            CORNER_RADIUS_DP,
            activity.resources.displayMetrics,
        )
        val background = MaterialShapeDrawable(
            ShapeAppearanceModel.builder().setAllCornerSizes(radius).build(),
        ).apply {
            setStroke(density, ColorUtils.setAlphaComponent(outline, STROKE_ALPHA))
        }
        window.setBackgroundDrawable(background)

        fun paint(blurred: Boolean) {
            // Worth logging: when the system refuses blur the panel falls back
            // to nearly opaque, which looks identical to the effect simply not
            // having been implemented.
            android.util.Log.i("Glass", if (blurred) "blur active" else "blur unavailable, using opaque panel")
            val alpha = if (blurred) ALPHA_BLURRED else ALPHA_OPAQUE
            background.fillColor = android.content.res.ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(surface, (alpha * 255).toInt()),
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            paint(blurred = false)
            return
        }
        // Blur is best-effort. If anything about it is unavailable on this
        // device, an opaque panel is a worse-looking but perfectly usable
        // result - it is not worth taking the window down over.
        runCatching { enableBlur(activity, window, density, ::paint) }
            .onFailure { paint(blurred = false) }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun enableBlur(
        activity: Activity,
        window: android.view.Window,
        density: Float,
        paint: (Boolean) -> Unit,
    ) {
        val windowManager = activity.getSystemService(WindowManager::class.java)

        // Blurring what is behind the window as well as under it is what stops
        // the panel reading as a flat translucent rectangle: the surrounding app
        // softens toward the edges the way Mica and acrylic do.
        window.addFlags(LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply {
            blurBehindRadius = (BEHIND_BLUR_RADIUS_DP * density).toInt()
        }
        window.setBackgroundBlurRadius((BACKGROUND_BLUR_RADIUS_DP * density).toInt())

        paint(windowManager.isCrossWindowBlurEnabled)

        // The setting is not fixed for the life of the window - dropping into
        // battery saver turns blur off underneath us - so follow it rather than
        // sampling it once and leaving the panel see-through.
        val listener = Consumer<Boolean> { enabled ->
            activity.runOnUiThread { paint(enabled) }
        }
        windowManager.addCrossWindowBlurEnabledListener(listener)
        window.decorView.addOnAttachStateChangeListener(
            object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: android.view.View) = Unit

                override fun onViewDetachedFromWindow(v: android.view.View) {
                    windowManager.removeCrossWindowBlurEnabledListener(listener)
                }
            },
        )
    }

    private const val STROKE_ALPHA = 90
}
