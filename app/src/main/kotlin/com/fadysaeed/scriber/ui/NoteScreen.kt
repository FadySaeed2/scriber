package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.SessionUi
import com.fadysaeed.scriber.core.Fact
import com.fadysaeed.scriber.core.FactKind
import com.fadysaeed.scriber.core.NoteSentence
import com.fadysaeed.scriber.core.NoteText
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.SectionForm

/** The three views of a conversation. */
enum class SessionView(val label: String) { TRANSCRIPT("Transcript"), NOTE("Note"), FACTS("Facts") }

/** What Fady can do with the note and the facts it rests on. */
class NoteActions(
    /** Write the note with [templateId]; [rewriteOnly] keeps the saved facts and only writes again. */
    val onWrite: (templateId: String?, rewriteOnly: Boolean) -> Unit = { _, _ -> },
    /** Fady's own text of the note; null goes back to the written one. */
    val onSaveText: (String?) -> Unit = {},
    val onCopy: (String) -> Unit = {},
    val onSetRemoved: (factId: String, removed: Boolean) -> Unit = { _, _ -> },
    val onEditFact: (factId: String, text: String) -> Unit = { _, _ -> },
    val onTemplates: () -> Unit = {},
)

private fun took(ms: Long): String {
    val s = (ms / 1000).toInt()
    return if (s < 60) "${s} s" else "${s / 60} min ${s % 60} s"
}

@Composable
fun NoteField(text: String, onChange: (String) -> Unit, minHeight: Int = 52, maxHeight: Int? = null, single: Boolean = false) {
    BasicTextField(text, onChange, singleLine = single, textStyle = Type.body, cursorBrush = SolidColor(Ink.Amber),
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp, max = (maxHeight ?: 2000).dp).background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(14.dp))
}

