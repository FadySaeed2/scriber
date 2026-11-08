package com.fadysaeed.scriber.fetch

import com.fadysaeed.scriber.core.AllowedHosts
import com.fadysaeed.scriber.core.Hashing
import com.fadysaeed.scriber.core.ModelFile
import com.fadysaeed.scriber.core.ModelPack
import com.fadysaeed.scriber.core.Resume
import com.fadysaeed.scriber.core.ResumeAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class IntegrityException(message: String) : IOException(message)
class BlockedHostException(message: String) : IOException(message)
/** The model host answered with a server error; worth trying again shortly. */
class ServerBusyException(message: String) : IOException(message)
/** This place no longer has the file; another place may. */
class MissingThereException(message: String) : IOException(message)

/**
 * Downloads one catalogue file, resuming where it stopped, and refuses to keep it unless its size
 * and SHA-256 match the catalogue exactly.
 *
 * Redirects are followed by hand so every hop can be checked against [AllowedHosts]; Android's
 * network security config enforces the same list underneath, so either check alone would stop a
 * connection anywhere else.
 */
class ModelDownloader(private val store: ModelStore) {

    /** Progress callback: bytes of this file on the phone so far, and whether we are hashing. */
    fun interface Progress { fun update(bytes: Long, hashing: Boolean) }

    suspend fun fetch(pack: ModelPack, f: ModelFile, progress: Progress) {
        val done = store.file(pack, f)
        if (done.length() == f.size) {
            // Re-checked below at pack level when the marker is missing.
            progress.update(f.size, false)
            return
        }
        val part = store.partial(pack, f)
        part.parentFile?.mkdirs()
        // Each place that has the file gets a few tries when its host is overloaded (HTTP 5xx) or
        // the file is missing there; then the next place. Every try resumes from the bytes already
        // on the phone, and whichever place served them, the checksum below decides.
        val urls = f.urls
        var last: IOException? = null
        var ok = false
        for ((i, url) in urls.withIndex()) {
            var attempt = 0
            while (!ok) {
                try {
                    when (val plan = Resume.plan(part.length(), f.size)) {
                        ResumeAction.AlreadyComplete -> Unit
                        ResumeAction.StartFresh -> { part.delete(); download(f, url, part, 0, progress) }
                        is ResumeAction.ResumeFrom -> download(f, url, part, plan.offset, progress)
                    }
                    ok = true
                } catch (e: ServerBusyException) {
                    last = e
                    if (++attempt > (if (i == urls.lastIndex) 3 else 1)) break
                    kotlinx.coroutines.delay(5_000L * attempt)
                } catch (e: MissingThereException) {
                    last = e; break
                }
            }
            if (ok) break
        }
        if (!ok) throw IOException(last?.message ?: "Could not download ${f.path}")
        if (part.length() != f.size) throw IntegrityException("${f.path}: got ${part.length()} bytes, expected ${f.size}")
        val sha = Hashing.sha256(part) { progress.update(it, true) }
        if (!sha.equals(f.sha256, ignoreCase = true)) {
            part.delete()
            throw IntegrityException("${f.path}: checksum did not match the catalogue, file discarded")
        }
        if (!part.renameTo(done)) throw IOException("Could not move ${f.path} into place")
    }

    /** Re-hashes a finished file; used when a pack has no verified marker yet. */
    fun verify(pack: ModelPack, f: ModelFile, progress: Progress): Boolean {
        val done = store.file(pack, f)
        if (done.length() != f.size) return false
        return Hashing.sha256(done) { progress.update(it, true) }.equals(f.sha256, ignoreCase = true)
    }

    private suspend fun download(f: ModelFile, url: String, part: File, offset: Long, progress: Progress) {
        val conn = open(url, offset)
        try {
            val code = conn.responseCode
            val append = when {
                offset > 0 && code == 206 -> {
                    val start = Resume.contentRangeStart(conn.getHeaderField("Content-Range"))
                    if (start != offset) throw IOException("Server resumed at $start instead of $offset")
                    true
                }
                code == 200 -> false
                code in 500..599 -> throw ServerBusyException("HTTP $code for ${f.path}")
                code == 404 || code == 410 -> throw MissingThereException("HTTP $code for ${f.path}")
                else -> throw IOException("HTTP $code for ${f.path}")
            }
            if (!append) part.delete()
            var written = if (append) offset else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(1 shl 20)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written > f.size) throw IntegrityException("${f.path}: server sent more than ${f.size} bytes")
                        if (written - lastReport >= (4 shl 20)) { progress.update(written, false); lastReport = written }
                    }
                    out.fd.sync()
                }
            }
            progress.update(written, false)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, offset: Long): HttpsURLConnection {
        var current = URI(url)
        repeat(MAX_REDIRECTS) {
            if (current.scheme != "https") throw BlockedHostException("Refusing non-https address ${current.host}")
            if (!AllowedHosts.isAllowed(current.host)) throw BlockedHostException("Refusing ${current.host}: not a model download host")
            val conn = URL(current.toString()).openConnection() as HttpsURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "Scriber")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                conn.disconnect()
                current = current.resolve(location)
            } else {
                return conn
            }
        }
        throw IOException("Too many redirects for $url")
    }

    private companion object { const val MAX_REDIRECTS = 8 }
}
