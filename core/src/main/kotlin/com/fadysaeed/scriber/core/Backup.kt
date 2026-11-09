package com.fadysaeed.scriber.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One file holding everything in Scriber, locked with a passphrase, to move to a new phone or to
 * keep somewhere safe. The phone's own keys never leave it: the backup is decrypted from the vault
 * and encrypted again with a key made from the passphrase (PBKDF2, SHA-256, 310,000 rounds).
 *
 * Layout: "SCRIBERBK1", rounds (int), salt (16 bytes), then sealed pieces of up to 1 MiB, each
 * [plain length int][12-byte nonce][AES-256-GCM ciphertext and tag], with the piece number and a
 * last-piece flag bound into each, so pieces cannot be reordered, dropped or cut off unnoticed.
 * Inside: entries of [name (UTF)] then chunks [length int][bytes] ending with a 0 length; an empty
 * name ends the backup.
 */
object Backup {
    private val MAGIC = "SCRIBERBK1".toByteArray()
    const val ROUNDS = 310_000
    private const val PIECE = 1 shl 20

    class WrongPassphrase : Exception("Wrong passphrase, or not a Scriber backup")
    class Damaged(message: String) : Exception(message)

    private fun key(passphrase: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, rounds, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun aad(index: Long, last: Boolean) = ByteArray(9).also {
        for (i in 0 until 8) it[i] = (index ushr (56 - 8 * i)).toByte()
        it[8] = if (last) 1 else 0
    }

    /** Writes a backup to [out]; add entries, then [close] (which does not close [out]). */
    class Writer(private val out: OutputStream, passphrase: CharArray, rounds: Int = ROUNDS) : AutoCloseable {
        private val random = SecureRandom()
        private val key: SecretKeySpec
        private val buf = ByteArrayOutputStream(PIECE + 1024)
        private val data = DataOutputStream(buf)
        private var index = 0L
        private var closed = false
        var plainBytes = 0L; private set

        init {
            val salt = ByteArray(16).also(random::nextBytes)
            key = key(passphrase, salt, rounds)
            val head = DataOutputStream(out)
            head.write(MAGIC); head.writeInt(rounds); head.write(salt); head.flush()
        }

        private fun seal(plain: ByteArray, last: Boolean) {
            val nonce = ByteArray(12).also(random::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            c.updateAAD(aad(index++, last))
            val sealed = c.doFinal(plain)
            val d = DataOutputStream(out)
            d.writeInt(plain.size); d.write(nonce); d.write(sealed); d.flush()
        }

        private fun spill() {
            while (buf.size() >= PIECE) {
                val all = buf.toByteArray()
                seal(all.copyOfRange(0, PIECE), false)
                buf.reset(); buf.write(all, PIECE, all.size - PIECE)
            }
        }

        /** Adds one file under [name], its bytes read from [input] to the end. */
        fun entry(name: String, input: InputStream) {
            require(name.isNotEmpty())
            data.writeUTF(name)
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (n == 0) continue
                data.writeInt(n); data.write(chunk, 0, n); plainBytes += n
                spill()
            }
            data.writeInt(0)
            spill()
        }

        override fun close() {
            if (closed) return
            closed = true
            data.writeUTF("")
            data.flush()
            val all = buf.toByteArray()
            var at = 0
            while (all.size - at > PIECE) { seal(all.copyOfRange(at, at + PIECE), false); at += PIECE }
            seal(all.copyOfRange(at, all.size), true)
            out.flush()
        }
    }

    /** The plain bytes of a backup, piece by piece, checking every seal. */
    private class Opened(input: InputStream, passphrase: CharArray) : InputStream() {
        private val src = DataInputStream(input)
        private val key: SecretKeySpec
        private var piece = ByteArray(0)
        private var at = 0
        private var index = 0L
        private var ended = false

        init {
            val magic = ByteArray(MAGIC.size)
            try { src.readFully(magic) } catch (_: EOFException) { throw WrongPassphrase() }
            if (!magic.contentEquals(MAGIC)) throw WrongPassphrase()
            val rounds = src.readInt()
            if (rounds !in 10_000..10_000_000) throw Damaged("This backup's header is damaged")
            val salt = ByteArray(16).also(src::readFully)
            key = key(passphrase, salt, rounds)
        }

        private fun nextPiece(): Boolean {
            if (ended) return false
            val len = try { src.readInt() } catch (_: EOFException) { throw Damaged("This backup is cut short") }
            if (len < 0 || len > PIECE) throw Damaged("This backup is damaged")
            val nonce = ByteArray(12); val sealed = ByteArray(len + 16)
            try { src.readFully(nonce); src.readFully(sealed) } catch (_: EOFException) { throw Damaged("This backup is cut short") }
            val i = index++
            // A piece is either the last one or not; try the likely one first.
            piece = open(nonce, sealed, i, false) ?: open(nonce, sealed, i, true)?.also { ended = true }
                ?: if (i == 0L) throw WrongPassphrase() else throw Damaged("This backup is damaged at piece ${i + 1}")
            at = 0
            return true
        }

        private fun open(nonce: ByteArray, sealed: ByteArray, i: Long, last: Boolean): ByteArray? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            c.updateAAD(aad(i, last))
            c.doFinal(sealed)
        } catch (_: AEADBadTagException) { null }

        override fun read(): Int {
            while (at >= piece.size) if (!nextPiece()) return -1
            return piece[at++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at >= piece.size) if (!nextPiece()) return -1
            val n = minOf(len, piece.size - at)
            System.arraycopy(piece, at, b, off, n); at += n
            return n
        }
    }

    /**
     * Reads every entry of a backup, handing each to [onEntry] as a stream of its bytes (read it
     * to the end, or not at all). Throws [WrongPassphrase] or [Damaged] before handing over
     * anything that failed its seal.
     */
    fun read(input: InputStream, passphrase: CharArray, onEntry: (name: String, data: InputStream) -> Unit) {
        val plain = DataInputStream(Opened(input, passphrase))
        while (true) {
            val name = try { plain.readUTF() } catch (_: EOFException) { throw Damaged("This backup is cut short") }
            if (name.isEmpty()) return
            val body = object : InputStream() {
                var left = 0; var done = false
                private fun fill(): Boolean {
                    while (left == 0 && !done) { left = plain.readInt(); if (left == 0) done = true }
                    return !done || left > 0
                }
                override fun read(): Int { if (left == 0 && !fill()) return -1; left--; return plain.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    if (left == 0 && !fill()) return -1
                    val n = plain.read(b, off, minOf(len, left))
                    if (n < 0) throw Damaged("This backup is cut short")
                    left -= n; return n
                }
            }
            onEntry(name, body)
            // Skip whatever the caller did not read.
            val skip = ByteArray(64 * 1024)
            while (body.read(skip) >= 0) { /* drain */ }
        }
    }
}
