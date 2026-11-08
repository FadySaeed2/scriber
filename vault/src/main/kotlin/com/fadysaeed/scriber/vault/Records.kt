package com.fadysaeed.scriber.vault

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Typed JSON records stored encrypted in the vault. */
class Records(private val vault: Vault) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun <T> get(name: String, serializer: KSerializer<T>): T? =
        vault.getRecord("$name.json")?.let { json.decodeFromString(serializer, it.decodeToString()) }

    fun <T> put(name: String, serializer: KSerializer<T>, value: T) =
        vault.putRecord("$name.json", json.encodeToString(serializer, value).encodeToByteArray())

    /** Every record in a folder, skipping any that cannot be read (reported in [problems]). */
    fun <T> all(dir: String, serializer: KSerializer<T>, problems: MutableList<String>? = null): List<T> =
        vault.listRecords(dir).mapNotNull { name ->
            runCatching { vault.getRecord(name)?.let { json.decodeFromString(serializer, it.decodeToString()) } }
                .onFailure { problems?.add("$name: ${it.message}") }.getOrNull()
        }
}
