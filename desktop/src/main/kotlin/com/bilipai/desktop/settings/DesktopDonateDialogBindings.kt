@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import java.awt.Component
import java.awt.Dialog
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JDialog
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Typed identity for the original settings-local sponsor overlay, not a NavKey. */
internal class DesktopSettingsDonateEntry(
    val entryToken: Long,
    val parentPage: DesktopSettingsPage,
    private val current: () -> Boolean,
) {
    fun ownsCurrent() = current()
}

/** Actual Root client geometry and required current-entry publication admission.
 * Mirrors the proven ComposeDialog/anchor pattern of DesktopCommandPopupWindow,
 * without borrowing its player input region, surface owner or modal state. */
internal class DesktopDonateDialogBindings(
    private val owner: Window,
    private val owns: () -> Boolean,
    private val admit: ((() -> Unit) -> Boolean),
    private val onDismiss: () -> Unit,
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private var dialog: ComposeDialog? = null // EDT only
    private var anchor: Component? = null // the real Root content pane, not a guessed size
    private var showScheduled = false
    private fun owned() = alive.get() && owns()
    private fun edt() = check(SwingUtilities.isEventDispatchThread()) { "Donate window requires actual EDT" }
    private val componentListener = object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = updateGeometry()
        override fun componentResized(event: ComponentEvent) = updateGeometry()
        override fun componentShown(event: ComponentEvent) = updateGeometry()
        override fun componentHidden(event: ComponentEvent) = updateGeometry()
    }
    private val hierarchyBounds = object : HierarchyBoundsAdapter() {
        override fun ancestorMoved(event: HierarchyEvent) = updateGeometry()
        override fun ancestorResized(event: HierarchyEvent) = updateGeometry()
    }
    private val rootWindowListener = object : WindowAdapter() {
        override fun windowIconified(event: WindowEvent) = updateGeometry()
        override fun windowDeiconified(event: WindowEvent) = updateGeometry()
        override fun windowClosed(event: WindowEvent) = close()
        override fun windowClosing(event: WindowEvent) {
            // The real Root may reject a close while an owned operation is finishing.
            // Only its ownership retirement or WINDOW_CLOSED retires this modal.
            if (!owned()) close() else updateGeometry()
        }
    }
    private val modalWindowListener = object : WindowAdapter() {
        override fun windowClosing(event: WindowEvent) = dismiss()
    }
    // Owner retirement can precede Compose disposal during the native modal event pump.
    private val retirement = Timer(40) { if (!owned() || !owner.isDisplayable) close() else updateGeometry() }

    fun dismiss() {
        edt()
        if (!owned()) return
        admit { if (owned()) onDismiss() }
    }
    fun create(context: CompositionLocalContext, content: @Composable () -> Unit) {
        edt();check(dialog == null) { "Original sponsor modal already created" }
        // Retirement can race composition disposal during account/update/close. It is
        // an ordinary rejected publication, not a synchronous UI-effect failure.
        if (!owned() || !owner.isDisplayable) return
        val actualAnchor = (owner as? RootPaneContainer)?.contentPane
            ?: error("Original sponsor modal requires the actual Root content pane")
        check(SwingUtilities.getWindowAncestor(actualAnchor) === owner)
        val actual = ComposeDialog(owner, Dialog.ModalityType.DOCUMENT_MODAL).apply {
            isUndecorated = true
            isResizable = false
            defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
            title = "支持 BiliPai"
            iconImages = owner.iconImages
            addWindowListener(modalWindowListener)
            compositionLocalContext = context
            setContent(onPreviewKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { dismiss();true }
                else false
            }) { content() }
        }
        var installed = false
        try {
            // Native allocation is followed by a second owner check before attachment/show.
            if (admit {
                if (owned()) {
                    dialog = actual;anchor = actualAnchor
                    owner.addComponentListener(componentListener);owner.addWindowListener(rootWindowListener)
                    actualAnchor.addComponentListener(componentListener);actualAnchor.addHierarchyBoundsListener(hierarchyBounds)
                    retirement.start();installed = true
                }
            } && installed) updateGeometry()
            else return // finally disposes the uninstalled native resource; no late publication
        } finally { if (!installed) actual.dispose() }
    }
    fun update(context: CompositionLocalContext) {
        edt()
        if (!owned()) { close();return }
        dialog?.compositionLocalContext = context
        updateGeometry()
    }
    private fun canShow(): Boolean {
        val actualAnchor = anchor ?: return false
        return owned() && owner.isShowing && (owner !is Frame || owner.extendedState and Frame.ICONIFIED == 0) &&
            actualAnchor.isShowing && SwingUtilities.getWindowAncestor(actualAnchor) === owner &&
            actualAnchor.width > 0 && actualAnchor.height > 0
    }
    private fun updateGeometry() {
        edt()
        val actual = dialog ?: return
        if (!owned()) { close();return }
        if (!canShow()) { actual.isVisible = false;return }
        val actualAnchor = checkNotNull(anchor)
        // Same actual AWT logical-unit geometry as the existing command overlay. Do not
        // multiply LocalDensity or monitor scale a second time on mixed-DPI screens.
        val origin = actualAnchor.locationOnScreen
        val bounds = Rectangle(origin.x, origin.y, actualAnchor.width, actualAnchor.height)
        var geometryAccepted = false
        val admitted = admit {
            if (owned() && dialog === actual && canShow()) {
                if (actual.bounds != bounds) actual.bounds = bounds
                if (actual.isAlwaysOnTop != owner.isAlwaysOnTop) actual.isAlwaysOnTop = owner.isAlwaysOnTop
                geometryAccepted = true
            }
        }
        if (!admitted) { close();return }
        if (!geometryAccepted) {
            if (!owned()) close() else actual.isVisible = false
            return
        }
        if (!actual.isVisible && !showScheduled) {
            showScheduled = true
            // DOCUMENT_MODAL enters a nested native event pump; defer show until the
            // Compose effect/Root admission has returned, so no publication gate is held.
            SwingUtilities.invokeLater {
                showScheduled = false
                var showAccepted = false
                val admitted = admit { if (dialog === actual && canShow()) showAccepted = true }
                if (!admitted) { close();return@invokeLater }
                // A false/retired permit never reaches the native modal event pump.
                // Recheck current native identity and volatile lifetime immediately after
                // releasing the short publication gate; setVisible must remain outside it.
                if (showAccepted && dialog === actual && canShow()) actual.isVisible = true
                else if (!owned()) close()
            }
        }
    }
    override fun close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            alive.set(false)
            SwingUtilities.invokeLater { close() }
            return
        }
        alive.set(false);retirement.stop();showScheduled = false
        owner.removeComponentListener(componentListener);owner.removeWindowListener(rootWindowListener)
        anchor?.removeComponentListener(componentListener);anchor?.removeHierarchyBoundsListener(hierarchyBounds)
        anchor = null
        val previous = dialog;dialog = null
        previous?.removeWindowListener(modalWindowListener);previous?.dispose()
    }
}

/** Only the original Dialog window leaf changes. The complete original Box/QR/text/
 * close body renders under the current Root's actual composition locals and density. */
@Composable internal fun DesktopOriginalDonateModal(
    bindings: DesktopDonateDialogBindings,
    content: @Composable () -> Unit,
) {
    val context = currentCompositionLocalContext
    val latestContent by rememberUpdatedState(content)
    val latestDensity by rememberUpdatedState(LocalDensity.current)
    DisposableEffect(bindings) {
        bindings.create(context) {
            CompositionLocalProvider(LocalDensity provides latestDensity) {
                Box(Modifier.fillMaxSize()) { latestContent() }
            }
        }
        onDispose { bindings.close() }
    }
    SideEffect { bindings.update(context) }
}
