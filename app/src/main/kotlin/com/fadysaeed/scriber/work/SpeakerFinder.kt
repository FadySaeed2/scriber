package com.fadysaeed.scriber.work

import android.content.Context
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.Pieces
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Segment
import com.fadysaeed.scriber.core.SpeakerLayer
import com.fadysaeed.scriber.core.Speakers
import com.fadysaeed.scriber.core.VoicePiece
import com.fadysaeed.scriber.core.VoicePrints
import com.fadysaeed.scriber.core.Wav
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.engines.Speech
import com.fadysaeed.scriber.engines.VoicePrinter
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.vault.Vault

/**
 * Finds who spoke when. Every transcript line (cut where the words pause long enough for the
 * other person to start) gets a voice print; the prints are grouped into voices. Prints are made
 * while the main transcript is written, so this usually takes a fraction of a second; asking for
 * another number of voices only regroups them. Sessions without prints get them in one quick pass.
 */
class SpeakerFinder(private val context: Context) {
    private val vault = Vault.get(context)
    private val store = SessionStore.get(vault)
    private val models = ModelStore(context)
    private val catalog = ModelCatalog.bundled()

    fun run(sessionId: String, count: Int, progress: (Float) -> Unit): SpeakerLayer {
        val prints = store.prints(sessionId) ?: makePrints(sessionId, progress)
        return fromPrints(sessionId, count, prints)
    }

    /** Groups saved prints into [count] voices (0 lets Scriber decide) and saves the result. */
    fun fromPrints(sessionId: String, count: Int, prints: VoicePrints): SpeakerLayer {
        val session = store.session(sessionId) ?: error("Session not found")
        val started = System.currentTimeMillis()
        val turns = Speakers.smooth(Pieces.label(prints.pieces, count))
        val old = store.speakers(sessionId)
        val layer = SpeakerLayer(
            sessionId = sessionId, audioSha256 = session.audioSha256.orEmpty(), requested = count, turns = turns,
            // Names carry over when the same number of voices is found again; moves never do.
            names = old?.names?.takeIf { old.count == turns.map { it.speaker }.distinct().size }.orEmpty(),
            settings = mapOf("method" to "voice prints per line", "model" to prints.model, "pieces" to "${prints.pieces.size}",
                "printed" to "${prints.pieces.count { it.print != null }}", "groupMs" to "${System.currentTimeMillis() - started}",
                "runtime" to "sherpa-onnx 2.0.1"),
            createdAtMillis = System.currentTimeMillis(),
        )
        store.saveSpeakers(layer)
        return layer
    }

    /** One pass over the audio for a session transcribed before prints were kept. */
    private fun makePrints(sessionId: String, progress: (Float) -> Unit): VoicePrints {
        val session = store.session(sessionId) ?: error("Session not found")
        check(session.hasAudio) { "The audio of this session was deleted" }
        val audio = session.workAudioName ?: error("This session has no audio")
        check(session.durationMs <= MAX_MS) { "Sessions longer than ${MAX_MS / 60_000} minutes are not split into speakers yet" }
        val samples = vault.read(audio).use { input ->
            val info = Wav.readHeader(input)
            check(info.sampleRate == Speech.SAMPLE_RATE && info.channels == 1) { "Working audio must be 16 kHz mono" }
            val out = FloatArray((info.dataBytes / 2).toInt())
            var at = 0
            for (block in Wav.blocks(input, 16_000)) { block.copyInto(out, at, 0, minOf(block.size, out.size - at)); at += block.size; if (at >= out.size) break }
            if (at < out.size) out.copyOf(at) else out
        }
        val run = store.runs(sessionId).filter { it.status == RunStatus.DONE }.let { done ->
            done.lastOrNull { it.engine == store.settings().baseEngine } ?: done.lastOrNull()
        }
        val lines = run?.segments ?: speechStretches(samples).map { Segment(it.first, it.last, "") }
        val pieces = ArrayList<VoicePiece>()
        val printer = VoicePrinter.open(context, Speech.threads()) ?: error("The voice print model is not downloaded")
        try {
            lines.forEachIndexed { i, seg ->
                pieces += printer.pieces(seg, i, samples, 0)
                progress((i + 1).toFloat() / lines.size)
            }
            val prints = VoicePrints(sessionId, run?.id.orEmpty(), printer.modelId, pieces, System.currentTimeMillis())
            store.savePrints(prints)
            return prints
        } finally {
            printer.close()
        }
    }

    /** Stretches of speech from the voice activity model, the same way transcription splits lines. */
    private fun speechStretches(samples: FloatArray): List<LongRange> {
        val vadPack = catalog.byRole(PackRole.VAD).first { models.state(it) is PackState.Verified }
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

    companion object {
        const val MAX_MS = 80 * 60_000L

        fun ready(context: Context): Boolean {
            val models = ModelStore(context)
            return ModelCatalog.bundled().byRole(PackRole.SPEAKER_EMBEDDING).any { models.state(it) is PackState.Verified }
        }
    }
}
