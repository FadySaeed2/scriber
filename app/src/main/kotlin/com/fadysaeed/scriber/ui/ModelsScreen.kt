package com.fadysaeed.scriber.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.ModelsUi
import com.fadysaeed.scriber.PackRow
import com.fadysaeed.scriber.core.DevicePlan
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
    Heading("Scriber", "Models", "Downloaded once, checked byte for byte against a pinned list, then Scriber locks itself off the internet.")
    StatusBlock(ui, onDownload, onCancel, onVerify)

    ui.message?.let { msg ->
        Spacer(Modifier.height(14.dp))
        Text(msg, style = Type.body.copy(color = Ink.Amber))
        Text("Dismiss", style = Type.small.copy(color = Ink.Dust), modifier = Modifier.clickable(onClick = onDismissMessage).padding(vertical = 8.dp))
    }

    ui.device?.let { d ->
        Section("This phone")
        Fact("Phone", "${d.manufacturer} ${d.model}")
        Fact("Chip", d.soc)
        Fact("Memory", Units.bytes(d.totalRamBytes))
        Fact("Free space", Units.bytes(ui.freeBytes), if (ui.enoughSpace) Ink.Bone else Ink.Ember)
        Fact("Still to download", Units.bytes((ui.neededBytes - DevicePlan.SAFETY_MARGIN_BYTES).coerceAtLeast(0)))
    }

    Section("Note writer")
    for (row in ui.rows.filter { it.pack.role == PackRole.LLM }) {
        val variant = row.pack.variant ?: continue
        val on = ui.chosenLlm == variant
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = !ui.locked, role = Role.RadioButton) { onChooseLlm(variant) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Dot(if (on) Ink.Amber else Ink.Rule, 10.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.pack.title + if (variant == ui.recommendedLlm) "  ·  recommended" else "", style = Type.title.copy(color = if (on) Ink.Bone else Ink.Sand))
                    Text("${row.pack.subtitle}. ${Units.bytes(row.pack.totalBytes)}", style = Type.small)
                }
            }
            Hairline()
        }
    }

    Section("What gets downloaded")
    for (row in ui.selected) PackLine(row)

    if (!ui.locked) {
        Section("Network")
        Toggle("Wi-Fi only", "About ${Units.bytes(ui.selected.sumOf { it.pack.totalBytes })} in total", ui.wifiOnly, onWifiOnly)
    }
}

@Composable
private fun StatusBlock(ui: ModelsUi, onDownload: () -> Unit, onCancel: () -> Unit, onVerify: () -> Unit) {
    val fetch = ui.fetch
    Column {
        when {
            ui.verifying != null -> { Status("Checking files", Ink.Teal); Spacer(Modifier.height(8.dp)); Text(ui.verifying, style = Type.note) }
            fetch is Fetcher.Status.Running -> {
                val frac = if (fetch.total > 0) fetch.done.toFloat() / fetch.total else 0f
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${(frac * 100).toInt()}%", style = Type.display)
                    Spacer(Modifier.width(12.dp))
                    Status(if (fetch.hashing) "Checking" else "Downloading", Ink.Teal)
                }
                Spacer(Modifier.height(12.dp)); Meter(frac, Ink.Amber); Spacer(Modifier.height(10.dp))
                Text("${ui.rows.firstOrNull { it.pack.id == fetch.pack }?.pack?.title ?: ""} · ${Units.bytes(fetch.done)} of ${Units.bytes(fetch.total)}", style = Type.note)
                Text("Keeps going with the screen off; stops and resumes safely.", style = Type.small)
                Spacer(Modifier.height(12.dp)); GhostButton("Pause", onCancel)
            }
            fetch is Fetcher.Status.Waiting -> {
                Status("Waiting", Ink.Amber); Spacer(Modifier.height(8.dp))
                Text(if (ui.wifiOnly) "Waiting for Wi-Fi and enough free space." else "Waiting for a connection and enough free space.", style = Type.note)
                Spacer(Modifier.height(12.dp)); GhostButton("Cancel", onCancel)
            }
            ui.locked || ui.allReady -> {
                Status(if (ui.locked) "Internet locked" else "Ready", Ink.Teal); Spacer(Modifier.height(8.dp))
                Text(if (ui.locked) "Every model is on the phone and checked. Scriber opens no connection until you unlock downloads in Settings."
                    else "Everything is downloaded and checked.", style = Type.body)
                Spacer(Modifier.height(12.dp)); GhostButton("Check every file again", onVerify)
            }
            else -> {
                if (fetch is Fetcher.Status.Failed) { Status("Stopped", Ink.Ember); Spacer(Modifier.height(6.dp)); Text(fetch.reason, style = Type.body) }
                else Text("One download, then offline for good. Interrupted files resume; any file whose checksum differs is thrown away.", style = Type.body)
                Spacer(Modifier.height(14.dp))
                AmberButton(if (fetch is Fetcher.Status.Failed) "Try again" else "Download models", onDownload, Modifier.fillMaxWidth(), enabled = ui.enoughSpace)
                if (!ui.enoughSpace) { Spacer(Modifier.height(8.dp)); Text("Not enough free space: about ${Units.bytes(ui.neededBytes)} needed.", style = Type.small.copy(color = Ink.Ember)) }
            }
        }
    }
}

@Composable
private fun PackLine(row: PackRow) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(row.pack.title, style = Type.title)
                Text(row.pack.subtitle, style = Type.note)
                Text("${row.pack.source}. Licence: ${row.pack.license}.", style = Type.tiny)
            }
            Spacer(Modifier.width(10.dp))
            when (val st = row.state) {
                PackState.Verified -> Status("Ready", Ink.Teal)
                is PackState.Partial -> Status("${st.bytesOnPhone * 100 / row.pack.totalBytes}%", Ink.Amber)
                PackState.Missing -> Text(Units.bytes(row.pack.totalBytes), style = Type.figure)
            }
        }
        if (row.state is PackState.Partial) { Meter(row.bytesOnPhone.toFloat() / row.pack.totalBytes, Ink.Amber); Spacer(Modifier.height(10.dp)) }
        Hairline()
    }
}
