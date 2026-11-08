package com.fadysaeed.scriber.core

import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Turns runs into files for outside use. The raw form is exactly what the engine produced; the
 * readable form only applies dictation commands ("period", "{comma}", "[FINDINGS]") as punctuation
 * and headings, and only for dictation sessions, because in a conversation "period" can be a word.
 */
object TranscriptFormats {
    private val commandTokens = listOf(
        "new paragraph" to "\n\n", "new line" to "\n", "full stop" to ".", "period" to ".",
        "comma" to ",", "colon" to ":", "semicolon" to ";", "question mark" to "?",
    )

    fun readable(text: String, mode: SessionMode): String {
        if (mode != SessionMode.DICTATION) return text.trim()
        var t = text
        // MedASR writes section headings in brackets and commands in braces.
        // A heading's own spoken colon is dropped, and its first word starts with a capital.
        t = t.replace(Regex("""\[([^\]]+)\](\s*\{\s*colon\s*\})?\s*(\p{L})?""")) {
            "\n\n" + it.groupValues[1].trim().lowercase().replaceFirstChar { c -> c.uppercase() } + ": " + it.groupValues[3].uppercase()
        }
        for ((word, mark) in commandTokens) {
            t = t.replace(Regex("""\{\s*${Regex.escape(word)}\s*\}""", RegexOption.IGNORE_CASE), mark)
            t = t.replace(Regex("""(?<![\p{L}])${Regex.escape(word)}(?![\p{L}])[.,]?""", RegexOption.IGNORE_CASE), mark)
        }
        t = t.replace(Regex("""[ \t]+([.,:;?])"""), "$1")
        t = t.replace(Regex("""([.,:;?])(?=[^\s\n])"""), "$1 ")
        t = t.replace(Regex(""":\s*:"""), ":").replace(Regex("""\.\s*\."""), ".")
        t = t.replace(Regex("""[ \t]*\n[ \t]*"""), "\n").replace(Regex("""\n{3,}"""), "\n\n").replace(Regex("""[ \t]{2,}"""), " ")
        // Capital letter after a full stop or a line break.
        t = Regex("""(^|[.?]\s+|\n)(\p{Ll})""").replace(t.trim()) { it.groupValues[1] + it.groupValues[2].uppercase() }
        return t
    }

    fun clock(ms: Long, comma: Boolean): String {
        val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60; val f = ms % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d%s%03d", h, m, s, if (comma) "," else ".", f)
    }

    private fun linesOf(run: TranscriptRun) = run.segments.mapIndexed { i, s -> Line(i, s.startMs, s.endMs, s.text, false) }

    fun plain(run: TranscriptRun, mode: SessionMode, readable: Boolean): String = plain(linesOf(run), mode, readable)
    fun srt(run: TranscriptRun, mode: SessionMode, readable: Boolean): String = srt(linesOf(run), mode, readable)
    fun vtt(run: TranscriptRun, mode: SessionMode, readable: Boolean): String = vtt(linesOf(run), mode, readable)

    fun plain(lines: List<Line>, mode: SessionMode, readable: Boolean): String = buildString {
        for (s in lines) {
            append('[').append(clock(s.startMs, false).substring(0, 8)).append("] ")
            appendLine(if (readable) readable(s.text, mode) else s.text)
        }
    }

    fun srt(lines: List<Line>, mode: SessionMode, readable: Boolean): String = buildString {
        lines.forEachIndexed { i, s ->
            appendLine(i + 1)
            appendLine("${clock(s.startMs, true)} --> ${clock(s.endMs, true)}")
            appendLine(if (readable) readable(s.text, mode) else s.text)
            appendLine()
        }
    }

    fun vtt(lines: List<Line>, mode: SessionMode, readable: Boolean): String = buildString {
        appendLine("WEBVTT"); appendLine()
        for (s in lines) {
            appendLine("${clock(s.startMs, false)} --> ${clock(s.endMs, false)}")
            appendLine(if (readable) readable(s.text, mode) else s.text)
            appendLine()
        }
    }

    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    /** Everything: engine, model hash, settings, segments with token times, and the seal. */
    fun json(run: TranscriptRun): String = pretty.encodeToString(TranscriptRun.serializer(), run)

    /** Everything about one session's transcripts: every sealed run, and Fady's corrections on top. */
    @kotlinx.serialization.Serializable
    data class Bundle(val runs: List<TranscriptRun>, val corrections: List<EditLayer>, val correctedText: List<String>)

    fun json(bundle: Bundle): String = pretty.encodeToString(Bundle.serializer(), bundle)
}
