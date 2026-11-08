package com.fadysaeed.scriber.core

/**
 * Word error rate between a reference text and what a recogniser heard.
 *
 * Both sides are normalised the same way first: lower case, punctuation removed, and spoken
 * dictation commands ("period", "comma", "colon", "new paragraph") dropped, because one engine may
 * write them as words and another as symbols. Numbers are compared as written, so "54" against
 * "fifty four" still counts as an error; the report says so.
 */
object Wer {
    data class Result(val referenceWords: Int, val errors: Int) {
        val rate: Double get() = if (referenceWords == 0) 0.0 else errors.toDouble() / referenceWords
    }

    private val dictationCommands = listOf("new paragraph", "new line", "full stop", "period", "comma", "colon", "semicolon")

    fun normalise(text: String): List<String> {
        var t = text.lowercase()
        t = t.replace(Regex("""[{\[<][^}\]>]*[}\]>]"""), " ")
        for (c in dictationCommands) t = t.replace(Regex("""\b${Regex.escape(c)}\b"""), " ")
        t = t.replace(Regex("""[^\p{L}\p{N}' ]"""), " ").replace("'", "")
        return t.split(Regex("""\s+""")).filter { it.isNotBlank() }
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
