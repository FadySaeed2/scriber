package com.fadysaeed.scriber.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    private const val BUFFER = 1 shl 20

    fun sha256(file: File, onBytes: (Long) -> Unit = {}): String =
        file.inputStream().use { sha256(it, onBytes) }

    fun sha256(input: InputStream, onBytes: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            total += n
            onBytes(total)
        }
        return digest.digest().toHex()
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
