package com.fadysaeed.scriber.core

/**
 * Turns a transcript into a note, with the language model only as a writer and code as the referee:
 *  0. roles: the model says which speaker is the clinician, so facts about the patient are not
 *     confused with what the clinician explained;
 *  1. facts: the model lists facts, each with the exact words that support it; code keeps only
 *     those whose words and numbers are really in that line (a medicine name that is not there is
 *     kept but marked to check);
 *  2. note: each template section is written from its facts alone, every sentence citing them;
 *  3. check: code marks sentences without a fact, with a number none of its facts has (red), or
 *     with many words not in its facts; then the model compares each sentence with its facts and
 *     code marks what it finds unsupported (amber) or contradicted (red).
 * [ask] sends one prompt to the model and returns its whole answer.
 */
class NotePipeline(private val medicines: Medicines, private val ask: (String) -> String) {

    /**
     * A transcript line as the model sees it: "L12 Speaker 1 (clinician): words". [cleaned] are
     * medicine names put in from the second opinion or the dictionary, not as the first engine heard.
     */
    data class SourceLine(
        val number: Int, val speaker: String?, val text: String, val startMs: Long, val endMs: Long,
        val role: String? = null, val cleaned: Set<String> = emptySet(),
    ) {
        val label: String get() = "L$number" + (speaker?.let { " $it" + (role?.let { r -> " ($r)" } ?: "") } ?: "")
    }

    /** [cleaned] gives, by index in [lines], the medicine names the cleaning put in. */
    fun lines(lines: List<Line>, cleaned: Map<Int, Set<String>> = emptyMap()): List<SourceLine> =
        lines.withIndex().filter { it.value.text.isNotBlank() }.mapIndexed { i, (k, l) ->
            SourceLine(i + 1, l.speakerName, l.text, l.startMs, l.endMs, cleaned = cleaned[k].orEmpty())
        }

    // Roles -------------------------------------------------------------------------------------

    fun rolePrompt(lines: List<SourceLine>, speakers: List<String>): String = buildString {
        appendLine("Below is the start of a psychiatric consultation transcript. Say who each speaker is.")
        appendLine("Answer one line per speaker, exactly: <speaker> | CLINICIAN or PATIENT or FAMILY or STAFF or OTHER")
        appendLine("The clinician is the doctor leading the consultation (asks the questions, explains, decides the plan).")
        appendLine("Speakers: ${speakers.joinToString(", ")}")
        appendLine()
        for (l in lines) appendLine("${l.label}: ${l.text}")
    }

