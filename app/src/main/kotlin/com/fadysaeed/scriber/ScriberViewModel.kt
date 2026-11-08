package com.fadysaeed.scriber

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fadysaeed.scriber.audio.Importer
import com.fadysaeed.scriber.audio.Player
import com.fadysaeed.scriber.audio.RecordingService
import com.fadysaeed.scriber.core.AllowedHosts
import com.fadysaeed.scriber.core.DevicePlan
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.data.AppSettings
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.export.Exporter
import com.fadysaeed.scriber.fetch.FetchLock
import com.fadysaeed.scriber.fetch.Fetcher
import com.fadysaeed.scriber.fetch.ModelDownloader
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.lab.DeviceInfo
import com.fadysaeed.scriber.lab.LabReport
import com.fadysaeed.scriber.lab.LabRunner
import com.fadysaeed.scriber.vault.Records
import com.fadysaeed.scriber.vault.Vault
import com.fadysaeed.scriber.vault.VaultSelfTest
import com.fadysaeed.scriber.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class ScriberViewModel(app: Application) : AndroidViewModel(app) {
    private val catalog = ModelCatalog.bundled()
    private val models = ModelStore(app)
    private val vault by lazy { Vault.get(app) }
    private val store by lazy { SessionStore.get(vault) }
    val player by lazy { Player(vault) }

    private val _models = MutableStateFlow(ModelsUi())
    val modelsUi: StateFlow<ModelsUi> = _models
    private val _lab = MutableStateFlow(LabUi())
    val lab: StateFlow<LabUi> = _lab
    private val _settings = MutableStateFlow(SettingsUi(hosts = AllowedHosts.domains, version = BuildConfig.VERSION_NAME, catalogVersion = catalog.version))
    val settings: StateFlow<SettingsUi> = _settings
    private val _sessions = MutableStateFlow(SessionsUi())
    val sessions: StateFlow<SessionsUi> = _sessions
    private val _session = MutableStateFlow(SessionUi())
    val session: StateFlow<SessionUi> = _session
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    @Serializable
    private data class Prefs(val chosenLlm: String? = null, val wifiOnly: Boolean = true)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val device = DeviceInfo.snapshot(app)
            val recommended = DevicePlan.languageModelFor(catalog, device.totalRamBytes).variant
            val prefs = Records(vault).get("prefs", Prefs.serializer()) ?: Prefs()
            val report = runCatching { Records(vault).get("lab_report", LabReport.serializer()) }.getOrNull()
            _models.update { it.copy(device = device, recommendedLlm = recommended, chosenLlm = prefs.chosenLlm ?: recommended, wifiOnly = prefs.wifiOnly) }
            _lab.update { it.copy(report = report) }
            Exporter.clean(app)
            runCatching { store.applyRetention() }
            refresh()
        }
        viewModelScope.launch {
            var lastPack: String? = null
            Fetcher.status(app).collect { status ->
                _models.update { it.copy(fetch = status) }
                val pack = (status as? Fetcher.Status.Running)?.pack
                val changed = status is Fetcher.Status.Running && pack != lastPack
                if (status is Fetcher.Status.Running) lastPack = pack
                if (changed || status is Fetcher.Status.Finished || status is Fetcher.Status.Failed) withContext(Dispatchers.IO) { refresh() }
            }
        }
        viewModelScope.launch { RecordingService.state.collect { r -> _sessions.update { it.copy(recording = r) } } }
        viewModelScope.launch(Dispatchers.IO) {
            combine(Jobs.progress, store.changes) { j, _ -> j }.collect { j ->
                loadSessions(j)
                _session.value.session?.id?.let { loadSession(it) }
            }
        }
        viewModelScope.launch { player.state.collect { p -> _session.update { it.copy(player = p) } } }
    }

    private fun loadSessions(j: Map<String, com.fadysaeed.scriber.work.JobProgress> = Jobs.progress.value) {
        val problems = mutableListOf<String>()
        val rows = store.sessions(problems).map { s -> SessionRow(s, store.runs(s.id).lastOrNull(), j[s.id]) }
        _sessions.update { it.copy(rows = rows, speechReady = _models.value.speechReady, problems = problems) }
    }

    private fun loadSession(id: String) {
        val s = store.session(id) ?: run { _session.value = SessionUi(); return }
        val engines = catalog.byRole(PackRole.ASR).filter { models.state(it) is PackState.Verified }.map { it.engine!! to it.title }
        _session.update { it.copy(session = s, runs = store.runs(id), job = Jobs.progress.value[id], engines = engines) }
    }

    /** Re-reads models, lock, storage and sessions. Call off the main thread. */
    private fun refresh() {
        val app = getApplication<Application>()
        val rows = catalog.packs.map { PackRow(it, models.state(it), models.bytesOnPhone(it)) }
        val locked = FetchLock.isLocked(vault)
        _models.update { ui ->
            val selected = catalog.packs.filter { it.required || it.variant == ui.chosenLlm }
            ui.copy(rows = rows, locked = locked, freeBytes = DeviceInfo.freeStorage(app),
                neededBytes = DevicePlan.bytesNeeded(selected) { f -> selected.firstOrNull { f in it.files }?.let { p -> models.bytesOnPhone(p, f) } ?: 0 })
        }
        _settings.update { it.copy(locked = locked, settings = store.settings(), storageBytes = vault.root.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }) }
        _lab.update { it.copy(modelsReady = _models.value.allReady) }
        loadSessions()
    }

    fun refreshAsync() = viewModelScope.launch(Dispatchers.IO) { refresh() }
    fun dismissToast() { _toast.value = null }
    fun toastMicDenied() = say("Scriber needs the microphone to record. Allow it in Android settings, or import audio instead.")
    fun toastError(t: Throwable) = say("Could not export: ${t.message ?: t.javaClass.simpleName}")
    fun toastSaved() = say("Saved")
    fun toastCopied() = say("Report copied")
    private fun say(text: String) {
        _toast.value = text
        viewModelScope.launch { kotlinx.coroutines.delay(3500); if (_toast.value == text) _toast.value = null }
    }

    // Sessions ------------------------------------------------------------------------------

    fun startRecording(mode: SessionMode): String = RecordingService.start(getApplication(), mode)
    fun pauseRecording() = RecordingService.pause(getApplication())
    fun resumeRecording() = RecordingService.resume(getApplication())
    fun stopRecording() = RecordingService.stop(getApplication())

    fun import(uri: Uri, mode: SessionMode, opened: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val id = Importer.copyIn(getApplication(), uri, mode)
            Jobs.import(getApplication(), id)
            withContext(Dispatchers.Main) { opened(id) }
        } catch (t: Throwable) {
            say("Could not import: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Restarts unfinished decoding and transcription; called each time the app comes to the front. */
    fun clearCrash() = viewModelScope.launch(Dispatchers.IO) {
        CrashLog.clear(getApplication())
        _settings.update { it.copy(crash = null) }
    }

    fun resumeJobs() = viewModelScope.launch(Dispatchers.IO) {
        _settings.update { it.copy(crash = runCatching { CrashLog.report(getApplication()) }.getOrNull()) }
        runCatching { RecordingService.recover(getApplication()) }
        runCatching { com.fadysaeed.scriber.work.Jobs.resume(getApplication()) }
    }

    fun open(id: String?) {
        if (id == null) { player.stop(); _session.value = SessionUi(); return }
        if (_session.value.session?.id != id) { player.stop(); _session.value = SessionUi() }
        viewModelScope.launch(Dispatchers.IO) { loadSession(id) }
    }

    fun selectRun(runId: String) = _session.update { it.copy(selectedRunId = runId) }
    fun setReadable(on: Boolean) = _session.update { it.copy(readable = on) }

    fun rename(id: String, title: String) = viewModelScope.launch(Dispatchers.IO) {
        val t = title.trim()
        if (t.isNotEmpty()) store.update(id) { it.copy(title = t) }
    }

    fun transcribeAgain(id: String, engine: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = store.session(id)
        if (s != null && s.workAudioName == null && s.original != null) {
            store.update(id) { it.copy(status = com.fadysaeed.scriber.core.SessionStatus.FINALIZING, problem = null) }
            Jobs.import(getApplication(), id, engine)
            return@launch
        }
        Jobs.transcribe(getApplication(), id, engine)
        say("Queued a new transcript. The earlier ones stay as they are.")
    }

    fun deleteAudio(id: String) = viewModelScope.launch(Dispatchers.IO) { player.stop(); store.deleteAudio(id); say("Audio deleted. Transcripts kept.") }
    fun deleteSession(id: String) = viewModelScope.launch(Dispatchers.IO) { player.stop(); store.deleteSession(id); _session.value = SessionUi() }

    fun play(fromMs: Long) {
        val name = _session.value.session?.takeIf { it.hasAudio }?.audioName ?: return
        player.play(name, fromMs)
    }
    fun stopPlaying() = player.stop()

    // Settings ------------------------------------------------------------------------------

    fun updateSettings(change: (AppSettings) -> AppSettings) = viewModelScope.launch(Dispatchers.IO) {
        store.saveSettings(change(store.settings())); refresh()
    }

    fun checkVault() = viewModelScope.launch(Dispatchers.IO) {
        val r = VaultSelfTest.run(vault, 16)
        _settings.update { it.copy(vaultOk = r.ok, vaultDetail = r.detail) }
    }

    // Models --------------------------------------------------------------------------------

    fun chooseLlm(variant: String) = viewModelScope.launch(Dispatchers.IO) { _models.update { it.copy(chosenLlm = variant) }; savePrefs(); refresh() }
    fun setWifiOnly(on: Boolean) = viewModelScope.launch(Dispatchers.IO) { _models.update { it.copy(wifiOnly = on) }; savePrefs() }
    private fun savePrefs() { val ui = _models.value; Records(vault).put("prefs", Prefs.serializer(), Prefs(ui.chosenLlm, ui.wifiOnly)) }

    fun startDownload() = viewModelScope.launch(Dispatchers.IO) {
        val ui = _models.value
        if (!ui.enoughSpace) { _models.update { it.copy(message = "Not enough free space on the phone") }; return@launch }
        val started = Fetcher.start(getApplication(), ui.selected.map { it.pack }, ui.wifiOnly)
        _models.update { it.copy(message = if (started) null else "Downloads are locked. Unlock them in Settings first.") }
    }

    fun cancelDownload() = Fetcher.cancel(getApplication())

    fun verifyAll() = viewModelScope.launch(Dispatchers.IO) {
        val downloader = ModelDownloader(models)
        val bad = mutableListOf<String>()
        for (pack in catalog.packs) {
            if (models.bytesOnPhone(pack) == 0L) continue
            for (f in pack.files) {
                if (models.file(pack, f).length() != f.size) continue
                _models.update { it.copy(verifying = "${pack.title}: ${f.path}") }
                if (!downloader.verify(pack, f) { _, _ -> }) bad += "${pack.title} ${f.path}"
            }
            val complete = pack.files.all { models.file(pack, it).length() == it.size }
            if (complete && bad.none { it.startsWith(pack.title) }) models.markVerified(pack) else models.forget(pack)
        }
        _models.update { it.copy(verifying = null, message = if (bad.isEmpty()) "Every file matches the catalogue" else "Damaged: ${bad.joinToString()}. Unlock downloads to fetch them again.") }
        refresh()
    }

    fun unlockDownloads() = viewModelScope.launch(Dispatchers.IO) { FetchLock.unlock(vault); refresh() }
    fun lockDownloads() = viewModelScope.launch(Dispatchers.IO) { Fetcher.cancel(getApplication()); FetchLock.lock(vault); refresh() }
    fun dismissMessage() = _models.update { it.copy(message = null) }

    fun runLab() {
        if (_lab.value.step != null) return
        viewModelScope.launch {
            _lab.update { it.copy(step = "Starting") }
            val report = LabRunner(getApplication(), catalog, models).run { step -> _lab.update { it.copy(step = step) } }
            withContext(Dispatchers.IO) { Records(vault).put("lab_report", LabReport.serializer(), report) }
            _lab.update { it.copy(step = null, report = report) }
            _settings.update { it.copy(vaultOk = report.vault?.ok, vaultDetail = report.vault?.detail.orEmpty()) }
        }
    }

    override fun onCleared() { player.stop(); super.onCleared() }
}
