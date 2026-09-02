package org.pockettts.android.player

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.pockettts.android.engine.EngineTurn
import org.pockettts.android.engine.Settings
import org.robolectric.annotation.Config

/**
 * The reader's state machine, which is the part that has actually been wrong.
 *
 * Every bug these cover was invisible before the [SpeechEngine] and [AudioSink]
 * seams existed, because driving the reader meant downloading a 98 MB model and
 * opening an `AudioTrack`. The specific failure they were written for: `Idle`
 * meant both "not started" and "finished", so `stopLocked` published it on the
 * way from one utterance to the next, and three screens each kept a boolean to
 * paper over it. `StateFlow` conflates, so whether anyone saw that transitional
 * `Idle` was a race - the scratchpad overlay sometimes failed to appear on a
 * second Speak, and the foreground service sometimes stopped and immediately
 * restarted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ReaderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = FakeEngine()
    private val engines = FakeEngine.Factory(engine)
    private val sinks = FakeSink.Factory()

    private val threeSentences = "One first thing. Two second thing. Three third thing."

    @Before
    fun setUp() {
        EngineTurn.resetForTesting()
        // The reader is a process-wide singleton, so without this one test's
        // terminal state is the next test's starting state.
        Reader.resetForTesting()
        Reader.engines = engines
        Reader.sinks = sinks
    }

    @After
    fun tearDown() {
        Reader.resetForTesting()
    }

    /**
     * Waits for a state, rather than guessing how long the reader needs.
     *
     * Real time on purpose: the reader runs on its own dispatchers, so a
     * virtual-time test clock never advances and every wait times out at once.
     */
    private suspend fun await(what: String, predicate: (Reader.State) -> Boolean): Reader.State =
        withTimeout(TIMEOUT_MS) {
            Reader.state.first(predicate)
        }.also { assertTrue("never reached $what (at $it)", predicate(it)) }

    /**
     * Waits for a state belonging to [utterance] specifically.
     *
     * Without the id a wait matches the *previous* read's ending, which is
     * already in the flow - the same staleness that used to close the floating
     * window before its own read had started.
     */
    private suspend fun awaitFor(
        utterance: Long,
        what: String,
        predicate: (Reader.State) -> Boolean,
    ): Reader.State = await("$what for utterance $utterance") {
        it.utterance == utterance && predicate(it)
    }

    private suspend fun readToEnd(
        text: String = threeSentences,
        source: Reader.Source = Reader.Source.Scratchpad,
    ): Long {
        val id = Reader.speak(context, text, treatAsMarkdown = false, source = source)
        awaitFor(id, "finished") { it is Reader.State.Finished }
        return id
    }

    @Test
    fun `a finished read is distinguishable from one that never started`() = runBlocking {
        assertEquals(Reader.State.Idle, Reader.state.value)
        readToEnd()
        val state = Reader.state.value
        assertTrue("expected Finished, got $state", state is Reader.State.Finished)
        assertTrue(state.isTerminal)
    }

    @Test
    fun `handing over to a second utterance never publishes a terminal state`() = runBlocking {
        // The bug: stopLocked published Idle between utterances, so anything
        // watching for "the read is over" tore down and immediately rebuilt.
        //
        // Collected unconfined so every emission is seen. StateFlow conflates,
        // and conflation is exactly what made the original bug intermittent -
        // a test that could miss the transitional state would sometimes pass
        // against the broken code.
        // Gated so the first utterance is still speaking when the second
        // arrives; without it synthesis finishes in microseconds and the
        // handover being tested never happens.
        engine.gate = CompletableDeferred()
        val first = Reader.speak(context, threeSentences, treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        awaitFor(first, "speaking") { it is Reader.State.Speaking }

        val seen = mutableListOf<Reader.State>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            Reader.state.collect { seen += it }
        }

        val second = Reader.speak(context, "Another thing entirely.", treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        // Only once the handover has actually happened. Releasing the gate any
        // earlier lets the first read finish on its own, and then the test is
        // measuring a race rather than the handover.
        awaitFor(second, "started") { true }
        engine.gate?.complete(Unit)
        awaitFor(second, "finished") { it is Reader.State.Finished }
        watcher.cancel()

        // One terminal state, at the very end, belonging to the second read.
        // The handover contributes none.
        assertEquals(
            listOf(Reader.State.Finished(second, Reader.Source.Scratchpad)),
            seen.filter { it.isTerminal },
        )
    }

    @Test
    fun `the state carries who asked, for the whole utterance`() = runBlocking {
        engine.gate = CompletableDeferred()
        val id = Reader.speak(context, threeSentences, treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        val speaking = awaitFor(id, "speaking") { it is Reader.State.Speaking }
        engine.gate?.complete(Unit)
        assertEquals(Reader.Source.Scratchpad, speaking.source)
        awaitFor(id, "finished") { it is Reader.State.Finished }
        assertEquals(Reader.Source.Scratchpad, Reader.state.value.source)
    }

    @Test
    fun `stopping ends the read as stopped, not as finished`() = runBlocking {
        engine.gate = CompletableDeferred()
        val id = Reader.speak(context, threeSentences, treatAsMarkdown = false, source = Reader.Source.Selection)
        awaitFor(id, "speaking") { it is Reader.State.Speaking }

        Reader.stop()
        engine.gate?.complete(Unit)
        val state = awaitFor(id, "stopped") { it is Reader.State.Stopped }
        assertEquals(Reader.Source.Selection, state.source)
    }

    @Test
    fun `stopping before anything started does nothing`() = runBlocking {
        Reader.stop()
        assertEquals(Reader.State.Idle, Reader.state.value)
    }

    @Test
    fun `a synthesis failure is terminal and carries the source`() = runBlocking {
        val exploding = FakeEngine(failOn = "Boom.")
        Reader.engines = FakeEngine.Factory(exploding)
        val id = Reader.speak(context, "Boom.", treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        val state = awaitFor(id, "failed") { it is Reader.State.Failed }
        assertTrue(state.isTerminal)
        assertEquals(Reader.Source.Scratchpad, state.source)
        Reader.engines = engines
    }

    @Test
    fun `every utterance releases its sink`() = runBlocking {
        readToEnd()
        readToEnd("A second one.")
        awaitUntil("both sinks released") { sinks.created.size == 2 }
        assertEquals(2, sinks.created.size)
        assertTrue(sinks.created.all { it.released == 1 })
    }

    @Test
    fun `each sentence after the first continues the voice already speaking`() = runBlocking {
        // The reported symptom: the voice changes between sentences. Pocket TTS
        // samples a speaker in the neighbourhood of the reference prompt, and a
        // read is one call per sentence, so the same prompt gives a slightly
        // different speaker each time. Upstream names the fix as a TODO against
        // this behaviour - condition each chunk on the audio of the last one.
        val paragraphs = "First paragraph here.\n\nSecond paragraph here.\n\nThird paragraph here."
        val id = Reader.speak(context, paragraphs, treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        awaitFor(id, "finished") { it is Reader.State.Finished }

        assertEquals("expected three chunks", 3, engine.conditionedOn.size)
        assertNull("the first sentence has nothing to continue from", engine.conditionedOn[0])
        for (index in 1 until engine.conditionedOn.size) {
            assertNotNull(
                "sentence $index was drawn from the voice prompt again",
                engine.conditionedOn[index],
            )
        }
    }

    @Test
    fun `the continuation is the audio that was actually produced`() = runBlocking {
        // Not merely non-null: a prompt of the wrong length, or of silence, is
        // still a prompt, and the model would carry on regardless.
        val paragraphs = "First paragraph here.\n\nSecond paragraph here."
        val id = Reader.speak(context, paragraphs, treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        awaitFor(id, "finished") { it is Reader.State.Finished }

        // The fake engine emits a tenth of a second per chunk, and the buffer
        // holds several seconds, so the whole of the first chunk is carried.
        assertEquals(engine.sampleRate / 10, engine.conditionedOn[1]!!.size)
    }

    @Test
    fun `turning the setting off goes back to the voice prompt every time`() = runBlocking {
        Settings(context).steadyVoice = false
        val paragraphs = "First paragraph here.\n\nSecond paragraph here."
        val id = Reader.speak(context, paragraphs, treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        awaitFor(id, "finished") { it is Reader.State.Finished }

        assertTrue(
            "a chunk was conditioned on generated audio with the setting off",
            engine.conditionedOn.all { it == null },
        )
    }

    @Test
    fun `skipping does not restart the speaker`() = runBlocking {
        // The tail lives on the utterance rather than the play loop for this
        // reason: skipping moves through the text, and the voice should carry
        // on across it rather than being drawn afresh at the new position.
        val paragraphs = "First paragraph here.\n\nSecond paragraph here.\n\nThird paragraph here."
        val id = Reader.speak(context, paragraphs, treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        awaitFor(id, "finished") { it is Reader.State.Finished }
        val before = engine.conditionedOn.size

        Reader.skipBack()
        awaitUntil("the skipped sentence was re-read") { engine.conditionedOn.size > before }
        awaitFor(id, "finished after skip") { it is Reader.State.Finished }

        assertNotNull(
            "the sentence after a skip was drawn from the voice prompt again",
            engine.conditionedOn.last(),
        )
    }

    @Test
    fun `skipping back replays the sentence just read`() = runBlocking {
        val id = readToEnd()
        val beforeSkip = engine.spoken.size

        Reader.skipBack()
        awaitUntil("the skipped sentence was re-read") { engine.spoken.size > beforeSkip }
        awaitFor(id, "finished after skip") { it is Reader.State.Finished }
        // The last chunk again, and nothing before it re-synthesised.
        assertEquals(beforeSkip + 1, engine.spoken.size)
        assertEquals(engine.spoken[beforeSkip - 1], engine.spoken.last())
    }

    @Test
    fun `skipping does not reload the engine or the voice`() = runBlocking {
        // This is the whole point of retaining the utterance: skipping back a
        // sentence should cost that sentence, not a model load.
        val id = readToEnd()
        val created = engines.created
        val spoken = engine.spoken.size
        Reader.skipBack()
        awaitUntil("the skipped sentence was re-read") { engine.spoken.size > spoken }
        awaitFor(id, "finished after skip") { it is Reader.State.Finished }
        assertEquals(created, engines.created)
    }

    @Test
    fun `skipping forward past the end finishes rather than hanging`() = runBlocking {
        engine.gate = CompletableDeferred()
        val id = Reader.speak(context, "Only one sentence here.", treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        awaitFor(id, "speaking") { it is Reader.State.Speaking }

        Reader.skipForward()
        engine.gate?.complete(Unit)
        val state = awaitFor(id, "finished") { it is Reader.State.Finished }
        assertTrue(state.isTerminal)
    }

    @Test
    fun `skipping with nothing playing is ignored`() = runBlocking {
        Reader.skipForward()
        Reader.skipBack()
        assertEquals(Reader.State.Idle, Reader.state.value)
    }

    @Test
    fun `it does not claim to be reading before any sound has come out`() = runBlocking {
        // The model composes a whole sentence before emitting a sample, so
        // there are several silent seconds after work starts. Saying "Reading
        // aloud" through them is what made the wait feel broken rather than
        // merely slow.
        //
        // Asserted over the sequence of states, collected unconfined. Waiting
        // for the audible one instead would be a race: once the fake engine is
        // ungated it runs to the end in microseconds, and StateFlow conflates.
        val seen = mutableListOf<Reader.State>()
        val watcher = CoroutineScope(Dispatchers.Unconfined).launch {
            Reader.state.collect { seen += it }
        }

        engine.gate = CompletableDeferred()
        val id = Reader.speak(context, threeSentences, treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        val silent = awaitFor(id, "speaking") { it is Reader.State.Speaking }
        assertFalse(
            "claimed to be audible before writing a sample",
            (silent as Reader.State.Speaking).audible,
        )

        engine.gate?.complete(Unit)
        awaitFor(id, "finished") { it is Reader.State.Finished }
        watcher.cancel()

        val speaking = seen.filterIsInstance<Reader.State.Speaking>().filter { it.utterance == id }
        assertFalse("the first spoken state claimed to be audible", speaking.first().audible)
        assertTrue("never became audible", speaking.any { it.audible })
    }

    @Test
    fun `a read stands down when something else claims the engine`() = runBlocking {
        // The alternating-voices bug: the system engine and the in-app reader
        // both drove the model, serialised per chunk, so one passage came out
        // read alternately in two voices. Whoever asked most recently wins, and
        // the loser has to actually stop rather than wait its turn again.
        engine.gate = CompletableDeferred()
        val id = Reader.speak(context, threeSentences, treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        awaitFor(id, "speaking") { it is Reader.State.Speaking }
        val spokenBefore = engine.spoken.size

        EngineTurn.take()
        engine.gate?.complete(Unit)

        val state = awaitFor(id, "stopped") { it is Reader.State.Stopped }
        assertTrue(state.isTerminal)
        // It gave up rather than reading on to the end of the passage.
        assertTrue(
            "kept synthesising after losing the engine",
            engine.spoken.size < spokenBefore + 3,
        )
    }

    @Test
    fun `blank text finishes instead of leaving the reader hanging`() = runBlocking {
        val id = Reader.speak(context, "   ", treatAsMarkdown = false, source = Reader.Source.Scratchpad)
        awaitFor(id, "finished") { it is Reader.State.Finished }
        // Waited for rather than asserted outright: the state is published from
        // inside the job, so there is a moment after Finished where the
        // coroutine has not yet completed.
        awaitUntil("the reader went idle") { !Reader.isActive }
    }

    /** Polls [check], for the things that are true of the world rather than of one state. */
    private suspend fun awaitUntil(what: String, check: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!check()) delay(POLL_MS)
        }
        assertTrue("never became true: $what", check())
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
