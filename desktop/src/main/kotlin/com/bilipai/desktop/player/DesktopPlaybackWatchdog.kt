package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.player.platform.DesktopMedia3PlayerStates as Player
import kotlinx.coroutines.*

internal data class DesktopWatchdogIdentity(val sourceVersion: Long, val requestGeneration: Long, val loadId: Long)
internal class DesktopWatchdogSnapshot(val identity: DesktopWatchdogIdentity, val native: PlayerState,
    val cdnIndex: Int, val candidateCount: Int, val fallback: PlaybackCdnFallbackState, val expectedAudio: Boolean,
    val enabled: Boolean = true) {
    override fun toString() = "DesktopWatchdogSnapshot(identity=$identity, cdnIndex=$cdnIndex)"
}
internal sealed interface DesktopWatchdogAction {
    data class FirstFrameFallback(val nextState: PlaybackCdnFallbackState, val audioMissing: Boolean) : DesktopWatchdogAction
    data class StallSwitch(val index: Int) : DesktopWatchdogAction
}

/** Timers/ownership adapt the native lifecycle; all candidate and first-frame decisions use unchanged original policies. */
internal class DesktopPlaybackWatchdog(
    private val scope: CoroutineScope,
    private val current: () -> DesktopWatchdogSnapshot?,
    private val switch: (DesktopWatchdogSnapshot, DesktopWatchdogAction) -> Unit,
    private val firstFrameTimeoutMs: Long = desktopCdnFirstFrameTimeoutMs(),
    private val stallTimeoutMs: Long = desktopPlaybackStallTimeoutMs(),
) {
    private var identity: DesktopWatchdogIdentity? = null
    private var sourceVersion: Long? = null
    private var firstFrame: Job? = null
    private var stall: Job? = null
    private val attemptedStallIndexes = mutableSetOf<Int>()

    fun loaded(snapshot: DesktopWatchdogSnapshot) {
        cancelTimers()
        if (sourceVersion != snapshot.identity.sourceVersion) attemptedStallIndexes.clear()
        sourceVersion = snapshot.identity.sourceVersion; identity = snapshot.identity
        if (!snapshot.enabled || snapshot.native.paused || !snapshot.fallback.usesCdnRewrite) return
        val token = snapshot.identity
        firstFrame = scope.launch {
            delay(firstFrameTimeoutMs)
            val latest = valid(token) ?: return@launch
            firstFrame = null
            val ready = isMediaReadyForRecovery(latest.native)
            val hasAudio = latest.native.audioCodec != null
            if (!shouldFallbackFromCdnRewrite(latest.fallback, ready, latest.expectedAudio, hasAudio, audioRendererError = false)) return@launch
            val video = latest.fallback.fallbackVideoUrl ?: return@launch
            switch(latest, DesktopWatchdogAction.FirstFrameFallback(
                latest.fallback.advanceFallback(video, latest.fallback.fallbackAudioUrl), ready && latest.expectedAudio && !hasAudio))
        }
    }

    fun observe(snapshot: DesktopWatchdogSnapshot) {
        if (identity != snapshot.identity) { loaded(snapshot); return }
        val native = snapshot.native
        if (!snapshot.enabled || native.paused || native.ended || native.failure != null || native.error != null) {
            cancelTimers(); return
        }
        if (isMediaReadyForRecovery(native) && (!snapshot.expectedAudio || native.audioCodec != null)) {
            firstFrame?.cancel(); firstFrame = null
        }
        val buffer = native.bufferedForwardSeconds
        if (!native.pausedForCache || !native.firstVideoFrameReady || buffer == null || (buffer * 1_000.0).toLong() > 0L || snapshot.candidateCount <= 1) {
            stall?.cancel(); stall = null; return
        }
        if (stall?.isActive == true) return
        val token = snapshot.identity
        stall = scope.launch {
            delay(stallTimeoutMs)
            val latest = valid(token) ?: return@launch
            stall = null
            val duration = latest.native.bufferedForwardSeconds ?: return@launch
            attemptedStallIndexes.add(latest.cdnIndex)
            val next = resolvePlaybackStallRecoveryDecision(
                playbackState = if (latest.native.pausedForCache) Player.STATE_BUFFERING else Player.STATE_READY,
                playWhenReady = !latest.native.paused,
                firstFrameRendered = latest.native.firstVideoFrameReady,
                forwardBufferDurationMs = (duration * 1_000.0).toLong().coerceAtLeast(0L),
                currentCdnIndex = latest.cdnIndex, cdnCandidateCount = latest.candidateCount,
                attemptedCdnIndexes = attemptedStallIndexes, usesAdaptivePlayback = false)
            next.nextCdnIndex?.let { index -> attemptedStallIndexes.add(index); switch(latest, DesktopWatchdogAction.StallSwitch(index)) }
        }
    }

    private fun valid(token: DesktopWatchdogIdentity): DesktopWatchdogSnapshot? = current()?.takeIf {
        it.identity == token && it.enabled && !it.native.paused && !it.native.ended && it.native.failure == null && it.native.error == null
    }
    private fun cancelTimers() { firstFrame?.cancel(); firstFrame = null; stall?.cancel(); stall = null }
    fun cancel() { cancelTimers(); identity = null }
    fun reset() { cancel(); sourceVersion = null; attemptedStallIndexes.clear() }
}
