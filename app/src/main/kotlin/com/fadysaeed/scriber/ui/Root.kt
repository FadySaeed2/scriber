package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

enum class Tab(val label: String) { Models("Models"), Lab("Lab"), Security("Security") }

@Composable
fun Frame(tab: Tab, onTab: (Tab) -> Unit, content: @Composable () -> Unit) {
    val s = LocalSignals.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp)
            ) { content() }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in Tab.entries) {
                val on = t == tab
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onTab(t) }.padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t.label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onSurface else s.muted)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(18.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface))
                }
            }
        }
    }
}
