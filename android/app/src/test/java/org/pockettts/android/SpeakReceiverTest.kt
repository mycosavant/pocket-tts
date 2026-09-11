package org.pockettts.android

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pockettts.android.engine.EngineTurn
import org.pockettts.android.player.FakeEngine
import org.pockettts.android.player.FakeSink
import org.pockettts.android.player.Reader
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The headless entry point, which is the one nobody can see fail.
 *
 * Every other way into [Reader] has a window attached: something went wrong is
 * a toast, an error line, a sheet that closed too early. This one is a phone in
 * a pocket that either speaks or does not, so the decisions it makes about what
 * to read - and, more often, what not to - have to be assertable here or they
 * are not checkable anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SpeakReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = FakeEngine()
    private val engines = FakeEngine.Factory(engine)
    private val receiver = SpeakReceiver()

    /** Two sentences, one chunk: TextChunker's target is 200 characters. */
    private val passage = "One first thing. Two second thing."

    @Before
    fun setUp() {
        EngineTurn.resetForTesting()
        Reader.resetForTesting()
        Reader.engines = engines
        Reader.sinks = FakeSink.Factory()
    }

    @After
    fun tearDown() {
        engine.gate?.complete(Unit)
        Reader.resetForTesting()
    }

    // --- what it reads ------------------------------------------------------

    @Test
    fun `the text extra is read aloud`() = runBlocking {
        val utterance = reading(receiver.handle(context, intentOf(text = "Receiver check.")))
        awaitFor(utterance, "finished") { it is Reader.State.Finished }
        assertEquals(listOf("Receiver check."), engine.spoken)
    }

    @Test
    fun `an agent read is told apart from a selection`() = runBlocking {
        // The trace and the metrics are the only account of a read nobody
        // watched, and "Selection" in them points at a text-selection toolbar
        // that was never involved.
        val utterance = reading(receiver.handle(context, intentOf(text = passage)))
        val state = awaitFor(utterance, "speaking") { it is Reader.State.Speaking }
        assertEquals(Reader.Source.Agent, state.source)
    }

    @Test
    fun `a path is read from disk`() = runBlocking {
        val file = write("From the file.")
        val utterance = reading(receiver.handle(context, intentOf(path = file.path)))
        awaitFor(utterance, "finished") { it is Reader.State.Finished }
        assertEquals(listOf("From the file."), engine.spoken)
    }

    @Test
    fun `a path wins over a text extra`() = runBlocking {
        // A caller that sends both is a caller whose text did not fit. The
        // extra that did not fit is the one to ignore - reading it would read
        // the truncated half of something.
        val file = write("From the file.")
        val intent = intentOf(text = "From the extra.", path = file.path)
        val utterance = reading(receiver.handle(context, intent))
        awaitFor(utterance, "finished") { it is Reader.State.Finished }
        assertEquals(listOf("From the file."), engine.spoken)
    }

    @Test
    fun `markdown is stripped by the reader, not by the caller`() = runBlocking {
        // Not a formatting preference: it is the guarantee that an agent's
        // reply sounds the same here as it does pasted into the scratchpad,
        // including the next time MarkdownSpeech is improved.
        val utterance = reading(receiver.handle(context, intentOf(text = "## Heading")))
        awaitFor(utterance, "finished") { it is Reader.State.Finished }
        assertEquals(listOf("Heading."), engine.spoken)
    }

    // --- what it refuses ----------------------------------------------------

    @Test
    fun `blank text is a no-op rather than an empty read`() = runBlocking {
        // The distinction that matters: an empty read still takes the engine,
        // starts a foreground service, claims audio focus, puts a notification
        // up - and silences whatever was already playing - to say nothing.
        val outcome = receiver.handle(context, intentOf(text = "   \n  "))
        assertEquals(SpeakReceiver.Outcome.Ignored("nothing to read"), outcome)
        assertEquals(Reader.State.Idle, Reader.state.value)
    }

    @Test
    fun `a broadcast with no extras at all is a no-op`() = runBlocking {
        val outcome = receiver.handle(context, intentOf())
        assertTrue("expected Ignored, got $outcome", outcome is SpeakReceiver.Outcome.Ignored)
        assertEquals(Reader.State.Idle, Reader.state.value)
    }

    @Test
    fun `a blank broadcast does not silence the read in progress`() = runBlocking {
        engine.gate = CompletableDeferred()
        val utterance = reading(receiver.handle(context, intentOf(text = passage)))
        awaitFor(utterance, "speaking") { it is Reader.State.Speaking }

        receiver.handle(context, intentOf(text = ""))

        engine.gate?.complete(Unit)
        // A blank read would have taken the reader over, and a handover
        // publishes no terminal state - so this wait is the assertion.
        awaitFor(utterance, "finished") { it is Reader.State.Finished }
        assertEquals(listOf(passage), engine.spoken)
    }

    @Test
    fun `text past the inline cap is refused rather than truncated`() = runBlocking {
        val outcome = receiver.handle(
            context,
            intentOf(text = "x".repeat(SpeakReceiver.MAX_INLINE_BYTES + 1)),
        )
        assertTrue("expected Ignored, got $outcome", outcome is SpeakReceiver.Outcome.Ignored)
        assertTrue(
            "the refusal should say what to do instead: $outcome",
            (outcome as SpeakReceiver.Outcome.Ignored).reason.contains("path"),
        )
        assertEquals(Reader.State.Idle, Reader.state.value)
    }

    @Test
    fun `the cap is measured in bytes, not characters`() = runBlocking {
        // Two bytes each in UTF-8, so half the cap in characters is all of it
        // in the units the sending script is counting.
        val text = "é".repeat(SpeakReceiver.MAX_INLINE_BYTES / 2 + 1)
        assertTrue("the test text is short enough to pass a length check", text.length < SpeakReceiver.MAX_INLINE_BYTES)
        val outcome = receiver.handle(context, intentOf(text = text))
        assertTrue("expected Ignored, got $outcome", outcome is SpeakReceiver.Outcome.Ignored)
    }

    @Test
    fun `a path is the way past the inline cap`() = runBlocking {
        val long = "Sentence. ".repeat(SpeakReceiver.MAX_INLINE_BYTES / 10 + 1)
        reading(receiver.handle(context, intentOf(path = write(long).path)))
        awaitUntil("the file reached the engine") { engine.spoken.isNotEmpty() }
    }

    @Test
    fun `a path that is not there is a no-op`() = runBlocking {
        val missing = File(temporaryDirectory(), "no-such-file.txt")
        val outcome = receiver.handle(context, intentOf(path = missing.path))
        assertTrue("expected Ignored, got $outcome", outcome is SpeakReceiver.Outcome.Ignored)
        assertEquals(Reader.State.Idle, Reader.state.value)
    }

    @Test
    fun `a file past the file cap is refused`() = runBlocking {
        // Read with this app's own permissions, so the size cannot be bounded
        // by the caller's good intentions.
        val huge = write("x".repeat((SpeakReceiver.MAX_FILE_BYTES + 1).toInt()))
        val outcome = receiver.handle(context, intentOf(path = huge.path))
        assertTrue("expected Ignored, got $outcome", outcome is SpeakReceiver.Outcome.Ignored)
        assertEquals(Reader.State.Idle, Reader.state.value)
    }

    // --- append -------------------------------------------------------------

    @Test
    fun `appending queues behind the read in progress`() = runBlocking {
        engine.gate = CompletableDeferred()
        val first = reading(receiver.handle(context, intentOf(text = passage)))
        awaitFor(first, "speaking") { it is Reader.State.Speaking }

        val second = reading(receiver.handle(context, intentOf(text = "Three third thing.", append = true)))
        awaitUntil("the second read is queued") { Reader.queued == 1 }
        // Still the first read's: queuing must not hand the reader over.
        assertEquals(first, Reader.state.value.utterance)

        engine.gate?.complete(Unit)
        awaitFor(second, "finished") { it is Reader.State.Finished }
        assertEquals(listOf(passage, "Three third thing."), engine.spoken)
    }

    @Test
    fun `not appending replaces the read in progress`() = runBlocking {
        val held = CompletableDeferred<Unit>()
        engine.gate = held
        val first = reading(receiver.handle(context, intentOf(text = passage)))
        awaitFor(first, "speaking") { it is Reader.State.Speaking }
        // Waited for rather than assumed: the gate is read when synthesis is
        // called, one line after Speaking is published, so clearing it any
        // earlier lets the first read run to the end ungated.
        awaitUntil("the first read reached the engine") { engine.spoken.size == 1 }
        engine.gate = null

        val second = reading(receiver.handle(context, intentOf(text = "Replacement.")))
        held.complete(Unit)

        awaitFor(second, "finished") { it is Reader.State.Finished }
        assertEquals(listOf(passage, "Replacement."), engine.spoken)
    }

    @Test
    fun `appending with nothing playing simply reads`() = runBlocking {
        val utterance = reading(receiver.handle(context, intentOf(text = "Nothing to wait for.", append = true)))
        awaitFor(utterance, "finished") { it is Reader.State.Finished }
        assertEquals(listOf("Nothing to wait for."), engine.spoken)
    }

    @Test
    fun `a plain read drops what was queued behind the one it replaces`() = runBlocking {
        // The queue belongs to the passage being abandoned. Carrying it over
        // would have a replacement read followed by the tail of the thing it
        // was sent to replace.
        engine.gate = CompletableDeferred()
        val first = reading(receiver.handle(context, intentOf(text = passage)))
        awaitFor(first, "speaking") { it is Reader.State.Speaking }
        receiver.handle(context, intentOf(text = "Queued behind it.", append = true))
        awaitUntil("the queued read is waiting") { Reader.queued == 1 }

        awaitUntil("the first read reached the engine") { engine.spoken.size == 1 }
        val replacement = reading(receiver.handle(context, intentOf(text = "Replacement.")))
        engine.gate?.complete(Unit)

        awaitFor(replacement, "finished") { it is Reader.State.Finished }
        awaitUntil("the reader went idle") { !Reader.isActive }
        assertEquals(listOf(passage, "Replacement."), engine.spoken)
    }

    // --- helpers ------------------------------------------------------------

    private fun intentOf(
        text: String? = null,
        path: String? = null,
        append: Boolean? = null,
    ): Intent = Intent(context, SpeakReceiver::class.java).apply {
        text?.let { putExtra(SpeakReceiver.EXTRA_TEXT, it) }
        path?.let { putExtra(SpeakReceiver.EXTRA_PATH, it) }
        append?.let { putExtra(SpeakReceiver.EXTRA_APPEND, it) }
    }

    private fun reading(outcome: SpeakReceiver.Outcome): Long {
        assertTrue("expected a read, got $outcome", outcome is SpeakReceiver.Outcome.Reading)
        return (outcome as SpeakReceiver.Outcome.Reading).utterance
    }

    private fun temporaryDirectory(): File = context.cacheDir

    private fun write(text: String): File =
        File.createTempFile("speak", ".md", temporaryDirectory()).apply {
            writeText(text)
            deleteOnExit()
        }

    /** Waits for a state belonging to [utterance] specifically. */
    private suspend fun awaitFor(
        utterance: Long,
        what: String,
        predicate: (Reader.State) -> Boolean,
    ): Reader.State = try {
        withTimeout(TIMEOUT_MS) {
            Reader.state.first { it.utterance == utterance && predicate(it) }
        }
    } catch (timeout: TimeoutCancellationException) {
        error("never reached $what for utterance $utterance (at ${Reader.state.value})")
    }

    private suspend fun awaitUntil(what: String, check: () -> Boolean) {
        try {
            withTimeout(TIMEOUT_MS) {
                while (!check()) delay(POLL_MS)
            }
        } catch (timeout: TimeoutCancellationException) {
            fail("never became true: $what")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
