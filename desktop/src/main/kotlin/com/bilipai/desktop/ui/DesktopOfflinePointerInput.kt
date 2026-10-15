package com.bilipai.desktop.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.android.purebilibili.feature.download.resolveOfflineSeekPositionFromTouch
import com.android.purebilibili.feature.download.resolveOfflineSeekProgressFromTouch

/** One owner for click and drag. Two competing recognizers can cancel a mouse
 * click before the original timeline commits it. Mapping stays in the complete
 * original offline policy; source admission remains in the existing player. */
internal suspend fun PointerInputScope.detectDesktopOfflineSeekGestures(
    durationMs: Long,
    isCurrent: () -> Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Long) -> Unit,
    onCancel: () -> Unit,
) {
    if (durationMs <= 0L) return
    awaitEachGesture {
        val down = awaitFirstDown()
        if (!isCurrent() || !down.position.x.isFinite() ||
            down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        var committed = false
        down.consume()
        try {
            onPreview(resolveOfflineSeekProgressFromTouch(down.position.x, size.width.toFloat()))
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || change.isConsumed || !isCurrent() || !change.position.x.isFinite()) return@awaitEachGesture
                if (change.type == PointerType.Mouse && change.pressed && !event.buttons.isPrimaryPressed) return@awaitEachGesture
                change.consume()
                onPreview(resolveOfflineSeekProgressFromTouch(change.position.x, size.width.toFloat()))
                if (!change.pressed) {
                    if (!isCurrent()) return@awaitEachGesture
                    onCommit(resolveOfflineSeekPositionFromTouch(change.position.x, size.width.toFloat(), durationMs))
                    committed = true
                    return@awaitEachGesture
                }
            }
        } finally {
            if (!committed) onCancel()
        }
    }
}

/** Rekey on the captured native source, not on changing progress/UI callbacks.
 * A source replacement cancels an in-flight gesture instead of seeking its successor. */
@Composable
internal fun Modifier.desktopOfflineSeekPointerInput(
    sourceIdentity: OwnedPlaybackSourceSnapshot?,
    durationMs: Long,
    isCurrent: () -> Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Long) -> Unit,
    onCancel: () -> Unit,
): Modifier {
    val latestCurrent = rememberUpdatedState(isCurrent)
    val latestPreview = rememberUpdatedState(onPreview)
    val latestCommit = rememberUpdatedState(onCommit)
    val latestCancel = rememberUpdatedState(onCancel)
    // Reads allocate a fresh receipt wrapper even while the same media stays
    // loaded. Key by its complete immutable value, retaining final source admission.
    val sourceKey = sourceIdentity?.let { it.sourceVersion to it.source }
    return pointerInput(sourceKey, durationMs) {
        if (sourceKey != null) detectDesktopOfflineSeekGestures(durationMs,
            { latestCurrent.value() }, { latestPreview.value(it) },
            { latestCommit.value(it) }, { latestCancel.value() })
    }
}


/** Native Canvas input can land below the transparent Compose foreground.
 * Like the ordinary video leaf, listen only on this one existing host/Canvas;
 * original controls retain their own pointer handlers and native source admission. */
