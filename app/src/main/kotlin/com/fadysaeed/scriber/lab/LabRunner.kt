package com.fadysaeed.scriber.lab

import android.content.Context
import android.os.SystemClock
import com.fadysaeed.scriber.BuildConfig
import com.fadysaeed.scriber.core.ModelCatalog
import com.fadysaeed.scriber.core.ModelPack
import com.fadysaeed.scriber.core.PackRole
import com.fadysaeed.scriber.core.Wer
import com.fadysaeed.scriber.engines.Audio
import com.fadysaeed.scriber.engines.LanguageModel
import com.fadysaeed.scriber.engines.Speech
import com.fadysaeed.scriber.fetch.ModelStore
import com.fadysaeed.scriber.fetch.PackState
import com.fadysaeed.scriber.vault.Vault
import com.fadysaeed.scriber.vault.VaultSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs every check one after another and never two big models at once: each speech engine is
 * released before the next loads, and the language model loads last with everything else freed.
 * Only the public test recordings are used.
 */
class LabRunner(private val context: Context, private val catalog: ModelCatalog, private val store: ModelStore) {
    private val UNSCORED = setOf("2.wav")


    suspend fun run(onStep: (String) -> Unit): LabReport = withContext(Dispatchers.Default) {
        val problems = mutableListOf<String>()
        var report = LabReport(System.currentTimeMillis(), BuildConfig.VERSION_NAME, DeviceInfo.snapshot(context))

        onStep("Checking encryption")
        report = report.copy(vault = VaultSelfTest.run(Vault.get(context), 64).let { VaultRun(it.ok, it.megabytes, it.writeMBps, it.readMBps, it.detail) })

        val bench = ready(PackRole.BENCHMARK_AUDIO).firstOrNull()
        val clips: List<Pair<String, Audio>> = bench?.let { pack ->
            pack.files.filter { it.path.endsWith(".wav") }.map { it.path to Speech.readWave(store.file(pack, it)) }
        }.orEmpty()
        val references = bench?.let { pack -> pack.files.firstOrNull { it.path == "transcript.txt" }?.let { Wer.parseReferences(store.file(pack, it).readText()) } }.orEmpty()
        if (clips.isEmpty()) problems += "Test recordings are not downloaded yet"

        val runs = mutableListOf<AsrRun>()
        for (pack in ready(PackRole.ASR)) {
            onStep("Speech to text: ${pack.title}")
            runCatching { runs += asr(pack, clips, references) }.onFailure { problems += "${pack.title}: ${it.message}" }
            if (pack.engine == "parakeet") {
                onStep("Speech to text: Parakeet, beam search")
                runCatching { runs += asr(pack, clips, references, variant = "+beam") }.onFailure { problems += "Parakeet, beam search: ${it.message}" }
                onStep("Speech to text: Parakeet with medicine hints")
                runCatching { runs += asr(pack, clips, references, variant = "+hints") }.onFailure { problems += "Parakeet with medicine hints: ${it.message}" }
            }
        }
        report = report.copy(asr = runs)

        ready(PackRole.VAD).firstOrNull()?.let { pack ->
            onStep("Voice activity")
            runCatching {
                val vad = Speech.vad(store.file(pack, pack.files.first()))
                try {
                    val all = concat(clips.map { it.second })
                    val t = SystemClock.elapsedRealtime()
                    val n = Speech.countSpeechSegments(vad, all)
                    report = report.copy(vad = VadRun(all.seconds, SystemClock.elapsedRealtime() - t, n))
                } finally { vad.release() }
            }.onFailure { problems += "Voice activity: ${it.message}" }
        }

        val sample = catalog.packs.firstOrNull { it.engine == "parakeet" }?.takeIf { store.state(it) is PackState.Verified }
            ?.let { p -> p.files.firstOrNull { it.path == "sample.wav" }?.let { Speech.readWave(store.file(p, it)) } }
        if (sample != null && clips.isNotEmpty() && ready(PackRole.VAD).isNotEmpty() && com.fadysaeed.scriber.work.SpeakerFinder.ready(context)) {
            onStep("Speaker turns")
            runCatching {
                // Two different voices back to back, found exactly the way a session's are.
                val audio = concat(listOf(clips.first().second, sample))
                val t0 = SystemClock.elapsedRealtime()
                val lines = com.fadysaeed.scriber.work.stretches(context, audio.samples)
                val t1 = SystemClock.elapsedRealtime()
                val prints = com.fadysaeed.scriber.work.SpeakerFinder.printsFor(context, audio.samples, lines, 2, (audio.seconds * 1000).toLong()) {}
                val turns = com.fadysaeed.scriber.core.Speakers.smooth(com.fadysaeed.scriber.core.Pieces.labelWithModel(prints.pieces, prints.modelTurns, 2))
                val t2 = SystemClock.elapsedRealtime()
                report = report.copy(diarization = DiarizationRun(audio.seconds, t1 - t0, t2 - t1, turns.map { it.speaker }.distinct().size, 2,
                    com.fadysaeed.scriber.core.Speakers.changes(turns), com.fadysaeed.scriber.core.Speakers.changes(prints.modelTurns)))
            }.onFailure { problems += "Speaker turns: ${it.message}" }
        }

        ready(PackRole.LLM).firstOrNull()?.let { pack ->
            onStep("Language model: loading ${pack.title}")
            System.gc()
            runCatching {
                val dir = File(context.cacheDir, "litertlm").apply { mkdirs() }
                val g = LanguageModel.generate(store.file(pack, pack.files.first()), dir, PROMPT)
                report = report.copy(llm = LlmRun(pack.title, g.backend, g.loadMs, g.firstPieceMs, g.pieces, g.piecesPerSecond, g.text, g.fellBackFrom?.let { "Fell back to CPU. $it" } ?: ""))
            }.onFailure { problems += "Language model: ${it.javaClass.simpleName} ${it.message}" }
        }

        report.copy(thermalAfter = DeviceInfo.thermal(context), problems = problems)
    }

