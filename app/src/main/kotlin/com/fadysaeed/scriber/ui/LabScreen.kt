package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    Heading("Scriber", "Lab", "Measures every model on this phone with public test recordings only. No patient audio is used here.")
    if (ui.step != null) {
        Status("Running", Ink.Teal); Spacer(Modifier.height(8.dp))
        Text(ui.step, style = Type.title)
        Text("The language model loads last, after the speech models are closed.", style = Type.small)
    } else {
        Text(if (ui.modelsReady) "Encryption, both speech engines on six medical dictations, voice activity, speaker turns, and the note writer."
            else "Download the models first; checks for anything missing are skipped.", style = Type.body)
        Spacer(Modifier.height(14.dp))
        AmberButton("Run checks", onRun, Modifier.fillMaxWidth())
    }
    ui.report?.let { Results(it, onCopy) }
}

@Composable
private fun Results(r: LabReport, onCopy: (String) -> Unit) {
    Section("Speech to text")
    if (r.asr.isEmpty()) Text("Not measured.", style = Type.note, modifier = Modifier.padding(vertical = 10.dp))
    val best = r.asr.minByOrNull { it.wer }
    for (a in r.asr) AsrBlock(a, a == best && r.asr.size > 1)

    Section("Voice activity and speaker turns")
    r.vad?.let { v -> Fact("Speech segments", "${v.speechSegments} in ${one(v.audioSeconds)} s") }
    r.diarization?.let { d ->
        Fact("Voices found", "${d.speakersFound} of ${d.speakersExpected}", if (d.speakersFound == d.speakersExpected) Ink.Teal else Ink.Amber)
        Fact("Speaker changes", "${d.turns} (1 expected)", if (d.turns == 1) Ink.Teal else Ink.Amber)
        Fact("Time", "${Units.seconds(d.processMs)} for ${one(d.audioSeconds)} s")
    }

    Section("Note writer")
    r.llm?.let { l ->
        Fact("Model", l.model)
        Fact("Ran on", l.backend, if (l.backend == "GPU") Ink.Teal else Ink.Amber)
        Fact("Loading", Units.seconds(l.loadMs))
        Fact("First words", Units.seconds(l.firstWordMs))
        Fact("Speed", "${one(l.piecesPerSecond)} pieces per second")
        if (l.note.isNotBlank()) Text(l.note, style = Type.small.copy(color = Ink.Amber))
        Spacer(Modifier.height(12.dp))
        Text(l.output.ifBlank { "(no text)" }, style = Type.mono, modifier = Modifier.fillMaxWidth().background(Ink.Field, Corner).padding(14.dp))
    } ?: Text("Not measured.", style = Type.note, modifier = Modifier.padding(vertical = 10.dp))

    Section("Encryption and heat")
    r.vault?.let { v ->
        Fact("Vault", if (v.ok) "Read back exactly" else "FAILED", if (v.ok) Ink.Teal else Ink.Ember)
        Fact("Speed", "write ${one(v.writeMBps)}, read ${one(v.readMBps)} MB/s")
    }
    Fact("Heat", "${r.device.thermal} → ${r.thermalAfter}")
    if (r.problems.isNotEmpty()) { Section("Problems"); for (p in r.problems) Text(p, style = Type.body.copy(color = Ink.Ember), modifier = Modifier.padding(vertical = 6.dp)) }
    Spacer(Modifier.height(18.dp))
    GhostButton("Copy report", { onCopy(r.toText()) }, Modifier.fillMaxWidth())
}

@Composable
private fun AsrBlock(a: AsrRun, best: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.title, style = Type.title, modifier = Modifier.weight(1f))
            if (best) Status("Most accurate", Ink.Teal)
        }
        Fact("Word errors", "${pct(a.wer)} (${a.errors} of ${a.referenceWords})")
        Fact("Speed", "${one(a.speedFactor)}× real time")
        Fact("Loading", Units.seconds(a.loadMs))
        Text(if (open) "Hide what it heard" else "Show what it heard", style = Type.small.copy(color = Ink.Amber),
            modifier = Modifier.clickable { open = !open }.padding(vertical = 10.dp))
        if (open) for (x in a.samples) {
            Text("${x.file} · ${pct(x.wer)} · ${one(x.seconds)} s", style = Type.tiny)
            Text(x.heard, style = Type.mono, modifier = Modifier.padding(bottom = 10.dp))
        }
    }
}
