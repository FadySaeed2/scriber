package com.fadysaeed.scriber

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fadysaeed.scriber.core.Backup
import com.fadysaeed.scriber.data.Backups
import com.fadysaeed.scriber.vault.Vault
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** A backup made from the real vault opens with its passphrase and gives back what was in it. */
@RunWith(AndroidJUnit4::class)
class BackupOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val vault = Vault.get(context)

    @Test fun backUpWipeAndRestore() {
        val record = "{\"hello\":\"backup\"}".toByteArray()
        val audio = ByteArray(1_500_000) { (it % 97).toByte() }
        vault.putRecord("bktest/a.json", record)
        vault.write("bktest/audio.bin") { it.write(audio) }

        val out = ByteArrayOutputStream()
        val made = Backups(vault).backup(out, "a long passphrase".toCharArray()) {}
        assertTrue(made.files >= 2)
        val bytes = out.toByteArray()

        vault.deleteTree("records/bktest"); vault.deleteTree("bktest")
        assertFalse(vault.exists("bktest/audio.bin"))

        // A wrong passphrase restores nothing.
        try { Backups(vault).restore(ByteArrayInputStream(bytes), bytes.size.toLong(), "not it".toCharArray()) {}; fail() } catch (_: Backup.WrongPassphrase) {}
        assertFalse(vault.exists("bktest/audio.bin"))

        var last = 0f
        val back = Backups(vault).restore(ByteArrayInputStream(bytes), bytes.size.toLong(), "a long passphrase".toCharArray()) { last = it }
        assertEquals(2, back.files)
        assertTrue(back.skipped >= 0)
        assertTrue(last > 0.9f)
        assertArrayEquals(record, vault.getRecord("bktest/a.json"))
        assertArrayEquals(audio, vault.read("bktest/audio.bin").use { it.readBytes() })

        // Restoring again changes nothing: everything is already here.
        val again = Backups(vault).restore(ByteArrayInputStream(bytes), bytes.size.toLong(), "a long passphrase".toCharArray()) {}
        assertEquals(0, again.files)
        vault.deleteTree("records/bktest"); vault.deleteTree("bktest")
    }
}
