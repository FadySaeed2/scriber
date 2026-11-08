package com.fadysaeed.scriber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.SettingsUi
import com.fadysaeed.scriber.core.Units
import com.fadysaeed.scriber.data.AppSettings

@Composable
fun SettingsScreen(
    ui: SettingsUi, onChange: ((AppSettings) -> AppSettings) -> Unit, onUnlock: () -> Unit, onLock: () -> Unit, onCheckVault: () -> Unit,
    onCopyCrash: () -> Unit = {}, onClearCrash: () -> Unit = {}, onTemplates: () -> Unit = {},
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
    Toggle("Show every difference", "Off: the second opinion shows only medicines. On: every place the two engines heard differently.", st.showAllDifferences) { on ->
        onChange { it.copy(showAllDifferences = on) }
    }
    Toggle("Find who is speaking", "In every new conversation, while it is transcribed. Off: you can still do it for one session from its screen.", st.findSpeakers) { on ->
        onChange { it.copy(findSpeakers = on) }
    }
    Text("Voices to look for in a conversation", style = Type.note, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((n, label) in listOf(2 to "2", 3 to "3", 0 to "Decide")) Choice(label, st.speakers == n, { onChange { it.copy(speakers = n) } }, Modifier.weight(1f))
    }
    Fact("Medicine dictionary", "${ui.medicines} names, ${ui.personalWords} of yours")

    Section("Notes")
    Toggle("Write the note after every conversation", "Once the transcript and speakers are ready, Gemma writes it on this phone. Off: write it from the session when you want.", st.autoNotes) { on ->
        onChange { it.copy(autoNotes = on) }
    }
    Fact("Template for new conversations", ui.templates.firstOrNull { it.id == st.noteTemplate }?.name ?: "Inpatient review")
    ActionRow(Glyph.LIST, "Note templates", onTemplates, "Change the five that come with Scriber, or make your own")

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
    Fact("Language", "LiteRT-LM 0.19.1")
    Spacer(Modifier.height(4.dp))
}
