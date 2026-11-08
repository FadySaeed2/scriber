package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.SessionUi
import com.fadysaeed.scriber.core.Difference
import com.fadysaeed.scriber.core.MedicineHit
import com.fadysaeed.scriber.core.WordEdit

/** Short engine name for buttons: "Parakeet", "MedASR". */
fun engineName(engine: String?): String = when (engine) { "medasr" -> "MedASR"; "parakeet" -> "Parakeet"; null -> "the engine"; else -> engine }

/** The line under the transcript status: how the second opinion went and what is left to check. */
@Composable
fun SecondOpinion(ui: SessionUi, a: SessionActions, onReview: () -> Unit) {
    val r = a.review
    val compare = ui.compareRun
    val diffs = ui.openDifferences
    val meds = ui.openMedicines
    val medSuggestions = diffs.count { it.medicine != null } + meds.size
    Spacer(Modifier.height(14.dp))
    when {
        compare != null && (diffs.isNotEmpty() || meds.isNotEmpty()) -> {
            Text(listOfNotNull(
                "${engineName(compare.engine)} heard ${diffs.size} ${if (diffs.size == 1) "place" else "places"} differently",
                if (medSuggestions > 0) "$medSuggestions about medicines" else null,
            ).joinToString(", ") + ".", style = Type.body)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AmberButton("Review ${diffs.size + meds.size}", onReview, Modifier.weight(1f))
                if (medSuggestions > 0) GhostButton("Take all $medSuggestions medicines", r.onAcceptAllMedicines, Modifier.weight(1f), color = Ink.Teal, minHeight = 52.dp)
            }
        }
        compare != null -> Status("Checked against ${engineName(compare.engine)}: nothing left to look at", Ink.Teal)
        meds.isNotEmpty() -> {
            Text("${meds.size} medicine ${if (meds.size == 1) "name looks" else "names look"} misspelled.", style = Type.body)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AmberButton("Review ${meds.size}", onReview, Modifier.weight(1f))
                GhostButton("Fix all", r.onAcceptAllMedicines, Modifier.weight(1f), color = Ink.Teal, minHeight = 52.dp)
            }
        }
    }
    if (ui.missingSecond != null && ui.job == null && ui.session?.hasAudio == true) {
        ActionRow(Glyph.REDO, "Get a second opinion from ${engineName(ui.missingSecond)}", r.onSecondOpinion, "Marks the places where the two engines disagree")
    }
    if (ui.corrections > 0) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${ui.corrections} ${if (ui.corrections == 1) "correction" else "corrections"}, kept beside the sealed original.", style = Type.small, modifier = Modifier.weight(1f))
            Text("Undo", style = Type.small.copy(color = Ink.Amber), modifier = Modifier.clickable(role = Role.Button, onClick = r.onUndo).padding(10.dp))
        }
    }
    if (ui.reading != null && (ui.corrections > 0 || ui.edits?.kept?.isNotEmpty() == true)) {
        Toggle("This transcript is now right", "The Lab scores both engines against it, on your own voice", ui.edits?.reference == true, r.onReference)
    }
}

private sealed interface Piece {
    val text: String
    data class Plain(override val text: String, val index: Int) : Piece
    data class Medicine(override val text: String, val index: Int) : Piece
    data class Misspelled(override val text: String, val hit: MedicineHit) : Piece
    data class Different(override val text: String, val diff: Difference) : Piece
    data class Missing(val diff: Difference) : Piece { override val text = "+" }
    data class Corrected(override val text: String, val edit: WordEdit) : Piece
}

