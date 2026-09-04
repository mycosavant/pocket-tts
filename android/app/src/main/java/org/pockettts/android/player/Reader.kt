package org.pockettts.android.player

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import org.pockettts.android.debug.Metrics
import org.pockettts.android.engine.EngineTurn
import org.pockettts.android.engine.Settings
import org.pockettts.android.speech.MarkdownSpeech
import org.pockettts.android.speech.TextChunker
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Reads a body of text aloud, one chunk at a time.
 *
 * There is one reader for the whole process: starting a new utterance stops
 * whatever was playing, which is what you want when you select a second
 * paragraph while the first is still being read.
 *
 * Everything needed to *keep* reading survives the current chunk - the chunked
 * text, the loaded voice, the engine - so skipping back a sentence costs a
 * re-synthesis of that sentence and nothing else. No re-selection, no reload.
 */
object Reader {

    private const val TAG = "Reader"

    /**
     * Who asked for this utterance.
     *
     * The reader is process-wide, so a screen that wants to show its own
     * controls has to know whether the current read is *its* read. This used to
     * be a boolean on each screen, set in the click handler and cleared when
     * the state went Idle - which broke every time the state passed through
     * Idle on its way from one utterance to the next.
     */
    enum class Source {
        /** Text selected in another app, or shared in. */
        Selection,

        /** The Speak button, or a selection inside the scratchpad. */
        Scratchpad,

        /** Another app driving the system text-to-speech engine. */
        System,
    }

    sealed interface State {

        /**
         * Which utterance this state belongs to, or 0 before there is one.
         *
         * A screen that started a read has to be able to tell that read's
         * ending from the ending of some earlier one it never asked for. The
         * floating window did not, and would close itself on a terminal state
         * left over from a previous utterance before its own had begun.
         */
        val utterance: Long

        /** Who asked for this utterance; null only before anything is asked for. */
        val source: Source?

        /** True once the utterance is over, however it ended. */
        val isTerminal: Boolean get() = false

        /**
         * Nothing has been asked for.
         *
         * Only ever the *starting* state. An utterance that ends reaches
         * [Finished], [Stopped] or [Failed] - all distinguishable from "not
         * started yet", which is the distinction three separate
         * `readingBegan`-style flags used to approximate, and get wrong every
         * time the state passed through Idle between two utterances.
         */
        data object Idle : State {
            override val utterance: Long get() = 0
            override val source: Source? get() = null
        }

        /** Model or voice is being fetched. [fraction] is -1 when size is unknown. */
        data class Preparing(
            override val utterance: Long,
            override val source: Source,
            val fraction: Float,
        ) : State

        data class Speaking(
            override val utterance: Long,
            override val source: Source,
            val chunkIndex: Int,
            val chunkCount: Int,
            /** Character range of the chunk within the speakable text. */
            val start: Int,
            val end: Int,
            val paused: Boolean,
            /**
             * Whether any sound has come out yet.
             *
             * The reader reaches this state when it starts *working on* a
             * chunk, and the model composes a whole sentence before it emits a
             * single sample. Reporting "Reading aloud" from the first moment
             * meant the app claimed to be reading through the several seconds
             * where it was silent - which is most of what the wait before the
             * first word actually feels like.
             */
            val audible: Boolean,
        ) : State

        /** Read to the end. */
        data class Finished(
            override val utterance: Long,
            override val source: Source,
        ) : State {
            override val isTerminal: Boolean get() = true
        }

        /** Ended early, by the user or by another utterance taking over. */
        data class Stopped(
            override val utterance: Long,
            override val source: Source,
        ) : State {
            override val isTerminal: Boolean get() = true
        }

        data class Failed(
            override val utterance: Long,
            override val source: Source,
            val message: String,
        ) : State {
            override val isTerminal: Boolean get() = true
        }
    }

    private val scope = CoroutineScope(SupervisorJob())

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The text currently being read, after Markdown stripping. */
    @Volatile
    var speakableText: String = ""
        private set

