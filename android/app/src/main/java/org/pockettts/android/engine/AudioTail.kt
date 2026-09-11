package org.pockettts.android.engine

/**
 * The last few seconds of audio to pass through it, and nothing else.
 *
 * A ring buffer rather than a growing list because this sits in the audio
 * callback of a read that may run for twenty minutes: the whole point is that
 * memory stays flat no matter how long the passage is, and only the recent end
 * of it is ever wanted.
 */
class AudioTail(private val capacity: Int) {

    private val buffer = FloatArray(capacity.coerceAtLeast(0))
    private var filled = 0
    private var write = 0

    val isEmpty: Boolean get() = filled == 0

    /** Samples currently held; never more than the capacity. */
    val size: Int get() = filled

    fun append(samples: FloatArray) {
        if (capacity <= 0) return
        // Everything but the final `capacity` samples would be overwritten
        // before anybody could read it, so it is not copied in the first place.
        // A single synthesised sentence can be longer than the tail.
        val from = (samples.size - capacity).coerceAtLeast(0)
        for (index in from until samples.size) {
            buffer[write] = samples[index]
            write = (write + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** What is held, oldest first. */
    fun snapshot(): FloatArray {
        if (filled == 0) return FloatArray(0)
        val out = FloatArray(filled)
        val start = (write - filled + capacity) % capacity
        for (index in 0 until filled) out[index] = buffer[(start + index) % capacity]
        return out
    }

    fun clear() {
        filled = 0
        write = 0
    }
}
