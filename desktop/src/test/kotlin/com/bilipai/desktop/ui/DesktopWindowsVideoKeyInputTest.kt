package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.*
import java.awt.Canvas
import java.awt.EventQueue
import kotlin.test.*

/** Actual Window-preview recipient and ownership state; no real OS input or GPU. */
class DesktopWindowsVideoKeyInputTest {
    @Test fun spaceConsumesTheCompletePressAndCommandsOnlyOnce() = edt {
        val f = Fixture()
        assertTrue(f.input.onKey(key()))
        assertTrue(f.input.onKey(key(type = KeyEventType.KeyUp)))
        assertEquals(listOf(Key.Spacebar), f.commands)
    }

    @Test fun repeatedSpaceDoesNotTogglePlaybackAgain() = edt {
        val f = Fixture()
        repeat(4) { assertTrue(f.input.onKey(key())) }
        assertEquals(1, f.commands.size)
        assertTrue(f.input.onKey(key(type = KeyEventType.KeyUp)))
        assertTrue(f.input.onKey(key()))
        assertEquals(2, f.commands.size)
    }

    @Test fun heldEscapeCannotNavigateBackAfterExitingFullscreen() = edt {
        val f = Fixture(); f.fullscreen = true
        assertTrue(f.input.onKey(key(Key.Escape)))
        f.fullscreen = false
        assertTrue(f.input.onKey(key(Key.Escape)))
        assertTrue(f.input.onKey(key(Key.Escape, KeyEventType.KeyUp)))
        assertFalse(f.input.onKey(key(Key.Escape)))
        assertEquals(listOf(Key.Escape), f.commands)
    }

    @Test fun escapeOutsideFullscreenKeepsTheOriginalScenePolicy() = edt {
        val f = Fixture()
        assertFalse(f.input.onKey(key(Key.Escape)))
        assertFalse(f.input.onKey(key(Key.Escape, KeyEventType.KeyUp)))
        assertTrue(f.commands.isEmpty())
    }

    @Test fun modifiedKeysKeepTheirExistingActions() = edt {
        val f = Fixture(); f.fullscreen = true
        for (modified in 1..4) for (k in listOf(Key.Spacebar, Key.Escape))
            assertFalse(f.input.onKey(key(k, modifier = modified)))
        assertTrue(f.commands.isEmpty())
    }

    @Test fun retiredOrNotYetPublishedSourceCannotCommandPlayback() = edt {
        val f = Fixture(); f.source = null; f.fullscreen = true
        assertFalse(f.input.onKey(key()))
        assertFalse(f.input.onKey(key(Key.Escape)))
        assertTrue(f.commands.isEmpty())
    }

    @Test fun aHeldPressCannotToggleAReplacementSource() = edt {
        val f = Fixture()
        assertTrue(f.input.onKey(key()))
        f.source = Any()
        assertTrue(f.input.onKey(key()))
        assertEquals(1, f.commands.size)
        assertFalse(f.input.onKey(key(type = KeyEventType.KeyUp)))
        assertTrue(f.input.onKey(key()))
        assertEquals(2, f.commands.size)
    }

    @Test fun anEditorOutsideThePlaybackControlsKeepsSpaceAndEscape() = edt {
        val f = Fixture(); f.focused = false; f.fullscreen = true
        assertFalse(f.input.onKey(key()))
        assertFalse(f.input.onKey(key(Key.Escape)))
        assertTrue(f.commands.isEmpty())
    }

    @Test fun aReleaseAfterEditorFocusStillClearsTheOldRepeatGuard() = edt {
        val f = Fixture(); assertTrue(f.input.onKey(key()))
        f.focused = false
        assertFalse(f.input.onKey(key(type = KeyEventType.KeyUp)))
        f.focused = true
        assertTrue(f.input.onKey(key()))
        assertEquals(2, f.commands.size)
    }