    private fun ready(role: PackRole): List<ModelPack> = catalog.byRole(role).filter { store.state(it) is PackState.Verified }

    private fun asr(pack: ModelPack, clips: List<Pair<String, Audio>>, refs: Map<String, String>, variant: String = ""): AsrRun {
        val threads = Speech.threads()
        val t0 = SystemClock.elapsedRealtime()
        val hints = if (variant == "+hints") com.fadysaeed.scriber.engines.MedicineHints.prepare(context, store.dir(pack), com.fadysaeed.scriber.data.SessionStore.get(Vault.get(context)).personalWords()) else null
        val engine = (pack.engine ?: error("no engine")) + variant
        val recognizer = Speech.recognizer(engine, store.dir(pack), threads, hints)
        val loadMs = SystemClock.elapsedRealtime() - t0
        try {
            val samples = clips.map { (name, audio) ->
                val t = SystemClock.elapsedRealtime()
                val heard = Speech.transcribe(recognizer, audio)
                val ms = SystemClock.elapsedRealtime() - t
                val ref = refs[name].orEmpty()
                val w = Wer.compute(ref, heard)
                AsrSample(name, audio.seconds, ms, w.rate, heard, ref) to w
            }
            return AsrRun(
                engine = engine, title = pack.title + when (variant) { "+hints" -> " + medicine hints"; "+beam" -> ", beam search"; else -> "" }, threads = threads, loadMs = loadMs,
                audioSeconds = clips.sumOf { it.second.seconds },
                decodeMs = samples.sumOf { it.first.decodeMs },
                // 2.wav's published reference has a sentence that is not in the audio, so it is shown but not scored.
                referenceWords = samples.filter { it.first.file !in UNSCORED }.sumOf { it.second.referenceWords },
                errors = samples.filter { it.first.file !in UNSCORED }.sumOf { it.second.errors },
                samples = samples.map { it.first },
            )
        } finally {
            recognizer.release()
        }
    }

    /** What the note check found on one invented consultation. */
    data class ScriptCheck(
        val name: String, val factsMs: Long, val noteMs: Long, val facts: Int, val doubts: List<String>, val rejected: List<String>,
        val marked: List<String>, val red: List<String>, val found: List<String>, val missing: List<String>, val wrong: List<String>, val note: String,
    ) {
        val expected: Int get() = found.size + missing.size
    }

