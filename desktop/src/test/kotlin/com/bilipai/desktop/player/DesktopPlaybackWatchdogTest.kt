package com.bilipai.desktop.player

import com.android.purebilibili.feature.plugin.PlaybackCdnCandidate
import com.android.purebilibili.feature.plugin.PlaybackCdnCandidateSource
import com.android.purebilibili.feature.video.viewmodel.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.*

class DesktopPlaybackWatchdogTest {
    private fun fallback() = buildPlaybackCdnFallbackState("https://rewritten.invalid/video", "https://rewritten.invalid/audio",
        "https://original.invalid/video", "https://original.invalid/audio", regionLabel = null,
        fallbackCandidates = listOf(
            PlaybackCdnCandidate("https://original.invalid/video", "https://original.invalid/audio", PlaybackCdnCandidateSource.ORIGINAL),
            PlaybackCdnCandidate("https://backup.invalid/video", "https://backup.invalid/audio", PlaybackCdnCandidateSource.ORIGINAL)))
    private fun snapshot(version: Long = 1, load: Long = 1, index: Int = 0, candidates: Int = 3,
        native: PlayerState = PlayerState(ready = true, loading = true), fallback: PlaybackCdnFallbackState = fallback(),
        audio: Boolean = true, enabled: Boolean = true) =
        DesktopWatchdogSnapshot(DesktopWatchdogIdentity(version, 7, load), native, index, candidates, fallback, audio, enabled)

    private class Fixture(scope: CoroutineScope) {
        var current: DesktopWatchdogSnapshot? = null
        val actions = Channel<Pair<DesktopWatchdogSnapshot, DesktopWatchdogAction>>(Channel.UNLIMITED)
        val watchdog = DesktopPlaybackWatchdog(scope, { current }, { old, action -> actions.trySend(old to action) },
            firstFrameTimeoutMs = 30, stallTimeoutMs = 35)
        fun loaded(snapshot: DesktopWatchdogSnapshot) { current = snapshot; watchdog.loaded(snapshot) }
        fun observe(snapshot: DesktopWatchdogSnapshot) { current = snapshot; watchdog.observe(snapshot) }
        suspend fun noAction() { assertNull(withTimeoutOrNull(100) { actions.receive() }) }
        suspend fun action() = withTimeout(2_000) { actions.receive() }
        fun close() { watchdog.reset(); actions.close() }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) = coroutineScope {
        val fixture = Fixture(this)
        try { block(fixture) } finally { fixture.close() }
    }

    @Test fun `original timeouts and player states come from verified unchanged source`() {
        assertEquals(2_500L, desktopCdnFirstFrameTimeoutMs())
        assertEquals(10_000L, desktopPlaybackStallTimeoutMs())
        assertEquals(2, com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates.STATE_BUFFERING)
    }

    @Test fun `missing selected audio falls back to original paired track and then consumes backups`() = runBlocking<Unit> {
        fixture { f ->
            val state = snapshot(native = PlayerState(ready = true, videoCodec = "h264", firstVideoFrameReady = true))
            f.loaded(state)
            val action = assertIs<DesktopWatchdogAction.FirstFrameFallback>(f.action().second)
            assertTrue(action.audioMissing)
            assertEquals("https://original.invalid/video", action.nextState.selectedVideoUrl)
            assertEquals("https://original.invalid/audio", action.nextState.selectedAudioUrl)
            f.loaded(snapshot(load = 2, fallback = action.nextState))
            val second = assertIs<DesktopWatchdogAction.FirstFrameFallback>(f.action().second)
            assertEquals("https://backup.invalid/video", second.nextState.selectedVideoUrl)
            assertEquals("https://backup.invalid/audio", second.nextState.selectedAudioUrl)
            assertFalse(second.nextState.usesCdnRewrite)
            f.loaded(snapshot(load = 3, fallback = second.nextState)); f.noAction()
        }
    }

    @Test fun `native initialization alone cannot acknowledge media readiness`() = runBlocking<Unit> {
        fixture { f ->
            f.loaded(snapshot(native = PlayerState(ready = true)))
            val action = assertIs<DesktopWatchdogAction.FirstFrameFallback>(f.action().second)
            assertFalse(action.audioMissing)
        }
    }

    @Test fun `decoded ready video and selected audio cancel the first frame timer`() = runBlocking<Unit> {
        fixture { f ->
            f.loaded(snapshot())
            f.observe(snapshot(native = PlayerState(ready = true, videoCodec = "h264", audioCodec = "aac", firstVideoFrameReady = true)))
            f.noAction()
        }
    }

    @Test fun `late timer cannot act on a foreign source or same owner replacement`() = runBlocking<Unit> {
        fixture { f ->
            f.loaded(snapshot())
            f.current = snapshot(version = 2); f.noAction()
            f.loaded(snapshot())
            f.current = snapshot(load = 2); f.noAction()
            f.loaded(snapshot())
            f.current = null; f.noAction()
        }
    }

    @Test fun `paused disabled ended and typed failures suppress timer recovery`() = runBlocking<Unit> {
        fixture { f ->
            val failure = PlayerFailure(PlayerFailureKind.NETWORK, -13, "Synthetic timeout", sourceVersion = 1, attemptId = 1)
            val blocked = listOf(snapshot(native = PlayerState(paused = true)), snapshot(enabled = false),
                snapshot(native = PlayerState(ended = true)), snapshot(native = PlayerState(error = "Synthetic error")),
                snapshot(native = PlayerState(failure = failure)))
            for (state in blocked) { f.loaded(snapshot()); f.observe(state); f.noAction() }
            f.loaded(snapshot()); f.watchdog.reset(); f.noAction()
        }
    }

    @Test fun `stall requires known exhausted buffer and an actual frame and tries each CDN once`() = runBlocking<Unit> {
        fixture { f ->
            val stalled = PlayerState(ready = true, loading = true, videoCodec = "h264", firstVideoFrameReady = true,
                pausedForCache = true, bufferedForwardSeconds = 0.0)
            for (native in listOf(stalled.copy(bufferedForwardSeconds = null), stalled.copy(bufferedForwardSeconds = 1.0),
                stalled.copy(firstVideoFrameReady = false), stalled.copy(pausedForCache = false))) {
                f.loaded(snapshot(native = native, fallback = PlaybackCdnFallbackState.Inactive))
                f.observe(f.current!!); f.noAction()
            }
            var state = snapshot(native = stalled, fallback = PlaybackCdnFallbackState.Inactive)
            f.loaded(state); f.observe(state)
            assertEquals(1, assertIs<DesktopWatchdogAction.StallSwitch>(f.action().second).index)
            state = snapshot(load = 2, index = 1, native = stalled, fallback = PlaybackCdnFallbackState.Inactive)
            f.loaded(state); f.observe(state)
            assertEquals(2, assertIs<DesktopWatchdogAction.StallSwitch>(f.action().second).index)
            state = snapshot(load = 3, index = 2, native = stalled, fallback = PlaybackCdnFallbackState.Inactive)
            f.loaded(state); f.observe(state); f.noAction()
            // A new source owns a fresh original attempted-CDN set.
            state = snapshot(version = 2, native = stalled, fallback = PlaybackCdnFallbackState.Inactive)
            f.loaded(state); f.observe(state)
            assertEquals(1, assertIs<DesktopWatchdogAction.StallSwitch>(f.action().second).index)
        }
    }
}
