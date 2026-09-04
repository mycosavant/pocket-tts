package org.pockettts.android.ui

import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.core.graphics.Insets as GraphicsInsets

/**
 * What the bottom of a screen has to get out of the way of.
 *
 * A window laid out edge to edge is not resized when the keyboard opens -
 * `adjustResize` stopped meaning that - it is handed the keyboard's size in the
 * insets and expected to deal with it. Nothing warns you. The layout keeps its
 * full height, the keyboard covers whatever was against the bottom, and in the
 * scratchpad that is the Speak button: the only way to reach the control was to
 * dismiss the keyboard you were typing with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class InsetsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun bottomBar(initialPadding: Int = 0): View =
        FrameLayout(context).apply {
            setPadding(0, 0, 0, initialPadding)
            Insets.apply(bottom = this)
        }

    private fun dispatch(view: View, navBar: Int = 0, ime: Int = 0) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), GraphicsInsets.of(0, 0, 0, navBar))
            .setInsets(WindowInsetsCompat.Type.ime(), GraphicsInsets.of(0, 0, 0, ime))
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    @Test
    fun `the keyboard lifts what sits against the bottom`() {
        val view = bottomBar()
        dispatch(view, navBar = 48, ime = 900)

        assertEquals("the keyboard covered the controls", 900, view.paddingBottom)
    }

    @Test
    fun `the navigation bar alone still pays its way`() {
        val view = bottomBar()
        dispatch(view, navBar = 48)

        assertEquals(48, view.paddingBottom)
    }

    @Test
    fun `the keyboard's inset is not added to the bar underneath it`() {
        // The keyboard is drawn over the navigation bar, so its inset already
        // contains it. Adding them would leave a gap the height of the bar.
        val view = bottomBar()
        dispatch(view, navBar = 48, ime = 900)

        assertEquals(900, view.paddingBottom)
    }

    @Test
    fun `padding the layout asked for is kept`() {
        val view = bottomBar(initialPadding = 12)
        dispatch(view, navBar = 48, ime = 900)

        assertEquals(912, view.paddingBottom)
    }

    @Test
    fun `closing the keyboard puts it back`() {
        val view = bottomBar()
        dispatch(view, navBar = 48, ime = 900)
        dispatch(view, navBar = 48, ime = 0)

        assertEquals("the padding was applied cumulatively", 48, view.paddingBottom)
    }
}
