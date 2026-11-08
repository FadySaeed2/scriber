package com.fadysaeed.scriber.work

import android.content.Context
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.SpeakerLayer
import com.fadysaeed.scriber.core.Speakers
import com.fadysaeed.scriber.core.Turn
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
            val raw = found.map { Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker) }
            val clean = Speakers.smooth(raw)
            val old = store.speakers(sessionId)
            val layer = SpeakerLayer(
                sessionId = sessionId, audioSha256 = session.audioSha256.orEmpty(), requested = count, turns = clean,
                // Names carry over when the same number of voices is found again; moves never do.
                names = old?.names?.takeIf { old.count == clean.map { it.speaker }.distinct().size }.orEmpty(),
                settings = mapOf("segmentation" to seg.id, "embedding" to emb.id, "clusters" to "$count", "threshold" to "0.5",
                    "rawChanges" to "${Speakers.changes(raw.sortedBy { it.startMs })}", "rawVoices" to "${raw.map { it.speaker }.distinct().size}",
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

    companion object {
        const val MAX_MS = 80 * 60_000L

        fun ready(context: Context): Boolean {
            val models = ModelStore(context)
            val catalog = ModelCatalog.bundled()
            return listOf(PackRole.SEGMENTATION, PackRole.SPEAKER_EMBEDDING).all { r -> catalog.byRole(r).any { models.state(it) is PackState.Verified } }
        }
    }
}
