package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.SessionUi
import com.fadysaeed.scriber.core.Difference
import com.fadysaeed.scriber.core.MedicineHit
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Seal
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.SessionSource
import com.fadysaeed.scriber.core.SessionStatus
import com.fadysaeed.scriber.core.TranscriptFormats
import com.fadysaeed.scriber.core.Units
import com.fadysaeed.scriber.export.ExportKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface SessionSheet {
    data object Review : SessionSheet
    data object Speakers : SessionSheet
    /** A line's words [from] until [to], now said by [speaker]. */
    data class Speaker(val from: Int, val to: Int, val speaker: Int, val text: String) : SessionSheet
    /** Edit base words [from] until [to] of line [segment]; [current] is what shows there now. */
    data class Words(val from: Int, val to: Int, val segment: Int, val current: String, val heard: String, val startMs: Long, val endMs: Long, val line: Boolean = false) : SessionSheet
    data object Export : SessionSheet
    data object Engine : SessionSheet
    data object Rename : SessionSheet
    data object DeleteAudio : SessionSheet
    data object DeleteSession : SessionSheet
    data class Confirm(val kind: ExportKind, val share: Boolean) : SessionSheet
    /** Where sentence [sentence] of note section [section] came from. */
    data class Sources(val section: Int, val sentence: Int) : SessionSheet
    data class ChooseTemplate(val rewriteOnly: Boolean) : SessionSheet
    data object EditNote : SessionSheet
    data class FactSheet(val factId: String) : SessionSheet
    data object Dropped : SessionSheet
}

class SessionActions(
    val onBack: () -> Unit,
    val onSelectRun: (String) -> Unit,
    val onReadable: (Boolean) -> Unit,
    val onPlay: (Long) -> Unit,
    val onStopPlaying: () -> Unit,
    val onTranscribeAgain: (String) -> Unit,
    val onRename: (String) -> Unit,
    val onExport: (ExportKind, Boolean) -> Unit,
    val onDeleteAudio: () -> Unit,
    val onDeleteSession: () -> Unit,
    val review: ReviewActions = ReviewActions(),
    val note: NoteActions = NoteActions(),
)

/** What Fady can do to correct the transcript. */
class ReviewActions(
    val onAccept: (Difference) -> Unit = {},
    val onKeep: (String) -> Unit = {},
    val onAcceptMedicine: (MedicineHit) -> Unit = {},
    val onAcceptAllMedicines: () -> Unit = {},
    val onEdit: (from: Int, to: Int, segment: Int, text: String, addToDictionary: Boolean) -> Unit = { _, _, _, _, _ -> },
    val onUndo: () -> Unit = {},
    val onShowEdited: (Boolean) -> Unit = {},
    val onReference: (Boolean) -> Unit = {},
    val onSecondOpinion: () -> Unit = {},
    val onPlayRange: (Long, Long) -> Unit = { _, _ -> },
    val onFindSpeakers: (Int) -> Unit = {},
    val onRenameSpeaker: (Int, String) -> Unit = { _, _ -> },
    val onMoveSpeaker: (Int, Int, Int) -> Unit = { _, _, _ -> },
    val onHideSpeakers: () -> Unit = {},
)

private fun stamp(ms: Long) = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ENGLISH).format(Date(ms))

