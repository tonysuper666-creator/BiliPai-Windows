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
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.KeyStroke
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
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
    preferredHeightDp: Int = 720, presentationVisible: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val parentDensity = LocalDensity.current
    val owner = LocalDesktopWindowsPlayerWindow.current ?: return
    // Existing dialogs still dispose on hiding. SHARE explicitly keeps the same
    // AwtWindow/setContent group and only toggles its peer's visible property.
    if (presentationVisible == null && !owner.isShowing) return
    if (!owner.isDisplayable) return
    val dismiss = rememberUpdatedState(onDismissRequest)
    val escapeEnabled = rememberUpdatedState(dismissOnEscape)
    DialogWindow(
        visible = presentationVisible ?: true,
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
                    Rectangle(if (client.isShowing) client.locationOnScreen else
                        javax.swing.SwingUtilities.convertPoint(client, 0, 0, owner).apply { translate(owner.x, owner.y) },
                        client.size), insets,
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
            // SwingPanel's native text editor does not send its keys through
            // DialogWindow's Compose onKeyEvent. Keep Escape in this peer only.
            val escape = DesktopWindowsDialogEscapeBinding(window.rootPane, ownsFocus = {
                val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                escapeEnabled.value && window.owner === owner && owner.isDisplayable &&
                    window.isDisplayable && window.isShowing && focus.focusedWindow === window &&
                    focus.focusOwner?.let { SwingUtilities.getWindowAncestor(it) === window } == true
            }, dismiss = { dismiss.value() })
            onDispose { escape.close(); window.removeWindowListener(listener) }
        }
        // No update callback writes geometry: the user keeps normal drag/resize.
        CompositionLocalProvider(LocalDensity provides parentDensity) {
            com.bilipai.desktop.appearance.DesktopWindowsOwnedDisplayScaleInputScope(
                window, owner, presented = presentationVisible != false,
            ) {
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
}

/** A local Swing ancestor binding, never an application-wide keyboard dispatcher.
 * The caller retains the exact native window/focus and original dismissal authority. */
internal class DesktopWindowsDialogEscapeBinding(
    root: JRootPane,
    private val ownsFocus: () -> Boolean,
    private val dismiss: () -> Unit,
) : AutoCloseable {
    private val stroke = KeyStroke.getKeyStroke(AwtKeyEvent.VK_ESCAPE, 0, false)
    private val input = root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
    private val actions = root.actionMap
    private val previousLocal = if (input.keys()?.contains(stroke) == true) input.get(stroke) else null
    private val actionKey = Any()
    private var closed = false
    private val action = object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent) {
            check(SwingUtilities.isEventDispatchThread())
            if (!closed && ownsFocus()) dismiss()
        }
    }
    init {
        check(SwingUtilities.isEventDispatchThread())
        input.put(stroke, actionKey)
        actions.put(actionKey, action)
    }
    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        closed = true
        // Leave another owner's replacement untouched; do not restore an
        // inherited binding as a new local entry.
        if (input.get(stroke) === actionKey) {
            if (previousLocal == null) input.remove(stroke) else input.put(stroke, previousLocal)
        }
        if (actions.get(actionKey) === action) actions.remove(actionKey)
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
