package org.pockettts.android.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring buffer behind [VoiceContinuity]. */
class AudioTailTest {

    @Test
    fun `holds nothing until something is written`() {
        val tail = AudioTail(capacity = 4)
        assertTrue(tail.isEmpty)
        assertEquals(0, tail.snapshot().size)
    }

    @Test
    fun `keeps what fits, oldest first`() {
        val tail = AudioTail(capacity = 4)
        tail.append(floatArrayOf(1f, 2f))
        assertArrayEquals(floatArrayOf(1f, 2f), tail.snapshot(), 0f)
    }

    @Test
    fun `keeps the end and drops the beginning once full`() {
        val tail = AudioTail(capacity = 4)
        tail.append(floatArrayOf(1f, 2f, 3f))
        tail.append(floatArrayOf(4f, 5f))
        assertArrayEquals(floatArrayOf(2f, 3f, 4f, 5f), tail.snapshot(), 0f)
    }

    @Test
    fun `a single write longer than the buffer keeps only its end`() {
        // A synthesised sentence is routinely longer than the tail, and copying
        // the whole of it round the ring to overwrite itself is work for
        // nothing on every callback of every read.
        val tail = AudioTail(capacity = 3)
        tail.append(FloatArray(1000) { it.toFloat() })
        assertArrayEquals(floatArrayOf(997f, 998f, 999f), tail.snapshot(), 0f)
    }

    @Test
    fun `a zero capacity keeps nothing and does not fall over`() {
        // This is the shape a rate mismatch takes, and it is reached on real
        // audio rather than never.
        val tail = AudioTail(capacity = 0)
        tail.append(floatArrayOf(1f, 2f, 3f))
        assertTrue(tail.isEmpty)
        assertEquals(0, tail.snapshot().size)
    }

    @Test
    fun `clearing forgets everything`() {
        val tail = AudioTail(capacity = 4)
        tail.append(floatArrayOf(1f, 2f))
        tail.clear()
        assertTrue(tail.isEmpty)
        assertFalse(tail.snapshot().isNotEmpty())
    }
}
