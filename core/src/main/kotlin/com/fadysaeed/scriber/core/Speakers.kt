package com.fadysaeed.scriber.core

import kotlinx.serialization.Serializable

/** A stretch of audio spoken by one voice. Speakers are numbered from 0 in order of first speaking. */
@Serializable
data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

/** Fady moved base words [from] until [to] of run [runId] to [speaker]. */
@Serializable
data class SpeakerMove(val runId: String, val from: Int, val to: Int, val speaker: Int)

/**
 * Who spoke when in one session, kept beside the transcripts (never inside them). Made from the
 * audio, so it serves every run of the session; Fady's names and moves sit on top.
 */
@Serializable
data class SpeakerLayer(
    val sessionId: String,
    val audioSha256: String,
    /** How many voices were asked for; 0 lets the model decide. */
    val requested: Int,
    val turns: List<Turn>,
    val names: Map<Int, String> = emptyMap(),
    val moves: List<SpeakerMove> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val createdAtMillis: Long = 0,
) {
    val count: Int get() = (turns.map { it.speaker } + moves.map { it.speaker }).distinct().size
    fun name(speaker: Int): String = names[speaker]?.takeIf { it.isNotBlank() } ?: "Speaker ${speaker + 1}"
}

object Speakers {
    /**
     * Cleans the model's raw turns: joins a speaker's neighbouring turns, gives blips too short to be
     * a word (a cough, a breath of overlap) to the voice around them, and numbers voices in speaking
     * order. A short real answer ("Yes.") is longer than a blip and keeps its own voice.
     */
    fun smooth(raw: List<Turn>, minTurnMs: Long = 400, joinGapMs: Long = 600): List<Turn> {
        var t = raw.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        repeat(4) {
            t = join(t, joinGapMs)
            if (t.size < 2) return@repeat
            val out = ArrayList<Turn>()
            for (i in t.indices) {
                val cur = t[i]
                if (cur.endMs - cur.startMs >= minTurnMs) { out += cur; continue }
                val prev = out.lastOrNull()
                val next = t.getOrNull(i + 1)
                val to = when {
                    prev != null && next != null -> if (prev.speaker == next.speaker || prev.endMs - prev.startMs >= next.endMs - next.startMs) prev.speaker else next.speaker
                    prev != null -> prev.speaker
                    next != null -> next.speaker
                    else -> cur.speaker
                }
                out += cur.copy(speaker = to)
            }
            t = out
        }
        t = join(t, joinGapMs)
        val order = LinkedHashMap<Int, Int>()
        for (x in t) order.getOrPut(x.speaker) { order.size }
        return t.map { it.copy(speaker = order.getValue(it.speaker)) }
    }

    private fun join(t: List<Turn>, gap: Long): List<Turn> {
        val out = ArrayList<Turn>()
        for (x in t) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == x.speaker && x.startMs - last.endMs <= gap) out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, x.endMs))
            else out += x
        }
        return out
    }

    /** Number of times the voice changes. */
    fun changes(turns: List<Turn>): Int = turns.zipWithNext().count { (a, b) -> a.speaker != b.speaker }

    /**
     * Gives every word a speaker: the voice that overlaps it most, or the nearest one. Inside a
     * line, a run of one or two words between the same voice goes to that voice, because a real
     * change of speaker almost always comes with a pause, and pauses already split lines.
     */
    fun assign(words: List<Word>, ranges: List<IntRange>, layer: SpeakerLayer, runId: String): List<Int> {
        val turns = layer.turns
        val out = IntArray(words.size) { -1 }
        if (turns.isNotEmpty()) {
            var ti = 0
            for ((k, w) in words.withIndex()) {
                while (ti < turns.size - 1 && turns[ti].endMs <= w.startMs) ti++
                var best = -1; var bestOverlap = -1L; var bestGap = Long.MAX_VALUE
                for (j in maxOf(0, ti - 1)..minOf(turns.size - 1, ti + 2)) {
                    val t = turns[j]
                    val overlap = minOf(t.endMs, maxOf(w.endMs, w.startMs + 1)) - maxOf(t.startMs, w.startMs)
                    val gap = if (overlap > 0) 0 else minOf(kotlin.math.abs(t.startMs - w.endMs), kotlin.math.abs(w.startMs - t.endMs))
                    if (overlap > bestOverlap || (overlap <= 0 && bestOverlap <= 0 && gap < bestGap)) { best = t.speaker; bestOverlap = overlap; bestGap = gap }
                }
                out[k] = best
            }
            for (r in ranges) {
                if (r.isEmpty()) continue
                var i = r.first
                while (i <= r.last) {
                    var j = i
                    while (j + 1 <= r.last && out[j + 1] == out[i]) j++
                    val before = if (i > r.first) out[i - 1] else -2
                    val after = if (j < r.last) out[j + 1] else -3
                    if (j - i + 1 <= 2 && before == after) for (x in i..j) out[x] = before
                    i = j + 1
                }
            }
        }
        for (m in layer.moves) if (m.runId == runId) for (x in m.from until minOf(m.to, words.size)) if (x >= 0) out[x] = m.speaker
        return out.toList()
    }
}

