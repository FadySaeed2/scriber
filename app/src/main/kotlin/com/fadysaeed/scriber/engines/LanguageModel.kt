package com.fadysaeed.scriber.engines

import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/** Result of one timed generation. "Pieces" are the streamed chunks, roughly one per token. */
data class Generation(
    val backend: String,
    val loadMs: Long,
    val firstPieceMs: Long,
    val pieces: Int,
    val piecesPerSecond: Double,
    val text: String,
    val fellBackFrom: String?,
)

/**
 * Runs a Gemma model through LiteRT-LM, trying the phone's GPU first and the CPU if the GPU path
 * cannot start. Greedy sampling (temperature 0) so the same input gives the same output.
 */
object LanguageModel {
    suspend fun generate(model: File, cacheDir: File, prompt: String, maxTokens: Int = 4096): Generation {
        var gpuProblem: String? = null
        for (backend in listOf<Backend>(Backend.GPU(), Backend.CPU())) {
            try {
                return run(model, cacheDir, prompt, maxTokens, backend).copy(fellBackFrom = gpuProblem)
            } catch (t: Throwable) {
                if (backend is Backend.CPU) throw t
                gpuProblem = "GPU: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
            }
        }
        error("unreachable")
    }

    private suspend fun run(model: File, cacheDir: File, prompt: String, maxTokens: Int, backend: Backend): Generation {
        val t0 = SystemClock.elapsedRealtime()
        Engine(EngineConfig(modelPath = model.path, backend = backend, maxNumTokens = maxTokens, cacheDir = cacheDir.path)).use { engine ->
            engine.initialize()
            val loaded = SystemClock.elapsedRealtime()
            val config = ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))
            engine.createConversation(config).use { conversation ->
                val text = StringBuilder()
                var first = -1L
                var pieces = 0
                val sent = SystemClock.elapsedRealtime()
                conversation.sendMessageAsync(prompt).collect { message ->
                    val piece = message.toString()
                    if (piece.isNotEmpty()) {
                        if (first < 0) first = SystemClock.elapsedRealtime()
                        pieces++
                        text.append(piece)
                    }
                }
                val end = SystemClock.elapsedRealtime()
                val generating = (end - (if (first > 0) first else sent)).coerceAtLeast(1)
                return Generation(
                    backend = backend.name,
                    loadMs = loaded - t0,
                    firstPieceMs = if (first > 0) first - sent else -1,
                    pieces = pieces,
                    piecesPerSecond = if (pieces > 1) (pieces - 1) * 1000.0 / generating else 0.0,
                    text = text.toString().trim(),
                    fellBackFrom = null,
                )
            }
        }
    }
}
