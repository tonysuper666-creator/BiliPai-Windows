@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow

/** Window-sized settings content. Exactly this finite pane owns its scrolling. */
@Composable
internal fun DesktopWindowsSettingsPane(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(title, style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
    }
}

@Composable
internal fun DesktopWindowsSettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun DesktopWindowsSettingsSwitch(title: String, value: Boolean?, enabled: Boolean = true,
    description: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (value == null) Text("读取设置…", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = value == true, enabled = enabled && value != null, onCheckedChange = onChange)
    }
}

@Composable
internal fun <T> DesktopWindowsSettingsChoice(title: String, value: T?, choices: List<Pair<T, String>>,
    enabled: Boolean = true, allowUnselected: Boolean = false, onChange: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { (candidate, label) ->
                FilterChip(selected = value == candidate, enabled = enabled && (value != null || allowUnselected),
                    onClick = { if (value != candidate) onChange(candidate) }, label = { Text(label) })
            }
        }
    }
}

/** Loading is explicit. An initial schema default is never treated as a disk readback. */
@Composable
internal fun <T> desktopWindowsSettingsValue(flow: Flow<T>): T? = flow.collectAsState(initial = null).value
