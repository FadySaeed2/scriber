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
import com.fadysaeed.scriber.core.CleanTranscript
import com.fadysaeed.scriber.core.Compare
import com.fadysaeed.scriber.core.Difference
import com.fadysaeed.scriber.core.Document
import com.fadysaeed.scriber.core.EditLayer
import com.fadysaeed.scriber.core.MedicineHit
import com.fadysaeed.scriber.core.Medicines
import com.fadysaeed.scriber.core.PersonalWords
import com.fadysaeed.scriber.core.Reading
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Scoring
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.SpeakerMove
import com.fadysaeed.scriber.core.Speakers
import com.fadysaeed.scriber.work.SpeakerFinder
import com.fadysaeed.scriber.core.TranscriptFormats
import com.fadysaeed.scriber.core.WordEdit
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
    private data class Prefs(val wifiOnly: Boolean = true)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val device = DeviceInfo.snapshot(app)
            val prefs = Records(vault).get("prefs", Prefs.serializer()) ?: Prefs()
            val report = runCatching { Records(vault).get("lab_report", LabReport.serializer()) }.getOrNull()
            _models.update { it.copy(device = device, wifiOnly = prefs.wifiOnly) }
            removeNoteWriter()
            _lab.update { it.copy(report = report, speed = store.threadPlan().takeIf { p -> p.measuredAtMillis > 0 }) }
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

    private var medicineCache: Pair<PersonalWords, Medicines>? = null
    private fun medicines(): Medicines {
        val p = store.personalWords()
        medicineCache?.let { (cp, m) -> if (cp == p) return m }
        return Medicines.bundled(p).also { medicineCache = p to it }
    }

    private var readingCache: Triple<String, String?, Reading>? = null

    private fun loadSession(id: String) {
        val s = store.session(id) ?: run { _session.value = SessionUi(); return }
        val engines = catalog.byRole(PackRole.ASR).filter { models.state(it) is PackState.Verified }.map { it.engine!! to it.title }
        val runs = store.runs(id)
        val done = runs.filter { it.status == RunStatus.DONE }
        val settings = store.settings()
        val current = _session.value.takeIf { it.session?.id == id }
        // The main transcript is the base engine's latest, unless Fady picked another one.
        val selected = current?.selectedRunId?.takeIf { sel -> runs.any { it.id == sel } }
            ?: done.lastOrNull { it.engine == settings.baseEngine }?.id ?: done.lastOrNull()?.id ?: runs.lastOrNull()?.id
        val run = runs.firstOrNull { it.id == selected }
        val compare = run?.takeIf { it.status == RunStatus.DONE }?.let { r ->
            done.lastOrNull { it.engine == settings.secondEngine && it.engine != r.engine } ?: done.lastOrNull { it.engine != r.engine && !it.engine.startsWith(r.engine) }
        }
        val reading = if (run?.status == RunStatus.DONE) {
            val cached = readingCache?.takeIf { it.first == run.id && it.second == compare?.id && medicineCache?.first == store.personalWords() }
            cached?.third ?: runCatching { Compare.reading(run, compare, s.mode, medicines()) }.getOrNull()?.also { readingCache = Triple(run.id, compare?.id, it) }
        } else null
        val edits = run?.let { store.edits(id, it.id) }
        val missing = if (done.isNotEmpty() && done.none { it.engine == settings.secondEngine } && engines.any { it.first == settings.secondEngine }) settings.secondEngine else null
        val speakers = if (s.mode != SessionMode.DICTATION) store.speakers(id) else null
        val speakerOf = if (speakers != null && reading != null && run != null) Speakers.assign(reading.words, reading.segments, speakers, run.id) else null
        _session.update {
            it.copy(session = s, runs = runs, selectedRunId = selected, job = Jobs.progress.value[id], engines = engines,
                reading = reading, compareRun = compare, edits = edits, missingSecond = missing,
                speakers = speakers, speakerOf = speakerOf, canFindSpeakers = s.mode != SessionMode.DICTATION && SpeakerFinder.ready(getApplication()),
                allDifferences = settings.showAllDifferences,
                prompts = store.prompts(), promptId = settings.promptFor(s.mode), copyTimes = settings.copyTimes, copyNoticeSeen = settings.copyNoticeSeen,
                secondPending = missing != null && (store.runs(id).any { it.engine == missing && it.status == RunStatus.RUNNING } ||
                    Jobs.pending(getApplication()).any { it.kind == Jobs.TRANSCRIBE && it.sessionId == id && it.engine == missing }))
        }
    }

    /** Re-reads models, lock, storage and sessions. Call off the main thread. */
    private fun refresh() {
        val app = getApplication<Application>()
        val rows = catalog.packs.map { PackRow(it, models.state(it), models.bytesOnPhone(it)) }
        val locked = FetchLock.isLocked(vault)
        _models.update { ui ->
            val selected = catalog.packs.filter { it.required }
            ui.copy(rows = rows, locked = locked, freeBytes = DeviceInfo.freeStorage(app),
                neededBytes = DevicePlan.bytesNeeded(selected) { f -> selected.firstOrNull { f in it.files }?.let { p -> models.bytesOnPhone(p, f) } ?: 0 })
        }
        _settings.update { it.copy(locked = locked, settings = store.settings(), storageBytes = vault.root.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() }) }
        runCatching { storage() }
        _lab.update { it.copy(modelsReady = _models.value.allReady) }
        _settings.update { it.copy(medicines = runCatching { medicines().size }.getOrDefault(0), personalWords = store.personalWords().let { p -> p.words.size + p.heardAs.size },
            prompts = runCatching { store.prompts() }.getOrDefault(emptyList())) }
        runCatching { loadOwnScores() }
        loadSessions()
    }

    fun refreshAsync() = viewModelScope.launch(Dispatchers.IO) { refresh() }
    fun dismissToast() { _toast.value = null }
    fun toastMicDenied() = say("Scriber needs the microphone to record. Allow it in Android settings, or import audio instead.")
    fun toastError(t: Throwable) = say("Could not export: ${t.message ?: t.javaClass.simpleName}")
    fun toastSaved() = say("Saved")
    fun toastCopied() = say("Copied")
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

    fun selectRun(runId: String) {
        _session.update { it.copy(selectedRunId = runId) }
        _session.value.session?.id?.let { id -> viewModelScope.launch(Dispatchers.IO) { loadSession(id) } }
    }
    fun setShowEdited(on: Boolean) = _session.update { it.copy(showEdited = on) }

    // Corrections ---------------------------------------------------------------------------

    /** Applies [change] to the selected run's correction layer and saves it. */
    private fun editLayer(change: (EditLayer) -> EditLayer) = viewModelScope.launch(Dispatchers.IO) {
        val ui = _session.value
        val s = ui.session ?: return@launch
        val run = ui.run?.takeIf { it.status == RunStatus.DONE && it.seal != null } ?: return@launch
        val layer = store.edits(s.id, run.id) ?: EditLayer(s.id, run.id, run.seal!!)
        store.saveEdits(change(layer))
        loadSession(s.id)
    }

    /** Remembers what the engine heard for a medicine Fady put in, so it is suggested next time. */
    private fun learn(heard: String, medicine: String) {
        val k = Medicines.key(heard)
        if (k.isEmpty() || k == Medicines.key(medicine)) return
        val p = store.personalWords()
        store.savePersonalWords(p.copy(heardAs = p.heardAs + (k to medicine)))
    }

    fun acceptDifference(d: Difference) = editLayer { layer ->
        if (d.medicine != null && d.baseText.isNotBlank()) learn(d.baseText, d.medicine!!)
        layer.with(WordEdit(d.from, d.to, d.segment, d.suggestion, if (d.medicine != null) "medicine" else "second", System.currentTimeMillis()))
    }

    fun keepDifference(id: String) = editLayer { it.keep(id, System.currentTimeMillis()) }

    fun acceptMedicine(m: MedicineHit) = editLayer { layer ->
        learn(m.heard, m.canonical)
        layer.with(WordEdit(m.from, m.to, m.segment, m.canonical, "medicine", System.currentTimeMillis()))
    }

    /** Takes every medicine suggestion at once: from the second engine and misspellings. */
    fun acceptAllMedicines() = editLayer { start ->
        val ui = _session.value
        var layer = start
        var t = System.currentTimeMillis()
        for (d in ui.openDifferences.filter { it.medicine != null }) {
            if (d.baseText.isNotBlank()) learn(d.baseText, d.medicine!!)
            layer = layer.with(WordEdit(d.from, d.to, d.segment, d.suggestion, "medicine", t++))
        }
        for (m in ui.openMedicines) {
            learn(m.heard, m.canonical)
            layer = layer.with(WordEdit(m.from, m.to, m.segment, m.canonical, "medicine", t++))
        }
        layer
    }

    /** Fady's own words for [from] until [to]; optionally adds them to his dictionary. */
    fun editWords(from: Int, to: Int, segment: Int, text: String, addToDictionary: Boolean) = editLayer { layer ->
        val heard = _session.value.reading?.words?.subList(from, to)?.joinToString(" ") { it.text }.orEmpty()
        val clean = text.trim()
        if (addToDictionary && clean.isNotEmpty()) {
            val p = store.personalWords()
            if (clean !in p.words) store.savePersonalWords(p.copy(words = p.words + clean))
        }
        if (clean.isNotEmpty() && heard.isNotBlank() && (addToDictionary || medicines().contains(clean))) learn(heard, clean)
        layer.with(WordEdit(from, to, segment, clean, "typed", System.currentTimeMillis()))
    }

    fun undoEdit() = editLayer { it.undo() }

    fun markReference(on: Boolean) = editLayer { it.copy(reference = on, updatedAtMillis = System.currentTimeMillis()) }.also {
        viewModelScope.launch(Dispatchers.IO) { kotlinx.coroutines.delay(300); loadOwnScores() }
    }

    // Speakers ------------------------------------------------------------------------------

    fun findSpeakers(id: String, count: Int) {
        Jobs.speakers(getApplication(), id, count)
        say(if (count > 0) "Grouping into $count voices." else "Letting Scriber decide how many voices.")
    }

    fun hideSpeakers(id: String) = viewModelScope.launch(Dispatchers.IO) { store.removeSpeakers(id); loadSession(id) }

    fun renameSpeaker(speaker: Int, name: String) = viewModelScope.launch(Dispatchers.IO) {
        val s = _session.value.session ?: return@launch
        val layer = store.speakers(s.id) ?: return@launch
        store.saveSpeakers(layer.copy(names = layer.names + (speaker to name.trim())))
        loadSession(s.id)
    }

    /** Gives base words [from] until [to] to [speaker] (a new voice when it is past the last one). */
    fun moveToSpeaker(from: Int, to: Int, speaker: Int) = viewModelScope.launch(Dispatchers.IO) {
        val ui = _session.value
        val s = ui.session ?: return@launch
        val run = ui.run ?: return@launch
        val layer = store.speakers(s.id) ?: return@launch
        store.saveSpeakers(layer.copy(moves = layer.moves + SpeakerMove(run.id, from, to, speaker)))
        loadSession(s.id)
    }

    /** Transcribes Fady's reference sessions once more with Parakeet and the medicine hints, to score them. */
    fun tryHints() = viewModelScope.launch(Dispatchers.IO) {
        val todo = _lab.value.own.filter { it.canTryHints }
        for (o in todo) {
            Jobs.transcribe(getApplication(), o.sessionId, "parakeet+beam")
            Jobs.transcribe(getApplication(), o.sessionId, "parakeet+hints")
        }
        say(if (todo.isEmpty()) "Nothing to try." else "Transcribing ${todo.size} of your sessions with medicine hints. Scores appear here when done.")
        _lab.update { l -> l.copy(own = l.own.map { it.copy(canTryHints = false) }) }
    }

    fun secondOpinion(id: String) {
        val engine = _session.value.missingSecond ?: return
        Jobs.transcribe(getApplication(), id, engine)
        say("Getting a second opinion. The main transcript stays as it is.")
    }

    /** What an export needs: the corrected lines and everything for the full-data file. */
    fun exportMaterial(): Exporter.Material? {
        val ui = _session.value
        val s = ui.session ?: return null
        val layers = runCatching { store.allEdits(s.id) }.getOrDefault(emptyList())
        val corrected = ui.lines.map { (it.speakerName?.let { n -> "$n: " } ?: "") + it.text }
        val sp = ui.speakers
        return Exporter.Material(s, ui.run, if (ui.reading != null) Document.lines(ui.run!!, ui.reading, ui.edits, ui.speakerOf) { sp?.name(it) ?: "Speaker ${it + 1}" } else ui.lines,
            TranscriptFormats.Bundle(ui.runs.filter { it.status == RunStatus.DONE }, layers, corrected, sp))
    }

    /** Scores each engine on Fady's own sessions that he marked as right. */
    private fun loadOwnScores() {
        val meds = medicines()
        val own = store.sessions().mapNotNull { s ->
            val runs = store.runs(s.id).filter { it.status == RunStatus.DONE }
            val layer = store.allEdits(s.id).firstOrNull { it.reference } ?: return@mapNotNull null
            val base = runs.firstOrNull { it.id == layer.runId } ?: return@mapNotNull null
            val reading = Compare.reading(base, null, s.mode, meds)
            val reference = Document.lines(base, reading, layer).joinToString(" ") { it.text }
            OwnScore(s.id, s.title, s.durationMs / 60_000.0, Scoring.score(reference, runs, meds),
                canTryHints = s.hasAudio && runs.none { it.engine == "parakeet+hints" } && store.runs(s.id).none { it.engine.startsWith("parakeet+") && it.status == RunStatus.RUNNING })
        }
        _lab.update { it.copy(own = own) }
    }
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
    /** Several sessions at once, from the library. Sessions still being worked on are left alone. */
    fun deleteSessions(ids: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        player.stop()
        val busy = ids.filter { Jobs.progress.value[it] != null }
        (ids - busy.toSet()).forEach { store.deleteSession(it) }
        say("Deleted ${ids.size - busy.size} " + (if (ids.size - busy.size == 1) "session" else "sessions") + if (busy.isNotEmpty()) "; ${busy.size} still being transcribed stayed." else ".")
    }

    fun deleteAudioOf(ids: List<String>) = viewModelScope.launch(Dispatchers.IO) {
        player.stop()
        val busy = ids.filter { Jobs.progress.value[it] != null }
        (ids - busy.toSet()).forEach { store.deleteAudio(it) }
        say("Deleted the audio of ${ids.size - busy.size} " + (if (ids.size - busy.size == 1) "session" else "sessions") + ". Transcripts kept.")
    }

    fun deleteSession(id: String) = viewModelScope.launch(Dispatchers.IO) { player.stop(); store.deleteSession(id); _session.value = SessionUi() }

    fun play(fromMs: Long) {
        val name = _session.value.session?.takeIf { it.hasAudio }?.audioName ?: return
        player.play(name, fromMs)
    }

    /** Plays a short stretch around a word or a difference, then stops by itself. */
    fun playRange(fromMs: Long, toMs: Long) {
        val name = _session.value.session?.takeIf { it.hasAudio }?.audioName ?: return
        player.play(name, (fromMs - 600).coerceAtLeast(0), toMs + 600)
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

    fun setWifiOnly(on: Boolean) = viewModelScope.launch(Dispatchers.IO) { _models.update { it.copy(wifiOnly = on) }; savePrefs() }
    private fun savePrefs() { val ui = _models.value; Records(vault).put("prefs", Prefs.serializer(), Prefs(ui.wifiOnly)) }

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

    // Copying out --------------------------------------------------------------------------

    /**
     * The open session's transcript, clean, with the prompt [promptId] above it when given. Remembers
     * the prompt for this kind of session. Call off the main thread.
     */
    fun clipText(promptId: String?, extra: String? = null): String? {
        val ui = _session.value
        val s = ui.session ?: return null
        val run = ui.run?.takeIf { it.status == RunStatus.DONE } ?: return null
        val reading = ui.reading ?: return null
        val sp = ui.speakers
        val input = CleanTranscript.medicineLines(run, reading, ui.edits, ui.speakerOf) { sp?.name(it) ?: "Speaker ${it + 1}" }
        val settings = store.settings()
        val body = CleanTranscript.render(CleanTranscript.paragraphs(input.lines, s.mode), settings.copyTimes)
        val header = CleanTranscript.header(s.mode, s.createdAtMillis, s.durationMs, input.lines.mapNotNull { it.speakerName }.distinct())
        val prompt = promptId?.let { store.prompt(it)?.text }
        store.saveSettings(settings.copy(copyNoticeSeen = true, prompts = if (promptId != null) settings.prompts + (s.mode.name to promptId) else settings.prompts))
        return CleanTranscript.compose(prompt, header, body, extra.takeIf { prompt != null })
    }

    fun setCopyTimes(on: Boolean) = updateSettings { it.copy(copyTimes = on) }

    /** The prompt [mode] sessions copy with from now on. */
    fun choosePrompt(mode: SessionMode, id: String) = updateSettings { it.copy(prompts = it.prompts + (mode.name to id)) }

    private fun reloadPrompts() {
        val p = store.prompts()
        _settings.update { it.copy(prompts = p, settings = store.settings()) }
        _session.update { it.copy(prompts = p) }
    }

    fun savePrompt(p: com.fadysaeed.scriber.core.PromptTemplate) = viewModelScope.launch(Dispatchers.IO) {
        store.savePrompt(p.copy(updatedAtMillis = System.currentTimeMillis())); reloadPrompts()
    }

    /** A built-in prompt goes back to how it came; one of Fady's is deleted (and stops being anyone's first choice). */
    fun removePrompt(id: String) = viewModelScope.launch(Dispatchers.IO) {
        store.removePrompt(id)
        if (store.prompt(id) == null) { val st = store.settings(); store.saveSettings(st.copy(prompts = st.prompts.filterValues { it != id })) }
        reloadPrompts(); _session.value.session?.id?.let { loadSession(it) }
    }

    /** A new prompt, empty or a copy of [from]; returns its id. */
    fun newPrompt(from: com.fadysaeed.scriber.core.PromptTemplate?): String {
        val id = "p" + com.fadysaeed.scriber.core.Ids.new()
        val p = from?.copy(id = id, name = from.name + " (copy)", builtIn = false) ?: com.fadysaeed.scriber.core.Prompts.blank(id, "New prompt")
        store.savePrompt(p.copy(updatedAtMillis = System.currentTimeMillis())); reloadPrompts()
        return id
    }

    // Storage and backup ----------------------------------------------------------------------

    /** How much room the audio takes, and which sessions take the most. */
    private fun storage() {
        val sessions = store.sessions()
        val sized = sessions.map { s ->
            val dir = vault.file("sessions/${s.id}")
            StoredSession(s.id, s.title, dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }, s.createdAtMillis, s.hasAudio)
        }
        val audio = sessions.filter { it.hasAudio }.sumOf { s ->
            listOfNotNull(s.audioName, s.workAudioName, s.original?.vaultName).distinct().sumOf { runCatching { vault.file(it).length() }.getOrDefault(0L) } +
                vault.list(store.chunkDir(s.id)).sumOf { vault.file(it).length() }
        }
        _settings.update { it.copy(audioBytes = audio, audioSessions = sessions.count { s -> s.hasAudio }, largest = sized.filter { s -> s.hasAudio }.sortedByDescending { s -> s.bytes }.take(5)) }
    }

    /** Deletes the audio of sessions older than [days] days; their transcripts and corrections stay. */
    fun deleteOldAudio(days: Int) = viewModelScope.launch(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val old = store.sessions().filter { it.hasAudio && it.createdAtMillis < cutoff && Jobs.progress.value[it.id] == null }
        val before = _settings.value.audioBytes
        old.forEach { store.deleteAudio(it.id) }
        refresh()
        say(if (old.isEmpty()) "No audio older than $days days." else "Deleted the audio of ${old.size} sessions, about ${com.fadysaeed.scriber.core.Units.bytes((before - _settings.value.audioBytes).coerceAtLeast(0))}. Transcripts kept.")
    }

    private fun size(uri: android.net.Uri): Long = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
    }.getOrDefault(-1L)

    /** Writes everything into the file Fady chose, locked with [passphrase] (cleared afterwards). */
    fun backup(uri: android.net.Uri, passphrase: CharArray) = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        _settings.update { it.copy(backup = BackupProgress("Backing up", 0f)) }
        val r = runCatching {
            app.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                com.fadysaeed.scriber.data.Backups(vault).backup(java.io.BufferedOutputStream(out, 1 shl 16), passphrase) { f -> _settings.update { it.copy(backup = BackupProgress("Backing up", f)) } }
            }
        }
        passphrase.fill('\u0000')
        _settings.update { it.copy(backup = null) }
        r.onSuccess { say("Backed up ${it.files} files (${com.fadysaeed.scriber.core.Units.bytes(size(uri).coerceAtLeast(0))}). Keep the passphrase: without it the backup cannot be opened.") }
            .onFailure { e ->
                runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
                say("The backup failed and was removed: ${e.message ?: e.javaClass.simpleName}")
            }
    }

    /** Adds what a backup holds and this phone does not; nothing here is overwritten. */
    fun restore(uri: android.net.Uri, passphrase: CharArray) = viewModelScope.launch(Dispatchers.IO) {
        val app = getApplication<Application>()
        _settings.update { it.copy(backup = BackupProgress("Restoring", 0f)) }
        val total = size(uri)
        val r = runCatching {
            app.contentResolver.openInputStream(uri)!!.use { input ->
                com.fadysaeed.scriber.data.Backups(vault).restore(java.io.BufferedInputStream(input, 1 shl 16), total, passphrase) { f -> _settings.update { it.copy(backup = BackupProgress("Restoring", f)) } }
            }
        }
        passphrase.fill('\u0000')
        _settings.update { it.copy(backup = null) }
        store.changedOutside()
        refresh()
        r.onSuccess { say("Restored ${it.files} files" + if (it.skipped > 0) "; ${it.skipped} were already here and stayed as they are." else ".") }
            .onFailure { e -> say(when (e) {
                is com.fadysaeed.scriber.core.Backup.WrongPassphrase -> "Wrong passphrase, or not a Scriber backup. Nothing was restored."
                is com.fadysaeed.scriber.core.Backup.Damaged -> "${e.message}. What came before the damage was restored."
                else -> "The restore stopped: ${e.message ?: e.javaClass.simpleName}"
            }) }
    }

    /**
     * Scriber no longer writes notes on the phone: the language model files, its cache, and the
     * notes, facts and templates kept from before are deleted once.
     */
    private fun removeNoteWriter() {
        val app = getApplication<Application>()
        val freed = runCatching { models.removeUnknown(catalog) }.getOrDefault(0L)
        for (d in listOf("litertlm", "llm")) runCatching { java.io.File(app.cacheDir, d).deleteRecursively() }
        val notes = runCatching { store.removeNoteRecords() }.getOrDefault(false)
        if (freed > 0 || notes) say("Removed the note writer" + if (freed > 0) " and freed ${com.fadysaeed.scriber.core.Units.bytes(freed)}" else "")
    }

    fun runSpeedCheck() {
        if (_lab.value.step != null) return
        viewModelScope.launch {
            _lab.update { it.copy(step = "Starting") }
            val plan = runCatching { LabRunner(getApplication(), catalog, models).speedCheck { step -> _lab.update { it.copy(step = step) } } }
            plan.onSuccess { p -> withContext(Dispatchers.IO) { store.saveThreadPlan(p) }; say("Saved: every job now uses the fastest setting for this phone.") }
                .onFailure { say("Speed check stopped: ${it.message}") }
            _lab.update { it.copy(step = null, speed = plan.getOrNull() ?: it.speed) }
        }
    }

    override fun onCleared() { player.stop(); super.onCleared() }
}
