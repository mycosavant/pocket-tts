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
 *  3. draw the node clipped to the panel's rounded rect, then the dim, then the
 *     tint, then the hairline - each of which is one layer of the glass.
 *
 * The capture is padded by the blur radius on every side. Without that the
 * kernel samples past the edge of what was recorded and the panel's border
 * smears into a pale halo.
 *
 * Every frame records why it drew what it drew, in [lastDraw]. That is not
 * decoration: the capture has several ways to decline quietly, and on a
 * sideloaded phone a log line is somewhere nobody can read it. A panel that
 * silently fell back to a flat tint is indistinguishable from settings that do
 * not work, which is exactly the confusion this field exists to end.
 */
class GlassPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** What the last frame actually did, and if it was not the blur, why not. */
    enum class DrawMode {
        /** The backdrop was captured and blurred. */
        BLURRED,

        /** No [RenderEffect] before Android 12. */
        BELOW_API_31,

        /** Drawing into a bitmap or a print job; [RenderNode] needs the GPU. */
        SOFTWARE_CANVAS,

        /** The blur radius is zero, so there is nothing to do. */
        NO_BLUR_RADIUS,

        /** No [backdrop] was assigned. */
        NO_BACKDROP,

        /** Panel and backdrop are in different view hierarchies. */
        BACKDROP_UNRELATED,

        /** Panel or backdrop has no size yet. */
        NOT_LAID_OUT,

        /** The capture threw; see the log for what. */
        CAPTURE_FAILED,
    }

    /**
     * The view whose pixels are blurred. Normally a sibling: the panel must sit
     * outside the backdrop, or capturing it would draw the panel into its own
     * backdrop and recurse.
     */
    var backdrop: View? = null
        set(value) {
            require(value == null || (!isAncestor(value) && !isDescendant(value))) {
                "backdrop must not contain the panel or be contained by it, or capturing it recurses"
            }
            field = value
            invalidate()
        }

    /**
     * What the capture is painted onto before the backdrop is drawn into it.
     *
     * A backdrop with no background of its own - an ordinary layout, which is
     * most of them - records as its content on transparency. Blurring that and
     * compositing it over the screen puts a faint blurred ghost *on top of* the
     * original, which is still there, still sharp, showing straight through the
     * gaps. The panel then looks exactly like one whose blur is not working,
     * and only the dim and the tint appear to do anything.
     *
     * So the capture starts opaque. The window's own background is the right
     * colour: it is what the backdrop is sitting on, and what the eye expects
     * to see behind the text being frosted.
     */
    var backdropBase: Int = Color.TRANSPARENT
        set(value) {
            field = value
            invalidate()
        }

    /** What the last frame drew. Changes are reported via [onDrawModeChanged]. */
    var lastDraw: DrawMode = DrawMode.NO_BACKDROP
        private set

    /**
     * Notified when [lastDraw] changes. Delivered by [post] rather than inline,
     * because a listener that touches the view tree during a draw pass would be
     * modifying a hierarchy that is mid-traversal.
     */
    var onDrawModeChanged: ((DrawMode) -> Unit)? = null

    private var blurRadiusPx = 0f
    private var cornerRadiusPx = 0f
    private var tint = Color.TRANSPARENT
    private var dimColour = Color.TRANSPARENT
    private var strokeColour = Color.TRANSPARENT

    private val node by lazy(LazyThreadSafetyMode.NONE) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) RenderNode("glass") else null
    }
    private val clip = Path()
    private val bounds = RectF()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Scratch for the per-frame offset calculation; never escapes a draw. */
    private val offset = FloatArray(2)
    private val panelInRoot = FloatArray(2)
    private val backdropInRoot = FloatArray(2)

    /**
     * The backdrop moving under the panel has to redraw it, otherwise the glass
     * shows a stale capture from wherever the content used to be.
     */
    private val onScroll = ViewTreeObserver.OnScrollChangedListener { invalidate() }

    fun configure(
        alpha: Float,
        blurDp: Float,
        dim: Float,
        cornerDp: Float,
        surface: Int,
        outline: Int,
        base: Int = Glass.windowBackground(context),
    ) {
        backdropBase = base
        blurRadiusPx = dp(blurDp)
        cornerRadiusPx = dp(cornerDp)
        tint = ColorUtils.setAlphaComponent(surface, (alpha.coerceIn(0f, 1f) * 255).toInt())
        dimColour = ColorUtils.setAlphaComponent(Color.BLACK, (dim.coerceIn(0f, 1f) * 255).toInt())
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

    override fun dispatchDraw(canvas: Canvas) {
        // Rebuilt every frame from the current size rather than cached in
        // onSizeChanged: the corner radius is a live setting and can change
        // without the size doing so, and a configure() that lands before the
        // first layout would otherwise leave an empty clip and draw nothing.
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clip)
        val mode = drawBackdrop(canvas)
        // Dim first, then tint: both sit over whatever is behind, whether that
        // is the blurred capture above or the live content showing through.
        fill(canvas, dimColour)
        fill(canvas, tint)
        canvas.restore()

        report(mode)
        drawHairline(canvas)
        super.dispatchDraw(canvas)
    }

    private fun drawBackdrop(canvas: Canvas): DrawMode {
        // Ordered so the reason reported is the most specific one: how this
        // panel is configured comes before what the device or the canvas can
        // do, because the first is something the reader can act on.
        if (blurRadiusPx <= 0f) return DrawMode.NO_BLUR_RADIUS
        val source = backdrop ?: return DrawMode.NO_BACKDROP
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return DrawMode.BELOW_API_31
        val target = node ?: return DrawMode.BELOW_API_31
        if (width == 0 || height == 0 || source.width == 0 || source.height == 0) {
            return DrawMode.NOT_LAID_OUT
        }
        if (!backdropOffset(source)) return DrawMode.BACKDROP_UNRELATED
        // RenderNode recording needs a hardware canvas. Robolectric, a bitmap
        // capture and the print pipeline all hand us a software one.
        if (!canvas.isHardwareAccelerated) return DrawMode.SOFTWARE_CANVAS

        return runCatching {
            captureAndDraw(canvas, source, target, offset[0], offset[1])
            DrawMode.BLURRED
        }.getOrElse {
            Log.w(TAG, "Backdrop capture failed; falling back to a flat tint", it)
            DrawMode.CAPTURE_FAILED
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun captureAndDraw(
        canvas: Canvas,
        source: View,
        target: RenderNode,
        offsetX: Float,
        offsetY: Float,
    ) {
        // Padding the capture by the blur radius gives the kernel real pixels to
        // sample at the panel's edges instead of clamping on empty space.
        val pad = blurRadiusPx.toInt().coerceAtLeast(1)
        val captureWidth = width + pad * 2
        val captureHeight = height + pad * 2

        target.setPosition(0, 0, captureWidth, captureHeight)
        target.setRenderEffect(
            RenderEffect.createBlurEffect(blurRadiusPx, blurRadiusPx, Shader.TileMode.CLAMP),
        )

        val recording = target.beginRecording(captureWidth, captureHeight)
        try {
            // Before anything else, so the blur has opaque material to work on
            // and the result covers the live content rather than ghosting over
            // it. See backdropBase.
            recording.drawColor(backdropBase)
            // Shift the source so the slice behind this panel lands at the
            // node's origin, with the pad exposed on every side.
            recording.translate(pad - offsetX, pad - offsetY)
            source.draw(recording)
        } finally {
            target.endRecording()
        }

        canvas.save()
        canvas.translate(-pad.toFloat(), -pad.toFloat())
        canvas.drawRenderNode(target)
        canvas.restore()
    }

    private fun fill(canvas: Canvas, colour: Int) {
        if (Color.alpha(colour) == 0) return
        fillPaint.color = colour
        canvas.drawRect(bounds, fillPaint)
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

    private fun report(mode: DrawMode) {
        if (mode == lastDraw) return
        lastDraw = mode
        val listener = onDrawModeChanged ?: return
        post { listener(mode) }
    }

    /**
     * Writes this panel's top-left, in [source]'s own coordinates, into [offset].
     *
     * The backdrop is a sibling, not an ancestor - the panel has to stay outside
     * it or capturing it recurses - so walking up the parent chain looking for
     * the backdrop never finds it. Both views are instead located relative to
     * the root they share, and the difference is taken. An earlier version did
     * walk the parent chain, which meant this always failed and every panel fell
     * back to a flat tint, no matter where the appearance sliders sat.
     *
     * @return false when the two views are not in the same hierarchy.
     */
    internal fun backdropOffset(source: View): Boolean {
        val panelRoot = locationInRoot(this, panelInRoot)
        val sourceRoot = locationInRoot(source, backdropInRoot)
        if (panelRoot !== sourceRoot) return false
        offset[0] = panelInRoot[0] - backdropInRoot[0]
        offset[1] = panelInRoot[1] - backdropInRoot[1]
        return true
    }

    /** Exposed for tests; the values are only meaningful straight after a call. */
    internal val lastOffset: FloatArray get() = offset

    /**
     * Writes [view]'s top-left in the coordinates of its root into [out], and
     * returns that root.
     *
     * `getLocationInWindow` would do this, but it returns (0, 0) for a view with
     * no attach info, which silently turns "not attached yet" into "exactly on
     * top of each other" - a wrong answer rather than no answer.
     */
    private fun locationInRoot(view: View, out: FloatArray): View {
        var x = 0f
        var y = 0f
        var current: View = view
        while (true) {
            x += current.left + current.translationX
            y += current.top + current.translationY
            val parent = current.parent as? ViewGroup ?: break
            x -= parent.scrollX
            y -= parent.scrollY
            current = parent
        }
        out[0] = x
        out[1] = y
        return current
    }

    private fun isAncestor(candidate: View): Boolean {
        var parent = this.parent
        while (parent != null) {
            if (parent === candidate) return true
            parent = (parent as? View)?.parent
        }
        return false
    }

    private fun isDescendant(candidate: View): Boolean {
        var parent = candidate.parent
        while (parent != null) {
            if (parent === this) return true
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
    }
}