/** The note: each sentence taps open the facts and words it came from. */
@Composable
fun NoteView(ui: SessionUi, a: SessionActions, open: (SessionSheet) -> Unit) {
    val note = ui.note
    val busy = ui.job != null
    val transcribed = ui.runs.any { it.status == RunStatus.DONE }
    Spacer(Modifier.height(16.dp))
    if (note == null) {
        Text(
            when {
                !ui.canWriteNotes -> "To write notes, download Gemma 4 E4B in Models. It runs on this phone; nothing leaves it."
                !transcribed -> "The note is written once the transcript is ready."
                busy -> "Scriber is still working on this session; the note follows."
                else -> "No note yet. Choose a template and Scriber writes it here, on this phone, from the facts it finds in the transcript."
            }, style = Type.note,
        )
        if (ui.canWriteNotes && transcribed) {
            Spacer(Modifier.height(14.dp))
            AmberButton("Write the note", { open(SessionSheet.ChooseTemplate(rewriteOnly = false)) }, Modifier.fillMaxWidth(), enabled = !busy)
        }
        return
    }

    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, enabled = !busy) { open(SessionSheet.ChooseTemplate(rewriteOnly = ui.facts != null)) }.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(note.templateName, style = Type.title)
            Text("${note.model} · ${took(note.tookMs)} · ${NoteText.words(note.text)} words", style = Type.small)
        }
        GlyphIcon(Glyph.DOWN, Ink.Dust)
    }
    Spacer(Modifier.height(10.dp))
    if (note.warnings > 0) Status("${note.warnings} ${if (note.warnings == 1) "sentence" else "sentences"} to check, marked in amber", Ink.Amber)
    else Status("Every sentence rests on facts from the transcript", Ink.Teal)
    val facts = ui.facts
    if (facts != null && facts.changedAtMillis > note.createdAtMillis) {
        Spacer(Modifier.height(12.dp))
        Text("You changed the facts after this note was written.", style = Type.note.copy(color = Ink.Amber))
        Spacer(Modifier.height(8.dp))
        GhostButton("Write it again from the facts", { a.note.onWrite(note.templateId, true) }, Modifier.fillMaxWidth(), enabled = !busy)
    }

    var showWritten by remember(note.id) { mutableStateOf(false) }
    val edited = note.editedText
    if (edited != null) {
        Spacer(Modifier.height(12.dp))
        Row {
            Status(if (showWritten) "As written" else "Your edited version", if (showWritten) Ink.Teal else Ink.Amber)
            Spacer(Modifier.weight(1f))
            Text(if (showWritten) "Show yours" else "Show as written", style = Type.small.copy(color = Ink.Amber),
                modifier = Modifier.clickable(role = Role.Switch) { showWritten = !showWritten })
        }
    }

    if (edited != null && !showWritten) {
        Spacer(Modifier.height(14.dp))
        Text(edited, style = Type.reading)
    } else {
        note.sections.forEachIndexed { si, section ->
            Spacer(Modifier.height(18.dp))
            Eyebrow(section.title)
            Spacer(Modifier.height(6.dp))
            val tap = { i: Int -> open(SessionSheet.Sources(si, i)) }
            when (section.form) {
                SectionForm.PROSE -> Text(sentences(section.sentences, tap), style = Type.reading)
                else -> section.sentences.forEachIndexed { i, x ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(if (section.form == SectionForm.NUMBERED) "${i + 1}." else "–", style = Type.reading.copy(color = Ink.Ash), modifier = Modifier.width(26.dp))
                        Text(sentences(listOf(x)) { tap(i) }, style = Type.reading, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AmberButton("Copy note", { a.note.onCopy(note.text) }, Modifier.weight(1f))
        GhostButton("Edit", { open(SessionSheet.EditNote) }, Modifier.weight(1f), minHeight = 52.dp)
    }
    Spacer(Modifier.height(8.dp))
    ActionRow(Glyph.REDO, "Write again", { open(SessionSheet.ChooseTemplate(rewriteOnly = true)) }, "From the same facts, with this or another template", enabled = !busy && facts != null)
    ActionRow(Glyph.REDO, "Start again from the transcript", { open(SessionSheet.ChooseTemplate(rewriteOnly = false)) }, "Takes the facts out again; changes you made to facts are lost", enabled = !busy && ui.canWriteNotes)
    ActionRow(Glyph.LIST, "Note templates", a.note.onTemplates, "Change them or make your own")
}

private fun sentences(list: List<NoteSentence>, onTap: (Int) -> Unit) = buildAnnotatedString {
    list.forEachIndexed { i, x ->
        if (i > 0) append(" ")
        val style = if (x.warning != null) SpanStyle(color = Ink.Amber, textDecoration = TextDecoration.Underline) else SpanStyle(color = Ink.Sand)
        withLink(LinkAnnotation.Clickable("s$i", TextLinkStyles(style = style)) { onTap(i) }) { append(x.text) }
    }
}

/** Where a sentence came from: its warning, its facts, and the words of each, to hear. */
@Composable
fun SourcesSheet(ui: SessionUi, sh: SessionSheet.Sources, a: SessionActions, close: () -> Unit) {
    val section = ui.note?.sections?.getOrNull(sh.section) ?: return
    val sentence = section.sentences.getOrNull(sh.sentence) ?: return
    val byId = ui.facts?.facts?.associateBy { it.id }.orEmpty()
    Sheet(close) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Eyebrow(section.title)
            Spacer(Modifier.height(8.dp))
            Text(sentence.text, style = Type.reading.copy(color = Ink.Bone))
            sentence.warning?.let { Spacer(Modifier.height(8.dp)); Text(it + ". Check it against the transcript before you use it.", style = Type.note.copy(color = Ink.Amber)) }
            for (id in sentence.factIds) {
                val f = byId[id] ?: continue
                Spacer(Modifier.height(14.dp)); Hairline(); Spacer(Modifier.height(10.dp))
                FactCard(f, ui.session?.hasAudio == true) { a.review.onPlayRange(f.startMs, f.endMs) }
            }
            if (sentence.factIds.isEmpty()) { Spacer(Modifier.height(10.dp)); Text("The writer cited no fact for this sentence.", style = Type.note) }
        }
    }
}

@Composable
private fun FactCard(f: Fact, canPlay: Boolean, onPlay: () -> Unit) {
    Eyebrow(f.kind.label + if (f.edited) " · edited by you" else "")
    Spacer(Modifier.height(4.dp))
    Text(f.text, style = Type.body)
    Spacer(Modifier.height(4.dp))
    Text("“${f.quote}”", style = Type.note)
    Spacer(Modifier.height(6.dp))
    Row {
        Text("Line ${f.line} · ${clock(f.startMs)}", style = Type.small, modifier = Modifier.weight(1f).padding(top = 14.dp))
        GhostButton("Play", onPlay, enabled = canPlay)
    }
}

/** Pick the template to write with. */
@Composable
fun ChooseTemplateSheet(ui: SessionUi, sh: SessionSheet.ChooseTemplate, a: SessionActions, close: () -> Unit) {
    Sheet(close) {
        Eyebrow(if (sh.rewriteOnly) "Write again from the facts, with" else "Write the note with")
        Spacer(Modifier.height(4.dp))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            for (t in ui.templates) ActionRow(Glyph.LIST, t.name, { close(); a.note.onWrite(t.id, sh.rewriteOnly) },
                "${t.sections.size} sections" + if (t.id == ui.note?.templateId) " · this note" else "")
            ActionRow(Glyph.RENAME, "Change templates", { close(); a.note.onTemplates() })
        }
    }
}

