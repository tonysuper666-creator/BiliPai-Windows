package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.bilipai.desktop.player.MpvPlayer

/**
 * One existing MPV Canvas plus the existing same-window transparent foreground carrier.
 * The full actual player hit region lets the original Compose gesture body receive input.
 * Caller owns native lifetime; leaving this composition never stops or closes MPV.
 * Prepared source only: a real window/input acceptance is still required.
 */
@Composable
internal fun DesktopOriginalPlayerSurface(
    player: MpvPlayer,
    sourceVersion: Long?,
    isOwned: () -> Boolean,
    modifier: Modifier,
    foreground: @Composable () -> Unit,
) {
    val latestOwned by rememberUpdatedState(isOwned)
    val latestSourceVersion by rememberUpdatedState(sourceVersion)
    val latestForeground by rememberUpdatedState(foreground)
    var size by remember(player) { mutableStateOf(IntSize.Zero) }
    if (!isOwned()) return
    Box(modifier.background(Color.Black).onSizeChanged { size = it }) {
        SwingPanel(factory = { player.surface }, background = Color.Black, modifier = Modifier.fillMaxSize())
        DesktopShapedVideoCommandPopup(size, player.surface) {
            val version = latestSourceVersion
            // Null is an explicit entry-loading cover/control presentation, never native write permission.
            // Every control supplied by the caller must reject native writes until it owns a non-null version.
            if (latestOwned() && (version == null || player.ownsSourceVersion(version))) {
                key(player, version) {
                    Box(Modifier.fillMaxSize().desktopCommandHitRegion("original-player-surface")) {
                        latestForeground()
                    }
                }
            }
        }
    }
}
