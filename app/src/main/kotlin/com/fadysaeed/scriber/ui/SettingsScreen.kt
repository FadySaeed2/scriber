package com.fadysaeed.scriber.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.fadysaeed.scriber.SettingsUi
import com.fadysaeed.scriber.core.Units
import com.fadysaeed.scriber.data.AppSettings

@Composable
fun SettingsScreen(
    ui: SettingsUi, onChange: ((AppSettings) -> AppSettings) -> Unit, onUnlock: () -> Unit, onLock: () -> Unit, onCheckVault: () -> Unit,
    onCopyCrash: () -> Unit = {}, onClearCrash: () -> Unit = {}, onPrompts: () -> Unit = {},
    data: DataActions = DataActions(),
) {
    val st = ui.settings
    Heading("Scriber", "Settings")

    ui.crash?.let { details ->
        Section("Last problem")
        Text("Scriber stopped unexpectedly. Copy the details and send them so it can be fixed; they hold the error only, no recordings or transcripts.", style = Type.note)
        Spacer(Modifier.height(10.dp))
        Text(details.lines().take(14).joinToString("\n"), style = Type.mono)
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            AmberButton("Copy details", onCopyCrash, Modifier.weight(1f))
            GhostButton("Clear", onClearCrash, Modifier.weight(1f))
        }
    }

    Section("Keeping recordings")
    Toggle("Keep audio forever", "Recordings stay until you delete them, so you can transcribe them again any time", st.retention.keepForever) { on ->
        onChange { it.copy(retention = it.retention.copy(keepForever = on)) }
    }
    if (!st.retention.keepForever) {
        Text("Delete audio after", style = Type.note, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in listOf(7, 30, 90, 365)) Choice(if (d == 365) "1 year" else "$d days", st.retention.deleteAfterDays == d, { onChange { it.copy(retention = it.retention.copy(deleteAfterDays = d)) } }, Modifier.weight(1f))
        }
        Text("Transcripts are never deleted by this; only the audio.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
    }
    Toggle("Studio quality", "Record at 48 kHz (about 345 MB an hour instead of 115 MB). Speech models still hear 16 kHz.", st.highQuality) { on ->
        onChange { it.copy(highQuality = on) }
    }
    Fact("Space used by Scriber's vault", Units.bytes(ui.storageBytes))
    Fact("Audio", "${Units.bytes(ui.audioBytes)} in ${ui.audioSessions} ${if (ui.audioSessions == 1) "session" else "sessions"}")
    if (ui.largest.isNotEmpty()) {
        Text("Taking the most room", style = Type.note, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
        for (x in ui.largest) ActionRow(Glyph.WAVE, x.title, { data.onOpenSession(x.id) }, "${Units.bytes(x.bytes)} · ${java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(x.createdAtMillis))}")
    }
    var sure by remember { mutableStateOf(false) }
    ActionRow(Glyph.TRASH, if (sure) "Tap again: delete audio older than 30 days" else "Delete audio older than 30 days now",
        { if (sure) { sure = false; data.onDeleteOldAudio(30) } else sure = true }, "Transcripts, corrections and speakers stay", color = Ink.Amber, enabled = ui.audioSessions > 0)

    Section("Engines")
    Text("Main transcript, for dictation and conversation", style = Type.note, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Parakeet", st.baseEngine == "parakeet", { onChange { it.copy(baseEngine = "parakeet", secondEngine = "medasr") } }, Modifier.weight(1f))
        Choice("MedASR", st.baseEngine == "medasr", { onChange { it.copy(baseEngine = "medasr", secondEngine = "parakeet") } }, Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    Toggle("Second opinion on every session", "${if (st.secondEngine == "medasr") "MedASR" else "Parakeet"} transcribes it too, and the places where the two disagree are marked for you to check", st.secondOpinion) { on ->
        onChange { it.copy(secondOpinion = on) }
    }
    Toggle("Show every difference", "Off: the second opinion shows medical language only: medicines, conditions, tests, procedures. On: every place the two engines heard differently.", st.showAllDifferences) { on ->
        onChange { it.copy(showAllDifferences = on) }
    }
    Toggle("Find who is speaking", "In every new conversation and meeting, while it is transcribed. Off: you can still do it for one session from its screen.", st.findSpeakers) { on ->
        onChange { it.copy(findSpeakers = on) }
    }
    Text("Voices to look for in a conversation (meetings always decide)", style = Type.note, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((n, label) in listOf(2 to "2", 3 to "3", 0 to "Decide")) Choice(label, st.speakers == n, { onChange { it.copy(speakers = n) } }, Modifier.weight(1f))
    }
    Fact("Medicine dictionary", "${ui.medicines} names, ${ui.personalWords} of yours")

    Section("Copying for AI")
    Text("The Copy button on a session puts the clean transcript on the clipboard, with a prompt above it that tells a cloud model what to write.", style = Type.note, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
    ActionRow(Glyph.LIST, "Prompts", onPrompts, "${ui.prompts.size} prompts: change them or make your own")

    Section("Backup")
    BackupSection(ui, data)

    Section("Internet")
    Status(if (ui.locked) "Locked" else "Downloads open", if (ui.locked) Ink.Teal else Ink.Amber)
    Spacer(Modifier.height(8.dp))
    Text(if (ui.locked) "Scriber opens no connections. Unlocking needs your fingerprint or screen lock, and only re-enables model downloads."
        else "Scriber may reach only the model hosts below, and locks itself again once the models are in place.", style = Type.body)
    Spacer(Modifier.height(12.dp))
    if (ui.locked) GhostButton("Unlock downloads", onUnlock, Modifier.fillMaxWidth()) else GhostButton("Lock now", onLock, Modifier.fillMaxWidth())
    Spacer(Modifier.height(14.dp))
    for (h in ui.hosts) Text("$h and its subdomains", style = Type.mono)
    Text("Every other address fails before any data is sent; plain http is refused everywhere.", style = Type.small, modifier = Modifier.padding(top = 6.dp))

    Section("On this phone")
    Fact("Storage", when (ui.vaultOk) { true -> "Encrypted, checked"; false -> "Check FAILED"; null -> "Encrypted" }, when (ui.vaultOk) { false -> Ink.Ember; true -> Ink.Teal; null -> Ink.Bone })
    Fact("Keys", "Android Keystore")
    Fact("Backups", "Off")
    Toggle("Allow screenshots", if (st.allowScreenshots) "On: screenshots go to your Gallery, outside Scriber's encryption, and may sync to the cloud. Turn it off when you are done."
        else "Off: the phone cannot capture Scriber's screen, and the recent-apps view is blank.", st.allowScreenshots) { on -> onChange { it.copy(allowScreenshots = on) } }
    Fact("Opening", "Fingerprint or screen lock")
    Spacer(Modifier.height(12.dp))
    GhostButton("Check encryption now", onCheckVault, Modifier.fillMaxWidth())

    Section("Build")
    Fact("App", ui.version)
    Fact("Model list", "version ${ui.catalogVersion}")
    Fact("Speech", "sherpa-onnx 2.0.1")
    Spacer(Modifier.height(4.dp))
}

/** Looking after what is stored: opening a big session, clearing old audio, backups. */
class DataActions(
    val onOpenSession: (String) -> Unit = {},
    val onDeleteOldAudio: (Int) -> Unit = {},
    /** Asks where to save, then backs up with this passphrase. */
    val onBackup: (CharArray) -> Unit = {},
    /** Asks for the backup file, then restores it with this passphrase. */
    val onRestore: (CharArray) -> Unit = {},
)

@Composable
private fun BackupSection(ui: SettingsUi, data: DataActions) {
    var mode by remember { mutableStateOf<String?>(null) }
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    Text("One file with every session, transcript, correction, speaker name, prompt and setting, locked with a passphrase you choose. Use it to move Scriber to a new phone, or keep it somewhere safe.", style = Type.note, modifier = Modifier.padding(top = 10.dp))
    val progress = ui.backup
    if (progress != null) {
        Spacer(Modifier.height(12.dp))
        Status("${progress.label} ${(progress.fraction * 100).toInt()}%. Keep Scriber open.", Ink.Teal)
        Spacer(Modifier.height(8.dp))
        Meter(progress.fraction, Ink.Teal, Modifier.fillMaxWidth())
        return
    }
    when (mode) {
        null -> {
            ActionRow(Glyph.SAVE, "Back up everything", { mode = "backup"; pass = ""; again = "" }, "You choose where the file goes: the phone, a USB drive, a cloud folder")
            ActionRow(Glyph.IMPORT, "Restore from a backup", { mode = "restore"; pass = ""; again = "" }, "Adds what this phone does not have; nothing here is overwritten")
        }
        else -> {
            val backup = mode == "backup"
            Spacer(Modifier.height(12.dp))
            Eyebrow(if (backup) "Passphrase for this backup" else "The backup's passphrase")
            Spacer(Modifier.height(8.dp))
            SecretField(pass, "Passphrase") { pass = it }
            if (backup) {
                Spacer(Modifier.height(8.dp))
                SecretField(again, "The same again") { again = it }
                Spacer(Modifier.height(8.dp))
                Text("At least 10 characters. Scriber cannot open the backup without it, and nobody can reset it. The file leaves Scriber's own encryption, but no one can read it without the passphrase.",
                    style = Type.small.copy(color = Ink.Amber))
            }
            val ok = if (backup) pass.length >= 10 && pass == again else pass.isNotEmpty()
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("Cancel", { mode = null; pass = ""; again = "" }, Modifier.weight(1f), minHeight = 52.dp)
                AmberButton(if (backup) "Choose where to save" else "Choose the backup", {
                    val chars = pass.toCharArray(); mode = null; pass = ""; again = ""
                    if (backup) data.onBackup(chars) else data.onRestore(chars)
                }, Modifier.weight(1.4f), enabled = ok)
            }
        }
    }
}

@Composable
private fun SecretField(value: String, hint: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(14.dp)) {
        if (value.isEmpty()) Text(hint, style = Type.body.copy(color = Ink.Ash))
        BasicTextField(value, onChange, singleLine = true, textStyle = Type.body, cursorBrush = SolidColor(Ink.Amber),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth())
    }
}
