package com.fadysaeed.scriber.fetch

import android.content.Context
import com.fadysaeed.scriber.core.ModelFile
import com.fadysaeed.scriber.core.ModelPack
import java.io.File

/** Where a pack's state stands on this phone. */
sealed interface PackState {
    data object Missing : PackState
    data class Partial(val bytesOnPhone: Long) : PackState
    data object Verified : PackState
}

/**
 * Model files live in the app's no-backup folder: private to Scriber, never copied to Google or
 * Samsung backups. They are public files, so they are not encrypted (that would also stop the
 * engines from memory-mapping them).
 *
 * A pack counts as verified only when every file has its exact size and a marker records that all
 * of their SHA-256 hashes were checked against the catalogue.
 */
class ModelStore(context: Context) {
    val root: File = File(context.applicationContext.noBackupFilesDir, "models").apply { mkdirs() }

    fun dir(pack: ModelPack) = File(root, pack.id)
    fun file(pack: ModelPack, f: ModelFile) = File(dir(pack), f.path)
    fun partial(pack: ModelPack, f: ModelFile) = File(dir(pack), f.path + ".part")
    private fun marker(pack: ModelPack) = File(dir(pack), ".verified")

    private fun fingerprint(pack: ModelPack) = pack.files.joinToString("\n") { "${it.path} ${it.size} ${it.sha256}" }

    fun state(pack: ModelPack): PackState {
        val m = marker(pack)
        if (m.isFile && m.readText() == fingerprint(pack) && pack.files.all { file(pack, it).length() == it.size }) {
            return PackState.Verified
        }
        val bytes = bytesOnPhone(pack)
        return if (bytes == 0L) PackState.Missing else PackState.Partial(bytes)
    }

    fun bytesOnPhone(pack: ModelPack): Long = pack.files.sumOf { bytesOnPhone(pack, it) }

    fun bytesOnPhone(pack: ModelPack, f: ModelFile): Long {
        val done = file(pack, f)
        if (done.length() == f.size) return f.size
        return partial(pack, f).length().coerceAtMost(f.size)
    }

    fun markVerified(pack: ModelPack) {
        val m = marker(pack)
        val tmp = File(m.parentFile, ".verified.tmp")
        tmp.writeText(fingerprint(pack))
        tmp.renameTo(m)
    }

    fun forget(pack: ModelPack) {
        marker(pack).delete()
    }

    fun delete(pack: ModelPack) {
        dir(pack).deleteRecursively()
    }
}
