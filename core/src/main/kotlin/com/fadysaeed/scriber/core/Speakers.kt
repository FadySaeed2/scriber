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

    /**
     * Average-link clustering. Group-to-group likeness is kept up to date as groups join (the
     * average of the two rows, weighted by size), and each group remembers its most alike
     * partner, so a two-hour meeting with a thousand or more pieces takes seconds, not minutes.
     * Same result as comparing every member of every pair of groups each round.
     */
    fun cluster(prints: List<FloatArray>, k: Int, joinAbove: Float = 0.45f, sameVoiceAbove: Float = 0.7f): IntArray {
        val n = prints.size
        if (n == 0) return IntArray(0)
        val sim = Array(n) { FloatArray(n) }
        for (i in 0 until n) { sim[i][i] = 1f; for (j in i + 1 until n) { val c = cosine(prints[i], prints[j]); sim[i][j] = c; sim[j][i] = c } }
        val alive = BooleanArray(n) { true }
        val size = IntArray(n) { 1 }
        val members = Array(n) { mutableListOf(it) }
        val best = IntArray(n) { -1 }
        fun findBest(i: Int) {
            var bj = -1; var bv = -2f
            for (j in 0 until n) if (j != i && alive[j] && sim[i][j] > bv) { bv = sim[i][j]; bj = j }
            best[i] = bj
        }
        for (i in 0 until n) findBest(i)
        var groups = n
        while (groups > 1) {
            var bi = -1; var bv = -2f
            for (i in 0 until n) if (alive[i] && best[i] >= 0 && sim[i][best[i]] > bv) { bv = sim[i][best[i]]; bi = i }
            if (bi < 0) break
            val bj = best[bi]
            val enough = if (k > 0) groups <= k && bv < sameVoiceAbove else bv < joinAbove
            if (enough) break
            // Join the later group into the earlier one, so labels follow first appearance.
            val (keep, gone) = if (bi < bj) bi to bj else bj to bi
            for (x in 0 until n) if (alive[x] && x != keep && x != gone) {
                val v = (size[keep] * sim[keep][x] + size[gone] * sim[gone][x]) / (size[keep] + size[gone])
                sim[keep][x] = v; sim[x][keep] = v
            }
            size[keep] += size[gone]; members[keep].addAll(members[gone]); alive[gone] = false; groups--
            findBest(keep)
            for (x in 0 until n) if (alive[x] && x != keep && (best[x] == keep || best[x] == gone)) findBest(x)
        }
        // Number voices in order of first speaking.
        val out = IntArray(n)
        (0 until n).filter { alive[it] }.map { members[it] }.sortedBy { it.min() }.forEachIndexed { label, g -> for (x in g) out[x] = label }
        return out
    }
}

/** One stretch of speech with its voice print (null when too short to carry one). */
@Serializable
data class VoicePiece(val startMs: Long, val endMs: Long, val segment: Int, val question: Boolean, val print: List<Float>? = null)

/** Voice prints of a session, made while the main transcript was written (records/prints/<session>.json). */
@Serializable
data class VoicePrints(
    val sessionId: String,
    val runId: String,
    val model: String,
    val pieces: List<VoicePiece>,
    val createdAtMillis: Long = 0,
    /** The speaker model's own turns (pyannote), used to cut lines and to place short pieces. */
    val modelTurns: List<Turn> = emptyList(),
)

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

    /**
     * Cuts transcript lines only where the speaker model heard a clear change inside a long line
     * (both sides at least two seconds). Ordinary pauses inside a sentence do not cut it: short
     * pieces make unsteady voice prints.
     */
    fun cutAtModelChanges(lines: List<LongRange>, model: List<Turn>): List<Pair<LongRange, Int>> {
        val out = ArrayList<Pair<LongRange, Int>>()
        lines.forEachIndexed { li, line ->
            var start = line.first
            for (t in model) {
                if (t.startMs > start + 2000 && t.startMs < line.last - 2000 && model.any { o -> o.speaker != t.speaker && o.endMs in (t.startMs - 600)..(t.startMs + 600) }) {
                    out += (start until t.startMs) to li; start = t.startMs
                }
            }
            out += (start until line.last) to li
        }
        return out
    }

    /**
     * Gives every piece a voice: pieces with a print are grouped into [k] voices; a piece too short
     * for a print takes the voice that the speaker model's turn there most often became.
     */
    fun labelWithModel(pieces: List<VoicePiece>, model: List<Turn>, k: Int): List<Turn> {
        if (pieces.isEmpty()) return emptyList()
        val printed = pieces.indices.filter { pieces[it].print != null }
        val label = IntArray(pieces.size) { -1 }
        if (printed.size >= 2) {
            val c = Voices.cluster(printed.map { pieces[it].print!!.toFloatArray() }, k)
            printed.forEachIndexed { j, i -> label[i] = c[j] }
        } else printed.forEach { label[it] = 0 }
        fun modelSpeaker(p: VoicePiece) = model.maxByOrNull { t -> minOf(t.endMs, p.endMs) - maxOf(t.startMs, p.startMs) }
            ?.takeIf { t -> minOf(t.endMs, p.endMs) > maxOf(t.startMs, p.startMs) }?.speaker ?: -1
        val vote = HashMap<Int, HashMap<Int, Int>>()
        for (i in printed) vote.getOrPut(modelSpeaker(pieces[i])) { HashMap() }.merge(label[i], 1, Int::plus)
        for (i in pieces.indices) if (label[i] < 0) {
            label[i] = vote[modelSpeaker(pieces[i])]?.maxByOrNull { it.value }?.key
                ?: (i - 1 downTo 0).firstOrNull { label[it] >= 0 }?.let { label[it] } ?: 0
        }
        return pieces.mapIndexed { i, p -> Turn(p.startMs, p.endMs, label[i]) }
    }

    /** The voice that last spoke before [prev] other than the one at [prev]; voice 0 or 1 when none did. */
    private fun otherVoice(labels: IntArray, prev: Int, before: Int): Int {
        val mine = labels[prev]
        for (j in prev - 1 downTo 0) if (labels[j] >= 0 && labels[j] != mine) return labels[j]
        for (j in before + 1 until labels.size) if (labels[j] >= 0 && labels[j] != mine) return labels[j]
        return if (mine == 0) 1 else 0
    }
}
