package org.pockettts.android.engine

/**
 * The stock Pocket TTS voices.
 *
 * Pocket TTS has no fixed speaker table: a "voice" is just a few seconds of
 * reference audio that the model conditions on. So a voice here is a URL to a
 * wav, and adding your own is the same operation as picking a stock one.
 *
 * Paths are relative to the `kyutai/tts-voices` repository on Hugging Face,
 * whose per-voice licences are listed at
 * https://huggingface.co/kyutai/tts-voices.
 */
object VoiceCatalog {

    private const val BASE = "https://huggingface.co/kyutai/tts-voices/resolve/main/"

    data class Voice(
        val id: String,
        val displayName: String,
        /** BCP-47 tag, for the Android TTS voice list. */
        val language: String,
        val path: String,
    ) {
        val url: String get() = BASE + path
        /** Stable filename for the cached wav. */
        val fileName: String get() = "$id.wav"
    }

    val voices: List<Voice> = listOf(
        Voice("alba", "Alba", "en-GB", "alba-mackenna/casual.wav"),
        Voice("anna", "Anna", "en-GB", "vctk/p228_023_enhanced.wav"),
        Voice("azelma", "Azelma", "en-GB", "vctk/p303_023_enhanced.wav"),
        Voice("bill_boerst", "Bill Boerst", "en-US", "voice-zero/bill_boerst.wav"),
        Voice("caro_davy", "Caro Davy", "en-US", "voice-zero/caro_davy.wav"),
        Voice("charles", "Charles", "en-GB", "vctk/p254_023_enhanced.wav"),
        Voice(
            "cosette", "Cosette", "en-US",
            "expresso/ex04-ex02_confused_001_channel1_499s.wav",
        ),
        Voice("eponine", "Eponine", "en-GB", "vctk/p262_023_enhanced.wav"),
        Voice("eve", "Eve", "en-GB", "vctk/p361_023_enhanced.wav"),
        Voice("fantine", "Fantine", "en-GB", "vctk/p244_023_enhanced.wav"),
        Voice("george", "George", "en-GB", "vctk/p315_023_enhanced.wav"),
        Voice("jane", "Jane", "en-GB", "vctk/p339_023_enhanced.wav"),
        Voice("javert", "Javert", "en-US", "voice-donations/Butter.wav"),
        Voice("jean", "Jean", "en-US", "ears/p010/freeform_speech_01_enhanced.wav"),
        Voice("marius", "Marius", "en-US", "voice-donations/Selfie.wav"),
        Voice("mary", "Mary", "en-GB", "vctk/p333_023_enhanced.wav"),
        Voice("michael", "Michael", "en-GB", "vctk/p360_023_enhanced.wav"),
        Voice("paul", "Paul", "en-GB", "vctk/p259_023_enhanced.wav"),
        Voice("peter_yearsley", "Peter Yearsley", "en-US", "voice-zero/peter_yearsley.wav"),
        Voice("stuart_bell", "Stuart Bell", "en-US", "voice-zero/stuart_bell.wav"),
        Voice("vera", "Vera", "en-GB", "vctk/p229_023_enhanced.wav"),
    )

    const val DEFAULT_VOICE_ID = "alba"

    fun byId(id: String?): Voice? = voices.firstOrNull { it.id == id }

    fun default(): Voice = byId(DEFAULT_VOICE_ID) ?: voices.first()
}
