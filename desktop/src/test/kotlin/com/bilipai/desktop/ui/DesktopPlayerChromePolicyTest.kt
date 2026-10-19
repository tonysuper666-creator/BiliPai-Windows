package com.bilipai.desktop.ui

import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure chrome policy against the shared contract and the existing fullscreen rule, plus the idleNanos parameter. No window, no GPU, no network. */
class DesktopPlayerChromePolicyTest {
    private val chrome = DesktopUxContract.section("chrome")
    private val playing = PlayerState(ready = true, firstVideoFrameReady = true, nativePaused = false)

    /** Every combination of the eight flags of the rule and the three states of the native pause readback. */
    private fun everyState(): List<PlayerState> {
        val states = ArrayList<PlayerState>()
        for (bits in 0 until 256) {
            for (native in listOf(false, true, null)) {
                states.add(
                    PlayerState(
                        ready = (bits and 1) != 0, loading = (bits and 2) != 0, paused = (bits and 4) != 0, ended = (bits and 8) != 0,
                        audioOnly = (bits and 16) != 0, firstVideoFrameReady = (bits and 32) != 0, pausedForCache = (bits and 64) != 0,
                        error = if ((bits and 128) != 0) "failed" else null, nativePaused = native,
                    ),
                )
            }
        }
        return states
    }

    @Test fun everyNumberOfTheContractHasItsConstant() {
        assertEquals(DesktopUxContract.int(chrome, "idle_ms").toLong(), DESKTOP_PLAYER_CHROME_IDLE_MS)
        assertEquals(DesktopUxContract.int(chrome, "leave_idle_ms").toLong(), DESKTOP_PLAYER_CHROME_LEAVE_IDLE_MS)
        assertEquals(DesktopUxContract.int(chrome, "fade_out_ms"), DESKTOP_PLAYER_CHROME_FADE_OUT_MS)
        assertEquals(DesktopUxContract.int(chrome, "fade_in_ms"), DESKTOP_PLAYER_CHROME_FADE_IN_MS)
        assertEquals(DesktopUxContract.int(chrome, "move_threshold_px"), DESKTOP_PLAYER_CHROME_MOVE_THRESHOLD_PX)
        val buffering = chrome.getValue("buffering").jsonObject
        assertEquals(DesktopUxContract.int(buffering, "spinner_after_ms").toLong(), DESKTOP_PLAYER_BUFFERING_SPINNER_AFTER_MS)
        assertEquals(DesktopUxContract.int(buffering, "text_after_ms").toLong(), DESKTOP_PLAYER_BUFFERING_TEXT_AFTER_MS)
        assertEquals(2_500_000_000L, DESKTOP_PLAYER_CHROME_IDLE_NANOS)
        assertEquals(DesktopUxContract.int(chrome, "legacy_fullscreen_idle_ms") * 1_000_000L, DesktopWindowsFullscreenChromeState.DEFAULT_IDLE_NANOS)
    }

    @Test fun theHoldNamesAreTheContractListInOrder() {
        assertEquals(DesktopUxContract.strings(chrome.getValue("hold_while")), DesktopPlayerChromeHold.entries.map { it.name })
    }

    @Test fun noPlaybackHoldExactlyWhenTheExistingRuleCountsTheVideoAsPlaying() {
        var checked = 0
        for (state in everyState()) {
            for (problem in listOf(false, true)) {
                val existing = desktopWindowsFullscreenChromeCanAutoHide(true, true, true, false, state, problem)
                assertEquals(existing, desktopPlayerPlaybackHolds(state, problem).isEmpty(), "$state $problem")
                checked++
            }
        }
        assertEquals(256 * 3 * 2, checked)
    }

    @Test fun theSiblingRuleEqualsTheExistingOneWithFullscreenOn() {
        for (state in everyState()) {
            for (problem in listOf(false, true)) {
                for (active in listOf(false, true)) {
                    for (focused in listOf(false, true)) {
                        for (held in listOf(false, true)) {
                            assertEquals(
                                desktopWindowsFullscreenChromeCanAutoHide(true, active, focused, held, state, problem),
                                desktopPlayerChromeCanAutoHide(active, focused, held, state, problem),
                                "$state $problem $active $focused $held",
                            )
                        }
                    }
                }
            }
        }
        assertFalse(desktopWindowsFullscreenChromeCanAutoHide(false, true, true, false, playing, false))
        assertTrue(desktopPlayerChromeCanAutoHide(true, true, false, playing, false))
    }