/** One line of the transcript as pieces: plain words, medicines, differences, corrections. */
private fun pieces(ui: SessionUi, segment: Int): List<Piece> {
    val reading = ui.reading ?: return emptyList()
    val range = reading.segments.getOrNull(segment) ?: return emptyList()
    val edits = if (ui.showEdited) ui.edits?.edits.orEmpty() else emptyList()
    val diffs = if (ui.showEdited) ui.openDifferences.filter { it.segment == segment } else emptyList()
    val misspelled = if (ui.showEdited) ui.openMedicines.filter { it.segment == segment } else emptyList()
    val exact = reading.medicines.filter { it.exact && it.segment == segment }
    val out = ArrayList<Piece>()
    var k = range.first
    fun insertsAt(pos: Int) {
        edits.filter { it.from == it.to && it.from == pos && it.segment == segment && it.text.isNotBlank() }.forEach { out += Piece.Corrected(it.text, it) }
        diffs.filter { it.from == it.to && it.from == pos }.forEach { out += Piece.Missing(it) }
    }
    while (k <= range.last) {
        insertsAt(k)
        val e = edits.firstOrNull { it.from == k && it.to > k }
        val d = diffs.firstOrNull { it.from == k && it.to > k }
        val m = misspelled.firstOrNull { it.from == k }
        when {
            e != null -> { if (e.text.isNotBlank()) out += Piece.Corrected(e.text, e); k = minOf(e.to, range.last + 1) }
            d != null -> { out += Piece.Different(d.baseText, d); k = d.to }
            m != null -> { out += Piece.Misspelled(m.heard, m); k = m.to }
            exact.any { k >= it.from && k < it.to } -> { out += Piece.Medicine(reading.words[k].text, k); k++ }
            else -> { out += Piece.Plain(reading.words[k].text, k); k++ }
        }
    }
    insertsAt(range.last + 1)
    return out
}

private val commandMarks = mapOf("period" to ".", "fullstop" to ".", "comma" to ",", "colon" to ":", "semicolon" to ";", "questionmark" to "?")

/**
 * Builds the line as clickable text. In readable dictation, spoken commands become punctuation and
 * line breaks here too, so corrections and readable text work together.
 */
private fun annotated(list: List<Piece>, readable: Boolean, onTap: (Piece) -> Unit): AnnotatedString = buildAnnotatedString {
    var capitalise = true
    var i = 0
    while (i < list.size) {
        val p = list[i]
        var text = p.text
        if (readable && p is Piece.Plain) {
            val key = text.lowercase().filter { it.isLetter() }
            val nextKey = list.getOrNull(i + 1)?.takeIf { it is Piece.Plain }?.text?.lowercase()?.filter { it.isLetter() }
            if ((key == "new" && (nextKey == "paragraph" || nextKey == "line"))) {
                append(if (nextKey == "paragraph") "\n\n" else "\n"); capitalise = true; i += 2; continue
            }
            val two = if (nextKey != null) key + nextKey else null
            val mark = commandMarks[key] ?: two?.let { commandMarks[it] }?.also { i++ }
            if (mark != null) { append(mark); capitalise = mark == "." || mark == "?"; i++; continue }
            if (capitalise) text = text.replaceFirstChar { it.uppercase() }
        }
        if (length > 0 && !this.toString().endsWith("\n")) append(" ")
        val style = when (p) {
            is Piece.Plain -> SpanStyle()
            is Piece.Medicine -> SpanStyle(color = Ink.Teal)
            is Piece.Misspelled -> SpanStyle(color = Ink.Teal, textDecoration = TextDecoration.Underline)
            is Piece.Different -> SpanStyle(color = Ink.Amber, textDecoration = TextDecoration.Underline)
            is Piece.Missing -> SpanStyle(color = Ink.Amber)
            is Piece.Corrected -> SpanStyle(color = Ink.Bone)
        }
        withLink(LinkAnnotation.Clickable("p$i", TextLinkStyles(style = style)) { onTap(p) }) { withStyle(style) { append(text) } }
        capitalise = readable && text.trimEnd().endsWith(".")
        i++
    }
}

