package com.fadysaeed.scriber

import com.fadysaeed.scriber.audio.PlayerState
import com.fadysaeed.scriber.audio.RecordingState
import com.fadysaeed.scriber.core.ModelPack
import com.fadysaeed.scriber.core.Session
import com.fadysaeed.scriber.core.Difference
import com.fadysaeed.scriber.core.Document
import com.fadysaeed.scriber.core.EditLayer
import com.fadysaeed.scriber.core.Line
import com.fadysaeed.scriber.core.MedicineHit
import com.fadysaeed.scriber.core.Reading
import com.fadysaeed.scriber.core.Scoring
import com.fadysaeed.scriber.core.SpeakerLayer
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
    val rows: List<PackRow> = emptyList(),
    val fetch: Fetcher.Status = Fetcher.Status.Idle,
    val locked: Boolean = false,
    val wifiOnly: Boolean = true,
    val freeBytes: Long = 0,
    val neededBytes: Long = 0,
    val verifying: String? = null,
    val message: String? = null,
) {
    val selected: List<PackRow> get() = rows.filter { it.pack.required }
    val allReady: Boolean get() = selected.isNotEmpty() && selected.all { it.state is PackState.Verified }
    val enoughSpace: Boolean get() = freeBytes >= neededBytes
    val speechReady: Boolean get() = rows.any { it.pack.role == com.fadysaeed.scriber.core.PackRole.ASR && it.state is PackState.Verified } &&
        rows.any { it.pack.role == com.fadysaeed.scriber.core.PackRole.VAD && it.state is PackState.Verified }
}

/** Engine scores on one of Fady's own sessions that he corrected and marked as right. */
data class OwnScore(val sessionId: String, val sessionTitle: String, val minutes: Double, val scores: List<Scoring.EngineScore>, val canTryHints: Boolean = false)

data class LabUi(
    val step: String? = null,
    val report: LabReport? = null,
    val modelsReady: Boolean = false,
    val own: List<OwnScore> = emptyList(),
    val speed: com.fadysaeed.scriber.data.ThreadPlan? = null,
)

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
    /** The selected run split into words and compared with the other engine's latest transcript. */
    val reading: Reading? = null,
    /** The run the selected one is compared with (the second opinion). */
    val compareRun: TranscriptRun? = null,
    /** Fady's corrections to the selected run. */
    val edits: EditLayer? = null,
    /** Show the corrected text (true) or the sealed original. */
    val showEdited: Boolean = true,
    /** Engine name for the second opinion that is missing, if the session has none yet. */
    val missingSecond: String? = null,
    /** The second opinion is queued or running: the medicine check waits for it. */
    val secondPending: Boolean = false,
    /** Who spoke when, for a conversation. */
    val speakers: SpeakerLayer? = null,
    /** The speaker of each base word of the selected run. */
    val speakerOf: List<Int>? = null,
    /** The speaker models are on the phone. */
    val canFindSpeakers: Boolean = false,
    /** Show every difference, not only those about medicines (a setting, off by default). */
    val allDifferences: Boolean = false,
    /** Prompts to copy with, and the one this kind of session copies with now. */
    val prompts: List<com.fadysaeed.scriber.core.PromptTemplate> = emptyList(),
    val promptId: String = "",
    val copyTimes: Boolean = false,
    val copyNoticeSeen: Boolean = false,
) {
    val run: TranscriptRun? get() = runs.firstOrNull { it.id == selectedRunId } ?: runs.lastOrNull()

    /** The text to show: corrected lines, or the sealed run's own lines. */
    val lines: List<Line> get() {
        val r = run ?: return emptyList()
        val rd = reading ?: return r.segments.mapIndexed { i, s -> Line(i, s.startMs, s.endMs, s.text, false) }
        val sp = speakers
        return Document.lines(r, rd, if (showEdited) edits else null, speakerOf) { sp?.name(it) ?: "Speaker ${it + 1}" }
    }

    /** True when a correction already covers this place (made from it, or a wider one over it). */
    private fun touched(from: Int, to: Int): Boolean = edits?.edits.orEmpty().any { e ->
        (e.from == from && e.to == to) ||
            (e.from < e.to && (if (from == to) e.from <= from && from < e.to else e.from < to && from < e.to))
    }

    /** Differences Fady has not dealt with yet. */
    val openDifferences: List<Difference> get() = reading?.differences.orEmpty()
        .filter { d -> (allDifferences || d.medical) && d.id !in edits?.kept.orEmpty() && !touched(d.from, d.to) }

    /** Medicines in the transcript that look misspelled and have not been dealt with. */
    val openMedicines: List<MedicineHit> get() = reading?.medicines.orEmpty().filter { m -> !m.exact && m.id !in edits?.kept.orEmpty() && !touched(m.from, m.to) }

    val corrections: Int get() = edits?.edits?.size ?: 0
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
    val medicines: Int = 0,
    val personalWords: Int = 0,
    val prompts: List<com.fadysaeed.scriber.core.PromptTemplate> = emptyList(),
    /** Recordings' audio, and how many sessions still have it. */
    val audioBytes: Long = 0,
    val audioSessions: Int = 0,
    /** Sessions taking the most room, biggest first. */
    val largest: List<StoredSession> = emptyList(),
    /** A backup or restore under way. */
    val backup: BackupProgress? = null,
)

data class StoredSession(val id: String, val title: String, val bytes: Long, val createdAtMillis: Long, val hasAudio: Boolean)

data class BackupProgress(val label: String, val fraction: Float)
