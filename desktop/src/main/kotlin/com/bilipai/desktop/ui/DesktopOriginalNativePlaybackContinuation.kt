package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.bilipai.desktop.data.BiliApiException
import com.bilipai.desktop.player.PlayerNativeEof
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Immutable metadata from the existing native listener registration and accepted
 * publication. It owns no Job, Store, player or mutable latest-source state. */
class DesktopOriginalNativePlaybackContinuation internal constructor(
    private val origin: DesktopVideoBootstrapAccepted,
    private val native: DesktopOriginalVideoNativeOwner,
    private val accepted: DesktopOriginalVideoAcceptedPublication,
    private val registrationJob: Job,
    private val nativeEof: PlayerNativeEof? = null,
) {
    internal fun forEvent(nativeEof: PlayerNativeEof?): DesktopOriginalNativePlaybackContinuation =
        DesktopOriginalNativePlaybackContinuation(origin, native, accepted, registrationJob, nativeEof)
    internal fun isCurrent(): Boolean {
        fun current() = registrationJob.isActive && origin.seed.ownsEntry() &&
            native.isCurrent(accepted) && origin.matchesResolvedRequest(accepted.request) &&
            nativeEof != null && nativeEof.sourceVersion == accepted.sourceVersion && native.player.ownsNativeEof(nativeEof)
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
        if (isCurrent()) origin.seed.forAutomaticRequest(request, fallbackResumeMs, registrationJob) else null
    override fun toString() = "DesktopOriginalNativePlaybackContinuation(sourceVersion=${accepted.sourceVersion})"
}
