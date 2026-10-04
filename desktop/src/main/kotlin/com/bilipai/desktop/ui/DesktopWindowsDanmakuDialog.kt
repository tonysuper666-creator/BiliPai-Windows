package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/** A real owned Windows dialog keeps the original controls above the heavyweight MPV Canvas.
 * It carries no playback or settings authority and never detaches the playing native surface. */
@Composable
internal fun DesktopWindowsDanmakuDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    content: @Composable () -> Unit,
) {
    DesktopWindowsPlayerDialog("弹幕设置", onDismissRequest, properties.dismissOnBackPress, content = content)
}

@Composable
internal fun DesktopWindowsPlayerDialog(
    title: String, onDismissRequest: () -> Unit, dismissOnEscape: Boolean = true,
    preferredHeightDp: Int = 720, content: @Composable () -> Unit,
) {
    val parentDensity = LocalDensity.current
    val info = LocalWindowInfo.current
    val size = info.containerSize
    val systemDensity = size.width / info.containerDpSize.width.value.coerceAtLeast(1f)
    val windowScale = parentDensity.density / systemDensity.coerceAtLeast(.1f)
    val viewport = DesktopDanmakuSettingsViewport(
        (size.width / parentDensity.density).toInt(), (size.height / parentDensity.density).toInt())
    val state = rememberDialogState(size = DpSize(
        (minOf(640, viewport.screenWidthDp).coerceAtLeast(1) * windowScale).dp,
        (minOf(preferredHeightDp, viewport.screenHeightDp).coerceAtLeast(1) * windowScale).dp))
    DialogWindow(onCloseRequest = onDismissRequest, state = state, title = title, resizable = true,
        onKeyEvent = { event ->
            if (dismissOnEscape && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismissRequest(); true
            } else false
        }) {
        CompositionLocalProvider(LocalDensity provides parentDensity) {
            BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                CompositionLocalProvider(LocalDesktopDanmakuSettingsViewport provides DesktopDanmakuSettingsViewport(
                    maxWidth.value.toInt().coerceAtLeast(1), maxHeight.value.toInt().coerceAtLeast(1))) {
                    content()
                }
            }
        }
    }
}
