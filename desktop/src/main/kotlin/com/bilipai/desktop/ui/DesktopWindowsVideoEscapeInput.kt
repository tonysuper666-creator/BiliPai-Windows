package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.*
import com.bilipai.desktop.player.MpvNativeWindowInputEvent
import com.bilipai.desktop.player.MpvNativeWindowInputKind
import java.awt.EventQueue

/** The current ordinary-video leaf owns this route, shared by Compose, AWT and
 * MPV's child-window messages. Admission only checks the native lease; navigation
 * runs after that check returns, outside the MPV/publication locks. */
internal class DesktopWindowsVideoEscapeInput(
    private val current: () -> Boolean,
    private val windowCurrent: () -> Boolean,
    private val textEditorFocused: () -> Boolean,
    private val closeTopOverlay: () -> Boolean,
    private val fullscreen: () -> Boolean,
    private val leaveFullscreen: () -> Unit,
    private val requestBack: () -> Boolean,
    private val admitNative: (MpvNativeWindowInputEvent) -> Boolean,
    private val nanoTime: () -> Long = System::nanoTime,
    private val trace: (String) -> Unit = {},
) : AutoCloseable {
    private var closed = false
    private var held = false
    private var backRequested = false
    private var nativeTransition = false
    private var transitionAt = Long.MIN_VALUE

    fun onKey(event: KeyEvent): Boolean {
        check(EventQueue.isDispatchThread())
        if (event.key != Key.Escape || closed) return false
        if (event.type == KeyEventType.KeyUp) {
            val consumed = held
            held = false
            return consumed
        }
        if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed ||
            event.isMetaPressed || event.isShiftPressed || !owns()) return false
        if (held || backRequested) return true
        held = true
        if (nativeTransition && recent(nanoTime())) return true
        return route("awt/compose")
    }

    fun onNative(event: MpvNativeWindowInputEvent): Boolean {
        check(EventQueue.isDispatchThread())
        if (event.kind != MpvNativeWindowInputKind.ESCAPE || event.lease.windowId == 0L || !owns()) return false
        if (!admitNative(event) || !owns()) { trace("native rejected lease/window"); return false }
        // nonrepeatable bindings suppress OS key repeats. Also retire events
        // already queued before an overlay/fullscreen transition or a back.
        if (held || backRequested || event.receivedAtNanos <= transitionAt || recent(event.receivedAtNanos)) {
            trace("native retired duplicate/queued transition")
            return true
        }
        return route("mpv")
    }

    // Also cover the same press being reported by both native and AWT paths.
    private fun recent(at: Long) = transitionAt != Long.MIN_VALUE &&
        at - transitionAt in 0..250_000_000L

    private fun owns() = !closed && current() && windowCurrent()

    private fun route(origin: String): Boolean {
        when {
            textEditorFocused() -> trace("$origin editor retained")
            closeTopOverlay() -> trace("$origin overlay closed")
            fullscreen() -> { leaveFullscreen(); trace("$origin fullscreen exited") }
            else -> {
                // Reserve before invoking Root; it can synchronously re-enter.
                backRequested = true
                requestBack()
                trace("$origin windowed back")
            }
        }
        nativeTransition = origin == "mpv"
        transitionAt = nanoTime()
        return true
    }

    fun releaseHeldInput() {
        check(EventQueue.isDispatchThread())
        held = false
    }

    override fun close() {
        check(EventQueue.isDispatchThread())
        closed = true
        held = false
    }
}

/** Bound after all overlay state is declared; never a second navigation owner. */
internal class DesktopWindowsVideoEscapeOverlay { var closeTop: () -> Boolean = { false } }