    @Test fun focusLossReleasesBothKeysWhenTheOtherWindowReceivesKeyUp() = edt {
        val f = Fixture(); f.fullscreen = true
        assertTrue(f.input.onKey(key())); assertTrue(f.input.onKey(key(Key.Escape)))
        f.input.releaseHeldInput()
        assertTrue(f.input.onKey(key())); assertTrue(f.input.onKey(key(Key.Escape)))
        assertEquals(4, f.commands.size)
    }

    @Test fun closingThePageRetiresItsInputAndPendingReleases() = edt {
        val f = Fixture(); f.fullscreen = true
        assertTrue(f.input.onKey(key())); f.input.close()
        assertFalse(f.input.onKey(key(type = KeyEventType.KeyUp)))
        assertFalse(f.input.onKey(key(Key.Escape)))
        assertEquals(1, f.commands.size)
    }

    @Test fun aDeclinedOwnerDoesNotClaimTheKeyOrItsRelease() = edt {
        val f = Fixture(); f.accept = false
        assertFalse(f.input.onKey(key()))
        assertFalse(f.input.onKey(key(type = KeyEventType.KeyUp)))
        f.accept = true
        assertTrue(f.input.onKey(key()))
        assertEquals(2, f.commands.size)
    }

    @Test fun retirementBeforeReleaseNeverDispatchesAnotherAction() = edt {
        val f = Fixture(); assertTrue(f.input.onKey(key()))
        f.source = null
        assertFalse(f.input.onKey(key(type = KeyEventType.KeyUp)))
        assertEquals(1, f.commands.size)
        f.source = Any(); assertTrue(f.input.onKey(key()))
        assertEquals(2, f.commands.size)
    }

    @Test fun buttonAndTimelineNavigationKeysKeepTheirOriginalFocusBehavior() = edt {
        val f = Fixture()
        for (k in listOf(Key.Enter, Key.DirectionLeft, Key.DirectionRight, Key.Tab))
            assertFalse(f.input.onKey(key(k)))
        assertTrue(f.commands.isEmpty())
    }

    @Test fun actualWindowPreviewPreventsTheFocusedFullscreenButtonFromSeeingSpace() = edt {
        val f = Fixture(); val fallback = DesktopWindowKeyFallback(); var buttonActivations = 0
        fallback.register { error("Accepted video input must not reach Root navigation") }
        val registration = fallback.registerLeaf(f.input::onKey)
        for (event in listOf(key(), key(type = KeyEventType.KeyUp)))
            if (!fallback.preview(event)) buttonActivations++
        assertEquals(0, buttonActivations); assertEquals(1, f.commands.size)
        registration.close(); assertFalse(fallback.preview(key()))
        fallback.close(); f.input.close()
    }

    private class Fixture {
        var source: Any? = Any()
        var focused = true
        var fullscreen = false
        var accept = true
        val commands = mutableListOf<Key>()
        val input = DesktopWindowsVideoKeyInput({ source }, { focused }, { fullscreen }) {
            commands += it.key; accept
        }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    private fun key(k: Key = Key.Spacebar, type: KeyEventType = KeyEventType.KeyDown, modifier: Int = 0): KeyEvent =
        KeyEvent(k, type, if (k == Key.Spacebar) ' '.code else 0,
            isCtrlPressed = modifier == 1, isAltPressed = modifier == 2,
            isMetaPressed = modifier == 3, isShiftPressed = modifier == 4,
            nativeEvent = java.awt.event.KeyEvent(Canvas(),
                if (type == KeyEventType.KeyUp) java.awt.event.KeyEvent.KEY_RELEASED else java.awt.event.KeyEvent.KEY_PRESSED,
                1L, 0, if (k == Key.Spacebar) java.awt.event.KeyEvent.VK_SPACE else java.awt.event.KeyEvent.VK_ESCAPE,
                if (k == Key.Spacebar) ' ' else java.awt.event.KeyEvent.CHAR_UNDEFINED))
    private fun edt(action: () -> Unit) { EventQueue.invokeAndWait(action) }
}
