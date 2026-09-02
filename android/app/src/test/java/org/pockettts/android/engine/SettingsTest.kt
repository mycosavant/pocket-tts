package org.pockettts.android.engine

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The stored values, and the range each of them is allowed to take.
 *
 * Ranges matter more here than they look: a Material slider handed a value off
 * its own step grid throws during layout rather than rounding, so a setting
 * written by one build and read by the next can take a whole screen down on
 * open. That has already happened once, to the appearance screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SettingsTest {

    private val settings = Settings(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `decode steps default to what has been shipping, not to a new guess`() {
        // Changing the default silently changes how every existing install
        // sounds. The knob exists so a device can answer the question; until it
        // does, the answer stays the one that has been in use.
        assertEquals(5, settings.decodeSteps)
        assertEquals(Settings.DEFAULT_DECODE_STEPS, settings.decodeSteps)
    }

    @Test
    fun `decode steps stay inside the slider's range`() {
        settings.decodeSteps = 99
        assertEquals(Settings.MAX_STEPS, settings.decodeSteps)

        settings.decodeSteps = 0
        assertEquals(Settings.MIN_STEPS, settings.decodeSteps)

        settings.decodeSteps = 2
        assertEquals(2, settings.decodeSteps)
    }

    @Test
    fun `one voice across sentences is on unless it is turned off`() {
        assertTrue(settings.steadyVoice)
        settings.steadyVoice = false
        assertTrue(!settings.steadyVoice)
    }

    @Test
    fun `speed stays inside the slider's range`() {
        settings.speed = 9f
        assertEquals(Settings.MAX_SPEED, settings.speed, 0f)

        settings.speed = 0f
        assertEquals(Settings.MIN_SPEED, settings.speed, 0f)
    }
}
