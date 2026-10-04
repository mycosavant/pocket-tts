package org.pockettts.android.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pockettts.android.debug.Metrics
import org.pockettts.android.debug.VoiceTrace
import sonic.Sonic
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the one loaded copy of the model.
 *
 * The engine is pocket-speak's, the Rust crate the desk CLI runs, over JNI
 * ([NativeEngine]). It replaced sherpa-onnx on 2026-10-03, after the same
 * text through both scored 3.5% and 53% word error on TalkBack's
 * announcements and 1.5% and 3.4% on prose (`android/docs/engine-swap.md`).
 *
 * Loading takes about half a second on a Galaxy S25 (0.43-0.67 s measured)
 * and a few hundred megabytes of RAM, so the engine
 * is a process-wide singleton shared by the reader UI, the scratchpad and the
 * system TTS service - all three of which can be alive at once. Synthesis is
 * serialised: the engine keeps per-generation state, so two at once would
 * interleave into noise.
 */
class PocketTts private constructor(
    private val engine: NativeEngine,
    private val modelManager: ModelManager,
) {

    val sampleRate: Int = engine.sampleRate

    private val synthesisLock = Mutex()

    /** A voice embedding, loaded into the engine once and reused. */
    class LoadedVoice internal constructor(val id: String, internal val handle: Long) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is LoadedVoice && id == other.id)

        override fun hashCode(): Int = id.hashCode()
    }

    // Written from Dispatchers.IO by both the reader and the system engine, so
    // not a plain map.
    private val voiceCache = ConcurrentHashMap<String, LoadedVoice>()

    suspend fun loadVoice(voice: VoiceCatalog.Voice): LoadedVoice = withContext(Dispatchers.IO) {
        voiceCache[voice.id]?.let { return@withContext it }
        val file = modelManager.ensureEmbedding(voice)
        synchronized(voiceCache) {
            voiceCache[voice.id] ?: LoadedVoice(voice.id, engine.loadVoice(file.absolutePath))
                .also { voiceCache[voice.id] = it }
        }
    }

    /**
     * A voice cloned from the user's own recording.
     *
     * Not available on this engine: pocket-speak is conditioned on precomputed
     * embeddings, and making one from a wav needs the Mimi encoder, which its
     * bundle does not carry. Callers fall back to a stock voice. Bringing
     * cloning back is a documented later step (`android/docs/engine-swap.md`).
     */
    @Suppress("UNUSED_PARAMETER")
    fun loadVoiceFile(id: String, file: File): LoadedVoice =
        throw UnsupportedOperationException("Cloned voices are not supported by this engine yet")

    /**
     * Synthesises [text] and hands audio to [onAudio] as it is produced.
     *
     * @param speed 1 is the voice's own pace. Anything else is applied after
     *   generation by Sonic, which changes tempo and keeps pitch; the model
     *   has no speed input of its own.
     * @param numSteps unused: this engine decodes the flow in one step, as the
     *   reference implementation does. Kept so the setting's callers compile.
     * @param temperature width of the neighbourhood the speaker is drawn from.
     * @param seed restarts sampling from this seed, or -1 to keep drawing.
     * @param cancel stops this generation at the next frame once cancelled, or
     *   at the first if it already was. Its owner makes it before calling, so
     *   a cancel during the wait for [synthesisLock] is not lost.
     * @param onAudio receives float samples, clamped to [-1, 1]; return false
     *   to abandon the rest of this utterance.
     * @return false if generation was stopped early.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun synthesize(
        text: String,
        voice: LoadedVoice,
        speed: Float,
        numSteps: Int,
        temperature: Float,
        seed: Int,
        cancel: NativeEngine.CancelToken? = null,
        onAudio: (FloatArray) -> Boolean,
    ): Boolean = synthesisLock.withLock {
        withContext(Dispatchers.Default) {
            var completed = true
            val stretch = Stretch.forSpeed(speed, sampleRate)
            val startedAt = System.currentTimeMillis()
            var firstSampleMillis = -1L
            val emit: (FloatArray) -> Boolean = { samples ->
                if (samples.isEmpty() || onAudio(samples)) true else {
                    completed = false
                    false
                }
            }
            try {
                val stats = engine.synthesize(voice.handle, text, temperature, seed.toLong(), cancel) { samples ->
                    if (firstSampleMillis < 0) firstSampleMillis = System.currentTimeMillis() - startedAt
                    // The decoder can overshoot full scale. Sonic narrows
                    // floats to 16-bit without a clamp, so an overshoot would
                    // wrap into a full-scale click; clamped here for both paths.
                    for (i in samples.indices) samples[i] = samples[i].coerceIn(-1f, 1f)
                    emit(stretch?.process(samples) ?: samples)
                }
                val end = runCatching { JSONObject(stats).optString("end") }.getOrNull()
                if (end == "Cancelled") completed = false
                // Sonic's tail only for a chunk that finished.
                if (completed && stretch != null) emit(stretch.drain())
            } finally {
                VoiceTrace.generated(
                    voiceId = voice.id,
                    promptSamples = 0,
                    promptRate = sampleRate,
                    promptHash = voice.handle.hashCode(),
                    temperature = temperature,
                    seed = seed,
                    firstSampleMillis = firstSampleMillis,
                )
            }
            completed
        }
    }

    /** Sonic, set to one speed for one chunk. Null at speed 1. */
    internal class Stretch private constructor(private val sonic: Sonic) {
        fun process(samples: FloatArray): FloatArray {
            sonic.writeFloatToStream(samples, samples.size)
            return read()
        }

        fun drain(): FloatArray {
            sonic.flushStream()
            return read()
        }

        private fun read(): FloatArray {
            val out = FloatArray(sonic.samplesAvailable())
            val n = if (out.isEmpty()) 0 else sonic.readFloatFromStream(out, out.size)
            return if (n == out.size) out else out.copyOf(n)
        }

        companion object {
            fun forSpeed(speed: Float, sampleRate: Int): Stretch? =
                if (kotlin.math.abs(speed - 1f) < 0.01f) {
                    null
                } else {
                    Stretch(Sonic(sampleRate, 1).apply { this.speed = speed })
                }
        }
    }

    companion object {
        private const val TAG = "PocketTts"

        @Volatile
        private var instance: PocketTts? = null
        private val loadLock = Mutex()

        /**
         * Returns the shared engine, loading it if necessary. Suspends for as
         * long as the model takes to download on first run.
         */
        suspend fun get(
            context: Context,
            progress: ModelManager.ProgressListener? = null,
        ): PocketTts {
            instance?.let { return it }
            return loadLock.withLock {
                instance ?: create(context.applicationContext, progress).also { instance = it }
            }
        }

        /** The already-loaded engine, if any. Never triggers a load. */
        fun peek(): PocketTts? = instance

        private suspend fun create(
            context: Context,
            progress: ModelManager.ProgressListener?,
        ): PocketTts = withContext(Dispatchers.IO) {
            val manager = ModelManager(context)
            // Through the shared installer rather than straight to the manager,
            // so a read that arrives while the download button's transfer is
            // running joins it instead of starting a second one over the same
            // files.
            val files = ModelInstall.ensure(context)
            val settings = Settings(context)

            Log.i(TAG, "Loading pocket-speak's engine from ${files.dir}")
            // Timed because the process is killed while cached routinely, and
            // this is paid again on the next read.
            val startedAt = System.currentTimeMillis()
            val engine = NativeEngine.load(files.dir.absolutePath, settings.numThreads)
            Metrics.modelLoadMillis = System.currentTimeMillis() - startedAt
            manager.removeRetiredModel()
            PocketTts(engine, manager)
        }
    }
}
