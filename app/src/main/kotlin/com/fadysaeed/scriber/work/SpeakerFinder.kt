package com.fadysaeed.scriber.work

import android.content.Context
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.Pieces
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.SpeakerLayer
import com.fadysaeed.scriber.core.Speakers
import com.fadysaeed.scriber.core.VoicePiece
import com.fadysaeed.scriber.core.Turn
import com.fadysaeed.scriber.core.VoicePrints
import com.fadysaeed.scriber.core.Wav
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.engines.Speech
import com.fadysaeed.scriber.engines.VoicePrinter
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.vault.Vault

/**
 * Finds who spoke when, in two steps. The speaker model (pyannote) listens to the whole session
 * once; then every transcript line, cut only where that model heard a clear change, gets a voice
 * print, and the prints are grouped into voices. Both results are saved, so asking for another
 * number of voices only regroups them, in a fraction of a second.
 */
class SpeakerFinder(private val context: Context) {
    private val vault = Vault.get(context)
    private val store = SessionStore.get(vault)
    private val models = ModelStore(context)
    private val catalog = ModelCatalog.bundled()

    fun run(sessionId: String, count: Int, progress: (Float) -> Unit): SpeakerLayer {
        val saved = store.prints(sessionId)?.takeIf { it.modelTurns.isNotEmpty() }
        return fromPrints(sessionId, count, saved ?: makePrints(sessionId, count, progress))
    }

    /** Groups saved prints into [count] voices (0 lets Scriber decide) and saves the result. */
    fun fromPrints(sessionId: String, count: Int, prints: VoicePrints): SpeakerLayer {
        val session = store.session(sessionId) ?: error("Session not found")
        val started = System.currentTimeMillis()
        val turns = Speakers.smooth(Pieces.labelWithModel(prints.pieces, prints.modelTurns, count))
        val old = store.speakers(sessionId)
        val layer = SpeakerLayer(
            sessionId = sessionId, audioSha256 = session.audioSha256.orEmpty(), requested = count, turns = turns,
            // Names carry over when the same number of voices is found again; moves never do.
            names = old?.names?.takeIf { old.count == turns.map { it.speaker }.distinct().size }.orEmpty(),
            settings = mapOf("method" to "speaker model, then voice prints per line", "model" to prints.model, "pieces" to "${prints.pieces.size}",
                "printed" to "${prints.pieces.count { it.print != null }}", "groupMs" to "${System.currentTimeMillis() - started}",
                "runtime" to "sherpa-onnx 2.0.1"),
            createdAtMillis = System.currentTimeMillis(),
        )
        store.saveSpeakers(layer)
        return layer
    }

    /** The slow step, once per session: the speaker model over the whole audio, then a print per line. */
    private fun makePrints(sessionId: String, count: Int, progress: (Float) -> Unit): VoicePrints {
        val session = store.session(sessionId) ?: error("Session not found")
        check(session.hasAudio) { "The audio of this session was deleted" }
        val audio = session.workAudioName ?: error("This session has no audio")
        check(session.durationMs <= MAX_MS) { "Sessions longer than ${MAX_MS / 60_000} minutes are not split into speakers yet" }
        val run = store.runs(sessionId).filter { it.status == RunStatus.DONE }.let { done ->
            done.lastOrNull { it.engine == store.settings().baseEngine } ?: done.lastOrNull()
        }
        if (session.durationMs > ONE_PIECE_MS) {
            return inPieces(audio, run?.segments?.map { it.startMs..it.endMs }, count, session.durationMs, progress)
                .copy(sessionId = sessionId, runId = run?.id.orEmpty()).also { store.savePrints(it) }
        }
        val samples = vault.read(audio).use { input ->
            val info = Wav.readHeader(input)
            check(info.sampleRate == Speech.SAMPLE_RATE && info.channels == 1) { "Working audio must be 16 kHz mono" }
            val out = FloatArray((info.dataBytes / 2).toInt())
            var at = 0
            for (block in Wav.blocks(input, 16_000)) { block.copyInto(out, at, 0, minOf(block.size, out.size - at)); at += block.size; if (at >= out.size) break }
            if (at < out.size) out.copyOf(at) else out
        }
        val lines = run?.segments?.map { it.startMs..it.endMs } ?: speechStretches(samples)
        val prints = printsFor(context, samples, lines, count, session.durationMs, progress)
        return prints.copy(sessionId = sessionId, runId = run?.id.orEmpty()).also { store.savePrints(it) }
    }

    private fun speechStretches(samples: FloatArray): List<LongRange> = stretches(context, samples)

