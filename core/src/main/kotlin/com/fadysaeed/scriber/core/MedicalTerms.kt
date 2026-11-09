package com.fadysaeed.scriber.core

/**
 * Words and short phrases that mark speech as medical: conditions, syndromes, symptoms, anatomy,
 * tests, procedures and the abbreviations clinicians say aloud. Built from MeSH (U.S. National
 * Library of Medicine) with everyday English removed, plus a hand-made list of psychiatric and
 * ward language. Words are compared by their transcript key, so British and American spellings
 * meet ("haemorrhage" and "hemorrhage").
 */
class MedicalTerms(words: Collection<String>, removed: Collection<String> = emptyList()) {
    private val singles: Set<String>
    private val phrases: Set<String>
    val size: Int

    init {
        val gone = removed.map { key(it) }.toSet()
        val keyed = words.map { w -> w.trim().split(Regex("""\s+""")).map(::key).filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
        singles = keyed.filter { it.size == 1 }.map { it[0] }.filter { it !in gone }.toSet()
        phrases = keyed.filter { it.size in 2..4 }.map { it.joinToString(" ") }.filter { it !in gone }.toSet()
        size = singles.size + phrases.size
    }

    /**
     * The first medical word or phrase among [keys] (transcript keys, in order) that touches the
     * positions in [mark], or null. Phrases may reach into the words around the mark.
     */
    fun termIn(keys: List<String>, mark: IntRange): String? {
        for (i in mark) if (keys.getOrNull(i)?.let { it in singles } == true) return keys[i]
        for (n in 4 downTo 2) for (start in (mark.first - n + 1).coerceAtLeast(0)..mark.last) {
            if (start + n > keys.size) continue
            val p = keys.subList(start, start + n).joinToString(" ")
            if (p in phrases) return p
        }
        return null
    }

    fun isTerm(word: String): Boolean = key(word) in singles

    companion object {
        private fun key(w: String) = Words.key(w, SessionMode.CONVERSATION)

        private fun lines(file: String): List<String> =
            MedicalTerms::class.java.getResourceAsStream("/$file")?.bufferedReader()?.use { r -> r.readLines() }.orEmpty()
                .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        private val bundled by lazy {
            val extra = lines("medical_terms_extra.txt")
            MedicalTerms(lines("medical_terms.txt") + extra.filterNot { it.startsWith("-") }, extra.filter { it.startsWith("-") }.map { it.drop(1) })
        }

        fun bundled(): MedicalTerms = bundled
        val NONE = MedicalTerms(emptyList())
    }
}
