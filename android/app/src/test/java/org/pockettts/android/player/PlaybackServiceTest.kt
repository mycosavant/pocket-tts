package org.pockettts.android.player

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pockettts.android.engine.EngineTurn
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
 *
 * It now stays for as long as [Reader.busy] says, which a read holds from the
 * moment it is asked for. The state could not say it: between two queued reads,
 * and just after a speak, it is an earlier read's ending.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class PlaybackServiceTest {

    /** Runs [body] with a fake engine whose reads wait on [FakeEngine.gate]. */
    private fun withFakeReader(body: suspend (Context, FakeEngine) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        EngineTurn.resetForTesting()
        Reader.resetForTesting()
        val engine = FakeEngine().apply { gate = CompletableDeferred() }
        Reader.engines = FakeEngine.Factory(engine)
        Reader.sinks = FakeSink.Factory()
        try {
            body(context, engine)
        } finally {
            engine.gate?.complete(Unit)
            Reader.resetForTesting()
            Reader.engines = PocketTtsEngine
            Reader.sinks = StreamingPlayer
        }
    }

    /** Lets the main looper run until [check] holds, for what the service hears on it. */
    private fun idleUntil(what: String, check: () -> Boolean) {
        var tries = 0
        while (!check()) {
            if (tries++ > 500) throw AssertionError("never became true: $what")
            shadowOf(Looper.getMainLooper()).idle()
            if (!check()) Thread.sleep(10)
        }
    }

    @Test
    fun `does not stop itself before the read it was started for begins`() = withFakeReader { context, _ ->
        // The order every caller uses: speak, then start the service. The
        // read has not published anything yet, and the state is still the
        // last read's.
        Reader.speak(context, "One sentence.", treatAsMarkdown = false, source = Reader.Source.Selection)
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        controller.startCommand(0, 0)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "service shut itself down before reading ever began",
            shadowOf(controller.get()).isStoppedBySelf,
        )
        controller.destroy()
    }

    @Test
    fun `does not stop on the ending left over from the last read`() = withFakeReader { context, engine ->
        // Seen on an emulator four times out of four: a selection read
        // started just after another finished played with no service, no
        // notification and no focus, because the service met the old
        // Finished in the state and took it for its own read's.
        engine.gate?.complete(Unit)
        val earlier = Reader.speak(context, "Earlier.", treatAsMarkdown = false, source = Reader.Source.Selection)
        withTimeout(5_000) { Reader.state.first { it.utterance == earlier && it.isTerminal } }
        idleUntil("the earlier read let go") { !Reader.isBusy }

        engine.gate = CompletableDeferred()
        Reader.speak(context, "Later.", treatAsMarkdown = false, source = Reader.Source.Selection)
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        controller.startCommand(0, 0)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `stays up from one queued read to the next`() = withFakeReader { context, engine ->
        val first = Reader.speak(context, "First.", treatAsMarkdown = false, source = Reader.Source.Agent)
        val second = Reader.enqueue(context, "Second.", treatAsMarkdown = false, source = Reader.Source.Agent)
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        controller.startCommand(0, 0)
        withTimeout(5_000) { Reader.state.first { it.utterance == first && it is Reader.State.Speaking } }

        // The second read waits on a gate of its own, or it could finish
        // before this test looked for it. Swapped only once the first read is
        // parked on the first gate, or the first read takes the second.
        withTimeout(5_000) { while (engine.spoken.isEmpty()) delay(5) }
        delay(20)
        val firstGate = engine.gate!!
        engine.gate = CompletableDeferred()
        firstGate.complete(Unit)
        withTimeout(5_000) { Reader.state.first { it.utterance == second && it is Reader.State.Speaking } }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("stopped on the first read's ending", shadowOf(controller.get()).isStoppedBySelf)

        engine.gate?.complete(Unit)
        idleUntil("stopped once both reads were over") { shadowOf(controller.get()).isStoppedBySelf }
        controller.destroy()
    }

    @Test
    fun `started with nothing to read, it does not linger in the foreground`() {
        Reader.resetForTesting()
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        controller.startCommand(0, 0)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `stop is one of the three controls in the collapsed notification`() {
        // Before Android 13 the notification's own actions are what the shade
        // and the lock screen draw, and collapsed they draw three. Stop used
        // to be the fourth, and so was never there without expanding - which
        // the lock screen does not offer.
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        controller.startCommand(0, 0)
        val notification = shadowOf(controller.get()).lastForegroundNotification
        val titles = notification.actions.map { it.title.toString() }
        val compact = notification.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS)!!.toList()

        assertEquals(3, compact.size)
        val shown = compact.map { titles[it] }
        assertTrue("collapsed controls were $shown", "Stop" in shown)
        assertTrue("pause must stay in the collapsed controls, got $shown", "Pause" in shown)
        controller.destroy()
    }

    @Test
    fun `survives being started twice`() = withFakeReader { context, _ ->
        // Selecting new text while something is already being read starts the
        // service again; the second start must not tear down the first.
        Reader.speak(context, "First.", treatAsMarkdown = false, source = Reader.Source.Selection)
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        controller.startCommand(0, 0)
        Reader.speak(context, "Second.", treatAsMarkdown = false, source = Reader.Source.Selection)
        controller.startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `a failed read leaves a notification that outlasts the service`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        EngineTurn.resetForTesting()
        Reader.resetForTesting()
        Reader.engines = FakeEngine.Factory(FakeEngine(failOn = "Boom."))
        Reader.sinks = FakeSink.Factory()
        try {
            val controller = Robolectric.buildService(PlaybackService::class.java).create()
            val id = Reader.speak(context, "Boom.", treatAsMarkdown = false, source = Reader.Source.Agent)
            controller.startCommand(0, 0)
            withTimeout(5_000) { Reader.state.first { it.utterance == id && it is Reader.State.Failed } }
            // The service hears of it on the main looper, posted from the
            // reader's thread, so possibly just after this test did.
            val manager = context.getSystemService(NotificationManager::class.java)
            var failure: Notification? = null
            var tries = 0
            while (failure == null && tries++ < 100) {
                shadowOf(Looper.getMainLooper()).idle()
                failure = shadowOf(manager).getNotification(NowPlaying.FAILURE_NOTIFICATION_ID)
                if (failure == null) Thread.sleep(10)
            }
            assertTrue("no failure notification", failure != null)
            assertEquals("synthesis exploded", failure!!.extras.getString(Notification.EXTRA_TEXT))
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            controller.destroy()
        } finally {
            Reader.resetForTesting()
            Reader.engines = PocketTtsEngine
            Reader.sinks = StreamingPlayer
        }
    }
}
