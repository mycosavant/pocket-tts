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

    /** Embedding sha256s at the pinned commit in ModelManager.EMBEDDINGS_URL. */
    private val SHAS: Map<String, String> = mapOf(
        "alba" to "69c32db63ca56843d994f81f343f62e0bf2d73f7e4c9bc73e44bb1110b1d8845",
        "anna" to "5ea82f78db006c9fd34e32ddd5aae82674b5b32646097977436458d00af80dfa",
        "azelma" to "9f3e69f29075f991fd47774566865ef0e0e637cb5a35992c9919761b5b84b1de",
        "bill_boerst" to "75610127d44e0b05b442154f80f89f993df235aecc6cad7070f11000d006c188",
        "caro_davy" to "a5961b63a2e7a5cfd7edc383aa9042fb70fd14a9dee6310cdc633881a7f2449a",
        "charles" to "299edc20182eeccfbf94e308626f259da4fbf339daa8d5905218f2b1774639b8",
        "cosette" to "c4fdc15f5a3a20c44dd0064a37e87d15d25562936e8dbad7e07b9832015a545d",
        "eponine" to "bda3b76a384ff355fe0350736387765946304ae8ca16e59f60ea3296a1c99cc6",
        "estelle" to "ccebef7f51762c7fc08870f5ecf268e8713551ae2c9f7984ddaec0c1e1c77153",
        "eve" to "ea9c2faf862a6c9d2cb61910fdf02842ae56940382cc8c1000fdb1b43269692b",
        "fantine" to "51a8a4355d7f912d4959e4b1918314fda85ad47eba0a33a1d78a4a505d3465f5",
        "george" to "0c1c6c57c55a98d81254b33728150c7776f40647fe95258d1a6c1a02780b5d02",
        "giovanni" to "a5ee718157ec1c6fd9c1e66a7733c7e6337d474a74a7da756455d27717885c59",
        "jane" to "37386227ca8ec5bf1b8e516c13d132ce5ff5437a304fe90129a1c62f41d9a008",
        "javert" to "0ae88e03ca4e76a0e16cbf321a807428febda9d9e9bc0358c02e7f9c9e2c263b",
        "jean" to "90be4b8f50bb4d2dbe27e3fb4e31417cf6a57928931f0a60426a1748821a3d12",
        "juergen" to "e74d67b38339fc01118e3bbe2c90d6d40e601b161dbf7be906421956ba80a532",
        "lola" to "34972e86b07b17272a8061460054a08119399cace9454979052b9e2d96664e8c",
        "marius" to "04f84efcb77a0547ba582c058db496f7ff4920891d49d37b9950d128422582a8",
        "mary" to "a8f2adf260cab966fe0a113d6b549d6efdeaa79de544ae0ff34b5b6a41445a59",
        "michael" to "8937f724ac4719b9aa51ea0ba1f18f9de0af7a663ad6263558266c1a53c9722d",
        "paul" to "ed7a019168f94dfe77009f1b0de59387abc6fbb0db954d38ce722ecb77da61aa",
        "peter_yearsley" to "dd977a6e15591e347c9a23fa7cc09e35a65b462917f5eeb162baff6dc9e3f685",
        "rafael" to "ac19f099f6cd839875a629c3e2e91e0dfc2c197acf2875db168d0ae244fb58bd",
        "stuart_bell" to "5a49da7ca5df05d02587ec4a0981c0d318e045f68e24423c4203ce474d9b33dc",
        "vera" to "4bf50ddd957b5d218b264fdcf18efbbc7384d12da3eca98ca19b9e8dd6976acc",
    )

    private const val BASE = "https://huggingface.co/kyutai/tts-voices/resolve/main/"

    data class Voice(
        val id: String,
        val displayName: String,
        /** BCP-47 tag, for the Android TTS voice list. */
        val language: String,
        val path: String,
        /**
         * The prompt's exact size on Hugging Face.
         *
         * Here because a cached prompt is otherwise unidentifiable. An import
         * named like a stock voice used to overwrite that voice's file, and
         * nothing ever fetched it again - so the app would read every "Alba"
         * from that day on in a voice that was not Alba, with no error and no
         * way to tell from inside the app. A size is the cheapest check that
         * catches it, and the CDN serves an exact content-length.
         */
        val bytes: Long,
        /**
         * sha256 of this voice's embedding in `kyutai/pocket-tts-without-voice-cloning`,
         * which is what the engine is conditioned on. The wav above is only
         * the preview the picker plays.
         */
        val embeddingSha256: String = SHAS.getValue(id),
    ) {
        val url: String get() = BASE + path
        /** Stable filename for the cached wav. */
        val fileName: String get() = "$id.wav"
    }

    val voices: List<Voice> = listOf(
        Voice("alba", "Alba", "en-GB", "alba-mackenna/casual.wav", 958542),
        Voice("anna", "Anna", "en-GB", "vctk/p228_023_enhanced.wav", 804630),
        Voice("azelma", "Azelma", "en-GB", "vctk/p303_023_enhanced.wav", 823852),
        Voice("bill_boerst", "Bill Boerst", "en-US", "voice-zero/bill_boerst.wav", 955496),
        Voice("caro_davy", "Caro Davy", "en-US", "voice-zero/caro_davy.wav", 743528),
        Voice("charles", "Charles", "en-GB", "vctk/p254_023_enhanced.wav", 639272),
        Voice(
            "cosette", "Cosette", "en-US",
            "expresso/ex04-ex02_confused_001_channel1_499s.wav", 960044,
        ),
        Voice("eponine", "Eponine", "en-GB", "vctk/p262_023_enhanced.wav", 716330),
        Voice("eve", "Eve", "en-GB", "vctk/p361_023_enhanced.wav", 671872),
        Voice("fantine", "Fantine", "en-GB", "vctk/p244_023_enhanced.wav", 674852),
        Voice("george", "George", "en-GB", "vctk/p315_023_enhanced.wav", 642692),
        Voice("jane", "Jane", "en-GB", "vctk/p339_023_enhanced.wav", 759340),
        Voice("javert", "Javert", "en-US", "voice-donations/Butter.wav", 480044),
        Voice("jean", "Jean", "en-US", "ears/p010/freeform_speech_01_enhanced.wav", 640044),
        Voice("marius", "Marius", "en-US", "voice-donations/Selfie.wav", 480044),
        Voice("mary", "Mary", "en-GB", "vctk/p333_023_enhanced.wav", 639084),
        Voice("michael", "Michael", "en-GB", "vctk/p360_023_enhanced.wav", 751140),
        Voice("paul", "Paul", "en-GB", "vctk/p259_023_enhanced.wav", 717182),
        Voice("peter_yearsley", "Peter Yearsley", "en-US", "voice-zero/peter_yearsley.wav", 524448),
        Voice("stuart_bell", "Stuart Bell", "en-US", "voice-zero/stuart_bell.wav", 745776),
        Voice("vera", "Vera", "en-GB", "vctk/p229_023_enhanced.wav", 691416),
    )

    const val DEFAULT_VOICE_ID = "alba"


    fun byId(id: String?): Voice? = voices.firstOrNull { it.id == id }

    fun default(): Voice = byId(DEFAULT_VOICE_ID) ?: voices.first()
}
