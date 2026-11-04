package org.pockettts.android.player

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
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
    enum class Source(
        /**
         * How this source is named in [VoiceTrace].
         *
         * The trace called every in-app read "[reader]" regardless of who asked,
         * which made it useless for the one question it was reached for: whether
         * the read under investigation was the broadcast or the scratchpad.
         */
        val traceName: String,
    ) {
        /** Text selected in another app, or shared in. */
        Selection("selection"),

        /** The Speak button, or a selection inside the scratchpad. */
        Scratchpad("scratchpad"),

        /** Another app driving the system text-to-speech engine. */
        System("system"),

        /**
         * A coding agent or script, over [org.pockettts.android.SpeakReceiver].
         *
         * Neither a paste nor a selection: nobody was looking at this text in
         * an editor, and there is no screen anywhere that owns the read. It is
         * told apart from the other two because a read with no window behind
         * it fails differently - silently, with the phone in a pocket - and a
         * trace that calls it a selection sends the search somewhere there is
         * nothing to find.
         */
        Agent("agent"),

        /** "Hear the current model", on the model picker. */
        ModelPicker("model-picker"),
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

    @Volatile
    private var job: Job? = null
    private var player: AudioSink? = null

    /**
     * How many things are holding the reader busy: one per read asked for
     * whose request has not been handled yet, plus one while a read is playing
     * or queued.
     *
     * A count in one flow rather than two flags, because what matters is that
     * it never passes through zero on the way from one read to the next. A
     * request is counted on the caller's thread, before `speak` returns, so a
     * screen that starts [PlaybackService] straight after can never find the
     * reader idle with its own read still to begin. That is what the service
     * found when it watched the state instead: the previous read's ending,
     * still in the flow, and it shut itself down under the new read.
     */
    private val holds = MutableStateFlow(0)

    /** Whether [holds] carries the playing-or-queued hold. Only touched under [control]. */
    private var runningHeld = false

    /**
     * True from the moment a read is asked for until nothing is playing,
     * starting or queued.
     *
     * The state cannot say this. Between two queued reads it is the first
     * one's [State.Finished], and for the moment between `speak` and the new
     * read's [State.Preparing] it is whatever the last read ended with. Both
     * look like "over" to anything that only reads the state, and the media
     * notification, the session and audio focus all went with them while the
     * next read played on.
     */
    val busy: Flow<Boolean> = holds.map { it > 0 }.distinctUntilChanged()

    /** [busy], now. */
    val isBusy: Boolean get() = holds.value > 0

    /**
     * [holds], for a test that has to know its requests have been handled:
     * a skip returns before it has taken [control], and a test that moves on
     * at once is testing whichever of the two got there first.
     */
    @VisibleForTesting
    internal val holdCount: Int get() = holds.value

    /** Brings the playing-or-queued hold up to date. Caller holds [control]. */
    private fun refreshLocked() {
        _queuedCount.value = queue.size
        val running = job?.isActive == true || queue.isNotEmpty()
        if (running == runningHeld) return
        runningHeld = running
        holds.update { if (running) it + 1 else it - 1 }
    }

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
        /**
         * Replaced by a skip that lands inside a chunk, which chunks the text
         * again from the sentence it lands on. Only replaced under [control]
         * with no read in progress, so a read sees one list from start to end.
         */
        @Volatile var chunks: List<TextChunker.Chunk>,
        val engine: SpeechEngine,
        /** Whether its chunks continue each other; see [SpeechEngine.Read]. */
        val continuing: Boolean,
    ) {
        /** What a skip moves by; see [TextChunker.sentenceStarts]. */
        val sentences: List<Int> = TextChunker.sentenceStarts(speakable)
    }

    @Volatile
    private var utterance: Utterance? = null

    /**
     * An utterance that has been asked for but not started.
     *
     * Everything needed to begin a read, held until the one in front of it
     * ends. Deliberately the arguments to [run] rather than an [Utterance]:
     * the engine, the voice and the chunking are the expensive half, and
     * doing them at queue time would load a model for a read that a stop
     * arriving first means nobody will ever hear.
     */
    private class Pending(
        val id: Long,
        val context: Context,
        val text: String,
        val treatAsMarkdown: Boolean,
        val voiceOverride: String?,
        val source: Source,
    )

    /**
     * Reads waiting their turn. Only ever touched under [control].
     *
     * [EngineTurn] is not this. That arbitrates between the two things that
     * drive the one model - this reader and the system engine service - and
     * its rule is that the most recent request wins, which is the opposite of
     * a queue. It exists so two callers who cannot see each other do not
     * interleave; this exists so one caller can say "after that one", and
     * nothing about standing down for a stranger answers that.
     */
    private val queue = ArrayDeque<Pending>()

    /**
     * How many reads are waiting their turn.
     *
     * Read without [control], which is safe for what it is used for: a test
     * waiting for an enqueue to have landed before it does the thing it is
     * actually testing. Without it every queue test races the dispatch of the
     * coroutine that does the enqueuing, and races that are usually won are
     * the ones that fail in CI.
     */
    @VisibleForTesting
    internal val queued: Int get() = queue.size

    private val _queuedCount = MutableStateFlow(0)

    /** How many reads are waiting behind this one, for the controls to show. */
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()

    private val _speed = MutableStateFlow(1f)

    /**
     * The speed reads are composed at. Read before each chunk, so a change
     * is heard from the next chunk composed: the one after the current, or
     * the one after that if it is already composed.
     */
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** The speeds the speed control steps through. */
    @VisibleForTesting
    internal val SPEED_STEPS = listOf(0.8f, 1f, 1.25f, 1.5f)

    /** Steps the speed to the next of [SPEED_STEPS], and keeps it for later reads. */
    fun cycleSpeed(context: Context) {
        // From the saved speed, which the slider on the main screen may have
        // set to something between the steps.
        val now = Settings(context).speed
        setSpeed(context, SPEED_STEPS.firstOrNull { it > now + 0.01f } ?: SPEED_STEPS.first())
    }

    /** Sets the speed for the read in progress and every one after it. */
    fun setSpeed(context: Context, speed: Float) {
        val settings = Settings(context)
        settings.speed = speed
        // Read back, so the reader holds the clamped value the setting keeps.
        _speed.value = settings.speed
    }

    /**
     * Seconds of speech per character of text, at speed 1: measured from
     * every chunk heard so far, and a guess at 15 characters a second before
     * the first. What turns a character offset into a time and back.
     */
    @Volatile
    private var secondsPerChar = 1.0 / 15

    private var measuredSeconds = 0.0
    private var measuredChars = 0

    /** Samples of the current chunk written to the sink, for [positionChars]. */
    @Volatile
    private var chunkSamples = 0L

    /**
     * Where the last few chunks began in the sink's frames, newest last: so
     * [positionChars] can tell which chunk the listener is actually hearing,
     * which near a boundary is the one before the chunk being written.
     */
    @Volatile
    private var chunkMarks: List<Mark> = emptyList()

    /**
     * Chunk [index] began at sink frame [frame], composed at [speed]. The
     * speed is the chunk's own: a speed change is heard from a later chunk,
     * and measuring the chunk in the ear with the new speed put the
     * position a quarter of a chunk off.
     */
    private class Mark(val index: Int, val frame: Long, val speed: Float)

    /** The speed the chunk in [chunkIndex] was composed at, for when the sink cannot say what is heard. */
    @Volatile
    private var chunkSpeed = 1f

    private fun markChunk(index: Int, frame: Long, speed: Float) {
        chunkSpeed = speed
        chunkMarks = (chunkMarks + Mark(index, frame, speed)).takeLast(4)
    }

    /** Forgets where the listener was, before the chunks change under it. */
    private fun resetPosition(index: Int) {
        chunkMarks = emptyList()
        chunkSamples = 0
        chunkIndex = index
        chunkSpeed = _speed.value
    }

    /** Milliseconds per character at the current speed. */
    private val millisPerChar: Double get() = secondsPerChar / _speed.value * 1000

    /**
     * Where the listener is in [current], as an offset into its speakable
     * text: the start of the chunk being heard, plus as much of it as its
     * samples so far account for. An estimate, and the best one there is:
     * the model composes a chunk whole and does not say where in it a
     * sentence ends.
     */
    private fun positionChars(current: Utterance): Int = heard(current).second

    /** The chunk being heard and the offset reached in it; see [positionChars]. */
    private fun heard(current: Utterance): Pair<TextChunker.Chunk?, Int> {
        var index = chunkIndex
        var samples = chunkSamples
        var speed = chunkSpeed
        // What the listener hears, where the sink can say: the chunk whose
        // first frame was played last, and how far past it the playback is.
        val played = player?.playedFrames ?: -1
        val marks = chunkMarks
        if (played >= 0 && marks.isNotEmpty()) {
            val mark = marks.lastOrNull { it.frame <= played } ?: marks.first()
            index = mark.index
            samples = (played - mark.frame).coerceAtLeast(0)
            speed = mark.speed
        }
        val chunk = current.chunks.getOrNull(index) ?: return null to 0
        val rate = current.engine.sampleRate.toDouble()
        val heard = (samples / rate * speed / secondsPerChar).toInt()
        return chunk to chunk.start + heard.coerceIn(0, chunk.end - chunk.start)
    }

    /** Where the read in progress is, for the media session and the notification. */
    data class Progress(
        val positionMillis: Long,
        val durationMillis: Long,
        /** One-based. */
        val sentence: Int,
        val sentences: Int,
        /** The chunk being heard, as offsets into [speakableText]. */
        val heardStart: Int,
        val heardEnd: Int,
    )

    fun progress(): Progress? {
        val current = utterance ?: return null
        val (chunk, position) = heard(current)
        val sentence = current.sentences.indexOfLast { it <= position }.coerceAtLeast(0)
        return Progress(
            positionMillis = (position * millisPerChar).toLong(),
            durationMillis = (current.speakable.length * millisPerChar).toLong(),
            sentence = sentence + 1,
            sentences = current.sentences.size,
            heardStart = chunk?.start ?: 0,
            heardEnd = chunk?.end ?: 0,
        )
    }

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
     * with a 125 MB model and real audio hardware, which is why its state
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
            control.withLock {
                queue.clear()
                stopLocked(ending = null)
            }
        }
        holds.value = 0
        runningHeld = false
        stoppedAt = 0
        order.set(0)
        beforeRequest = null
        _queuedCount.value = 0
        _speed.value = 1f
        secondsPerChar = 1.0 / 15
        measuredSeconds = 0.0
        measuredChars = 0
        chunkSamples = 0
        chunkMarks = emptyList()
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
        request { stopped ->
            if (stopped) {
                // Stopped before it began. Said, so a window waiting for this
                // read's ending is not left waiting.
                _state.value = State.Stopped(id, source)
                return@request
            }
            // A read that replaces what is playing replaces what was going
            // to play after it too. Queued follow-ups belong to the passage
            // being abandoned, not to the one taking over.
            queue.clear()
            // No terminal state on the way out: this is a handover, not an
            // ending, and anything watching for "the read is over" would
            // otherwise tear down and immediately rebuild.
            stopLocked(ending = null)
            startLocked(Pending(id, appContext, text, treatAsMarkdown, voiceOverride, source))
        }
        return id
    }

    /**
     * Runs [block] under [control], holding the reader [busy] from now until
     * it has run.
     *
     * [block] is told whether a [stop] was asked for after this request was.
     * Each request and each stop launches its own coroutine, and nothing
     * orders which of them takes [control] first, so without this a stop
     * could run before the read it was meant to stop, find nothing playing,
     * and let the read start after it. The order is taken here, on the
     * caller's thread, where it is the order things were asked for.
     */
    private fun request(block: suspend (stopped: Boolean) -> Unit) {
        val ticket = order.incrementAndGet()
        holds.update { it + 1 }
        scope.launch {
            try {
                beforeRequest?.invoke()
                control.withLock {
                    block(stoppedAt > ticket)
                    refreshLocked()
                }
            } finally {
                holds.update { it - 1 }
            }
        }
    }

    /**
     * Runs before a request takes [control], so a test can make a later stop
     * reach the lock first. That is the order the tickets exist for, and
     * without a way to force it the race is won by the request nearly every
     * time.
     */
    @VisibleForTesting
    internal var beforeRequest: (suspend () -> Unit)? = null

    /** Hands out the tickets [request] and [stop] are ordered by. */
    private val order = AtomicLong(0)

    /** The ticket of the most recent [stop]. */
    @Volatile
    private var stoppedAt = 0L

    /**
     * Reads [text] after whatever is playing, rather than instead of it.
     *
     * For a caller sending a series of things to hear in order - an agent
     * handing over one reply while the last is still being read. [speak] would
     * make each one silence the one before, so a run of them plays only the
     * last few words of the last.
     *
     * With nothing playing this is exactly [speak]; there is no queue to join.
     *
     * @return the utterance id, which is allocated now even though the read
     *   starts later, so a caller can recognise its own read when it arrives.
     */
    fun enqueue(
        context: Context,
        text: String,
        treatAsMarkdown: Boolean = true,
        voiceOverride: String? = null,
        source: Source = Source.Selection,
    ): Long {
        val appContext = context.applicationContext
        val id = nextUtterance.incrementAndGet()
        request { stopped ->
            if (stopped) return@request
            val pending = Pending(id, appContext, text, treatAsMarkdown, voiceOverride, source)
            if (job?.isActive == true || queue.isNotEmpty()) {
                queue.addLast(pending)
            } else {
                VoiceSample.stop()
                startLocked(pending)
            }
        }
        return id
    }

    /**
     * Begins [pending]. The caller holds [control] and has stopped whatever
     * was playing.
     */
    private fun startLocked(pending: Pending) {
        currentSource = pending.source
        currentUtterance = pending.id
        // The last read is no longer the one in progress. Left in place, a
        // skip while this one was still preparing replayed the last read and
        // dropped this one, and the controls drew the last read's progress
        // over this one's text.
        utterance = null
        resetPosition(0)
        launchLocked {
            run(
                pending.context,
                pending.id,
                pending.text,
                pending.treatAsMarkdown,
                pending.voiceOverride,
                pending.source,
            )
        }
    }

    /**
     * Makes [block] the read in progress. Caller holds [control].
     *
     * Every read goes through here, a skip's included, because the completion
     * handler is what starts the queue's next read. A skip used to launch its
     * own job without one, so a read that had been skipped within finished
     * and left everything queued behind it waiting for good.
     */
    private fun launchLocked(block: suspend () -> Unit) {
        val started = scope.launch { block() }
        job = started
        // Advancing the queue from inside the job would deadlock: a stop holds
        // `control` while it joins this very job, so the job cannot take the
        // lock it would need. A completion handler runs after the job is done
        // and the joiner has been released.
        //
        // A non-null cause means cancelled, and a cancel is either a handover
        // or a stop - both of which have already decided what plays next.
        started.invokeOnCompletion { cause ->
            if (cause == null) scope.launch { advance(started) }
        }
        refreshLocked()
    }

    /** Starts whatever [finished] was holding up. */
    private suspend fun advance(finished: Job) {
        control.withLock {
            // Somebody took over while the completion handler was in flight;
            // the queue is theirs now.
            if (job === finished) {
                // A failure here is almost never about the text - no model, no
                // voice, no network - so the rest of the queue would fail the
                // same way. Four more reads that each say nothing but the same
                // error is not a service to anyone.
                if (_state.value is State.Failed) {
                    queue.clear()
                } else {
                    job = null
                    advanceLocked()
                }
            }
            refreshLocked()
        }
    }

    /** Starts the next queued read, if there is one. Caller holds [control]. */
    private fun advanceLocked() {
        startLocked(queue.removeFirstOrNull() ?: return)
    }

    /**
     * Moves [delta] sentences and carries on reading.
     *
     * Back restarts the sentence being heard, unless it began less than
     * [RESTART_SECONDS] ago, in which case it goes to the one before: what a
     * music player does with a track, and what makes a single tap mean
     * "again". Skipping back past the start replays the first sentence;
     * skipping forward past the end finishes the utterance, which is what a
     * listener who keeps tapping forward means by it.
     */
    fun skip(delta: Int) {
        if (delta == 0) return
        // Through request, so the reader stays busy across the moment between
        // stopping the old chunk and launching the new one. Without the hold
        // the count passed through zero there, and the playback service
        // stopped under a read that was only skipping.
        request { stopped ->
            if (stopped) return@request
            val current = utterance ?: return@request
            val starts = current.sentences
            val position = positionChars(current)
            val here = starts.indexOfLast { it <= position }.coerceAtLeast(0)
            val intoSentence = (position - starts[here]) * millisPerChar / 1000
            val target = if (delta < 0 && intoSentence > RESTART_SECONDS) here + delta + 1 else here + delta
            Log.i(TAG, "Skip $delta: hearing sentence ${here + 1}, %.1fs in; to ${target + 1}".format(intoSentence))
            if (target >= starts.size) {
                stopLocked(ending = State.Finished(current.id, current.source))
                // Skipping off the end is an ending like any other, and
                // what was queued behind it is still wanted. The cancel
                // inside stopLocked means the completion handler will not
                // do this for us.
                advanceLocked()
                return@request
            }
            seekLocked(current, starts[target.coerceAtLeast(0)])
        }
    }

    /**
     * Carries on reading from the sentence at [millis], as the media
     * session's seek bar asks. The time is turned into a place in the text
     * with the same estimate [progress] reports it with, so a seek lands
     * where the bar said it would.
     */
    fun seekTo(millis: Long) {
        request { stopped ->
            if (stopped) return@request
            val current = utterance ?: return@request
            val offset = (millis / millisPerChar).toInt()
            val sentence = current.sentences.lastOrNull { it <= offset } ?: 0
            seekLocked(current, sentence)
        }
    }

    /**
     * Reads [current] from [offset], which is a sentence start. Caller holds
     * [control].
     *
     * The chunks before [offset] are kept and the text from it is chunked
     * again, so a sentence inside a chunk becomes the start of one. Landing
     * on a chunk's own start chunks it exactly as before.
     */
    private suspend fun seekLocked(current: Utterance, offset: Int) {
        stopLocked(ending = null)
        val before = current.chunks.filter { it.end <= offset }
        // Here and not only when the new read begins: a second tap handled
        // before then measured from the last chunk's position against the new
        // list, and a double tap on back went two sentences instead of one.
        // Before the list changes, so nothing reads the old position against it.
        resetPosition(before.size)
        current.chunks = before + TextChunker.chunkFrom(current.speakable, offset, current.continuing)
        launchLocked { play(current, before.size) }
    }

    /**
     * Ends this read and starts the next queued one. Nothing happens with
     * nothing queued: the control is only offered while something is.
     */
    fun next() {
        request { stopped ->
            if (stopped || queue.isEmpty()) return@request
            val source = currentSource
            if (source != null) {
                stopLocked(ending = State.Stopped(currentUtterance, source))
            } else {
                stopLocked(ending = null)
            }
            advanceLocked()
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
                MarkdownSpeech.Options(
                    speakCodeBlocks = settings.speakCodeBlocks,
                    language = settings.readingLanguage,
                ),
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
        _speed.value = settings.speed
        try {
            _state.value = State.Preparing(id, source, 0f)
            val engine = engines.create(context) { fraction ->
                _state.value = State.Preparing(id, source, fraction)
            }
            engine.useVoice(voiceOverride ?: settings.voiceId, source.traceName)
            val prepared = Utterance(
                id = id,
                turn = EngineTurn.take(),
                source = source,
                speakable = speakable,
                chunks = TextChunker.chunk(speakable, continuing = settings.continueChunks),
                engine = engine,
                continuing = settings.continueChunks,
            )
            utterance = prepared
            play(prepared, from = 0, askedAt = askedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Reading failed", error)
            _state.value = State.Failed(id, source, failureMessage(error))
        }
    }

    /**
     * One piece of a chunk's audio on its way from the engine to the sink;
     * the end of a chunk when [samples] is null.
     */
    private class Piece(val chunk: Int, val samples: FloatArray?, val speed: Float = 1f)

    /**
     * Reads [current] from chunk [from] to the end.
     *
     * Synthesis runs ahead of playback. The engine composes a whole chunk
     * before it emits a sample - every frame of the language model, then the
     * decoder - so a chunk's audio arrives all at once, after a silence as
     * long as the model took. Feeding the sink from inside the engine's
     * callback, as this used to, meant the next chunk could not begin until
     * this one had been *heard*: the blocking write held the callback, the
     * callback held the engine. The silence before every chunk was the model's
     * full pass over it, less whatever the sink had buffered, and on a phone
     * that was several seconds between paragraphs, charged for a table that
     * stripped to one line just as for a long one.
     *
     * Now a producer hands pieces to a channel and a consumer writes them,
     * and the producer may be [LOOKAHEAD_CHUNKS] chunks ahead of the one
     * being written. The model's pass over chunk N+1 happens while chunk N
     * plays, and as long as the model is faster than real time - it is, by a
     * comfortable margin on the device this was measured on - the gap is
     * gone. Memory is bounded by the lookahead: a chunk is at most a few
     * hundred characters, under thirty seconds of float audio.
     *
     * What is published follows the *listener*: [State.Speaking.chunkIndex]
     * is the chunk being written to the sink, not the one being composed,
     * so highlighting and skipping stay true to what is being heard.
     *
     * [askedAt] is when speech was asked for, so the wait before the first
     * sound can be measured rather than guessed at. A skip passes null: it
     * resumes an utterance whose first-audio time is already known.
     */
    private suspend fun play(current: Utterance, from: Int, askedAt: Long? = null) {
        if (from >= current.chunks.size) {
            _state.value = State.Finished(current.id, current.source)
            return
        }
        var localPlayer: AudioSink? = null
        // From here on, so a skip, which plays from another chunk, starts its
        // own: the chunk it lands on does not follow the last one composed.
        // Closed in the finally, after the producer below has finished.
        val read = if (current.continuing) current.engine.startRead() else SpeechEngine.Fresh
        try {
            localPlayer = sinks.create(current.engine.sampleRate).also {
                player = it
                it.start()
            }
            val sink: AudioSink = localPlayer

            // The sink refusing audio means it was stopped under us, which is
            // an interruption and not an ending: whoever stopped it owns the
            // state that follows. Publishing Finished here would put a
            // terminal state on the handover between two utterances - the same
            // bug the Idle-means-two-things design had, wearing a new hat.
            val interrupted = AtomicBoolean(false)
            var audible = false
            var framesWritten = 0L
            chunkIndex = from
            chunkSamples = 0
            chunkMarks = listOf(Mark(from, 0L, _speed.value))
            chunkSpeed = _speed.value
            _state.value = speakingState(current, from, paused = sink.isPaused, audible = false)

            // Unbounded on purpose: the engine's callback cannot suspend, and a
            // callback that blocks waiting for room would hold the engine
            // exactly the way the blocking write used to - and, worse, would
            // have nothing to wake it once a stop had cancelled the consumer.
            // The bound is on chunks instead, below.
            val pieces = Channel<Piece>(Channel.UNLIMITED)
            // One permit per chunk the producer may be working on or holding
            // finished: the one being written plus the lookahead. Released as
            // the consumer finishes each chunk.
            val permits = Semaphore(LOOKAHEAD_CHUNKS + 1)

            coroutineScope {
                val producer = launch(Dispatchers.Default) {
                    val self = coroutineContext.job
                    try {
                        for (index in from until current.chunks.size) {
                            permits.acquire()
                            if (EngineTurn.superseded(current.turn)) {
                                interrupted.set(true)
                                break
                            }
                            val chunk = current.chunks[index]
                            val startedAt = System.currentTimeMillis()
                            var samplesProduced = 0L
                            val speed = _speed.value
                            val spoke = current.engine.synthesize(chunk.speech, speed, read) { samples ->
                                // The engine only looks at this between pieces,
                                // so a stop is felt within one piece rather than
                                // at the end of the chunk.
                                if (!self.isActive) return@synthesize false
                                // Checked per callback as well as per chunk: a
                                // request arriving mid-sentence should not have
                                // to wait out the rest of it.
                                if (EngineTurn.superseded(current.turn)) {
                                    interrupted.set(true)
                                    return@synthesize false
                                }
                                samplesProduced += samples.size
                                pieces.trySend(Piece(index, samples, speed))
                                true
                            }
                            if (index == from) {
                                // The engine on its own, now that nothing in the
                                // callback waits on the speaker: audio seconds
                                // composed per wall second.
                                val elapsed = System.currentTimeMillis() - startedAt
                                if (elapsed > 0) {
                                    Metrics.generationRealTimeFactor =
                                        samplesProduced.toFloat() / current.engine.sampleRate / (elapsed / 1000f)
                                }
                            }
                            if (!spoke) {
                                interrupted.set(true)
                                break
                            }
                            pieces.trySend(Piece(index, null, speed))
                        }
                        pieces.close()
                    } catch (cancelled: CancellationException) {
                        pieces.close()
                        throw cancelled
                    } catch (error: Throwable) {
                        // Delivered to the consumer in order, after the audio
                        // that was composed before it - so a failure on chunk
                        // three does not cut off chunk two mid-word.
                        pieces.close(error)
                    }
                }

                withContext(Dispatchers.IO) {
                    var writing = from
                    for (piece in pieces) {
                        coroutineContext.ensureActive()
                        if (piece.chunk != writing) {
                            writing = piece.chunk
                            chunkIndex = writing
                            chunkSamples = 0
                            markChunk(writing, framesWritten, piece.speed)
                            _state.value = speakingState(current, writing, paused = sink.isPaused, audible = audible)
                        }
                        val samples = piece.samples
                        if (samples == null) {
                            learnRate(current.chunks[piece.chunk], chunkSamples, current.engine.sampleRate, piece.speed)
                            val pause = current.chunks[piece.chunk].trailingPauseSeconds
                            if (!sink.writeSilence(pause)) {
                                interrupted.set(true)
                                break
                            }
                            framesWritten += (pause * current.engine.sampleRate).toLong()
                            permits.release()
                            continue
                        }
                        if (EngineTurn.superseded(current.turn)) {
                            interrupted.set(true)
                            break
                        }
                        if (!sink.write(samples)) {
                            interrupted.set(true)
                            break
                        }
                        chunkSamples += samples.size
                        framesWritten += samples.size
                        if (!audible) {
                            audible = true
                            askedAt?.let { Metrics.timeToFirstAudioMillis = System.currentTimeMillis() - it }
                            // Said once, when it becomes true.
                            (_state.value as? State.Speaking)
                                ?.takeIf { it.utterance == current.id }
                                ?.let { _state.value = it.copy(audible = true) }
                        }
                    }
                    // Nothing more will be heard, so nothing more should be
                    // composed; without this the scope would wait for the
                    // producer to finish every remaining chunk into a channel
                    // nobody reads.
                    if (interrupted.get()) producer.cancel()
                }
            }

            sink.drain()
            when {
                !interrupted.get() -> _state.value = State.Finished(current.id, current.source)
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
            _state.value = State.Failed(current.id, current.source, failureMessage(error))
        } finally {
            read.close()
            localPlayer?.let { Metrics.underruns = it.underruns }
            localPlayer?.release()
            if (player === localPlayer) player = null
        }
    }

    /**
     * What a failed read tells the listener. The exception's own message is
     * written for whoever reads the log, and the ones a read meets most, a
     * download with no connection, say nothing a listener can act on.
     */
    @VisibleForTesting
    internal fun failureMessage(error: Throwable): String = when (error) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketTimeoutException,
        -> "No connection, and the voice model or voice is not downloaded yet. " +
            "Connect once, or download it in Pocket TTS."
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    private fun speakingState(current: Utterance, index: Int, paused: Boolean, audible: Boolean): State.Speaking {
        val chunk = current.chunks[index]
        return State.Speaking(
            utterance = current.id,
            source = current.source,
            chunkIndex = index,
            chunkCount = current.chunks.size,
            start = chunk.start,
            end = chunk.end,
            paused = paused,
            audible = audible,
        )
    }

    /**
     * How many chunks synthesis may run ahead of the one being played.
     *
     * One is enough to hide the model's pass over the next chunk behind the
     * current one, which is the whole point; more would only add memory and
     * lengthen the work thrown away by a stop or a skip.
     */
    @VisibleForTesting
    internal const val LOOKAHEAD_CHUNKS = 1

    /** How far into a sentence skipping back restarts it rather than going to the one before. */
    private const val RESTART_SECONDS = 2.0

    /** Folds a whole chunk heard at [speed] into [secondsPerChar]. */
    private fun learnRate(chunk: TextChunker.Chunk, samples: Long, sampleRate: Int, speed: Float) {
        val chars = chunk.end - chunk.start
        if (samples <= 0 || chars <= 0) return
        measuredSeconds += samples.toDouble() / sampleRate * speed
        measuredChars += chars
        secondsPerChar = measuredSeconds / measuredChars
    }

    /**
     * Counts pauses and resumes, so [AudioFocus] can tell whether the pause it
     * caused is still the one in effect when focus comes back.
     */
    @Volatile
    var pauseChanges: Int = 0
        private set

    fun pause() {
        pauseChanges++
        player?.pause()
        (_state.value as? State.Speaking)?.let { _state.value = it.copy(paused = true) }
    }

    fun resume() {
        pauseChanges++
        player?.resume()
        (_state.value as? State.Speaking)?.let { _state.value = it.copy(paused = false) }
    }

    fun togglePause() {
        if (player?.isPaused == true) resume() else pause()
    }

    fun stop() {
        stoppedAt = order.incrementAndGet()
        scope.launch {
            control.withLock {
                // Cleared before the early return: a stop with nothing playing
                // still means "and nothing after it either".
                queue.clear()
                val source = currentSource
                // A read that already ended keeps its source until the next
                // one starts. Stopping it again would publish a second ending
                // for it.
                if (source == null || job?.isActive != true) {
                    refreshLocked()
                    return@withLock
                }
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
        refreshLocked()
        if (ending != null) {
            utterance = null
            currentSource = null
            currentUtterance = 0
            chunkIndex = 0
            _state.value = ending
        }
    }
}
