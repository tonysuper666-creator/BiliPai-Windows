package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.*
import java.awt.EventQueue

/** Window preview for the current video's controls/viewport. The original owner
 * still executes the command; editors and separate modal windows keep their keys.
 * A held press cannot command a replacement publication or activate a button on
 * key-up after its key-down was consumed here. No global input hook is installed. */
internal class DesktopWindowsVideoKeyInput<Source : Any>(
    private val currentSource: () -> Source?,
    private val ownsFocus: () -> Boolean,
    private val isFullscreen: () -> Boolean,
    private val onKey: (KeyEvent) -> Boolean,
) : AutoCloseable {
    private var closed = false
    private var heldSpace: Source? = null
    private var heldEscape: Source? = null

    fun onKey(event: KeyEvent): Boolean {
        check(EventQueue.isDispatchThread())
        if (closed || event.key != Key.Spacebar && event.key != Key.Escape) return false
        val space = event.key == Key.Spacebar
        val held = if (space) heldSpace else heldEscape
        if (event.type == KeyEventType.KeyUp) {
            if (space) heldSpace = null else heldEscape = null
            return held != null && currentSource() === held && ownsFocus()
        }
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed ||
            event.isMetaPressed || event.isShiftPressed || !ownsFocus()) return false
        val source = currentSource() ?: return false
        // A repeat belongs to its original physical press, including across a
        // source switch or fullscreen exit. Never execute it on a successor.
        if (held != null) return true
        if (!space && !isFullscreen()) return false
        if (currentSource() !== source || !onKey.invoke(event)) return false
        if (space) heldSpace = source else heldEscape = source
        return true
    }

    fun releaseHeldInput() {
        check(EventQueue.isDispatchThread())
        heldSpace = null
        heldEscape = null
    }

    override fun close() {
        check(EventQueue.isDispatchThread())
        closed = true
        releaseHeldInput()
    }
}
