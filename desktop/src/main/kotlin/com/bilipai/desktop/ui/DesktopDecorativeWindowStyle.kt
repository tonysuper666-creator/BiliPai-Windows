package com.bilipai.desktop.ui

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import javax.swing.SwingUtilities

/** Pure bit policy is independently testable without loading user32. Requires
 * the existing transparent AWT peer to be layered already: it never invents a
 * new alpha renderer or replaces the Java2D/Skiko transparency implementation. */
internal object DesktopDecorativeWindowStylePolicy {
    const val LAYERED = 0x00080000
    const val TRANSPARENT = 0x00000020
    const val NO_ACTIVATE = 0x08000000
    fun requested(current: Int): Int? = if (current and LAYERED == 0) null else current or TRANSPARENT or NO_ACTIVATE
    fun acknowledged(actual: Int): Boolean = actual and (LAYERED or TRANSPARENT or NO_ACTIVATE) == (LAYERED or TRANSPARENT or NO_ACTIVATE)
}

/** Only the carrier's exact owned peer is touched, on EDT. No global input hooks,
 * window enumeration, focus request, process search or always-on-top override. */
internal object DesktopDecorativeWindowStyle {
    private val log = java.util.logging.Logger.getLogger("DesktopDecorativeWindowStyle")
    private interface User32 : StdCallLibrary {
        fun GetWindowLongW(window: Pointer, index: Int): Int
        fun SetWindowLongW(window: Pointer, index: Int, value: Int): Int
        fun SetWindowPos(window: Pointer, after: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean
    }
    private val native by lazy { Native.load("user32", User32::class.java) }
    fun applyTo(window: Window): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (!window.isDisplayable || window.focusableWindowState || window.isAutoRequestFocus) return false
        return runCatching {
            val pointer = Native.getWindowPointer(window)
            val current = native.GetWindowLongW(pointer, -20)
            val requested = DesktopDecorativeWindowStylePolicy.requested(current) ?: run {
                log.warning("Owned decorative peer rejected: layered alpha is not ready, showing=${window.isShowing}, style=0x${current.toUInt().toString(16)}")
                return@runCatching false
            }
            if (requested != current) {
                native.SetWindowLongW(pointer, -20, requested)
                // Refresh this exact peer's cached style; preserve position,
                // size and z order, and never activate the user's window.
                if (!native.SetWindowPos(pointer, null, 0, 0, 0, 0,
                    0x0001 or 0x0002 or 0x0004 or 0x0010 or 0x0020)) {
                    log.warning("Owned decorative peer rejected: cached style refresh failed")
                    return@runCatching false
                }
            }
            val actual = native.GetWindowLongW(pointer, -20)
            DesktopDecorativeWindowStylePolicy.acknowledged(actual).also {
                if (!it) log.warning("Owned decorative peer rejected: mouse-through/no-activate readback missing, style=0x${actual.toUInt().toString(16)}")
            }
        }.onFailure { log.warning("Owned decorative peer rejected: ${it.javaClass.simpleName}") }.getOrDefault(false)
    }
}
