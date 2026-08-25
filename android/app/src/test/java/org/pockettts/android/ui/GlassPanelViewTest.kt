package org.pockettts.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the parts of the glass panel that do not need a GPU to be wrong.
 *
 * Robolectric draws onto a software canvas, so the `RenderNode` path is skipped
 * and the fallback runs - which is exactly the branch worth pinning here, since
 * a panel that throws while drawing takes the screen down just as thoroughly as
 * one that throws while laying out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class GlassPanelViewTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun panelInFrame(): Pair<GlassPanelView, TextView> {
        val frame = FrameLayout(context)
        val backdrop = TextView(context).apply { text = "behind the panel" }
        val panel = GlassPanelView(context)
        frame.addView(backdrop)
        frame.addView(panel)
        panel.addView(TextView(context).apply { text = "on the panel" })

        frame.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(600, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, 600, 400)
        return panel to backdrop
    }

    private fun draw(view: android.view.View) {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
    }

    @Test
    fun `draws without a backdrop set`() {
        val (panel, _) = panelInFrame()
        panel.configure(alpha = 0.5f, blurDp = 20f, cornerDp = 28f, surface = Color.DKGRAY, outline = Color.GRAY)
        draw(panel)
    }

    @Test
    fun `draws with a sibling backdrop on a software canvas`() {
        val (panel, backdrop) = panelInFrame()
        panel.backdrop = backdrop
        panel.configure(alpha = 0.5f, blurDp = 20f, cornerDp = 28f, surface = Color.DKGRAY, outline = Color.GRAY)
        draw(panel)
    }

    @Test
    fun `draws at the extremes of every slider`() {
        val (panel, backdrop) = panelInFrame()
        panel.backdrop = backdrop
        listOf(0f to 0f, 1f to 80f).forEach { (alpha, blur) ->
            panel.configure(alpha = alpha, blurDp = blur, cornerDp = 0f, surface = Color.DKGRAY, outline = Color.GRAY)
            draw(panel)
        }
    }

    @Test
    fun `refuses a backdrop that contains the panel`() {
        // Capturing an ancestor would draw the panel into its own backdrop, and
        // that recursion is a stack overflow rather than a visual glitch.
        val (panel, _) = panelInFrame()
        val parent = panel.parent as FrameLayout
        assertThrows(IllegalArgumentException::class.java) { panel.backdrop = parent }
    }

    @Test
    fun `accepts a null backdrop`() {
        val (panel, backdrop) = panelInFrame()
        panel.backdrop = backdrop
        panel.backdrop = null
        draw(panel)
    }
}
