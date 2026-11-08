package com.fadysaeed.scriber.work

import android.content.Context
import com.fadysaeed.scriber.core.Ids
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Segment
import com.fadysaeed.scriber.core.Token
import com.fadysaeed.scriber.core.TranscriptRun
import com.fadysaeed.scriber.core.Wav
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.engines.Audio
import com.fadysaeed.scriber.engines.Speech
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.vault.Vault
import com.k2fsa.sherpa.onnx.SpeechSegment
import kotlin.math.roundToLong

/**
 * Makes one sealed raw transcript of a session: the 16 kHz audio is read in pieces, Silero finds
 * each stretch of speech, and the chosen engine transcribes it. Nothing is corrected or edited;
 * the run records exactly what the engine produced, with the timing of every token.
 */
class Transcriber(private val context: Context) {
    private val vault = Vault.get(context)
    private val store = SessionStore.get(vault)
    private val models = ModelStore(context)
    private val catalog = ModelCatalog.bundled()

    fun run(sessionId: String, engine: String, progress: (Float) -> Unit): TranscriptRun {
        val session = store.session(sessionId) ?: error("Session not found")
        val audio = session.workAudioName ?: error("This session has no audio")
        check(session.hasAudio) { "The audio of this session was deleted" }
        val asr = catalog.byRole(PackRole.ASR).firstOrNull { it.engine == engine } ?: error("Unknown engine $engine")
        check(models.state(asr) is PackState.Verified) { "${asr.title} is not downloaded" }
        val vadPack = catalog.byRole(PackRole.VAD).first()
        check(models.state(vadPack) is PackState.Verified) { "Voice activity model is not downloaded" }

        val threads = Speech.threads()
        val vadSettings = mapOf("vad" to "silero", "vadThreshold" to "0.5", "vadMinSilence" to "0.4", "vadMaxSpeech" to "20")
        var run = TranscriptRun(
            id = Ids.new(), sessionId = sessionId, engine = engine, engineTitle = asr.title,
            modelSha256 = asr.files.first { it.path.endsWith(".onnx") }.sha256,
            settings = vadSettings + mapOf("threads" to "$threads", "decoding" to "greedy_search", "runtime" to "sherpa-onnx 2.0.1", "catalog" to "${catalog.version}"),
            audioSha256 = session.audioSha256.orEmpty(), startedAtMillis = System.currentTimeMillis(), status = RunStatus.RUNNING,
        )
        store.saveRun(run)

        val recognizer = Speech.recognizer(engine, models.dir(asr), threads)
        val vad = Speech.vad(models.file(vadPack, vadPack.files.first()))
        val segments = ArrayList<Segment>()
        try {
            fun take(seg: SpeechSegment) {
                val result = Speech.decode(recognizer, Audio(seg.samples, Speech.SAMPLE_RATE))
                val startMs = seg.start * 1000 / Speech.SAMPLE_RATE
                val endMs = startMs + seg.samples.size * 1000L / Speech.SAMPLE_RATE
                if (result.text.isBlank()) return
                val tokens = result.tokens.mapIndexed { i, t -> Token(t, startMs + ((result.timestamps.getOrNull(i) ?: 0f) * 1000).roundToLong()) }
                segments += Segment(startMs, endMs, result.text.trim(), tokens)
            }
            vault.read(audio).use { input ->
                val info = Wav.readHeader(input)
                check(info.sampleRate == Speech.SAMPLE_RATE && info.channels == 1) { "Working audio must be 16 kHz mono" }
                val total = (info.dataBytes / 2).coerceAtLeast(1)
                var done = 0L
                var pending = FloatArray(0)
                for (block in Wav.blocks(input, 16_000)) {
                    pending += block
                    var i = 0
                    while (i + 512 <= pending.size) {
                        vad.acceptWaveform(pending.copyOfRange(i, i + 512)); i += 512
                        while (!vad.empty()) { take(vad.front()); vad.pop() }
                    }
                    pending = pending.copyOfRange(i, pending.size)
                    done += block.size
                    progress(done.toFloat() / total)
                }
                if (pending.isNotEmpty()) vad.acceptWaveform(pending + FloatArray(512 - pending.size % 512))
                vad.flush()
                while (!vad.empty()) { take(vad.front()); vad.pop() }
            }
            run = run.copy(status = RunStatus.DONE, finishedAtMillis = System.currentTimeMillis(), segments = segments)
            store.saveRun(run)
            return store.runs(sessionId).first { it.id == run.id }
        } catch (t: Throwable) {
            store.saveRun(run.copy(status = RunStatus.FAILED, finishedAtMillis = System.currentTimeMillis(), segments = segments, problem = t.message ?: t.javaClass.simpleName))
            throw t
        } finally {
            vad.release()
            recognizer.release()
        }
    }
}
