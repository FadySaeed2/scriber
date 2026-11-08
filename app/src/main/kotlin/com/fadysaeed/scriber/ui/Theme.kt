package com.fadysaeed.scriber.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Extra colours the screens use for meaning: ready, in progress, warning, problem. */
@Immutable
data class Signals(
    val ready: Color,
    val busy: Color,
    val warn: Color,
    val problem: Color,
    val muted: Color,
    val line: Color,
    val raised: Color,
)

val LocalSignals = staticCompositionLocalOf {
    Signals(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

private val Ink = Color(0xFF0B0D0E)
private val Slate = Color(0xFF14181A)
private val SlateHigh = Color(0xFF1C2124)
private val Paper = Color(0xFFE8E4D8)
private val Tide = Color(0xFF3FB8A6)
private val Amber = Color(0xFFE0B25B)
private val Ember = Color(0xFFE0705B)

private val darkScheme = darkColorScheme(
    primary = Tide, onPrimary = Ink,
    secondary = Amber, onSecondary = Ink,
    background = Ink, onBackground = Paper,
    surface = Slate, onSurface = Paper,
    surfaceVariant = SlateHigh, onSurfaceVariant = Color(0xFF9AA3A6),
    outline = Color(0xFF2A3134),
    error = Ember, onError = Ink,
)

private val lightScheme = lightColorScheme(
    primary = Color(0xFF167A6D), onPrimary = Color.White,
    secondary = Color(0xFF9A6B12), onSecondary = Color.White,
    background = Color(0xFFF6F4EE), onBackground = Color(0xFF16191A),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF16191A),
    surfaceVariant = Color(0xFFEDEAE1), onSurfaceVariant = Color(0xFF5B6366),
    outline = Color(0xFFD8D4C8),
    error = Color(0xFFB4412C), onError = Color.White,
)

private val type = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, lineHeight = 36.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)

@Composable
fun ScriberTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) darkScheme else lightScheme
    val signals = if (dark) {
        Signals(Tide, Color(0xFF7FB3E0), Amber, Ember, Color(0xFF9AA3A6), Color(0xFF2A3134), SlateHigh)
    } else {
        Signals(Color(0xFF167A6D), Color(0xFF2F6FA8), Color(0xFF9A6B12), Color(0xFFB4412C), Color(0xFF5B6366), Color(0xFFD8D4C8), Color(0xFFEDEAE1))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalSignals provides signals) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
