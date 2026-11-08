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
}