/** The transcript, line by line: time on the left (tap to hear), words on the right (tap to correct). */
@Composable
fun Transcript(ui: SessionUi, a: SessionActions, readable: Boolean, open: (SessionSheet) -> Unit) {
    val s = ui.session ?: return
    val reading = ui.reading
    for (line in ui.lines) {
        val playingHere = ui.player.playing && ui.player.positionMs in line.startMs until line.endMs
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(clock(line.startMs), style = Type.tiny.copy(color = if (playingHere) Ink.Amber else Ink.Ash),
                modifier = Modifier.width(48.dp).clickable(enabled = s.hasAudio, role = Role.Button) { a.onPlay(line.startMs) }.padding(top = 5.dp, bottom = 8.dp))
            val color = if (playingHere) Ink.Bone else Ink.Sand
            if (reading == null) {
                Text(if (readable) runCatching { com.fadysaeed.scriber.core.TranscriptFormats.readable(line.text, s.mode) }.getOrDefault(line.text) else line.text, style = Type.reading.copy(color = color))
            } else {
                val list = pieces(ui, line.segment)
                Text(annotated(list, readable) { p ->
                    when (p) {
                        is Piece.Different -> open(SessionSheet.Review)
                        is Piece.Missing -> open(SessionSheet.Review)
                        is Piece.Misspelled -> open(SessionSheet.Review)
                        is Piece.Plain, is Piece.Medicine -> {
                            val idx = if (p is Piece.Plain) p.index else (p as Piece.Medicine).index
                            val w = reading.words[idx]
                            open(SessionSheet.Words(idx, idx + 1, line.segment, w.text, w.text, w.startMs, w.endMs))
                        }
                        is Piece.Corrected -> {
                            val e = p.edit
                            val heard = if (e.to > e.from) reading.words.subList(e.from, e.to).joinToString(" ") { it.text } else ""
                            val t0 = reading.words.getOrNull(e.from)?.startMs ?: line.startMs
                            val t1 = reading.words.getOrNull((e.to - 1).coerceAtLeast(e.from))?.endMs ?: line.endMs
                            open(SessionSheet.Words(e.from, e.to, e.segment, e.text, heard, t0, t1))
                        }
                    }
                }, style = Type.reading.copy(color = color), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** One place at a time: what each engine heard, a button to hear it, and a choice. */
@Composable
fun ReviewSheet(ui: SessionUi, a: SessionActions, onClose: () -> Unit, open: (SessionSheet) -> Unit) {
    val r = a.review
    val items: List<Any> = (ui.openDifferences + ui.openMedicines).sortedBy { if (it is Difference) it.from else (it as MedicineHit).from }
    var index by remember { mutableIntStateOf(0) }
    Sheet(onClose) {
        if (items.isEmpty()) {
            Eyebrow("Review"); Spacer(Modifier.height(10.dp))
            Text("Everything is checked.", style = Type.title)
            Spacer(Modifier.height(16.dp))
            AmberButton("Done", onClose, Modifier.fillMaxWidth())
            return@Sheet
        }
        val i = index.coerceIn(0, items.size - 1)
        val item = items[i]
        val reading = ui.reading
        val main = engineName(ui.run?.engine)
        val second = engineName(ui.compareRun?.engine)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Review  ${i + 1} of ${items.size}", Modifier.weight(1f))
            Text("Back", style = Type.small.copy(color = if (i > 0) Ink.Amber else Ink.Slate), modifier = Modifier.clickable(enabled = i > 0) { index = i - 1 }.padding(8.dp))
            Text("Skip", style = Type.small.copy(color = if (i < items.size - 1) Ink.Amber else Ink.Slate), modifier = Modifier.clickable(enabled = i < items.size - 1) { index = i + 1 }.padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        val (from, to, segment) = when (item) { is Difference -> Triple(item.from, item.to, item.segment); else -> (item as MedicineHit).let { Triple(it.from, it.to, it.segment) } }
        // The words around the place, so it can be judged in context.
        if (reading != null) {
            val range = reading.segments[segment]
            val before = reading.words.subList(maxOf(range.first, from - 6), from).joinToString(" ") { it.text }
            val here = reading.words.subList(from, to).joinToString(" ") { it.text }
            val after = reading.words.subList(to, minOf(range.last + 1, to + 6)).joinToString(" ") { it.text }
            Text(buildAnnotatedString {
                append(if (from - 6 > range.first) "… " else ""); append(before)
                withStyle(SpanStyle(color = Ink.Amber)) { append(if (here.isEmpty()) "  ▴  " else " $here ") }
                append(after); if (to + 6 <= range.last) append(" …")
            }, style = Type.reading)
        }
        Spacer(Modifier.height(14.dp))
        val (t0, t1) = when (item) {
            is Difference -> item.startMs to item.endMs
            else -> (item as MedicineHit).let { m -> (reading?.words?.get(m.from)?.startMs ?: 0L) to (reading?.words?.get(m.to - 1)?.endMs ?: 0L) }
        }
        if (ui.session?.hasAudio == true) {
            GhostButton("Hear this moment", { r.onPlayRange(t0, t1) }, Modifier.fillMaxWidth(), color = Ink.Amber, minHeight = 52.dp)
            Spacer(Modifier.height(10.dp))
        }
        when (item) {
            is Difference -> {
                Option("$second heard", item.suggestion.ifBlank { "(nothing)" }, item.medicine != null) { r.onAccept(item) }
                Spacer(Modifier.height(8.dp))
                Option("$main heard", item.baseText.ifBlank { "(nothing here)" }, false) { r.onKeep(item.id) }
            }
            is MedicineHit -> {
                Option("Medicine, spelled properly", item.canonical, true) { r.onAcceptMedicine(item) }
                Spacer(Modifier.height(8.dp))
                Option("$main wrote", item.heard, false) { r.onKeep(item.id) }
            }
        }
        Spacer(Modifier.height(8.dp))
        val heard = reading?.words?.subList(from, to)?.joinToString(" ") { it.text }.orEmpty()
        Text("Neither; type it", style = Type.body.copy(color = Ink.Amber), modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
            open(SessionSheet.Words(from, to, segment, if (item is Difference) item.suggestion else (item as MedicineHit).canonical, heard, t0, t1))
        }.padding(vertical = 14.dp))
    }
}

@Composable
private fun Option(label: String, text: String, medicine: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 60.dp).border(1.dp, Ink.Edge, Corner).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = Type.small)
        Text(text, style = Type.title.copy(color = if (medicine) Ink.Teal else Ink.Bone))
    }
}

/** Correct a word, a few words, or a whole line by typing. */
@Composable
fun WordsSheet(sh: SessionSheet.Words, ui: SessionUi, a: SessionActions, onClose: () -> Unit, open: (SessionSheet) -> Unit) {
    val r = a.review
    var text by remember(sh) { mutableStateOf(sh.current) }
    var addWord by remember(sh) { mutableStateOf(false) }
    Sheet(onClose) {
        Eyebrow(if (sh.line) "Correct this line" else "Correct"); Spacer(Modifier.height(6.dp))
        if (sh.heard.isNotBlank()) Text("${engineName(ui.run?.engine)} heard: ${sh.heard}", style = Type.note)
        Spacer(Modifier.height(10.dp))
        BasicTextField(text, { text = it }, singleLine = !sh.line, textStyle = Type.body, cursorBrush = SolidColor(Ink.Amber),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(14.dp))
        Spacer(Modifier.height(6.dp))
        Toggle("Add to my dictionary", "For medicines and names Scriber does not know yet", addWord) { addWord = it }
        if (ui.session?.hasAudio == true) GhostButton("Hear this moment", { r.onPlayRange(sh.startMs, sh.endMs) }, Modifier.fillMaxWidth(), color = Ink.Amber, minHeight = 52.dp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val reading = ui.reading
            val range = reading?.segments?.getOrNull(sh.segment)
            if (!sh.line && reading != null && range != null && !range.isEmpty()) {
                GhostButton("Whole line", {
                    val current = ui.lines.firstOrNull { it.segment == sh.segment }?.text.orEmpty()
                    val words = reading.words
                    open(SessionSheet.Words(range.first, range.last + 1, sh.segment, current, words.subList(range.first, range.last + 1).joinToString(" ") { it.text },
                        words[range.first].startMs, words[range.last].endMs, line = true))
                }, Modifier.weight(1f), minHeight = 52.dp)
            }
            AmberButton("Save", { onClose(); r.onEdit(sh.from, sh.to, sh.segment, text, addWord) }, Modifier.weight(1f))
        }
    }
}
