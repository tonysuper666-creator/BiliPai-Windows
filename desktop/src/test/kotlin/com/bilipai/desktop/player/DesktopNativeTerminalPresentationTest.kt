package com.bilipai.desktop.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class DesktopNativeTerminalPresentationTest {
    private val source = PlaybackSource("file:///C:/terminal-presentation-fixture.avi")
    private val snapshot = OwnedPlaybackSourceSnapshot(7, source)
    private val failure = PlayerFailure(PlayerFailureKind.DECODER, -13, "Fixture decoder failure", sourceVersion = 7, attemptId = 2)

    @Test fun coreInitializationPreservesEOFInsteadOfRestartingOriginalUnpausedSource() {
        val ended = PlayerState(ended = true, paused = true, positionSeconds = 60.0, durationSeconds = 60.0)
        val intent = assertNotNull(DesktopNativeTerminalPresentation.capture(snapshot, 4, ended))
        val ready = intent.coreReady(ended.copy(ready = false), "fixture-version")
        assertTrue(ready.ready)
        assertTrue(ready.ended)
        assertTrue(ready.paused)
        assertFalse(ready.loading)
        assertFalse(ready.firstVideoFrameReady)
        assertNull(ready.nativePaused)
        assertEquals(60.0, ready.positionSeconds)
        assertEquals(0.0, source.startPositionSeconds)
        assertFalse(source.startPaused)
    }

    @Test fun coreInitializationPreservesExactTypedFailure() {
        val failed = PlayerState(error = failure.safeMessage, failure = failure, positionSeconds = 23.0)
        val intent = assertNotNull(DesktopNativeTerminalPresentation.capture(snapshot, 4, failed))
        val ready = intent.coreReady(failed, "fixture-version")
        assertSame(failure, ready.failure)
        assertEquals(failure.safeMessage, ready.error)
        assertFalse(ready.ended)
        assertEquals(23.0, ready.positionSeconds)
    }

    @Test fun nonterminalPresentationNeverSuppressesOriginalLoad() {
        assertNull(DesktopNativeTerminalPresentation.capture(snapshot, 4, PlayerState(ready = true, paused = true)))
        assertNull(DesktopNativeTerminalPresentation.capture(snapshot, 4, PlayerState(loading = true)))
    }

    @Test fun terminalIntentCannotApplyToReplacementVersionRevisionOrFullSource() {
        val intent = assertNotNull(DesktopNativeTerminalPresentation.capture(snapshot, 4, PlayerState(ended = true)))
        assertTrue(intent.matches(OwnedPlaybackSourceSnapshot(7, source.copy()), 4))
        assertFalse(intent.matches(OwnedPlaybackSourceSnapshot(8, source), 4))
        assertFalse(intent.matches(snapshot, 5))
        assertFalse(intent.matches(OwnedPlaybackSourceSnapshot(7, source.copy(startPaused = true)), 4))
        assertFalse(intent.matches(OwnedPlaybackSourceSnapshot(7, source.copy(title = "Replacement")), 4))
    }

    @Test fun coreReadyPreservesCurrentMuteVolumeSpeedAndFailurePauseIntent() {
        val intent = assertNotNull(DesktopNativeTerminalPresentation.capture(snapshot, 4, PlayerState(error = "Fixture failure")))
        val ready = intent.coreReady(PlayerState(muted = false, volume = 0.0, speed = 1.5, paused = true), "fixture-version")
        assertFalse(ready.muted)
        assertEquals(0.0, ready.volume)
        assertEquals(1.5, ready.speed)
        assertTrue(ready.paused)
    }

    // These two headless tests model terminal event publication only. No HWND,
    // native core, account or files are created; actual PiP EOF is a native smoke.
    @Test fun explicitReplayConsumesActualPlayerTerminalIntentWhileReleaseKeepsFullSource() {
        MpvPlayer().use { player ->
            player.loadVersioned(source)
            val expected = assertNotNull(player.currentSourceSnapshot())
            state(player).value = player.state.value.copy(ended = true, paused = true, positionSeconds = 60.0)
            assertTrue(player.releasePresentationForDisposal()())
            assertNotNull(terminal(player))
            assertTrue(player.ownsSourceSnapshot(expected))
            assertTrue(player.state.value.ended)
            player.togglePause() // Existing explicit EOF replay.
            assertNull(terminal(player))
            assertFalse(player.state.value.ended)
            assertFalse(player.state.value.paused)
            assertEquals(0.0, player.state.value.positionSeconds)
        }
    }

    @Test fun explicitRecoveryConsumesActualTypedFailureIntent() {
        MpvPlayer().use { player ->
            val version = player.loadVersioned(source)
            val typed = failure.copy(sourceVersion = version)
            state(player).value = player.state.value.copy(error = typed.safeMessage, failure = typed, positionSeconds = 23.0)
            assertTrue(player.releasePresentationForDisposal()())
            assertNotNull(terminal(player))
            assertSame(typed, player.state.value.failure)
            assertTrue(player.recoverSource(version, positionSeconds = 23.0, paused = true, expectedFailureAttemptId = typed.attemptId))
            assertNull(terminal(player))
            assertNull(player.state.value.failure)
            assertNull(player.state.value.error)
            assertEquals(23.0, player.state.value.positionSeconds)
            assertTrue(player.state.value.paused)
        }
    }

    @Test fun newLoadStopAndCloseRetireActualPlayerTerminalIntent() {
        for (operation in 0..2) MpvPlayer().use { player ->
            player.loadVersioned(source)
            state(player).value = player.state.value.copy(ended = true, paused = true)
            player.releasePresentationForDisposal()
            assertNotNull(terminal(player))
            when (operation) {
                0 -> player.loadVersioned(source.copy(title = "New source"))
                1 -> player.stop()
                else -> player.close()
            }
            assertNull(terminal(player))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(player: MpvPlayer) = MpvPlayer::class.java.getDeclaredField("mutableState")
        .apply { isAccessible = true }.get(player) as MutableStateFlow<PlayerState>
    private fun terminal(player: MpvPlayer) = MpvPlayer::class.java.getDeclaredField("terminalPresentation")
        .apply { isAccessible = true }.get(player) as DesktopNativeTerminalPresentation?
}
