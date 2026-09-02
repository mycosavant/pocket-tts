package org.pockettts.android.engine

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reader.
 *
 * Only exists because Pocket TTS voices are wav files and sherpa-onnx wants
 * them as a float array. Android's own decoders all want to hand back encoded
 * frames through a codec, which is a lot of ceremony for "read this PCM".
 *
 * Handles 8/16/24/32-bit integer PCM and 32-bit float, mono or multi-channel
 * (downmixed by averaging). Anything else - compressed wavs, mostly - is
 * rejected rather than silently misread.
 */
object WavReader {

    data class Audio(val samples: FloatArray, val sampleRate: Int) {
        val durationSeconds: Float
            get() = if (sampleRate > 0) samples.size.toFloat() / sampleRate else 0f

        // Data class equals/hashCode on a FloatArray compares identity, which is
        // never what a caller means; compare contents instead.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Audio && sampleRate == other.sampleRate && samples.contentEquals(other.samples))

        override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRate
    }

    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE

    fun read(file: File): Audio = read(file.readBytes())

    fun read(bytes: ByteArray): Audio {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12) throw IOException("Not a wav file: only ${bytes.size} bytes")

        require4CC(buffer, "RIFF")
        buffer.int // total size, unreliable in streamed files
        require4CC(buffer, "WAVE")

        var format = -1
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var data: ByteArray? = null

        while (buffer.remaining() >= 8) {
            val id = read4CC(buffer)
            val size = buffer.int
            if (size < 0 || size > buffer.remaining()) {
                // Truncated or lying chunk header: take whatever is left of the
                // data chunk rather than failing outright, since a clipped tail
                // on a voice prompt is harmless.
                if (id == "data") {
                    data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
                break
            }
            when (id) {
                "fmt " -> {
                    val start = buffer.position()
                    format = buffer.short.toInt() and 0xFFFF
                    channels = buffer.short.toInt() and 0xFFFF
                    sampleRate = buffer.int
                    buffer.int // byte rate
                    buffer.short // block align
                    bitsPerSample = buffer.short.toInt() and 0xFFFF
                    if (format == FORMAT_EXTENSIBLE && size >= 40) {
                        buffer.position(start + 24)
                        format = buffer.short.toInt() and 0xFFFF
                    }
                    buffer.position(start + size)
                }

                "data" -> {
                    data = ByteArray(size).also { buffer.get(it) }
                }

                else -> buffer.position(buffer.position() + size)
            }
            if (size % 2 == 1 && buffer.remaining() > 0) buffer.position(buffer.position() + 1)
        }

        val payload = data ?: throw IOException("wav file has no data chunk")
        if (channels <= 0 || sampleRate <= 0) throw IOException("wav file has no fmt chunk")
        if (format != FORMAT_PCM && format != FORMAT_FLOAT) {
            throw IOException("Unsupported wav encoding $format; only uncompressed PCM is supported")
        }

        val samples = decode(payload, format, bitsPerSample, channels)
        return Audio(samples, sampleRate)
    }

    private fun decode(
        payload: ByteArray,
        format: Int,
        bitsPerSample: Int,
        channels: Int,
    ): FloatArray {
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample <= 0) throw IOException("wav file declares $bitsPerSample bits per sample")
        val frameSize = bytesPerSample * channels
        val frames = payload.size / frameSize
        val out = FloatArray(frames)
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                val base = frame * frameSize + channel * bytesPerSample
                sum += when {
                    format == FORMAT_FLOAT && bytesPerSample == 4 -> buffer.getFloat(base)
                    bytesPerSample == 1 -> ((payload[base].toInt() and 0xFF) - 128) / 128f
                    bytesPerSample == 2 -> buffer.getShort(base) / 32768f
                    bytesPerSample == 3 -> {
                        val value = (payload[base].toInt() and 0xFF) or
                            ((payload[base + 1].toInt() and 0xFF) shl 8) or
                            (payload[base + 2].toInt() shl 16) // sign-extends
                        value / 8388608f
                    }

                    bytesPerSample == 4 -> buffer.getInt(base) / 2147483648f
                    else -> throw IOException("Unsupported wav sample width $bitsPerSample")
                }
            }
            out[frame] = sum / channels
        }
        return out
    }

    private fun read4CC(buffer: ByteBuffer): String {
        val chars = ByteArray(4)
        buffer.get(chars)
        return String(chars, Charsets.US_ASCII)
    }

    private fun require4CC(buffer: ByteBuffer, expected: String) {
        val actual = read4CC(buffer)
        if (actual != expected) throw IOException("Not a wav file: expected $expected, got $actual")
    }
}