    /** What the Lab's note check found, on invented consultations only. */
    data class NoteCheck(val model: String, val backend: String, val loadMs: Long, val fellBack: String?, val scripts: List<ScriptCheck>) {
        fun toText() = buildString {
            appendLine("Note check: $model on $backend${fellBack?.let { " ($it)" } ?: ""}, load ${loadMs / 1000.0} s")
            for (c in scripts) {
                appendLine()
                appendLine("== ${c.name}: facts ${c.factsMs / 1000.0} s, note ${c.noteMs / 1000.0} s")
                appendLine("Found ${c.found.size} of ${c.expected} that must be there; ${c.wrong.size} that must not")
                c.missing.forEach { appendLine("Missing: $it") }
                c.wrong.forEach { appendLine("Should not be there: $it") }
                appendLine("${c.facts} facts kept (${c.doubts.size} to check), ${c.rejected.size} thrown out; ${c.marked.size} sentences amber, ${c.red.size} red")
                c.doubts.forEach { appendLine("To check: $it") }
                c.rejected.forEach { appendLine("Thrown out: $it") }
                c.red.forEach { appendLine("Red: $it") }
                c.marked.forEach { appendLine("Amber: $it") }
                appendLine(); appendLine(c.note)
            }
        }
    }

    /**
     * Writes notes from invented consultations (no patient), the same way sessions get theirs, and
     * scores each against the things it must and must not hold.
     */
    suspend fun noteCheck(onStep: (String) -> Unit): NoteCheck = withContext(Dispatchers.Default) {
        val model = com.fadysaeed.scriber.work.NoteWriter.model(context) ?: error("Download a note writer (Gemma) in Models first")
        val medicines = com.fadysaeed.scriber.core.Medicines.bundled()
        onStep("Note check: loading ${model.first}")
        val t0 = SystemClock.elapsedRealtime()
        LanguageModel.Session.open(model.second, java.io.File(context.cacheDir, "litertlm")).use { llm ->
            val loaded = SystemClock.elapsedRealtime()
            val pipeline = com.fadysaeed.scriber.core.NotePipeline(medicines) { p -> kotlinx.coroutines.runBlocking { llm.ask(p) } }
            val templates = com.fadysaeed.scriber.core.Templates.builtIns()
            val scripts = com.fadysaeed.scriber.core.RolePlay.FILES.map { file ->
                val play = com.fadysaeed.scriber.core.RolePlay.bundled(file)
                val template = templates.first { it.id == if (play.name == "adhd") "adhd" else "inpatient" }
                val t1 = SystemClock.elapsedRealtime()
                onStep("Note check, ${play.name}: who is speaking")
                val source = pipeline.withRoles(pipeline.lines(play.lines()))
                val (facts, rejected) = pipeline.facts(source, template) { p -> onStep("Note check, ${play.name}: facts ${(p * 100).toInt()}%") }
                val t2 = SystemClock.elapsedRealtime()
                val sections = pipeline.note(template, facts) { p -> onStep("Note check, ${play.name}: writing and checking ${(p * 100).toInt()}%") }
                val t3 = SystemClock.elapsedRealtime()
                val text = com.fadysaeed.scriber.core.NoteText.render(sections)
                val score = play.score(text)
                val all = sections.flatMap { it.sentences }
                ScriptCheck(play.name, t2 - t1, t3 - t2, facts.size, facts.filter { it.doubt != null }.map { "${it.text} (${it.doubt})" },
                    rejected.map { "${it.reason}: ${it.raw}" }, all.filter { it.warning != null && !it.severe }.map { "${it.warning}: ${it.text}" },
                    all.filter { it.severe }.map { "${it.warning}: ${it.text}" }, score.found, score.missing, score.wrong, text)
            }
            NoteCheck(model.first, llm.backend, loaded - t0, llm.fellBackFrom, scripts)
        }
    }

