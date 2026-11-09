package com.fadysaeed.scriber.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.SessionRow
import com.fadysaeed.scriber.SessionsUi
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.SessionSource
import com.fadysaeed.scriber.core.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

private fun day(ms: Long) = SimpleDateFormat("EEE d MMM, HH:mm", Locale.ENGLISH).format(Date(ms))

@Composable
fun SessionsScreen(
    ui: SessionsUi,
    mode: SessionMode,
    onMode: (SessionMode) -> Unit,
    onRecord: () -> Unit,
    onImport: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpen: (String) -> Unit,
    onDeleteSessions: (List<String>) -> Unit = {},
    onDeleteAudio: (List<String>) -> Unit = {},
    selectedOverride: Set<String> = emptySet(),
    folderOverride: SessionMode? = null,
) {
    Heading("Scriber", "Sessions", "Recorded or imported, transcribed on this phone, never sent anywhere.")

    if (ui.recording.active) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenRecording).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(Ink.Ember, 9.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (ui.recording.paused) "Paused" else "Recording now", style = Type.title)
                Text(clock(ui.recording.recordedMs), style = Type.figure)
            }
            Text("Open", style = Type.body.copy(color = Ink.Amber))
        }
        Hairline()
    } else {
        Eyebrow("New")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Dictation", mode == SessionMode.DICTATION, { onMode(SessionMode.DICTATION) }, Modifier.weight(1f))
            Choice("Conversation", mode == SessionMode.CONVERSATION, { onMode(SessionMode.CONVERSATION) }, Modifier.weight(1f))
            Choice("Meeting", mode == SessionMode.MEETING, { onMode(SessionMode.MEETING) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (mode) {
                SessionMode.DICTATION -> "Your voice only. Say \"period\", \"comma\", \"new paragraph\"."
                SessionMode.CONVERSATION -> "A consultation: speakers found, medicine names checked by MedASR."
                SessionMode.MEETING -> "Several people, up to two hours: Scriber finds how many are speaking."
            },
            style = Type.small,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmberButton("Record", onRecord, Modifier.weight(1f), enabled = ui.speechReady)
            GhostButton("Import audio", onImport, Modifier.weight(1f), enabled = ui.speechReady, minHeight = 52.dp)
        }
        if (!ui.speechReady) { Spacer(Modifier.height(8.dp)); Text("Download the models first, in Models.", style = Type.small.copy(color = Ink.Amber)) }
    }

    Library(ui, onOpen, onDeleteSessions, onDeleteAudio, selectedOverride, folderOverride)
    for (p in ui.problems) Text("Could not read $p", style = Type.small.copy(color = Ink.Ember))
}

/** Which date heading a session sits under: Today, Yesterday, This week, then month by month. */
fun dateGroup(ms: Long, now: Long = System.currentTimeMillis()): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    val today = cal.timeInMillis
    return when {
        ms >= today -> "Today"
        ms >= today - 86_400_000L -> "Yesterday"
        ms >= today - 6 * 86_400_000L -> "This week"
        else -> SimpleDateFormat("MMMM yyyy", Locale.ENGLISH).format(Date(ms))
    }
}

/**
 * The sessions as a small library: a folder for each kind, search through titles and words, date
 * headings, and a long press to pick several and delete them, or only their audio.
 */
