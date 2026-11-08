package com.fadysaeed.scriber.work

import android.content.Context
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.SpeakerLayer
import com.fadysaeed.scriber.core.Speakers
import com.fadysaeed.scriber.core.Turn
import com.fadysaeed.scriber.core.Voices
import com.fadysaeed.scriber.core.Wav
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.engines.Speech
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.vault.Vault

/**
 * Finds who spoke when in a session's audio with the speaker models (pyannote segmentation and a
 * voice-print model), cleans the result, and saves it beside the transcripts.
 */
class SpeakerFinder(private val context: Context) {
    private val vault = Vault.get(context)
    private val store = SessionStore.get(vault)
    private val models = ModelStore(context)
    private val catalog = ModelCatalog.bundled()

    fun run(sessionId: String, count: Int, progress: (Float) -> Unit): SpeakerLayer {
        val session = store.session(sessionId) ?: error("Session not found")
        check(session.hasAudio) { "The audio of this session was deleted" }
        val audio = session.workAudioName ?: error("This session has no audio")
        check(session.durationMs <= MAX_MS) { "Sessions longer than ${MAX_MS / 60_000} minutes are not split into speakers yet" }
        val seg = catalog.byRole(PackRole.SEGMENTATION).first { models.state(it) is PackState.Verified }
        val emb = catalog.byRole(PackRole.SPEAKER_EMBEDDING).first { models.state(it) is PackState.Verified }

        val samples = vault.read(audio).use { input ->
            val info = Wav.readHeader(input)
            check(info.sampleRate == Speech.SAMPLE_RATE && info.channels == 1) { "Working audio must be 16 kHz mono" }
            val out = FloatArray((info.dataBytes / 2).toInt())
            var at = 0
            for (block in Wav.blocks(input, 16_000)) { block.copyInto(out, at, 0, minOf(block.size, out.size - at)); at += block.size; if (at >= out.size) break }
            if (at < out.size) out.copyOf(at) else out
        }
        val threads = Speech.threads()
        val d = Speech.diarizer(models.file(seg, seg.files.first()), models.file(emb, emb.files.first()), speakers = if (count > 0) count else -1, threads = threads)
        try {
            // Not processWithCallback: its native side looks for an invoke(int, int, long) method that
            // Kotlin 2 lambdas do not have, and the app dies. Progress is estimated from the clock
            // instead, using the phone's measured speed (about five times faster than real time).
            val expectedMs = (session.durationMs / 5).coerceAtLeast(2_000)
            val started = System.currentTimeMillis()
            val ticker = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        progress(((System.currentTimeMillis() - started).toFloat() / expectedMs).coerceAtMost(0.95f))
                        Thread.sleep(1000)
                    }
                } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            val found = try { d.process(samples) } finally { ticker.interrupt() }
            val raw = found.map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }.sortedBy { it.startMs }
            // Second look, line by line: a voice print for every stretch of speech the transcript
            // found, grouped into voices. People rarely swap mid-phrase, so this is steadier than
            // the model's own turns, which can flip back and forth within one speaker.
            val byLines = runCatching { byVoicePrints(sessionId, samples, raw, count, models.file(emb, emb.files.first()), threads) }.getOrNull()
            val clean = Speakers.smooth(byLines ?: raw)
            val old = store.speakers(sessionId)
            val layer = SpeakerLayer(
                sessionId = sessionId, audioSha256 = session.audioSha256.orEmpty(), requested = count, turns = clean,
                // Names carry over when the same number of voices is found again; moves never do.
                names = old?.names?.takeIf { old.count == clean.map { it.speaker }.distinct().size }.orEmpty(),
                settings = mapOf("segmentation" to seg.id, "embedding" to emb.id, "clusters" to "$count", "threshold" to "0.5",
                    "rawChanges" to "${Speakers.changes(raw.sortedBy { it.startMs })}", "rawVoices" to "${raw.map { it.speaker }.distinct().size}", "method" to if (byLines != null) "voice prints per line" else "model turns",
                    "rawTurns" to raw.sortedBy { it.startMs }.joinToString(" ") { "${it.startMs / 100 / 10.0}-${it.endMs / 100 / 10.0}:${it.speaker}" }.take(2000),
                    "seconds" to "${samples.size / Speech.SAMPLE_RATE}", "runtime" to "sherpa-onnx 2.0.1"),
                createdAtMillis = System.currentTimeMillis(),
            )
            store.saveSpeakers(layer)
            return layer
        } finally {
            d.release()
        }
    }

    /**
     * Splits the audio at the transcript's lines (and inside a long line where the model heard a
     * clear change), makes a voice print for each piece long enough to carry one, groups the prints
     * into voices, and gives the short pieces the voice the model heard there.
     */
    private fun byVoicePrints(sessionId: String, samples: FloatArray, raw: List<Turn>, count: Int, embedding: java.io.File, threads: Int): List<Turn>? {
        val run = store.runs(sessionId).filter { it.status == com.fadysaeed.scriber.core.RunStatus.DONE }.let { done ->
            done.lastOrNull { it.engine == store.settings().baseEngine } ?: done.lastOrNull()
        }
        val lines: List<LongRange> = run?.segments?.map { it.startMs..it.endMs } ?: speechStretches(samples) ?: return null
        val pieces = ArrayList<LongRange>()
        for (line in lines) {
            val seg = object { val startMs = line.first; val endMs = line.last }
            var start = seg.startMs
            // A clear change inside a long line: both sides at least two seconds.
            for (t in raw) {
                if (t.startMs > start + 2000 && t.startMs < seg.endMs - 2000 && raw.any { o -> o.speaker != t.speaker && o.endMs in (t.startMs - 600)..(t.startMs + 600) }) {
                    pieces += start until t.startMs; start = t.startMs
                }
            }
            pieces += start until seg.endMs
        }
        if (pieces.size < 2) return null
        val extractor = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor(config = com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig(model = embedding.path, numThreads = threads))
        val prints = arrayOfNulls<FloatArray>(pieces.size)
        try {
            for ((i, p) in pieces.withIndex()) {
                if (p.last - p.first < 800) continue
                // At most ten seconds from the middle of the piece.
                val mid = (p.first + p.last) / 2
                val from = maxOf(p.first, mid - 5000); val to = minOf(p.last, mid + 5000)
                val a = (from * 16).toInt().coerceIn(0, samples.size); val b = (to * 16).toInt().coerceIn(a, samples.size)
                if (b - a < 12_000) continue
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(samples.copyOfRange(a, b), Speech.SAMPLE_RATE)
                    stream.inputFinished()
                    if (extractor.isReady(stream)) prints[i] = extractor.compute(stream)
                } finally { stream.release() }
            }
        } finally { extractor.release() }
        val withPrint = prints.indices.filter { prints[it] != null }
        if (withPrint.size < 2) return null
        val labels = Voices.cluster(withPrint.map { prints[it]!! }, count)
        val label = IntArray(pieces.size) { -1 }
        withPrint.forEachIndexed { j, i -> label[i] = labels[j] }
        // Short pieces: the model's speaker there, translated to the grouped voices by majority.
        fun modelSpeaker(p: LongRange) = raw.maxByOrNull { t -> minOf(t.endMs, p.last) - maxOf(t.startMs, p.first) }?.speaker ?: -1
        val vote = HashMap<Int, HashMap<Int, Int>>()
        for (i in withPrint) vote.getOrPut(modelSpeaker(pieces[i])) { HashMap() }.merge(label[i], 1, Int::plus)
        for (i in pieces.indices) if (label[i] < 0) {
            label[i] = vote[modelSpeaker(pieces[i])]?.maxByOrNull { it.value }?.key ?: label.getOrNull(i - 1)?.takeIf { it >= 0 } ?: 0
        }
        return pieces.mapIndexed { i, p -> Turn(p.first, p.last + 1, label[i]) }
    }

    /** Stretches of speech from the voice activity model, the same way transcription splits lines. */
    private fun speechStretches(samples: FloatArray): List<LongRange>? {
        val vadPack = catalog.byRole(PackRole.VAD).firstOrNull { models.state(it) is PackState.Verified } ?: return null
        val vad = Speech.vad(models.file(vadPack, vadPack.files.first()))
        val out = ArrayList<LongRange>()
        try {
            var i = 0
            while (i + 512 <= samples.size) {
                vad.acceptWaveform(samples.copyOfRange(i, i + 512)); i += 512
                while (!vad.empty()) { val f = vad.front(); out += (f.start * 1000L / Speech.SAMPLE_RATE)..((f.start + f.samples.size) * 1000L / Speech.SAMPLE_RATE); vad.pop() }
            }
            vad.flush()
            while (!vad.empty()) { val f = vad.front(); out += (f.start * 1000L / Speech.SAMPLE_RATE)..((f.start + f.samples.size) * 1000L / Speech.SAMPLE_RATE); vad.pop() }
        } finally { vad.release() }
        return out.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val MAX_MS = 80 * 60_000L

        fun ready(context: Context): Boolean {
            val models = ModelStore(context)
            val catalog = ModelCatalog.bundled()
            return listOf(PackRole.SEGMENTATION, PackRole.SPEAKER_EMBEDDING).all { r -> catalog.byRole(r).any { models.state(it) is PackState.Verified } }
        }
    }
}
