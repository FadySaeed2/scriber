package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.LabUi
import com.fadysaeed.scriber.core.Units
import com.fadysaeed.scriber.lab.AsrRun
import com.fadysaeed.scriber.lab.LabReport
import java.util.Locale

private fun pct(d: Double) = String.format(Locale.ROOT, "%.1f%%", d * 100)
private fun one(d: Double) = String.format(Locale.ROOT, "%.1f", d)

@Composable
fun LabScreen(ui: LabUi, onRun: () -> Unit, onCopy: (String) -> Unit) {
    val s = LocalSignals.current
    Column {
        ScreenTitle("Lab", "Measures every model on this phone with public test recordings only. No patient audio is ever used here.")

        Panel(accent = if (ui.step != null) s.busy else null) {
            if (ui.step != null) {
                Chip("Running", s.busy)
                Text(ui.step, style = MaterialTheme.typography.titleMedium)
                Text("Takes a minute or two. The language model loads last, after the speech models are closed.", style = MaterialTheme.typography.bodySmall, color = s.muted)
            } else {
                Text("Run every check", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ui.modelsReady) "Encryption, both speech engines on six medical dictations, voice activity, speaker turns, and the note writer."
                    else "Download the models first; checks for anything missing are skipped.",
                    style = MaterialTheme.typography.bodyMedium, color = s.muted,
                )
                Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("Run checks") }
            }
        }

        ui.report?.let { r -> Results(r, onCopy) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Results(r: LabReport, onCopy: (String) -> Unit) {
    val s = LocalSignals.current
    SectionLabel("Speech to text")
    if (r.asr.isEmpty()) Panel { Text("Not measured yet.", color = s.muted) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { for (a in r.asr) AsrPanel(a, best = r.asr.minByOrNull { it.wer } == a && r.asr.size > 1) }

    SectionLabel("Speaker turns and voice activity")
    Panel {
        r.diarization?.let { d ->
            Fact("Voices found", "${d.speakersFound} of ${d.speakersExpected}", if (d.speakersFound == d.speakersExpected) s.ready else s.warn)
            Fact("Speaker changes", "${d.turns}")
            Fact("Time", "${Units.seconds(d.processMs)} for ${one(d.audioSeconds)} s of audio")
        } ?: Text("Speaker turns not measured.", color = s.muted)
        r.vad?.let { v -> Fact("Speech segments", "${v.speechSegments} in ${one(v.audioSeconds)} s (${v.ms} ms)") }
    }

    SectionLabel("Note writer")
    Panel {
        r.llm?.let { l ->
            Fact("Model", l.model)
            Fact("Ran on", l.backend, if (l.backend == "GPU") s.ready else s.warn)
            Fact("Loading", Units.seconds(l.loadMs))
            Fact("First words", Units.seconds(l.firstWordMs))
            Fact("Speed", "${one(l.piecesPerSecond)} pieces per second")
            if (l.note.isNotBlank()) Text(l.note, style = MaterialTheme.typography.bodySmall, color = s.warn)
            Text(
                l.output.ifBlank { "(no text)" },
                style = Mono, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(s.raised).padding(12.dp),
            )
        } ?: Text("Not measured.", color = s.muted)
    }

    SectionLabel("Encryption and heat")
    Panel {
        r.vault?.let { v ->
            Fact("Vault", if (v.ok) "Read back exactly" else "FAILED", if (v.ok) s.ready else s.problem)
            Fact("Speed", "write ${one(v.writeMBps)} MB/s, read ${one(v.readMBps)} MB/s")
        }
        Fact("Heat", "${r.device.thermal} before, ${r.thermalAfter} after")
        Fact("Memory free", Units.bytes(r.device.availableRamBytes))
    }

    if (r.problems.isNotEmpty()) {
        SectionLabel("Problems")
        Panel(accent = s.problem) { for (p in r.problems) Text(p, style = MaterialTheme.typography.bodyMedium) }
    }

    Spacer(Modifier.height(14.dp))
    OutlinedButton(onClick = { onCopy(r.toText()) }, modifier = Modifier.fillMaxWidth()) { Text("Copy report") }
}

@Composable
private fun AsrPanel(a: AsrRun, best: Boolean) {
    val s = LocalSignals.current
    var open by remember { mutableStateOf(false) }
    Panel(accent = if (best) s.ready else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (best) Chip("Most accurate", s.ready)
        }
        Fact("Word errors", "${pct(a.wer)}  (${a.errors} of ${a.referenceWords})")
        Fact("Speed", "${one(a.speedFactor)}× faster than real time")
        Fact("Loading", Units.seconds(a.loadMs))
        Text(
            if (open) "Hide what it heard" else "Show what it heard",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { open = !open }.padding(vertical = 4.dp),
        )
        if (open) for (x in a.samples) {
            Column(Modifier.padding(top = 6.dp)) {
                Text("${x.file} · ${pct(x.wer)} errors · ${one(x.seconds)} s", style = MaterialTheme.typography.bodySmall, color = s.muted)
                Text(x.heard, style = Mono)
            }
        }
        Text("Spoken and written numbers, section tags and dictation commands are evened out before counting.", style = MaterialTheme.typography.bodySmall, color = s.muted)
    }
}