@Composable
private fun Library(ui: SessionsUi, onOpen: (String) -> Unit, onDeleteSessions: (List<String>) -> Unit, onDeleteAudio: (List<String>) -> Unit,
                    selectedOverride: Set<String>, folderOverride: SessionMode?) {
    var folder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(folderOverride?.name) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(selectedOverride) }
    var sure by remember { mutableStateOf<String?>(null) }
    if (ui.rows.isEmpty()) { Section("Nothing yet"); return }

    Section("Library")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("All ${ui.rows.size}", folder == null, { folder = null })
        for (m in listOf(SessionMode.CONVERSATION, SessionMode.DICTATION, SessionMode.MEETING)) {
            val n = ui.rows.count { it.session.mode == m }
            if (n > 0) Choice("${modeName(m)}s $n", folder == m.name, { folder = m.name })
        }
    }
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (query.isEmpty()) Text("Search titles and words", style = Type.body.copy(color = Ink.Ash))
        BasicTextField(query, { query = it }, singleLine = true, textStyle = Type.body, cursorBrush = SolidColor(Ink.Amber), modifier = Modifier.fillMaxWidth())
    }

    val q = query.trim()
    val shown = ui.rows.filter { r ->
        (folder == null || r.session.mode.name == folder) &&
            (q.length < 2 || r.session.title.contains(q, true) || r.latest?.text?.contains(q, true) == true)
    }
    if (selected.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size} selected", style = Type.title, modifier = Modifier.weight(1f))
            Text("Cancel", style = Type.small.copy(color = Ink.Amber), modifier = Modifier.clickable(role = Role.Button) { selected = emptySet(); sure = null }.padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(if (sure == "audio") "Tap again" else "Delete audio", {
                if (sure == "audio") { onDeleteAudio(selected.toList()); selected = emptySet(); sure = null } else sure = "audio"
            }, Modifier.weight(1f), color = Ink.Amber, minHeight = 52.dp)
            GhostButton(if (sure == "all") "Tap again" else "Delete", {
                if (sure == "all") { onDeleteSessions(selected.toList()); selected = emptySet(); sure = null } else sure = "all"
            }, Modifier.weight(1f), color = Ink.Ember, minHeight = 52.dp)
        }
        Text(when (sure) { "audio" -> "Deletes their audio; transcripts stay."; "all" -> "Deletes the sessions, audio and transcripts, for good."; else -> "Long press picks more; tap one to add or remove it." },
            style = Type.small, modifier = Modifier.padding(top = 6.dp))
    }
    if (shown.isEmpty()) { Text("Nothing here.", style = Type.note, modifier = Modifier.padding(vertical = 16.dp)); return }
    var last: String? = null
    for (row in shown) {
        val g = dateGroup(row.session.createdAtMillis)
        if (g != last) { Spacer(Modifier.height(18.dp)); Eyebrow(g); Spacer(Modifier.height(4.dp)); Hairline(); last = g }
        val id = row.session.id
        SessionLine(row, id in selected, selected.isNotEmpty(),
            onClick = { if (selected.isNotEmpty()) { selected = if (id in selected) selected - id else selected + id; sure = null } else onOpen(id) },
            onLongClick = { selected = selected + id; sure = null })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionLine(row: SessionRow, picked: Boolean, picking: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val s = row.session
    Column(Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
        .then(if (picked) Modifier.background(Ink.Field, Corner) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            if (picking) { GlyphIcon(Glyph.CHECK, if (picked) Ink.Amber else Ink.Hairline); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                Text(s.title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(day(s.createdAtMillis), if (s.durationMs > 0) clock(s.durationMs) else null,
                        modeName(s.mode).lowercase(),
                        if (s.source == SessionSource.IMPORTED) "imported" else null).joinToString(" · "),
                    style = Type.small,
                )
                row.latest?.takeIf { it.status == RunStatus.DONE }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(runCatching { com.fadysaeed.scriber.core.TranscriptFormats.readable(it.text, row.session.mode) }.getOrDefault(it.text).replace(Regex("\\s+"), " "), style = Type.note, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            val job = row.job
            when {
                job != null -> Status("${job.label} ${(job.fraction * 100).toInt()}%", Ink.Teal)
                s.status == SessionStatus.RECORDING -> Status("Recording", Ink.Ember)
                s.status == SessionStatus.FINALIZING -> Status("Saving", Ink.Teal)
                s.status == SessionStatus.FAILED -> Status("Problem", Ink.Ember)
                row.latest?.status == RunStatus.FAILED -> Status("Transcript failed", Ink.Ember)
                row.latest == null -> Status("No transcript", Ink.Ash)
                s.recovered -> Status("Rescued", Ink.Amber)
                else -> Status("Ready", Ink.Teal)
            }
        }
        Hairline()
    }
}
