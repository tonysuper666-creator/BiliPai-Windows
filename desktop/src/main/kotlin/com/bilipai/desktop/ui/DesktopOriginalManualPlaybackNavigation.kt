package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.bilipai.desktop.data.BiliApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The actual click-time native publication and callback identities. No coroutine,
 * state/queue owner or later credential/source lookup is constructed here. */
internal class DesktopOriginalManualPlaybackClick(
    val assembly: DesktopOriginalVideoOwnerAssembly,
    val accepted: DesktopOriginalVideoAcceptedPublication,
    val nextCallback: (() -> Boolean)?,
    val previousCallback: (() -> Boolean)?,
)

/** A manual UI action's immutable source metadata. This is deliberately separate
 * from the native EOF continuation: no EOF or listener-registration Job is used.
 * A normally completed click Job remains a genuine request caller; cancellation
 * of its actual page/Window parent retires it. Native/request/entry still decide
 * whether this click may start a successor; no original algorithm runs in a gate. */
class DesktopOriginalManualPlaybackNavigation internal constructor(
    private val origin: DesktopVideoBootstrapAccepted,
    private val native: DesktopOriginalVideoNativeOwner,
    private val accepted: DesktopOriginalVideoAcceptedPublication,
    private val caller: Job,
    private val clickCurrent: () -> Boolean,
    private val callerStillOwned: (() -> Boolean)? = null,
) {
    internal fun isCurrent(): Boolean {
        fun current() = !caller.isCancelled && callerStillOwned?.invoke() != false &&
            clickCurrent() && origin.seed.ownsEntry() &&
            native.isCurrent(accepted) && origin.matchesResolvedRequest(accepted.request)
        if (!current()) return false
        var owned = false
        try {
            origin.seed.window.repository.withCurrentHomeNavRequest(origin.seed.primaryInstallation, ::current) {
                origin.seed.assembly.environment.commit { owned = current() }
            }
        } catch (_: CancellationException) { return false }
        catch (failure: BiliApiException) { if (failure.apiCode == -101) return false else throw failure }
        return owned
    }
    internal fun forRequest(request: PlaybackRequest, fallbackResumeMs: Long): DesktopVideoBootstrapSeed? =
        if (isCurrent()) origin.seed.forManualRequest(request, fallbackResumeMs, caller, callerStillOwned) else null
    override fun toString() = "DesktopOriginalManualPlaybackNavigation(sourceVersion=${accepted.sourceVersion})"
}

/** Source is fixed synchronously on the genuine click. The original AudioMode
 * playlist-only algorithm runs on this parent's real child task, outside every
 * Store/native gate. Unknown origin is kept Unknown, never backfilled from BV.
 * callerStillOwned is only parent/retained-entry lifetime, NOT old native/raw UI. */
internal fun launchDesktopOriginalManualAudioNavigation(
    assembly: DesktopOriginalVideoOwnerAssembly,
    expected: DesktopOriginalVideoAcceptedPublication,
    callerScope: CoroutineScope,
    forward: Boolean,
    clickCurrent: () -> Boolean,
    callerStillOwned: () -> Boolean,
) {
    val predecessor = assembly.playback.captureDesktopPlaybackState() as?
        com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState.Success ?: return
    fun current(): Boolean {
        return callerScope.isActive && callerStillOwned() && assembly.owns() &&
            assembly.native.isCurrent(expected) && assembly.playback.captureDesktopPlaybackState() === predecessor &&
            predecessor.info.bvid == expected.request.bvid &&
            predecessor.info.cid == expected.request.cid && !predecessor.isQualitySwitching
    }
    if (!current() || !clickCurrent()) return
    callerScope.launch {
        val caller = requireNotNull(currentCoroutineContext()[Job])
        // Original selection mutates the queue. Check the captured click/queue
        // only before it begins, not again after this same selection advances it.
        if (!caller.isActive || !current() || !clickCurrent()) return@launch
        val origin = expected.bootstrapOrigin
        if (origin != null && origin.seed.assembly !== assembly) return@launch
        val source = origin?.let {
            DesktopOriginalManualPlaybackNavigation(it, assembly.native, expected, caller,
                ::current, { callerScope.isActive && callerStillOwned() })
        }
        if (source != null && !source.isCurrent()) return@launch
        if (forward) assembly.playback.playNextAudioModeTrack(
            ignoreSavedProgress = false, desktopManualNavigation = source)
        else assembly.playback.playPreviousAudioModeTrack(
            ignoreSavedProgress = false, desktopManualNavigation = source)
    }
}
