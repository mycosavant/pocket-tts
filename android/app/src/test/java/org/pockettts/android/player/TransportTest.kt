package org.pockettts.android.player

import android.content.Context
import android.media.session.PlaybackState
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.pockettts.android.engine.EngineTurn
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The media session, which is how this app is controlled when it is not on
 * screen - which, for something you start and then put in your pocket, is most
 * of the time it is running.
 *
 * Two things can be wrong here and neither shows up in the app itself. The
 * mapping from a transport command to a reader call is one; the other is the
 * published playback state, because that is what the framework consults to
 * decide whether a headset's single button means play or pause. A session that
 * claims to be playing while paused answers that button with "pause" and the
 * button appears to do nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class TransportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val engine = FakeEngine()
    private val sinks = FakeSink.Factory()

    private lateinit var transport: Transport

    @Before
    fun setUp() {
        EngineTurn.resetForTesting()
        Reader.resetForTesting()
        Reader.engines = FakeEngine.Factory(engine)
        Reader.sinks = sinks
        transport = Transport(context)
    }

    @After
    fun tearDown() {
        transport.release()
        Reader.resetForTesting()
    }

    private suspend fun awaitFor(
        utterance: Long,
        what: String,
        predicate: (Reader.State) -> Boolean,
    ): Reader.State = withTimeout(TIMEOUT_MS) {
        Reader.state.first { it.utterance == utterance && predicate(it) }
    }.also { assertTrue("never reached $what (at $it)", predicate(it)) }

    /**
     * Starts a read and holds it mid-chunk, so the commands have something to
     * act on.
     *
     * Paragraphs rather than sentences: the chunker packs up to 200 characters
     * into a chunk, so three short sentences are one chunk and there is nothing
     * to skip between.
     */
    private suspend fun startHeldRead(): Long {
        engine.gate = CompletableDeferred()
        val id = Reader.speak(
            context,
            "First paragraph here.\n\nSecond paragraph here.\n\nThird paragraph here.",
            treatAsMarkdown = false,
            source = Reader.Source.Scratchpad,
        )
        awaitFor(id, "speaking") { it is Reader.State.Speaking }
        // Speaking is published when the reader starts *working on* a chunk,
        // one line before it asks the engine for it. So the state alone does
        // not mean the engine has been called, and a test that reads
        // engine.spoken at that moment sometimes reads an empty list - which
        // is how this failed in CI while passing here.
        awaitUntil("the first chunk reached the engine") { engine.spoken.isNotEmpty() }
        return id
    }

    /** Polls [check], for what is true of the world rather than of one state. */
    private suspend fun awaitUntil(what: String, check: () -> Boolean) {
        try {
            withTimeout(TIMEOUT_MS) {
                while (!check()) delay(POLL_MS)
            }
        } catch (timeout: TimeoutCancellationException) {
            fail("never became true: $what")
        }
    }

    @Test
    fun `pause from the headset pauses the read, and play resumes it`() = runBlocking {
        val id = startHeldRead()

        transport.callback.onPause()
        awaitFor(id, "paused") { it is Reader.State.Speaking && it.paused }

        transport.callback.onPlay()
        awaitFor(id, "resumed") { it is Reader.State.Speaking && !it.paused }
        Unit
    }

    @Test
    fun `skip to next abandons the chunk being read rather than restarting`() = runBlocking {
        val id = startHeldRead()
        val before = engine.spoken.toList()

        transport.callback.onSkipToNext()
        // The reader has to have moved on before the gate opens, or the chunk
        // being abandoned races the cancellation for the right to emit audio.
        awaitFor(id, "the next chunk") { it is Reader.State.Speaking && it.chunkIndex >= 1 }
        engine.gate?.complete(Unit)
        awaitFor(id, "finished") { it is Reader.State.Finished }

        // Same utterance throughout: skipping must not re-chunk or reload, which
        // is the whole reason the reader keeps the utterance around.
        assertEquals("skip started a new utterance", id, Reader.state.value.utterance)
        assertEquals(
            "the chunk that was playing was read again, so this was a restart",
            1,
            engine.spoken.count { it == before.last() },
        )
        // What distinguishes a skip from simply letting the read continue: the
        // chunk that was playing produces no sound. Counting chunks spoken
        // cannot tell those apart, because both end up speaking the rest.
        assertEquals(
            "the abandoned chunk was still played",
            (engine.spoken.size - 1) * (engine.sampleRate / 10),
            sinks.created.sumOf { it.samplesWritten },
        )
    }

    @Test
    fun `stop from the system ends the read`() = runBlocking {
        val id = startHeldRead()

        transport.callback.onStop()
        engine.gate?.complete(Unit)
        // Stopped, specifically, and not merely terminal: a read left alone
        // reaches the end by itself, so "it ended" is not evidence that
        // anything was asked of it.
        awaitFor(id, "stopped") { it is Reader.State.Stopped }
        Unit
    }

    @Test
    fun `a paused read is published as paused, not as playing`() {
        // The framework turns one headset button into onPlay or onPause by
        // reading this. Getting it wrong makes the button a no-op every other
        // press, which reads as a broken button rather than a wrong state.
        assertEquals(
            PlaybackState.STATE_PAUSED,
            Transport.playbackStateOf(speaking(paused = true, audible = true)),
        )
        assertEquals(
            PlaybackState.STATE_PLAYING,
            Transport.playbackStateOf(speaking(paused = false, audible = true)),
        )
    }

    @Test
    fun `composing is buffering rather than playing`() {
        // The model is silent for the first seconds of a read. A player showing
        // "playing" through that silence is describing a fault that is not
        // there; buffering is what the wait actually is.
        assertEquals(
            PlaybackState.STATE_BUFFERING,
            Transport.playbackStateOf(speaking(paused = false, audible = false)),
        )
        assertEquals(
            PlaybackState.STATE_BUFFERING,
            Transport.playbackStateOf(
                Reader.State.Preparing(1, Reader.Source.Scratchpad, fraction = 0.5f),
            ),
        )
    }

    @Test
    fun `nothing being read is published as stopped`() {
        assertEquals(PlaybackState.STATE_STOPPED, Transport.playbackStateOf(Reader.State.Idle))
        assertEquals(
            PlaybackState.STATE_STOPPED,
            Transport.playbackStateOf(Reader.State.Finished(1, Reader.Source.Scratchpad)),
        )
        assertEquals(
            PlaybackState.STATE_STOPPED,
            Transport.playbackStateOf(Reader.State.Failed(1, Reader.Source.Scratchpad, "no")),
        )
    }

    @Test
    fun `the title is the first real line of what is being read`() {
        assertEquals(
            "The second paragraph",
            Transport.titleOf("\n  \nThe second paragraph\nand more after it."),
        )
        assertEquals("", Transport.titleOf("   \n\n "))
    }

    @Test
    fun `a long first line is cut at a word, not mid-word`() {
        val line = "Supercalifragilistic expialidocious ".repeat(10)
        val title = Transport.titleOf(line)

        assertTrue("not truncated at all", title.length < line.length)
        assertTrue("did not mark the truncation", title.endsWith("…"))
        assertTrue(
            "cut in the middle of a word: $title",
            line.startsWith(title.dropLast(1) + " ") || line == title.dropLast(1),
        )
    }

    private fun speaking(paused: Boolean, audible: Boolean) = Reader.State.Speaking(
        utterance = 1,
        source = Reader.Source.Scratchpad,
        chunkIndex = 0,
        chunkCount = 3,
        start = 0,
        end = 10,
        paused = paused,
        audible = audible,
    )

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 5L
    }
}
