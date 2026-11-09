package com.fadysaeed.scriber.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    private val pass = "correct horse battery".toCharArray()
    private val audio = ByteArray(2_600_000) { (it * 31 % 251).toByte() }

    private fun make(): ByteArray {
        val out = ByteArrayOutputStream()
        Backup.Writer(out, pass.copyOf(), rounds = 10_000).use { w ->
            w.entry("records/settings.json", ByteArrayInputStream("{\"a\":1}".toByteArray()))
            w.entry("audio/s1.wav", ByteArrayInputStream(audio))
            w.entry("records/empty.json", ByteArrayInputStream(ByteArray(0)))
        }
        return out.toByteArray()
    }

    private fun readAll(bytes: ByteArray, p: CharArray = pass): Map<String, ByteArray> {
        val got = LinkedHashMap<String, ByteArray>()
        Backup.read(ByteArrayInputStream(bytes), p) { name, data -> got[name] = data.readBytes() }
        return got
    }

    @Test fun everythingComesBackAsItWent() {
        val bytes = make()
        val got = readAll(bytes)
        assertEquals(listOf("records/settings.json", "audio/s1.wav", "records/empty.json"), got.keys.toList())
        assertEquals("{\"a\":1}", String(got.getValue("records/settings.json")))
        assertArrayEquals(audio, got.getValue("audio/s1.wav"))
        assertEquals(0, got.getValue("records/empty.json").size)
        // Nothing readable in the file itself.
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("settings.json"))
    }

    @Test fun entriesTheReaderSkipsDoNotBreakTheRest() {
        val names = ArrayList<String>()
        Backup.read(ByteArrayInputStream(make()), pass) { name, data -> names += name; if (name.startsWith("records")) data.readBytes() }
        assertEquals(3, names.size)
    }

    @Test fun aWrongPassphraseIsSaidPlainly() {
        try { readAll(make(), "wrong one".toCharArray()); fail() } catch (_: Backup.WrongPassphrase) {}
        try { readAll("not a backup at all".toByteArray()); fail() } catch (_: Backup.WrongPassphrase) {}
    }

    @Test fun aCutOrChangedBackupIsCaught() {
        val bytes = make()
        try { readAll(bytes.copyOf(bytes.size - 500)); fail() } catch (_: Backup.Damaged) {}
        // Drop the last piece entirely: still caught, though every remaining piece is intact.
        val firstTwo = 10 + 4 + 16 + 2 * (4 + 12 + (1 shl 20) + 16)
        try { readAll(bytes.copyOf(firstTwo)); fail() } catch (_: Backup.Damaged) {}
        val changed = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        try { readAll(changed); fail() } catch (e: Backup.Damaged) { assertTrue(e.message!!.contains("damaged")) }
    }
}
