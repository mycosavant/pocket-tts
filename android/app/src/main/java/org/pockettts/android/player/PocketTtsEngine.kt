package org.pockettts.android.player

import android.content.Context
import org.pockettts.android.engine.ModelManager
import org.pockettts.android.engine.PocketTts
import org.pockettts.android.engine.Settings
import org.pockettts.android.engine.VoiceCatalog

/** [SpeechEngine] backed by the real model. */
class PocketTtsEngine(
    private val context: Context,
    private val tts: PocketTts,
) : SpeechEngine {

    private val settings = Settings(context)

    private var voice: PocketTts.LoadedVoice? = null

    /** Reference audio to continue from, in place of the voice's prompt. */
    private var continuation: PocketTts.LoadedVoice? = null

    override val sampleRate: Int get() = tts.sampleRate

    override suspend fun useVoice(voiceId: String) {
        voice = resolve(voiceId)
        continuation = null
    }

    override fun continueFrom(audio: FloatArray?) {
        // The samples come straight from this engine's own output, so they are
        // already at the model's rate and need no conversion.
        continuation = audio?.takeIf { it.isNotEmpty() }
            ?.let { PocketTts.LoadedVoice(CONTINUATION_ID, it, tts.sampleRate) }
    }

    override suspend fun synthesize(
        text: String,
        speed: Float,
        onAudio: (FloatArray) -> Boolean,
    ): Boolean {
        val loaded = continuation
            ?: voice
            ?: resolve(VoiceCatalog.DEFAULT_VOICE_ID).also { voice = it }
        // Read per call rather than held, so moving the slider changes the
        // next sentence rather than the next read.
        return tts.synthesize(text, loaded, speed, settings.decodeSteps, onAudio)
    }

    private suspend fun resolve(voiceId: String): PocketTts.LoadedVoice {
        VoiceCatalog.byId(voiceId)?.let { return tts.loadVoice(it) }
        // Not a stock voice, so it is one the user imported.
        val imported = ModelManager(context).voiceFile(voiceId)
        if (imported.isFile) return tts.loadVoiceFile(voiceId, imported)
        return tts.loadVoice(VoiceCatalog.default())
    }

    companion object : SpeechEngine.Factory {

        /**
         * Not a voice id anyone can select. [PocketTts.LoadedVoice] is keyed by
         * id for the engine's voice cache, and this one must never land in it -
         * it is different audio on every chunk.
         */
        private const val CONTINUATION_ID = "\u0000continuation"

        override suspend fun create(
            context: Context,
            progress: (Float) -> Unit,
        ): SpeechEngine = PocketTtsEngine(context, PocketTts.get(context, progress))
    }
}
