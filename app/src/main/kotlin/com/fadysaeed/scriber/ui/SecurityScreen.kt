package com.fadysaeed.scriber.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.SecurityUi

@Composable
fun SecurityScreen(ui: SecurityUi, onUnlock: () -> Unit, onLock: () -> Unit, onCheckVault: () -> Unit) {
    val s = LocalSignals.current
    Column {
        ScreenTitle("Security", "What protects the recordings, and how to check it yourself.")

        Panel(accent = if (ui.locked) s.ready else s.warn) {
            Chip(if (ui.locked) "Internet locked" else "Downloads open", if (ui.locked) s.ready else s.warn)
            Text(
                if (ui.locked) "Scriber opens no connections. Unlocking needs your fingerprint or screen lock, and only re-enables model downloads."
                else "Scriber may only reach the model hosts below. It locks itself again once the models are in place.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (ui.locked) OutlinedButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) { Text("Unlock downloads") }
            else OutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth()) { Text("Lock now") }
        }

        SectionLabel("The only hosts Android lets Scriber reach")
        Panel {
            for (h in ui.hosts) Text("$h and its subdomains", style = Mono)
            Text("Every other address fails before any data is sent, because the app trusts no certificate except these hosts'. Plain http is refused everywhere.", style = MaterialTheme.typography.bodySmall, color = s.muted)
            Text("To see it yourself: Settings, Apps, Scriber, Mobile data and Wi-Fi usage stays at the download size and stops growing.", style = MaterialTheme.typography.bodySmall, color = s.muted)
        }

        SectionLabel("On the phone")
        Panel {
            Fact("Storage", when (ui.vaultOk) { true -> "Encrypted, checked"; false -> "Check FAILED"; null -> "Encrypted, not checked yet" },
                when (ui.vaultOk) { true -> s.ready; false -> s.problem; null -> MaterialTheme.colorScheme.onSurface })
            Fact("Keys", "Held by the Android Keystore")
            Fact("Backups", "Off (Google, Samsung, transfers)")
            Fact("Screenshots", "Blocked, and hidden in recent apps")
            Fact("Opening", "Fingerprint or screen lock")
            if (ui.vaultDetail.isNotBlank()) Text(ui.vaultDetail, style = MaterialTheme.typography.bodySmall, color = s.muted)
            OutlinedButton(onClick = onCheckVault, modifier = Modifier.fillMaxWidth()) { Text("Check encryption now") }
        }

        SectionLabel("Build")
        Panel {
            Fact("App", ui.version)
            Fact("Model list", "version ${ui.catalogVersion}")
            Fact("Speech engine", "sherpa-onnx 2.0.1")
            Fact("Language runtime", "LiteRT-LM 0.19.1")
        }
        Spacer(Modifier.height(24.dp))
    }
}
