package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerState
import kotlin.test.*

/** Actual mount-local UI state and installed original policy; no windows/native/player IO. */
class DesktopWindowsFullscreenChromeStateTest {
    private val playing = PlayerState(ready = true, firstVideoFrameReady = true, nativePaused = false)
    private fun allowed(state: PlayerState = playing, fullscreen: Boolean = true, active: Boolean = true,
        focused: Boolean = true, held: Boolean = false, problem: Boolean = false) =
        desktopWindowsFullscreenChromeCanAutoHide(fullscreen, active, focused, held, state, problem)

    @Test fun sameValuedDifferentEntryMustReceiveNewUiState() {
        data class Entry(val value: Int)
        val first = Entry(1)
        val second = Entry(1)
        assertEquals(first, second)
        assertEquals(DesktopWindowsFullscreenChromeEntryKey(first), DesktopWindowsFullscreenChromeEntryKey(first))
        assertNotEquals(DesktopWindowsFullscreenChromeEntryKey(first), DesktopWindowsFullscreenChromeEntryKey(second))
    }

    @Test fun originalPolicyRequiresPlayingFullscreenAndNoWindowsHolds() {
        assertTrue(allowed())
        assertFalse(allowed(fullscreen = false)); assertFalse(allowed(active = false))
        assertFalse(allowed(focused = false)); assertFalse(allowed(held = true)); assertFalse(allowed(problem = true))
    }

    @Test fun pauseStartupLoadingCacheEndAndErrorKeepControls() {
        listOf(playing.copy(paused = true), playing.copy(ready = false), playing.copy(loading = true),
            playing.copy(firstVideoFrameReady = false), playing.copy(nativePaused = null),
            playing.copy(nativePaused = true), playing.copy(audioOnly = true),
            playing.copy(pausedForCache = true), playing.copy(ended = true), playing.copy(error = "failed"))
            .forEach { assertFalse(allowed(it), it.toString()) }
    }

    @Test fun actualStateWaitsFourFullSecondsThenHides() {
        var now = 0L
        val chrome = DesktopWindowsFullscreenChromeState { now }
        val receipt = chrome.activityRevision
        now = 3_999_999_999L
        assertEquals(1L, chrome.remainingIdleMillis()); assertFalse(chrome.hideIfIdle(receipt, allowed()))
        assertTrue(chrome.visible)
        now++
        assertTrue(chrome.hideIfIdle(receipt, allowed())); assertFalse(chrome.visible)
    }

    @Test fun nativeActivityInvalidatesAnAlreadyWaitingTimer() {
        var now = 0L
        val chrome = DesktopWindowsFullscreenChromeState { now }
        val old = chrome.activityRevision
        now = 3_000_000_000L; chrome.reveal()
        now = 4_000_000_000L
        assertFalse(chrome.hideIfIdle(old, true)); assertEquals(3_000L, chrome.remainingIdleMillis())
        now = 7_000_000_000L
        assertTrue(chrome.hideIfIdle(chrome.activityRevision, true))
    }

    @Test fun retiredSourceOrInteractiveHoldCannotConsumeElapsedTimer() {
        var now = 0L
        val chrome = DesktopWindowsFullscreenChromeState { now }
        now = 5_000_000_000L
        assertFalse(chrome.hideIfIdle(chrome.activityRevision, false)); assertTrue(chrome.visible)
        chrome.reveal(); assertEquals(4_000L, chrome.remainingIdleMillis())
    }

    @Test fun foregroundRestoreAndFullscreenExitRevealAndRestartIdlePeriod() {
        var now = 0L
        val chrome = DesktopWindowsFullscreenChromeState { now }
        now = 4_000_000_000L
        assertTrue(chrome.hideIfIdle(chrome.activityRevision, true))
        chrome.reveal()
        assertTrue(chrome.visible); assertEquals(4_000L, chrome.remainingIdleMillis())
        assertFalse(chrome.hideIfIdle(chrome.activityRevision, true))
    }

    @Test fun sourceReplacementStartsVisibleAndOldUiTimerCannotChangeNewState() {
        var now = 0L
        val old = DesktopWindowsFullscreenChromeState { now }
        val current = DesktopWindowsFullscreenChromeState { now }
        now = 5_000_000_000L
        assertFalse(old.hideIfIdle(old.activityRevision, false)); assertTrue(current.visible)
        current.reveal(); assertEquals(4_000L, current.remainingIdleMillis())
    }
}