    @Test fun everyHoldHasItsReason() {
        assertTrue(desktopPlayerChromeHolds(playing, false, DesktopPlayerChromeInteraction()).isEmpty())
        val every = DesktopPlayerChromeInteraction(flyoutOpen = true, scrubbing = true, draggingVolume = true, dockHovered = true, topBarHovered = true)
        assertEquals(
            listOf(
                DesktopPlayerChromeHold.FLYOUT_OPEN, DesktopPlayerChromeHold.SCRUBBING, DesktopPlayerChromeHold.DRAGGING_VOLUME,
                DesktopPlayerChromeHold.DOCK_HOVERED, DesktopPlayerChromeHold.TOP_BAR_HOVERED,
            ),
            desktopPlayerChromeHolds(playing, false, every).toList(),
        )
        val broken = PlayerState(ready = true, firstVideoFrameReady = true, nativePaused = false, paused = true, ended = true, audioOnly = true, error = "failed", loading = true)
        assertEquals(DesktopPlayerChromeHold.entries.toList(), desktopPlayerChromeHolds(broken, false, every).toList())
        assertEquals(setOf(DesktopPlayerChromeHold.FLYOUT_OPEN), desktopPlayerChromeHolds(playing, false, DesktopPlayerChromeInteraction(flyoutOpen = true)))
        assertEquals(setOf(DesktopPlayerChromeHold.SCRUBBING), desktopPlayerChromeHolds(playing, false, DesktopPlayerChromeInteraction(scrubbing = true)))
        assertEquals(setOf(DesktopPlayerChromeHold.DRAGGING_VOLUME), desktopPlayerChromeHolds(playing, false, DesktopPlayerChromeInteraction(draggingVolume = true)))
        assertEquals(setOf(DesktopPlayerChromeHold.DOCK_HOVERED), desktopPlayerChromeHolds(playing, false, DesktopPlayerChromeInteraction(dockHovered = true)))
        assertEquals(setOf(DesktopPlayerChromeHold.TOP_BAR_HOVERED), desktopPlayerChromeHolds(playing, false, DesktopPlayerChromeInteraction(topBarHovered = true)))
        assertEquals(setOf(DesktopPlayerChromeHold.ERRORED), desktopPlayerChromeHolds(playing, true, DesktopPlayerChromeInteraction()))
        assertEquals(setOf(DesktopPlayerChromeHold.PAUSED), desktopPlayerPlaybackHolds(playing.copy(nativePaused = true), false))
        assertEquals(setOf(DesktopPlayerChromeHold.BUFFERING), desktopPlayerPlaybackHolds(playing.copy(nativePaused = null), false))
        assertEquals(setOf(DesktopPlayerChromeHold.AUDIO_ONLY), desktopPlayerPlaybackHolds(playing.copy(audioOnly = true), false))
        assertEquals(setOf(DesktopPlayerChromeHold.ENDED), desktopPlayerPlaybackHolds(playing.copy(ended = true), false))
    }

    @Test fun theLeaveIdleMoveThresholdCursorAndBufferingRules() {
        assertEquals(600L, desktopPlayerChromeRemainingIdleMs(2500L, true))
        assertEquals(300L, desktopPlayerChromeRemainingIdleMs(300L, true))
        assertEquals(2500L, desktopPlayerChromeRemainingIdleMs(2500L, false))
        assertEquals(0L, desktopPlayerChromeRemainingIdleMs(0L, true))
        val moves = listOf(0 to 0, 1 to 1, 2 to 0, 0 to -2, -2 to 0, 1 to 2, 1 to 0).map { (dx, dy) -> desktopPlayerPointerMoveCounts(dx, dy) }
        assertEquals(listOf(false, false, true, true, true, true, false), moves)
        assertTrue(desktopPlayerPointerMoveCounts(Int.MAX_VALUE, Int.MIN_VALUE))
        assertTrue(desktopPlayerCursorHidden(false, true))
        assertFalse(desktopPlayerCursorHidden(true, true))
        assertFalse(desktopPlayerCursorHidden(false, false))
        val none = DesktopPlayerBufferingPresentation.NONE
        val spinner = DesktopPlayerBufferingPresentation.SPINNER
        val text = DesktopPlayerBufferingPresentation.SPINNER_WITH_TEXT
        assertEquals(listOf(none, none, spinner, spinner, text, text), listOf(0L, 399L, 400L, 4999L, 5000L, 60000L).map { desktopPlayerBufferingPresentation(it) })
    }

    @Test fun idleNanosOfTheDefaultIsFourSecondsAndANonDefaultOneIsHonoured() {
        var now = 0L
        val legacy = DesktopWindowsFullscreenChromeState { now }
        val legacyReceipt = legacy.activityRevision
        now = 3_999_999_999L
        assertEquals(1L, legacy.remainingIdleMillis())
        assertFalse(legacy.hideIfIdle(legacyReceipt, true))
        now = 4_000_000_000L
        assertEquals(0L, legacy.remainingIdleMillis())
        assertTrue(legacy.hideIfIdle(legacyReceipt, true))
        assertFalse(legacy.visible)

        now = 0L
        val player = DesktopWindowsFullscreenChromeState(DESKTOP_PLAYER_CHROME_IDLE_NANOS) { now }
        val receipt = player.activityRevision
        assertEquals(2500L, player.remainingIdleMillis())
        now = 2_499_999_999L
        assertEquals(1L, player.remainingIdleMillis())
        assertFalse(player.hideIfIdle(receipt, true))
        assertTrue(player.visible)
        now = 2_500_000_000L
        assertEquals(0L, player.remainingIdleMillis())
        assertTrue(player.hideIfIdle(receipt, true))
        assertFalse(player.visible)
        player.reveal()
        assertEquals(2500L, player.remainingIdleMillis())
        val named = DesktopWindowsFullscreenChromeState(idleNanos = 1_000_000_000L) { now }
        assertEquals(1000L, named.remainingIdleMillis())
        assertEquals(4_000_000_000L, DesktopWindowsFullscreenChromeState.DEFAULT_IDLE_NANOS)
    }
}
