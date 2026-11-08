package com.fadysaeed.scriber.vault

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Scriber's encrypted storage. Everything about patients (recordings, transcripts, notes, and the
 * settings that mention them) goes through here; nothing is written to disk in the clear.
 *
 * - Files use streaming AES-256-GCM (Tink AES256_GCM_HKDF_1MB), so hour-long recordings are
 *   encrypted and decrypted piece by piece without loading them into memory.
 * - Small records use AES-256-GCM.
 * - Both keys are themselves encrypted by a master key that lives in the Android Keystore
 *   hardware, so copying the app's files off the phone does not copy usable keys.
 * - Every ciphertext is bound to its own name (associated data), so swapping two files on disk
 *   makes both fail to open instead of showing the wrong patient's data.
 * - Writes go to a temporary file that is synced and then renamed, so a crash or a flat battery
 *   leaves either the old version or the new one, never half of each.
 */
class Vault private constructor(
    val root: File,
    private val aead: Aead,
    private val streaming: StreamingAead,
) {
    fun file(name: String): File = File(root, checkName(name))

    fun exists(name: String): Boolean = file(name).isFile

    fun write(name: String, block: (OutputStream) -> Unit) {
        val target = file(name)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            streaming.newEncryptingStream(KeepOpen(fos), ad("file", name)).use(block)
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Could not save $name")
        }
    }

    fun read(name: String): InputStream =
        streaming.newDecryptingStream(FileInputStream(file(name)), ad("file", name))

    /** Random access to an encrypted file, for players and media decoders that need to seek. */
    fun openSeekable(name: String): java.nio.channels.SeekableByteChannel {
        val channel = streaming.newSeekableDecryptingChannel(FileInputStream(file(name)).channel, ad("file", name))
        // Tink only knows the plaintext size after the first read, so read one byte and rewind.
        channel.read(java.nio.ByteBuffer.allocate(1))
        channel.position(0)
        return channel
    }

    fun putRecord(name: String, plain: ByteArray) {
        val sealed = aead.encrypt(plain, ad("record", name))
        val target = file("records/$name")
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(sealed); it.flush(); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Could not save record $name")
        }
    }

    fun getRecord(name: String): ByteArray? {
        val f = file("records/$name")
        if (!f.isFile) return null
        return aead.decrypt(f.readBytes(), ad("record", name))
    }

    fun delete(name: String): Boolean = file(name).delete()

    /** Deletes a folder and everything in it. */
    fun deleteTree(dir: String): Boolean = file(dir).deleteRecursively()

    /** Names of the files directly inside [dir] (not temporaries), sorted. */
    fun list(dir: String): List<String> =
        file(dir).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { "$dir/${it.name}" }?.sorted().orEmpty()

    /** Record names directly inside a records folder, without the "records/" prefix. */
    fun listRecords(dir: String): List<String> =
        file("records/$dir").listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { "$dir/${it.name}" }?.sorted().orEmpty()

    fun deleteRecord(name: String): Boolean = file("records/$name").delete()

    private fun ad(kind: String, name: String) = "scriber:$kind:$name".toByteArray()

    private fun checkName(name: String): String {
        require(name.isNotBlank() && !name.startsWith("/") && name.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
            "Unsafe vault name: $name"
        }
        return name
    }

    /** Lets the caller sync the real file after the encrypting stream has written its last block. */
    private class KeepOpen(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    companion object {
        private const val PREFS = "scriber_vault_keysets"
        private const val MASTER_KEY = "android-keystore://scriber_vault_master"

        @Volatile private var instance: Vault? = null

        fun get(context: Context): Vault =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): Vault {
            AeadConfig.register()
            StreamingAeadConfig.register()
            val records = AndroidKeysetManager.Builder()
                .withSharedPref(context, "records", PREFS)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY)
                .build()
                .keysetHandle
            val files = AndroidKeysetManager.Builder()
                .withSharedPref(context, "files", PREFS)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
                .withMasterKeyUri(MASTER_KEY)
                .build()
                .keysetHandle
            val root = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
            return Vault(
                root,
                records.getPrimitive(RegistryConfiguration.get(), Aead::class.java),
                files.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java),
            )
        }
    }
}
