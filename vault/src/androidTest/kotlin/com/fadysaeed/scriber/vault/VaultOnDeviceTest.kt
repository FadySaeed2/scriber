package com.fadysaeed.scriber.vault

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultOnDeviceTest {
    private val vault = Vault.get(ApplicationProvider.getApplicationContext())

    @Test fun roundTripsALargeFile() {
        val r = VaultSelfTest.run(vault, 24)
        assertTrue(r.detail, r.ok)
    }

    @Test fun filesAreNotStoredInTheClear() {
        val secret = "Patient says the voices started in March".toByteArray()
        vault.write("t/plain-check.bin") { it.write(secret) }
        val raw = vault.file("t/plain-check.bin").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("voices"))
        assertArrayEquals(secret, vault.read("t/plain-check.bin").use { it.readBytes() })
        vault.delete("t/plain-check.bin")
    }

    @Test fun swappedFilesRefuseToOpen() {
        vault.write("t/a.bin") { it.write("A".repeat(100).toByteArray()) }
        vault.write("t/b.bin") { it.write("B".repeat(100).toByteArray()) }
        val a = vault.file("t/a.bin"); val b = vault.file("t/b.bin")
        val tmp = File(a.parentFile, "swap")
        a.renameTo(tmp); b.renameTo(a); tmp.renameTo(b)
        try {
            vault.read("t/a.bin").use { it.readBytes() }
            fail("A file under the wrong name must not decrypt")
        } catch (expected: Exception) {
        } finally {
            vault.delete("t/a.bin"); vault.delete("t/b.bin")
        }
    }

    @Test fun recordsRoundTripAndMissingIsNull() {
        vault.putRecord("t-rec", "hello".toByteArray())
        assertEquals("hello", vault.getRecord("t-rec")!!.decodeToString())
        assertNull(vault.getRecord("does-not-exist"))
    }

    @Test fun rejectsUnsafeNames() {
        for (bad in listOf("../x", "/abs", "a/../b", "", "a//b")) {
            try { vault.file(bad); fail("accepted $bad") } catch (expected: IllegalArgumentException) {}
        }
    }

    @Test fun vaultLivesOutsideBackups() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(vault.root.path.startsWith(ctx.noBackupFilesDir.path))
    }
}
