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

    /** Whether fenced code blocks in Markdown are read out. */
    var speakCodeBlocks: Boolean
        get() = prefs.getBoolean(KEY_CODE_BLOCKS, false)
        set(value) = prefs.edit { putBoolean(KEY_CODE_BLOCKS, value) }

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

        const val MAX_BLUR_DP = 80f
        const val MAX_CORNER_DP = 48f

        /**
         * AOSP's own window-blur sample uses alpha 170 of 255 with blur active,
         * which is the anchor for this default. Written as 0.67 rather than
         * 170f/255f so it lands on the appearance slider's 0.01 step grid -
         * 0.6666667 does not, and Material's Slider throws on values that miss
         * the grid.
         */
        const val DEFAULT_GLASS_ALPHA = 0.67f
        const val DEFAULT_GLASS_BLUR_DP = 48f
        const val DEFAULT_GLASS_DIM = 0.35f
        const val DEFAULT_GLASS_CORNER_DP = 28f

        private const val KEY_VOICE = "voice"
        private const val KEY_SPEED = "speed"
        private const val KEY_THREADS = "threads"
        private const val KEY_CODE_BLOCKS = "speak_code_blocks"
        private const val KEY_SELECTION_MARKDOWN = "selection_markdown"
        private const val KEY_SCRATCHPAD = "scratchpad"
        private const val KEY_GLASS_ALPHA = "glass_alpha"
        private const val KEY_GLASS_BLUR = "glass_blur_dp"
        private const val KEY_GLASS_DIM = "glass_dim"
        private const val KEY_GLASS_CORNER = "glass_corner_dp"
    }
}
