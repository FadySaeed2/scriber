package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ScreenTitle(title: String, line: String) {
    Column(Modifier.padding(top = 8.dp, bottom = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = LocalSignals.current.muted)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LocalSignals.current.muted,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp, start = 2.dp),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, accent?.copy(alpha = 0.55f) ?: LocalSignals.current.line, shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun Chip(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color, 6)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
    }
}

/** A label on the left and a value on the right, for facts like "Memory  11.2 GB". */
@Composable
fun Fact(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalSignals.current.muted, modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.weight(0.55f), overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(LocalSignals.current.raised)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}
