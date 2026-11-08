package com.fadysaeed.scriber.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.ModelsUi
import com.fadysaeed.scriber.PackRow
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.Units
import com.fadysaeed.scriber.fetch.Fetcher
import com.fadysaeed.scriber.fetch.PackState

@Composable
fun ModelsScreen(
    ui: ModelsUi,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onVerify: () -> Unit,
    onChooseLlm: (String) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onDismissMessage: () -> Unit,
) {
    val s = LocalSignals.current
    Column {
        ScreenTitle("Models", "Downloaded once, checked byte for byte against a pinned list, then Scriber locks itself off the internet.")

        StatusPanel(ui, onDownload, onCancel, onVerify)

        ui.message?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Panel(accent = s.warn) {
                Text(msg, style = MaterialTheme.typography.bodyMedium)
                Text("Dismiss", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onDismissMessage))
            }
        }

        ui.device?.let { d ->
            SectionLabel("This phone")
            Panel {
                Fact("Phone", "${d.manufacturer} ${d.model}")
                Fact("Chip", d.soc)
                Fact("Memory", Units.bytes(d.totalRamBytes))
                Fact("Free space", Units.bytes(ui.freeBytes), if (ui.enoughSpace) s.ready else s.problem)
                Fact("Still to download", Units.bytes((ui.neededBytes - com.fadysaeed.scriber.core.DevicePlan.SAFETY_MARGIN_BYTES).coerceAtLeast(0)))
            }
        }

        SectionLabel("Note writer")
        Panel {
            Text(
                "Pick the language model that writes the notes. The recommendation comes from this phone's memory.",
                style = MaterialTheme.typography.bodyMedium, color = s.muted,
            )
            for (row in ui.rows.filter { it.pack.role == PackRole.LLM }) {
                val variant = row.pack.variant ?: continue
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !ui.locked) { onChooseLlm(variant) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = ui.chosenLlm == variant, onClick = { onChooseLlm(variant) }, enabled = !ui.locked)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.pack.title, style = MaterialTheme.typography.titleMedium)
                            if (variant == ui.recommendedLlm) { Spacer(Modifier.width(8.dp)); Chip("Recommended", s.ready) }
                        }
                        Text("${row.pack.subtitle}. ${Units.bytes(row.pack.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = s.muted)
                    }
                }
            }
        }

        SectionLabel("What gets downloaded")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in ui.selected) PackLine(row)
        }

        if (!ui.locked) {
            SectionLabel("Network")
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wi-Fi only", style = MaterialTheme.typography.titleMedium)
                        Text("About ${Units.bytes(ui.selected.sumOf { it.pack.totalBytes })} in total.", style = MaterialTheme.typography.bodySmall, color = s.muted)
                    }
                    Switch(checked = ui.wifiOnly, onCheckedChange = onWifiOnly)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusPanel(ui: ModelsUi, onDownload: () -> Unit, onCancel: () -> Unit, onVerify: () -> Unit) {
    val s = LocalSignals.current
    val fetch = ui.fetch
    when {
        ui.verifying != null -> Panel(accent = s.busy) {
            Chip("Checking files", s.busy)
            Text(ui.verifying, style = MaterialTheme.typography.bodyMedium)
        }
        fetch is Fetcher.Status.Running -> Panel(accent = s.busy) {
            val frac = if (fetch.total > 0) fetch.done.toFloat() / fetch.total else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(if (fetch.hashing) "Checking" else "Downloading", s.busy)
                Spacer(Modifier.weight(1f))
                Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
            }
            Meter(frac, s.busy)
            val packTitle = ui.rows.firstOrNull { it.pack.id == fetch.pack }?.pack?.title ?: ""
            Text("$packTitle  ${fetch.file ?: ""}", style = MaterialTheme.typography.bodySmall, color = s.muted)
            Text("${Units.bytes(fetch.done)} of ${Units.bytes(fetch.total)}. Keeps going with the screen off; stops and resumes safely.", style = MaterialTheme.typography.bodySmall, color = s.muted)
            OutlinedButton(onClick = onCancel) { Text("Pause") }
        }
        fetch is Fetcher.Status.Waiting -> Panel(accent = s.warn) {
            Chip("Waiting", s.warn)
            Text(if (ui.wifiOnly) "Waiting for Wi-Fi and enough free space." else "Waiting for a connection and enough free space.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
        ui.locked -> Panel(accent = s.ready) {
            Chip("Internet locked", s.ready)
            Text("All models are on the phone and checked. Scriber will not open any connection until you unlock downloads in Security.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onVerify) { Text("Check every file again") }
        }
        ui.allReady -> Panel(accent = s.ready) {
            Chip("Ready", s.ready)
            Text("Everything is downloaded and checked.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onVerify) { Text("Check every file again") }
        }
        else -> Panel(accent = if (fetch is Fetcher.Status.Failed) s.problem else null) {
            if (fetch is Fetcher.Status.Failed) {
                Chip("Stopped", s.problem)
                Text(fetch.reason, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("One download, then offline for good", style = MaterialTheme.typography.titleMedium)
                Text("Partly downloaded files resume where they stopped. Each file is rejected unless its checksum matches.", style = MaterialTheme.typography.bodyMedium, color = s.muted)
            }
            Button(onClick = onDownload, enabled = ui.enoughSpace, modifier = Modifier.fillMaxWidth()) {
                Text(if (fetch is Fetcher.Status.Failed) "Try again" else "Download models")
            }
            if (!ui.enoughSpace) Text("Not enough free space: about ${Units.bytes(ui.neededBytes)} needed.", style = MaterialTheme.typography.bodySmall, color = s.problem)
        }
    }
}

@Composable
private fun PackLine(row: PackRow) {
    val s = LocalSignals.current
    Panel {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(row.pack.title, style = MaterialTheme.typography.titleMedium)
                Text(row.pack.subtitle, style = MaterialTheme.typography.bodySmall, color = s.muted)
            }
            Spacer(Modifier.width(10.dp))
            when (val st = row.state) {
                PackState.Verified -> Chip("Ready", s.ready)
                is PackState.Partial -> Chip("${(st.bytesOnPhone * 100 / row.pack.totalBytes)}%", s.busy)
                PackState.Missing -> Chip(Units.bytes(row.pack.totalBytes), s.muted)
            }
        }
        if (row.state is PackState.Partial) Meter(row.bytesOnPhone.toFloat() / row.pack.totalBytes, s.busy, Modifier.padding(top = 2.dp))
        Text("${row.pack.source}. Licence: ${row.pack.license}.", style = MaterialTheme.typography.bodySmall, color = s.muted)
    }
}
