@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.android.purebilibili.feature.video.player.PlayerKeyAction
import com.android.purebilibili.feature.video.player.resolvePlayerKeyAction
import com.bilipai.desktop.data.DesktopStoryOwner
import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.math.abs
import kotlin.math.max

/** The existing native player and Root keyboard actions; this binding creates no player or window. */
class DesktopStoryNativeInputBinding(
    val surface: Component,
    val sourceVersion: () -> Long,
    val owns: (DesktopStoryOwner) -> Boolean,
    val onPlayerKey: (PlayerKeyAction, expectedSourceVersion: Long) -> Boolean,
)

internal data class DesktopStoryNativeInputToken(val owner: DesktopStoryOwner, val sourceVersion: Long)

/**
 * AWT forwards mouse-wheel events to the Compose pager already. Only native mouse drag
 * and focused keyboard events need bridging. There are no native/system-wide hooks.
 */
internal class DesktopStoryNativeInputBridge(
    private val surface: Component,
    private val token: () -> DesktopStoryNativeInputToken?,
    private val onPageStep: (Int, DesktopStoryNativeInputToken) -> Boolean,
    private val onPlayerKey: (PlayerKeyAction, DesktopStoryNativeInputToken) -> Boolean,
    private val onNativeFocus: () -> Unit = {},
    private val isWindowActive: () -> Boolean = {
        val window = SwingUtilities.getWindowAncestor(surface)
        surface.isShowing && window != null && KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow === window
    },
) : AutoCloseable {
    private data class Drag(val token: DesktopStoryNativeInputToken, val start: Point)
    private var drag: Drag? = null
    private var focusedOwner: DesktopStoryOwner? = null
    private var closed = false
    private val heldKeys = mutableSetOf<Int>()
    private val mouseListener = AWTEventListener(::mouse)
    private val keyDispatcher = java.awt.KeyEventDispatcher(::key)

    init {
        Toolkit.getDefaultToolkit().addAWTEventListener(mouseListener, AWTEvent.MOUSE_EVENT_MASK)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
    }

    private fun within(component: Component) = component === surface || SwingUtilities.isDescendingFrom(component, surface)
    private fun current() = if (closed || !isWindowActive()) null else token()
    private fun point(event: MouseEvent) = SwingUtilities.convertPoint(event.component, event.point, surface)

    internal fun mouse(event: AWTEvent) {
        val mouse = event as? MouseEvent ?: return
        if (closed) return
        if (mouse.id == MouseEvent.MOUSE_PRESSED) {
            if (!within(mouse.component) || mouse.button != MouseEvent.BUTTON1) {
                // Search/comment controls keep all keyboard input after a click outside the video.
                focusedOwner = null; drag = null; heldKeys.clear(); return
            }
            val owner = current() ?: run { focusedOwner = null; drag = null; return }
            focusedOwner = owner.owner
            onNativeFocus()
            mouse.component.requestFocusInWindow()
            drag = Drag(owner, point(mouse))
        } else if (mouse.id == MouseEvent.MOUSE_RELEASED && mouse.button == MouseEvent.BUTTON1) {
            val start = drag ?: return
            drag = null
            if (!within(mouse.component) || current() != start.token) return
            val end = point(mouse); val dx = end.x - start.start.x; val dy = end.y - start.start.y
            val distance = max(40, surface.height / 8)
            if (abs(dy) < distance || abs(dy) <= abs(dx)) return
            if (onPageStep(if (dy < 0) 1 else -1, start.token)) mouse.consume()
        }
    }

    internal fun key(event: KeyEvent): Boolean {
        if (closed) return false
        if (event.id == KeyEvent.KEY_TYPED) {
            // Compose text focus can outlive an AWT click. A consumed shortcut must not type
            // its character into the previous editor while its corresponding key is held.
            return heldKeys.isNotEmpty() && current()?.owner == focusedOwner && focusedOwner != null
        }
        if (event.id == KeyEvent.KEY_RELEASED) {
            val held = heldKeys.remove(event.keyCode)
            return held && current()?.owner == focusedOwner && focusedOwner != null
        }
        if (event.id != KeyEvent.KEY_PRESSED) return false
        if (event.keyCode == KeyEvent.VK_TAB || event.isControlDown || event.isAltDown || event.isMetaDown) {
            if (event.keyCode == KeyEvent.VK_TAB) { focusedOwner = null; drag = null; heldKeys.clear() }
            return false
        }
        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner is JTextComponent) return false
        val owner = current() ?: run { heldKeys.clear(); return false }
        if (focusedOwner != owner.owner) { heldKeys.clear(); return false }
        if (event.keyCode in heldKeys) return true
        val direction = if (event.isShiftDown) 0 else when (event.keyCode) {
            KeyEvent.VK_DOWN, KeyEvent.VK_PAGE_DOWN -> 1
            KeyEvent.VK_UP, KeyEvent.VK_PAGE_UP -> -1
            else -> 0
        }
        val handled = if (direction != 0) onPageStep(direction, owner)
        else resolvePlayerKeyAction(androidx.compose.ui.input.key.KeyEvent(
            Key(event.keyCode, event.keyLocation.takeUnless { it == KeyEvent.KEY_LOCATION_UNKNOWN } ?: KeyEvent.KEY_LOCATION_STANDARD),
            KeyEventType.KeyDown, event.keyChar.code,
            isAltPressed = event.isAltDown, isCtrlPressed = event.isControlDown,
            isMetaPressed = event.isMetaDown, isShiftPressed = event.isShiftDown, nativeEvent = event,
        ))?.let { onPlayerKey(it, owner) } == true
        if (handled) { heldKeys += event.keyCode; event.consume() }
        return handled
    }

    override fun close() {
        if (closed) return
        closed = true; drag = null; focusedOwner = null; heldKeys.clear()
        Toolkit.getDefaultToolkit().removeAWTEventListener(mouseListener)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher)
    }
}
