package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlaybackRecoveryTest {
    private fun failure(kind: PlayerFailureKind) = PlayerFailure(kind, -13, "failure", sourceVersion = 1, attemptId = 1)

    @Test fun `network recovery consumes two real mirror switches then three bounded fresh URL retries`() {
        val budget = DesktopPlaybackRecoveryBudget()
        val network = failure(PlayerFailureKind.NETWORK)
        repeat(2) {
            val action = budget.action(network, hasAlternatives = true)
            assertEquals(PlayerErrorRecoveryAction.SWITCH_CDN, action)
            assertEquals(500L, budget.consume(action))
        }
        for (delay in listOf(1_000L, 2_000L, 4_000L)) {
            val action = budget.action(network, hasAlternatives = true)
            assertEquals(PlayerErrorRecoveryAction.RETRY_NETWORK, action)
            assertEquals(delay, budget.consume(action))
        }
        assertEquals(PlayerErrorRecoveryAction.GIVE_UP, budget.action(network, true))
        assertEquals(2, budget.cdnSwitches)
        assertEquals(3, budget.retries)
    }

    @Test fun `a native file failure is not mapped to Media3 network file-not-found retries`() {
        val budget = DesktopPlaybackRecoveryBudget()
        val file = failure(PlayerFailureKind.FILE_IO)
        val action = budget.action(file, true)
        assertEquals(PlayerErrorRecoveryAction.RETRY_NON_NETWORK, action)
        assertEquals(0L, budget.consume(action))
        assertEquals(PlayerErrorRecoveryAction.GIVE_UP, budget.action(file, true))
        val decoder = DesktopPlaybackRecoveryBudget()
        repeat(2) { decoder.consume(decoder.action(failure(PlayerFailureKind.DECODER), false)) }
        assertEquals(PlayerErrorRecoveryAction.GIVE_UP, decoder.action(failure(PlayerFailureKind.DECODER), false))
    }

    @Test fun `native initialization cannot replenish recovery budgets before decoded media is loaded`() {
        assertFalse(isMediaReadyForRecovery(PlayerState(ready = true)))
        assertFalse(isMediaReadyForRecovery(PlayerState(ready = true, loading = true, videoCodec = "h264")))
        assertFalse(isMediaReadyForRecovery(PlayerState(ready = true, videoCodec = "h264", failure = failure(PlayerFailureKind.DECODER))))
        assertFalse(isMediaReadyForRecovery(PlayerState(ready = true, ended = true, audioCodec = "aac")))
        assertTrue(isMediaReadyForRecovery(PlayerState(ready = true, videoCodec = "h264")))
        assertTrue(isMediaReadyForRecovery(PlayerState(ready = true, audioCodec = "aac", audioOnly = true)))
    }

    @Test fun `all audio-only mirrors remain paired with authorized original video and signed URLs are never rewritten`() {
        val original = "https://v.example/video?token=original"
        val audio = listOf("https://a.example/audio?token=1", "https://b.example/audio?token=2", "https://c.example/audio?token=3")
        val source = ResolvedSource(original, audio.first(), "title", "https://www.bilibili.com/", audioAlternatives = audio.drop(1))
        val candidates = authorizedPlaybackCandidates(source)
        assertEquals(3, candidates.size)
        assertEquals(setOf(original), candidates.map { it.videoUrl }.toSet())
        assertEquals(audio, candidates.map { it.audioUrl })
    }
}
