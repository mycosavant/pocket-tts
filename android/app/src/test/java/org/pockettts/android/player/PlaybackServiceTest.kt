package org.pockettts.android.player

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the start-up sequence of the playback service.
 *
 * The service is started by whichever screen asked for speech, which happens
 * *before* the reader has had a chance to leave Idle. An earlier version
 * treated that first Idle as "reading finished" and called `stopSelf()`
 * synchronously, one line after `startForeground()` - a promote-then-drop
 * inside a single `onStartCommand`, which is exactly the shape Android's
 * foreground-service checks reject.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class PlaybackServiceTest {

    @Test
    fun `does not stop itself on the idle state that precedes reading`() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        controller.startCommand(0, 0)

        assertFalse(
            "service shut itself down before reading ever began",
            shadowOf(controller.get()).isStoppedBySelf,
        )
        controller.destroy()
    }

    @Test
    fun `survives being started twice`() {
        // Selecting new text while something is already being read starts the
        // service again; the second start must not tear down the first.
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        controller.startCommand(0, 0)
        controller.startCommand(0, 1)

        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }
}
