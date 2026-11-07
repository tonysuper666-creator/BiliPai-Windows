package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.*
import java.awt.Canvas
import java.awt.EventQueue
import kotlin.test.*

class DesktopWindowsVideoEscapeInputTest {
    @Test fun windowedEscapeUsesTheExistingBackRouteOnceAcrossPreviewAndDispatch() = edt {
        val f = Fixture()
        val fallback = DesktopWindowKeyFallback()
        fallback.register { error("Esc escaped the current video leaf") }
        fallback.registerLeaf(f.input::onKey)
        assertTrue(fallback.preview(key()))
        repeat(5) { assertTrue(fallback.dispatch(key())) }
        assertTrue(fallback.dispatch(key(KeyEventType.KeyUp)))
        assertTrue(fallback.dispatch(key()))
        assertEquals(listOf("back"), f.effects)
        f.input.close(); fallback.close()
    }

    @Test fun editorRetainsEscapeEvenWhenVideoIsFullscreenOrHasAnOverlay() = edt {
        val f = Fixture(); f.editor = true; f.fullscreen = true; f.overlay = true
        assertTrue(f.input.onKey(key()))
        assertTrue(f.effects.isEmpty()); assertTrue(f.fullscreen); assertTrue(f.overlay)
    }

    @Test fun topOverlayClosesBeforeFullscreenAndHeldPressCannotCascade() = edt {
        val f = Fixture(); f.overlay = true; f.fullscreen = true
        assertTrue(f.input.onKey(key()))
        repeat(4) { assertTrue(f.input.onKey(key())) }
        assertEquals(listOf("overlay"), f.effects); assertTrue(f.fullscreen)
        f.input.onKey(key(KeyEventType.KeyUp)); f.input.onKey(key())
        assertEquals(listOf("overlay", "fullscreen"), f.effects)
        assertFalse(f.fullscreen)
        f.input.onKey(key(KeyEventType.KeyUp)); f.input.onKey(key())
        assertEquals(listOf("overlay", "fullscreen", "back"), f.effects)
    }

    @Test fun retiredPageAndForeignActiveWindowCannotNavigate() = edt {
        val f = Fixture(); f.current = false
        assertFalse(f.input.onKey(key())); f.current = true; f.window = false
        assertFalse(f.input.onKey(key())); assertTrue(f.effects.isEmpty())
        f.window = true; f.input.close(); assertFalse(f.input.onKey(key()))
    }

    @Test fun focusLossReleasesHeldEscapeWithoutRetiringThePage() = edt {
        val f = Fixture(); f.fullscreen = true
        assertTrue(f.input.onKey(key())); f.input.releaseHeldInput()
        assertTrue(f.input.onKey(key()))
        assertEquals(listOf("fullscreen", "back"), f.effects)
    }

    private class Fixture {
        var current = true; var window = true; var editor = false
        var overlay = false; var fullscreen = false
        val effects = mutableListOf<String>()
        val input = DesktopWindowsVideoEscapeInput(
            current = { current }, windowCurrent = { window }, textEditorFocused = { editor },
            closeTopOverlay = { if (overlay) { overlay = false; effects += "overlay"; true } else false },
            fullscreen = { fullscreen }, leaveFullscreen = { fullscreen = false; effects += "fullscreen" },
            requestBack = { effects += "back"; true }, admitNative = { error("AWT called native admission") },
        )
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun key(type: KeyEventType = KeyEventType.KeyDown) = KeyEvent(Key.Escape, type, 27,
        nativeEvent = java.awt.event.KeyEvent(Canvas(),
            if (type == KeyEventType.KeyUp) java.awt.event.KeyEvent.KEY_RELEASED else java.awt.event.KeyEvent.KEY_PRESSED,
            1L, 0, java.awt.event.KeyEvent.VK_ESCAPE, 27.toChar()))
    private fun edt(block: () -> Unit) { EventQueue.invokeAndWait(block) }
}
