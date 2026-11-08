package com.fadysaeed.scriber.vault

import android.os.SystemClock
import com.fadysaeed.scriber.core.Hashing
import java.util.Random

/** Writes and reads back random data through the vault and checks it is identical. */
object VaultSelfTest {
    data class Result(val ok: Boolean, val megabytes: Int, val writeMBps: Double, val readMBps: Double, val detail: String)

    fun run(vault: Vault, megabytes: Int = 64): Result {
        val name = "selftest/check.bin"
        val chunk = ByteArray(1 shl 20)
        val random = Random(42)
        val expected = java.security.MessageDigest.getInstance("SHA-256")
        return try {
            val t0 = SystemClock.elapsedRealtime()
            vault.write(name) { out ->
                repeat(megabytes) {
                    random.nextBytes(chunk)
                    expected.update(chunk)
                    out.write(chunk)
                }
            }
            val t1 = SystemClock.elapsedRealtime()
            val actual = vault.read(name).use { Hashing.sha256(it) }
            val t2 = SystemClock.elapsedRealtime()
            val want = expected.digest().joinToString("") { "%02x".format(it) }
            val onDisk = vault.file(name).length()
            vault.delete(name)
            Result(
                ok = actual == want,
                megabytes = megabytes,
                writeMBps = megabytes * 1000.0 / (t1 - t0).coerceAtLeast(1),
                readMBps = megabytes * 1000.0 / (t2 - t1).coerceAtLeast(1),
                detail = if (actual == want) "Encrypted size on disk ${onDisk / (1 shl 20)} MB" else "Read-back did not match",
            )
        } catch (t: Throwable) {
            vault.delete(name)
            Result(false, megabytes, 0.0, 0.0, t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
    }
}
