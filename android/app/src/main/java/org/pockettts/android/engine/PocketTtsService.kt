package org.pockettts.android.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.pockettts.android.speech.MarkdownSpeech
import org.pockettts.android.speech.TextChunker
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exposes Pocket TTS as a system-wide Android text-to-speech engine.
 *
 * This is the part that does the most work for the least ceremony. Once this
 * service is selected in Settings, every app that already knows how to read
 * text - Select to Speak, Chrome's read-aloud, ebook readers, accessibility
 * tools - speaks in a Pocket TTS voice, without any of them knowing this app
 * exists.
 *
 * Audio is handed back chunk by chunk as it is generated rather than at the
 * end, so the caller starts playing after the first sentence instead of after
 * the last one.
 */
class PocketTtsService : TextToSpeechService() {

    private val stopRequested = AtomicBoolean(false)

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        when {
            lang == null -> TextToSpeech.LANG_NOT_SUPPORTED
            !lang.equals(ISO3_ENGLISH, ignoreCase = true) -> TextToSpeech.LANG_NOT_SUPPORTED
            country.isNullOrEmpty() -> TextToSpeech.LANG_AVAILABLE
            supportedCountries.any { it.equals(country, ignoreCase = true) } ->
                TextToSpeech.LANG_COUNTRY_AVAILABLE

            else -> TextToSpeech.LANG_AVAILABLE
        }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> {
        val voice = VoiceCatalog.byId(Settings(this).voiceId) ?: VoiceCatalog.default()
        val locale = Locale.forLanguageTag(voice.language)
        return arrayOf(ISO3_ENGLISH, locale.isO3Country.orEmpty(), "")
    }

    override fun onGetVoices(): MutableList<Voice> {
        val voices = VoiceCatalog.voices.map { entry ->
            Voice(
                entry.id,
                Locale.forLanguageTag(entry.language),
                Voice.QUALITY_VERY_HIGH,
                // Everything runs on the phone's CPU, so latency is real but
                // there is no network round trip.
                Voice.LATENCY_NORMAL,
                false,
                emptySet(),
            )
        }.toMutableList()

        // Voices the user cloned from their own audio show up alongside the
        // stock ones, so any app's voice picker can select them.
        ModelManager(this).importedVoices().forEach { file ->
            voices += Voice(
                file.nameWithoutExtension,
                Locale.ENGLISH,
                Voice.QUALITY_VERY_HIGH,
                Voice.LATENCY_NORMAL,
                false,
                emptySet(),
            )
        }
        return voices
    }

    override fun onIsValidVoiceName(name: String?): Int =
        if (name != null && resolveVoiceFile(name) != null) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String =
        Settings(this).voiceId

    override fun onStop() {
        stopRequested.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested.set(false)

        val settings = Settings(this)
        val raw = request.charSequenceText?.toString().orEmpty()
        val speakable = MarkdownSpeech.toSpeakable(
            raw,
            MarkdownSpeech.Options(speakCodeBlocks = settings.speakCodeBlocks),
        )
        if (speakable.isBlank()) {
            callback.start(SAMPLE_RATE_FALLBACK, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        // Downloading 98 MB inside a synthesis request would look like a hang to
        // whichever app asked for the speech, so fail and let the app's own
        // first-run screen fetch the model.
        if (!ModelManager(this).isModelInstalled) {
            Log.w(TAG, "Refusing to synthesise: model is not downloaded yet")
            callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
            return
        }

        try {
            runBlocking {
                val engine = PocketTts.get(this@PocketTtsService)
                val voice = loadRequestedVoice(engine, request.voiceName ?: settings.voiceId)

                // SynthesisRequest reports the rate as a percentage, where 100
                // is the user's normal speed.
                val speed = (request.speechRate / 100f)
                    .coerceIn(Settings.MIN_SPEED, Settings.MAX_SPEED)

                callback.start(engine.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
                val maxBytes = callback.maxBufferSize

                for (chunk in TextChunker.chunk(speakable)) {
                    if (stopRequested.get()) break
                    val completed = engine.synthesize(chunk.text, voice, speed) { samples ->
                        if (stopRequested.get()) false else deliver(callback, samples, maxBytes)
                    }
                    if (!completed) break
                    if (chunk.trailingPauseSeconds > 0f) {
                        val silence = FloatArray((chunk.trailingPauseSeconds * engine.sampleRate).toInt())
                        if (!deliver(callback, silence, maxBytes)) break
                    }
                }
                callback.done()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Synthesis failed", error)
            callback.error(TextToSpeech.ERROR_SYNTHESIS)
        }
    }

    private suspend fun loadRequestedVoice(
        engine: PocketTts,
        name: String,
    ): PocketTts.LoadedVoice {
        VoiceCatalog.byId(name)?.let { return engine.loadVoice(it) }
        resolveVoiceFile(name)?.let { return engine.loadVoiceFile(name, it) }
        return engine.loadVoice(VoiceCatalog.default())
    }

    private fun resolveVoiceFile(name: String): File? {
        val manager = ModelManager(this)
        VoiceCatalog.byId(name)?.let { stock ->
            // Stock voices are downloadable, so they count as valid whether or
            // not the wav happens to be cached yet.
            return manager.cachedVoice(stock) ?: File(filesDir, "pocket-tts/voices/$name.wav")
        }
        return manager.importedVoices().firstOrNull { it.nameWithoutExtension == name }
    }

    /**
     * Converts float samples to the 16-bit PCM the framework expects and feeds
     * them to [callback] in pieces no larger than it allows.
     *
     * @return false if the caller has stopped consuming audio.
     */
    private fun deliver(
        callback: SynthesisCallback,
        samples: FloatArray,
        maxBytes: Int,
    ): Boolean {
        val maxSamples = (maxBytes / 2).coerceAtLeast(1)
        var offset = 0
        while (offset < samples.size) {
            if (stopRequested.get()) return false
            val count = minOf(maxSamples, samples.size - offset)
            val bytes = ByteArray(count * 2)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                val clamped = samples[offset + i].coerceIn(-1f, 1f)
                buffer.putShort((clamped * 32767f).toInt().toShort())
            }
            if (callback.audioAvailable(bytes, 0, bytes.size) != TextToSpeech.SUCCESS) {
                return false
            }
            offset += count
        }
        return true
    }

    private companion object {
        const val TAG = "PocketTtsService"
        const val ISO3_ENGLISH = "eng"
        const val SAMPLE_RATE_FALLBACK = 24000
        val supportedCountries = listOf("USA", "GBR")
    }
}
