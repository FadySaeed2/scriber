package com.fadysaeed.scriber

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
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
import com.fadysaeed.scriber.ui.ScriberTheme
import com.fadysaeed.scriber.ui.SecurityScreen
import com.fadysaeed.scriber.ui.Tab
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every screen with sample data so the design can be reviewed without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h1500dp-night-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val catalog = ModelCatalog.bundled()
    private val gb = 1024L * 1024 * 1024
    private val device = DeviceSnapshot("Samsung", "SM-S918B", "Qualcomm SM8550", "16", 8, (11.1 * gb).toLong(), 5 * gb, 180 * gb, "cool", 0.21f)

    private fun rows(state: (String) -> PackState) = catalog.packs.map {
        val st = state(it.id)
        PackRow(it, st, when (st) { PackState.Verified -> it.totalBytes; is PackState.Partial -> st.bytesOnPhone; PackState.Missing -> 0 })
    }

    private fun shot(file: String, tab: Tab, content: @Composable () -> Unit) {
        compose.setContent { ScriberTheme(dark = true) { Frame(tab, {}) { content() } } }
        compose.onRoot().captureRoboImage("screenshots/$file")
    }

    private fun models(ui: ModelsUi, file: String) = shot(file, Tab.Models) { ModelsScreen(ui, {}, {}, {}, {}, {}, {}) }

    @Test fun modelsFirstRun() = models(
        ModelsUi(device, "E4B", "E4B", rows { PackState.Missing }, freeBytes = 180 * gb, neededBytes = (5.5 * gb).toLong()),
        "01_models_first_run.png",
    )

    @Test fun modelsDownloading() {
        val llm = catalog.byRole(PackRole.LLM).first { it.variant == "E4B" }
        models(
            ModelsUi(
                device, "E4B", "E4B",
                rows { if (it == llm.id) PackState.Partial(1_200_000_000) else if (it.startsWith("llm")) PackState.Missing else PackState.Verified },
                fetch = Fetcher.Status.Running(llm.id, "gemma-4-E4B-it.litertlm", 2_050_000_000, 4_510_000_000, false),
                freeBytes = 176 * gb, neededBytes = 3 * gb,
            ),
            "02_models_downloading.png",
        )
    }

    @Test fun modelsLocked() = models(
        ModelsUi(device, "E4B", "E4B", rows { if (it == "llm-gemma4-e2b") PackState.Missing else PackState.Verified }, locked = true, freeBytes = 170 * gb, neededBytes = gb),
        "03_models_locked.png",
    )

    @Test fun modelsFailed() = models(
        ModelsUi(device, "E2B", "E2B", rows { PackState.Missing }, fetch = Fetcher.Status.Failed("model.int8.onnx: checksum did not match the catalogue, file discarded"),
            freeBytes = 3 * gb, neededBytes = (4.2 * gb).toLong()),
        "04_models_failed_low_space.png",
    )

    private val report = LabReport(
        atMillis = 1_762_560_000_000, appVersion = "0.0.12", device = device, thermalAfter = "warm",
        vault = VaultRun(true, 64, 210.0, 340.0, "Encrypted size on disk 64 MB"),
        asr = listOf(
            AsrRun("parakeet", "Parakeet TDT 0.6B v2", 4, 2100, 117.0, 6100, 412, 37,
                listOf(AsrSample("5.wav", 22.4, 1100, 0.08, "the patient temperature is thirty seven point two degrees celsius heart rate is seventy two beats per minute", ""))),
            AsrRun("medasr", "Google MedASR", 4, 900, 117.0, 3900, 412, 19,
                listOf(AsrSample("5.wav", 22.4, 700, 0.03, "the patient temperature is thirty seven point two degrees celsius heart rate is seventy two beats per minute", ""))),
        ),
        vad = VadRun(132.0, 410, 41),
        diarization = DiarizationRun(58.0, 300, 4200, 2, 2, 5),
        llm = LlmRun("Gemma 4 E4B", "GPU", 9800, 1400, 214, 17.8,
            "History since last review: He reports sleeping about six hours and the voices are quieter, mainly at night, now commenting only without commands.\n\nMental state: Calm and engaged. Denies thoughts of harm to self or others.\n\nPlan: Continue olanzapine 15 mg nocte, weight and bloods this week, discuss weekend leave with his sister."),
    )

    @Test fun labEmpty() = shot("10_lab_empty.png", Tab.Lab) { LabScreen(LabUi(modelsReady = true), {}, {}) }
    @Test fun labRunning() = shot("11_lab_running.png", Tab.Lab) { LabScreen(LabUi(step = "Speech to text: Google MedASR", report = report, modelsReady = true), {}, {}) }
    @Test fun labResults() = shot("12_lab_results.png", Tab.Lab) { LabScreen(LabUi(report = report, modelsReady = true), {}, {}) }

    @Test fun securityLocked() = shot("20_security_locked.png", Tab.Security) {
        SecurityScreen(SecurityUi(true, listOf("huggingface.co", "hf.co", "github.com", "githubusercontent.com"), true, "Encrypted size on disk 16 MB", "0.0.12", 1), {}, {}, {})
    }

    @Test fun securityOpen() = shot("21_security_open.png", Tab.Security) {
        SecurityScreen(SecurityUi(false, listOf("huggingface.co", "hf.co", "github.com", "githubusercontent.com"), null, "", "0.0.12", 1), {}, {}, {})
    }

    @Test fun lock() {
        compose.setContent { ScriberTheme(dark = true) { LockScreen(null) {} } }
        compose.onRoot().captureRoboImage("screenshots/30_lock.png")
    }

    @Test @Config(qualifiers = "w384dp-h1500dp-notnight-xxhdpi")
    fun modelsLight() {
        compose.setContent { ScriberTheme(dark = false) { Frame(Tab.Models, {}) { ModelsScreen(ModelsUi(device, "E4B", "E4B", rows { PackState.Verified }, locked = true, freeBytes = 170 * gb, neededBytes = gb), {}, {}, {}, {}, {}, {}) } } }
        compose.onRoot().captureRoboImage("screenshots/40_models_light.png")
    }
}
