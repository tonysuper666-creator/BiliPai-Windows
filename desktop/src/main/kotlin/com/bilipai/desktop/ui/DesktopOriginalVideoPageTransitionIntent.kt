package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable parameters of ONE original in-place switchPage launch. The existing
 * page generation and original full-load request are borrowed, never replaced.
 * This is not a PlaybackRequest acceptance, error source, route or account. */
internal class DesktopOriginalVideoPageTransitionIntent(
    val bvid: String,
    val cid: Long,
    val pageIndex: Int,
    val requestedQuality: Int,
    val audioLang: String?,
    val ignoreSavedProgress: Boolean,
    val previousCid: Long,
    private val original: PlaybackSessionState,
    val switchGeneration: Long,
    private val isGenerationCurrent: () -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopOriginalVideoPageTransitionIntent>
    internal fun sameOriginalRequest(state: PlaybackSessionState): Boolean {
        val request = original.currentRequest ?: return false
        return bvid.isNotBlank() && cid > 0L && pageIndex >= 0 && request.bvid == bvid &&
            state.currentBvid == bvid && state.currentRequest === request &&
            state.currentLoadRequestToken == original.currentLoadRequestToken && isGenerationCurrent()
    }
    /** Called once by the existing Factory in the ACTUAL page launch coroutine,
     * before its original Invocation body/HTTP/media preparation. */
    internal fun capture(state: PlaybackSessionState, caller: Job): DesktopOriginalVideoPageTransitionProof {
        if (!caller.isActive || !sameOriginalRequest(state) || state.currentCid != previousCid)
            throw CancellationException("Original page transition request retired before capture")
        return DesktopOriginalVideoPageTransitionProof(this, checkNotNull(original.currentRequest), caller)
    }
    override fun toString() = "DesktopOriginalVideoPageTransitionIntent(generation=$switchGeneration, pageIndex=$pageIndex)"
}

/** One actual Factory caller, not a new Job or another native owner. Successful
 * completion may still admit the existing delayed native ACK. The original
 * finally clears pageSwitchJob/pendingCID, so neither slot is a long-lived guard. */
internal class DesktopOriginalVideoPageTransitionProof(
    val intent: DesktopOriginalVideoPageTransitionIntent,
    private val originalRequest: PlaybackRequest,
    val factoryCaller: Job,
) {
    internal fun resolvedCommittedSubject(state: PlaybackSessionState): PlaybackRequest {
        if (factoryCaller.isCancelled || !intent.sameOriginalRequest(state) || state.currentCid != intent.cid)
            throw CancellationException("Original committed page subject replaced")
        // Native media subject only; no beginLoadRequest, new token or fabricated
        // full-load flags. All old flags remain those of the SAME original request.
        return originalRequest.copy(cid = intent.cid)
    }
}
