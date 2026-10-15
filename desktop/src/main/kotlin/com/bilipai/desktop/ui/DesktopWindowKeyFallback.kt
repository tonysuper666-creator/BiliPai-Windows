package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.*
import java.awt.EventQueue

/** The actual Window's preview and final key callbacks. A visible native player
 * supplies its own source/foreground admission before the retained Root scene
 * can consume Space on an old focus target. Root navigation remains post-scene.
 * A disposed predecessor cannot clear a successor; no global key hook is used. */
internal class DesktopWindowKeyFallback : AutoCloseable {
    private class Registration(val handler: (KeyEvent) -> Boolean)
    private var current: Registration? = null
    private var leaf: Registration? = null
    private var closed = false

    fun register(handler: (KeyEvent) -> Boolean): AutoCloseable {
        check(EventQueue.isDispatchThread())
        check(!closed) { "Window key fallback is retired" }
        val registration = Registration(handler)
        current = registration
        return AutoCloseable {
            check(EventQueue.isDispatchThread())
            if (current === registration) current = null
        }
    }

    /** The native leaf owns its complete keyboard policy, including consumed
     * editor keys and current source admission. Root registration is independent. */
    fun registerLeaf(handler: (KeyEvent) -> Boolean): AutoCloseable {
        check(EventQueue.isDispatchThread())
        check(!closed) { "Window key fallback is retired" }
        val registration = Registration(handler)
        leaf = registration
        return AutoCloseable {
            check(EventQueue.isDispatchThread())
            if (leaf === registration) leaf = null
        }
    }

    /** Only the explicitly registered native leaf may act before Compose.
     * Never invoke Root navigation while a Compose editor can still own a key. */
    fun preview(event: KeyEvent): Boolean {
        check(EventQueue.isDispatchThread())
        if (closed) return false
        recordSpace(event, com.bilipai.desktop.player.MpvRuntimeProbeStage.WINDOW_PREVIEW)
        return leaf?.handler?.invoke(event) ?: false
    }

    fun dispatch(event: KeyEvent): Boolean {
        check(EventQueue.isDispatchThread())
        if (closed) return false
        recordSpace(event, com.bilipai.desktop.player.MpvRuntimeProbeStage.WINDOW_UNHANDLED)
        if (leaf?.handler?.invoke(event) == true) return true
        return current?.handler?.invoke(event) ?: false
    }

    private fun recordSpace(event: KeyEvent, stage: com.bilipai.desktop.player.MpvRuntimeProbeStage) {
        if (event.key == Key.Spacebar)
            com.bilipai.desktop.player.MpvRuntimeProbe.record(stage,
                if (event.type == KeyEventType.KeyDown) 401L else if (event.type == KeyEventType.KeyUp) 402L else 0L,
                flags = if (leaf != null) 2 else 0)
    }

    override fun close() {
        check(EventQueue.isDispatchThread())
        closed = true
        current = null
        leaf = null
    }
}

internal val LocalDesktopWindowKeyFallback = staticCompositionLocalOf<DesktopWindowKeyFallback?> { null }
