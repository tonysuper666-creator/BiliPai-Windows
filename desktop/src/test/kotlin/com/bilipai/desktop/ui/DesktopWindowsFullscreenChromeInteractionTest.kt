package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerState
import kotlin.test.*

/** Crossing the heavyweight video boundary must retire only stale input holds.
 * Uses the product hold policy and actual idle timer; no native window or input injection. */
class DesktopWindowsFullscreenChromeInteractionTest {
    private val playing = PlayerState(ready = true, firstVideoFrameReady = true, nativePaused = false)
    private fun allowed(hold: DesktopWindowsFullscreenChromeInteraction, pointer: Boolean, keyboard: Boolean,
        state: PlayerState = playing, active: Boolean = true, windowFocused: Boolean = true) =
        desktopWindowsFullscreenChromeCanAutoHide(true, active, windowFocused,
            hold.held(pointer, keyboard), state, false)

    @Test fun retainedHoverAndFocusCannotPinChromeAfterNativeVideoOwnsBothInputs() {
        var now = 0L
        val chrome = DesktopWindowsFullscreenChromeState { now }
        val stale = DesktopWindowsFullscreenChromeInteraction(hovered = true, focused = true)
        assertFalse(allowed(stale, false, false))
        chrome.reveal() // accepted real native activity starts its own full idle period
        now = 3_999_999_999L
        assertFalse(chrome.hideIfIdle(chrome.activityRevision, allowed(stale, true, true)))
        now++
        assertTrue(chrome.hideIfIdle(chrome.activityRevision, allowed(stale, true, true)))
    }

    @Test fun nativePointerMustNotStealKeyboardFocusFromAControl() {
        val hold = DesktopWindowsFullscreenChromeInteraction(hovered = true, focused = true)
        assertFalse(allowed(hold, true, false))
        assertFalse(allowed(hold, false, true))
        assertTrue(allowed(hold, true, true))
    }

    @Test fun returningToControlsRestoresHoverHoldEvenIfComposeNeverEmittedExit() {
        val retained = DesktopWindowsFullscreenChromeInteraction(hovered = true)
        assertTrue(allowed(retained, true, true))
        assertFalse(allowed(retained, false, true)) // actual Compose Enter/Move takes pointer back
    }

    @Test fun popupOrScrubAlwaysProtectsChromeAcrossNativeInputTransfer() {
        val interactive = DesktopWindowsFullscreenChromeInteraction(hovered = true, focused = true, operationHeld = true)
        for (pointer in listOf(false, true)) for (keyboard in listOf(false, true))
            assertFalse(allowed(interactive, pointer, keyboard))
        assertTrue(allowed(interactive.copy(operationHeld = false), true, true))
    }

    @Test fun staleFocusCanBeSupersededOnlyByNativeKeyboardOwnership() {
        val retained = DesktopWindowsFullscreenChromeInteraction(focused = true)
        assertFalse(allowed(retained, true, false))
        assertTrue(allowed(retained, false, true))
        assertFalse(allowed(retained, false, false))
    }

    @Test fun nativeOwnershipNeverBypassesPauseWindowOrEntryGuards() {
        val stale = DesktopWindowsFullscreenChromeInteraction(hovered = true, focused = true)
        assertFalse(allowed(stale, true, true, playing.copy(nativePaused = true, paused = true)))
        assertFalse(allowed(stale, true, true, active = false))
        assertFalse(allowed(stale, true, true, windowFocused = false))
    }
}
