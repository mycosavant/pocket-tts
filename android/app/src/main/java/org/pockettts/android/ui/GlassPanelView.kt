package org.pockettts.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils

/**
 * A panel that blurs what is behind it, and only what is behind it.
 *
 * This is the `backdrop-filter: blur()` behaviour: the blur is confined to the
 * panel's own rounded bounds, and content outside those bounds stays sharp.
 *
 * An earlier version applied [RenderEffect] to the backdrop view itself, which
 * is a different effect entirely - CSS `filter: blur()` on the *sibling*. It
 * softens the whole screen and leaves the panel with no material of its own,
 * because there is nothing left for the panel to blur.
 *
 * The real thing needs the backdrop captured and blurred per-frame:
 *
 *  1. record the backdrop view into a [RenderNode], offset so the slice sitting
 *     behind this panel lands at the panel's origin;
 *  2. hang a blur [RenderEffect] on that node, so only the captured slice blurs;
 *  3. draw the node clipped to the panel's rounded rect, then the tint, then the
 *     hairline - each of which is one layer of the glass.
 *
 * The capture is padded by the blur radius on every side. Without that the
 * kernel samples past the edge of what was recorded and the panel's border
 * smears into a pale halo.
 */
class GlassPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * The view whose pixels are blurred. Must not be an ancestor of this panel:
     * capturing a parent would recurse into capturing the panel itself.
     */
    var backdrop: View? = null
        set(value) {
            require(value == null || !isAncestor(value)) {
                "backdrop must not contain the panel, or capturing it recurses"
            }
            field = value
            invalidate()
        }

    private var blurRadiusPx = 0f
    private var cornerRadiusPx = 0f
    private var tint = Color.TRANSPARENT
    private var strokeColour = Color.TRANSPARENT

    private val node by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RenderNode("glass") else null
    }
    private val clip = Path()
    private val bounds = RectF()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The backdrop moving under the panel has to redraw it, otherwise the glass
     * shows a stale capture from wherever the content used to be.
     */
    private val onScroll = ViewTreeObserver.OnScrollChangedListener { invalidate() }

    fun configure(alpha: Float, blurDp: Float, cornerDp: Float, surface: Int, outline: Int) {
        blurRadiusPx = dp(blurDp)
        cornerRadiusPx = dp(cornerDp)
        tint = ColorUtils.setAlphaComponent(surface, (alpha.coerceIn(0f, 1f) * 255).toInt())
        strokeColour = ColorUtils.setAlphaComponent(outline, STROKE_ALPHA)
        strokePaint.strokeWidth = resources.displayMetrics.density
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(onScroll)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(onScroll)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Rebuilt here rather than only in onSizeChanged, because the corner
        // radius is a live setting and can change without the size doing so.
        clip.reset()
        clip.addRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clip)
        if (!drawBackdrop(canvas)) {
            // No blur available: the tint alone has to carry legibility, so it
            // is drawn opaque rather than leaving the content showing through
            // sharp and unreadable behind the text.
            canvas.drawColor(ColorUtils.setAlphaComponent(tint, OPAQUE_ALPHA))
        }
        canvas.restore()

        drawHairline(canvas)
        super.dispatchDraw(canvas)
    }

    /** @return true when a real blurred backdrop was drawn. */
    private fun drawBackdrop(canvas: Canvas): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!canvas.isHardwareAccelerated) return false
        if (blurRadiusPx <= 0f) return false
        val source = backdrop ?: return false
        val target = node ?: return false

        return runCatching { captureAndDraw(canvas, source, target) }
            .onFailure { Log.w(TAG, "Backdrop capture failed; falling back to a solid panel", it) }
            .getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun captureAndDraw(canvas: Canvas, source: View, target: RenderNode): Boolean {
        if (width == 0 || height == 0) return false

        // Padding the capture by the blur radius gives the kernel real pixels to
        // sample at the panel's edges instead of clamping on empty space.
        val pad = blurRadiusPx.toInt().coerceAtLeast(1)
        val captureWidth = width + pad * 2
        val captureHeight = height + pad * 2

        val offset = offsetWithin(source) ?: return false

        target.setPosition(0, 0, captureWidth, captureHeight)
        target.setRenderEffect(
            RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP),
        )

        val recording = target.beginRecording(captureWidth, captureHeight)
        try {
            // Shift the source so the slice behind this panel lands at the
            // node's origin, with the pad exposed on every side.
            recording.translate(-(offset[0] - pad).toFloat(), -(offset[1] - pad).toFloat())
            source.draw(recording)
        } finally {
            target.endRecording()
        }

        canvas.save()
        canvas.translate(-pad.toFloat(), -pad.toFloat())
        canvas.drawRenderNode(target)
        canvas.restore()

        tintPaint.color = tint
        canvas.drawRect(bounds, tintPaint)
        return true
    }

    private fun drawHairline(canvas: Canvas) {
        if (Color.alpha(strokeColour) == 0) return
        strokePaint.color = strokeColour
        val inset = strokePaint.strokeWidth / 2f
        canvas.drawRoundRect(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset,
            cornerRadiusPx,
            cornerRadiusPx,
            strokePaint,
        )
    }

    /** This panel's top-left in [ancestor]'s coordinates, or null if unrelated. */
    private fun offsetWithin(ancestor: View): IntArray? {
        var x = 0
        var y = 0
        var view: View = this
        while (view !== ancestor) {
            x += view.left - view.scrollX
            y += view.top - view.scrollY
            val parent = view.parent
            if (parent !is ViewGroup) return null
            view = parent
        }
        return intArrayOf(x, y)
    }

    private fun isAncestor(candidate: View): Boolean {
        var parent = this.parent
        while (parent != null) {
            if (parent === candidate) return true
            parent = (parent as? View)?.parent
        }
        return false
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        const val TAG = "GlassPanelView"
        const val STROKE_ALPHA = 90

        /** Alpha for the tint when no blur is available behind it. */
        const val OPAQUE_ALPHA = 242
    }
}
