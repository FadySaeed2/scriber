package com.fadysaeed.scriber.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.fadysaeed.scriber.core.Ids
import com.fadysaeed.scriber.core.Line
import com.fadysaeed.scriber.core.Session
import com.fadysaeed.scriber.core.TranscriptFormats
import com.fadysaeed.scriber.core.TranscriptRun
import com.fadysaeed.scriber.vault.Vault
import java.io.File
import java.io.OutputStream

/** Everything that can leave Scriber, and only when Fady asks. */
enum class ExportKind(val label: String, val note: String, val ext: String, val mime: String) {
    TEXT("Text with times", "Your corrected text, readable, one line per phrase", "txt", "text/plain"),
    TEXT_RAW("Raw text with times", "Exactly as the engine wrote it, no corrections", "txt", "text/plain"),
    SRT("Subtitles (SRT)", "Corrected text; opens in video players and editors", "srt", "application/x-subrip"),
    VTT("Subtitles (WebVTT)", "Corrected text; web and browser players", "vtt", "text/vtt"),
    JSON("Full data (JSON)", "Every sealed transcript with token times, your corrections, and the corrected text; for other models", "json", "application/json"),
    AUDIO("Audio", "The recording, or the imported file exactly as it came in", "", "audio/*"),
}

object Exporter {
    /** Everything an export can draw on, prepared by the screen that asks for it. */
    data class Material(val session: Session, val run: TranscriptRun?, val lines: List<Line>, val bundle: TranscriptFormats.Bundle)

    fun fileName(m: Material, kind: ExportKind): String {
        val stem = Ids.fileStem(m.session.title)
        return when (kind) {
            ExportKind.AUDIO -> m.session.original?.displayName ?: "$stem.wav"
            ExportKind.JSON -> "$stem.json"
            else -> "$stem-${m.run?.engine ?: "transcript"}${if (kind == ExportKind.TEXT_RAW) "-raw" else ""}.${kind.ext}"
        }
    }

    fun mime(m: Material, kind: ExportKind): String =
        if (kind == ExportKind.AUDIO) m.session.original?.mime ?: "audio/wav" else kind.mime

    fun write(vault: Vault, m: Material, kind: ExportKind, out: OutputStream) {
        val mode = m.session.mode
        when (kind) {
            ExportKind.AUDIO -> {
                val name = m.session.original?.vaultName ?: m.session.audioName ?: error("No audio")
                vault.read(name).use { it.copyTo(out, 1 shl 16) }
            }
            else -> {
                val r = m.run ?: error("No transcript")
                val text = when (kind) {
                    ExportKind.TEXT -> TranscriptFormats.plain(m.lines, mode, readable = true)
                    ExportKind.TEXT_RAW -> TranscriptFormats.plain(r, mode, readable = false)
                    ExportKind.SRT -> TranscriptFormats.srt(m.lines, mode, readable = false)
                    ExportKind.VTT -> TranscriptFormats.vtt(m.lines, mode, readable = false)
                    ExportKind.JSON -> TranscriptFormats.json(m.bundle)
                    ExportKind.AUDIO -> ""
                }
                out.write(text.toByteArray())
            }
        }
    }

    /** Writes to a place Fady picked with the system file picker. */
    fun saveTo(context: Context, uri: Uri, m: Material, kind: ExportKind) {
        context.contentResolver.openOutputStream(uri)?.use { write(Vault.get(context), m, kind, it) } ?: error("Cannot write there")
    }

    /**
     * Prepares a share. The decrypted copy lives in a private cache folder that is emptied on the
     * next start and before every share, so nothing lingers.
     */
    fun shareIntent(context: Context, m: Material, kind: ExportKind): Intent {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        clean(context)
        val f = File(dir, fileName(m, kind))
        f.outputStream().use { write(Vault.get(context), m, kind, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", f)
        return Intent(Intent.ACTION_SEND).setType(mime(m, kind)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .let { Intent.createChooser(it, "Share ${f.name}") }
    }

    fun clean(context: Context) {
        File(context.cacheDir, "export").listFiles()?.forEach { it.delete() }
    }
}
