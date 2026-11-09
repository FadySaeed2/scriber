package com.fadysaeed.scriber.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.fadysaeed.scriber.core.Prompts
import com.fadysaeed.scriber.core.PromptTemplate
import com.fadysaeed.scriber.core.SessionMode

/**
 * Copy or share the clean transcript: on its own, or under a prompt that asks a cloud model for
 * the document. The prompt this kind of session last used is offered first.
 */
@Composable
fun CopySheet(ui: SessionUi, a: CopyActions, close: () -> Unit) {
    val s = ui.session ?: return
    var choosing by remember { mutableStateOf(false) }
    var extra by remember(s.id) { mutableStateOf("") }
    val prompt = ui.prompts.firstOrNull { it.id == ui.promptId } ?: ui.prompts.firstOrNull()
    Sheet(close) {
        Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
            Eyebrow("Copy for AI")
            Spacer(Modifier.height(8.dp))
            Text("The transcript cleaned: medicine names from the second opinion, no ums or repeated words, one paragraph per speaker.", style = Type.small)
            if (!ui.copyNoticeSeen) {
                Spacer(Modifier.height(10.dp))
                Text("Once copied, the text is outside Scriber's encryption, and any names said in the session stay in it. Pasting it into a cloud model is subject to your workplace's privacy rules.",
                    style = Type.note.copy(color = Ink.Amber))
            }
            Spacer(Modifier.height(16.dp))
            if (prompt != null) {
                Eyebrow("With the prompt")
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { choosing = !choosing }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(prompt.name, style = Type.title, modifier = Modifier.weight(1f))
                    Text(if (choosing) "Close" else "Change", style = Type.small.copy(color = Ink.Amber))
                }
                if (choosing) {
                    for (p in ui.prompts) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { a.onChoosePrompt(p.id); choosing = false }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (p.id == prompt.id) Ink.Amber else Ink.Rule, 9.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(p.name, style = Type.body.copy(color = if (p.id == prompt.id) Ink.Bone else Ink.Sand))
                        }
                    }
                    ActionRow(Glyph.RENAME, "Change the prompts", { close(); a.onPrompts() })
                }
                Box(Modifier.fillMaxWidth().background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(14.dp)) {
                    if (extra.isEmpty()) Text("Add a line for this one (optional): female, on a Form 6, write it as a letter to her GP…", style = Type.body.copy(color = Ink.Ash))
                    BasicTextField(extra, { extra = it }, textStyle = Type.body, cursorBrush = SolidColor(Ink.Amber), modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AmberButton("Copy with prompt", { close(); a.onCopy(prompt.id, false, extra) }, Modifier.weight(1.4f))
                    GhostButton("Share", { close(); a.onCopy(prompt.id, true, extra) }, Modifier.weight(1f), minHeight = 52.dp)
                }
                Spacer(Modifier.height(18.dp))
            }
            Eyebrow("Transcript only")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Copy transcript", { close(); a.onCopy(null, false, null) }, Modifier.weight(1.4f), minHeight = 52.dp)
                GhostButton("Share", { close(); a.onCopy(null, true, null) }, Modifier.weight(1f), minHeight = 52.dp)
            }
            Spacer(Modifier.height(8.dp))
            Toggle("Times", "The time at the start of each paragraph", ui.copyTimes, a.onTimes)
            if (s.mode != SessionMode.DICTATION && ui.speakers == null) Text("No speakers yet: the transcript is copied without names.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

class PromptActions(
    val onBack: () -> Unit = {},
    val onSave: (PromptTemplate) -> Unit = {},
    /** A built-in prompt goes back to how it came; one of Fady's is deleted. */
    val onRemove: (String) -> Unit = {},
    /** A new prompt, empty or a copy; returns its id. */
    val onNew: (PromptTemplate?) -> String = { "" },
    /** Make [id] the prompt [mode] sessions copy with. */
    val onDefault: (SessionMode, String) -> Unit = { _, _ -> },
)

/** The prompts copied above a transcript: the list, then one prompt to change. */
@Composable
fun PromptsScreen(prompts: List<PromptTemplate>, defaults: Map<SessionMode, String>, a: PromptActions, openOverride: String? = null) {
    var editing by remember { mutableStateOf(openOverride) }
    val p = editing?.let { id -> prompts.firstOrNull { it.id == id } }
    if (p != null) {
        PromptEditor(p, defaults, a, onOpen = { editing = it }) { editing = null }
        return
    }
    BackHandler(onBack = a.onBack)
    Page("Prompts", a.onBack) {
        Spacer(Modifier.height(14.dp))
        Text("A prompt goes above the transcript when you copy it, and tells the cloud model what to write and how. Change the ones that came with Scriber freely; you can always put them back.", style = Type.note)
        Section("Prompts")
        for (x in prompts) {
            val usedFor = defaults.filterValues { it == x.id }.keys.map(::modeName)
            ActionRow(Glyph.LIST, x.name, { editing = x.id },
                (if (x.builtIn) "Came with Scriber" else "Yours") + if (usedFor.isNotEmpty()) " · for ${usedFor.joinToString(", ").lowercase()}" else "",
                color = if (usedFor.isNotEmpty()) Ink.Amber else Ink.Bone)
        }
        ActionRow(Glyph.PLUS, "New prompt", { editing = a.onNew(null) }, "Starts with the shared rules, ready for your task")
    }
}

@Composable
private fun PromptEditor(saved: PromptTemplate, defaults: Map<SessionMode, String>, a: PromptActions, onOpen: (String) -> Unit, close: () -> Unit) {
    var draft by remember(saved.id, saved.updatedAtMillis) { mutableStateOf(saved) }
    var confirmDelete by remember(saved.id) { mutableStateOf(false) }
    fun keep() { if (draft != saved && draft.name.isNotBlank()) a.onSave(draft) }
    val back = { keep(); close() }
    BackHandler(onBack = back)
    val original = remember(saved.id) { Prompts.builtIns().firstOrNull { it.id == saved.id } }
    Box {
        Page(draft.name.ifBlank { "Prompt" }, back, footer = {
            AmberButton("Save", { keep(); close() }, Modifier.fillMaxWidth(), enabled = draft != saved && draft.name.isNotBlank())
        }) {
            Spacer(Modifier.height(14.dp))
            Eyebrow("Name")
            Spacer(Modifier.height(8.dp))
            Field(draft.name, { draft = draft.copy(name = it) }, single = true)
            Spacer(Modifier.height(16.dp))
            Eyebrow("Used first for")
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(SessionMode.CONVERSATION, SessionMode.MEETING, SessionMode.DICTATION)) {
                    Choice(modeName(m), defaults[m] == saved.id, { keep(); a.onDefault(m, saved.id) }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            Eyebrow("The prompt")
            Spacer(Modifier.height(8.dp))
            Field(draft.text, { draft = draft.copy(text = it) }, minHeight = 360)
            Text("The transcript goes below it, after a line that says TRANSCRIPT.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
            Section("This prompt")
            ActionRow(Glyph.COPY, "Duplicate", { keep(); onOpen(a.onNew(draft)) }, "A copy of yours to change")
            if (original != null) ActionRow(Glyph.REDO, "Back to how it came", { a.onRemove(saved.id); draft = original }, enabled = draft != original || saved != original)
            else ActionRow(Glyph.TRASH, "Delete this prompt", { confirmDelete = true }, color = Ink.Ember)
        }
        if (confirmDelete) Sheet({ confirmDelete = false }) {
            Eyebrow("Delete ${saved.name}")
            Spacer(Modifier.height(16.dp))
            AmberButton("Delete", { confirmDelete = false; a.onRemove(saved.id); close() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Field(text: String, onChange: (String) -> Unit, minHeight: Int = 52, single: Boolean = false) {
    BasicTextField(text, onChange, singleLine = single, textStyle = Type.body, cursorBrush = SolidColor(Ink.Amber),
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp).background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(14.dp))
}
