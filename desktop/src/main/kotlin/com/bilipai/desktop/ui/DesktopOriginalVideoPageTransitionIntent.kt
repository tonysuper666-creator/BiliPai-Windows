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
    private val currentPageGeneration: () -> Long,
    private val isPageSwitchPending: () -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopOriginalVideoPageTransitionIntent>
    internal fun sameOriginalRequest(state: PlaybackSessionState): Boolean =
        sameOriginalRequestIdentity(state) && isGenerationCurrent()
    internal fun sameOriginalRequestIdentity(state: PlaybackSessionState): Boolean {
        val request = original.currentRequest ?: return false
        return bvid.isNotBlank() && cid > 0L && pageIndex >= 0 && request.bvid == bvid &&
            state.currentBvid == bvid && state.currentRequest === request &&
            state.currentLoadRequestToken == original.currentLoadRequestToken
    }
    /** Called once by the existing Factory in the ACTUAL page launch coroutine,
     * before its original Invocation body/HTTP/media preparation. */
    internal fun capture(state: PlaybackSessionState, caller: Job): DesktopOriginalVideoPageTransitionProof {
        if (!caller.isActive || !sameOriginalRequest(state) || state.currentCid != previousCid)
            throw CancellationException("Original page transition request retired before capture")
        return DesktopOriginalVideoPageTransitionProof(this, checkNotNull(original.currentRequest), caller)
    }
    /** Native-owned page metadata validates the actual original request and
     * generation, independently from an ancestor execution Job. */
    internal fun matchesResolvedRequest(request: PlaybackRequest): Boolean {
        val originalRequest = original.currentRequest ?: return false
        return originalRequest.bvid == bvid && cid > 0L &&
            request == originalRequest.copy(cid = cid)
    }
    internal fun resolvedCommittedSubject(state: PlaybackSessionState): PlaybackRequest {
        if (!sameOriginalRequest(state) || state.currentCid != cid)
            throw CancellationException("Original committed page subject replaced")
        return checkNotNull(original.currentRequest).copy(cid = cid)
    }
    internal fun captureSuccessor(state: PlaybackSessionState, caller: Job,
        expected: DesktopOriginalVideoAcceptedPublication): DesktopOriginalVideoPageSuccessorProof {
        val generation = currentPageGeneration()
        if (!caller.isActive || expected.pageSubject !== this)
            throw CancellationException("Original page successor retired before capture")
        // Original page success starts metadata children before its finally
        // clears pending. Keep their repository work; a blocked media capture
        // stays blocked and cannot later borrow the plain full-load helper.
        val capturedSubject = expected.request.takeIf {
            currentPageGeneration() == generation && !isPageSwitchPending() &&
                sameOriginalRequestIdentity(state) && state.currentCid == cid && matchesResolvedRequest(it)
        }
        return DesktopOriginalVideoPageSuccessorProof(this, expected, caller, generation, capturedSubject)
    }
    internal fun resolvedAcceptedSubject(state: PlaybackSessionState,
        expectedGeneration: Long): PlaybackRequest {
        if (currentPageGeneration() != expectedGeneration || isPageSwitchPending() ||
            !sameOriginalRequestIdentity(state) || state.currentCid != cid)
            throw CancellationException("Original accepted page generation/subject replaced")
        return checkNotNull(original.currentRequest).copy(cid = cid)
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
        if (factoryCaller.isCancelled)
            throw CancellationException("Original committed page caller replaced")
        // Native media subject only; no beginLoadRequest or new load token.
        return intent.resolvedCommittedSubject(state).also {
            check(it == originalRequest.copy(cid = intent.cid))
        }
    }
}


/** A new real Factory caller borrowed from ONE exact native accepted page.
 * Parent ownership is consumed only at submission; it cannot guard the successor
 * ACK after NativeOwner installs the new accepted object. No ancestor Job is kept. */
internal class DesktopOriginalVideoPageSuccessorProof(
    val subject: DesktopOriginalVideoPageTransitionIntent,
    val expected: DesktopOriginalVideoAcceptedPublication,
    val factoryCaller: Job,
    val generation: Long,
    private val capturedSubject: PlaybackRequest?,
) {
    internal fun resolvedCommittedSubject(state: PlaybackSessionState): PlaybackRequest {
        if (capturedSubject == null || factoryCaller.isCancelled || expected.pageSubject !== subject)
            throw CancellationException("Original page successor caller retired")
        val resolved = subject.resolvedAcceptedSubject(state, generation)
        if (resolved != capturedSubject || resolved != expected.request)
            throw CancellationException("Original page successor subject changed")
        return resolved
    }
    override fun toString() = "DesktopOriginalVideoPageSuccessorProof(sourceVersion=${expected.sourceVersion})"
}
