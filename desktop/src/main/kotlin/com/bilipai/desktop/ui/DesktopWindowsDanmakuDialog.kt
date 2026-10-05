package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindow
import java.awt.Dialog
import java.awt.Insets
import java.awt.Rectangle
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.RootPaneContainer
import javax.swing.WindowConstants
import kotlin.math.roundToInt

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
    val owner = LocalDesktopWindowsPlayerWindow.current ?: return
    if (!owner.isShowing) return
    val dismiss = rememberUpdatedState(onDismissRequest)
    DialogWindow(
        create = {
            ComposeDialog(owner, Dialog.ModalityType.DOCUMENT_MODAL, owner.graphicsConfiguration).apply {
                this.title = title
                isResizable = true
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                // Realize decoration before first show. All geometry here is Java
                // logical coordinates; app density is converted exactly once.
                pack()
                val client = (owner as? RootPaneContainer)?.contentPane ?: owner
                val transform = owner.graphicsConfiguration.defaultTransform
                bounds = desktopWindowsPlayerDialogInitialBounds(
                    Rectangle(client.locationOnScreen, client.size), insets,
                    parentDensity.density, transform.scaleX, transform.scaleY,
                    preferredHeightDp = preferredHeightDp,
                )
            }
        },
        dispose = { it.dispose() },
        update = { it.title = title },
        onKeyEvent = { event ->
            if (dismissOnEscape && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                dismiss.value(); true
            } else false
        }) {
        DisposableEffect(window) {
            val listener = object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) { dismiss.value() }
            }
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener) }
        }
        // No update callback writes geometry: the user keeps normal drag/resize.
        CompositionLocalProvider(LocalDensity provides parentDensity) {
            DesktopWindowsPopupMaterialHost(sourceOwner = window, owns = { window.isDisplayable }) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalDesktopDanmakuSettingsViewport provides DesktopDanmakuSettingsViewport(
                        maxWidth.value.toInt().coerceAtLeast(1), maxHeight.value.toInt().coerceAtLeast(1))) {
                        content()
                    }
                }
            }
        }
    }
}

/** Initial outer bounds, including the realized title bar/borders. Compose app
 * density includes OS DPI; AWT client rectangles and native insets do not. */
internal fun desktopWindowsPlayerDialogInitialBounds(
    ownerClient: Rectangle,
    decoration: Insets,
    parentDensity: Float,
    ownerScaleX: Double,
    ownerScaleY: Double,
    preferredWidthDp: Int = 640,
    preferredHeightDp: Int = 720,
): Rectangle {
    require(ownerClient.width > 0 && ownerClient.height > 0)
    require(parentDensity.isFinite() && parentDensity > 0f)
    require(ownerScaleX.isFinite() && ownerScaleX > 0.0)
    require(ownerScaleY.isFinite() && ownerScaleY > 0.0)
    val width = (preferredWidthDp.coerceAtLeast(1).toDouble() * parentDensity / ownerScaleX +
        decoration.left.coerceAtLeast(0) + decoration.right.coerceAtLeast(0))
        .roundToInt().coerceIn(1, ownerClient.width)
    val height = (preferredHeightDp.coerceAtLeast(1).toDouble() * parentDensity / ownerScaleY +
        decoration.top.coerceAtLeast(0) + decoration.bottom.coerceAtLeast(0))
        .roundToInt().coerceIn(1, ownerClient.height)
    return Rectangle(ownerClient.x + (ownerClient.width - width) / 2,
        ownerClient.y + (ownerClient.height - height) / 2, width, height)
}
