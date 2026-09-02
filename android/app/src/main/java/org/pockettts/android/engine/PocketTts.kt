package org.pockettts.android.engine

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.pockettts.android.debug.Metrics
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the one loaded copy of the model.
 *
 * Loading takes a few seconds and a few hundred megabytes of RAM, so the engine
 * is a process-wide singleton shared by the reader UI, the scratchpad and the
 * system TTS service - all three of which can be alive at once.
 *
 * Synthesis is serialised: sherpa-onnx keeps mutable decoder state per engine
 * instance, so two concurrent generations would interleave into noise.
 */
class PocketTts private constructor(
    private val tts: OfflineTts,
    private val modelManager: ModelManager,
) {

    val sampleRate: Int get() = tts.sampleRate()

    private val synthesisLock = Mutex()

    /** A voice prompt, already decoded to the float samples sherpa-onnx wants. */
    data class LoadedVoice(val id: String, val samples: FloatArray, val sampleRate: Int) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is LoadedVoice && id == other.id)

        override fun hashCode(): Int = id.hashCode()
    }

    // Written from Dispatchers.IO by both the reader and the system engine, so
    // not a plain map.
    private val voiceCache = ConcurrentHashMap<String, LoadedVoice>()

    suspend fun loadVoice(voice: VoiceCatalog.Voice): LoadedVoice = withContext(Dispatchers.IO) {
        voiceCache[voice.id]?.let { return@withContext it }
        val file = modelManager.ensureVoice(voice)
        loadVoiceFile(voice.id, file)
    }

    suspend fun loadVoiceFile(id: String, file: File): LoadedVoice = withContext(Dispatchers.IO) {
        voiceCache[id]?.let { return@withContext it }
        val audio = WavReader.read(file)
        // Pocket TTS conditions on a short prompt; a long one costs encode time
        // on first use and buys nothing. sherpa-onnx caps it internally too,
        // but trimming here keeps the wav we hold in memory small.
        val trimmed = if (audio.durationSeconds > MAX_PROMPT_SECONDS) {
            audio.samples.copyOf((MAX_PROMPT_SECONDS * audio.sampleRate).toInt())
        } else {
            audio.samples
        }
        LoadedVoice(id, trimmed, audio.sampleRate).also { voiceCache[id] = it }
    }

    /**
     * Synthesises [text] and hands audio to [onAudio] as it is produced.
     *
     * @param numSteps Euler steps per frame when decoding the flow. See
     *   [Settings.decodeSteps]: sherpa-onnx defaults to 5, the reference
     *   implementation to 1.
     * @param onAudio receives float samples in [-1, 1]; return false to abandon
     *   the rest of this utterance.
     * @return false if generation was stopped early.
     */
    suspend fun synthesize(
        text: String,
        voice: LoadedVoice,
        speed: Float,
        numSteps: Int,
        onAudio: (FloatArray) -> Boolean,
    ): Boolean = synthesisLock.withLock {
        withContext(Dispatchers.Default) {
            var completed = true
            val config = GenerationConfig(
                speed = speed,
                referenceAudio = voice.samples,
                referenceSampleRate = voice.sampleRate,
                numSteps = numSteps,
            )
            tts.generateWithConfigAndCallback(
                text,
                config,
                audioCallback { samples ->
                    if (onAudio(samples)) true else {
                        completed = false
                        false
                    }
                },
            )
            completed
        }
    }

    fun release() {
        voiceCache.clear()
        tts.release()
    }

    companion object {
        private const val TAG = "PocketTts"
        private const val MAX_PROMPT_SECONDS = 10f

        /**
         * Wraps the audio callback in a real class rather than a lambda.
         *
         * sherpa-onnx's JNI resolves this callback by name and exact signature -
         * `invoke([F)Ljava/lang/Integer;`. Kotlin 2.0 compiles lambdas with
         * `invokedynamic` by default, and D8 desugars those into
         * `$$ExternalSyntheticLambda` classes carrying only the erased
         * `invoke(Object)Object`. The specialised method the JNI looks for is
         * simply absent, so `GetMethodID` fails, and because the JNI call
         * proceeds with a pending exception the runtime aborts the process -
         * a native crash with no Java stack trace, from ordinary Kotlin.
         *
         * An object expression generates an ordinary class with both the
         * specialised method and its bridge, which is what the lookup needs.
         * `PocketTtsCallbackTest` asserts that signature exists.
         *
         * @param onAudio returns true to keep generating, false to stop.
         */
        fun audioCallback(onAudio: (FloatArray) -> Boolean): Function1<FloatArray, Int> =
            object : Function1<FloatArray, Int> {
                // sherpa-onnx reads the result as "1 to keep going, 0 to stop".
                override fun invoke(samples: FloatArray): Int = if (onAudio(samples)) 1 else 0
            }

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

            Log.i(TAG, "Loading Pocket TTS from ${files.lmMain.parentFile}")
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    pocket = OfflineTtsPocketModelConfig(
                        lmFlow = files.lmFlow.absolutePath,
                        lmMain = files.lmMain.absolutePath,
                        encoder = files.encoder.absolutePath,
                        decoder = files.decoder.absolutePath,
                        textConditioner = files.textConditioner.absolutePath,
                        vocabJson = files.vocabJson.absolutePath,
                        tokenScoresJson = files.tokenScoresJson.absolutePath,
                    ),
                    numThreads = settings.numThreads,
                    debug = false,
                    provider = "cpu",
                ),
            )
            // Timed because the process is killed while cached routinely, and
            // this is paid again on the next read. Whether that is worth
            // holding the model in memory depends on the number.
            val startedAt = System.currentTimeMillis()
            val tts = OfflineTts(assetManager = null, config = config)
            Metrics.modelLoadMillis = System.currentTimeMillis() - startedAt
            PocketTts(tts, manager)
        }
    }
}
