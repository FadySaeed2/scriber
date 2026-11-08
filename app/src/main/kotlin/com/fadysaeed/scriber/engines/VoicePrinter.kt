package com.fadysaeed.scriber.engines

import android.content.Context
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.Pieces
import com.fadysaeed.scriber.core.Segment
import com.fadysaeed.scriber.core.VoicePiece
import com.fadysaeed.scriber.core.Words
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

/** Makes voice prints (speaker embeddings) of short stretches of 16 kHz audio. */
class VoicePrinter private constructor(val modelId: String, path: String, threads: Int) : AutoCloseable {
    private val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = path, numThreads = threads))

    /** Voice print of samples [from, to), or null when there is too little sound. */
    fun print(samples: FloatArray, from: Int, to: Int): FloatArray? {
        val a = from.coerceIn(0, samples.size); val b = to.coerceIn(a, samples.size)
        if (b - a < (Pieces.MIN_PRINT_MS * Speech.SAMPLE_RATE / 1000).toInt()) return null
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples.copyOfRange(a, b), Speech.SAMPLE_RATE)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    /**
     * The pieces of one transcript line with their prints. [samples] hold the line's audio, starting
     * at [offsetMs] in the session.
     */
    fun pieces(seg: Segment, si: Int, samples: FloatArray, offsetMs: Long): List<VoicePiece> =
        Words.pieces(seg, si).map { (r, question) ->
            val w = Pieces.printWindow(r)
            val p = w?.let { print(samples, ((it.first - offsetMs) * 16).toInt(), ((it.last + 1 - offsetMs) * 16).toInt()) }
            VoicePiece(r.first, r.last + 1, si, question, p?.map { x -> Math.round(x * 10_000) / 10_000f })
        }

    override fun close() = extractor.release()

    companion object {
        fun open(context: Context, threads: Int = 2): VoicePrinter? {
            val models = ModelStore(context)
            val pack = ModelCatalog.bundled().byRole(PackRole.SPEAKER_EMBEDDING).firstOrNull { models.state(it) is PackState.Verified } ?: return null
            return VoicePrinter(pack.id, models.file(pack, pack.files.first()).path, threads)
        }
    }
}
