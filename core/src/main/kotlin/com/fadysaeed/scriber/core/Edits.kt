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

/** A line of the finished transcript: the words of one segment after corrections. */
data class Line(val segment: Int, val startMs: Long, val endMs: Long, val text: String, val edited: Boolean)

object Document {
    /** The run's lines with [layer] applied. With no layer, exactly the run's own text, word for word. */
    fun lines(run: TranscriptRun, reading: Reading, layer: EditLayer?): List<Line> {
        val edits = layer?.edits.orEmpty()
        return run.segments.mapIndexed { si, seg ->
            val range = reading.segments.getOrNull(si) ?: IntRange.EMPTY
            val inLine = edits.filter { (it.from < it.to && it.from >= range.first && it.from <= range.last) || (it.from == it.to && it.segment == si) }
            if (inLine.isEmpty()) return@mapIndexed Line(si, seg.startMs, seg.endMs, seg.text, false)
            val out = ArrayList<String>()
            var k = range.first
            val sorted = inLine.sortedWith(compareBy({ it.from }, { if (it.from == it.to) 0 else 1 }))
            for (e in sorted) {
                while (k < e.from && k <= range.last) { out += reading.words[k].text; k++ }
                if (e.text.isNotBlank()) out += e.text.trim()
                if (e.to > k) k = minOf(e.to, range.last + 1)
            }
            while (k <= range.last) { out += reading.words[k].text; k++ }
            Line(si, seg.startMs, seg.endMs, out.joinToString(" "), true)
        }
    }
}
