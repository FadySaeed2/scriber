package com.fadysaeed.scriber.core

/**
 * Turns a transcript into a note in two steps, with the language model only as a writer:
 *  1. facts: the model lists facts, each with the exact words that support it; code keeps only
 *     those whose words are really in that line, and whose medicines and numbers are there too;
 *  2. note: each template section is written from its facts alone, every sentence citing the
 *     facts it rests on; code marks sentences without a fact, or with a number or medicine that
 *     none of its facts has.
 * [ask] sends one prompt to the model and returns its whole answer.
 */
class NotePipeline(private val medicines: Medicines, private val ask: (String) -> String) {

    /** A transcript line as the model sees it: "L12 Patient: words". */
    data class SourceLine(val number: Int, val speaker: String?, val text: String, val startMs: Long, val endMs: Long)

    fun lines(lines: List<Line>): List<SourceLine> =
        lines.filter { it.text.isNotBlank() }.mapIndexed { i, l -> SourceLine(i + 1, l.speakerName, l.text, l.startMs, l.endMs) }

    /** Groups lines into pieces small enough for the model's memory, with two lines of overlap. */
    fun chunks(lines: List<SourceLine>, maxChars: Int = 6000): List<List<SourceLine>> {
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

    fun factPrompt(chunk: List<SourceLine>): String = buildString {
        appendLine("You take facts out of part of a psychiatric consultation transcript, for a clinical note.")
        appendLine("Write one fact per line, exactly in this form and nothing else:")
        appendLine("KIND | L<number> | the fact | \"exact words from that line\"")
        appendLine()
        appendLine("KIND is one of:")
        for (k in FactKind.entries) appendLine("${k.name}: ${k.hint}")
        appendLine()
        appendLine("Rules:")
        appendLine("- Only what was said in these lines. Never add, guess or interpret.")
        appendLine("- The quote is 3 to 15 words copied exactly from that one line.")
        appendLine("- The fact is short clinical English in the third person; keep every medicine name, dose and number exactly as said.")
        appendLine("- One fact per line; one idea per fact. Questions alone are not facts: use the answer.")
        appendLine("- If nothing in these lines is a fact, write NONE.")
        appendLine()
        appendLine("Transcript:")
        for (l in chunk) appendLine("L${l.number}${l.speaker?.let { " $it" } ?: ""}: ${l.text}")
    }

    /** Facts from one model answer, checked against the lines they cite. */
    fun parseFacts(answer: String, lines: List<SourceLine>, firstId: Int): Pair<List<Fact>, List<RejectedFact>> {
        val byNumber = lines.associateBy { it.number }
        val facts = ArrayList<Fact>(); val rejected = ArrayList<RejectedFact>()
        var next = firstId
        for (raw in answer.lineSequence().map { it.trim().trimStart('-', '*', ' ') }.filter { it.isNotEmpty() && !it.equals("NONE", true) }) {
            val parts = raw.split('|').map { it.trim() }
            if (parts.size < 4) { rejected += RejectedFact(raw, "not in the expected form"); continue }
            val kind = FactKind.entries.firstOrNull { it.name.equals(parts[0].trim('*', ' '), true) }
            if (kind == null) { rejected += RejectedFact(raw, "unknown kind ${parts[0]}"); continue }
            val number = parts[1].removePrefix("L").removePrefix("l").trim().toIntOrNull()
            val line = number?.let { byNumber[it] }
            if (line == null) { rejected += RejectedFact(raw, "no such line ${parts[1]}"); continue }
            val text = parts[2]
            val quote = parts.drop(3).joinToString(" | ").trim().trim('"', '“', '”', '\'')
            if (text.isBlank()) { rejected += RejectedFact(raw, "empty fact"); continue }
            check(quote, line)?.let { rejected += RejectedFact(raw, it); continue }
            groundedIn(text, line.text)?.let { rejected += RejectedFact(raw, it); continue }
            facts += Fact("F${next++}", kind, text, quote, line.number, line.startMs, line.endMs)
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

    /** Null when every number and medicine in [claim] is also in [source]; otherwise what is missing. */
    fun groundedIn(claim: String, source: String): String? {
        val src = Wer.normalise(source).toSet()
        val missingNumbers = Wer.normalise(claim).filter { w -> w.any(Char::isDigit) }.filter { it !in src }
        if (missingNumbers.isNotEmpty()) return "number ${missingNumbers.joinToString()} not said there"
        val said = Scoring.medicineCounts(source, medicines).keys
        val claimed = Scoring.medicineCounts(claim, medicines).keys
        val extra = claimed - said
        if (extra.isNotEmpty()) return "medicine ${extra.joinToString()} not said there"
        return null
    }

    /** Takes facts from every chunk, dropping exact repeats from the overlaps. */
    fun facts(lines: List<SourceLine>, progress: (Float) -> Unit = {}): Pair<List<Fact>, List<RejectedFact>> {
        val all = ArrayList<Fact>(); val rejected = ArrayList<RejectedFact>()
        val parts = chunks(lines)
        parts.forEachIndexed { i, chunk ->
            val (f, r) = parseFacts(ask(factPrompt(chunk)), chunk, all.size + 1)
            for (x in f) if (all.none { it.kind == x.kind && Wer.normalise(it.text) == Wer.normalise(x.text) }) all += x.copy(id = "F${all.size + 1}")
            rejected += r
            progress((i + 1).toFloat() / parts.size)
        }
        return all to rejected
    }

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
        appendLine("Use only these facts. After every sentence or item, put the ids of the facts it uses in square brackets, like [F3] or [F3, F7].")
        appendLine("Do not write the section title. Do not mention facts that are not listed. Keep medicine names, doses and numbers exactly as in the facts.")
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
            val warning = when {
                known.isEmpty() -> "No fact behind this sentence"
                else -> groundedIn(text, known.joinToString(" ") { facts.getValue(it).text + " " + facts.getValue(it).quote })
            }
            NoteSentence(text, known, warning)
        }
    }

    /** Writes the note section by section from [facts] (removed facts are left out). */
    fun note(template: NoteTemplate, facts: List<Fact>, progress: (Float) -> Unit = {}): List<NoteSectionText> {
        val active = facts.filterNot { it.removed }
        val byId = active.associateBy { it.id }
        val out = ArrayList<NoteSectionText>()
        template.sections.forEachIndexed { i, section ->
            val mine = if (section.allFacts) active.filter { it.kind in section.kinds } + active.filter { it.kind !in section.kinds }
                else active.filter { it.kind in section.kinds }
            val sentences = when {
                mine.isEmpty() && section.omitWhenEmpty -> emptyList()
                mine.isEmpty() -> listOf(NoteSentence("Not assessed.", emptyList()))
                else -> parseSection(ask(sectionPrompt(template, section, mine)), section.form, byId)
            }
            if (sentences.isNotEmpty()) out += NoteSectionText(section.id, section.title, section.form, sentences)
            progress((i + 1).toFloat() / template.sections.size)
        }
        return out
    }
}
