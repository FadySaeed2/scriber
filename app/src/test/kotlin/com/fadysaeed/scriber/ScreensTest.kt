package com.fadysaeed.scriber

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.fadysaeed.scriber.audio.LiveLine
import com.fadysaeed.scriber.audio.PlayerState
import com.fadysaeed.scriber.audio.RecordingState
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Seal
import com.fadysaeed.scriber.core.Segment
import com.fadysaeed.scriber.core.Session
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.SessionSource
import com.fadysaeed.scriber.core.SessionStatus
import com.fadysaeed.scriber.core.TranscriptRun
import com.fadysaeed.scriber.fetch.Fetcher
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.lab.AsrRun
import com.fadysaeed.scriber.lab.AsrSample
import com.fadysaeed.scriber.lab.DeviceSnapshot
import com.fadysaeed.scriber.lab.DiarizationRun
import com.fadysaeed.scriber.lab.LabReport
import com.fadysaeed.scriber.lab.LlmRun
import com.fadysaeed.scriber.lab.VadRun
import com.fadysaeed.scriber.lab.VaultRun
import com.fadysaeed.scriber.ui.Frame
import com.fadysaeed.scriber.ui.LabScreen
import com.fadysaeed.scriber.ui.LockScreen
import com.fadysaeed.scriber.ui.ModelsScreen
import com.fadysaeed.scriber.ui.RecordingScreen
import com.fadysaeed.scriber.ui.ScriberTheme
import com.fadysaeed.scriber.ui.SessionActions
import com.fadysaeed.scriber.ui.SessionScreen
import com.fadysaeed.scriber.ui.SessionSheet
import com.fadysaeed.scriber.ui.SessionsScreen
import com.fadysaeed.scriber.ui.SettingsScreen
import com.fadysaeed.scriber.ui.Tab
import com.fadysaeed.scriber.work.JobProgress
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every screen with sample data (no patient data) so the design can be reviewed without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h1500dp-night-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val catalog = ModelCatalog.bundled()
    private val gb = 1024L * 1024 * 1024
    private val now = 1_794_150_000_000L
    private val device = DeviceSnapshot("Samsung", "SM-S918B", "QTI SM8550", "17", 8, (11.58 * gb).toLong(), 4 * gb, 727 * gb, "cool", 0.21f)

    private fun rows(state: (String) -> PackState) = catalog.packs.map {
        val st = state(it.id)
        PackRow(it, st, when (st) { PackState.Verified -> it.totalBytes; is PackState.Partial -> st.bytesOnPhone; PackState.Missing -> 0 })
    }

    private fun tabShot(file: String, tab: Tab, content: @Composable () -> Unit) {
        compose.setContent { ScriberTheme { Frame(tab, {}) { content() } } }
        compose.onRoot().captureRoboImage("screenshots/$file")
    }

    private fun shot(file: String, content: @Composable () -> Unit) {
        compose.setContent { ScriberTheme { content() } }
        compose.onRoot().captureRoboImage("screenshots/$file")
    }

    private fun session(id: String, title: String, mode: SessionMode, minutes: Int, source: SessionSource = SessionSource.RECORDED, recovered: Boolean = false) =
        Session(id, title, mode, source, now - minutes * 3_600_000L, SessionStatus.READY, durationMs = minutes * 60_000L, audioName = "a", audioSha256 = "3f9a2c71b04e88d1aa", audioBytes = minutes * 1_920_000L, workAudioName = "a", recovered = recovered)

    private val dictationRun = Seal.sealed(TranscriptRun("r1", "s1", "medasr", "Google MedASR", "a".repeat(64), emptyMap(), "b".repeat(64), now, now, RunStatus.DONE, listOf(
        Segment(0, 6_200, "[MENTAL STATE] {colon} Calm and settled {comma} good eye contact {period}"),
        Segment(6_500, 14_000, "Speech normal in rate and volume {period} Mood described as better {period} No perceptual disturbance reported today {period}"),
        Segment(14_600, 21_000, "{new paragraph} [PLAN] {colon} Continue olanzapine fifteen milligrams nocte {period} Weight and bloods this week {period}"),
    )))
    private val conversationRun = Seal.sealed(TranscriptRun("r2", "s2", "parakeet", "Parakeet TDT 0.6B v2", "a".repeat(64), emptyMap(), "b".repeat(64), now, now, RunStatus.DONE, listOf(
        Segment(0, 3_000, "How have the last few days been on the ward?"),
        Segment(3_400, 9_000, "Better. I'm sleeping about six hours now. The voices are quieter, mostly at night."),
        Segment(9_500, 11_000, "Are they telling you to do anything?"),
        Segment(11_400, 14_000, "No, not anymore. They just comment."),
    )))

    private val sessionRows = listOf(
        SessionRow(session("s9", "Session 8 Nov, 14:05", SessionMode.CONVERSATION, 0).copy(status = SessionStatus.READY), null, JobProgress("s9", "Transcribing", 0.42f)),
        SessionRow(session("s1", "Ward review dictation", SessionMode.DICTATION, 2), dictationRun, null),
        SessionRow(session("s2", "Session 7 Nov, 10:30", SessionMode.CONVERSATION, 26), conversationRun, null),
        SessionRow(session("s3", "telehealth-2026-11-06.m4a", SessionMode.CONVERSATION, 52, SessionSource.IMPORTED, recovered = false), conversationRun, null),
        SessionRow(session("s4", "Session 5 Nov, 16:12", SessionMode.CONVERSATION, 75, recovered = true), conversationRun, null),
    )

    @Test fun sessionsHome() = tabShot("01_sessions.png", Tab.Sessions) {
        SessionsScreen(SessionsUi(sessionRows, speechReady = true), SessionMode.DICTATION, {}, {}, {}, {}, {})
    }

    @Test fun sessionsWhileRecording() = tabShot("02_sessions_recording.png", Tab.Sessions) {
        SessionsScreen(SessionsUi(sessionRows.drop(1), true, RecordingState("x", SessionMode.CONVERSATION, active = true, recordedMs = 754_000)), SessionMode.CONVERSATION, {}, {}, {}, {}, {})
    }

    @Test fun sessionsEmptyNoModels() = tabShot("03_sessions_empty.png", Tab.Sessions) {
        SessionsScreen(SessionsUi(emptyList(), speechReady = false), SessionMode.CONVERSATION, {}, {}, {}, {}, {})
    }

    @Test fun recording() = shot("04_recording.png") {
        RecordingScreen(RecordingState("x", SessionMode.CONVERSATION, active = true, recordedMs = 754_000,
            levels = List(48) { ((kotlin.math.sin(it / 3.0) + 1) / 2 * 0.8).toFloat() },
            live = conversationRun.segments.map { LiveLine(it.startMs, it.text) }), {}, {}, {}, {})
    }

    private val actions = SessionActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {})

    @Test fun dictationSession() = shot("05_session_dictation.png") {
        SessionScreen(SessionUi(sessionRows[1].session, listOf(dictationRun), engines = listOf("medasr" to "Google MedASR", "parakeet" to "Parakeet TDT 0.6B v2"),
            player = PlayerState("a", true, 8_000, 21_000)), actions)
    }

    @Test fun dictationSessionRaw() = shot("06_session_dictation_raw.png") {
        SessionScreen(SessionUi(sessionRows[1].session, listOf(dictationRun), readable = false), actions)
    }

    @Test fun conversationSessionTwoRuns() = shot("07_session_two_runs.png") {
        val second = conversationRun.copy(id = "r3", engine = "medasr", engineTitle = "Google MedASR", startedAtMillis = now + 600_000)
        SessionScreen(SessionUi(sessionRows[2].session, listOf(conversationRun, second), selectedRunId = "r2"), actions)
    }

    @Test fun exportSheet() = shot("08_export_sheet.png") {
        SessionScreen(SessionUi(sessionRows[3].session, listOf(conversationRun)), actions, SessionSheet.Export)
    }

    @Test fun exportConfirm() = shot("09_export_confirm.png") {
        SessionScreen(SessionUi(sessionRows[3].session, listOf(conversationRun)), actions, SessionSheet.Confirm(com.fadysaeed.scriber.export.ExportKind.JSON, false))
    }

    @Test fun modelsLocked() = tabShot("10_models_locked.png", Tab.Models) {
        ModelsScreen(ModelsUi(device, "E4B", "E4B", rows { if (it == "llm-gemma4-e2b") PackState.Missing else PackState.Verified }, locked = true, freeBytes = 720 * gb, neededBytes = gb), {}, {}, {}, {}, {}, {})
    }

    @Test fun modelsDownloading() {
        val llm = catalog.byRole(PackRole.LLM).first { it.variant == "E4B" }
        tabShot("11_models_downloading.png", Tab.Models) {
            ModelsScreen(ModelsUi(device, "E4B", "E4B", rows { if (it == llm.id) PackState.Partial(1_200_000_000) else if (it.startsWith("llm")) PackState.Missing else PackState.Verified },
                fetch = Fetcher.Status.Running(llm.id, "gemma-4-E4B-it.litertlm", 2_050_000_000, 4_510_000_000, false), freeBytes = 720 * gb, neededBytes = 3 * gb), {}, {}, {}, {}, {}, {})
        }
    }

    private val report = LabReport(now, "0.0.6", device, thermalAfter = "cool",
        vault = VaultRun(true, 64, 200.6, 524.6, "Encrypted size on disk 64 MB"),
        asr = listOf(
            AsrRun("parakeet", "Parakeet TDT 0.6B v2", 4, 2191, 127.2, 7945, 262, 9, listOf(AsrSample("0.wav", 43.8, 2909, 0.015, "Exam type CT chest PE protocol period", ""))),
            AsrRun("medasr", "Google MedASR", 4, 759, 127.2, 3854, 262, 12, listOf(AsrSample("0.wav", 43.8, 1560, 0.0, "[EXAM TYPE] CT chest PE protocol {period}", ""))),
        ),
        vad = VadRun(127.2, 607, 15), diarization = DiarizationRun(51.2, 388, 9234, 2, 2, 8),
        llm = LlmRun("Gemma 4 E4B", "GPU", 14484, 1515, 158, 14.1, "History since last review: ..."))

    @Test fun lab() = tabShot("12_lab.png", Tab.Lab) { LabScreen(LabUi(report = report, modelsReady = true), {}, {}) }

    @Test fun settings() = tabShot("13_settings.png", Tab.Settings) {
        SettingsScreen(SettingsUi(locked = true, hosts = listOf("huggingface.co", "hf.co", "github.com", "githubusercontent.com"), vaultOk = true, version = "0.0.6", catalogVersion = 1, storageBytes = 1_340_000_000), {}, {}, {}, {})
    }

    @Test fun settingsRetention() = tabShot("14_settings_retention.png", Tab.Settings) {
        SettingsScreen(SettingsUi(settings = com.fadysaeed.scriber.data.AppSettings(retention = com.fadysaeed.scriber.core.Retention(keepForever = false, deleteAfterDays = 90), highQuality = true),
            locked = false, hosts = listOf("huggingface.co"), version = "0.0.6"), {}, {}, {}, {})
    }

    @Test fun lock() = shot("20_lock.png") { LockScreen(null) {} }

    @Test fun lockAfterACrash() = shot("21_lock_crash.png") { LockScreen(null, stoppedLastTime = true) {} }

    // Second opinion and corrections ----------------------------------------------------------

    private val base = Seal.sealed(TranscriptRun("b1", "s5", "parakeet", "Parakeet TDT 0.6B v2", "a".repeat(64), emptyMap(), "b".repeat(64), now, now, RunStatus.DONE, listOf(
        Segment(0, 4_000, "How have you been since the change to all and zapping?"),
        Segment(4_400, 9_800, "Better. Sleeping more. I still take the quetiapin at night and the sertraline in the morning."),
        Segment(10_200, 14_000, "Any side effects, weight gain, feeling slowed down?"),
        Segment(14_300, 19_000, "A bit hungry. My mum says I eat more."),
    )))
    private val second = Seal.sealed(TranscriptRun("m1", "s5", "medasr", "Google MedASR", "a".repeat(64), emptyMap(), "b".repeat(64), now, now, RunStatus.DONE, listOf(
        Segment(0, 4_000, "how have you been since the change to olanzapine"),
        Segment(4_400, 9_800, "better sleeping more i still take the quetiapine at night and the sertraline in the morning"),
        Segment(10_200, 14_000, "any side effects weight gain feeling slowed down"),
        Segment(14_300, 19_000, "a bit hungry my mom says i eat more"),
    )))
    private val reviewSession = session("s5", "Ward review, bed 4", SessionMode.CONVERSATION, 0).copy(durationMs = 19_000)
    private val reading = com.fadysaeed.scriber.core.Compare.reading(base, second, SessionMode.CONVERSATION, com.fadysaeed.scriber.core.Medicines.bundled())
    private fun reviewUi(edits: com.fadysaeed.scriber.core.EditLayer? = null) = SessionUi(reviewSession, listOf(base, second), selectedRunId = "b1",
        reading = reading, compareRun = second, edits = edits, engines = listOf("parakeet" to "Parakeet", "medasr" to "MedASR"))

    @Test fun sessionWithSecondOpinion() = shot("15_session_second_opinion.png") { SessionScreen(reviewUi(), actions) }

    @Test fun reviewSheet() = shot("16_review_sheet.png") { SessionScreen(reviewUi(), actions, SessionSheet.Review) }

    @Test fun sessionAfterCorrections() {
        val d = reading.differences.first()
        val layer = com.fadysaeed.scriber.core.EditLayer("s5", "b1", base.seal!!)
            .with(com.fadysaeed.scriber.core.WordEdit(d.from, d.to, d.segment, d.suggestion, "medicine", now))
            .keep(reading.differences.last().id, now)
        shot("17_session_corrected.png") { SessionScreen(reviewUi(layer), actions) }
    }

    @Test fun wordSheet() = shot("18_word_sheet.png") {
        val i = reading.words.indexOfFirst { it.text == "sertraline" }
        SessionScreen(reviewUi(), actions, SessionSheet.Words(i, i + 1, 1, "sertraline", "sertraline", 6_000, 6_600))
    }

    @Test fun labWithOwnScores() = tabShot("19_lab_own.png", Tab.Lab) {
        LabScreen(LabUi(report = report, modelsReady = true, own = listOf(OwnScore("Role-play review", 14.2, listOf(
            com.fadysaeed.scriber.core.Scoring.EngineScore("parakeet", "Parakeet", com.fadysaeed.scriber.core.Wer.Result(2140, 62), 9, 14),
            com.fadysaeed.scriber.core.Scoring.EngineScore("medasr", "MedASR", com.fadysaeed.scriber.core.Wer.Result(2140, 131), 13, 14),
        )))), {}, {})
    }

    private val voices = com.fadysaeed.scriber.core.SpeakerLayer("s5", "a", 2, listOf(
        com.fadysaeed.scriber.core.Turn(0, 4_000, 0), com.fadysaeed.scriber.core.Turn(4_400, 9_800, 1),
        com.fadysaeed.scriber.core.Turn(10_200, 14_000, 0), com.fadysaeed.scriber.core.Turn(14_300, 19_000, 1)), names = mapOf(1 to "Patient"))
    private fun speakersUi() = reviewUi().copy(speakers = voices, speakerOf = com.fadysaeed.scriber.core.Speakers.assign(reading.words, reading.segments, voices, "b1"))

    @Test fun sessionWithSpeakers() = shot("22_session_speakers.png") { SessionScreen(speakersUi(), actions) }

    @Test fun speakersSheet() = shot("23_speakers_sheet.png") { SessionScreen(speakersUi(), actions, SessionSheet.Speakers) }

    @Test fun speakerPartSheet() = shot("24_speaker_part.png") {
        SessionScreen(speakersUi(), actions, SessionSheet.Speaker(11, 25, 1, "Better. Sleeping more. I still take the quetiapin at night and the sertraline in the morning."))
    }
}
