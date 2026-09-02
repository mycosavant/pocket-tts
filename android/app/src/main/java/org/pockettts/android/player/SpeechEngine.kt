package org.pockettts.android.player

import android.content.Context

/**
 * The synthesis half of reading, behind an interface the reader can be tested against.
 *
 * `Reader` used to build `PocketTts` and `StreamingPlayer` itself, which meant
 * nothing in this package could be exercised without a 98 MB model and real
 * audio hardware. Two consequences, both bad: the reader's state machine - the
 * part that has actually been wrong - had no tests at all, and
 * `ActivityLaunchTest` reached `ensureModel`, so the unit suite downloaded the
 * model bundle from GitHub on every single run.
 *
 * A voice is selected on the engine rather than passed to each call, because
 * that is how an utterance uses it: one voice, many chunks. It also keeps the
 * loaded-voice representation an implementation detail.
 */
interface SpeechEngine {

    val sampleRate: Int

    /** Loads [voiceId], falling back to the default voice if it cannot be found. */
    suspend fun useVoice(voiceId: String)

    /**
     * Synthesises [text], handing samples to [onAudio] as they are produced.
     *
     * @return false if [onAudio] asked to stop before the end.
     */
    suspend fun synthesize(
        text: String,
        speed: Float,
        onAudio: (FloatArray) -> Boolean,
    ): Boolean

    fun interface Factory {
        /** @param progress fraction 0..1 while fetching, or -1 when the size is unknown. */
        suspend fun create(context: Context, progress: (Float) -> Unit): SpeechEngine
    }
}
