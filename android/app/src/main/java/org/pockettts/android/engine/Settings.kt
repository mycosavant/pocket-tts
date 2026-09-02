package org.pockettts.android.engine

import android.content.Context
import androidx.core.content.edit

/** User preferences, shared between the UI, the reader and the system engine. */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pocket-tts", Context.MODE_PRIVATE)

    var voiceId: String
        get() = prefs.getString(KEY_VOICE, VoiceCatalog.DEFAULT_VOICE_ID)
            ?: VoiceCatalog.DEFAULT_VOICE_ID
        set(value) = prefs.edit { putString(KEY_VOICE, value) }

    /** Playback rate multiplier; 1.0 is the model's natural pace. */
    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f).coerceIn(MIN_SPEED, MAX_SPEED)
        set(value) = prefs.edit { putFloat(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)) }

    /**
     * Pocket TTS is tuned for two cores and scales poorly past that, but
     * thread-limited phones sometimes do better with more.
     */
    var numThreads: Int
        get() = prefs.getInt(KEY_THREADS, DEFAULT_THREADS).coerceIn(1, 8)
        set(value) = prefs.edit { putInt(KEY_THREADS, value.coerceIn(1, 8)) }

    /**
     * Flow-decoding steps per audio frame.
     *
     * Each generated frame is produced by integrating a flow, and this is how
     * many Euler steps that integration takes. sherpa-onnx defaults to 5. The
     * reference implementation defaults to 1 - both in `lsd_decode` itself and
     * in `default_parameters.py` - so four fifths of that work may be buying
     * nothing at all, on a phone, per frame.
     *
     * Which it is cannot be decided from here: it is a quality-against-speed
     * trade, one side of which is only audible. So it is a slider, and the
     * default stays at what has been shipping until a device says otherwise.
     */
    var decodeSteps: Int
        get() = prefs.getInt(KEY_DECODE_STEPS, DEFAULT_DECODE_STEPS).coerceIn(MIN_STEPS, MAX_STEPS)
        set(value) = prefs.edit { putInt(KEY_DECODE_STEPS, value.coerceIn(MIN_STEPS, MAX_STEPS)) }

    /** Whether fenced code blocks in Markdown are read out. */
    var speakCodeBlocks: Boolean
        get() = prefs.getBoolean(KEY_CODE_BLOCKS, false)
        set(value) = prefs.edit { putBoolean(KEY_CODE_BLOCKS, value) }

    /**
     * Whether each sentence is conditioned on the one before it.
     *
     * Pocket TTS is prompted with a few seconds of reference audio and samples
     * a speaker in its neighbourhood, so the same prompt gives a slightly
     * different voice every call - and a read is many calls, one per sentence.
     * Handing the audio just spoken back as the prompt for the next sentence
     * continues the voice instead of drawing a new one. Upstream names this as
     * the fix, as a TODO against the same behaviour.
     *
     * A setting rather than a constant because the trade is real: conditioning
     * on generated audio can also let the voice wander over a long read, and
     * which of the two is worse can only be settled by listening.
     */
    var steadyVoice: Boolean
        get() = prefs.getBoolean(KEY_STEADY_VOICE, true)
        set(value) = prefs.edit { putBoolean(KEY_STEADY_VOICE, value) }

    /** Whether text arriving from other apps is treated as Markdown. */
    var treatSelectionAsMarkdown: Boolean
        get() = prefs.getBoolean(KEY_SELECTION_MARKDOWN, true)
        set(value) = prefs.edit { putBoolean(KEY_SELECTION_MARKDOWN, value) }

    /**
     * Glass panel appearance. Exposed as settings rather than constants so the
     * values can be dialled in on a real device - the effect depends on the
     * wallpaper, the theme and whether the device supports blur at all, none of
     * which can be judged from source.
     */
    var glassAlpha: Float
        get() = prefs.getFloat(KEY_GLASS_ALPHA, DEFAULT_GLASS_ALPHA).coerceIn(0f, 1f)
        set(value) = prefs.edit { putFloat(KEY_GLASS_ALPHA, value.coerceIn(0f, 1f)) }

    var glassBlurDp: Float
        get() = prefs.getFloat(KEY_GLASS_BLUR, DEFAULT_GLASS_BLUR_DP).coerceIn(0f, MAX_BLUR_DP)
        set(value) = prefs.edit { putFloat(KEY_GLASS_BLUR, value.coerceIn(0f, MAX_BLUR_DP)) }

    var glassDim: Float
        get() = prefs.getFloat(KEY_GLASS_DIM, DEFAULT_GLASS_DIM).coerceIn(0f, 1f)
        set(value) = prefs.edit { putFloat(KEY_GLASS_DIM, value.coerceIn(0f, 1f)) }

    var glassCornerDp: Float
        get() = prefs.getFloat(KEY_GLASS_CORNER, DEFAULT_GLASS_CORNER_DP).coerceIn(0f, MAX_CORNER_DP)
        set(value) = prefs.edit { putFloat(KEY_GLASS_CORNER, value.coerceIn(0f, MAX_CORNER_DP)) }

    fun resetGlass() {
        glassAlpha = DEFAULT_GLASS_ALPHA
        glassBlurDp = DEFAULT_GLASS_BLUR_DP
        glassDim = DEFAULT_GLASS_DIM
        glassCornerDp = DEFAULT_GLASS_CORNER_DP
    }

    var scratchpad: String
        get() = prefs.getString(KEY_SCRATCHPAD, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SCRATCHPAD, value) }

    fun voice(): VoiceCatalog.Voice = VoiceCatalog.byId(voiceId) ?: VoiceCatalog.default()

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        private const val DEFAULT_THREADS = 2

        const val MIN_STEPS = 1
        const val MAX_STEPS = 8

        /** sherpa-onnx's own default, so nothing changes until it is changed. */
        const val DEFAULT_DECODE_STEPS = 5

        const val MAX_BLUR_DP = 80f
        const val MAX_CORNER_DP = 48f

        /**
         * Dialled in on a device rather than guessed here, which is what the
         * appearance screen exists for. These are the values that came back.
         *
         * The alpha is low because the panel it governs sits over the app's own
         * content, where the blur behind it is real - it is the scratchpad
         * overlay that uses these, not the sheet over another app, which is
         * opaque by design because there is nothing behind it to frost.
         *
         * The alpha also has to land on the slider's 0.01 step grid: Material's
         * Slider throws during layout on a value that misses it, which took the
         * appearance screen down on open once already.
         */
        const val DEFAULT_GLASS_ALPHA = 0.37f
        const val DEFAULT_GLASS_BLUR_DP = 51f
        const val DEFAULT_GLASS_DIM = 0f
        const val DEFAULT_GLASS_CORNER_DP = 10f

        private const val KEY_VOICE = "voice"
        private const val KEY_SPEED = "speed"
        private const val KEY_THREADS = "threads"
        private const val KEY_DECODE_STEPS = "decode_steps"
        private const val KEY_CODE_BLOCKS = "speak_code_blocks"
        private const val KEY_SELECTION_MARKDOWN = "selection_markdown"
        private const val KEY_STEADY_VOICE = "steady_voice"
        private const val KEY_SCRATCHPAD = "scratchpad"
        private const val KEY_GLASS_ALPHA = "glass_alpha"
        private const val KEY_GLASS_BLUR = "glass_blur_dp"
        private const val KEY_GLASS_DIM = "glass_dim"
        private const val KEY_GLASS_CORNER = "glass_corner_dp"
    }
}
