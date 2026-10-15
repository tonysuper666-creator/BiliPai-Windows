package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEvent
import java.awt.EventQueue

/** The actual Window's final key callback, after its Compose scene declines the event.
 * Registration is scoped to the ready Root; a disposed predecessor cannot clear a successor.
 * This supplies no navigation owner or input: the shared Root handler keeps those policies.
 */
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

    /** A visible native player may own a key after its Compose editors declined
     * it. Keep the ready Root callback; disposal cannot remove a successor. */
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

    fun dispatch(event: KeyEvent): Boolean {
        check(EventQueue.isDispatchThread())
        if (closed) return false
        if (leaf?.handler?.invoke(event) == true) return true
        return current?.handler?.invoke(event) ?: false
    }

    override fun close() {
        check(EventQueue.isDispatchThread())
        closed = true
        current = null
        leaf = null
    }
}

internal val LocalDesktopWindowKeyFallback = staticCompositionLocalOf<DesktopWindowKeyFallback?> { null }
