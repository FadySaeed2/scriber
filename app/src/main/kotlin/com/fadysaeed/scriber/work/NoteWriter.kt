package com.fadysaeed.scriber.work

import android.content.Context
import com.fadysaeed.scriber.core.Compare
import com.fadysaeed.scriber.core.Document
import com.fadysaeed.scriber.core.FactSet
import com.fadysaeed.scriber.core.Ids
import com.fadysaeed.scriber.core.Medicines
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.Note
import com.fadysaeed.scriber.core.NotePipeline
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.RunStatus
import com.fadysaeed.scriber.core.Speakers
import com.fadysaeed.scriber.data.SessionStore
import com.fadysaeed.scriber.engines.LanguageModel
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.vault.Records
import com.fadysaeed.scriber.vault.Vault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Writes a session's note on the phone: facts from the corrected transcript (with speakers), then
 * the note from those facts with the chosen template. With [rewriteOnly], the saved facts (as Fady
 * left them) are used again and only the note is written, which is much quicker.
 */
class NoteWriter(private val context: Context) {
    private val vault = Vault.get(context)
    private val store = SessionStore.get(vault)

    fun run(sessionId: String, templateId: String?, rewriteOnly: Boolean, progress: (String, Float) -> Unit): Note {
        val session = store.session(sessionId) ?: error("Session not found")
        val settings = store.settings()
        val template = store.template(templateId ?: settings.noteTemplate) ?: store.templates().first()
        val medicines = Medicines.bundled(store.personalWords())
        val model = model(context) ?: error("Download a note writer (Gemma) in Models first")
        val started = System.currentTimeMillis()
        progress("Loading the note writer", 0f)
        LanguageModel.Session.open(model.second, File(context.cacheDir, "litertlm")).use { llm ->
            val pipeline = NotePipeline(medicines) { prompt -> runBlocking { llm.ask(prompt) } }
            val saved = store.facts(sessionId)
            val facts = if (rewriteOnly && saved != null) saved else {
                val run = store.runs(sessionId).filter { it.status == RunStatus.DONE }.let { done ->
                    done.lastOrNull { it.engine == settings.baseEngine } ?: done.lastOrNull()
                } ?: error("No transcript yet")
                val reading = Compare.reading(run, null, session.mode, medicines)
                val speakers = store.speakers(sessionId)
                val who = speakers?.let { Speakers.assign(reading.words, reading.segments, it, run.id) }
                val lines = Document.lines(run, reading, store.edits(sessionId, run.id), who) { speakers?.name(it) ?: "Speaker ${it + 1}" }
                val t0 = System.currentTimeMillis()
                val (f, rejected) = pipeline.facts(pipeline.lines(lines)) { p -> progress("Taking out facts", p * 0.6f) }
                FactSet(sessionId, run.id, run.seal.orEmpty(), model.first, f, rejected, System.currentTimeMillis(), System.currentTimeMillis() - t0)
                    .also { store.saveFacts(it) }
            }
            val sections = pipeline.note(template, facts.facts) { p -> progress("Writing the note", 0.6f + p * 0.4f) }
            val note = Note(Ids.new(), sessionId, template.id, template.name, facts.createdAtMillis, sections, "${model.first} (${llm.backend})",
                System.currentTimeMillis(), System.currentTimeMillis() - started)
            store.saveNote(note)
            return note
        }
    }

    companion object {
        @Serializable private data class Prefs(val chosenLlm: String? = null, val wifiOnly: Boolean = true)

        /** The note writer on the phone: the one chosen in Models if it is there, else any. (title, file) */
        fun model(context: Context): Pair<String, File>? {
            val models = ModelStore(context)
            val chosen = runCatching { Records(Vault.get(context)).get("prefs", Prefs.serializer())?.chosenLlm }.getOrNull()
            val ready = ModelCatalog.bundled().byRole(PackRole.LLM).filter { models.state(it) is PackState.Verified }
            val pack = ready.firstOrNull { it.variant == chosen } ?: ready.firstOrNull { it.variant == "E4B" } ?: ready.firstOrNull() ?: return null
            return pack.title to models.file(pack, pack.files.first())
        }
    }
}
