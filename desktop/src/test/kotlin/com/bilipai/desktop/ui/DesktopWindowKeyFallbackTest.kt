package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.KeyEvent
import java.awt.Canvas
import java.awt.EventQueue
import kotlin.test.*

/** Production Window dispatch only; no real Window, OS input or native player. */
class DesktopWindowKeyFallbackTest {
    @Test fun aDeclinedLeafKeepsTheExistingRootHandler() = edt {
        val fallback = DesktopWindowKeyFallback(); var roots = 0
        fallback.register { roots++; true }; fallback.registerLeaf { false }
        assertTrue(fallback.dispatch(key())); assertEquals(1, roots); fallback.close()
    }
    @Test fun anAcceptedPlayerKeyRunsOnceBeforeRoot() = edt {
        val fallback = DesktopWindowKeyFallback(); var leaves = 0
        fallback.register { error("Root must not process an accepted player key") }
        fallback.registerLeaf { leaves++; true }
        assertTrue(fallback.dispatch(key())); assertEquals(1, leaves); fallback.close()
    }
    @Test fun disposingAnOldLeafCannotUnregisterItsSuccessor() = edt {
        val fallback = DesktopWindowKeyFallback(); var calls = 0
        val old = fallback.registerLeaf { error("Retired player") }
        val next = fallback.registerLeaf { calls++; true }; old.close()
        assertTrue(fallback.dispatch(key())); assertEquals(1, calls); next.close()
        assertFalse(fallback.dispatch(key())); fallback.close()
    }
    @Test fun disposingTheLeafRestoresTheSameRootRegistration() = edt {
        val fallback = DesktopWindowKeyFallback(); var roots = 0
        fallback.register { roots++; true }; val leaf = fallback.registerLeaf { true }
        leaf.close(); assertTrue(fallback.dispatch(key())); assertEquals(1, roots); fallback.close()
    }
    @Test fun rootRefreshDoesNotRetireTheVisiblePlayerLeaf() = edt {
        val fallback = DesktopWindowKeyFallback(); var leaves = 0
        val old = fallback.register { false }; fallback.registerLeaf { leaves++; true }
        fallback.register { error("Unconsumed Root refresh") }; old.close()
        assertTrue(fallback.dispatch(key())); assertEquals(1, leaves); fallback.close()
    }
    @Test fun closingTheActualWindowRetiresBothKindsOfRecipient() = edt {
        val fallback = DesktopWindowKeyFallback()
        val root = fallback.register { error("Closed Root") }; val leaf = fallback.registerLeaf { error("Closed player") }
        fallback.close(); root.close(); leaf.close(); assertFalse(fallback.dispatch(key()))
        assertFailsWith<IllegalStateException> { fallback.registerLeaf { true } }
    }
    private fun key() = KeyEvent(java.awt.event.KeyEvent(Canvas(), java.awt.event.KeyEvent.KEY_PRESSED,
        1L, 0, java.awt.event.KeyEvent.VK_SPACE, ' '))
    private fun edt(block: () -> Unit) { EventQueue.invokeAndWait(block) }
}
