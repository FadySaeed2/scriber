package com.fadysaeed.scriber.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.core.FactKind
import com.fadysaeed.scriber.core.Ids
import com.fadysaeed.scriber.core.NoteSection
import com.fadysaeed.scriber.core.NoteTemplate
import com.fadysaeed.scriber.core.SectionForm
import com.fadysaeed.scriber.core.Templates

class TemplateActions(
    val onBack: () -> Unit = {},
    val onSave: (NoteTemplate) -> Unit = {},
    /** A built-in goes back to how it came; one of Fady's is deleted. */
    val onRemove: (String) -> Unit = {},
    /** A new template, empty or a copy; returns its id. */
    val onNew: (NoteTemplate?) -> String = { "" },
    val onDefault: (String) -> Unit = {},
    val onCopyText: (NoteTemplate) -> Unit = {},
    /** Reads a template from the clipboard. */
    val onPaste: () -> Unit = {},
)

private fun formLabel(f: SectionForm) = when (f) { SectionForm.PROSE -> "Prose"; SectionForm.BULLETS -> "Points"; SectionForm.NUMBERED -> "Numbered" }

private fun sectionNote(s: NoteSection): String = buildString {
    append(formLabel(s.form)).append(" · up to ").append(s.maxWords).append(" words")
    if (!s.omitWhenEmpty) append(" · always shown")
    if (s.allFacts) append(" · every fact")
    if (s.kinds.isEmpty() && !s.allFacts) append(" · no facts chosen")
}

/** Note templates: the list, then one template, then one section, each a page of its own. */
@Composable
fun TemplatesScreen(templates: List<NoteTemplate>, defaultId: String, a: TemplateActions, openOverride: String? = null, sectionOverride: Int? = null) {
    var editing by remember { mutableStateOf(openOverride) }
    val t = editing?.let { id -> templates.firstOrNull { it.id == id } }
    if (t != null) {
        TemplateEditor(t, t.id == defaultId, a, sectionOverride, onOpen = { editing = it }) { editing = null }
        return
    }
    BackHandler(onBack = a.onBack)
    Page("Note templates", a.onBack) {
        Spacer(Modifier.height(14.dp))
        Text("Each template is the note's sections in order. For each section you say what it holds, how long it is, and which kinds of fact it draws on. Change the built-in ones freely; you can always put them back.", style = Type.note)
        Section("Templates")
        for (x in templates) {
            ActionRow(Glyph.LIST, x.name, { editing = x.id },
                "${x.sections.size} sections · " + (if (x.builtIn) "built in" else "yours") + if (x.id == defaultId) " · used for new conversations" else "",
                color = if (x.id == defaultId) Ink.Amber else Ink.Bone)
        }
        ActionRow(Glyph.PLUS, "New template", { editing = a.onNew(null) }, "Starts with the usual sections, ready to change")
        ActionRow(Glyph.IMPORT, "Paste a template", a.onPaste, "One copied as text from Scriber on another phone")
    }
}

