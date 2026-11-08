package com.fadysaeed.scriber.data

import com.fadysaeed.scriber.core.EditLayer
import com.fadysaeed.scriber.core.PersonalWords
import com.fadysaeed.scriber.core.Retention
import com.fadysaeed.scriber.core.SpeakerLayer
import com.fadysaeed.scriber.core.VoicePrints
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Seal
import com.fadysaeed.scriber.core.Session
import com.fadysaeed.scriber.core.SessionMode
import com.fadysaeed.scriber.core.TranscriptRun
import com.fadysaeed.scriber.vault.Records
import com.fadysaeed.scriber.vault.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** App-wide settings, kept encrypted. */
@Serializable
data class AppSettings(
    val retention: Retention = Retention(),
    /** Record at 48 kHz instead of 16 kHz (three times the space; speech models still read 16 kHz). */
    val highQuality: Boolean = false,
    /** The engine whose transcript is the one Fady reads and corrects, in both modes. */
    val baseEngine: String = "parakeet",
    /** The engine that runs after it on every session, as a second opinion. */
    val secondEngine: String = "medasr",
    val secondOpinion: Boolean = true,
    /** Voices to look for in a conversation; 0 lets the model decide. */
    val speakers: Int = 2,
    /** Find who is speaking in every new conversation, while it is transcribed. */
    val findSpeakers: Boolean = true,
) {
    fun engineFor(@Suppress("UNUSED_PARAMETER") mode: SessionMode) = baseEngine
    /** The second engine to run after the base one, or null when the second opinion is off. */
    val then: String? get() = secondEngine.takeIf { secondOpinion && it != baseEngine }
}

/**
 * Where sessions and runs live in the vault:
 * - records/sessions/<id>.json          the session
 * - records/runs/<sessionId>/<runId>.json   each transcription run, sealed when done
 * - sessions/<id>/chunks/<n>.pcm         recording in progress (5-second pieces)
 * - sessions/<id>/audio.wav              the finished recording
 * - sessions/<id>/audio16k.wav           16 kHz copy for the models, when the original is not 16 kHz
 * - sessions/<id>/original.<ext>         an imported file, byte for byte
 */
class SessionStore(private val vault: Vault) {
    private val records = Records(vault)
    private val _changes = MutableStateFlow(0L)
    /** Ticks whenever anything is saved, so screens can reload. */
    val changes: StateFlow<Long> = _changes

    private fun touched() { _changes.value = _changes.value + 1 }

    fun sessions(problems: MutableList<String>? = null): List<Session> =
        records.all("sessions", Session.serializer(), problems).sortedByDescending { it.createdAtMillis }

    fun session(id: String): Session? = records.get("sessions/$id", Session.serializer())

    @Synchronized
    fun save(session: Session) { records.put("sessions/${session.id}", Session.serializer(), session); touched() }

    @Synchronized
    fun update(id: String, change: (Session) -> Session): Session? {
        val s = session(id) ?: return null
        val n = change(s)
        save(n)
        return n
    }

    fun runs(sessionId: String): List<TranscriptRun> =
        records.all("runs/$sessionId", TranscriptRun.serializer()).sortedBy { it.startedAtMillis }

    /** Saves a run. A run that is already sealed on disk is never overwritten. */
    @Synchronized
    fun saveRun(run: TranscriptRun) {
        val existing = records.get("runs/${run.sessionId}/${run.id}", TranscriptRun.serializer())
        check(existing?.seal == null) { "Run ${run.id} is sealed and cannot be changed" }
        val toWrite = if (run.status == RunStatus.DONE) Seal.sealed(run) else run
        records.put("runs/${run.sessionId}/${run.id}", TranscriptRun.serializer(), toWrite)
        touched()
    }

    fun chunkDir(id: String) = "sessions/$id/chunks"
    fun chunkName(id: String, index: Int) = "${chunkDir(id)}/${index.toString().padStart(6, '0')}.pcm"
    fun chunks(id: String): List<String> = vault.list(chunkDir(id))
    fun audioName(id: String) = "sessions/$id/audio.wav"
    fun work16kName(id: String) = "sessions/$id/audio16k.wav"
    fun originalName(id: String, ext: String) = "sessions/$id/original.${ext.ifBlank { "bin" }}"

    fun deleteAudio(id: String) {
        update(id) { s ->
            listOfNotNull(s.audioName, s.workAudioName, s.original?.vaultName).forEach { vault.delete(it) }
            vault.deleteTree(chunkDir(id))
            s.copy(audioDeletedAtMillis = System.currentTimeMillis())
        }
    }

    /** Fady's corrections to one run, kept beside it (records/edits/<session>/<run>.json). */
    fun edits(sessionId: String, runId: String): EditLayer? = runCatching { records.get("edits/$sessionId/$runId", EditLayer.serializer()) }.getOrNull()

    fun saveEdits(layer: EditLayer) { records.put("edits/${layer.sessionId}/${layer.runId}", EditLayer.serializer(), layer); touched() }

    fun allEdits(sessionId: String): List<EditLayer> = records.all("edits/$sessionId", EditLayer.serializer())

    /** Who spoke when (records/speakers/<session>.json). */
    fun speakers(sessionId: String): SpeakerLayer? = runCatching { records.get("speakers/$sessionId", SpeakerLayer.serializer()) }.getOrNull()

    fun saveSpeakers(layer: SpeakerLayer) { records.put("speakers/${layer.sessionId}", SpeakerLayer.serializer(), layer); touched() }

    /** Turns speakers off for one session; the voice prints stay, so turning them on again is instant. */
    fun removeSpeakers(sessionId: String) { vault.deleteRecord("speakers/$sessionId.json"); touched() }

    /** Voice prints made while the main transcript was written (records/prints/<session>.json). */
    fun prints(sessionId: String): VoicePrints? = runCatching { records.get("prints/$sessionId", VoicePrints.serializer()) }.getOrNull()

    fun savePrints(p: VoicePrints) = records.put("prints/${p.sessionId}", VoicePrints.serializer(), p)

    fun personalWords(): PersonalWords = runCatching { records.get("personal_words", PersonalWords.serializer()) }.getOrNull() ?: PersonalWords()

    fun savePersonalWords(p: PersonalWords) { records.put("personal_words", PersonalWords.serializer(), p); touched() }

    fun deleteSession(id: String) {
        vault.deleteTree("sessions/$id")
        vault.deleteTree("records/runs/$id")
        vault.deleteTree("records/edits/$id")
        vault.deleteRecord("speakers/$id.json")
        vault.deleteRecord("prints/$id.json")
        vault.deleteRecord("sessions/$id.json")
        touched()
    }

    fun settings(): AppSettings = records.get("settings", AppSettings.serializer()) ?: AppSettings()
    fun saveSettings(s: AppSettings) { records.put("settings", AppSettings.serializer(), s); touched() }

    /** Deletes audio older than the retention setting allows. Transcripts stay. */
    fun applyRetention(now: Long = System.currentTimeMillis()): Int {
        val r = settings().retention
        var n = 0
        for (s in sessions()) if (s.hasAudio && r.audioExpired(s.createdAtMillis, now)) { deleteAudio(s.id); n++ }
        return n
    }

    companion object {
        @Volatile private var instance: SessionStore? = null
        fun get(vault: Vault): SessionStore = instance ?: synchronized(this) { instance ?: SessionStore(vault).also { instance = it } }
    }
}
