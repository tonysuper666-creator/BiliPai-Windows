package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The actual Root diagnostic actor, shared by recoverable page errors; never a second log sink. */
internal val LocalDesktopPageErrorReporter = staticCompositionLocalOf<((String, Throwable?) -> Unit)?> { null }

/** A pending return belongs to this error presentation, never to a later card or retired page. */
internal class DesktopWindowsPageErrorFocusReturn {
    private var mounted = false
    private var pending = false

    fun mount() { mounted = true; pending = false }

    fun release(stillCurrent: Boolean, foregroundOwned: Boolean): Boolean {
        mounted = false
        pending = stillCurrent && foregroundOwned
        return pending
    }

    fun ownerActivated(stillCurrent: Boolean): Boolean {
        val restore = pending && !mounted && stillCurrent
        pending = false
        return restore
    }
}

/** Fill the existing player overlay, keeping the recovery card at the centre of the video stage. */
@Composable
internal fun DesktopWindowsPageErrorOverlay(
    onUnhandledKey: (KeyEvent) -> Boolean = { false },
    onMounted: () -> Unit = {},
    onReturnFocus: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val mounted by rememberUpdatedState(onMounted)
    val returnFocus by rememberUpdatedState(onReturnFocus)
    val shortcutHost = LocalDesktopShellShortcutHost.current
    DisposableEffect(shortcutHost) {
        val navigation = shortcutHost?.admitPageErrorNavigation()
        mounted()
        onDispose {
            navigation?.close()
            returnFocus()
        }
    }
    // The retained native carrier starts with an empty shape and cannot take keyboard focus.
    // Admit drawing and input only inside this failed stage while its recovery card is mounted.
    // This does not change AWT modality or create another window/playback owner.
    DesktopCommandModalRegion(present = true)
    // Bubble only after the card's own controls; Tab/Enter/Space keep their normal button behavior.
    Box(Modifier.fillMaxSize().onKeyEvent(onUnhandledKey), contentAlignment = Alignment.Center) { content() }
}

/** Contract player.overlay.error_card: 360dp, or the available width on a smaller stage. */
@Composable
internal fun DesktopWindowsPageErrorCard(message: String, onRetry: () -> Unit, onLogin: (() -> Unit)? = null) {
    val retryFocus = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        // Native activation is guarded by the existing popup's owner.isActive policy.
        // Also runs when a card first shown in the background gains foreground focus.
        if (windowFocused) retryFocus.requestFocus()
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 360.dp).fillMaxWidth()
            .desktopCommandHitRegion("windows-page-error-card").semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text("暂时无法播放", style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("重试") }
                    if (onLogin != null) TextButton(onClick = onLogin) { Text("登录并返回") }
                }
            }
        }
    }
}
