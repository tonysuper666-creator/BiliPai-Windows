package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One row of a shortcut list: the keys, then what they do. */
@Composable
private fun DesktopTipsShortcutRow(row: DesktopShortcutListRow) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.keys, Modifier.width(200.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The shortcut tables of the shell and the player and the right-click hint (spec 3.7), as three groups of the Tips page. The shell
 * keys are live (the shell shortcut host of slice S2 handles every row), so that group has no caveat beyond what Ctrl+B means on
 * the video page. The player group says which keys wait for the new player interface, and the right-click hint is conditional on
 * that interface by its own words.
 */
@Composable
internal fun DesktopWindowsTipsShortcuts() {
    DesktopWindowsSettingsGroup(DesktopWindowsTipsStrings.SHELL_GROUP) {
        desktopShellShortcutListRows().forEach { DesktopTipsShortcutRow(it) }
        Text(DesktopShellStrings.CTRL_B_NOTE, style = MaterialTheme.typography.bodySmall)
    }
    DesktopWindowsSettingsGroup(DesktopWindowsTipsStrings.PLAYER_GROUP) {
        desktopPlayerShortcutListRows().forEach { DesktopTipsShortcutRow(it) }
        Text(DesktopWindowsTipsStrings.PLAYER_NOTE, style = MaterialTheme.typography.bodySmall)
    }
    DesktopWindowsSettingsGroup(DesktopWindowsTipsStrings.MOUSE_GROUP) {
        Text(DesktopWindowsTipsStrings.RIGHT_CLICK_HINT)
    }
}
