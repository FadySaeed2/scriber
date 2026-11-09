package com.fadysaeed.scriber.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Corner = RoundedCornerShape(4.dp)

@Composable
fun AmberButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.heightIn(min = 52.dp).clip(Corner).background(if (enabled) Ink.Amber else Ink.Rule)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.button.copy(color = if (enabled) Ink.OnAmber else Ink.Ash), textAlign = TextAlign.Center) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink.Bone, enabled: Boolean = true, minHeight: Dp = 48.dp) {
    Box(
        modifier.heightIn(min = minHeight).clip(Corner).border(1.dp, if (enabled) Ink.Edge else Ink.Hairline, Corner)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.body.copy(color = if (enabled) color else Ink.Slate, fontSize = 14.sp), textAlign = TextAlign.Center) }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Ink.Hairline) {
    Box(modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(color))
}

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Ink.Ash) {
    Text(text.uppercase(), style = Type.eyebrow.copy(color = color), modifier = modifier)
}

/** Screen heading: a small label over a light, large title. */
@Composable
fun Heading(eyebrow: String, title: String, line: String? = null) {
    Column(Modifier.padding(top = 18.dp, bottom = 22.dp)) {
        Eyebrow(eyebrow)
        Spacer(Modifier.height(8.dp))
        Text(title, style = Type.display)
        if (line != null) { Spacer(Modifier.height(10.dp)); Text(line, style = Type.note) }
    }
}

@Composable
fun Section(label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 26.dp, bottom = 6.dp)) {
        Eyebrow(label)
        Spacer(Modifier.height(10.dp))
        Hairline()
    }
}

/** A label on the left and a value on the right, between hairlines. */
@Composable
fun Fact(label: String, value: String, valueColor: Color = Ink.Bone) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Type.note, modifier = Modifier.weight(0.42f))
            Text(value, style = Type.body.copy(color = valueColor), modifier = Modifier.weight(0.58f), textAlign = TextAlign.End, overflow = TextOverflow.Ellipsis)
        }
        Hairline()
    }
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) { Box(Modifier.size(size).clip(CircleShape).background(color)) }

/** A small status word with a coloured dot. */
@Composable
fun Status(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color); Spacer(Modifier.width(7.dp))
        Text(text, style = Type.small.copy(color = color), maxLines = 1)
    }
}

@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Ink.Rule)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(color))
    }
}

@Composable
fun Choice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 48.dp).clip(Corner).background(if (selected) Ink.Bone else Color.Transparent)
            .border(1.dp, if (selected) Ink.Bone else Ink.Rule, Corner).clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.body.copy(fontSize = 14.sp, color = if (selected) Ink.Black else Ink.Sand), maxLines = 1) }
}

/** A tappable row with a glyph, a label and an optional note. */
@Composable
fun ActionRow(glyph: Glyph, label: String, onClick: () -> Unit, note: String? = null, color: Color = Ink.Bone, enabled: Boolean = true) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(glyph, if (!enabled) Ink.Slate else if (color == Ink.Bone) Ink.Dust else color)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.body.copy(color = if (enabled) color else Ink.Slate))
                if (note != null) Text(note, style = Type.small)
            }
        }
        Hairline()
    }
}

@Composable
fun Toggle(label: String, note: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { onChange(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.body)
                if (note != null) Text(note, style = Type.small)
            }
            Box(Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(13.dp)).background(if (on) Ink.Amber else Ink.Rule).padding(3.dp),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) Ink.OnAmber else Ink.Dust))
            }
        }
        Hairline()
    }
}

enum class Glyph { BACK, CLOSE, PLUS, MIC, STOP, PAUSE, PLAY, IMPORT, SHARE, SAVE, TRASH, LOCK, CHECK, RENAME, REDO, WAVE, INFO, LIST, DOWNLOAD, UP, DOWN, COPY, EXPAND }

