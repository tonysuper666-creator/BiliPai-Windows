package com.bilipai.desktop.ui

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle
import kotlin.test.*

class DesktopFullscreenSessionTest {
    @Test fun maximizedFullscreenRoundTripRestoresMaximizedAcrossEveryTogglePath() {
        val state = WindowState(placement = WindowPlacement.Maximized)
        val session = DesktopFullscreenSession(state)
        repeat(3) {
            session.setFullscreen(true, Rectangle(0, 0, 1600, 900))
            assertEquals(WindowPlacement.Fullscreen, state.placement)
            session.setFullscreen(true, Rectangle(0, 0, 1920, 1080))
            session.setFullscreen(false, null)
            assertEquals(WindowPlacement.Maximized, state.placement)
            session.setFullscreen(false, null)
            assertEquals(WindowPlacement.Maximized, state.placement)
        }
    }
    @Test fun floatingFullscreenRoundTripRestoresTheCapturedPositionAndSize() {
        val state = WindowState(placement = WindowPlacement.Floating)
        val session = DesktopFullscreenSession(state)
        var bounds = Rectangle(120, 70, 1280, 800)
        session.setFullscreen(true, bounds)
        bounds.setBounds(0, 0, 1920, 1080)
        session.restoreFloatingBounds { fail("Cannot restore before leaving fullscreen") }
        session.setFullscreen(false, bounds)
        assertEquals(WindowPlacement.Floating, state.placement)
        session.restoreFloatingBounds { bounds = it }
        assertEquals(Rectangle(120, 70, 1280, 800), bounds)
        session.restoreFloatingBounds { fail("A resize after restoration must stay accepted") }
        bounds = Rectangle(-900, 100, 1000, 700)
        session.setFullscreen(true, bounds); session.setFullscreen(false, null)
        session.restoreFloatingBounds { bounds = it }
        assertEquals(Rectangle(-900, 100, 1000, 700), bounds)
    }
    @Test fun fullscreenShutdownCannotApplyAnOldFloatingRectangle() {
        val state = WindowState(placement = WindowPlacement.Floating)
        val session = DesktopFullscreenSession(state)
        session.setFullscreen(true, Rectangle(10, 20, 900, 600))
        session.clear(); session.setFullscreen(false, null)
        session.restoreFloatingBounds { fail("Retired window must not restore stale bounds") }
    }
}
