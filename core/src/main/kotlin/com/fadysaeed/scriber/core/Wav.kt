package com.fadysaeed.scriber.core

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM WAV, the format Scriber keeps its own audio in. */
object Wav {
    const val HEADER_BYTES = 44

    fun header(sampleRate: Int, dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
        b.put("WAVE".toByteArray()); b.put("fmt ".toByteArray()); b.putInt(16)
        b.putShort(1); b.putShort(1); b.putInt(sampleRate); b.putInt(sampleRate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return b.array()
    }

    data class Info(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Long)

    /** Reads the header and leaves [input] at the first audio byte. Supports extra chunks before "data". */
    fun readHeader(input: InputStream): Info {
        val d = DataInputStream(input)
        val riff = ByteArray(12).also { d.readFully(it) }
        require(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") { "Not a WAV file" }
        var rate = 0; var channels = 0; var bits = 0
        while (true) {
            val id = ByteArray(4).also { d.readFully(it) }
            val size = Integer.reverseBytes(d.readInt()).toLong() and 0xFFFFFFFFL
            when (String(id)) {
                "fmt " -> {
                    val f = ByteArray(size.toInt()).also { d.readFully(it) }
                    val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                    require(bb.short.toInt() == 1) { "Only plain PCM WAV is supported" }
                    channels = bb.short.toInt(); rate = bb.int; bb.int; bb.short; bits = bb.short.toInt()
                }
                "data" -> return Info(rate, channels, bits, size)
                else -> {
                    var left = size + (size and 1)
                    while (left > 0) { val k = d.skip(left); if (k <= 0) { d.readByte(); left-- } else left -= k }
                }
            }
        }
    }

    fun shortsToFloats(bytes: ByteArray, count: Int = bytes.size): FloatArray {
        val n = count / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[2 * i].toInt() and 0xFF
            val hi = bytes[2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out
    }

    fun floatsToShorts(samples: FloatArray, from: Int = 0, to: Int = samples.size): ByteArray {
        val out = ByteArray((to - from) * 2)
        for (i in from until to) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out[2 * (i - from)] = (v and 0xFF).toByte()
            out[2 * (i - from) + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Reads mono PCM16 samples in blocks of up to [block] samples. */
    fun blocks(input: InputStream, block: Int = 16_000): Sequence<FloatArray> = sequence {
        val buf = ByteArray(block * 2)
        while (true) {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n >= 2) yield(shortsToFloats(buf, n - (n and 1)))
            if (n < buf.size) break
        }
    }

}
