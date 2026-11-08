package com.fadysaeed.scriber.core

/**
 * Chooses what to download for a given phone.
 *
 * Phones sold with 12 GB report a little under 12 GB of total memory, and 8 GB phones a little under
 * 8 GB, so the line between the two language models sits at 10 GB.
 */
object DevicePlan {
    private const val GB = 1024L * 1024L * 1024L

    fun languageModelFor(catalog: ModelCatalog, totalRamBytes: Long): ModelPack {
        val llms = catalog.byRole(PackRole.LLM).sortedByDescending { it.minRamGb }
        require(llms.isNotEmpty()) { "The catalogue has no language model" }
        return llms.firstOrNull { totalRamBytes >= it.minRamGb * GB } ?: llms.last()
    }

    /** Every required pack plus the language model that fits this phone. */
    fun defaultSelection(catalog: ModelCatalog, totalRamBytes: Long): List<ModelPack> =
        catalog.packs.filter { it.required } + languageModelFor(catalog, totalRamBytes)

    /** Space to keep free beyond the downloads themselves, so the phone never fills up. */
    const val SAFETY_MARGIN_BYTES: Long = 1L * GB

    fun bytesNeeded(packs: List<ModelPack>, alreadyOnPhone: (ModelFile) -> Long): Long =
        packs.flatMap { it.files }.sumOf { (it.size - alreadyOnPhone(it)).coerceAtLeast(0) } + SAFETY_MARGIN_BYTES
}
