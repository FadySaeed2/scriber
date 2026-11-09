package com.fadysaeed.scriber.data

import com.fadysaeed.scriber.core.Backup
import com.fadysaeed.scriber.vault.Vault
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Backs up the whole vault into one passphrase-locked file, and restores one. A restore adds what
 * the phone does not have yet and never overwrites: sessions, transcripts, corrections, speakers,
 * prompts and settings already here stay as they are.
 */
class Backups(private val vault: Vault) {
    data class Result(val files: Int, val skipped: Int, val bytes: Long)

    /** The job queue and crash notes describe this phone's state at the time, not Fady's work. */
    private fun wanted(name: String) = name != "records/jobs.json" && !name.startsWith("records/crash")

    fun backup(out: OutputStream, passphrase: CharArray, progress: (Float) -> Unit): Result {
        val names = vault.all().filter(::wanted)
        val total = names.sumOf { vault.file(it).length() }.coerceAtLeast(1)
        var done = 0L; var files = 0; var skipped = 0
        val w = Backup.Writer(out, passphrase)
        w.use {
            for (n in names) {
                if (n.startsWith("records/")) {
                    val plain = runCatching { vault.getRecord(n.removePrefix("records/")) }.getOrNull()
                    if (plain == null) skipped++ else { w.entry(n, ByteArrayInputStream(plain)); files++ }
                } else {
                    vault.read(n).use { w.entry(n, it) }; files++
                }
                done += vault.file(n).length()
                progress(done.toFloat() / total)
            }
        }
        return Result(files, skipped, w.plainBytes)
    }

    fun restore(input: InputStream, size: Long, passphrase: CharArray, progress: (Float) -> Unit): Result {
        var read = 0L
        val counted = object : FilterInputStream(input) {
            override fun read(): Int = super.read().also { if (it >= 0) { read++; tick() } }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { read += it; tick() } }
            fun tick() { if (size > 0) progress((read.toFloat() / size).coerceAtMost(1f)) }
        }
        var files = 0; var skipped = 0; var bytes = 0L
        Backup.read(counted, passphrase) { name, data ->
            // Names that could reach outside the vault, or things this phone already has, are left alone.
            val here = runCatching { vault.file(name).exists() }.getOrNull()
            if (!wanted(name) || here != false) { skipped++; return@read }
            if (name.startsWith("records/")) {
                val plain = data.readBytes(); bytes += plain.size
                vault.putRecord(name.removePrefix("records/"), plain)
            } else {
                vault.write(name) { out -> bytes += data.copyTo(out) }
            }
            files++
        }
        return Result(files, skipped, bytes)
    }
}
