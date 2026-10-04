package com.bilipai.desktop.appearance

import java.awt.*
import java.awt.event.*
import javax.swing.SwingUtilities

internal fun desktopWindowsScaleKey(event: KeyEvent): DesktopWindowsScaleCommand? {
    if (!event.isControlDown || event.isAltDown || event.isMetaDown) return null
    return when (event.keyCode) {
        KeyEvent.VK_PLUS, KeyEvent.VK_EQUALS, KeyEvent.VK_ADD -> DesktopWindowsScaleCommand.Adjust(1)
        KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> DesktopWindowsScaleCommand.Adjust(-1)
        KeyEvent.VK_0, KeyEvent.VK_NUMPAD0 -> DesktopWindowsScaleCommand.Reset
        else -> null
    }
}

internal class DesktopWindowsScaleWheelAccumulator {
    private var remainder = 0.0
    fun reset() { remainder = 0.0 }
    fun steps(rotation: Double): Int {
        if (!rotation.isFinite()) return 0
        remainder = (remainder - rotation).coerceIn(-20.0, 20.0)
        val whole = remainder.toInt()
        remainder -= whole
        return whole
    }
}

/** Standard JVM AWT registrations, with exact owned Main Window filtering BEFORE decoding.
 * No OS keyboard hook, no global input injection, and no foreign-window input is inspected.
 * One key path also sees the existing native Canvas focus. Ctrl-wheel mutation is solely here;
 * the Main Compose root only suppresses its ordinary scroll handling for that same gesture.
 */
internal class DesktopWindowsDisplayScaleInput(
    private val host: Window,
    private val controller: DesktopWindowsDisplayScaleController,
    private val owns: () -> Boolean,
) : AutoCloseable {
    private var closed = false
    private val wheel = DesktopWindowsScaleWheelAccumulator()
    private val consumedKeys = mutableSetOf<Int>()
    private fun eligible(component: Component?): Boolean {
        if (component == null || SwingUtilities.getWindowAncestor(component) !== host) return false
        return !closed && host.isDisplayable && host.isShowing && host.isActive && owns()
    }
    private val keys = KeyEventDispatcher { event ->
        if (!eligible(event.component)) return@KeyEventDispatcher false
        if (event.id == KeyEvent.KEY_RELEASED && consumedKeys.remove(event.keyCode)) {
            event.consume(); return@KeyEventDispatcher true
        }
        if (event.id == KeyEvent.KEY_TYPED && consumedKeys.isNotEmpty() && event.keyChar in charArrayOf('+', '=', '-', '0')) {
            event.consume(); return@KeyEventDispatcher true
        }
        val command = desktopWindowsScaleKey(event) ?: return@KeyEventDispatcher false
        if (event.id != KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false
        if (!controller.submitWindow(command, owns)) return@KeyEventDispatcher false
        consumedKeys.add(event.keyCode)
        event.consume(); true
    }
    private val focus = object : WindowAdapter() {
        override fun windowLostFocus(event: WindowEvent) { consumedKeys.clear(); wheel.reset() }
    }
    private val wheels = AWTEventListener { actual ->
        val event = actual as? MouseWheelEvent ?: return@AWTEventListener
        if (!eligible(event.component)) return@AWTEventListener
        if (!event.isControlDown || event.isAltDown || event.isMetaDown) return@AWTEventListener
        val steps = wheel.steps(event.preciseWheelRotation)
        if (steps != 0 && !controller.submitWindow(DesktopWindowsScaleCommand.Adjust(steps), owns)) return@AWTEventListener
        event.consume()
    }
    init {
        check(EventQueue.isDispatchThread())
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keys)
        Toolkit.getDefaultToolkit().addAWTEventListener(wheels, AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        host.addWindowFocusListener(focus)
    }
    override fun close() {
        check(EventQueue.isDispatchThread())
        if (closed) return
        closed = true
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keys)
        Toolkit.getDefaultToolkit().removeAWTEventListener(wheels)
        host.removeWindowFocusListener(focus)
        consumedKeys.clear(); wheel.reset()
    }
}