@Composable
fun EditNoteSheet(ui: SessionUi, a: SessionActions, close: () -> Unit) {
    val note = ui.note ?: return
    var text by remember(note.id) { mutableStateOf(note.text) }
    Sheet(close) {
        Eyebrow("Your version of the note")
        Spacer(Modifier.height(10.dp))
        NoteField(text, { text = it }, minHeight = 240, maxHeight = 460)
        Spacer(Modifier.height(8.dp))
        Text("The note as written stays unchanged beside your version, with its sources.", style = Type.small)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (note.editedText != null) GhostButton("Back to as written", { close(); a.note.onSaveText(null) }, Modifier.weight(1f), minHeight = 52.dp)
            AmberButton("Save", { close(); a.note.onSaveText(text) }, Modifier.weight(1f))
        }
    }
}

/** Every fact, grouped by kind, each with the words it came from. */
@Composable
fun FactsView(ui: SessionUi, a: SessionActions, open: (SessionSheet) -> Unit) {
    Spacer(Modifier.height(16.dp))
    val set = ui.facts
    if (set == null) {
        Text("No facts yet. Scriber takes them out of the transcript when it writes the note.", style = Type.note)
        return
    }
    Text("${set.active.size} facts, each with the words it came from. Tap one to hear it, change it or leave it out of the note.", style = Type.note)
    val note = ui.note
    if (note != null && set.changedAtMillis > note.createdAtMillis) {
        Spacer(Modifier.height(12.dp))
        GhostButton("Write the note again from these facts", { a.note.onWrite(note.templateId, true) }, Modifier.fillMaxWidth(), color = Ink.Amber, enabled = ui.job == null)
    }
    if (set.rejected.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("${set.rejected.size} more dropped: their words, numbers or medicines were not in the transcript. See them",
            style = Type.small.copy(color = Ink.Amber), modifier = Modifier.clickable(role = Role.Button) { open(SessionSheet.Dropped) }.padding(vertical = 6.dp))
    }
    for (kind in FactKind.entries) {
        val group = set.facts.filter { it.kind == kind && !it.removed }
        if (group.isEmpty()) continue
        Section(kind.label)
        for (f in group) FactRow(f) { open(SessionSheet.FactSheet(f.id)) }
    }
    val removed = set.facts.filter { it.removed }
    if (removed.isNotEmpty()) {
        Section("Left out of the note")
        for (f in removed) FactRow(f) { open(SessionSheet.FactSheet(f.id)) }
    }
}

@Composable
private fun FactRow(f: Fact, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp)) {
        Text(f.text, style = Type.body.copy(color = if (f.removed) Ink.Slate else Ink.Bone,
            textDecoration = if (f.removed) TextDecoration.LineThrough else null))
        Spacer(Modifier.height(3.dp))
        Text("${clock(f.startMs)}  “${f.quote}”" + if (f.edited) "  · edited" else "", style = Type.small, maxLines = 2)
    }
    Hairline()
}

@Composable
fun FactSheet(ui: SessionUi, sh: SessionSheet.FactSheet, a: SessionActions, close: () -> Unit) {
    val f = ui.facts?.facts?.firstOrNull { it.id == sh.factId } ?: return
    var text by remember(f.id) { mutableStateOf(f.text) }
    Sheet(close) {
        Eyebrow(f.kind.label + " · line ${f.line} · ${clock(f.startMs)}")
        Spacer(Modifier.height(10.dp))
        NoteField(text, { text = it }, minHeight = 80, maxHeight = 220)
        Spacer(Modifier.height(8.dp))
        Text("Said: “${f.quote}”", style = Type.note)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Play", { a.review.onPlayRange(f.startMs, f.endMs) }, Modifier.weight(1f), enabled = ui.session?.hasAudio == true, minHeight = 52.dp)
            GhostButton(if (f.removed) "Put back" else "Leave out", { close(); a.note.onSetRemoved(f.id, !f.removed) }, Modifier.weight(1f),
                color = if (f.removed) Ink.Teal else Ink.Ember, minHeight = 52.dp)
        }
        Spacer(Modifier.height(10.dp))
        AmberButton("Save", { close(); a.note.onEditFact(f.id, text) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() && text.trim() != f.text)
    }
}

@Composable
fun DroppedSheet(ui: SessionUi, close: () -> Unit) {
    val list = ui.facts?.rejected.orEmpty()
    Sheet(close) {
        Eyebrow("Dropped, with the reason")
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            for (r in list) {
                Text(r.raw, style = Type.mono)
                Text(r.reason, style = Type.small.copy(color = Ink.Amber))
                Spacer(Modifier.height(8.dp)); Hairline(); Spacer(Modifier.height(8.dp))
            }
        }
    }
}
