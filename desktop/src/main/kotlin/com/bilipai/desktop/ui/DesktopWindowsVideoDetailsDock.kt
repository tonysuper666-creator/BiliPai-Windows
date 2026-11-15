package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.appearance.DesktopWindowsUxDetailsPanel

// Reused from TON-1056 c61c12a (f403334 production seam); entry/policy geometry is unchanged.

internal data class DesktopWindowsVideoDock(val mounted: Boolean, val widthDp: Int)

/**
 * Pure. The only caller is DesktopWindowsVideoDetailsDockRow below, which the public Leaf calls once.
 * resolveDesktopPlayerLayout decides eligibility and the recommended width from the client area; the budget then
 * subtracts the real Row gap and the minimum stage from the Row's own pixel width. No fallback width exists:
 * not eligible or not enough room means not mounted and width 0. `intentOpen` is the user's choice (OPEN), never AUTO.
 */
internal fun resolveDesktopWindowsVideoDock(
    clientWidthDp: Int, clientHeightDp: Int, rowMaxWidthPx: Int, gapPx: Int, minStagePx: Int, density: Float,
    fullscreen: Boolean, intentOpen: Boolean, userPanelWidthDp: Int?,
): DesktopWindowsVideoDock {
    if (!intentOpen) return DesktopWindowsVideoDock(false, 0)
    val policy = resolveDesktopPlayerLayout(
        clientWidthDp, clientHeightDp, panelDefaultOpen = false, fullscreen = fullscreen,
        panel = DesktopWindowsUxDetailsPanel.OPEN, userPanelWidthDp = userPanelWidthDp,
    )
    val budget = kotlin.math.floor((rowMaxWidthPx - gapPx - minStagePx) / density).toInt()
    return if (policy.docked && budget >= DESKTOP_PLAYER_PANEL_MIN_DP)
        DesktopWindowsVideoDock(true, minOf(policy.panelWidthDp, budget)) else DesktopWindowsVideoDock(false, 0)
}

/**
 * The single Row producer of the video page. Width, fullscreen and panel changes only recompose it: there is no key() here
 * and no state of its own. State owners (open, tab, entryDecided, mounted) stay in the public Leaf and arrive by value/callback.
 * The entry effect runs once per owner: entryDecided is saveable in the Leaf, so resize, cover/restore and manual close never re-apply AUTO.
 * It only ever writes open=true, so a CLOSED preference can not undo the comment deep link.
 */
@Composable internal fun DesktopWindowsVideoDetailsDockRow(
    owner: Any, fullscreen: Boolean, active: Boolean, current: () -> Boolean,
    detailsOpen: Boolean, entryDecided: Boolean, entryWanted: (clientWidthDp: Int) -> Boolean,
    onEntryOpen: () -> Unit, onEntryDecided: () -> Unit, userPanelWidthDp: Int?,
    onMounted: (Boolean) -> Unit,
    content: @Composable RowScope.(DesktopWindowsVideoDock) -> Unit,
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val clientW = (window.width / density.density).toInt()
    val clientH = (window.height / density.density).toInt()
    val latest = rememberUpdatedState(Triple(entryWanted, onEntryOpen, onEntryDecided))
    val latestCurrent = rememberUpdatedState(current)
    LaunchedEffect(owner, active, clientW, clientH, entryDecided) {
        if (!entryDecided && active && latestCurrent.value() && clientW > 0 && clientH > 0) {
            if (latest.value.first(clientW)) latest.value.second()
            latest.value.third()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(if (fullscreen) 0.dp else 8.dp)) {
        val dock = resolveDesktopWindowsVideoDock(
            clientW, clientH, constraints.maxWidth, with(density) { 8.dp.roundToPx() },
            with(density) { DESKTOP_PLAYER_MIN_STAGE_DP.dp.roundToPx() }, density.density, fullscreen, detailsOpen, userPanelWidthDp,
        )
        SideEffect { onMounted(dock.mounted) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content(dock) }
    }
}
