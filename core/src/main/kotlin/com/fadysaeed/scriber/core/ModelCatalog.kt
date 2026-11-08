package com.fadysaeed.scriber.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a model pack is for. The app decides how to use a pack from its role, never from its name. */
@Serializable
enum class PackRole { ASR, VAD, SEGMENTATION, SPEAKER_EMBEDDING, LLM, BENCHMARK_AUDIO }

/**
 * One file of a model pack.
 *
 * [url] is pinned to an exact revision wherever the host allows it, and [sha256] is checked after
 * every download, so a file that changes upstream is rejected instead of silently used.
 * [path] is where the file lives inside the pack folder on the phone.
 */
@Serializable
data class ModelFile(
    val path: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class ModelPack(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val role: PackRole,
    /** For ASR packs: which recogniser wiring to use ("parakeet", "medasr"). */
    val engine: String? = null,
    /** For LLM packs: the size variant ("E4B", "E2B"). */
    val variant: String? = null,
    /** Downloaded by default on every phone. Language models are chosen separately by memory. */
    val required: Boolean = false,
    /** Smallest phone memory, in GB, that this pack is meant for. */
    val minRamGb: Int = 0,
    val license: String,
    val source: String,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

@Serializable
data class ModelCatalog(
    val version: Int,
    val packs: List<ModelPack>,
) {
    fun pack(id: String): ModelPack? = packs.firstOrNull { it.id == id }
    fun byRole(role: PackRole): List<ModelPack> = packs.filter { it.role == role }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        fun parse(text: String): ModelCatalog = json.decodeFromString(serializer(), text)

        /** The catalogue that ships inside the app. */
        fun bundled(): ModelCatalog {
            val stream = ModelCatalog::class.java.getResourceAsStream("/model_catalog.json")
                ?: error("model_catalog.json is missing from the build")
            return stream.use { parse(it.readBytes().decodeToString()) }
        }
    }
}
