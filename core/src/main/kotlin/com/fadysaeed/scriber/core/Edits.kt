package com.fadysaeed.scriber.core

import kotlinx.serialization.Serializable

/**
 * One correction: base words [from] until [to] become [text]. When [from] equals [to] the text is
 * put in before word [from], in line [segment]. Empty [text] removes the words.
 */
@Serializable
data class WordEdit(
    val from: Int,
    val to: Int,
    val segment: Int,
    val text: String,
    /** "second" (the second engine's words), "medicine" (dictionary spelling), "typed". */
    val source: String,
    val atMillis: Long,
)

/**
 * Fady's corrections to one sealed run, kept beside it. The run itself never changes; the
 * corrected text is the run with these edits laid over it. [runSeal] ties the layer to the exact
 * run it was made on.
 */
@Serializable
data class EditLayer(
    val sessionId: String,
    val runId: String,
    val runSeal: String,
    val edits: List<WordEdit> = emptyList(),
    /** Differences Fady looked at and kept as the base had them. */
    val kept: Set<String> = emptySet(),
    /** Fady marked the corrected text as right, so the Lab can score the engines against it. */
    val reference: Boolean = false,
    val updatedAtMillis: Long = 0,
) {
    /** Adds [e], dropping earlier edits it overlaps, so the newest correction of any word wins. */
    fun with(e: WordEdit): EditLayer {
        val rest = edits.filterNot { overlaps(it, e) }
        return copy(edits = (rest + e).sortedWith(compareBy({ it.from }, { it.to })), updatedAtMillis = e.atMillis)
    }

    /** Removes the most recent edit. */
    fun undo(): EditLayer = if (edits.isEmpty()) this else copy(edits = edits - edits.maxBy { it.atMillis })

    fun keep(id: String, now: Long) = copy(kept = kept + id, updatedAtMillis = now)

    /** The edit that touches exactly this place, if any. */
    fun at(from: Int, to: Int): WordEdit? = edits.lastOrNull { it.from == from && it.to == to }

    companion object {
        private fun overlaps(a: WordEdit, b: WordEdit): Boolean {
            val aIns = a.from == a.to; val bIns = b.from == b.to
            return when {
                aIns && bIns -> a.from == b.from && a.segment == b.segment
                aIns -> a.from > b.from && a.from < b.to
                bIns -> b.from > a.from && b.from < a.to
                else -> a.from < b.to && b.from < a.to
            }
        }
    }
}

/**
 * A line of the finished transcript: the words of one segment (or of one speaker within it) after
 * corrections. [from] until [to] are the base words it covers.
 */
data class Line(
    val segment: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val edited: Boolean,
    val speaker: Int? = null,
    val speakerName: String? = null,
    val from: Int = -1,
    val to: Int = -1,
)

object Document {
    /**
     * The run's lines with [layer] applied. With no layer, exactly the run's own text, word for word.
     * With [speakers] (one per base word), a segment where the voice changes becomes one line per voice.
     */
    fun lines(run: TranscriptRun, reading: Reading, layer: EditLayer?, speakers: List<Int>? = null, name: (Int) -> String = { "Speaker ${it + 1}" }): List<Line> {
        val edits = layer?.edits.orEmpty()
        val out = ArrayList<Line>()
        run.segments.forEachIndexed { si, seg ->
            val range = reading.segments.getOrNull(si) ?: IntRange.EMPTY
            if (range.isEmpty()) {
                val ins = edits.filter { it.from == it.to && it.segment == si && it.text.isNotBlank() }
                val text = if (ins.isEmpty()) seg.text else (ins.map { it.text.trim() }).joinToString(" ")
                out += Line(si, seg.startMs, seg.endMs, text, ins.isNotEmpty(), from = range.first, to = range.first)
                return@forEachIndexed
            }
            // Split the segment where the voice changes.
            val spans = ArrayList<IntRange>()
            var start = range.first
            for (k in range.first + 1..range.last + 1) {
                if (k > range.last || (speakers != null && speakers.getOrNull(k) != speakers.getOrNull(k - 1))) { spans += start until k; start = k }
            }
            fun spanOf(k: Int) = spans.indexOfFirst { k in it }.let { if (it < 0) spans.size - 1 else it }
            val tokens = Array(spans.size) { ArrayList<String>() }
            val touched = BooleanArray(spans.size)
            val inLine = edits.filter { (it.from < it.to && it.from >= range.first && it.from <= range.last) || (it.from == it.to && it.segment == si) }
                .sortedWith(compareBy({ it.from }, { if (it.from == it.to) 0 else 1 }))
            var k = range.first
            for (e in inLine) {
                while (k < e.from && k <= range.last) { tokens[spanOf(k)] += reading.words[k].text; k++ }
                val at = spanOf(minOf(e.from, range.last))
                if (e.text.isNotBlank()) tokens[at] += e.text.trim()
                touched[at] = true
                if (e.to > k) k = minOf(e.to, range.last + 1)
            }
            while (k <= range.last) { tokens[spanOf(k)] += reading.words[k].text; k++ }
            spans.forEachIndexed { i, span ->
                if (tokens[i].isEmpty() && !touched[i]) return@forEachIndexed
                val sp = speakers?.getOrNull(span.first)?.takeIf { it >= 0 }
                val startMs = if (spans.size == 1) seg.startMs else reading.words[span.first].startMs
                val endMs = if (spans.size == 1) seg.endMs else reading.words[span.last].endMs
                out += Line(si, startMs, endMs, if (inLine.isEmpty() && spans.size == 1) seg.text else tokens[i].joinToString(" "),
                    touched[i], sp, sp?.let(name), span.first, span.last + 1)
            }
        }
        return out
    }
}
