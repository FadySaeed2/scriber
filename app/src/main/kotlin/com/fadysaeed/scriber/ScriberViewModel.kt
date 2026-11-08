package com.fadysaeed.scriber

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fadysaeed.scriber.core.AllowedHosts
import com.fadysaeed.scriber.core.DevicePlan
import com.fadysaeed.scriber.core.ModelCatalog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class ScriberViewModel(app: Application) : AndroidViewModel(app) {
    private val catalog = ModelCatalog.bundled()
    private val store = ModelStore(app)
    private val vault by lazy { Vault.get(app) }

    private val _models = MutableStateFlow(ModelsUi())
    val models: StateFlow<ModelsUi> = _models
    private val _lab = MutableStateFlow(LabUi())
    val lab: StateFlow<LabUi> = _lab
    private val _security = MutableStateFlow(SecurityUi(hosts = AllowedHosts.domains, version = BuildConfig.VERSION_NAME, catalogVersion = catalog.version))
    val security: StateFlow<SecurityUi> = _security

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
            refresh()
        }
        viewModelScope.launch {
            var lastPack: String? = null
            Fetcher.status(app).collect { status ->
                _models.update { it.copy(fetch = status) }
                val pack = (status as? Fetcher.Status.Running)?.pack
                val packChanged = status is Fetcher.Status.Running && pack != lastPack
                if (status is Fetcher.Status.Running) lastPack = pack
                if (packChanged || status is Fetcher.Status.Finished || status is Fetcher.Status.Failed) {
                    withContext(Dispatchers.IO) { refresh() }
                }
            }
        }
    }

    /** Re-reads pack states, lock state and free space. Cheap: sizes and markers only. Call off the main thread. */
    private fun refresh() {
        val app = getApplication<Application>()
        val rows = catalog.packs.map { PackRow(it, store.state(it), store.bytesOnPhone(it)) }
        val locked = FetchLock.isLocked(vault)
        _models.update { ui ->
            val selected = catalog.packs.filter { it.required || it.variant == ui.chosenLlm }
            ui.copy(
                rows = rows, locked = locked,
                freeBytes = DeviceInfo.freeStorage(app),
                neededBytes = DevicePlan.bytesNeeded(selected) { f -> selected.firstOrNull { f in it.files }?.let { p -> store.bytesOnPhone(p, f) } ?: 0 },
            )
        }
        _security.update { it.copy(locked = locked) }
        _lab.update { it.copy(modelsReady = _models.value.allReady) }
    }

    fun chooseLlm(variant: String) = viewModelScope.launch(Dispatchers.IO) {
        _models.update { it.copy(chosenLlm = variant) }
        savePrefs(); refresh()
    }

    fun setWifiOnly(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        _models.update { it.copy(wifiOnly = on) }
        savePrefs()
    }

    private fun savePrefs() {
        val ui = _models.value
        Records(vault).put("prefs", Prefs.serializer(), Prefs(ui.chosenLlm, ui.wifiOnly))
    }

    fun startDownload() = viewModelScope.launch(Dispatchers.IO) {
        val ui = _models.value
        if (!ui.enoughSpace) {
            _models.update { it.copy(message = "Not enough free space on the phone") }
            return@launch
        }
        val started = Fetcher.start(getApplication(), ui.selected.map { it.pack }, ui.wifiOnly)
        _models.update { it.copy(message = if (started) null else "Downloads are locked. Unlock them in Security first.") }
    }

    fun cancelDownload() = Fetcher.cancel(getApplication())

    /** Hashes every downloaded file again against the catalogue. */
    fun verifyAll() = viewModelScope.launch(Dispatchers.IO) {
        val downloader = ModelDownloader(store)
        val bad = mutableListOf<String>()
        for (pack in catalog.packs) {
            if (store.bytesOnPhone(pack) == 0L) continue
            for (f in pack.files) {
                if (store.file(pack, f).length() != f.size) continue
                _models.update { it.copy(verifying = "${pack.title}: ${f.path}") }
                if (!downloader.verify(pack, f) { _, _ -> }) bad += "${pack.title} ${f.path}"
            }
            val complete = pack.files.all { store.file(pack, it).length() == it.size }
            if (complete && bad.none { it.startsWith(pack.title) }) store.markVerified(pack) else store.forget(pack)
        }
        _models.update { it.copy(verifying = null, message = if (bad.isEmpty()) "Every file matches the catalogue" else "Damaged: ${bad.joinToString()}. Unlock downloads to fetch them again.") }
        refresh()
    }

    fun unlockDownloads() = viewModelScope.launch(Dispatchers.IO) { FetchLock.unlock(vault); refresh() }
    fun lockDownloads() = viewModelScope.launch(Dispatchers.IO) { Fetcher.cancel(getApplication()); FetchLock.lock(vault); refresh() }

    fun runLab() {
        if (_lab.value.step != null) return
        viewModelScope.launch {
            _lab.update { it.copy(step = "Starting") }
            val report = LabRunner(getApplication(), catalog, store).run { step -> _lab.update { it.copy(step = step) } }
            withContext(Dispatchers.IO) { Records(vault).put("lab_report", LabReport.serializer(), report) }
            _lab.update { it.copy(step = null, report = report) }
            _security.update { it.copy(vaultOk = report.vault?.ok, vaultDetail = report.vault?.detail.orEmpty()) }
        }
    }

    fun checkVault() = viewModelScope.launch(Dispatchers.IO) {
        val r = VaultSelfTest.run(vault, 16)
        _security.update { it.copy(vaultOk = r.ok, vaultDetail = r.detail) }
    }

    fun dismissMessage() = _models.update { it.copy(message = null) }

    fun refreshAsync() = viewModelScope.launch(Dispatchers.IO) { refresh() }
}