    /**
     * Where the spoken text came from, for a screen that wants to follow along.
     *
     * A [State.Speaking] reports offsets into [speakableText], and stripping
     * deleted syntax, so those offsets are not positions in the document the
     * user is looking at. This turns one into the other.
     */
    @Volatile
    private var sourceMap: MarkdownSpeech.Speakable? = null

    /**
     * The range of [source] currently being spoken, or null if it cannot be
     * placed - because nothing is being read, or because [source] is no longer
     * the text that was read.
     */
    fun spokenRangeIn(source: String): IntRange? {
        val speaking = _state.value as? State.Speaking ?: return null
        val map = sourceMap ?: return null
        return map.sourceRange(speaking.start, speaking.end, source)
    }

    private val control = Mutex()
    private var job: Job? = null
    private var player: AudioSink? = null

    /**
     * The current utterance, minus its position.
     *
     * Held so that skipping does not repeat the expensive half of the work:
     * the engine is loaded, the voice is loaded, the Markdown is stripped and
     * the text is chunked exactly once per utterance.
     */
    private class Utterance(
        val id: Long,
        /** This utterance's claim on the engine; see [EngineTurn]. */
        val turn: Long,
        val source: Source,
        val speakable: String,
        val chunks: List<TextChunker.Chunk>,
        val engine: SpeechEngine,
        val speed: Float,
    )

    @Volatile
    private var utterance: Utterance? = null

    @Volatile
    private var chunkIndex: Int = 0

    /**
     * The source of the utterance currently being worked on.
     *
     * Tracked separately from [state] because it is known the moment `speak`
     * is called, while the state only catches up once the text has been
     * stripped - and a stop arriving in that window still has to be attributed
     * to the right screen.
     */
    @Volatile
    private var currentSource: Source? = null

    private val nextUtterance = AtomicLong(0)

    @Volatile
    private var currentUtterance: Long = 0

    val isActive: Boolean get() = job?.isActive == true

    /**
     * How the reader obtains an engine and a sink.
     *
     * Swapped in tests for fakes. Without this the reader can only be exercised
     * with a 98 MB model and real audio hardware, which is why its state
     * machine - the part that has repeatedly been wrong - had no tests, and why
     * `ActivityLaunchTest` was quietly downloading the model on every run.
     */
    @VisibleForTesting
    var engines: SpeechEngine.Factory = PocketTtsEngine

    @VisibleForTesting
    var sinks: AudioSink.Factory = StreamingPlayer

    /**
     * Returns the reader to its just-started state.
     *
     * Only for tests: this is a process-wide singleton, so without it one
     * test's terminal state is the next test's starting state.
     */
    @VisibleForTesting
    fun resetForTesting() {
        runBlocking {
            // The queued launches as well as the read in flight. speak() and
            // skip() return before their coroutine has taken the control lock,
            // so a read asked for by a test that ended early would otherwise
            // begin during the next one - against its engine, its sink and its
            // assertions. That is a whole class of confusing cross-test
            // failures, and it does not belong to the test that reports it.
            scope.coroutineContext.job.children.toList().forEach { it.cancelAndJoin() }
            control.withLock { stopLocked(ending = null) }
        }
        utterance = null
        currentSource = null
        currentUtterance = 0
        chunkIndex = 0
        speakableText = ""
        sourceMap = null
        _state.value = State.Idle
    }