/**
 * Groups voice prints (one per stretch of speech) into voices. Average-link agglomerative
 * clustering on cosine similarity: the two most alike groups join until [k] are left (or, with k = 0,
 * until no two groups are alike enough). Groups that end up very alike are one voice after all,
 * so a single speaker is never split in two just because two were asked for.
 */
object Voices {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0f else (dot / Math.sqrt(na * nb)).toFloat()
    }

    fun cluster(prints: List<FloatArray>, k: Int, joinAbove: Float = 0.45f, sameVoiceAbove: Float = 0.7f): IntArray {
        val n = prints.size
        if (n == 0) return IntArray(0)
        val sim = Array(n) { i -> FloatArray(n) { j -> if (i == j) 1f else cosine(prints[i], prints[j]) } }
        val groups = (0 until n).map { mutableListOf(it) }.toMutableList()
        fun link(a: List<Int>, b: List<Int>): Float { var s = 0f; for (x in a) for (y in b) s += sim[x][y]; return s / (a.size * b.size) }
        while (groups.size > 1) {
            var bi = -1; var bj = -1; var best = -2f
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val l = link(groups[i], groups[j]); if (l > best) { best = l; bi = i; bj = j }
            }
            val enough = if (k > 0) groups.size <= k && best < sameVoiceAbove else best < joinAbove
            if (enough) break
            groups[bi].addAll(groups[bj]); groups.removeAt(bj)
        }
        // Number voices in order of first speaking.
        val out = IntArray(n)
        groups.sortedBy { it.min() }.forEachIndexed { label, g -> for (x in g) out[x] = label }
        return out
    }
}

/** One stretch of speech with its voice print (null when too short to carry one). */
@Serializable
data class VoicePiece(val startMs: Long, val endMs: Long, val segment: Int, val question: Boolean, val print: List<Float>? = null)

/** Voice prints of a session, made while the main transcript was written (records/prints/<session>.json). */
@Serializable
data class VoicePrints(val sessionId: String, val runId: String, val model: String, val pieces: List<VoicePiece>, val createdAtMillis: Long = 0)

object Pieces {
    /** Shortest stretch that gets a voice print, and the most of it that is used. */
    const val MIN_PRINT_MS = 800L
    const val MAX_PRINT_MS = 10_000L

    /**
     * Splits one transcript line where the words pause long enough for the other person to have
     * started (the gap between word starts minus a typical word), keeping each part at least a second.
     */
    fun split(startMs: Long, endMs: Long, wordStarts: List<Long>, wordChars: List<Int>, minPartMs: Long = 1_000, pauseMs: Long = 450): List<LongRange> {
        val cuts = ArrayList<Long>()
        var from = startMs
        for (i in 0 until wordStarts.size - 1) {
            val expected = 120L + 70L * wordChars.getOrElse(i) { 4 }
            val gap = wordStarts[i + 1] - wordStarts[i] - expected
            val at = wordStarts[i + 1]
            if (gap >= pauseMs && at - from >= minPartMs && endMs - at >= minPartMs) { cuts += at; from = at }
        }
        val out = ArrayList<LongRange>()
        var s = startMs
        for (c in cuts) { out += s until c; s = c }
        out += s until endMs
        return out
    }

    /** The middle stretch of a piece used for its voice print, at most [MAX_PRINT_MS] long. */
    fun printWindow(r: LongRange): LongRange? {
        val len = r.last - r.first + 1
        if (len < MIN_PRINT_MS) return null
        val win = minOf(len, MAX_PRINT_MS)
        val start = r.first + (len - win) / 2
        return start until start + win
    }

    /**
     * Gives every piece a voice. Pieces with a print are grouped into [k] voices; a piece too short
     * for one takes the other voice when it answers a question, and otherwise the voice before it.
     */
    fun label(pieces: List<VoicePiece>, k: Int): List<Turn> {
        if (pieces.isEmpty()) return emptyList()
        val printed = pieces.indices.filter { pieces[it].print != null }
        val labels = IntArray(pieces.size) { -1 }
        if (printed.size >= 2) {
            val c = Voices.cluster(printed.map { pieces[it].print!!.toFloatArray() }, k)
            printed.forEachIndexed { j, i -> labels[i] = c[j] }
        } else printed.forEach { labels[it] = 0 }
        val voices = labels.filter { it >= 0 }.distinct().size
        for (i in pieces.indices) if (labels[i] < 0) {
            val prev = (i - 1 downTo 0).firstOrNull { labels[it] >= 0 }
            labels[i] = when {
                prev == null -> (i + 1 until pieces.size).firstOrNull { labels[it] >= 0 }?.let { labels[it] } ?: 0
                pieces[prev].question && voices >= 2 -> otherVoice(labels, prev, i)
                else -> labels[prev]
            }
        }
        return pieces.mapIndexed { i, p -> Turn(p.startMs, p.endMs, labels[i]) }
    }

    /** The voice that last spoke before [prev] other than the one at [prev]; voice 0 or 1 when none did. */
    private fun otherVoice(labels: IntArray, prev: Int, before: Int): Int {
        val mine = labels[prev]
        for (j in prev - 1 downTo 0) if (labels[j] >= 0 && labels[j] != mine) return labels[j]
        for (j in before + 1 until labels.size) if (labels[j] >= 0 && labels[j] != mine) return labels[j]
        return if (mine == 0) 1 else 0
    }
}
