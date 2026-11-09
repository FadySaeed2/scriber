package com.fadysaeed.scriber.core

/** Chooses what to download: every required pack. */
object DevicePlan {
    private const val GB = 1024L * 1024L * 1024L

    fun defaultSelection(catalog: ModelCatalog): List<ModelPack> = catalog.packs.filter { it.required }

    /** Space to keep free beyond the downloads themselves, so the phone never fills up. */
    const val SAFETY_MARGIN_BYTES: Long = 1L * GB

    fun bytesNeeded(packs: List<ModelPack>, alreadyOnPhone: (ModelFile) -> Long): Long =
        packs.flatMap { it.files }.sumOf { (it.size - alreadyOnPhone(it)).coerceAtLeast(0) } + SAFETY_MARGIN_BYTES
}
