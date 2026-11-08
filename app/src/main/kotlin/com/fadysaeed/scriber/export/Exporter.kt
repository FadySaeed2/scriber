package com.fadysaeed.scriber.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.fadysaeed.scriber.core.Ids
import com.fadysaeed.scriber.core.Session
import com.fadysaeed.scriber.core.TranscriptFormats
import com.fadysaeed.scriber.core.TranscriptRun
import com.fadysaeed.scriber.vault.Vault
import java.io.File
import java.io.OutputStream

/** Everything that can leave Scriber, and only when Fady asks. */
enum class ExportKind(val label: String, val note: String, val ext: String, val mime: String) {
    TEXT("Text with times", "Readable, one line per phrase", "txt", "text/plain"),
    TEXT_RAW("Raw text with times", "Exactly as the engine wrote it", "txt", "text/plain"),
    SRT("Subtitles (SRT)", "Opens in video players and editors", "srt", "application/x-subrip"),
    VTT("Subtitles (WebVTT)", "Web and browser players", "vtt", "text/vtt"),
    JSON("Full data (JSON)", "Raw segments, token times, engine, settings and seal; for other models", "json", "application/json"),
    AUDIO("Audio", "The recording, or the imported file exactly as it came in", "", "audio/*"),
}

object Exporter {
    fun fileName(session: Session, run: TranscriptRun?, kind: ExportKind): String {
        val stem = Ids.fileStem(session.title)
        return when (kind) {
            ExportKind.AUDIO -> session.original?.displayName ?: "$stem.wav"
            else -> "$stem-${run?.engine ?: "transcript"}${if (kind == ExportKind.TEXT_RAW) "-raw" else ""}.${kind.ext}"
        }
    }

    fun mime(session: Session, kind: ExportKind): String =
        if (kind == ExportKind.AUDIO) session.original?.mime ?: "audio/wav" else kind.mime

    fun write(vault: Vault, session: Session, run: TranscriptRun?, kind: ExportKind, out: OutputStream) {
        when (kind) {
            ExportKind.AUDIO -> {
                val name = session.original?.vaultName ?: session.audioName ?: error("No audio")
                vault.read(name).use { it.copyTo(out, 1 shl 16) }
            }
            else -> {
                val r = run ?: error("No transcript")
                val text = when (kind) {
                    ExportKind.TEXT -> TranscriptFormats.plain(r, session.mode, readable = true)
                    ExportKind.TEXT_RAW -> TranscriptFormats.plain(r, session.mode, readable = false)
                    ExportKind.SRT -> TranscriptFormats.srt(r, session.mode, readable = false)
                    ExportKind.VTT -> TranscriptFormats.vtt(r, session.mode, readable = false)
                    ExportKind.JSON -> TranscriptFormats.json(r)
                    ExportKind.AUDIO -> ""
                }
                out.write(text.toByteArray())
            }
        }
    }

    /** Writes to a place Fady picked with the system file picker. */
    fun saveTo(context: Context, uri: Uri, session: Session, run: TranscriptRun?, kind: ExportKind) {
        context.contentResolver.openOutputStream(uri)?.use { write(Vault.get(context), session, run, kind, it) } ?: error("Cannot write there")
    }

    /**
     * Prepares a share. The decrypted copy lives in a private cache folder that is emptied on the
     * next start and before every share, so nothing lingers.
     */
    fun shareIntent(context: Context, session: Session, run: TranscriptRun?, kind: ExportKind): Intent {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        clean(context)
        val f = File(dir, fileName(session, run, kind))
        f.outputStream().use { write(Vault.get(context), session, run, kind, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", f)
        return Intent(Intent.ACTION_SEND).setType(mime(session, kind)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .let { Intent.createChooser(it, "Share ${f.name}") }
    }

    fun clean(context: Context) {
        File(context.cacheDir, "export").listFiles()?.forEach { it.delete() }
    }
}
