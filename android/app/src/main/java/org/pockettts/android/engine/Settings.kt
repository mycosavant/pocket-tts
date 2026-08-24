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

    var scratchpad: String
        get() = prefs.getString(KEY_SCRATCHPAD, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SCRATCHPAD, value) }

    fun voice(): VoiceCatalog.Voice = VoiceCatalog.byId(voiceId) ?: VoiceCatalog.default()

    companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
        private const val DEFAULT_THREADS = 2

        private const val KEY_VOICE = "voice"
        private const val KEY_SPEED = "speed"
        private const val KEY_THREADS = "threads"
        private const val KEY_CODE_BLOCKS = "speak_code_blocks"
        private const val KEY_SELECTION_MARKDOWN = "selection_markdown"
        private const val KEY_SCRATCHPAD = "scratchpad"
    }
}
