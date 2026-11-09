package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fadysaeed.scriber.audio.RecordingState
import com.fadysaeed.scriber.core.SessionMode

/** The recording itself: time, loudness, the live preview, and three controls. */
@Composable
fun RecordingScreen(state: RecordingState, onBack: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.BACK, "Back to sessions; recording continues", onBack)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                Dot(if (state.paused) Ink.Amber else Ink.Ember, 9.dp)
                Spacer(Modifier.width(8.dp))
                Eyebrow(if (state.finishing) "Saving" else if (state.paused) "Paused" else modeName(state.mode), color = Ink.Sand)
            }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(18.dp))
            Text(clock(state.recordedMs), style = Type.hero)
            Spacer(Modifier.height(18.dp))
            Levels(if (state.paused) emptyList() else state.levels, if (state.paused) Ink.Ash else Ink.Amber)
            Spacer(Modifier.height(8.dp))
            Text("Saved every five seconds, encrypted. The screen can lock; recording carries on.", style = Type.small)
        }
        Spacer(Modifier.height(18.dp))
        Hairline()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            LaunchedEffect(state.live.size) { scroll.animateScrollTo(scroll.maxValue) }
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Live preview")
                state.liveNote?.let { Text(it, style = Type.small) }
                if (state.live.isEmpty() && state.liveNote == null) Text("Phrases appear here as each one ends.", style = Type.note)
                for (line in state.live) {
                    Row {
                        Text(clock(line.atMs), style = Type.tiny, modifier = Modifier.width(44.dp).padding(top = 4.dp))
                        Text(line.text, style = Type.reading)
                    }
                }
                state.problem?.let { Text(it, style = Type.body.copy(color = Ink.Ember)) }
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.paused) GhostButton("Resume", onResume, Modifier.weight(1f), minHeight = 52.dp)
            else GhostButton("Pause", onPause, Modifier.weight(1f), minHeight = 52.dp, enabled = !state.finishing)
            AmberButton(if (state.finishing) "Saving…" else "Stop and save", onStop, Modifier.weight(1.4f), enabled = !state.finishing)
        }
    }
}
