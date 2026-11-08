package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LockScreen(problem: String?, onUnlock: () -> Unit) {
    val s = LocalSignals.current
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Scriber", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("Locked", style = MaterialTheme.typography.bodyLarge, color = s.muted)
        Spacer(Modifier.height(28.dp))
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodyMedium, color = s.warn, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
        }
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}