    fun parseRoles(answer: String, speakers: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in answer.lines()) {
            val parts = raw.split('|').map { it.trim().trim('*', '-', ' ', '"') }
            if (parts.size < 2) continue
            val who = speakers.firstOrNull { it.equals(parts[0], true) } ?: continue
            val role = when (parts[1].uppercase().takeWhile { it.isLetter() }) {
                "CLINICIAN" -> "clinician"; "PATIENT" -> "patient"; "FAMILY" -> "family"; "STAFF" -> "staff"; "OTHER" -> "other"
                else -> continue
            }
            out.putIfAbsent(who, role)
        }
        return out
    }

    /** The lines with each speaker's role, when the lines say who spoke; unchanged otherwise. */
    fun withRoles(lines: List<SourceLine>): List<SourceLine> {
        val speakers = lines.mapNotNull { it.speaker }.distinct()
        if (speakers.isEmpty()) return lines
        val sample = ArrayList<SourceLine>(); var chars = 0
        for (l in lines) { if (chars > 4000) break; sample += l; chars += l.text.length + 20 }
        val roles = runCatching { parseRoles(ask(rolePrompt(sample, speakers)), speakers) }.getOrDefault(emptyMap())
        return lines.map { it.copy(role = it.speaker?.let(roles::get)) }
    }

    // Facts -------------------------------------------------------------------------------------

    /** Groups lines into pieces of about three minutes of talk, with two lines of overlap. */
    fun chunks(lines: List<SourceLine>, maxChars: Int = 2500): List<List<SourceLine>> {
        val out = ArrayList<List<SourceLine>>()
        var i = 0
        while (i < lines.size) {
            var chars = 0; var j = i
            while (j < lines.size && (j == i || chars + lines[j].text.length + 20 <= maxChars)) { chars += lines[j].text.length + 20; j++ }
            out += lines.subList(i, j)
            if (j >= lines.size) break
            i = maxOf(i + 1, j - 2)
        }
        return out
    }

    fun factPrompt(chunk: List<SourceLine>, template: NoteTemplate? = null): String = buildString {
        appendLine("You take facts out of part of a psychiatric consultation transcript, for a clinical note.")
        if (chunk.any { it.role != null }) appendLine("Each line shows who spoke; the clinician is the doctor writing the note.")
        else appendLine("The lines do not say who spoke: work it out from what is said.")
        appendLine("Write one fact per line, exactly in this form and nothing else:")
        appendLine("KIND | L<number> | the fact | \"exact words from that line\"")
        appendLine()
        appendLine("KIND is one of:")
        for (k in FactKind.entries) appendLine("${k.name}: ${k.hint}")
        appendLine()
        if (template != null) {
            appendLine("Look for everything this note needs. Its sections:")
            for (s in template.sections) appendLine("- ${s.title}: ${s.instruction}")
        }
        appendLine("Always look for: thoughts of suicide or self-harm, including their denial; harm to others; hallucinations, including voices that give commands; " +
            "alcohol, cannabis and other drugs with amounts, including their denial; the patient's current medicines with doses, and medicines tried before; " +
            "allergies and the reaction; family history; every part of the plan, with doses, dates and who will do it.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Only what was said in these lines. Never add, guess or interpret.")
        appendLine("- The quote is 1 to 15 words copied exactly from the line you cite.")
        appendLine("- A short answer (yes, no, a number) means nothing alone: write the whole fact using the question before it, cite the answer's line and quote the answer.")
        appendLine("- What the patient or family say about the patient, and what the clinician observes or decides, are facts about the patient. " +
            "What the clinician explains in general is EDUCATION, and advice to this patient is ADVICE. A medicine the clinician only explains is never one of the patient's MEDICATIONS.")
        appendLine("- A medicine the patient took from someone else (a partner, a friend) is recorded as such.")
        appendLine("- The fact is short clinical English in the third person; doses and numbers in digits (15 mg).")
        appendLine("- One idea per fact. Be thorough: a few minutes of talk usually holds 10 to 25 facts.")
        appendLine("- If nothing in these lines is a fact, write NONE.")
        appendLine()
        appendLine("Transcript:")
        for (l in chunk) appendLine("${l.label}: ${l.text}")
    }

    private fun kindOf(name: String): FactKind? {
        val n = name.trim('*', ' ', '`').uppercase().replace(' ', '_')
        return FactKind.entries.firstOrNull { it.name == n }
            ?: FactKind.entries.firstOrNull { it.name.removeSuffix("S") == n.removeSuffix("S") }
    }

    /** Facts from one model answer, checked against the lines they cite. */
    fun parseFacts(answer: String, lines: List<SourceLine>, firstId: Int): Pair<List<Fact>, List<RejectedFact>> {
        val byNumber = lines.associateBy { it.number }
        val facts = ArrayList<Fact>(); val rejected = ArrayList<RejectedFact>()
        var next = firstId
        for (raw in answer.lineSequence().map { it.trim().trimStart('-', '*', ' ') }.filter { it.isNotEmpty() && !it.equals("NONE", true) }) {
            val parts = raw.split('|').map { it.trim() }
            if (parts.size < 4) { rejected += RejectedFact(raw, "not in the expected form"); continue }
            val kind = kindOf(parts[0])
            if (kind == null) { rejected += RejectedFact(raw, "unknown kind ${parts[0]}"); continue }
            val number = parts[1].trim('*', ' ').removePrefix("L").removePrefix("l").trim().toIntOrNull()
            val line = number?.let { byNumber[it] }
            if (line == null) { rejected += RejectedFact(raw, "no such line ${parts[1]}"); continue }
            val text = parts[2]
            val quote = parts.drop(3).joinToString(" | ").trim().trim('"', '“', '”', '\'')
            if (text.isBlank()) { rejected += RejectedFact(raw, "empty fact"); continue }
            check(quote, line)?.let { rejected += RejectedFact(raw, it); continue }
            missingNumbers(text, line.text)?.let { rejected += RejectedFact(raw, it); continue }
            val doubt = missingMedicines(text, line.text)?.let { "$it: listen to the line before trusting it" }
                ?: medicinesIn(text).filter { it in line.cleaned.map(::genericOf) }.takeIf { it.isNotEmpty() }
                    ?.let { "${it.joinToString()} put in from the second opinion or the dictionary: listen to the line" }
            facts += Fact("F${next++}", kind, text, quote, line.number, line.startMs, line.endMs, doubt = doubt)
        }
        return facts to rejected
    }

    /** Null when the quote is really in the line; otherwise why not. */
    private fun check(quote: String, line: SourceLine): String? {
        val q = Wer.normalise(quote); val l = Wer.normalise(line.text)
        if (q.isEmpty()) return "empty quote"
        if (containsRun(l, q)) return null
        // Allow small slips in a long quote: 80% of its words, in order.
        var j = 0; var hit = 0
        for (w in l) if (j < q.size && w == q[j]) { hit++; j++ }
        return if (q.size >= 5 && hit >= q.size * 0.8) null else "quote not found in L${line.number}"
    }

    private fun containsRun(hay: List<String>, needle: List<String>): Boolean {
        if (needle.size > hay.size) return false
        for (i in 0..hay.size - needle.size) if (hay.subList(i, i + needle.size) == needle) return true
        return false
    }

    private fun genericOf(name: String): String = medicines.find(listOf(name))?.generic ?: name

    private fun medicinesIn(text: String): Set<String> = Scoring.medicineCounts(text, medicines).keys

    private fun missingNumbers(claim: String, source: String): String? {
        val src = Wer.normalise(source).toSet()
        val missing = Wer.normalise(claim).filter { w -> w.any(Char::isDigit) }.filter { it !in src }.distinct()
        return if (missing.isEmpty()) null else "number ${missing.joinToString()} not said there"
    }

    private fun missingMedicines(claim: String, source: String): String? {
        val extra = medicinesIn(claim) - medicinesIn(source)
        return if (extra.isEmpty()) null else "medicine ${extra.joinToString()} not heard there"
    }

    /** Null when every number and medicine in [claim] is also in [source]; otherwise what is missing. */
    fun groundedIn(claim: String, source: String): String? = missingNumbers(claim, source) ?: missingMedicines(claim, source)

    /** Takes facts from every chunk, dropping exact repeats from the overlaps. */
    fun facts(lines: List<SourceLine>, template: NoteTemplate? = null, progress: (Float) -> Unit = {}): Pair<List<Fact>, List<RejectedFact>> {
        val all = ArrayList<Fact>(); val rejected = ArrayList<RejectedFact>()
        val parts = chunks(lines)
        parts.forEachIndexed { i, chunk ->
            val (f, r) = parseFacts(ask(factPrompt(chunk, template)), chunk, all.size + 1)
            for (x in f) if (all.none { it.kind == x.kind && Wer.normalise(it.text) == Wer.normalise(x.text) }) all += x.copy(id = "F${all.size + 1}")
            rejected += r
            progress((i + 1).toFloat() / parts.size)
        }
        return all to rejected
    }

    // Writing -----------------------------------------------------------------------------------

    fun sectionPrompt(template: NoteTemplate, section: NoteSection, facts: List<Fact>): String = buildString {
        appendLine("You write one section of a psychiatric clinical note from a list of facts.")
        appendLine("Section: ${section.title}")
        appendLine("What it should contain: ${section.instruction}")
        appendLine("Style: ${template.style}")
        appendLine(when (section.form) {
            SectionForm.PROSE -> "Form: prose, one paragraph."
            SectionForm.BULLETS -> "Form: one point per line, each starting with \"- \"."
            SectionForm.NUMBERED -> "Form: a numbered list, one item per line, \"1. \", \"2. \" and so on."
        })
        appendLine("At most ${section.maxWords} words.")
        appendLine("Use only the facts below that fit what this section should contain; leave out facts that belong elsewhere.")
        appendLine("After every sentence or item, put the ids of the facts it uses in square brackets, like [F3] or [F3, F7].")
        appendLine("Every sentence states specific facts. No general or filler statements, and nothing about what was not discussed.")
        appendLine("Never write a normal or negative finding unless a fact states it.")
        appendLine("Facts marked (Information given) are what the clinician explained in general: never write them as the patient's own medicines or history.")
        appendLine("Do not write the section title. Keep medicine names, doses and numbers exactly as in the facts.")
        appendLine()
        appendLine("Facts:")
        for (f in facts) appendLine("${f.id} (${f.kind.label}): ${f.text}")
    }

    /** Sentences (or items) of a section answer, with their cited facts, checked against those facts. */
    fun parseSection(answer: String, form: SectionForm, facts: Map<String, Fact>): List<NoteSentence> {
        val cite = Regex("""\[\s*(F\d+(?:\s*[,;]\s*F?\d+)*)\s*\]""")
        val pieces: List<String> = if (form == SectionForm.PROSE) {
            // Citations put just before the full stop belong to that sentence.
            val text = answer.replace(Regex("""\s*((?:\[[^\]]*\]\s*)+)([.!?])"""), "$2 $1").replace('\n', ' ').trim()
            // A sentence runs to its full stop and takes the citations right after it.
            val sentence = Regex("""(.+?[.!?]+)((?:\s*\[[^\]]*\])*)(?=\s+[A-Z(\[]|\s*$)""", RegexOption.DOT_MATCHES_ALL)
            val out = ArrayList<String>()
            var end = 0
            for (m in sentence.findAll(text)) { out += m.value; end = m.range.last + 1 }
            if (end < text.length) out += text.substring(end)
            out
        } else answer.lines().map { it.trim().replace(Regex("""^(\d+[.)]|[-*•])\s*"""), "") }
        return pieces.map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { p ->
            val ids = cite.findAll(p).flatMap { m -> m.groupValues[1].split(',', ';').map { x -> "F" + x.trim().removePrefix("F") } }.distinct().toList()
            val text = p.replace(cite, "").replace(Regex("""\s+([.,;:!?])"""), "$1").replace(Regex("""\s{2,}"""), " ").trim()
            if (text.isEmpty() || text.all { !it.isLetterOrDigit() }) return@mapNotNull null
            val known = ids.filter { it in facts }
            if (known.isEmpty()) return@mapNotNull NoteSentence(text, known, "No fact behind this sentence", severe = missingNumbers(text, "") != null)
            val source = known.joinToString(" ") { facts.getValue(it).text + " " + facts.getValue(it).quote }
            val numbers = missingNumbers(text, source)
            val warning = numbers
                ?: missingMedicines(text, source)
                ?: known.mapNotNull { facts.getValue(it).doubt }.firstOrNull()?.let { "Rests on a fact to check: $it" }
                ?: unsupportedShare(text, source).takeIf { it > 0.6 }?.let { "Much of it is not in its facts" }
            NoteSentence(text, known, warning, severe = numbers != null)
        }
    }

    /** Share of a sentence's content words that are not in its facts (first four letters compared, so "feels" meets "feeling"). */
    fun unsupportedShare(sentence: String, source: String): Double {
        fun stem(w: String) = w.take(4)
        val src = Wer.normalise(source).map(::stem).toSet()
        val words = Wer.normalise(sentence).filter { it.length >= 4 && it !in FILLER && it.none(Char::isDigit) }
        if (words.size < 3) return 0.0
        return words.count { stem(it) !in src }.toDouble() / words.size
    }

    // Checking ----------------------------------------------------------------------------------

    fun verifyPrompt(sentences: List<NoteSentence>, facts: Map<String, Fact>): String = buildString {
        appendLine("You check sentences of a clinical note against the facts each one cites.")
        appendLine("Answer one line per sentence, exactly: S<number> | SUPPORTED or PARTLY or UNSUPPORTED or CONTRADICTED | reason in a few words")
        appendLine("SUPPORTED: everything it says is in its facts (the wording may differ).")
        appendLine("PARTLY: some of it is in its facts and some is not.")
        appendLine("UNSUPPORTED: its main claim is not in its facts.")
        appendLine("CONTRADICTED: it says something its facts say the opposite of.")
        appendLine()
        sentences.forEachIndexed { i, s ->
            appendLine("S${i + 1}: ${s.text}")
            for (id in s.factIds) facts[id]?.let { f -> appendLine("   ${f.id} (${f.kind.label}): ${f.text} (said: \"${f.quote}\")") }
        }
    }

    /** Marks sentences the model found unsupported (amber) or contradicted (red); keeps earlier marks. */
    fun applyVerdicts(answer: String, sentences: List<NoteSentence>): List<NoteSentence> {
        val verdicts = HashMap<Int, Pair<String, String>>()
        for (raw in answer.lines()) {
            val parts = raw.split('|').map { it.trim().trim('*', ' ', '-') }
            if (parts.size < 2) continue
            val n = parts[0].removePrefix("S").removePrefix("s").trim().toIntOrNull() ?: continue
            val v = parts[1].uppercase().takeWhile { it.isLetter() }
            verdicts[n - 1] = v to parts.drop(2).joinToString(" | ").trim()
        }
        return sentences.mapIndexed { i, s ->
            val (v, why) = verdicts[i] ?: return@mapIndexed s
            val reason = why.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
            when (v) {
                "CONTRADICTED" -> s.copy(warning = "Contradicts its facts$reason", severe = true)
                "UNSUPPORTED" -> s.copy(warning = s.warning ?: "Not in its facts$reason")
                "PARTLY" -> s.copy(warning = s.warning ?: "Only partly in its facts$reason")
                else -> s
            }
        }
    }

    /** Writes the note section by section from [facts] (removed facts are left out), then checks it. */
    fun note(template: NoteTemplate, facts: List<Fact>, check: Boolean = true, progress: (Float) -> Unit = {}): List<NoteSectionText> {
        val active = facts.filterNot { it.removed }
        val byId = active.associateBy { it.id }
        val out = ArrayList<NoteSectionText>()
        template.sections.forEachIndexed { i, section ->
            val mine = if (section.allFacts) active.filter { it.kind in section.kinds } + active.filter { it.kind !in section.kinds }
                else active.filter { it.kind in section.kinds }
            var sentences = when {
                mine.isEmpty() && section.omitWhenEmpty -> emptyList()
                mine.isEmpty() -> listOf(NoteSentence("Not assessed.", emptyList()))
                else -> parseSection(ask(sectionPrompt(template, section, mine)), section.form, byId)
            }
            progress((i + 0.6f) / template.sections.size)
            val toCheck = sentences.filter { it.factIds.isNotEmpty() && !it.severe }
            if (check && toCheck.isNotEmpty()) {
                val checked = runCatching { applyVerdicts(ask(verifyPrompt(toCheck, byId)), toCheck) }.getOrDefault(toCheck)
                val byText = toCheck.zip(checked).toMap()
                sentences = sentences.map { byText[it] ?: it }
            }
            if (sentences.isNotEmpty()) out += NoteSectionText(section.id, section.title, section.form, sentences)
            progress((i + 1).toFloat() / template.sections.size)
        }
        return out
    }

    private companion object {
        val FILLER = setOf(
            "with", "that", "this", "from", "have", "been", "were", "which", "their", "there", "they", "them", "about", "into",
            "also", "when", "while", "after", "before", "since", "would", "could", "should", "will", "does", "than", "then",
            "some", "more", "most", "other", "over", "under", "such", "very", "being", "what", "where", "these", "those",
            "reports", "reported", "reporting", "states", "stated", "describes", "described", "notes", "noted", "patient", "patients",
            "currently", "current", "presents", "presented", "explained", "discussed", "advised", "said", "says", "indicates",
            "indicated", "mentioned", "remains", "continues", "regarding", "including", "given", "felt", "time",
        )
    }
}

