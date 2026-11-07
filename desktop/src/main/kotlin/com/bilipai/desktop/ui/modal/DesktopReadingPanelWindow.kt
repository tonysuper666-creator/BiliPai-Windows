package com.bilipai.desktop.ui.modal

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.DialogWindow
import com.bilipai.desktop.appearance.DesktopWindowsOwnedDisplayScaleInputScope
import com.bilipai.desktop.ui.DesktopWindowsDialogEscapeBinding
import com.bilipai.desktop.ui.DesktopWindowsPopupMaterialHost
import com.bilipai.desktop.ui.LocalDesktopWindowsPlayerWindow
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/** The reading layer remains a real owned peer above MPV's heavyweight Canvas. No new player/queue owner. */
@Composable
internal fun DesktopReadingPanelWindow(
    title: String,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.(requestClose: () -> Unit) -> Unit,
) {
    val owner = LocalDesktopWindowsPlayerWindow.current ?: return
    if (!owner.isShowing || !owner.isDisplayable) return
    val density = LocalDensity.current
    val dismiss by rememberUpdatedState(onDismissRequest)
    var closing by remember(owner) { mutableStateOf(false) }
    val requestClose = { closing = true }
    val previousFocus = remember(owner) { KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner }
    val client = (owner as? RootPaneContainer)?.contentPane ?: owner
    fun clientBounds() = Rectangle(client.locationOnScreen, client.size)
    DialogWindow(
        create = {
            ComposeDialog(owner, Dialog.ModalityType.DOCUMENT_MODAL, owner.graphicsConfiguration).apply {
                this.title = title
                isUndecorated = true
                isResizable = false
                isTransparent = true
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                bounds = clientBounds()
            }
        },
        dispose = { it.dispose() },
        update = { it.title = title },
    ) {
        DisposableEffect(window, owner) {
            val geometry = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) { if (client.isShowing) window.bounds = clientBounds() }
                override fun componentResized(e: ComponentEvent) { if (client.isShowing) window.bounds = clientBounds() }
                override fun componentHidden(e: ComponentEvent) { dismiss() }
            }
            val closing = object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) { if (e.window === owner) dismiss() else requestClose() }
                override fun windowClosed(e: WindowEvent) { if (e.window === owner) dismiss() }
                override fun windowIconified(e: WindowEvent) { dismiss() }
            }
            owner.addComponentListener(geometry)
            client.addComponentListener(geometry)
            owner.addWindowListener(closing)
            window.addWindowListener(closing)
            val escape = DesktopWindowsDialogEscapeBinding(window.rootPane, ownsFocus = {
                val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                focused.focusedWindow === window && focused.focusOwner?.let {
                    SwingUtilities.getWindowAncestor(it) === window
                } == true
            }, dismiss = requestClose)
            onDispose {
                escape.close()
                owner.removeComponentListener(geometry)
                client.removeComponentListener(geometry)
                owner.removeWindowListener(closing)
                window.removeWindowListener(closing)
                EventQueue.invokeLater {
                    if (owner.isShowing && KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow === owner &&
                        previousFocus?.isShowing == true && SwingUtilities.getWindowAncestor(previousFocus) === owner) {
                        previousFocus.requestFocusInWindow()
                    }
                }
            }
        }
        CompositionLocalProvider(LocalDensity provides density) {
            DesktopWindowsOwnedDisplayScaleInputScope(window, owner, presented = true) {
                DesktopWindowsPopupMaterialHost(sourceOwner = window, owns = { window.isDisplayable && owner.isShowing }) {
                    // Original collection/queue own their lazy scroll containers. Do not give them infinite height.
                    DesktopModalLayer(DesktopModalPurpose.READING_PANEL, { dismiss() }, Modifier.fillMaxSize(),
                        scrollBody = false, closingRequested = closing) { content(requestClose) }
                }
            }
        }
    }
}
