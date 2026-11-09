package com.fadysaeed.scriber.core

/** One word of a transcript, with its time and the line (segment) it belongs to. */
data class Word(val text: String, val key: String, val startMs: Long, val endMs: Long, val segment: Int)

/**
 * A place where the second engine heard something different from the base transcript.
 * [from] until [to] are base word positions (equal for words only the second engine heard).
 */
data class Difference(
    val from: Int,
    val to: Int,
    val segment: Int,
    val baseText: String,
    val altText: String,
    val startMs: Long,
    val endMs: Long,
    /** The medicine the second engine heard here, spelled properly, when the base did not hear it. */
    val medicine: String? = null,
    /** What to put in if Fady accepts the suggestion: the second engine's words, medicine spelled properly. */
    val suggestion: String = altText,
    /** A medical word or phrase either engine heard here (a condition, test, procedure...), when it is not a medicine. */
    val term: String? = null,
) {
    /** About medical language at all: a medicine or another medical term. */
    val medical: Boolean get() = medicine != null || term != null
    val id: String get() = "$from-$to"
}

/** A medicine named in the base transcript. Not [exact] means it looks misspelled; [canonical] is the fix. */
data class MedicineHit(val from: Int, val to: Int, val segment: Int, val heard: String, val canonical: String, val exact: Boolean) {
    val id: String get() = "m$from-$to"
}

/** The base transcript split into words, compared with a second engine's transcript of the same audio. */
data class Reading(
    val words: List<Word>,
    /** For each segment of the base run, its word positions [first, last + 1). */
    val segments: List<IntRange>,
    val differences: List<Difference>,
    val medicines: List<MedicineHit>,
)

object Words {
    private val braceCommand = Regex("""^\{.*\}$""")
    private val dictationCommands = setOf("period", "comma", "colon", "semicolon", "fullstop", "newline", "newparagraph", "questionmark")
    private val numbers = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
        "fifteen" to "15", "sixteen" to "16", "seventeen" to "17", "eighteen" to "18", "nineteen" to "19", "twenty" to "20",
        "thirty" to "30", "forty" to "40", "fifty" to "50", "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90",
        "hundred" to "100", "thousand" to "1000",
    )

    /**
     * Comparison key: lower-case letters and digits, number words as digits. Spoken commands in
     * braces ({period}) have no key; section tags in brackets ([PLAN]) keep their words.
     */
    fun key(word: String, mode: SessionMode): String {
        if (braceCommand.matches(word)) return ""
        val k = word.lowercase().filter { it.isLetterOrDigit() }
        if (mode == SessionMode.DICTATION && k in dictationCommands) return ""
        return numbers[k] ?: spelling(k)
    }

    // Built once: keying runs for every word of every transcript and every medical term. Each rule
    // is tried only when its ending is in the word at all, which a regex cannot match without.
    private val isation = Regex("""isation(s?)$""")
    private val ise = Regex("""is(e|ed|es|ing)$""")
    private val our = Regex("""our(s?)$""")
    private val tre = Regex("""tre(s?)$""")
    private val ogue = Regex("""ogue(s?)$""")
    private val lled = Regex("""lled$""")
    private val lling = Regex("""lling$""")

    /** British and American spellings of the same word get one key (mum and mom, oedema and edema). */
    private fun spelling(k: String): String {
        if (k.length < 3) return k
        var x = k
        if (x == "mum" || x == "mums") return x.replace("mum", "mom")
        x = x.replace("ae", "e").replace("oe", "e")
        if ("isation" in x) x = x.replace(isation, "ization$1")
        if ("is" in x) x = x.replace(ise, "iz$1")
        if ("our" in x) x = x.replace(our, "or$1")
        if ("tre" in x) x = x.replace(tre, "ter$1")
        if ("ogue" in x) x = x.replace(ogue, "og$1")
        if ("lled" in x) x = x.replace(lled, "led")
        if ("lling" in x) x = x.replace(lling, "ling")
        return x
    }

    /**
     * Splits each segment's text into words and gives every word a time. Times come from the
     * engine's token timings when the tokens spell the same text; otherwise they are spread evenly
     * over the segment by position, which is close enough to find and play a word.
     */
    fun of(run: TranscriptRun, mode: SessionMode): Pair<List<Word>, List<IntRange>> {
        val words = ArrayList<Word>()
        val ranges = ArrayList<IntRange>()
        run.segments.forEachIndexed { si, seg ->
            val first = words.size
            words += ofSegment(seg, si, mode)
            ranges += first until words.size
        }
        return words to ranges
    }

    /** The words of one line with their times. */
    fun ofSegment(seg: Segment, si: Int, mode: SessionMode): List<Word> {
        val parts = seg.text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val times = tokenTimes(seg, parts)
        return parts.mapIndexed { i, w ->
            val start = times?.getOrNull(i) ?: (seg.startMs + (seg.endMs - seg.startMs) * i / parts.size.coerceAtLeast(1))
            val end = times?.getOrNull(i + 1) ?: if (i + 1 < parts.size) seg.startMs + (seg.endMs - seg.startMs) * (i + 1) / parts.size else seg.endMs
            Word(w, key(w, mode), start, maxOf(start, end), si)
        }
    }

    /** A line cut into pieces where the speaker may change, each marked if it ends in a question. */
    fun pieces(seg: Segment, si: Int): List<Pair<LongRange, Boolean>> {
        val w = ofSegment(seg, si, SessionMode.CONVERSATION)
        val ranges = Pieces.split(seg.startMs, seg.endMs, w.map { it.startMs }, w.map { it.text.length })
        return ranges.map { r ->
            val last = w.lastOrNull { it.startMs in r }
            r to (last?.text?.trimEnd()?.endsWith("?") == true)
        }
    }

    /** Start time of each word from token timings, or null when the tokens do not spell the words. */
    private fun tokenTimes(seg: Segment, parts: List<String>): List<Long>? {
        if (seg.tokens.isEmpty() || parts.isEmpty()) return null
        val chars = StringBuilder()
        val charTimes = ArrayList<Long>()
        for (t in seg.tokens) {
            for (c in t.text.replace('▁', ' ')) {
                if (c.isWhitespace()) continue
                chars.append(c.lowercaseChar()); charTimes += t.startMs
            }
        }
        val joined = parts.joinToString("").lowercase()
        if (joined != chars.toString()) return null
        val out = ArrayList<Long>()
        var pos = 0
        for (p in parts) { out += charTimes[pos]; pos += p.length }
        return out
    }
}

