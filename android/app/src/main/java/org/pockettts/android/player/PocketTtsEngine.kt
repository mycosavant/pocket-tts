package org.pockettts.android.player

import android.content.Context
import org.pockettts.android.engine.ModelManager
import org.pockettts.android.engine.PocketTts
import org.pockettts.android.engine.Settings
import org.pockettts.android.engine.VoiceCatalog
import org.pockettts.android.debug.VoiceTrace

/** [SpeechEngine] backed by the real model. */
class PocketTtsEngine(
    private val context: Context,
    private val tts: PocketTts,
) : SpeechEngine {

    private val settings = Settings(context)

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
        // Read per call rather than held, so moving a slider changes the next
        // sentence rather than the next read.
        return tts.synthesize(
            text,
            loaded,
            speed,
            settings.decodeSteps,
            settings.temperature,
            settings.voiceSeed,
            onAudio,
        )
    }

    /**
     * Finds the prompt for [voiceId], falling back to the default voice.
     *
     * The fallback is silent by necessity - there is nothing sensible to do
     * mid-read about a voice that has gone missing - but silent is how "it
     * used the wrong voice" gets to be a mystery, so it is recorded.
     */
    private suspend fun resolve(voiceId: String): PocketTts.LoadedVoice {
        val manager = ModelManager(context)
        VoiceCatalog.byId(voiceId)?.let { stock ->
            VoiceTrace.resolved(
                caller = "reader",
                requested = voiceId,
                resolved = voiceId,
                promptBytes = manager.voiceFile(voiceId).length(),
                expectedBytes = stock.bytes,
            )
            return tts.loadVoice(stock)
        }
        // Not a stock voice, so it is one the user imported.
        val imported = manager.voiceFile(voiceId)
        if (imported.isFile) {
            VoiceTrace.resolved("reader", voiceId, voiceId, imported.length(), 0)
            return tts.loadVoiceFile(voiceId, imported)
        }
        val fallback = VoiceCatalog.default()
        VoiceTrace.resolved(
            caller = "reader",
            requested = voiceId,
            resolved = fallback.id,
            promptBytes = manager.voiceFile(fallback.id).length(),
            expectedBytes = fallback.bytes,
        )
        return tts.loadVoice(fallback)
    }

    companion object : SpeechEngine.Factory {
        override suspend fun create(
            context: Context,
            progress: (Float) -> Unit,
        ): SpeechEngine = PocketTtsEngine(context, PocketTts.get(context, progress))
    }
}
