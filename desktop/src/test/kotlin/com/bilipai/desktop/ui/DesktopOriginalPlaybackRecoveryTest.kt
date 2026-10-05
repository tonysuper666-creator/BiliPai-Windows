package com.bilipai.desktop.ui

import com.android.purebilibili.core.player.PlaybackProgressManager
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.feature.video.playback.loader.*
import com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction
import com.android.purebilibili.feature.video.usecase.VideoLoadResult
import com.android.purebilibili.feature.video.viewmodel.resolveRequestedStartPositionMs
import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import javax.swing.SwingUtilities
import kotlin.test.*

/** Runs the actual adapter used by the generated VM. No native core, HWND,
 * repository, user preferences or media/network request is started. */
class DesktopOriginalPlaybackRecoveryTest {
    private class Harness : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        var owned = true
        var inAdmission = false
        val delays = mutableListOf<Long>()
        var delayEntered = CompletableDeferred<Unit>()
        var delayRelease = CompletableDeferred<Unit>()
        val recovery = DesktopOriginalPlaybackRecovery(scope,
            admit = { block ->
                assertTrue(SwingUtilities.isEventDispatchThread())
                if (!owned) false else { inAdmission = true; try { block(); true } finally { inAdmission = false } }
            }, requireCurrent = { if (!owned) throw CancellationException("synthetic owner retired") },
            wait = { milliseconds ->
                assertFalse(inAdmission); delays += milliseconds; delayEntered.complete(Unit); delayRelease.await()
            })
        suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Swing) { block() }
        suspend fun entered() = withTimeout(5_000) { delayEntered.await() }
        fun resetWait() { delayEntered = CompletableDeferred(); delayRelease = CompletableDeferred() }
        override fun close() { recovery.close(); scope.cancel() }
    }

    private fun ticket(identity: Any = Any(), evidence: DesktopOriginalPlaybackFailureEvidence = desktopOriginalApiFailure(VideoLoadError.NetworkError),
        alternatives: Boolean = false, position: Long = 2_000L, current: () -> Boolean = { true },
        execute: (PlayerErrorRecoveryAction, Long, Boolean) -> Boolean) = DesktopOriginalPlaybackRecoveryTicket(
            identity, evidence, alternatives, position, false, current, execute)

    @Test fun actualAdapterBackoffThenOriginalLoaderRetainsExplicitPositionAndPause() = runBlocking<Unit> {
        for (position in listOf(0L, 2_000L, 59_000L)) Harness().use { h ->
            val delivered = CompletableDeferred<PlaybackLoadResult>()
            val progress = PlaybackProgressManager() // Non-singleton memory only; no Context/profile.
            progress.savePosition("BVfixture", cid = 22L, positionMs = 42_000L)
            val captured = ticket(position = position, execute = { action, actual, play ->
                assertEquals(PlayerErrorRecoveryAction.RETRY_NETWORK, action)
                assertTrue(SwingUtilities.isEventDispatchThread()); assertFalse(h.inAdmission)
                val request = PlaybackRequest.create("BVfixture", 7L, 22L, force = true, autoPlay = play, audioLang = "en")
                val cached = progress.getCachedPosition("BVfixture", 22L)
                assertEquals(42_000L, cached)
                assertEquals(position, resolveRequestedStartPositionMs(cached, actual, actual))
                h.scope.launch {
                    val result = PlaybackLoader { _, _ -> VideoLoadResult.Error(VideoLoadError.Timeout) }
                        .load(request, cached, PlaybackLoadConfig(120, -1, "hev1", "avc1", play, true, false, false))
                    assertEquals(position, resolveRequestedStartPositionMs(result.cachedPositionMs, actual, actual))
                    assertFalse(desktopWindowsShouldRestartPlaybackAtEnd(60_000L, actual, actual))
                    delivered.complete(result)
                }
                true
            })
            h.ui { h.recovery.beginLoad(false); assertTrue(h.recovery.fail(captured)) }
            h.entered()
            assertEquals(listOf(1_000L), h.delays)
            h.delayRelease.complete(Unit)
            val result = withTimeout(5_000) { delivered.await() }
            assertEquals(22L, result.request.cid); assertEquals("en", result.request.audioLang)
            assertEquals(false, result.request.autoPlay)
        }
    }

    @Test fun readyDoesNotRefillNetworkBudgetButExplicitUserLoadDoes() = runBlocking<Unit> {
        Harness().use { h ->
            for (expectedDelay in listOf(1_000L, 2_000L, 4_000L)) {
                h.resetWait(); val delivered = CompletableDeferred<Unit>()
                h.ui { assertTrue(h.recovery.fail(ticket { _, _, _ -> delivered.complete(Unit); true })) }
                h.entered(); assertEquals(expectedDelay, h.delays.last()); h.delayRelease.complete(Unit)
                withTimeout(5_000) { delivered.await() }
                h.ui { h.recovery.ready() }
            }
            h.ui {
                assertTrue(h.recovery.fail(ticket { _, _, _ -> fail("exhausted budget must not dispatch") }))
                assertEquals(PlaybackStatus.Failed, h.recovery.state.value.status)
                assertNull(h.recovery.state.value.recoveryStage)
                h.recovery.beginLoad(false)
            }
            h.resetWait(); val fresh = CompletableDeferred<Unit>()
            h.ui { h.recovery.fail(ticket { _, _, _ -> fresh.complete(Unit); true }) }
            h.entered(); assertEquals(1_000L, h.delays.last()); h.delayRelease.complete(Unit)
            withTimeout(5_000) { fresh.await() }
        }
    }

    @Test fun cdnSwitchesAreBoundedBeforeNetworkRetries() = runBlocking<Unit> {
        Harness().use { h ->
            for (expected in listOf(PlayerErrorRecoveryAction.SWITCH_CDN, PlayerErrorRecoveryAction.SWITCH_CDN,
                PlayerErrorRecoveryAction.RETRY_NETWORK)) {
                h.resetWait(); val delivered = CompletableDeferred<PlayerErrorRecoveryAction>()
                h.ui { h.recovery.fail(ticket(alternatives = true) { action, _, _ -> delivered.complete(action); true }) }
                h.entered(); assertEquals(if (expected == PlayerErrorRecoveryAction.SWITCH_CDN) 500L else 1_000L, h.delays.last())
                h.delayRelease.complete(Unit)
                assertEquals(expected, withTimeout(5_000) { delivered.await() })
                h.ui { h.recovery.ready() }
            }
        }
    }

    @Test fun fullSourceReplacementDuringDelayCannotConsumeEvenTheSameNumericVersion() = runBlocking<Unit> {
        Harness().use { h ->
            val held = OwnedPlaybackSourceSnapshot(8L, PlaybackSource("file:///synthetic-a.avi"))
            var current = held
            var calls = 0
            val retiredRead = CompletableDeferred<Unit>()
            h.ui { h.recovery.fail(ticket(current = { (current === held).also { if (!it) retiredRead.complete(Unit) } }) { _, _, _ -> calls++; true }) }
            h.entered()
            h.ui { current = OwnedPlaybackSourceSnapshot(8L, held.source.copy(videoUrl = "file:///synthetic-b.avi")) }
            h.delayRelease.complete(Unit)
            withTimeout(5_000) { retiredRead.await() }
            h.ui { assertEquals(0, calls) }
        }
    }

    @Test fun duplicateEpisodeDenialCancellationAndRetiredEntryDoNotPublishOrDispatch() = runBlocking<Unit> {
        Harness().use { h ->
            var calls = 0
            val captured = ticket { _, _, _ -> calls++; true }
            h.ui { assertTrue(h.recovery.fail(captured)); assertFalse(h.recovery.fail(captured)) }
            h.entered()
            h.ui { h.owned = false }
            h.delayRelease.complete(Unit)
            h.ui { assertEquals(0, calls); assertFalse(h.recovery.fail(ticket { _, _, _ -> calls++; true })) }
        }
        Harness().use { h ->
            h.ui { h.recovery.fail(ticket { _, _, _ -> fail("closed entry must not dispatch") }) }
            h.entered(); h.ui { h.recovery.close() }; h.delayRelease.complete(Unit)
        }
    }

    @Test fun authenticationAndLocalOutputFailureRemainTerminalWithoutDelay() = runBlocking<Unit> {
        Harness().use { h ->
            h.ui {
                assertTrue(h.recovery.fail(ticket(evidence = desktopOriginalApiFailure(VideoLoadError.ApiError(-101, "synthetic"))) {
                    _, _, _ -> fail("authentication cannot retry") }))
                assertEquals(PlaybackFailureReason.Authentication, h.recovery.state.value.failure?.reason)
                assertEquals(PlaybackStatus.Failed, h.recovery.state.value.status)
            }
            assertTrue(h.delays.isEmpty())
        }
    }
}
