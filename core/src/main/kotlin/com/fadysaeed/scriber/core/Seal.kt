package com.fadysaeed.scriber.core

import kotlinx.serialization.json.Json

object Seal {
    private val canonical = Json { encodeDefaults = true; prettyPrint = false }

    fun of(run: TranscriptRun): String =
        Hashing.sha256(canonical.encodeToString(TranscriptRun.serializer(), run.copy(seal = null)).encodeToByteArray())

    fun sealed(run: TranscriptRun): TranscriptRun = run.copy(seal = of(run))

    fun intact(run: TranscriptRun): Boolean = run.seal != null && run.seal == of(run)
}