    /**
     * Starts reading [text], stopping whatever was playing.
     *
     * @return the utterance id, so the caller can recognise its own read among
     *   the states of a reader the whole process shares.
     */
    fun speak(
        context: Context,
        text: String,
        treatAsMarkdown: Boolean = true,
        voiceOverride: String? = null,
        source: Source = Source.Selection,
    ): Long {
        val appContext = context.applicationContext
        val id = nextUtterance.incrementAndGet()
        // A voice sample auditioning in the picker must not talk over the thing
        // the user actually asked to hear.
        VoiceSample.stop()
        scope.launch {
            control.withLock {
                // No terminal state on the way out: this is a handover, not an
                // ending, and anything watching for "the read is over" would
                // otherwise tear down and immediately rebuild.
                stopLocked(ending = null)
                currentSource = source
                currentUtterance = id
                job = scope.launch {
                    run(appContext, id, text, treatAsMarkdown, voiceOverride, source)
                }
            }
        }
        return id
    }

    /**
     * Moves [delta] chunks - roughly sentences - and carries on reading.
     *
     * Skipping back past the start replays the first chunk; skipping forward
     * past the end finishes the utterance, which is what a listener who keeps
     * tapping forward means by it.
     */
    fun skip(delta: Int) {
        if (delta == 0) return
        scope.launch {
            control.withLock {
                val current = utterance ?: return@withLock
                val target = chunkIndex + delta
                if (target >= current.chunks.size) {
                    stopLocked(ending = State.Finished(current.id, current.source))
                    return@withLock
                }
                stopLocked(ending = null)
                val from = target.coerceAtLeast(0)
                job = scope.launch { play(current, from) }
            }
        }
    }

    fun skipForward() = skip(1)

    fun skipBack() = skip(-1)