    /**
     * Times each engine on the same clip of test speech with different numbers of cores, and keeps
     * the fastest for each. Same models, same audio: only the time changes. Nothing is kept from the
     * transcripts made here.
     */
    suspend fun speedCheck(onStep: (String) -> Unit): com.fadysaeed.scriber.data.ThreadPlan = withContext(Dispatchers.Default) {
        val bench = ready(PackRole.BENCHMARK_AUDIO).firstOrNull() ?: error("Download the models first")
        val wavs = bench.files.filter { it.path.endsWith(".wav") }.map { Speech.readWave(store.file(bench, it)) }
        // About thirty seconds of speech.
        val clip = concat(wavs).let { a -> Audio(a.samples.copyOf(minOf(a.samples.size, 30 * Speech.SAMPLE_RATE)), Speech.SAMPLE_RATE) }
        val cores = Runtime.getRuntime().availableProcessors()
        val options = listOf(2, 3, 4, 6, 8).filter { it <= cores }
        val timings = LinkedHashMap<String, Map<Int, Long>>()
        fun best(m: Map<Int, Long>) = m.minByOrNull { it.value }?.key ?: Speech.threads()
        for (pack in ready(PackRole.ASR)) {
            val engine = pack.engine ?: continue
            val t = LinkedHashMap<Int, Long>()
            for (n in options) {
                onStep("Speed: ${pack.title}, $n cores")
                val rec = Speech.recognizer(engine, store.dir(pack), n)
                try {
                    Speech.decode(rec, Audio(clip.samples.copyOf(minOf(clip.samples.size, 5 * Speech.SAMPLE_RATE)), Speech.SAMPLE_RATE)) // warm up
                    val t0 = SystemClock.elapsedRealtime()
                    Speech.decode(rec, clip)
                    t[n] = SystemClock.elapsedRealtime() - t0
                } finally { rec.release() }
            }
            timings[engine] = t
        }
        if (com.fadysaeed.scriber.work.SpeakerFinder.ready(context)) {
            val seg = ready(PackRole.SEGMENTATION).first(); val emb = ready(PackRole.SPEAKER_EMBEDDING).first()
            val t = LinkedHashMap<Int, Long>()
            for (n in options) {
                onStep("Speed: speaker model, $n cores")
                val d = Speech.diarizer(store.file(seg, seg.files.first()), store.file(emb, emb.files.first()), speakers = 2, threads = n)
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    d.process(clip.samples)
                    t[n] = SystemClock.elapsedRealtime() - t0
                } finally { d.release() }
            }
            timings["speakers"] = t
        }
        com.fadysaeed.scriber.data.ThreadPlan(timings.mapValues { best(it.value) }, timings, clip.seconds, System.currentTimeMillis())
    }

    private fun concat(parts: List<Audio>): Audio {
        parts.firstOrNull { it.sampleRate != Speech.SAMPLE_RATE }?.let { error("Test audio is ${it.sampleRate} Hz, expected ${Speech.SAMPLE_RATE}") }
        val total = parts.sumOf { it.samples.size }
        val out = FloatArray(total)
        var at = 0
        for (p in parts) { p.samples.copyInto(out, at); at += p.samples.size }
        return Audio(out, Speech.SAMPLE_RATE)
    }

    companion object {
        /** A made-up ward conversation, so the speed test resembles the real job. */
        const val PROMPT = """You are helping a consultant psychiatrist write an inpatient review note.
Use only facts from the transcript below. Do not add anything that was not said.
Write three short paragraphs: history since last review, mental state, and plan.

Transcript:
Doctor: How have the last few days been on the ward?
Patient: Better. I'm sleeping about six hours now. The voices are quieter, mostly at night.
Doctor: Are they telling you to do anything?
Patient: No, not anymore. They just comment.
Doctor: You're on olanzapine 15 milligrams at night. Any side effects?
Patient: I'm hungrier, and a bit slow in the mornings.
Doctor: Any thoughts of harming yourself or anyone else?
Patient: No. I want to go home to my sister's.
Doctor: We'll keep the dose the same, check your weight and bloods this week, and talk to your sister about leave on the weekend."""
    }
}
