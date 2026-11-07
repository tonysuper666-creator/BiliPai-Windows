package com.bilipai.desktop.appearance

import androidx.compose.ui.window.WindowState
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.util.concurrent.atomic.AtomicReference

/**
 * Saves the window bounds once, right now, for the close path. The settle-time effect in
 * DesktopWindowsUxWindowBounds.kt only saves after the window has rested, so a move made just before closing
 * would otherwise be lost. This reads the same state and writes through the same store; it never throws.
 */
internal class DesktopWindowsUxWindowFlush(store: DesktopPluginStore) {
    private val bounds = DesktopWindowsUxWindowBoundsStore(store)
    private val state = AtomicReference<WindowState?>()

    fun attach(windowState: WindowState) {
        state.set(windowState)
    }

    /** True when the stored bounds match the window afterwards (also when there was nothing to save). */
    fun flush(): Boolean {
        val current = state.get() ?: return true
        val tracker = DesktopWindowsUxWindowTracker(bounds.load())
        desktopWindowsUxSampleOf(current)?.let(tracker::accept)
        val next = tracker.bounds() ?: return true
        val saved = bounds.save(next)
        if (!saved) System.err.println("Window bounds were not saved before exit: the write was refused")
        return saved
    }
}
