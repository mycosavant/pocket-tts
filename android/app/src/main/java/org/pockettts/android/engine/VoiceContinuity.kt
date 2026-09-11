package org.pockettts.android.engine

import androidx.annotation.VisibleForTesting

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
 * The fix is the standard one for chunked neural TTS: condition what is about
 * to be generated on what was just spoken. This carries the voice across chunk
 * boundaries, which closes the seams between chunks and not the ones between
 * sentences inside a chunk.
 *
 * That is a deliberate retreat, and the device decided it. Doing it per
 * sentence means splitting before sherpa-onnx sees the text, and the reference
 * is re-encoded once per generation call - so a chunk of two or three sentences
 * paid that cost two or three times, and generation fell from 1.12x real time
 * to 0.73x, which is below playback. It did not even buy what it cost:
 * sherpa-onnx re-splits whatever it is handed, so splitting first never
 * decided what a sentence was, only how often the reference was encoded.
 * Conditioning per chunk costs nothing at all by comparison - the reference is
 * the same ten seconds either way.
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

    private val outputRate = outputSampleRate

    /**
     * False only when a rate is missing outright.
     *
     * This used to be `voice.sampleRate == outputSampleRate`, on the reasoning
     * that the two halves of the reference travel as one array under one
     * declared rate, so splicing speech at one rate onto a prompt at another
     * would play one of them at the wrong speed. The reasoning is right and the
     * guard was wrong: the stock prompts are not recorded at the rate the model
     * generates at, so on the device this was written for it disabled the whole
     * feature - silently, on the default voice, while the per-sentence
     * splitting it pays for went on happening. All of the cost and none of the
     * effect, and no way to tell from the outside.
     *
     * So the tail is resampled to the prompt's rate instead of the feature
     * standing down. Linear interpolation, which would be too crude for
     * playback and is not being played: this is a conditioning reference, and
     * what the model reads out of it is timbre and cadence rather than the
     * top octave.
     */
    val usable: Boolean = voice.sampleRate > 0 && outputSampleRate > 0

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
        val recent = resample(tail.snapshot(), from = outputRate, to = voice.sampleRate)
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

    /**
     * [samples] at a different rate, by linear interpolation.
     *
     * Deliberately the simplest thing that is correct about duration: the
     * output is conditioning, never played, and a resampler with a proper
     * anti-aliasing filter would be a large amount of code defending against
     * artefacts nobody hears in a reference clip.
     */
    @VisibleForTesting
    internal fun resample(samples: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || from <= 0 || to <= 0 || samples.isEmpty()) return samples
        val length = (samples.size.toLong() * to / from).toInt()
        if (length <= 0) return FloatArray(0)
        val out = FloatArray(length)
        for (index in out.indices) {
            val position = index.toDouble() * from / to
            val left = position.toInt().coerceAtMost(samples.size - 1)
            val right = (left + 1).coerceAtMost(samples.size - 1)
            val fraction = (position - left).toFloat()
            out[index] = samples[left] * (1f - fraction) + samples[right] * fraction
        }
        return out
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