@Composable
fun SessionScreen(ui: SessionUi, a: SessionActions, sheetOverride: SessionSheet? = null, viewOverride: SessionView? = null) {
    val s = ui.session ?: return
    var sheet by remember { mutableStateOf(sheetOverride) }
    val views = s.mode == SessionMode.CONVERSATION && (ui.canWriteNotes || ui.note != null || ui.facts != null)
    var view by remember(s.id) { mutableStateOf(viewOverride ?: SessionView.TRANSCRIPT) }
    if (!views) view = SessionView.TRANSCRIPT
    Box {
        Page(s.title, a.onBack, actions = { IconButton(Glyph.RENAME, "Rename", { sheet = SessionSheet.Rename }) }, footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val p = ui.player
                if (p.playing) GhostButton("Stop  ${clock(p.positionMs)}", a.onStopPlaying, Modifier.weight(1f), minHeight = 52.dp)
                else GhostButton(if (s.hasAudio) "Play" else "Audio deleted", { a.onPlay(0) }, Modifier.weight(1f), enabled = s.hasAudio && s.status == SessionStatus.READY, minHeight = 52.dp)
                AmberButton("Export", { sheet = SessionSheet.Export }, Modifier.weight(1f), enabled = ui.run?.status == RunStatus.DONE || s.hasAudio)
            }
        }) {
            Spacer(Modifier.height(14.dp))
            Text(
                listOfNotNull(stamp(s.createdAtMillis), if (s.durationMs > 0) clock(s.durationMs) else null,
                    if (s.mode == SessionMode.DICTATION) "Dictation" else "Conversation").joinToString("  ·  "),
                style = Type.figure,
            )
            ui.job?.let { j ->
                Spacer(Modifier.height(14.dp))
                Status("${j.label} ${(j.fraction * 100).toInt()}%", Ink.Teal)
                Spacer(Modifier.height(8.dp))
                Meter(j.fraction, Ink.Teal)
            }
            s.problem?.let { Spacer(Modifier.height(12.dp)); Text(it, style = Type.body.copy(color = Ink.Ember)) }
            if (s.recovered) { Spacer(Modifier.height(12.dp)); Text("This recording stopped unexpectedly and was rescued up to its last saved moment.", style = Type.note.copy(color = Ink.Amber)) }

            if (views) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in SessionView.entries) Choice(v.label, v == view, { view = v }, Modifier.weight(1f))
                }
            }
            when (view) {
                SessionView.NOTE -> NoteView(ui, a) { sheet = it }
                SessionView.FACTS -> FactsView(ui, a) { sheet = it }
                SessionView.TRANSCRIPT -> TranscriptView(ui, a, s) { sheet = it }
            }
            if (view == SessionView.TRANSCRIPT) {
                Section("This session")
                Fact("Source", if (s.source == SessionSource.IMPORTED) s.original?.let { "Imported: ${it.displayName}" } ?: "Imported" else "Recorded here")
                Fact("Audio", s.audioDeletedAtMillis?.let { "Deleted ${stamp(it)}" } ?: "${Units.bytes(s.audioBytes + (s.original?.size ?: 0))}, kept")
                if (s.source == SessionSource.RECORDED) Fact("Quality", "${s.sampleRate / 1000} kHz mono")
                s.audioSha256?.let { Fact("Audio fingerprint", it.take(16) + "…") }
                Spacer(Modifier.height(6.dp))
                ActionRow(Glyph.REDO, "Transcribe again", { sheet = SessionSheet.Engine }, "A new transcript with any engine; earlier ones stay", enabled = (s.hasAudio || (s.original != null && s.workAudioName == null)) && ui.engines.isNotEmpty() && ui.job == null)
                ActionRow(Glyph.TRASH, "Delete the audio, keep transcripts", { sheet = SessionSheet.DeleteAudio }, color = Ink.Amber, enabled = s.hasAudio)
                ActionRow(Glyph.TRASH, "Delete this session", { sheet = SessionSheet.DeleteSession }, color = Ink.Ember)
            }
        }

        when (val sh = sheet) {
            null -> Unit
            is SessionSheet.Sources -> SourcesSheet(ui, sh, a) { sheet = null }
            is SessionSheet.ChooseTemplate -> ChooseTemplateSheet(ui, sh, a) { sheet = null }
            SessionSheet.EditNote -> EditNoteSheet(ui, a) { sheet = null }
            is SessionSheet.FactSheet -> FactSheet(ui, sh, a) { sheet = null }
            SessionSheet.Dropped -> DroppedSheet(ui) { sheet = null }
            SessionSheet.Review -> ReviewSheet(ui, a, { sheet = null }) { sheet = it }
            SessionSheet.Speakers -> SpeakersSheet(ui, a.review.onFindSpeakers, a.review.onRenameSpeaker, a.review.onHideSpeakers) { sheet = null }
            is SessionSheet.Speaker -> SpeakerPartSheet(sh, ui, a.review.onMoveSpeaker) { sheet = null }
            is SessionSheet.Words -> WordsSheet(sh, ui, a, { sheet = null }) { sheet = it }
            SessionSheet.Export -> Sheet({ sheet = null }) {
                Eyebrow("Export"); Spacer(Modifier.height(4.dp))
                for (k in ExportKind.entries) {
                    val ok = if (k == ExportKind.AUDIO) s.hasAudio else ui.run?.status == RunStatus.DONE
                    ActionRow(if (k == ExportKind.AUDIO) Glyph.WAVE else Glyph.LIST, k.label, { sheet = SessionSheet.Confirm(k, share = false) }, k.note, enabled = ok)
                }
            }
            is SessionSheet.Confirm -> Sheet({ sheet = null }) {
                Eyebrow("Leaving Scriber"); Spacer(Modifier.height(10.dp))
                Text("${sh.kind.label}: once saved or shared, this copy is outside Scriber's encryption and lock, and its safety is up to where you put it.", style = Type.body)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("Share", { sheet = null; a.onExport(sh.kind, true) }, Modifier.weight(1f), minHeight = 52.dp)
                    AmberButton("Save to phone", { sheet = null; a.onExport(sh.kind, false) }, Modifier.weight(1f))
                }
            }
            SessionSheet.Engine -> Sheet({ sheet = null }) {
                Eyebrow("Transcribe again with"); Spacer(Modifier.height(4.dp))
                for ((engine, title) in ui.engines) ActionRow(Glyph.REDO, title, { sheet = null; a.onTranscribeAgain(engine) },
                    if (engine == "medasr") "Tuned for medical dictation" else "General English, conversation")
            }
            SessionSheet.Rename -> Sheet({ sheet = null }) {
                var text by remember { mutableStateOf(s.title) }
                Eyebrow("Name"); Spacer(Modifier.height(10.dp))
                BasicTextField(text, { text = it }, singleLine = true, textStyle = Type.body, cursorBrush = SolidColor(Ink.Amber),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(14.dp))
                Spacer(Modifier.height(10.dp))
                Text("Keep patient names out of titles if you share screenshots; titles are encrypted like everything else.", style = Type.small)
                Spacer(Modifier.height(14.dp))
                AmberButton("Save", { sheet = null; a.onRename(text) }, Modifier.fillMaxWidth())
            }
            SessionSheet.DeleteAudio -> Sheet({ sheet = null }) {
                Eyebrow("Delete the audio"); Spacer(Modifier.height(10.dp))
                Text("The recording${if (s.original != null) " and the imported original" else ""} will be deleted for good. Transcripts stay, but nothing can be transcribed again.", style = Type.body)
                Spacer(Modifier.height(16.dp))
                AmberButton("Delete audio", { sheet = null; a.onDeleteAudio() }, Modifier.fillMaxWidth())
            }
            SessionSheet.DeleteSession -> Sheet({ sheet = null }) {
                Eyebrow("Delete this session"); Spacer(Modifier.height(10.dp))
                Text("Audio and every transcript of it will be deleted for good.", style = Type.body)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Ink.Ember, Corner).clickable(role = Role.Button) { sheet = null; a.onDeleteSession() }, contentAlignment = Alignment.Center) {
                    Text("Delete for good", style = Type.button)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TranscriptView(ui: SessionUi, a: SessionActions, s: com.fadysaeed.scriber.core.Session, open: (SessionSheet) -> Unit) {
    if (ui.runs.isNotEmpty()) {
        Section("Transcripts")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in ui.runs) {
                val label = engineName(r.engine) + " · " + SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(r.startedAtMillis))
                Choice(label, r.id == ui.run?.id, { a.onSelectRun(r.id) })
            }
        }
        ui.run?.let { run ->
            Spacer(Modifier.height(12.dp))
            when (run.status) {
                RunStatus.RUNNING -> Text("Transcribing…", style = Type.note)
                RunStatus.FAILED -> Text("This transcript failed: ${run.problem}", style = Type.body.copy(color = Ink.Ember))
                RunStatus.DONE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Status(if (Seal.intact(run)) "Sealed original" else "Seal broken", if (Seal.intact(run)) Ink.Teal else Ink.Ember)
                        Spacer(Modifier.weight(1f))
                        if (ui.corrections > 0) {
                            Text(if (ui.showEdited) "Corrected" else "Original", style = Type.small.copy(color = Ink.Amber),
                                modifier = Modifier.clickable(role = Role.Switch) { a.review.onShowEdited(!ui.showEdited) }.padding(8.dp))
                        }
                        if (s.mode == SessionMode.DICTATION) {
                            Text(if (ui.readable) "Readable" else "Raw", style = Type.small.copy(color = Ink.Amber),
                                modifier = Modifier.clickable(role = Role.Switch) { a.onReadable(!ui.readable) }.padding(8.dp))
                        }
                    }
                    Text("${run.wordCount} words. Tap the time to hear a line, a word to correct it.", style = Type.small)
                    SecondOpinion(ui, a) { open(SessionSheet.Review) }
                    SpeakersSummary(ui, { open(SessionSheet.Speakers) }) { a.review.onFindSpeakers(ui.speakers?.requested ?: 2) }
                    Spacer(Modifier.height(10.dp))
                    if (run.segments.isEmpty()) Text("No speech was found in this audio.", style = Type.note)
                    Transcript(ui, a, readable = ui.readable && s.mode == SessionMode.DICTATION, open)
                }
            }
        }
    } else if (s.status == SessionStatus.READY && ui.job == null) {
        Section("Transcripts")
        Text("No transcript yet.", style = Type.note, modifier = Modifier.padding(vertical = 12.dp))
    }
}
