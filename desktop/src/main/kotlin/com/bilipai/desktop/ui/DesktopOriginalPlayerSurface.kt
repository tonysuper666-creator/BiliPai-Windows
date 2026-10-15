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

/** Windows layered-window input also tests pixel alpha. The one-code-value
 * backdrop keeps full-player gestures reachable after controls fade away.
 * Card-only and decorative popups retain their existing click-through behavior.
 * https://learn.microsoft.com/en-us/windows/win32/winmsg/window-features
 */
@Composable
internal fun DesktopOriginalPlayerInputLayer(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()
        .background(Color.Black.copy(alpha = 1f / 255f))
        .desktopCommandHitRegion("original-player-surface")) {
        content()
    }
}

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
    DesktopOriginalPlayerSurface(player, sourceVersion, isOwned, modifier, foreground, null)
}

@Composable
internal fun DesktopOriginalPlayerSurface(
    player: MpvPlayer,
    sourceVersion: Long?,
    isOwned: () -> Boolean,
    modifier: Modifier,
    foreground: @Composable () -> Unit,
    onWindowAvailability: ((Any, Boolean) -> Unit)?,
) {
    DesktopOriginalPlayerSurface(player, sourceVersion, isOwned, modifier, foreground, onWindowAvailability, null)
}

@Composable
internal fun DesktopOriginalPlayerSurface(
    player: MpvPlayer, sourceVersion: Long?, isOwned: () -> Boolean, modifier: Modifier,
    foreground: @Composable () -> Unit, onWindowAvailability: ((Any, Boolean) -> Unit)?,
    viewport: DesktopOriginalNativeViewportState?,
) {
    DesktopOriginalPlayerSurface(player,sourceVersion,isOwned,modifier,foreground,onWindowAvailability,viewport,null,true)
}

@Composable
internal fun DesktopOriginalPlayerSurface(
    player:MpvPlayer,sourceVersion:Long?,isOwned:()->Boolean,modifier:Modifier,
    foreground:@Composable () -> Unit,onWindowAvailability:((Any,Boolean)->Unit)?,
    viewport:DesktopOriginalNativeViewportState?,onNativeWindowAvailability:((java.awt.Window,Boolean)->Unit)?,
    presented:Boolean,
) {
    val latestOwned by rememberUpdatedState(isOwned)
    val latestSourceVersion by rememberUpdatedState(sourceVersion)
    val latestForeground by rememberUpdatedState(foreground)
    var size by remember(player) { mutableStateOf(IntSize.Zero) }
    val anchor = remember(player, viewport) {
        if (viewport == null) player.surface else viewport.createContainer(player.surface)
    }
    if (!isOwned()) return
    Box(modifier.background(Color.Black).onSizeChanged { size = it }) {
        SwingPanel(factory = { anchor }, background = Color.Black, modifier = Modifier.fillMaxSize())
        DesktopShapedVideoCommandPopup(size, anchor, onWindowAvailability, onNativeWindowAvailability, presented) {
            val version = latestSourceVersion
            // Null is an explicit entry-loading cover/control presentation, never native write permission.
            // Every control supplied by the caller must reject native writes until it owns a non-null version.
            if (latestOwned() && (version == null || player.ownsSourceVersion(version))) {
                key(player) {
                    DesktopOriginalPlayerInputLayer { latestForeground() }
                }
            }
        }
    }
}
