package com.fadysaeed.scriber.core

/**
 * Word error rate between a reference text and what a recogniser heard.
 *
 * Both sides are normalised the same way first: lower case, punctuation removed, and spoken
 * dictation commands ("period", "comma", "colon", "new paragraph") dropped, because one engine may
 * write them as words and another as symbols. Section tags keep their words, "%" reads as
 * "percent", and spoken numbers become digits, so "thirty seven point two" equals "37.2".
 */
object Wer {
    data class Result(val referenceWords: Int, val errors: Int) {
        val rate: Double get() = if (referenceWords == 0) 0.0 else errors.toDouble() / referenceWords
    }

    private val dictationCommands = listOf("new paragraph", "new line", "full stop", "period", "comma", "colon", "semicolon")

    private val units = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    /** "thirty seven point two" -> "37.2", "fifty four" -> "54", so spoken and written numbers compare equal. */
    internal fun spokenNumbersToDigits(words: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        fun readInt(): Int? {
            var value: Int? = null
            while (i < words.size) {
                val w = words[i]
                when {
                    w in tens && (value == null || value % 100 == 0) -> { value = (value ?: 0) + tens.getValue(w); i++ }
                    w in units && (value == null || (value % 100 == 0) || (value % 10 == 0 && value % 100 >= 20)) -> { value = (value ?: 0) + units.getValue(w); i++ }
                    w == "hundred" && value != null && value in 1..9 -> { value *= 100; i++ }
                    w == "and" && value != null && value % 100 == 0 && i + 1 < words.size && (words[i + 1] in units || words[i + 1] in tens) -> i++
                    else -> return value
                }
            }
            return value
        }
        while (i < words.size) {
            val start = i
            val n = readInt()
            if (n == null) { out += words[i]; i++; continue }
            var text = n.toString()
            if (i + 1 < words.size && words[i] == "point" && words[i + 1] in units) {
                i++
                val digits = StringBuilder()
                while (i < words.size && words[i] in units && units.getValue(words[i]) < 10) { digits.append(units.getValue(words[i])); i++ }
                text += ".$digits"
            }
            if (i == start) i++
            out += text
        }
        return out
    }

    fun normalise(text: String): List<String> {
        var t = text.lowercase()
        // Section tags such as "[EXAM TYPE]" and commands such as "{period}" keep their words.
        t = t.replace(Regex("""[\[\]{}<>]"""), " ")
        t = t.replace("%", " percent ")
        for (c in dictationCommands) t = t.replace(Regex("""\b${Regex.escape(c)}\b"""), " ")
        t = t.replace(Regex("""(\d)\.(\d)"""), "$1d$2") // keep decimals through punctuation removal
        t = t.replace(Regex("""[^\p{L}\p{N}' ]"""), " ").replace("'", "")
        val words = t.split(Regex("""\s+""")).filter { it.isNotBlank() }
        return spokenNumbersToDigits(words).map { it.replace('.', 'd') }
    }

    fun compute(reference: String, hypothesis: String): Result {
        val r = normalise(reference)
        val h = normalise(hypothesis)
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) {
                val sub = prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return Result(r.size, prev[h.size])
    }

    /** Parses the reference file shipped with the medical test recordings: "# 0.wav" then its text lines. */
    fun parseReferences(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        val buf = StringBuilder()
        fun flush() { key?.let { out[it] = buf.toString().trim() }; buf.clear() }
        for (line in text.lineSequence()) {
            val m = Regex("""^#\s*(\S+)\s*$""").find(line)
            if (m != null) { flush(); key = m.groupValues[1] } else if (line.isNotBlank()) buf.append(line.trim()).append(' ')
        }
        flush()
        return out
    }
}
