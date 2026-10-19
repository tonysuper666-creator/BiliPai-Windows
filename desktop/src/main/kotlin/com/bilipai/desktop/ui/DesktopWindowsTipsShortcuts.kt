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
 * The shortcut tables of the shell and the player and the right-click hint (spec 3.7), as three groups of the Tips page. The
 * lists are the planned tables, and the new keys and menus are enabled as their slices land, so one note above all three
 * groups says what works today (Space, Esc and F11 in the player) and that the rest comes with the new desktop interface.
 */
@Composable
internal fun DesktopWindowsTipsShortcuts() {
    Text(DesktopWindowsTipsStrings.NOTE, style = MaterialTheme.typography.bodySmall)
    DesktopWindowsSettingsGroup(DesktopWindowsTipsStrings.SHELL_GROUP) {
        desktopShellShortcutListRows().forEach { DesktopTipsShortcutRow(it) }
        Text(DesktopShellStrings.CTRL_B_NOTE, style = MaterialTheme.typography.bodySmall)
    }
    DesktopWindowsSettingsGroup(DesktopWindowsTipsStrings.PLAYER_GROUP) {
        desktopPlayerShortcutListRows().forEach { DesktopTipsShortcutRow(it) }
    }
    DesktopWindowsSettingsGroup(DesktopWindowsTipsStrings.MOUSE_GROUP) {
        Text(DesktopWindowsTipsStrings.RIGHT_CLICK_HINT)
    }
}