private val GLYPHS: Map<Glyph, Pair<String, String?>> = mapOf(
    Glyph.BACK to ("M15 18l-6-6 6-6" to null),
    Glyph.CLOSE to ("M18 6L6 18M6 6l12 12" to null),
    Glyph.PLUS to ("M12 5v14M5 12h14" to null),
    Glyph.MIC to ("M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5 11a7 7 0 0 0 14 0M12 18v3M9 21h6" to null),
    Glyph.STOP to ("M7 7h10v10H7z" to "M7 7h10v10H7z"),
    Glyph.PAUSE to ("M8 5v14M16 5v14" to null),
    Glyph.PLAY to ("M7 4.5v15l12-7.5z" to "M7 4.5v15l12-7.5z"),
    Glyph.IMPORT to ("M12 3v12M7.5 10.5L12 15l4.5-4.5M4 15v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4" to null),
    Glyph.SHARE to ("M18 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM8.6 13.5l6.8 4M15.4 6.5l-6.8 4" to null),
    Glyph.SAVE to ("M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h4M14 3v5h5v3M15 18h6M18.5 15.5L21 18l-2.5 2.5" to null),
    Glyph.TRASH to ("M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3" to null),
    Glyph.LOCK to ("M7 11V8a5 5 0 0 1 10 0v3M6 11h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1zM12 15v2" to null),
    Glyph.CHECK to ("M5 12.5l4.5 4.5L19 7" to null),
    Glyph.RENAME to ("M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16zM13.5 6.5l4 4" to null),
    Glyph.REDO to ("M15 14l5-5-5-5M20 9H9.5a5.5 5.5 0 0 0 0 11H13" to null),
    Glyph.WAVE to ("M3 12h2M7 8v8M11 5v14M15 9v6M19 11v2" to null),
    Glyph.INFO to ("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v5M12 8h.01" to null),
    Glyph.LIST to ("M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01" to null),
    Glyph.DOWNLOAD to ("M12 3v12M7.5 10.5L12 15l4.5-4.5M5 20h14" to null),
    Glyph.UP to ("M6 15l6-6 6 6" to null),
    Glyph.EXPAND to ("M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" to null),
    Glyph.DOWN to ("M6 9l6 6 6-6" to null),
    Glyph.COPY to ("M9 8h10a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM5 16V4a1 1 0 0 1 1-1h10" to null),
)

private val glyphCache = java.util.concurrent.ConcurrentHashMap<Glyph, Pair<Path, Path?>>()

private fun paths(g: Glyph) = glyphCache.getOrPut(g) {
    val (stroke, fill) = GLYPHS.getValue(g)
    PathParser().parsePathString(stroke).toPath() to fill?.let { PathParser().parsePathString(it).toPath() }
}

/** Line glyphs drawn in code, one stroke weight, so every icon looks drawn by the same hand. */
@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val k = s / 24f
        val (stroke, fill) = paths(glyph)
        val w = 1.85.dp.toPx() / k
        withTransform({ translate((this@Canvas.size.width - s) / 2f, (this@Canvas.size.height - s) / 2f); scale(k, k, Offset.Zero) }) {
            if (fill != null) drawPath(fill, color)
            drawPath(stroke, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun IconButton(glyph: Glyph, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Ink.Bone, size: Dp = 48.dp) {
    Box(
        modifier.size(size).clip(Corner).semantics { contentDescription = description }.clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, color) }
}

/** A short horizontal bar of recent loudness, newest on the right. */
@Composable
fun Levels(levels: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val n = 48
        val gap = size.width / n
        val bar = gap * 0.42f
        val padded = List(n - levels.size.coerceAtMost(n)) { 0f } + levels.takeLast(n)
        padded.forEachIndexed { i, v ->
            val h = (size.height * (0.06f + 0.94f * v.coerceIn(0f, 1f)))
            val x = i * gap + gap / 2
            drawLine(color.copy(alpha = 0.35f + 0.65f * (i.toFloat() / n)), Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), strokeWidth = bar, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun ColumnGap(content: @Composable () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
