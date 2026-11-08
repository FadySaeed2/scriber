package com.fadysaeed.scriber.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.fadysaeed.scriber.core.Ids
import com.fadysaeed.scriber.core.OriginalFile
import com.fadysaeed.scriber.core.Session
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.SessionSource
import com.fadysaeed.scriber.core.SessionStatus
import com.fadysaeed.scriber.core.StreamResampler
import com.fadysaeed.scriber.core.Wav
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.vault.Vault
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest

/**
 * Brings outside audio in. Step one copies the file into the vault byte for byte, with its
 * SHA-256, while Android still lets Scriber read it. Step two (a background job) decodes that
 * copy to 16 kHz mono for the speech models. The original is never changed.
 * Works for any audio Android can decode (m4a, mp3, wav, ogg, opus, flac, amr) and the sound
 * track of video files.
 */
object Importer {

    /** Copies [uri] into a new session and returns its id. Fast; decoding happens later in [decode]. */
    fun copyIn(context: Context, uri: Uri, mode: SessionMode): String {
        val vault = Vault.get(context)
        val store = SessionStore.get(vault)
        val cr = context.contentResolver
        var name = "audio"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val ext = name.substringAfterLast('.', "").lowercase().ifBlank { MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty() }
            .filter { it.isLetterOrDigit() }.take(8)
        val now = System.currentTimeMillis()
        val id = Ids.new()
        val vaultName = store.originalName(id, ext)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        vault.write(vaultName) { out ->
            (cr.openInputStream(uri) ?: error("Cannot open the file")).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); digest.update(buf, 0, n); copied += n }
            }
        }
        check(size < 0 || size == copied) { "Copied $copied bytes but the file has $size" }
        val original = OriginalFile(name, mime, copied, digest.digest().joinToString("") { "%02x".format(it) }, vaultName)
        val title = name.substringBeforeLast('.').ifBlank { Ids.defaultTitle(mode, now) }
        store.save(Session(id, title, mode, SessionSource.IMPORTED, now, SessionStatus.FINALIZING, original = original))
        return id
    }

    /** Decodes the vault copy to 16 kHz mono WAV. Called from the background job. */
    fun decode(context: Context, id: String, progress: (Float) -> Unit) {
        val vault = Vault.get(context)
        val store = SessionStore.get(vault)
        val s = store.session(id) ?: error("Session not found")
        val original = s.original ?: error("No original file")
        val pieceDir = "sessions/$id/decoded"
        vault.deleteTree(pieceDir)
        val extractor = MediaExtractor()
        vault.openSeekable(original.vaultName).use { channel ->
            extractor.setDataSource(ChannelSource(channel))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("This file has no sound track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: StreamResampler? = null
            val piece = ByteArrayOutputStream(16_000 * 2 * 5)
            var index = 0
            fun flush() { if (piece.size() > 0) { val b = piece.toByteArray(); vault.write("$pieceDir/${(index++).toString().padStart(6, '0')}.pcm") { it.write(b) }; piece.reset() } }
            fun emit(mono: FloatArray) {
                val r = resampler ?: StreamResampler(rate, 16_000).also { resampler = it }
                val out = r.feed(mono)
                piece.write(Wav.floatsToShorts(out))
                if (piece.size() >= 16_000 * 2 * 5) flush()
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) { codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        outIndex >= 0 -> {
                            val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            if (info.size > 0) emit(toMono(buf, channels, encoding))
                            codec.releaseOutputBuffer(outIndex, false)
                            if (durationUs > 0) progress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                resampler?.finish()?.let { piece.write(Wav.floatsToShorts(it)) }
                flush()
            } finally {
                runCatching { codec.stop() }
                codec.release()
                extractor.release()
            }
        }
        val pieces = vault.list(pieceDir)
        check(pieces.isNotEmpty()) { "No sound could be decoded from this file" }
        val name = store.work16kName(id)
        val r = Assembler(vault, store).assemble(pieces, 16_000, name)
        vault.deleteTree(pieceDir)
        store.update(id) {
            it.copy(status = SessionStatus.READY, sampleRate = 16_000, audioName = name, workAudioName = name,
                audioSha256 = r.sha256, audioBytes = r.bytes, durationMs = r.samples * 1000 / 16_000)
        }
    }

    private fun toMono(buf: ByteBuffer, channels: Int, encoding: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.asFloatBuffer()
            val frames = fb.remaining() / ch
            FloatArray(frames) { f -> var a = 0f; for (c in 0 until ch) a += fb.get(f * ch + c); a / ch }
        } else {
            val sb = buf.asShortBuffer()
            val frames = sb.remaining() / ch
            FloatArray(frames) { f -> var a = 0f; for (c in 0 until ch) a += sb.get(f * ch + c) / 32768f; a / ch }
        }
    }

    /** Lets Android's media decoder read straight from the encrypted vault copy. */
    private class ChannelSource(private val channel: SeekableByteChannel) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= channel.size()) return -1
            channel.position(position)
            val bb = ByteBuffer.wrap(buffer, offset, size)
            var total = 0
            while (bb.hasRemaining()) { val n = channel.read(bb); if (n <= 0) break; total += n }
            return if (total == 0) -1 else total
        }
        override fun getSize(): Long = channel.size()
        override fun close() {}
    }
}
