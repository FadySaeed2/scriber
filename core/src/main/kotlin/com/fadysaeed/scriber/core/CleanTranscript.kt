package com.fadysaeed.scriber.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The transcript as it leaves Scriber for another program (a cloud model, a letter): medicine names
 * the second opinion heard put in, speech fillers and stutters taken out, lines joined into
 * paragraphs, one per speaker turn. The sealed run and Fady's corrections never change; this is a
 * reading of them. Nothing is reworded: every word that stays was heard.
 */
object CleanTranscript {
    data class Result(val lines: List<Line>, /** By index in [lines]: medicine names put in. */ val cleaned: Map<Int, Set<String>>)

    /**
     * The corrected lines with the medicine fixes Fady has not looked at yet: a medicine the second
     * opinion heard where the first engine did not, and dictionary spellings of misheard names.
     * Fady's own corrections, and differences he kept as they were, win.
     */
    fun medicineLines(run: TranscriptRun, reading: Reading, layer: EditLayer?, speakers: List<Int>? = null, name: (Int) -> String = { "Speaker ${it + 1}" }): Result {
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

    private val FILLERS = setOf("um", "umm", "uh", "uhh", "uhm", "erm", "er", "ah", "hmm", "hm")
    /** Words that people really do say twice ("no, no", "very very"). */
    private val REPEATABLE = setOf("no", "very", "really", "bye", "had", "that", "so", "yes", "yeah", "okay", "ok", "well", "now", "far", "more", "in", "is")
    /** Real short words, never taken for the start of a stuttered word. */
    private val SHORT = ("a i am an as at be by do go he hi if in is it me my no of oh ok on or so to up us we " +
        "ago all and any are bad big bit but can day did dog eat end few for get got had has her him his hot how its " +
        "let lot low man may new nor not now off old one our out own put ran red run sad saw say see she sit six " +
        "ten the too top two use war was way who why yes yet you " +
        "act add age aid air arm art ask bed box car cut dad die dry due ear eye fit fun gap gas guy hit ill job key kid lay leg lie " +
        "met mix mum net nod odd oil pay pen per pet pop pot pub raw row sea set sex shy sky son sum sun tax tea tie tip toe ton try van via wet win won").split(' ').toSet()
    private val END = Regex("""[.!?]["')\]]*$""")

    private fun core(token: String) = token.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    /** One line without fillers, repeated words or stuttered starts of words; punctuation and capitals tidied. */
    fun tidy(text: String): String {
        val tokens = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for ((i, t) in tokens.withIndex()) {
            val c = core(t)
            val next = tokens.getOrNull(i + 1)?.let(::core).orEmpty()
            val prev = out.lastOrNull()
            val drop = when {
                c.isEmpty() -> false
                c in FILLERS -> true
                // "I I", "the the": the second goes, unless people say it twice on purpose.
                prev != null && core(prev) == c && c !in REPEATABLE && !END.containsMatchIn(prev) -> true
                // "lim limited", "s I": a broken-off start of the next word, or a stray letter.
                c.none(Char::isDigit) && c.length <= 3 && c !in SHORT && next.length > c.length && next.startsWith(c) -> true
                c.length == 1 && t == c && c != "a" && c != "i" && c.none(Char::isDigit) -> true
                else -> false
            }
            if (!drop) { out += t; continue }
            // Keep the sentence's end if the dropped word carried it ("... was, um.").
            val mark = END.find(t)?.value?.take(1)
            if (mark != null && out.isNotEmpty() && !END.containsMatchIn(out.last())) out[out.size - 1] = out.last().trimEnd(',', ';', ':') + mark
        }
        var s = out.joinToString(" ")
        s = s.replace(Regex("""^[,;:]\s*"""), "")
            .replace(Regex("""\s+([,;:.!?])"""), "$1")
            .replace(Regex("""([,;:])(\s*[,;:])+"""), "$1")
            .replace(Regex("""[,;:]([.!?])"""), "$1")
            .replace(Regex("""\s{2,}"""), " ").trim()
        // Capitals at the start and after each sentence.
        s = s.replaceFirstChar { it.uppercase() }
        s = Regex("""([.!?]\s+)(\p{Ll})""").replace(s) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return s
    }

    data class Paragraph(val speaker: String?, val startMs: Long, val text: String)

    /**
     * Lines joined into paragraphs: one per speaker turn when speakers are known; otherwise a new
     * paragraph only where a sentence ended and there was a pause. Dictation becomes readable text,
     * spoken commands turned into punctuation and paragraphs.
     */
    fun paragraphs(lines: List<Line>, mode: SessionMode): List<Paragraph> {
        val kept = lines.filter { it.text.isNotBlank() }
        if (kept.isEmpty()) return emptyList()
        if (mode == SessionMode.DICTATION) {
            val text = TranscriptFormats.readable(tidy(kept.joinToString(" ") { it.text }), mode)
            return text.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }.map { Paragraph(null, kept.first().startMs, it) }
        }
        val out = ArrayList<Paragraph>()
        var who: String? = null; var start = 0L; var lastEnd = 0L
        val text = StringBuilder()
        fun flush() { val t = tidy(text.toString()); if (t.isNotEmpty()) out += Paragraph(who, start, t); text.clear() }
        for (l in kept) {
            val newTurn = text.isNotEmpty() && (l.speakerName != who ||
                (END.containsMatchIn(text.trimEnd()) && (l.startMs - lastEnd >= 2000 || text.length > 900)))
            if (newTurn) flush()
            if (text.isEmpty()) { who = l.speakerName; start = l.startMs }
            if (text.isNotEmpty()) text.append(' ')
            text.append(l.text.trim())
            lastEnd = l.endMs
        }
        flush()
        return out
    }

    private fun stamp(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    fun render(paragraphs: List<Paragraph>, times: Boolean): String = paragraphs.joinToString("\n\n") { p ->
        (if (times) "[${stamp(p.startMs)}] " else "") + (p.speaker?.let { "$it: " } ?: "") + p.text
    }

    /** One line about the recording: kind, date, length, speakers. */
    fun header(mode: SessionMode, createdAtMillis: Long, durationMs: Long, speakers: List<String>): String {
        val kind = when (mode) { SessionMode.DICTATION -> "Dictation"; SessionMode.CONVERSATION -> "Conversation"; SessionMode.MEETING -> "Meeting" }
        val date = SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(Date(createdAtMillis))
        val minutes = ((durationMs + 30_000) / 60_000).coerceAtLeast(1)
        return "$kind recorded $date, $minutes min." + if (speakers.isNotEmpty()) " Speakers: ${speakers.joinToString(", ")}." else ""
    }

    /**
     * What is copied: the prompt (if any), Fady's line for this one session (if any), then the
     * header and the transcript.
     */
    fun compose(prompt: String?, header: String, body: String, extra: String? = null): String {
        val note = extra?.trim()?.takeIf { it.isNotEmpty() }?.let { "NOTE FROM THE CLINICIAN\n$it" }
        val top = listOfNotNull(prompt?.trim()?.takeIf { it.isNotEmpty() }, note).joinToString("\n\n")
        return if (top.isEmpty()) "$header\n\n$body" else "$top\n\nTRANSCRIPT\n\n$header\n\n$body"
    }
}
