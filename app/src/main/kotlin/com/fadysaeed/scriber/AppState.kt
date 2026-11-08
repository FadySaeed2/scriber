package com.fadysaeed.scriber

import com.fadysaeed.scriber.audio.PlayerState
import com.fadysaeed.scriber.audio.RecordingState
import com.fadysaeed.scriber.core.ModelPack
import com.fadysaeed.scriber.core.Session
import com.fadysaeed.scriber.core.TranscriptRun
import com.fadysaeed.scriber.data.AppSettings
import com.fadysaeed.scriber.fetch.Fetcher
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.lab.DeviceSnapshot
import com.fadysaeed.scriber.lab.LabReport
import com.fadysaeed.scriber.work.JobProgress

data class PackRow(val pack: ModelPack, val state: PackState, val bytesOnPhone: Long)

data class ModelsUi(
    val device: DeviceSnapshot? = null,
    val recommendedLlm: String? = null,
    val chosenLlm: String? = null,
    val rows: List<PackRow> = emptyList(),
    val fetch: Fetcher.Status = Fetcher.Status.Idle,
    val locked: Boolean = false,
    val wifiOnly: Boolean = true,
    val freeBytes: Long = 0,
    val neededBytes: Long = 0,
    val verifying: String? = null,
    val message: String? = null,
) {
    val selected: List<PackRow> get() = rows.filter { it.pack.required || it.pack.variant == chosenLlm }
    val allReady: Boolean get() = selected.isNotEmpty() && selected.all { it.state is PackState.Verified }
    val enoughSpace: Boolean get() = freeBytes >= neededBytes
    val speechReady: Boolean get() = rows.any { it.pack.role == com.fadysaeed.scriber.core.PackRole.ASR && it.state is PackState.Verified } &&
        rows.any { it.pack.role == com.fadysaeed.scriber.core.PackRole.VAD && it.state is PackState.Verified }
}

data class LabUi(val step: String? = null, val report: LabReport? = null, val modelsReady: Boolean = false)

data class SessionRow(val session: Session, val latest: TranscriptRun?, val job: JobProgress?)

data class SessionsUi(
    val rows: List<SessionRow> = emptyList(),
    val speechReady: Boolean = false,
    val recording: RecordingState = RecordingState(),
    val problems: List<String> = emptyList(),
)

data class SessionUi(
    val session: Session? = null,
    val runs: List<TranscriptRun> = emptyList(),
    val selectedRunId: String? = null,
    val readable: Boolean = true,
    val job: JobProgress? = null,
    val player: PlayerState = PlayerState(),
    val engines: List<Pair<String, String>> = emptyList(),
) {
    val run: TranscriptRun? get() = runs.firstOrNull { it.id == selectedRunId } ?: runs.lastOrNull()
}

data class SettingsUi(
    val settings: AppSettings = AppSettings(),
    val locked: Boolean = false,
    val hosts: List<String> = emptyList(),
    val vaultOk: Boolean? = null,
    val vaultDetail: String = "",
    val version: String = "",
    val catalogVersion: Int = 0,
    val storageBytes: Long = 0,
    /** Details of the last unexpected stop, until cleared. */
    val crash: String? = null,
)