    /**
     * A long recording (a meeting) in pieces of about half an hour, cut between lines, so the
     * phone never holds more than one piece of audio at a time. Each piece gets the speaker model
     * and its prints as usual; the prints of all pieces are then grouped together, so the same
     * voice keeps the same name across the whole meeting. The speaker model's own labels are
     * kept apart per piece (piece 2's "speaker 0" is not piece 1's).
     */
    private fun inPieces(audio: String, lines: List<LongRange>?, count: Int, durationMs: Long, progress: (Float) -> Unit): VoicePrints {
        val gaps = lines?.zipWithNext()?.map { (a, b) -> (a.last + b.first) / 2 }.orEmpty()
        val bounds = arrayListOf(0L)
        while (durationMs - bounds.last() > PIECE_MS + PIECE_MS / 3) {
            val target = bounds.last() + PIECE_MS
            val cut = gaps.filter { it > bounds.last() + PIECE_MS / 2 && it < target + PIECE_MS / 2 }.minByOrNull { kotlin.math.abs(it - target) } ?: target
            bounds += cut
        }
        bounds += durationMs
        val pieces = ArrayList<VoicePiece>(); val turns = ArrayList<Turn>()
        var model = ""; var linesBefore = 0
        vault.read(audio).use { input ->
            val info = Wav.readHeader(input)
            check(info.sampleRate == Speech.SAMPLE_RATE && info.channels == 1) { "Working audio must be 16 kHz mono" }
            val blocks = Wav.blocks(input, 16_000).iterator()
            var carry = FloatArray(0); var carryAt = 0
            val parts = bounds.size - 1
            for (c in 0 until parts) {
                val from = bounds[c]; val to = bounds[c + 1]
                val buf = FloatArray(((to - from) * (Speech.SAMPLE_RATE / 1000)).toInt())
                var at = 0
                while (at < buf.size) {
                    if (carryAt >= carry.size) { if (!blocks.hasNext()) break; carry = blocks.next(); carryAt = 0 }
                    val k = minOf(carry.size - carryAt, buf.size - at)
                    carry.copyInto(buf, at, carryAt, carryAt + k); at += k; carryAt += k
                }
                val samples = if (at < buf.size) buf.copyOf(at) else buf
                val mine = lines?.filter { it.first >= from && it.first < to }?.map { (it.first - from)..(minOf(it.last, to) - from) } ?: stretches(context, samples)
                val p = printsFor(context, samples, mine, count, to - from) { f -> progress((c + f) / parts) }
                pieces += p.pieces.map { it.copy(startMs = it.startMs + from, endMs = it.endMs + from, segment = it.segment + linesBefore) }
                turns += p.modelTurns.map { Turn(it.startMs + from, it.endMs + from, it.speaker + c * 100) }
                model = p.model; linesBefore += mine.size
            }
        }
        return VoicePrints("", "", model, pieces, System.currentTimeMillis(), turns)
    }

    companion object {
        /** Meetings run up to two hours; a little room for the end of one. */
        const val MAX_MS = 125 * 60_000L
        /** Up to this long, the whole recording goes through the speaker model at once, as it always has. */
        const val ONE_PIECE_MS = 40 * 60_000L
        const val PIECE_MS = 30 * 60_000L

        /**
         * The speaker model over [samples], then a voice print for each line piece. Shared with the
         * Lab, so the Lab measures exactly what sessions get.
         */
        fun printsFor(context: Context, samples: FloatArray, lines: List<LongRange>, count: Int, durationMs: Long, progress: (Float) -> Unit): VoicePrints {
            val models = ModelStore(context)
            val catalog = ModelCatalog.bundled()
            val seg = catalog.byRole(PackRole.SEGMENTATION).first { models.state(it) is PackState.Verified }
            val emb = catalog.byRole(PackRole.SPEAKER_EMBEDDING).first { models.state(it) is PackState.Verified }
            val threads = SessionStore.get(Vault.get(context)).threadsFor("speakers")
            val d = Speech.diarizer(models.file(seg, seg.files.first()), models.file(emb, emb.files.first()), speakers = if (count > 0) count else -1, threads = threads)
            val model = try {
                // Not processWithCallback: its native side looks for an invoke(int, int, long) method
                // that Kotlin 2 lambdas do not have, and the app dies. Progress comes from the clock,
                // at the phone's measured speed (about five times faster than real time).
                val expectedMs = (durationMs / 5).coerceAtLeast(2_000)
                val started = System.currentTimeMillis()
                val ticker = Thread {
                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            progress(((System.currentTimeMillis() - started).toFloat() / expectedMs).coerceAtMost(0.9f) * 0.85f)
                            Thread.sleep(1000)
                        }
                    } catch (_: InterruptedException) {}
                }.apply { isDaemon = true; start() }
                val found = try { d.process(samples) } finally { ticker.interrupt() }
                found.map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }.sortedBy { it.startMs }
            } finally { d.release() }
            val cut = Pieces.cutAtModelChanges(lines, model)
            val printer = VoicePrinter.open(context, threads) ?: error("The voice print model is not downloaded")
            try {
                val pieces = cut.mapIndexed { i, (r, li) ->
                    progress(0.85f + 0.15f * i / cut.size.coerceAtLeast(1))
                    val w = Pieces.printWindow(r)
                    val p = w?.let { printer.print(samples, (it.first * 16).toInt(), ((it.last + 1) * 16).toInt()) }
                    VoicePiece(r.first, r.last + 1, li, false, p?.map { x -> Math.round(x * 10_000) / 10_000f })
                }
                return VoicePrints("", "", printer.modelId, pieces, System.currentTimeMillis(), model)
            } finally { printer.close() }
        }

        fun ready(context: Context): Boolean {
            val models = ModelStore(context)
            val catalog = ModelCatalog.bundled()
            return listOf(PackRole.SEGMENTATION, PackRole.SPEAKER_EMBEDDING).all { r -> catalog.byRole(r).any { models.state(it) is PackState.Verified } }
        }
    }
}

/** Stretches of speech from the voice activity model, the same way transcription splits lines. */
fun stretches(context: Context, samples: FloatArray): List<LongRange> {
    val models = ModelStore(context)
    val vadPack = ModelCatalog.bundled().byRole(PackRole.VAD).first { models.state(it) is PackState.Verified }
    val vad = Speech.vad(models.file(vadPack, vadPack.files.first()))
    val out = ArrayList<LongRange>()
    fun drain() { while (!vad.empty()) { val f = vad.front(); out += (f.start * 1000L / Speech.SAMPLE_RATE)..((f.start + f.samples.size) * 1000L / Speech.SAMPLE_RATE); vad.pop() } }
    try {
        var i = 0
        while (i + 512 <= samples.size) { vad.acceptWaveform(samples.copyOfRange(i, i + 512)); i += 512; drain() }
        vad.flush(); drain()
    } finally { vad.release() }
    return out
}