/**
 * What the note writer reads: the corrected transcript, plus the medicine fixes Fady has not looked
 * at yet (a medicine the second opinion heard where the first engine did not, and dictionary
 * spellings of misheard names). The transcript itself and Fady's corrections are not changed.
 */
object NoteInput {
    data class Result(val lines: List<Line>, /** By index in [lines]: medicine names put in. */ val cleaned: Map<Int, Set<String>>)

    fun lines(run: TranscriptRun, reading: Reading, layer: EditLayer?, speakers: List<Int>? = null, name: (Int) -> String = { "Speaker ${it + 1}" }): Result {
        val mine = layer?.edits.orEmpty()
        fun free(from: Int, to: Int, segment: Int) = mine.none { e ->
            if (from == to || e.from == e.to) e.from == from && e.segment == segment else e.from < to && from < e.to
        }
        val added = ArrayList<Pair<WordEdit, String>>()
        for (d in reading.differences) {
            val med = d.medicine ?: continue
            if (layer?.kept?.contains(d.id) == true || !free(d.from, d.to, d.segment)) continue
            added += WordEdit(d.from, d.to, d.segment, d.suggestion, "medicine", 0) to med
        }
        for (m in reading.medicines) {
            if (m.exact || !free(m.from, m.to, m.segment) || added.any { (e, _) -> e.from < m.to && m.from < e.to }) continue
            added += WordEdit(m.from, m.to, m.segment, m.canonical, "medicine", 0) to m.canonical
        }
        val all = (mine + added.map { it.first }).sortedWith(compareBy({ it.from }, { it.to }))
        val base = layer ?: EditLayer(run.sessionId, run.id, run.seal.orEmpty())
        val lines = Document.lines(run, reading, base.copy(edits = all), speakers, name)
        val cleaned = HashMap<Int, MutableSet<String>>()
        for ((e, med) in added) {
            val i = lines.indexOfFirst { l -> l.segment == e.segment && e.from >= l.from && e.from < maxOf(l.to, l.from + 1) }
            if (i >= 0) cleaned.getOrPut(i) { HashSet() } += med
        }
        return Result(lines, cleaned)
    }
}

