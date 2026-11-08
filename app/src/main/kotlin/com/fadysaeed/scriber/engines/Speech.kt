package com.fadysaeed.scriber.engines

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.fadysaeed.scriber.core.Resample
import java.io.File

/** 16 kHz mono audio, the format every speech model here expects. */
class Audio(val samples: FloatArray, val sampleRate: Int) {
    val seconds: Double get() = samples.size.toDouble() / sampleRate
}

/**
 * Thin wiring over sherpa-onnx. Each engine is created, used and released explicitly; native
 * memory is freed in release(), not by the garbage collector, so the language model has room.
 */
object Speech {
    const val SAMPLE_RATE = 16_000

    fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** Reads a wave file and always returns 16 kHz audio, whatever rate it was saved at. */
    fun readWave(file: File): Audio {
        val w = WaveReader.readWave(file.absolutePath)
        return Audio(Resample.mono(w.samples, w.sampleRate, SAMPLE_RATE), SAMPLE_RATE)
    }

    /** Medicine names Parakeet is nudged towards while it listens (sherpa-onnx hotwords). */
    class Hints(val hotwordsFile: File, val bpeVocab: File, val score: Float)

    /** The catalogue engine behind an engine name: "parakeet+hints" runs the Parakeet model. */
    fun modelEngine(engine: String) = engine.substringBefore('+')

    /**
     * A recogniser for an engine: "parakeet", "medasr", or "parakeet+hints" (Parakeet with beam
     * search and the medicine list as hotwords, which needs [hints]).
     */
    fun recognizer(engine: String, dir: File, threads: Int = threads(), hints: Hints? = null): OfflineRecognizer {
        val withHints = engine.endsWith("+hints") && hints != null
        val model = when (modelEngine(engine)) {
            "parakeet" -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                modelType = "nemo_transducer",
                numThreads = threads,
                provider = "cpu",
                modelingUnit = if (withHints) "bpe" else "",
                bpeVocab = if (withHints) hints!!.bpeVocab.path else "",
            )
            "medasr" -> OfflineModelConfig(
                medasr = OfflineMedAsrCtcModelConfig(model = File(dir, "model.int8.onnx").path),
                tokens = File(dir, "tokens.txt").path,
                numThreads = threads,
                provider = "cpu",
            )
            else -> error("Unknown speech engine $engine")
        }
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
                decodingMethod = if (withHints) "modified_beam_search" else "greedy_search",
                maxActivePaths = 4,
                hotwordsFile = if (withHints) hints!!.hotwordsFile.path else "",
                hotwordsScore = hints?.score ?: 1.5f,
            )
        )
    }

    fun transcribe(recognizer: OfflineRecognizer, audio: Audio): String = decode(recognizer, audio).text.trim()

    /** Full result: text, tokens, and each token's time from the start of [audio], in seconds. */
    fun decode(recognizer: OfflineRecognizer, audio: Audio): OfflineRecognizerResult {
        val stream = recognizer.createStream()
        try {
            // Very short pieces (a click, a breath at the end) are padded with silence to one second:
            // some models misbehave on a handful of frames. Times stay right because the padding is at the end.
            val min = audio.sampleRate
            val samples = if (audio.samples.size >= min) audio.samples else audio.samples.copyOf(min)
            stream.acceptWaveform(samples, audio.sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream)
        } finally {
            stream.release()
        }
    }

    fun vad(model: File): Vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.path,
                threshold = 0.5f,
                minSilenceDuration = 0.4f,
                minSpeechDuration = 0.25f,
                maxSpeechDuration = 20f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        )
    )

    /** Counts speech segments, feeding audio in the window size Silero expects. */
    fun countSpeechSegments(vad: Vad, audio: Audio): Int {
        var count = 0
        val window = 512
        var i = 0
        while (i + window <= audio.samples.size) {
            vad.acceptWaveform(audio.samples.copyOfRange(i, i + window))
            while (!vad.empty()) { count++; vad.pop() }
            i += window
        }
        vad.flush()
        while (!vad.empty()) { count++; vad.pop() }
        return count
    }

    fun diarizer(segmentation: File, embedding: File, speakers: Int = -1, threads: Int = 2) = OfflineSpeakerDiarization(
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentation.path),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = embedding.path, numThreads = threads),
            clustering = FastClusteringConfig(numClusters = speakers, threshold = 0.5f),
        )
    )
}
