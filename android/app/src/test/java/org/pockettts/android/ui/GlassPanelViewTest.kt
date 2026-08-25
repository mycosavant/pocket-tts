package org.pockettts.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the parts of the glass panel that do not need a GPU to be wrong.
 *
 * The bug these were rewritten for: the panel located itself inside the
 * backdrop by walking *up* its own parent chain, which only works if the
 * backdrop is an ancestor. It never is - the panel has to stay outside the
 * backdrop or capturing it would recurse - so the walk always ran off the top of
 * the hierarchy, the capture always declined, and every panel fell back to a
 * flat tint no matter where the appearance sliders sat.
 *
 * The previous version of this file could not have caught that. It asserted only
 * that drawing did not throw, and Robolectric's software canvas made the panel
 * bail before it ever reached the geometry. So the two things asserted here are
 * the two things that were missing: where the panel thinks it is, and what
 * colour it actually put on the canvas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class GlassPanelViewTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Records what was filled, rather than reading pixels back.
     *
     * The assertion that matters is "the panel painted at the alpha it was
     * given", and a colour int says that exactly, where a sampled pixel says it
     * through two blends and a tolerance.
     */
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val fills = mutableListOf<Int>()

        override fun drawRect(rect: RectF, paint: Paint) {
            fills += paint.color
            super.drawRect(rect, paint)
        }
    }

    private data class Fixture(val panel: GlassPanelView, val backdrop: View, val frame: FrameLayout)

    /** A backdrop and a panel side by side, which is how both real layouts do it. */
    private fun siblings(): Fixture {
        val frame = FrameLayout(context)
        val backdrop = TextView(context).apply { text = "behind the panel" }
        val panel = GlassPanelView(context)
        frame.addView(backdrop)
        frame.addView(panel)

        frame.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, 600, 400)
        backdrop.layout(0, 0, 600, 400)
        panel.layout(40, 90, 560, 310)
        return Fixture(panel, backdrop, frame)
    }

    private fun draw(view: View): RecordingCanvas {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        return RecordingCanvas(bitmap).also { view.draw(it) }
    }

    private fun GlassPanelView.configure(alpha: Float = 0.5f, blurDp: Float = 20f, dim: Float = 0f) =
        configure(
            alpha = alpha,
            blurDp = blurDp,
            dim = dim,
            cornerDp = 28f,
            surface = Color.DKGRAY,
            outline = Color.GRAY,
        )

    @Test
    fun `locates itself inside a sibling backdrop`() {
        // The whole defect in one assertion: an ancestor-walk returns nothing
        // here, because the backdrop is beside the panel rather than above it.
        val (panel, backdrop, _) = siblings()
        assertTrue(panel.backdropOffset(backdrop))
        assertEquals(40f, panel.lastOffset[0], 0f)
        assertEquals(90f, panel.lastOffset[1], 0f)
    }

    @Test
    fun `locates itself against a backdrop nested deeper than it is`() {
        val frame = FrameLayout(context)
        val inner = FrameLayout(context)
        val backdrop = TextView(context)
        val panel = GlassPanelView(context)
        inner.addView(backdrop)
        frame.addView(inner)
        frame.addView(panel)

        frame.layout(0, 0, 600, 400)
        inner.layout(10, 20, 610, 420)
        backdrop.layout(0, 0, 600, 400)
        panel.layout(40, 90, 560, 310)

        assertTrue(panel.backdropOffset(backdrop))
        assertEquals(30f, panel.lastOffset[0], 0f)
        assertEquals(70f, panel.lastOffset[1], 0f)
    }

    @Test
    fun `accounts for a scrolled container between the two`() {
        val frame = FrameLayout(context)
        val scroller = FrameLayout(context)
        val backdrop = TextView(context)
        val panel = GlassPanelView(context)
        scroller.addView(backdrop)
        frame.addView(scroller)
        frame.addView(panel)

        frame.layout(0, 0, 600, 400)
        scroller.layout(0, 0, 600, 400)
        backdrop.layout(0, 0, 600, 1200)
        panel.layout(0, 100, 600, 300)
        scroller.scrollTo(0, 250)

        // The backdrop has moved up by the scroll, so the slice under the panel
        // is 250px further down its content than the panel's own position.
        assertTrue(panel.backdropOffset(backdrop))
        assertEquals(350f, panel.lastOffset[1], 0f)
    }

    @Test
    fun `declines a backdrop from another hierarchy`() {
        val fixture = siblings()
        assertFalse(fixture.panel.backdropOffset(TextView(context)))
    }

    @Test
    fun `reports that it fell back, and why`() {
        val fixture = siblings()

        fixture.panel.configure(blurDp = 0f)
        draw(fixture.panel)
        assertEquals(GlassPanelView.DrawMode.NO_BLUR_RADIUS, fixture.panel.lastDraw)

        fixture.panel.configure(blurDp = 20f)
        draw(fixture.panel)
        assertEquals(GlassPanelView.DrawMode.NO_BACKDROP, fixture.panel.lastDraw)

        fixture.panel.backdrop = fixture.backdrop
        draw(fixture.panel)
        // Robolectric hands out a software canvas, so the capture cannot run -
        // but everything ahead of it did, which is the point of the ordering.
        assertEquals(GlassPanelView.DrawMode.SOFTWARE_CANVAS, fixture.panel.lastDraw)
    }

    @Test
    fun `reports a backdrop it cannot locate`() {
        val fixture = siblings()
        val orphan = TextView(context).apply { layout(0, 0, 100, 100) }
        fixture.panel.backdrop = orphan
        fixture.panel.configure()
        draw(fixture.panel)
        assertEquals(GlassPanelView.DrawMode.BACKDROP_UNRELATED, fixture.panel.lastDraw)
    }

    @Test
    fun `paints the fallback at the opacity it was given`() {
        // The reported symptom - "the appearance settings do nothing" - was this
        // exactly: the fallback ignored the configured alpha and painted at a
        // fixed 242, so every slider position produced the same panel.
        val fixture = siblings()
        fixture.panel.backdrop = fixture.backdrop

        listOf(0.2f, 0.67f, 0.9f).forEach { alpha ->
            fixture.panel.configure(alpha = alpha)
            val fills = draw(fixture.panel).fills
            assertEquals(
                "alpha $alpha should reach the canvas",
                (alpha * 255).toInt(),
                Color.alpha(fills.first()),
            )
        }
    }

    @Test
    fun `paints dim under the tint, and only when asked`() {
        val fixture = siblings()
        fixture.panel.backdrop = fixture.backdrop

        fixture.panel.configure(dim = 0f)
        assertEquals(1, draw(fixture.panel).fills.size)

        fixture.panel.configure(dim = 0.4f)
        val fills = draw(fixture.panel).fills
        assertEquals(2, fills.size)
        assertEquals((0.4f * 255).toInt(), Color.alpha(fills[0]))
        assertEquals(0, Color.red(fills[0]) or Color.green(fills[0]) or Color.blue(fills[0]))
    }

    @Test
    fun `draws at the extremes of every slider`() {
        val fixture = siblings()
        fixture.panel.backdrop = fixture.backdrop
        listOf(0f to 0f, 1f to 80f).forEach { (alpha, blur) ->
            fixture.panel.configure(
                alpha = alpha,
                blurDp = blur,
                dim = 1f,
                cornerDp = 0f,
                surface = Color.DKGRAY,
                outline = Color.GRAY,
            )
            draw(fixture.panel)
        }
    }

    @Test
    fun `draws before it has ever been laid out`() {
        // configure() can land before the first layout pass; an empty clip there
        // would mean a panel that draws nothing at all on its first frame.
        val panel = GlassPanelView(context)
        panel.configure()
        draw(panel)
        assertEquals(GlassPanelView.DrawMode.NO_BACKDROP, panel.lastDraw)
    }

    @Test
    fun `refuses a backdrop that contains the panel`() {
        // Capturing an ancestor would draw the panel into its own backdrop, and
        // that recursion is a stack overflow rather than a visual glitch.
        val fixture = siblings()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.panel.backdrop = fixture.frame
        }
    }

    @Test
    fun `refuses a backdrop the panel contains`() {
        val fixture = siblings()
        val child = TextView(context)
        fixture.panel.addView(child)
        assertThrows(IllegalArgumentException::class.java) { fixture.panel.backdrop = child }
    }

    @Test
    fun `accepts a null backdrop`() {
        val fixture = siblings()
        fixture.panel.backdrop = fixture.backdrop
        fixture.panel.backdrop = null
        fixture.panel.configure()
        draw(fixture.panel)
    }
}
