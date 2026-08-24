package org.pockettts.android.player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.pockettts.android.engine.PocketTts
import org.pockettts.android.engine.Settings
import org.pockettts.android.engine.VoiceCatalog
import org.pockettts.android.speech.MarkdownSpeech
import org.pockettts.android.speech.TextChunker
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Reads a body of text aloud, one chunk at a time.
 *
 * There is one reader for the whole process: starting a new utterance stops
 * whatever was playing, which is what you want when you select a second
 * paragraph while the first is still being read.
 */
object Reader {

    private const val TAG = "Reader"

    sealed interface State {
        data object Idle : State

        /** Model or voice is being fetched. [fraction] is -1 when size is unknown. */
        data class Preparing(val fraction: Float) : State

        data class Speaking(
            val chunkIndex: Int,
            val chunkCount: Int,
            /** Character range of the chunk within the speakable text. */
            val start: Int,
            val end: Int,
            val paused: Boolean,
        ) : State

        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob())

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The text currently being read, after Markdown stripping. */
    @Volatile
    var speakableText: String = ""
        private set

    private val control = Mutex()
    private var job: Job? = null
    private var player: StreamingPlayer? = null

    val isActive: Boolean get() = job?.isActive == true

    fun speak(
        context: Context,
        text: String,
        treatAsMarkdown: Boolean = true,
        voiceOverride: String? = null,
    ) {
        val appContext = context.applicationContext
        scope.launch {
            control.withLock {
                stopLocked()
                job = scope.launch { run(appContext, text, treatAsMarkdown, voiceOverride) }
            }
        }
    }

    private suspend fun run(
        context: Context,
        text: String,
        treatAsMarkdown: Boolean,
        voiceOverride: String?,
    ) {
        val settings = Settings(context)
        val speakable = if (treatAsMarkdown) {
            MarkdownSpeech.toSpeakable(
                text,
                MarkdownSpeech.Options(speakCodeBlocks = settings.speakCodeBlocks),
            )
        } else {
            text.trim()
        }
        speakableText = speakable

        if (speakable.isBlank()) {
            _state.value = State.Idle
            return
        }

        var localPlayer: StreamingPlayer? = null
        try {
            _state.value = State.Preparing(0f)
            val engine = PocketTts.get(context) { fraction ->
                _state.value = State.Preparing(fraction)
            }
            val voiceId = voiceOverride ?: settings.voiceId
            val voice = loadVoice(context, engine, voiceId)

            val chunks = TextChunker.chunk(speakable)
            localPlayer = StreamingPlayer(engine.sampleRate).also {
                player = it
                it.start()
            }

            for ((index, chunk) in chunks.withIndex()) {
                coroutineContext.ensureActive()
                _state.value = State.Speaking(
                    chunkIndex = index,
                    chunkCount = chunks.size,
                    start = chunk.start,
                    end = chunk.end,
                    paused = localPlayer.isPaused,
                )
                val finished = engine.synthesize(chunk.text, voice, settings.speed) { samples ->
                    localPlayer.write(samples)
                }
                if (!finished) break
                if (!localPlayer.writeSilence(chunk.trailingPauseSeconds)) break
            }
            localPlayer.drain()
            _state.value = State.Idle
        } catch (cancelled: CancellationException) {
            _state.value = State.Idle
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "Reading failed", error)
            _state.value = State.Failed(error.message ?: error.javaClass.simpleName)
        } finally {
            localPlayer?.release()
            if (player === localPlayer) player = null
        }
    }

    private suspend fun loadVoice(
        context: Context,
        engine: PocketTts,
        voiceId: String,
    ): PocketTts.LoadedVoice {
        VoiceCatalog.byId(voiceId)?.let { return engine.loadVoice(it) }
        // Not a stock voice, so it is one the user imported.
        val imported = File(File(context.filesDir, "pocket-tts/voices"), "$voiceId.wav")
        if (imported.isFile) return engine.loadVoiceFile(voiceId, imported)
        return engine.loadVoice(VoiceCatalog.default())
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
        scope.launch { control.withLock { stopLocked() } }
    }

    /** Stops playback and waits for the reading coroutine to unwind. */
    private suspend fun stopLocked() {
        // Stopping the player first unblocks any write that is parked waiting
        // for buffer space, so the cancel below does not have to wait it out.
        player?.stop()
        job?.cancelAndJoin()
        job = null
        _state.value = State.Idle
    }
}