object Compare {
    private val spaces = Regex("""\s+""")

    /**
     * Lines up two transcripts of the same audio word by word. Each base line is compared only with
     * the second engine's words spoken in the same stretch of time, so long sessions stay fast and a
     * difference in one place cannot shift the rest. Differences that are only spelling of the same
     * thing ("15" and "fifteen", "follow-up" and "follow up") are not shown.
     */
    fun reading(base: TranscriptRun, alt: TranscriptRun?, mode: SessionMode, medicines: Medicines, terms: MedicalTerms = MedicalTerms.bundled()): Reading {
        val (words, ranges) = Words.of(base, mode)
        val diffs = ArrayList<Difference>()
        if (alt != null) {
            val (altWords, _) = Words.of(alt, mode)
            val buckets = Array(ranges.size) { ArrayList<Word>() }
            for (w in altWords) {
                val mid = (w.startMs + w.endMs) / 2
                val si = segmentFor(base, mid)
                if (si >= 0) buckets[si] += w
            }
            ranges.forEachIndexed { si, range ->
                diffs += alignSegment(words, range, buckets[si], si, medicines, terms)
            }
        }
        val hits = findMedicines(words, ranges, medicines, diffs)
        return Reading(words, ranges, diffs, hits)
    }

    private fun segmentFor(run: TranscriptRun, ms: Long): Int {
        var best = -1; var bestGap = Long.MAX_VALUE
        run.segments.forEachIndexed { i, s ->
            val gap = when { ms < s.startMs -> s.startMs - ms; ms > s.endMs -> ms - s.endMs; else -> 0L }
            if (gap < bestGap) { best = i; bestGap = gap }
        }
        return if (bestGap <= 1500) best else -1
    }

