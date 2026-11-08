package com.fadysaeed.scriber.fetch

import com.fadysaeed.scriber.vault.Records
import com.fadysaeed.scriber.vault.Vault
import kotlinx.serialization.Serializable

/**
 * Once the models are in place the downloader locks, and stays locked until Fady unlocks it from
 * the Security screen with his fingerprint or screen lock. While locked, nothing in Scriber opens a
 * network connection. The flag lives in the encrypted vault.
 */
object FetchLock {
    @Serializable
    data class State(val locked: Boolean = false, val changedAtMillis: Long = 0)

    private const val NAME = "fetch_lock"

    fun state(vault: Vault): State = Records(vault).get(NAME, State.serializer()) ?: State()
    fun isLocked(vault: Vault): Boolean = state(vault).locked

    fun lock(vault: Vault) = Records(vault).put(NAME, State.serializer(), State(true, System.currentTimeMillis()))
    fun unlock(vault: Vault) = Records(vault).put(NAME, State.serializer(), State(false, System.currentTimeMillis()))
}
