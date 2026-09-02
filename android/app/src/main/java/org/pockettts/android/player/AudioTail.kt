package org.pockettts.android.player

/**
 * The last few seconds of audio produced, kept so the next sentence can be
 * conditioned on how the last one actually sounded.
 *
 * A ring rather than a growing list because it is written from the synthesis
 * callback, several times a second for the whole length of a read; appending
 * and trimming a list there would copy the whole buffer every time.
 */
class AudioTail(private val capacity: Int) {

    private val buffer = FloatArray(capacity)
    private var cursor = 0
    private var filled = 0

    val isEmpty: Boolean get() = filled == 0

    fun append(samples: FloatArray) {
        if (capacity == 0) return
        // Anything older than the last [capacity] samples is about to be
        // overwritten anyway, so only the tail of an oversized write is copied.
        val from = maxOf(0, samples.size - capacity)
        for (i in from until samples.size) {
            buffer[cursor] = samples[i]
            cursor = (cursor + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** The retained samples, oldest first. */
    fun snapshot(): FloatArray {
        if (filled < capacity) return buffer.copyOf(filled)
        val out = FloatArray(capacity)
        val head = capacity - cursor
        System.arraycopy(buffer, cursor, out, 0, head)
        System.arraycopy(buffer, 0, out, head, cursor)
        return out
    }

    fun clear() {
        cursor = 0
        filled = 0
    }
}
