package com.fadysaeed.scriber.audio

import com.fadysaeed.scriber.core.SessionStatus
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.vault.Vault

/**
 * Joins the 5-second pieces of a recording (or of a decoded import) into one WAV in the vault,
 * then removes the pieces. Used after a normal stop and to rescue a recording the app or phone
 * stopped in the middle of: everything up to the last saved piece is kept.
 */
class Assembler(private val vault: Vault, private val store: SessionStore) {

    fun assemble(pieces: List<String>, sampleRate: Int, outName: String): WavWriter.Result {
        val total = pieces.sumOf { name -> vault.read(name).use { it.readBytes().size.toLong() } }
        return WavWriter(sampleRate).write(vault, outName, total) { put ->
            for (name in pieces) vault.read(name).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; put(buf, n) }
            }
        }
    }

    /** Finishes a recorded session from its pieces. Returns false when nothing was recorded. */
    fun finishRecording(id: String, recovered: Boolean): Boolean {
        val s = store.session(id) ?: return false
        val pieces = store.chunks(id)
        if (pieces.isEmpty()) {
            store.update(id) { it.copy(status = SessionStatus.FAILED, problem = "Nothing was recorded", recovered = recovered) }
            return false
        }
        store.update(id) { it.copy(status = SessionStatus.FINALIZING) }
        val main = store.audioName(id)
        val r = assemble(pieces, s.sampleRate, main)
        val work = if (s.sampleRate == 16_000) main else store.work16kName(id).also { AudioFiles.make16k(vault, main, it) }
        store.update(id) {
            it.copy(
                status = SessionStatus.READY, audioName = main, audioSha256 = r.sha256, audioBytes = r.bytes,
                durationMs = r.samples * 1000 / s.sampleRate, workAudioName = work, recovered = recovered || it.recovered,
            )
        }
        vault.deleteTree(store.chunkDir(id))
        return true
    }
}
