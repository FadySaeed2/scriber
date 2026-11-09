package com.fadysaeed.scriber.core

import kotlinx.serialization.Serializable

/** Words Fady taught Scriber: medicines it did not know, and what the engines heard instead. */
@Serializable
data class PersonalWords(
    val words: List<String> = emptyList(),
    /** Key of what was heard (letters and digits only, lower case) to the word Fady put in. */
    val heardAs: Map<String, String> = emptyMap(),
)

/**
 * The medicine dictionary: generic and AU/NZ brand names, plus Fady's own words. Finds medicines
 * in transcript words, exactly or misspelled ("olanzapene", "olan zapine"), and gives the proper
 * spelling. A common English word is never taken for a misspelled medicine.
 */
class Medicines(entries: List<Entry>, private val common: Set<String>, personal: PersonalWords = PersonalWords()) {
    data class Entry(val name: String, val generic: String)
    data class Match(val canonical: String, val generic: String, val exact: Boolean, val index: Int, val count: Int)

    private val byKey = HashMap<String, Entry>()
    private val heardAs = personal.heardAs
    private val byLength: Map<Int, List<String>>
    private val fuzzyCache = HashMap<String, String?>()

    init {
        for (e in entries) byKey.putIfAbsent(key(e.name), e)
        for (w in personal.words) byKey.putIfAbsent(key(w), Entry(w, w))
        byLength = byKey.keys.groupBy { it.length }
    }

    val size: Int get() = byKey.size

    /** Is this exact name (any spelling of case or hyphens) in the dictionary? */
    fun contains(name: String): Boolean = byKey.containsKey(key(name))

    /**
     * The first medicine among [words]: one word, or two or three run together. With [joinOnly],
     * only the joined forms count (used when the single words were already checked).
     */
    fun find(words: List<String>, joinOnly: Boolean = false): Match? {
        for (count in (if (joinOnly) listOf(3, 2) else listOf(1, 3, 2))) {
            for (i in 0..words.size - count) {
                val joined = key(words.subList(i, i + count).joinToString(""))
                if (joined.isEmpty()) continue
                heardAs[joined]?.let { taught -> byKey[key(taught)]?.let { return Match(it.name, it.generic, false, i, count) } ?: return Match(taught, taught, false, i, count) }
                byKey[joined]?.let { return Match(it.name, it.generic, count == 1 && looksSame(words[i], it.name), i, count) }
                fuzzy(joined)?.let { k -> byKey[k]?.let { return Match(it.name, it.generic, false, i, count) } }
            }
        }
        return null
    }

    /** [words] with the matched medicine replaced by its proper spelling. */
    fun respell(words: List<String>, m: Match): String =
        (words.subList(0, m.index) + m.canonical + words.subList(m.index + m.count, words.size)).joinToString(" ")

    private fun looksSame(heard: String, name: String) = heard.trim { !it.isLetterOrDigit() }.equals(name, ignoreCase = true)

    /** The dictionary key closest to [k], if it is close enough and [k] is not an everyday word. */
    private fun fuzzy(k: String): String? = fuzzyCache.getOrPut(k) {
        if (k.length < 6 || k in common || k.any { it.isDigit() }) return@getOrPut null
        val limit = when { k.length <= 7 -> 1; k.length <= 10 -> 2; else -> 3 }
        var best: String? = null; var bestD = Int.MAX_VALUE; var tie = false
        for (len in k.length - limit..k.length + limit) for (cand in byLength[len].orEmpty()) {
            val d = distance(k, cand, limit)
            // A different first letter is allowed only for long words that are otherwise very close.
            if (d > limit || (cand[0] != k[0] && (limit < 2 || d > limit - 1))) continue
            if (d < bestD) { best = cand; bestD = d; tie = false } else if (d == bestD && byKey[cand]?.generic != byKey[best!!]?.generic) tie = true
        }
        if (tie) null else best
    }

    companion object {
        fun key(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

        /** Edit distance with swaps of neighbouring letters, giving up early past [limit]. */
        fun distance(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) {
                var rowMin = Int.MAX_VALUE
                for (j in 1..b.length) {
                    val c = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                    d[i][j] = v
                    rowMin = minOf(rowMin, v)
                }
                if (rowMin > limit) return limit + 1
            }
            return d[a.length][b.length]
        }

        fun parse(text: String): List<Entry> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .flatMap { line ->
                val generic = line.substringBefore('|').trim()
                val brands = line.substringAfter('|', "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                (listOf(generic) + brands).filter { it.isNotEmpty() }.map { Entry(it, generic) }
            }.toList()

        private fun resource(name: String): String =
            Medicines::class.java.getResourceAsStream("/$name")!!.bufferedReader().use { it.readText() }

        private val bundledEntries by lazy { parse(resource("medicines_au_nz.txt")) }
        private val bundledCommon by lazy { resource("common_english.txt").lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim() }.toHashSet() }

        fun bundled(personal: PersonalWords = PersonalWords()): Medicines = Medicines(bundledEntries, bundledCommon, personal)

        /** Every name in the bundled list, as written. */
        fun bundledNames(): List<String> = bundledEntries.map { it.name }

        /** Everyday English words (lower case), so they are never taken for medicines or speech slips. */
        fun commonWords(): Set<String> = bundledCommon
    }
}
