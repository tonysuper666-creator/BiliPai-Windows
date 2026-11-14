package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.bilipai.desktop.appearance.DesktopWindowsUxDetailsPanel
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.appearance.DesktopWindowsUxCardInteractions
import com.bilipai.desktop.appearance.DesktopWindowsUxCommand
import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import com.bilipai.desktop.appearance.LocalDesktopWindowsUx
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_CONTROL_DP
import com.bilipai.desktop.ui.kit.DesktopMenuRow
import com.bilipai.desktop.ui.kit.desktopKitHairlineBorder

// Controls are enabled only alongside their real consumers. Player selection and details
// now feed the public Leaf's retained stage/dock; the default player layout remains LEGACY.
private const val RAIL_MODE_HAS_CONSUMER = true
private const val PLAYER_LAYOUT_HAS_CONSUMER = DESKTOP_PLAYER_OVERLAY_AVAILABLE
private const val HOME_LAYOUT_HAS_CONSUMER = true
private const val CARD_INTERACTIONS_HAS_CONSUMER = true

/**
 * The layout group of the settings (spec 3.7), shown inside the display-scale card of the appearance settings: the navigation rail mode,
 * the player layout, the home layout, the card right-click menu and hover, and the button that resets them. Each row
 * writes one flag of the windows_ux store through LocalDesktopWindowsUx once its consumer exists; a fixture that provides
 * nothing sees the read-only defaults, which refuse every command. The default of the player layout is the existing behaviour,
 * so that row changes nothing until a choice is made. The default of the rail row is not: it is
 * AUTO, the new navigation rail (spec 5.2, slice S1), so the rail replaces the 172 dp text block for everyone who has chosen
 * nothing; neither is the default of the home row: it is DESKTOP, the feed that takes the whole width the rail leaves
 * (spec 3.6, slice H1), so the feed is no longer held at the old 1280 dp for everyone who has chosen nothing (the single-column
 * display mode and a pinned column count keep the vendored feed and its cap); and neither is the default of the card row: it is
 * ON (spec 3.6, slice H2), so a Home card opens its menu on a right click, shows the hand cursor, drops the emoji of its menu
 * labels and has the 32 dp overflow button for everyone who has chosen nothing. The reset row
 * brings back those defaults; the choices that keep the old block, the old feed and the old cards are the classic text rail mode,
 * the classic home layout and the card interactions OFF. The rows are plain choices (no group container), because the card around
 * them is the container.
 */
@Composable
internal fun DesktopWindowsUxLayoutGroup() {
    val ux = LocalDesktopWindowsUx.current
    val settings by ux.settings.collectAsState()
    val writeError by ux.writeError.collectAsState()
    val colors = MaterialTheme.colorScheme
    val text = DesktopWindowsUxSettingsStrings
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text.TITLE, style = MaterialTheme.typography.titleMedium)
        Text(text.NOTE, style = MaterialTheme.typography.bodySmall)
        DesktopWindowsSettingsChoice(text.RAIL, settings.railMode, listOf(
            DesktopWindowsUxRailMode.AUTO to text.RAIL_AUTO,
            DesktopWindowsUxRailMode.EXPANDED to text.RAIL_EXPANDED,
            DesktopWindowsUxRailMode.COLLAPSED to text.RAIL_COLLAPSED,
            DesktopWindowsUxRailMode.CLASSIC_TEXT to text.RAIL_CLASSIC_TEXT,
        ), enabled = RAIL_MODE_HAS_CONSUMER) { ux.submit(DesktopWindowsUxCommand.SetRailMode(it)) }
        DesktopWindowsSettingsChoice(text.PLAYER, settings.playerLayout, listOf(
            DesktopWindowsUxPlayerLayout.DESKTOP to text.PLAYER_DESKTOP,
            DesktopWindowsUxPlayerLayout.LEGACY to text.PLAYER_LEGACY,
        ), enabled = PLAYER_LAYOUT_HAS_CONSUMER) { ux.submit(DesktopWindowsUxCommand.SetPlayerLayout(it)) }
        DesktopWindowsSettingsChoice(text.DETAILS, settings.detailsPanel, listOf(
            DesktopWindowsUxDetailsPanel.AUTO to text.DETAILS_AUTO,
            DesktopWindowsUxDetailsPanel.OPEN to text.DETAILS_OPEN,
            DesktopWindowsUxDetailsPanel.CLOSED to text.DETAILS_CLOSED,
        )) { ux.submit(DesktopWindowsUxCommand.SetDetailsPanel(it)) }
        // Keep drag previews local; commit only on release through the existing serialized store writer.
        var width by remember(settings.detailsPanelWidthDp) { mutableStateOf((settings.detailsPanelWidthDp ?: 400).toFloat()) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text.DETAILS_WIDTH, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(settings.detailsPanelWidthDp?.let { text.width(it) } ?: text.DETAILS_WIDTH_AUTO)
            TextButton(onClick = { ux.submit(DesktopWindowsUxCommand.SetDetailsPanelWidth(null)) }) { Text(text.DETAILS_WIDTH_RESET) }
        }
        Slider(value = width, onValueChange = { width = it }, valueRange = 320f..560f,
            onValueChangeFinished = { ux.submit(DesktopWindowsUxCommand.SetDetailsPanelWidth(width.roundToInt())) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = text.DETAILS_WIDTH })
        Text(text.DETAILS_NOTE, style = MaterialTheme.typography.bodySmall)
        DesktopWindowsSettingsChoice(text.HOME, settings.homeLayout, listOf(
            DesktopWindowsUxHomeLayout.DESKTOP to text.HOME_DESKTOP,
            DesktopWindowsUxHomeLayout.CLASSIC to text.HOME_CLASSIC,
        ), enabled = HOME_LAYOUT_HAS_CONSUMER) { ux.submit(DesktopWindowsUxCommand.SetHomeLayout(it)) }
        DesktopWindowsSettingsChoice(text.CARDS, settings.cardInteractions, listOf(
            DesktopWindowsUxCardInteractions.ON to text.CARDS_ON,
            DesktopWindowsUxCardInteractions.OFF to text.CARDS_OFF,
        ), enabled = CARD_INTERACTIONS_HAS_CONSUMER) { ux.submit(DesktopWindowsUxCommand.SetCardInteractions(it)) }
        DesktopMenuRow(
            label = text.RESET, onClick = { ux.submit(DesktopWindowsUxCommand.ResetLayout) },
            modifier = Modifier.width(176.dp).desktopKitHairlineBorder(colors.outlineVariant, DESKTOP_KIT_RADIUS_CONTROL_DP.dp),
        )
        (settings.error ?: writeError)?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
    }
}
