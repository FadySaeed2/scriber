package com.fadysaeed.scriber

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fadysaeed.scriber.audio.Assembler
import com.fadysaeed.scriber.audio.Importer
import com.fadysaeed.scriber.core.Hashing
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Seal
import com.fadysaeed.scriber.core.Session
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.SessionSource
import com.fadysaeed.scriber.core.SessionStatus
import com.fadysaeed.scriber.core.TranscriptFormats
import com.fadysaeed.scriber.core.Wav
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.fetch.ModelDownloader
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.vault.Vault
import com.fadysaeed.scriber.work.Jobs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Recording rescue, 48 kHz handling, importing a real compressed file, and a full transcription. */
@RunWith(AndroidJUnit4::class)
class Phase1OnDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val vault = Vault.get(context)
    private val store = SessionStore.get(vault)

    private fun tone(rate: Int, seconds: Double) = FloatArray((rate * seconds).toInt()) { (0.3 * sin(2 * PI * 440 * it / rate)).toFloat() }

    private fun recordedSession(rate: Int, seconds: Int): String {
        val s = Session("t" + System.nanoTime(), "test", SessionMode.CONVERSATION, SessionSource.RECORDED, System.currentTimeMillis(), SessionStatus.RECORDING, sampleRate = rate)
        store.save(s)
        val pcm = Wav.floatsToShorts(tone(rate, seconds.toDouble()))
        val piece = rate * 2 * 5
        var i = 0; var n = 0
        while (i < pcm.size) { val end = minOf(pcm.size, i + piece); val b = pcm.copyOfRange(i, end); vault.write(store.chunkName(s.id, n++)) { it.write(b) }; i = end }
        return s.id
    }

    @Test fun rescuesAnInterruptedRecording() {
        val id = recordedSession(16_000, 12)
        assertTrue(Assembler(vault, store).finishRecording(id, recovered = true))
        val s = store.session(id)!!
        assertEquals(SessionStatus.READY, s.status)
        assertTrue(s.recovered)
        assertEquals(12_000L, s.durationMs)
        assertEquals(s.audioName, s.workAudioName)
        assertEquals(s.audioSha256, vault.read(s.audioName!!).use { Hashing.sha256(it) })
        assertTrue(store.chunks(id).isEmpty())
        val info = vault.read(s.audioName!!).use { Wav.readHeader(it) }
        assertEquals(Wav.Info(16_000, 1, 16, 12L * 16_000 * 2), info)
        store.deleteSession(id)
    }

    @Test fun studioQualityKeepsAHighRateFileAndAMatching16kCopy() {
        val id = recordedSession(48_000, 6)
        assertTrue(Assembler(vault, store).finishRecording(id, recovered = false))
        val s = store.session(id)!!
        assertEquals(6_000L, s.durationMs)
        val work = vault.read(s.workAudioName!!).use { Wav.readHeader(it) }
        assertEquals(16_000, work.sampleRate)
        assertTrue("16k bytes ${work.dataBytes}", abs(work.dataBytes - 6L * 16_000 * 2) <= 64)
        store.deleteSession(id)
    }

    @Test fun importsACompressedFileByteForByte() {
        val m4a = makeM4a(File(context.cacheDir, "tone.m4a"), seconds = 3)
        val id = Importer.copyIn(context, Uri.fromFile(m4a), SessionMode.DICTATION)
        Importer.decode(context, id) {}
        val s = store.session(id)!!
        assertEquals(SessionStatus.READY, s.status)
        assertEquals(Hashing.sha256(m4a), s.original!!.sha256)
        assertEquals(Hashing.sha256(m4a), vault.read(s.original!!.vaultName).use { Hashing.sha256(it) })
        assertTrue("duration ${s.durationMs}", abs(s.durationMs - 3000) < 200)
        assertEquals(16_000, vault.read(s.workAudioName!!).use { Wav.readHeader(it) }.sampleRate)
        store.deleteSession(id)
    }

    @Test fun transcribesAMedicalDictationAndSealsIt() = runBlocking {
        val catalog = ModelCatalog.bundled()
        val models = ModelStore(context)
        val downloader = ModelDownloader(models)
        val packs = listOf(catalog.byRole(PackRole.ASR).first { it.engine == "medasr" }, catalog.byRole(PackRole.VAD).single())
        for (p in packs) { for (f in p.files) downloader.fetch(p, f) { _, _ -> }; models.markVerified(p) }
        val bench = catalog.byRole(PackRole.BENCHMARK_AUDIO).single()
        val wav = bench.files.first { it.path == "1.wav" }
        downloader.fetch(bench, wav) { _, _ -> }

        // The whole path Fady uses: copy in, then the background queue decodes and transcribes.
        val id = Importer.copyIn(context, Uri.fromFile(models.file(bench, wav)), SessionMode.DICTATION)
        Jobs.import(context, id, "medasr")
        val deadline = System.currentTimeMillis() + 10 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val s = store.session(id)!!
            if (s.status == SessionStatus.FAILED) fail("Import failed: ${s.problem}")
            val r = store.runs(id).lastOrNull()
            if (r?.status == RunStatus.FAILED) fail("Transcription failed: ${r.problem}")
            if (r?.status == RunStatus.DONE && Jobs.pending(context).isEmpty()) break
            Thread.sleep(500)
        }
        assertEquals(SessionStatus.READY, store.session(id)!!.status)
        val run = store.runs(id).single()
        assertEquals(RunStatus.DONE, run.status)
        assertTrue(Jobs.progress.value[id] == null)
        assertTrue(Seal.intact(run))
        assertTrue(run.text, run.wordCount >= 20)
        assertTrue(run.segments.all { it.endMs > it.startMs })
        assertTrue(TranscriptFormats.srt(run, SessionMode.DICTATION, false).contains("-->"))
        try { store.saveRun(run.copy(segments = emptyList())); fail("A sealed run must not be overwritten") } catch (expected: IllegalStateException) {}
        store.deleteSession(id)
    }

    /** Encodes a 44.1 kHz stereo tone as AAC in an MP4 container, like a phone voice recorder would. */
    private fun makeM4a(out: File, seconds: Int): File {
        val rate = 44_100; val ch = 2
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val mono = tone(rate, seconds.toDouble())
        val pcm = ByteBuffer.allocate(mono.size * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in mono) { val sv = (v * 32767).toInt().toShort(); repeat(ch) { pcm.putShort(sv) } }
        pcm.flip()
        val info = MediaCodec.BufferInfo()
        var inputDone = false; var done = false; var ptsFrames = 0L
        while (!done) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = codec.getInputBuffer(i)!!
                    val n = minOf(buf.remaining(), pcm.remaining())
                    if (n <= 0) { codec.queueInputBuffer(i, 0, 0, ptsFrames * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else {
                        val slice = pcm.slice(); slice.limit(n); buf.put(slice); pcm.position(pcm.position() + n)
                        codec.queueInputBuffer(i, 0, n, ptsFrames * 1_000_000 / rate, 0)
                        ptsFrames += n / (2 * ch)
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                o >= 0 -> {
                    val buf = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) muxer.writeSampleData(track, buf, info)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        codec.stop(); codec.release(); muxer.stop(); muxer.release()
        return out
    }
}
