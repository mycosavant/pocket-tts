package org.pockettts.android.player

import android.content.Context
import org.pockettts.android.engine.ModelManager
import org.pockettts.android.engine.PocketTts
import org.pockettts.android.engine.VoiceCatalog

/** [SpeechEngine] backed by the real model. */
class PocketTtsEngine(
    private val context: Context,
    private val tts: PocketTts,
) : SpeechEngine {

    private var voice: PocketTts.LoadedVoice? = null

    override val sampleRate: Int get() = tts.sampleRate

    override suspend fun useVoice(voiceId: String) {
        voice = resolve(voiceId)
    }

    override suspend fun synthesize(
        text: String,
        speed: Float,
        onAudio: (FloatArray) -> Boolean,
    ): Boolean {
        val loaded = voice ?: resolve(VoiceCatalog.DEFAULT_VOICE_ID).also { voice = it }
        return tts.synthesize(text, loaded, speed, onAudio)
    }

    private suspend fun resolve(voiceId: String): PocketTts.LoadedVoice {
        VoiceCatalog.byId(voiceId)?.let { return tts.loadVoice(it) }
        // Not a stock voice, so it is one the user imported.
        val imported = ModelManager(context).voiceFile(voiceId)
        if (imported.isFile) return tts.loadVoiceFile(voiceId, imported)
        return tts.loadVoice(VoiceCatalog.default())
    }

    companion object : SpeechEngine.Factory {
        override suspend fun create(
            context: Context,
            progress: (Float) -> Unit,
        ): SpeechEngine = PocketTtsEngine(context, PocketTts.get(context, progress))
    }
}
