package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey
import java.awt.EventQueue

/** A borrowed exact displayed read, not a navigation owner, account action or replay queue.
 * Route admission performs its original checkpoint before invoking this short source gate. */
internal sealed interface DesktopReadFailureLoginIntent {
    val root: DesktopHomeRetainedRoot
    val sourceEpoch: Long
    val sourceMid: Long?
    val destination: BiliPaiNavKey
    fun admit(routes: DesktopOriginalRootRouteAssembly, block: () -> Unit): Boolean
}

/** The source entry is retained by reference separately from its sanitized read-return key.
 * A final API -101 from the actual primary transport is required. Unknown, intermediate APP,
 * dedicated-cookie, native and initialization failures keep the original retry action. */
internal class DesktopVideoFailureLoginIntent private constructor(
    private val window: DesktopOriginalVideoRootWindowEnvironment,
    private val shell: DesktopOriginalVideoShellOwner,
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val entry: BiliPaiNavKey.VideoDetail,
    private val failure: DesktopVideoBootstrapLoadFailure,
    private val stillPresented: () -> Boolean,
    override val destination: BiliPaiNavKey.VideoDetail,
) : DesktopReadFailureLoginIntent {
    override val root: DesktopHomeRetainedRoot get() = window.root
    override val sourceEpoch: Long get() = failure.source.accepted.seed.primaryInstallation.epoch
    override val sourceMid: Long? get() = failure.source.accepted.seed.primaryInstallation.mid

    private fun current(routes: DesktopOriginalRootRouteAssembly): Boolean {
        val source = failure.source
        val accepted = source.accepted
        val evidence = failure.apiEvidence ?: return false
        if (window.commands !== routes || routes.root !== root || !routes.owns() ||
            !window.owns() || !stillPresented() || window.inPictureInPicture() ||
            routes.currentKey !== entry || window.currentKey() !== entry ||
            shell.slot.currentAssembly() !== assembly || !assembly.owns() ||
            accepted.seed.window !== window || accepted.seed.assembly !== assembly ||
            accepted.seed.route !== entry || sourceEpoch != root.capturedEpoch ||
            sourceMid != root.entry.gate.mid || evidence.code != -101 ||
            evidence.error !== failure.displayedError.error || !source.accepts(evidence.parameters)) return false
        when (evidence.lane) {
            DesktopVideoBootstrapApiLane.PRIMARY_DETAIL -> Unit
            DesktopVideoBootstrapApiLane.PRIMARY_PLAYBACK_COOKIE ->
                if (source.authorization.usesDedicatedCookieJar) return false
            DesktopVideoBootstrapApiLane.DEDICATED_PLAYBACK_COOKIE -> return false
        }
        val actual = assembly.captureLoadState()
        return actual.currentRequest === accepted.request &&
            actual.currentLoadRequestToken == accepted.requestToken &&
            assembly.playback.captureDesktopPlaybackState() === failure.displayedError &&
            assembly.playback.desktopBootstrapReadFailure() === failure
    }

    override fun admit(routes: DesktopOriginalRootRouteAssembly, block: () -> Unit): Boolean {
        check(EventQueue.isDispatchThread())
        var applied = false
        // Same repository's primary installation and complete playback receipt, then the
        // existing Video entry gate. Normal read completion remains valid; cancellation does not.
        failure.source.admit(false) {
            if (current(routes)) { block(); applied = true }
        }
        return applied
    }

    companion object {
        fun capture(window: DesktopOriginalVideoRootWindowEnvironment,
            shell: DesktopOriginalVideoShellOwner, assembly: DesktopOriginalVideoOwnerAssembly,
            entry: BiliPaiNavKey.VideoDetail, displayedError: VideoPlaybackUiState.Error?,
            stillPresented: () -> Boolean): DesktopVideoFailureLoginIntent? {
            check(EventQueue.isDispatchThread())
            val routes = window.commands as? DesktopOriginalRootRouteAssembly ?: return null
            if (displayedError == null || !assembly.owns() || !stillPresented()) return null
            val failure = assembly.playback.desktopBootstrapReadFailure() ?: return null
            if (failure.displayedError !== displayedError) return null
            val accepted = failure.source.accepted
            // Only parameters on this genuine accepted read are copied. The original entry
            // reference remains the source; the new Login-return key never proves ownership.
            val destination = entry.copy(bvid = accepted.request.bvid, cid = accepted.request.cid,
                resumePositionMs = accepted.fallbackResumeMs)
            val intent = DesktopVideoFailureLoginIntent(window, shell, assembly, entry, failure,
                stillPresented, destination)
            return intent.takeIf { it.admit(routes) {} }
        }
    }
}
