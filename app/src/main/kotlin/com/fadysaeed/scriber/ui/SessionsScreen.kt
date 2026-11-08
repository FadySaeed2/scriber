package com.fadysaeed.scriber.ui

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
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (mode == SessionMode.DICTATION) "Your voice only. Say \"period\", \"comma\", \"new paragraph\"; MedASR by default."
            else "A consultation between people. Parakeet by default; speaker turns come in the next update.",
            style = Type.small,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmberButton("Record", onRecord, Modifier.weight(1f), enabled = ui.speechReady)
            GhostButton("Import audio", onImport, Modifier.weight(1f), enabled = ui.speechReady, minHeight = 52.dp)
        }
        if (!ui.speechReady) { Spacer(Modifier.height(8.dp)); Text("Download the models first, in Models.", style = Type.small.copy(color = Ink.Amber)) }
    }

    Section(if (ui.rows.isEmpty()) "Nothing yet" else "${ui.rows.size} session${if (ui.rows.size == 1) "" else "s"}")
    for (row in ui.rows) SessionLine(row) { onOpen(row.session.id) }
    for (p in ui.problems) Text("Could not read $p", style = Type.small.copy(color = Ink.Ember))
}

@Composable
private fun SessionLine(row: SessionRow, onClick: () -> Unit) {
    val s = row.session
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(s.title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(
                    listOfNotNull(day(s.createdAtMillis), if (s.durationMs > 0) clock(s.durationMs) else null,
                        if (s.mode == SessionMode.DICTATION) "dictation" else "conversation",
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