internal class DesktopOfflineNativeInputBridge(
    private val surface: java.awt.Container,
    private val currentSource: () -> OwnedPlaybackSourceSnapshot?,
    private val ownsSource: (OwnedPlaybackSourceSnapshot) -> Boolean,
    private val onRevealControls: () -> Unit,
    private val onTogglePlayback: () -> Unit,
    private val canUseSurface: () -> Boolean = {
        val owner = javax.swing.SwingUtilities.getWindowAncestor(surface)
        surface.isShowing && owner != null &&
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === owner
    },
    private val canReceiveKey: () -> Boolean = {
        val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        focus === surface || focus != null && javax.swing.SwingUtilities.isDescendingFrom(focus, surface)
    },
    private val requestNativeFocus: () -> Unit = { surface.requestFocusInWindow(); Unit },
) : AutoCloseable {
    private val canvas = surface.components.filterIsInstance<java.awt.Canvas>().single()
    private val oldFocusable = surface.isFocusable
    private val oldCanvasEnabled = canvas.isEnabled
    private var closed = false
    private var press: OwnedPlaybackSourceSnapshot? = null
    private var heldSpace: OwnedPlaybackSourceSnapshot? = null
    private fun current(): OwnedPlaybackSourceSnapshot? =
        if (closed || !canUseSurface()) null else currentSource()?.takeIf(ownsSource)
    private fun matches(receipt: OwnedPlaybackSourceSnapshot): Boolean {
        val now = current() ?: return false
        return now.sourceVersion == receipt.sourceVersion && now.source == receipt.source && ownsSource(receipt)
    }
    private val mouse = object : java.awt.event.MouseAdapter() {
        override fun mouseEntered(event: java.awt.event.MouseEvent) { reveal() }
        override fun mouseMoved(event: java.awt.event.MouseEvent) { reveal() }
        private fun reveal() { if (current() != null) onRevealControls() }
        override fun mousePressed(event: java.awt.event.MouseEvent) {
            press = null
            if (event.isConsumed || event.button != java.awt.event.MouseEvent.BUTTON1 ||
                event.isControlDown || event.isAltDown || event.isMetaDown) return
            press = current() ?: return
            requestNativeFocus()
        }
        override fun mouseReleased(event: java.awt.event.MouseEvent) {
            val captured = press; press = null
            if (!event.isConsumed && event.button == java.awt.event.MouseEvent.BUTTON1 &&
                captured != null && matches(captured)) onRevealControls()
        }
    }
    private val focus = object : java.awt.event.FocusAdapter() {
        override fun focusLost(event: java.awt.event.FocusEvent) { press = null; heldSpace = null }
    }
    private val key = object : java.awt.event.KeyAdapter() {
        override fun keyPressed(event: java.awt.event.KeyEvent) {
            if (event.isConsumed || event.keyCode != java.awt.event.KeyEvent.VK_SPACE ||
                event.isControlDown || event.isAltDown || event.isMetaDown || event.isShiftDown ||
                !canReceiveKey()) return
            val captured = current() ?: return
            val held = heldSpace
            // A held key stays suppressed across source replacement until key-up.
            if (held != null) { event.consume(); return }
            if (held == null) {
                heldSpace = captured
                onRevealControls()
                if (matches(captured)) onTogglePlayback()
            }
            event.consume()
        }
        override fun keyReleased(event: java.awt.event.KeyEvent) {
            if (event.keyCode != java.awt.event.KeyEvent.VK_SPACE) return
            val captured = heldSpace; heldSpace = null
            if (captured != null && canReceiveKey() && matches(captured)) event.consume()
        }
    }
    init {
        check(java.awt.EventQueue.isDispatchThread())
        surface.isFocusable = true
        // MPV renders into its own child HWND below this Canvas. Windows sends
        // child input directly to that peer instead of bubbling AWT events.
        // Disabling only the render Canvas makes that subtree input-inert while
        // its existing enabled host receives mouse/focus/key events; video and
        // decoding continue on the same native session. No input hook is added.
        // https://learn.microsoft.com/en-us/windows/win32/winmsg/window-features
        canvas.isEnabled = false
        for (component in listOf(surface, canvas)) {
            component.addMouseListener(mouse); component.addMouseMotionListener(mouse)
            component.addKeyListener(key); component.addFocusListener(focus)
        }
    }
    override fun close() {
        check(java.awt.EventQueue.isDispatchThread())
        if (closed) return
        closed = true; press = null; heldSpace = null
        for (component in listOf(surface, canvas)) {
            component.removeMouseListener(mouse); component.removeMouseMotionListener(mouse)
            component.removeKeyListener(key); component.removeFocusListener(focus)
        }
        canvas.isEnabled = oldCanvasEnabled
        surface.isFocusable = oldFocusable
    }
}

/** Same Root native host and the original screen's actual state; no new player,
 * Store, window, system-wide hook or alternate playback policy. */
@Composable
internal fun DesktopOfflineNativeInput(
    player: DesktopOfflineMpvControl,
    enabled: Boolean,
    onRevealControls: () -> Unit,
    onTogglePlayback: () -> Unit,
) {
    val native = player.nativePlayer
    val window = LocalDesktopWindowsPlayerWindow.current
    val latestEnabled = rememberUpdatedState(enabled)
    val latestReveal = rememberUpdatedState(onRevealControls)
    val latestToggle = rememberUpdatedState(onTogglePlayback)
    androidx.compose.runtime.DisposableEffect(player, native.surface) {
        val input = DesktopOfflineNativeInputBridge(native.surface,
            { if (latestEnabled.value && player.isOwned()) native.currentSourceSnapshot() else null },
            { latestEnabled.value && player.isOwned() && native.ownsSourceSnapshot(it) },
            { latestReveal.value() }, { latestToggle.value() },
            canUseSurface = {
                window != null && native.surface.isShowing &&
                    javax.swing.SwingUtilities.getWindowAncestor(native.surface) === window &&
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === window
            })
        onDispose { input.close() }
    }
}