    private fun alignSegment(words: List<Word>, range: IntRange, alt: List<Word>, si: Int, medicines: Medicines, terms: MedicalTerms): List<Difference> {
        // Only words with a key take part; markup is invisible to the comparison.
        val b = range.filter { words[it].key.isNotEmpty() }
        val a = alt.filter { it.key.isNotEmpty() }
        val n = b.size; val m = a.size
        val cost = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) cost[i][0] = i
        for (j in 0..m) cost[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val same = words[b[i - 1]].key == a[j - 1].key
            cost[i][j] = minOf(cost[i - 1][j - 1] + if (same) 0 else 1, cost[i - 1][j] + 1, cost[i][j - 1] + 1)
        }
        // Walk back, collecting runs of edits between matching words.
        val out = ArrayList<Difference>()
        var i = n; var j = m
        var spanB = ArrayList<Int>(); var spanA = ArrayList<Word>()
        fun flush(insertAt: Int) {
            if (spanB.isEmpty() && spanA.isEmpty()) return
            spanB.reverse(); spanA.reverse()
            makeDifference(words, spanB, spanA, insertAt, si, medicines, terms)?.let { out += it }
            spanB = ArrayList(); spanA = ArrayList()
        }
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && words[b[i - 1]].key == a[j - 1].key && cost[i][j] == cost[i - 1][j - 1]) {
                flush(b[i - 1] + 1); i--; j--
            } else if (i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + 1) {
                spanB += b[i - 1]; spanA += a[j - 1]; i--; j--
            } else if (i > 0 && cost[i][j] == cost[i - 1][j] + 1) {
                spanB += b[i - 1]; i--
            } else {
                spanA += a[j - 1]; j--
            }
        }
        flush(if (b.isNotEmpty()) b.first() else range.first)
        return out.reversed()
    }

    private fun makeDifference(words: List<Word>, spanB: List<Int>, spanA: List<Word>, insertAt: Int, si: Int, medicines: Medicines, terms: MedicalTerms): Difference? {
        val baseKeys = spanB.joinToString("") { words[it].key }
        val altKeys = spanA.joinToString("") { it.key }
        if (baseKeys == altKeys) return null
        val baseText = spanB.joinToString(" ") { words[it].text }
        val altText = spanA.joinToString(" ") { it.text }
        if (baseText.isNotBlank() && altText.isNotBlank() && Wer.normalise(baseText) == Wer.normalise(altText)) return null
        val altMedicine = medicines.find(spanA.map { it.text })
        val baseMedicine = medicines.find(spanB.map { words[it].text })
        val medicine = altMedicine?.takeIf { baseMedicine == null || baseMedicine.canonical != it.canonical || !baseMedicine.exact }?.canonical
        // A medical word either engine heard here, with two base words either side so phrases
        // like "heart failure" are found when only one of their words differs.
        val term = if (medicine != null || spanA.isEmpty()) null else {
            val at = spanB.firstOrNull() ?: insertAt
            val end = (spanB.lastOrNull() ?: (insertAt - 1)) + 1
            val before = (maxOf(0, at - 2) until at).map { words[it].key }
            val after = (end until minOf(words.size, end + 2)).map { words[it].key }
            val altKeys = before + spanA.map { it.key } + after
            val baseKeys = before + spanB.map { words[it].key } + after
            terms.termIn(altKeys, before.size until before.size + spanA.size)
                ?: if (spanB.isEmpty()) null else terms.termIn(baseKeys, before.size until before.size + spanB.size)
        }
        // Words only the base heard are not worth a question; words only the second engine heard
        // are shown only when they are medical.
        if (spanA.isEmpty()) return null
        if (spanB.isEmpty() && medicine == null && term == null) return null
        val from = if (spanB.isEmpty()) insertAt else spanB.first()
        val to = if (spanB.isEmpty()) insertAt else spanB.last() + 1
        val start = minOf(spanA.first().startMs, spanB.firstOrNull()?.let { words[it].startMs } ?: Long.MAX_VALUE)
        val end = maxOf(spanA.last().endMs, spanB.lastOrNull()?.let { words[it].endMs } ?: 0L)
        val suggestion = if (altMedicine != null && medicine != null) medicines.respell(spanA.map { it.text }, altMedicine) else altText
        return Difference(from, to, si, baseText, altText, start, end, medicine, dressLike(baseText, cleanMarkup(suggestion)), term)
    }

    /**
     * The second engine writes without capitals or punctuation; its words take the base words'
     * leading capital and trailing punctuation, so accepting them keeps the sentence intact.
     */
    private fun dressLike(base: String, text: String): String {
        if (base.isBlank() || text.isBlank()) return text
        var t = text
        if (base.first().isUpperCase() && t.first().isLowerCase()) t = t.replaceFirstChar { it.uppercase() }
        val trail = base.takeLastWhile { !it.isLetterOrDigit() }
        if (trail.isNotEmpty() && !t.endsWith(trail)) t = t.trimEnd { !it.isLetterOrDigit() } + trail
        return t
    }

    /** The second engine's markup ({period}, [PLAN]) is not offered as text to put in. */
    private fun cleanMarkup(text: String): String =
        text.split(spaces).filter { Words.key(it, SessionMode.CONVERSATION).isNotEmpty() }
            .joinToString(" ") { it.trim('[', ']') }

    /** Medicines in the base transcript, exact or misspelled, outside places already flagged as differences. */
    private fun findMedicines(words: List<Word>, ranges: List<IntRange>, medicines: Medicines, diffs: List<Difference>): List<MedicineHit> {
        val covered = BooleanArray(words.size)
        for (d in diffs) for (k in d.from until d.to) covered[k] = true
        val out = ArrayList<MedicineHit>()
        ranges.forEachIndexed { si, range ->
            var k = range.first
            while (k <= range.last) {
                if (covered[k]) { k++; continue }
                // Try two words joined first ("olan zapine"), then one.
                val two = if (k + 1 <= range.last && !covered[k + 1]) medicines.find(listOf(words[k].text, words[k + 1].text), joinOnly = true) else null
                val one = medicines.find(listOf(words[k].text))
                val hit = when {
                    one != null -> MedicineHit(k, k + 1, si, words[k].text, one.canonical, one.exact)
                    two != null -> MedicineHit(k, k + 2, si, "${words[k].text} ${words[k + 1].text}", two.canonical, false)
                    else -> null
                }
                if (hit != null) { out += hit; k = hit.to } else k++
            }
        }
        return out
    }
}