/**
 * An invented consultation for the Lab: "Speaker: words" lines, with what the note must hold
 * ("#+ label | pattern") and must not ("#- label | pattern").
 */
class RolePlay(val name: String, val turns: List<Pair<String, String>>, val expected: List<Pair<String, Regex>>, val forbidden: List<Pair<String, Regex>>) {
    data class Score(val found: List<String>, val missing: List<String>, val wrong: List<String>)

    /** Transcript lines, six seconds a turn. */
    fun lines(): List<Line> = turns.mapIndexed { i, (who, text) -> Line(i, i * 6000L, i * 6000L + 5500, text, false, null, who) }

    fun score(note: String): Score {
        val (found, missing) = expected.partition { (_, r) -> r.containsMatchIn(note) }
        return Score(found.map { it.first }, missing.map { it.first }, forbidden.filter { (_, r) -> r.containsMatchIn(note) }.map { it.first })
    }

    companion object {
        fun parse(name: String, text: String): RolePlay {
            fun rule(line: String) = line.drop(2).trim().let { it.substringBefore('|').trim() to Regex(it.substringAfter('|').trim(), RegexOption.IGNORE_CASE) }
            val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return RolePlay(
                name,
                rows.filter { !it.startsWith("#") && it.contains(": ") }.map { it.substringBefore(": ") to it.substringAfter(": ") },
                rows.filter { it.startsWith("#+") }.map(::rule),
                rows.filter { it.startsWith("#-") }.map(::rule),
            )
        }

        fun bundled(file: String): RolePlay =
            parse(file.removePrefix("roleplay_").removeSuffix(".txt"), RolePlay::class.java.getResourceAsStream("/$file")!!.bufferedReader().use { it.readText() })

        val FILES = listOf("roleplay_inpatient.txt", "roleplay_adhd.txt")
    }
}
