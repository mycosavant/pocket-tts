package org.pockettts.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavReaderTest {

    private fun wav(
        sampleRate: Int = 24000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
        format: Int = 1,
        data: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(value: Int, bytes: Int) {
            val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes == 2) buffer.putShort(value.toShort()) else buffer.putInt(value)
            out.write(buffer.array())
        }
        out.write("RIFF".toByteArray())
        le(36 + data.size, 4)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le(16, 4)
        le(format, 2)
        le(channels, 2)
        le(sampleRate, 4)
        le(sampleRate * channels * bitsPerSample / 8, 4)
        le(channels * bitsPerSample / 8, 2)
        le(bitsPerSample, 2)
        out.write("data".toByteArray())
        le(data.size, 4)
        out.write(data)
        return out.toByteArray()
    }

    private fun pcm16(vararg samples: Short): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    @Test
    fun `reads 16 bit mono pcm`() {
        val audio = WavReader.read(wav(data = pcm16(0, 16384, -16384, 32767)))
        assertEquals(24000, audio.sampleRate)
        assertEquals(4, audio.samples.size)
        assertEquals(0f, audio.samples[0], 1e-6f)
        assertEquals(0.5f, audio.samples[1], 1e-4f)
        assertEquals(-0.5f, audio.samples[2], 1e-4f)
        assertEquals(1f, audio.samples[3], 1e-4f)
    }

    @Test
    fun `downmixes stereo to mono`() {
        val data = pcm16(16384, -16384, 32767, 32767)
        val audio = WavReader.read(wav(channels = 2, data = data))
        assertEquals(2, audio.samples.size)
        assertEquals(0f, audio.samples[0], 1e-4f)
        assertEquals(1f, audio.samples[1], 1e-4f)
    }

    @Test
    fun `reads 32 bit float pcm`() {
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(0.25f)
        buffer.putFloat(-0.75f)
        val audio = WavReader.read(
            wav(bitsPerSample = 32, format = 3, data = buffer.array()),
        )
        assertEquals(0.25f, audio.samples[0], 1e-6f)
        assertEquals(-0.75f, audio.samples[1], 1e-6f)
    }

    @Test
    fun `reads 8 bit unsigned pcm`() {
        val audio = WavReader.read(
            wav(bitsPerSample = 8, data = byteArrayOf(128.toByte(), 255.toByte(), 0)),
        )
        assertEquals(0f, audio.samples[0], 1e-4f)
        assertTrue(audio.samples[1] > 0.9f)
        assertEquals(-1f, audio.samples[2], 1e-4f)
    }

    @Test
    fun `skips unknown chunks such as LIST`() {
        val base = wav(data = pcm16(16384))
        // Splice a LIST chunk in between "WAVE" and "fmt ".
        val out = ByteArrayOutputStream()
        out.write(base, 0, 12)
        out.write("LIST".toByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4).array())
        out.write("INFO".toByteArray())
        out.write(base, 12, base.size - 12)

        val audio = WavReader.read(out.toByteArray())
        assertEquals(1, audio.samples.size)
        assertEquals(0.5f, audio.samples[0], 1e-4f)
    }

    @Test
    fun `duration is derived from the sample rate`() {
        // 250 silent 16-bit frames at 100 Hz is two and a half seconds.
        val audio = WavReader.read(wav(sampleRate = 100, data = ByteArray(500)))
        assertEquals(2.5f, audio.durationSeconds, 1e-4f)
    }

    @Test
    fun `rejects a file that is not a wav`() {
        assertThrows(IOException::class.java) {
            WavReader.read("this is not a wav file at all".toByteArray())
        }
    }

    @Test
    fun `rejects a compressed wav rather than misreading it`() {
        assertThrows(IOException::class.java) {
            WavReader.read(wav(format = 0x11, data = pcm16(1, 2, 3)))
        }
    }
}