    private suspend fun run(
        context: Context,
        id: Long,
        text: String,
        treatAsMarkdown: Boolean,
        voiceOverride: String?,
        source: Source,
    ) {
        val settings = Settings(context)
        val mapped = if (treatAsMarkdown) {
            MarkdownSpeech.toSpeakableWithSource(
                text,
                MarkdownSpeech.Options(speakCodeBlocks = settings.speakCodeBlocks),
            )
        } else {
            // Nothing was stripped, so the speakable text is the source and the
            // offsets need no translating.
            val trimmed = text.trim()
            val offset = text.indexOf(trimmed).coerceAtLeast(0)
            MarkdownSpeech.Speakable(
                trimmed,
                listOf(MarkdownSpeech.Span(0, trimmed.length, offset, offset + trimmed.length)),
            )
        }
        val speakable = mapped.text
        speakableText = speakable
        sourceMap = mapped

        if (speakable.isBlank()) {
            _state.value = State.Finished(id, source)
            return
        }

        val askedAt = System.currentTimeMillis()
        try {
            _state.value = State.Preparing(id, source, 0f)
            val engine = engines.create(context) { fraction ->
                _state.value = State.Preparing(id, source, fraction)
            }
            engine.useVoice(voiceOverride ?: settings.voiceId)
            val prepared = Utterance(
                id = id,
                turn = EngineTurn.take(),
                source = source,
                speakable = speakable,
                chunks = TextChunker.chunk(speakable),
                engine = engine,
                speed = settings.speed,
            )
            utterance = prepared
            play(prepared, from = 0, askedAt = askedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Reading failed", error)
            _state.value = State.Failed(id, source, error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Reads [current] from chunk [from] to the end.
     *
     * [askedAt] is when speech was asked for, so the wait before the first
     * sound can be measured rather than guessed at. A skip passes null: it
     * resumes an utterance whose first-audio time is already known.
     */
    private suspend fun play(current: Utterance, from: Int, askedAt: Long? = null) {
        var localPlayer: AudioSink? = null
        try {
            localPlayer = sinks.create(current.engine.sampleRate).also {
                player = it
                it.start()
            }

            // The sink refusing audio means it was stopped under us, which is
            // an interruption and not an ending: whoever stopped it owns the
            // state that follows. Publishing Finished here would put a
            // terminal state on the handover between two utterances - the same
            // bug the Idle-means-two-things design had, wearing a new hat.
            var interrupted = false
            var audible = false
            var samplesProduced = 0L
            // Timed on the first chunk only. After that the buffer is full and
            // the blocking write makes every measurement come out at exactly
            // real time, which measures the speaker rather than the model.
            var chunkStartedAt = 0L
            for (index in from until current.chunks.size) {
                val chunk = current.chunks[index]
                coroutineContext.ensureActive()
                chunkIndex = index
                _state.value = State.Speaking(
                    utterance = current.id,
                    source = current.source,
                    chunkIndex = index,
                    chunkCount = current.chunks.size,
                    start = chunk.start,
                    end = chunk.end,
                    paused = localPlayer.isPaused,
                    audible = audible,
                )
                if (EngineTurn.superseded(current.turn)) {
                    interrupted = true
                    break
                }
                chunkStartedAt = System.currentTimeMillis()
                val spoke = current.engine.synthesize(
                    chunk.text,
                    current.speed,
                ) { samples ->
                    // Checked per callback as well as per chunk: a request
                    // arriving mid-sentence should not have to wait out the
                    // rest of it.
                    if (EngineTurn.superseded(current.turn)) return@synthesize false
                    val written = localPlayer.write(samples)
                    if (written) samplesProduced += samples.size
                    if (written && !audible) {
                        audible = true
                        askedAt?.let { Metrics.timeToFirstAudioMillis = System.currentTimeMillis() - it }
                        // Said once, when it becomes true.
                        (_state.value as? State.Speaking)
                            ?.takeIf { it.utterance == current.id }
                            ?.let { _state.value = it.copy(audible = true) }
                    }
                    written
                }
                if (index == from) {
                    val elapsed = System.currentTimeMillis() - chunkStartedAt
                    val audioSeconds = samplesProduced.toFloat() / current.engine.sampleRate
                    if (elapsed > 0) {
                        Metrics.generationRealTimeFactor = audioSeconds / (elapsed / 1000f)
                    }
                }
                if (!spoke || !localPlayer.writeSilence(chunk.trailingPauseSeconds)) {
                    interrupted = true
                    break
                }
            }
            localPlayer.drain()
            when {
                !interrupted -> _state.value = State.Finished(current.id, current.source)
                // Losing the engine to a more recent request is an ending this
                // read has to own; a sink stopped from inside belongs to
                // whoever stopped it, and they publish their own state.
                EngineTurn.superseded(current.turn) ->
                    _state.value = State.Stopped(current.id, current.source)
            }
        } catch (cancelled: CancellationException) {
            // The state belongs to whoever cancelled us - a handover leaves it
            // alone, an explicit stop sets Stopped.
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Reading failed", error)
            _state.value = State.Failed(current.id, current.source, error.message ?: error.javaClass.simpleName)
        } finally {
            localPlayer?.let { Metrics.underruns = it.underruns }
            localPlayer?.release()
            if (player === localPlayer) player = null
        }
    }

    fun pause() {
        player?.pause()
        (_state.value as? State.Speaking)?.let { _state.value = it.copy(paused = true) }
    }

    fun resume() {
        player?.resume()
        (_state.value as? State.Speaking)?.let { _state.value = it.copy(paused = false) }
    }

    fun togglePause() {
        if (player?.isPaused == true) resume() else pause()
    }

    fun stop() {
        scope.launch {
            control.withLock {
                val source = currentSource ?: return@withLock
                stopLocked(ending = State.Stopped(currentUtterance, source))
            }
        }
    }

    /**
     * Stops playback and waits for the reading coroutine to unwind.
     *
     * [ending] is the state to publish afterwards, or null when another
     * utterance is about to take over and will publish its own.
     */
    private suspend fun stopLocked(ending: State?) {
        // Stopping the player first unblocks any write that is parked waiting
        // for buffer space, so the cancel below does not have to wait it out.
        player?.stop()
        job?.cancelAndJoin()
        job = null
        if (ending != null) {
            utterance = null
            currentSource = null
            currentUtterance = 0
            chunkIndex = 0
            _state.value = ending
        }
    }
}
