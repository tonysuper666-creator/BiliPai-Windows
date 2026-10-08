package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.bilipai.desktop.data.BiliApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

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
) {
    internal fun isCurrent(): Boolean {
        fun current() = !caller.isCancelled && clickCurrent() && origin.seed.ownsEntry() &&
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
        if (isCurrent()) origin.seed.forManualRequest(request, fallbackResumeMs, caller) else null
    override fun toString() = "DesktopOriginalManualPlaybackNavigation(sourceVersion=${accepted.sourceVersion})"
}
