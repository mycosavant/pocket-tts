package org.pockettts.android.ui

import android.content.Intent
import android.os.Build
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pockettts.android.R
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Boots every activity for real.
 *
 * These exist because of a specific escape: `Theme.PocketTts` inherited from
 * `Theme.Material3.DayNight`, which hands the activity a decor action bar, while
 * two screens also called `setSupportActionBar`. That combination throws
 * `IllegalStateException` the instant the activity is created, so opening the
 * voice picker or the scratchpad closed the app. It compiled, it packaged, it
 * passed every test - and it could not survive a single tap.
 *
 * A theme, a manifest entry and a layout are as capable of crashing an activity
 * as any function is, and none of them are type-checked. So each screen gets
 * driven through its real lifecycle here.
 *
 * Reaching RESUMED is not enough on its own. Views validate themselves during
 * layout, and `setup()` alone never lays anything out - which is how a Material
 * `Slider` handed an off-grid value (`170f/255f` against a `0.01` step) sailed
 * through these tests and then threw `IllegalStateException` from
 * `onSizeChanged` the moment the appearance screen was opened. [layOut] forces
 * a real measure and layout pass so that class of failure surfaces here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ActivityLaunchTest {

    /**
     * Forces a measure and layout pass at a realistic size, so views that
     * validate their configuration while sizing actually do it.
     */
    private fun layOut(activity: android.app.Activity) {
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2400)
    }

    @Test
    fun `appearance screen survives layout with the stored glass values`() {
        Robolectric.buildActivity(AppearanceActivity::class.java).setup().use { controller ->
            // The sliders validate here, not at construction.
            layOut(controller.get())
            assertFalse(controller.get().isFinishing)
        }
    }

    @Test
    fun `main activity reaches resumed state`() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            layOut(controller.get())
            assertNotNull(controller.get().findViewById<Toolbar>(R.id.toolbar))
        }
    }

    @Test
    fun `voice picker reaches resumed state`() {
        Robolectric.buildActivity(VoicePickerActivity::class.java).setup().use { controller ->
            layOut(controller.get())
            assertNotNull(controller.get())
        }
    }

    @Test
    fun `scratchpad reaches resumed state`() {
        Robolectric.buildActivity(ScratchpadActivity::class.java).setup().use { controller ->
            layOut(controller.get())
            assertNotNull(controller.get())
        }
    }

    @Test
    fun `read aloud activity survives the glass treatment`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            ReadAloudActivity::class.java,
        ).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "Hello world.")
        }
        Robolectric.buildActivity(ReadAloudActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            layOut(activity)
            // Blur is best-effort, but the surface behind the controls is not:
            // without a window background the panel is invisible over the host
            // app, which is exactly what it looked like before.
            assertNotNull("read-aloud window has no background", activity.window.decorView.background)
            assertFalse(activity.isFinishing)
        }
    }

    /**
     * The theme must not supply an action bar, or `setSupportActionBar` throws.
     * Asserting on the attribute catches a future parent-theme change directly,
     * rather than leaving it to whichever screen happens to be opened first.
     */
    @Test
    fun `app theme does not supply a decor action bar`() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val attrs = intArrayOf(androidx.appcompat.R.attr.windowActionBar)
            val typed = activity.theme.obtainStyledAttributes(attrs)
            try {
                assertFalse(
                    "Theme.PocketTts must be a NoActionBar theme",
                    typed.getBoolean(0, true),
                )
            } finally {
                typed.recycle()
            }
        }
    }

    @Test
    fun `read aloud with no text does not linger`() {
        val intent = Intent(
            ApplicationProvider.getApplicationContext(),
            ReadAloudActivity::class.java,
        ).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "   ")
        }
        Robolectric.buildActivity(ReadAloudActivity::class.java, intent).create().use { controller ->
            assertTrue(controller.get().isFinishing)
        }
    }
}
