package org.pockettts.android.engine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.runBlocking
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

    // The framework declares these protected, and they are widened here so
    // they can be called from a test. This is the surface other apps drive,
    // in whatever order they like, and it had no tests at all - which is a
    // worse problem than a slightly larger public API on a service nothing
    // else in this app talks to.

    public override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int =
        when {
            lang == null -> TextToSpeech.LANG_NOT_SUPPORTED
            !lang.equals(ISO3_ENGLISH, ignoreCase = true) -> TextToSpeech.LANG_NOT_SUPPORTED
            country.isNullOrEmpty() -> TextToSpeech.LANG_AVAILABLE
            supportedCountries.any { it.equals(country, ignoreCase = true) } ->
                TextToSpeech.LANG_COUNTRY_AVAILABLE

            else -> TextToSpeech.LANG_AVAILABLE
        }

    public override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    public override fun onGetLanguage(): Array<String> {
        val voice = VoiceCatalog.byId(Settings(this).voiceId) ?: VoiceCatalog.default()
        val locale = Locale.forLanguageTag(voice.language)
        return arrayOf(ISO3_ENGLISH, locale.isO3Country.orEmpty(), "")
    }

    public override fun onGetVoices(): MutableList<Voice> {
        // Until the model is downloaded nothing here can speak. Saying so in
        // the voice's own feature set is what makes a picker offer to install
        // it rather than let someone choose a voice and hear silence.
        val features =
            if (ModelManager(this).isModelInstalled) {
                emptySet()
            } else {
                setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            }

        val voices = VoiceCatalog.voices.map { entry ->
            Voice(
                entry.id,
                Locale.forLanguageTag(entry.language),
                Voice.QUALITY_VERY_HIGH,
                // Everything runs on the phone's CPU, so latency is real but
                // there is no network round trip.
                Voice.LATENCY_NORMAL,
                false,
                features,
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
                features,
            )
        }
        return voices
    }

    public override fun onIsValidVoiceName(name: String?): Int =
        if (name != null && resolveVoiceFile(name) != null) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    public override fun onLoadVoice(name: String?): Int = onIsValidVoiceName(name)

    public override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String =
        Settings(this).voiceId

    public override fun onStop() {
        stopRequested.set(true)
    }

    /**
     * What this engine will say, given what a caller asked for.
     *
     * Deliberately not stripped as Markdown, and separated out so that
     * decision can be held in place by a test.
     *
     * Text arriving here was chosen by another app - Select to Speak, a
     * reader, a browser - and is almost always prose already. Running it
     * through the Markdown pipeline rewrote it on the way past: asterisks
     * vanished from ordinary sentences, snake_case identifiers changed shape,
     * and links were replaced by nothing at all. An accessibility tool exists
     * to tell someone what is on the screen, and quietly deleting a URL from
     * that is not a formatting choice, it is a false account of the content.
     *
     * Passing it through also keeps the chunk offsets meaningful, which is what
     * makes `rangeStart` - and so highlight-as-it-reads in Chrome and Select to
     * Speak - possible at all. The app's own screens still strip, because there
     * the text is the user's own Markdown and they asked for it to be read as
     * prose.
     */
    internal fun speakableFor(request: SynthesisRequest?): String =
        request?.charSequenceText?.toString().orEmpty().trim()

    public override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) return
        stopRequested.set(false)

        val settings = Settings(this)
        val speakable = speakableFor(request)
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

        // Claims the engine, so an in-app read standing on it gives way rather
        // than the two of them taking turns sentence by sentence in two
        // different voices. See EngineTurn.
        val turn = EngineTurn.take()

        try {
            runBlocking {
                val engine = PocketTts.get(this@PocketTtsService)
                val voice = loadRequestedVoice(engine, request.voiceName ?: settings.voiceId)

                // SynthesisRequest reports the rate as a percentage, where 100
                // is the user's normal speed.
                val speed = (request.speechRate / 100f)
                    .coerceIn(Settings.MIN_SPEED, Settings.MAX_SPEED)
                val steps = settings.decodeSteps

                callback.start(engine.sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
                val maxBytes = callback.maxBufferSize

                for (chunk in TextChunker.chunk(speakable)) {
                    if (stopRequested.get() || EngineTurn.superseded(turn)) break
                    // Where in the request this chunk is, so the caller can
                    // highlight along. Chrome and Select to Speak both use it,
                    // and it costs one call - the offsets are only meaningful
                    // because the text above is passed through unrewritten.
                    callback.rangeStart(chunk.start, chunk.end, 0)
                    val completed = engine.synthesize(chunk.text, voice, speed, steps) { samples ->
                        val giveUp = stopRequested.get() || EngineTurn.superseded(turn)
                        if (giveUp) false else deliver(callback, samples, maxBytes)
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
            // not the wav happens to be cached yet. Asked of the manager rather
            // than rebuilt from a path literal here, which is how the two got
            // to disagree in the first place.
            return manager.cachedVoice(stock) ?: manager.voiceFile(name)
        }
        return manager.voiceFile(name).takeIf { it.isFile }
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
