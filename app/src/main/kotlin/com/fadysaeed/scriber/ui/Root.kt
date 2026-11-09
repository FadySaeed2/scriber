package com.fadysaeed.scriber.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class Tab(val label: String) { Sessions("Sessions"), Models("Models"), Lab("Lab"), Settings("Settings") }

/** Tabbed screens: scrolling content on true black, tabs as words on a hairline. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Frame(tab: Tab, onTab: (Tab) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    // While typing, the tabs step aside and the page ends above the keyboard.
    val typing = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize().background(Ink.Black).imePadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp)) {
            content()
            Spacer(Modifier.height(28.dp))
        }
        if (!typing) Hairline()
        if (!typing) Row(Modifier.fillMaxWidth().background(Ink.Black).navigationBarsPadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in Tab.entries) {
                val on = t == tab
                Column(
                    Modifier.weight(1f).heightIn(min = 56.dp).clickable(role = Role.Tab) { onTab(t) }.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t.label, style = Type.body.copy(color = if (on) Ink.Bone else Ink.Ash))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(16.dp).height(2.dp).background(if (on) Ink.Amber else Color.Transparent))
                }
            }
        }
    }
}

/** A full screen above the tabs, with a back arrow. */
@Composable
fun Page(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.Black).imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.BACK, "Back", onBack)
            Text(title, style = Type.title, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            actions()
        }
        Hairline()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            content()
            Spacer(Modifier.height(28.dp))
        }
        if (footer != null) {
            Hairline()
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) { footer() }
        }
    }
}

/** A panel that rises from the bottom over a dimmed screen. */
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().background(Ink.Well)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            content()
        }
    }
}

@Composable
fun BoxScope.Toast(text: String, onDismiss: () -> Unit) {
    Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp, start = 20.dp, end = 20.dp)
        .background(Ink.Bone, Corner).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text, style = Type.body.copy(color = Ink.Black))
    }
}