@Composable
private fun TemplateEditor(saved: NoteTemplate, isDefault: Boolean, a: TemplateActions, sectionOverride: Int?, onOpen: (String) -> Unit, close: () -> Unit) {
    var draft by remember(saved.id, saved.updatedAtMillis) { mutableStateOf(saved) }
    var section by remember(saved.id) { mutableStateOf(sectionOverride) }
    var confirmDelete by remember(saved.id) { mutableStateOf(false) }
    fun keep() { if (draft != saved) a.onSave(draft) }
    val back = { keep(); close() }

    section?.let { i ->
        val s = draft.sections.getOrNull(i)
        if (s != null) {
            SectionEditor(s, i, draft.sections.size, onChange = { n -> draft = draft.copy(sections = draft.sections.toMutableList().also { it[i] = n }) },
                onRemove = { draft = draft.copy(sections = draft.sections.toMutableList().also { it.removeAt(i) }); section = null }) { section = null }
            return
        }
    }
    BackHandler(onBack = back)
    val original = remember(saved.id) { Templates.builtIns().firstOrNull { it.id == saved.id } }
    Box {
        Page(draft.name.ifBlank { "Template" }, back, footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton(if (isDefault) "Used for new conversations" else "Use for new conversations", { keep(); a.onDefault(saved.id) }, Modifier.weight(1f), enabled = !isDefault, minHeight = 52.dp)
                AmberButton("Save", { keep(); close() }, Modifier.weight(1f), enabled = draft != saved && draft.name.isNotBlank())
            }
        }) {
            Spacer(Modifier.height(14.dp))
            Eyebrow("Name")
            Spacer(Modifier.height(8.dp))
            NoteField(draft.name, { draft = draft.copy(name = it) }, single = true)

            Section("Sections, in order")
            draft.sections.forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button) { section = i }.padding(vertical = 10.dp)) {
                        Text(s.title.ifBlank { "Untitled" }, style = Type.body)
                        Text(sectionNote(s), style = Type.small.copy(color = if (s.kinds.isEmpty() && !s.allFacts) Ink.Amber else Ink.Dust))
                    }
                    IconButton(Glyph.UP, "Move up", { draft = draft.copy(sections = draft.sections.swap(i, i - 1)) }, color = if (i > 0) Ink.Dust else Ink.Hairline, size = 40.dp)
                    IconButton(Glyph.DOWN, "Move down", { draft = draft.copy(sections = draft.sections.swap(i, i + 1)) }, color = if (i < draft.sections.size - 1) Ink.Dust else Ink.Hairline, size = 40.dp)
                }
                Hairline()
            }
            ActionRow(Glyph.PLUS, "Add a section", {
                draft = draft.copy(sections = draft.sections + NoteSection("s" + Ids.new().take(8), "New section", "", emptyList()))
                section = draft.sections.size - 1
            })

            Section("Writing style")
            Text("How every section is written: voice, tone, what to avoid.", style = Type.small, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            NoteField(draft.style, { draft = draft.copy(style = it) }, minHeight = 120, maxHeight = 320)
            if (draft.style != Templates.STYLE) {
                Text("Use the usual style", style = Type.small.copy(color = Ink.Amber),
                    modifier = Modifier.clickable(role = Role.Button) { draft = draft.copy(style = Templates.STYLE) }.padding(vertical = 10.dp))
            }

            Section("This template")
            ActionRow(Glyph.COPY, "Duplicate", { keep(); onOpen(a.onNew(draft)) }, "A copy of yours to change")
            ActionRow(Glyph.SHARE, "Copy as text", { a.onCopyText(draft) }, "To paste into Scriber on another phone; it holds no patient data")
            if (original != null) ActionRow(Glyph.REDO, "Back to how it came", { a.onRemove(saved.id); draft = original }, enabled = draft != original && saved != original)
            else ActionRow(Glyph.TRASH, "Delete this template", { confirmDelete = true }, color = Ink.Ember)
        }
        if (confirmDelete) Sheet({ confirmDelete = false }) {
            Eyebrow("Delete ${saved.name}")
            Spacer(Modifier.height(10.dp))
            Text("Notes already written with it stay as they are.", style = Type.body)
            Spacer(Modifier.height(16.dp))
            AmberButton("Delete", { confirmDelete = false; a.onRemove(saved.id); close() }, Modifier.fillMaxWidth())
        }
    }
}

private fun <T> List<T>.swap(i: Int, j: Int): List<T> {
    if (j !in indices || i !in indices) return this
    return toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x }
}

@Composable
private fun SectionEditor(s: NoteSection, index: Int, count: Int, onChange: (NoteSection) -> Unit, onRemove: () -> Unit, close: () -> Unit) {
    BackHandler(onBack = close)
    Page(s.title.ifBlank { "Section" }, close) {
        Spacer(Modifier.height(14.dp))
        Text("Section ${index + 1} of $count", style = Type.figure)
        Spacer(Modifier.height(14.dp))
        Eyebrow("Title")
        Spacer(Modifier.height(8.dp))
        NoteField(s.title, { onChange(s.copy(title = it)) }, single = true)
        Spacer(Modifier.height(16.dp))
        Eyebrow("What it holds")
        Spacer(Modifier.height(8.dp))
        NoteField(s.instruction, { onChange(s.copy(instruction = it)) }, minHeight = 110, maxHeight = 300)
        Text("Plain words, as you would brief a registrar: what goes in, in what order, what to leave out.", style = Type.small, modifier = Modifier.padding(top = 6.dp))

        Section("Form")
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in SectionForm.entries) Choice(formLabel(f), s.form == f, { onChange(s.copy(form = f)) }, Modifier.weight(1f))
        }
        Text("Longest it may be, in words", style = Type.note, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in (listOf(60, 120, 200, 300, 450) + s.maxWords).distinct().sorted()) Choice("$n", s.maxWords == n, { onChange(s.copy(maxWords = n)) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Toggle("Leave out when nothing was said", "Off: the section stays, saying \"Not assessed.\"", s.omitWhenEmpty) { onChange(s.copy(omitWhenEmpty = it)) }
        Toggle("May use every fact", "Its own kinds first, then all the rest, as for a summary or a formulation", s.allFacts) { onChange(s.copy(allFacts = it)) }

        Section("Facts it draws on")
        if (s.kinds.isEmpty() && !s.allFacts) Text("Choose at least one kind, or this section stays empty.", style = Type.small.copy(color = Ink.Amber), modifier = Modifier.padding(top = 8.dp))
        for (k in FactKind.entries) {
            val on = k in s.kinds
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Checkbox) {
                    onChange(s.copy(kinds = if (on) s.kinds - k else FactKind.entries.filter { it in s.kinds || it == k }))
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.label, style = Type.body.copy(color = if (on) Ink.Bone else Ink.Dust))
                        Text(k.hint, style = Type.small, maxLines = 2)
                    }
                    Spacer(Modifier.width(10.dp))
                    GlyphIcon(Glyph.CHECK, if (on) Ink.Amber else Ink.Hairline)
                }
                Hairline()
            }
        }
        Spacer(Modifier.height(10.dp))
        ActionRow(Glyph.TRASH, "Remove this section", onRemove, color = Ink.Ember)
    }
}
