package com.fadysaeed.scriber.audio

import com.fadysaeed.scriber.core.Hashing
import com.fadysaeed.scriber.core.StreamResampler
import com.fadysaeed.scriber.core.Wav
import com.fadysaeed.scriber.vault.Vault
import java.io.OutputStream
import java.security.MessageDigest

/** Writes 16-bit mono WAV into the vault while hashing the plain bytes. */
class WavWriter(private val sampleRate: Int) {
    class Result(val sha256: String, val bytes: Long, val samples: Long)

    /** [fill] receives a function that appends PCM16 bytes. The header is written with the final size. */
    fun write(vault: Vault, name: String, totalDataBytes: Long, fill: ((ByteArray, Int) -> Unit) -> Unit): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        var data = 0L
        vault.write(name) { out: OutputStream ->
            val header = Wav.header(sampleRate, totalDataBytes)
            out.write(header); digest.update(header)
            fill { bytes, len -> out.write(bytes, 0, len); digest.update(bytes, 0, len); data += len }
        }
        check(data == totalDataBytes) { "Wrote $data audio bytes, expected $totalDataBytes" }
        return Result(digest.digest().joinToString("") { "%02x".format(it) }, Wav.HEADER_BYTES + data, data / 2)
    }
}

object AudioFiles {
    /** Makes a 16 kHz copy of a WAV in the vault, streaming, for the speech models. */
    fun make16k(vault: Vault, from: String, to: String): WavWriter.Result {
        val (info, total) = vault.read(from).use { val i = Wav.readHeader(it); i to i.dataBytes }
        val outSamples = maxOf(1L, total / 2 * 16_000L / info.sampleRate)
        val resampler = StreamResampler(info.sampleRate, 16_000)
        return WavWriter(16_000).write(vault, to, outSamples * 2) { put ->
            var written = 0L
            fun emit(f: FloatArray) {
                val take = minOf(f.size.toLong(), outSamples - written).toInt()
                if (take > 0) { val b = Wav.floatsToShorts(f, 0, take); put(b, b.size); written += take }
            }
            vault.read(from).use { input ->
                Wav.readHeader(input)
                for (block in Wav.blocks(input)) emit(resampler.feed(block))
            }
            emit(resampler.finish())
            while (written < outSamples) { val pad = ByteArray(minOf(32_000L, (outSamples - written) * 2).toInt()); put(pad, pad.size); written += pad.size / 2 }
        }
    }

    fun sha256(vault: Vault, name: String): String = vault.read(name).use { Hashing.sha256(it) }
}
