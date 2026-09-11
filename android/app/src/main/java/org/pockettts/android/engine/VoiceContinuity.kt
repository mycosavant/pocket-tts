package org.pockettts.android.engine

/**
 * Carries the voice from one sentence into the next.
 *
 * Pocket TTS has no speaker table: it is handed a few seconds of reference
 * audio and generates in that audio's neighbourhood. A fixed seed already makes
 * every sentence *draw the same speaker* - that landed earlier and is what
 * stopped a paragraph being read by a succession of different people. What it
 * cannot do is carry anything across a sentence boundary, because no generated
 * audio does: each sentence is an independent generation from the same prompt,
 * so timbre holds while pitch, pace and energy reset at every full stop. That
 * residue is what "the voice still wanders on long text" now is.
 *
 * The fix is the standard one for chunked neural TTS: condition each sentence
 * on what was just spoken. The important word is *each* - an earlier attempt
 * did this at chunk boundaries, and a chunk is two or three sentences, so it
 * skipped most of the seams it was meant to close.
 *
 * ### Why the prompt never leaves
 *
 * That earlier attempt also replaced the voice prompt with generated audio and
 * never went back to it, so a long read drifted steadily away from the voice
 * that was chosen - a mechanism for producing "it isn't the selected voice",
 * which is a worse complaint than the one it set out to fix. Here the prompt is
 * always the head of the reference and only the tail is recent speech, so
 * identity is re-anchored on every single sentence and only the delivery is
 * inherited.
 */
class VoiceContinuity(
    private val voice: PocketTts.LoadedVoice,
    /** The rate the model generates at, which need not be the prompt's. */
    outputSampleRate: Int,
    tailSeconds: Float = TAIL_SECONDS,
    private val maxSeconds: Float = MAX_REFERENCE_SECONDS,
) {

    /**
     * False when the prompt and the model disagree about sample rate.
     *
     * The two halves of the reference are concatenated into one array under one
     * declared rate, so splicing 24 kHz speech onto a 16 kHz prompt would play
     * one of them at the wrong speed and condition the model on a chipmunk.
     * Resampling to fix that is a real piece of DSP and not worth it here, so
     * this simply stands down and the prompt is used alone - which is the
     * behaviour that was shipping anyway.
     */
    val usable: Boolean = voice.sampleRate == outputSampleRate

    private val tail = AudioTail(
        if (usable) (tailSeconds * outputSampleRate).toInt() else 0,
    )

    /** Whether anything has been spoken yet for the next sentence to follow. */
    val hasContext: Boolean get() = usable && !tail.isEmpty

    /** Keeps the most recent audio, called with everything the model produces. */
    fun record(samples: FloatArray) {
        tail.append(samples)
    }

    /**
     * The reference audio for the next sentence: the voice prompt, then the end
     * of what has just been said.
     *
     * Before anything has been spoken this is the prompt untouched, so the
     * first sentence of a read sounds exactly as it always did.
     */
    fun reference(): FloatArray {
        if (!hasContext) return voice.samples
        val recent = tail.snapshot()
        val room = (maxSeconds * voice.sampleRate).toInt() - recent.size
        if (room <= 0) return recent
        // The head of the prompt rather than its end, matching how an
        // over-long prompt is trimmed everywhere else in this package, and
        // keeping the recording's natural onset.
        val head = voice.samples.copyOf(room.coerceAtMost(voice.samples.size))
        return head + recent
    }

    fun reset() {
        tail.clear()
    }

    companion object {
        /**
         * How much of the previous sentence is inherited.
         *
         * Long enough to carry pace and pitch, short enough that the prompt
         * still dominates the reference and identity cannot drift.
         */
        const val TAIL_SECONDS = 2f

        /** The reference is capped where a prompt is: see `PocketTts`. */
        const val MAX_REFERENCE_SECONDS = 10f
    }
}
