package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LockScreen(problem: String?, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.Black).padding(28.dp), verticalArrangement = Arrangement.Bottom) {
        GlyphIcon(Glyph.LOCK, Ink.Amber, 30.dp)
        Spacer(Modifier.height(20.dp))
        Text("Scriber", style = Type.hero)
        Spacer(Modifier.height(10.dp))
        Text("Locked. Recordings stay encrypted on this phone.", style = Type.note)
        if (problem != null) { Spacer(Modifier.height(16.dp)); Text(problem, style = Type.body.copy(color = Ink.Amber)) }
        Spacer(Modifier.height(28.dp))
        AmberButton("Unlock", onUnlock, Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}
