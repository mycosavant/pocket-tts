package org.pockettts.android.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The few seconds of audio handed back as the prompt for the next sentence.
 *
 * Order matters here in a way that is easy to get wrong and impossible to see:
 * a ring buffer read from the wrong end gives the model a prompt that is the
 * right samples in the wrong sequence - still speech-shaped, still the right
 * length, and nothing but an ear could tell.
 */
class AudioTailTest {

    private fun ramp(from: Int, count: Int) = FloatArray(count) { (from + it).toFloat() }

    @Test
    fun `keeps the most recent samples, oldest first`() {
        val tail = AudioTail(4)
        tail.append(ramp(0, 3))
        tail.append(ramp(3, 3))

        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f), tail.snapshot(), 0f)
    }

    @Test
    fun `a write larger than the buffer keeps only its end`() {
        val tail = AudioTail(3)
        tail.append(ramp(0, 10))

        assertArrayEquals(floatArrayOf(7f, 8f, 9f), tail.snapshot(), 0f)
    }

    @Test
    fun `a partly filled buffer reports only what it has`() {
        val tail = AudioTail(8)
        tail.append(ramp(0, 3))

        assertArrayEquals(floatArrayOf(0f, 1f, 2f), tail.snapshot(), 0f)
    }

    @Test
    fun `wrapping many times does not lose the order`() {
        val tail = AudioTail(5)
        repeat(20) { tail.append(ramp(it * 3, 3)) }

        // 60 samples written, the last five of them retained.
        assertArrayEquals(floatArrayOf(55f, 56f, 57f, 58f, 59f), tail.snapshot(), 0f)
    }

    @Test
    fun `empty until something is written, and empty again after clearing`() {
        val tail = AudioTail(4)
        assertTrue(tail.isEmpty)
        assertEquals(0, tail.snapshot().size)

        tail.append(ramp(0, 2))
        assertFalse(tail.isEmpty)

        tail.clear()
        assertTrue(tail.isEmpty)
        assertEquals(0, tail.snapshot().size)
    }

    @Test
    fun `a zero-length buffer is harmless rather than a crash`() {
        // Reachable from a sample rate of zero, which is what a broken engine
        // reports; taking the process down over it would be the worse failure.
        val tail = AudioTail(0)
        tail.append(ramp(0, 4))

        assertTrue(tail.isEmpty)
        assertEquals(0, tail.snapshot().size)
    }
}
