package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey
import java.awt.EventQueue
import kotlinx.coroutines.ensureActive

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
    private val entry: BiliPaiNavKey,
    private val failure: DesktopVideoBootstrapLoadFailure,
    private val stillPresented: () -> Boolean,
    override val destination: BiliPaiNavKey,
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
            stillPresented: () -> Boolean): DesktopVideoFailureLoginIntent? =
            captureTyped(window, shell, assembly, entry, displayedError, stillPresented)
        fun captureAudio(window: DesktopOriginalVideoRootWindowEnvironment,
            shell: DesktopOriginalVideoShellOwner, assembly: DesktopOriginalVideoOwnerAssembly,
            entry: BiliPaiNavKey, displayedError: VideoPlaybackUiState.Error?,
            stillPresented: () -> Boolean): DesktopVideoFailureLoginIntent? {
            if (entry !is BiliPaiNavKey.AudioMode && entry !is BiliPaiNavKey.NativeMusic) return null
            return captureTyped(window, shell, assembly, entry, displayedError, stillPresented)
        }
        private fun captureTyped(window: DesktopOriginalVideoRootWindowEnvironment,
            shell: DesktopOriginalVideoShellOwner, assembly: DesktopOriginalVideoOwnerAssembly,
            entry: BiliPaiNavKey, displayedError: VideoPlaybackUiState.Error?,
            stillPresented: () -> Boolean): DesktopVideoFailureLoginIntent? {
            check(EventQueue.isDispatchThread())
            val routes = window.commands as? DesktopOriginalRootRouteAssembly ?: return null
            if (displayedError == null || !assembly.owns() || !stillPresented()) return null
            val failure = assembly.playback.desktopBootstrapReadFailure() ?: return null
            if (failure.displayedError !== displayedError) return null
            val accepted = failure.source.accepted
            // Only parameters on this genuine accepted read are copied. The original entry
            // reference remains the source; the new Login-return key never proves ownership.
            val destination = when (entry) {
                is BiliPaiNavKey.VideoDetail -> entry.copy(bvid = accepted.request.bvid, cid = accepted.request.cid,
                    resumePositionMs = accepted.fallbackResumeMs)
                is BiliPaiNavKey.AudioMode -> entry.copy(sourceBvid = accepted.request.bvid,
                    sourceCid = accepted.request.cid, sourceResumePositionMs = accepted.fallbackResumeMs)
                is BiliPaiNavKey.NativeMusic -> {
                    // The original key has no resume field: never discard a real nonzero read target.
                    if (accepted.fallbackResumeMs != 0L) return null
                    entry.copy(bvid = accepted.request.bvid, cid = accepted.request.cid)
                }
                else -> return null
            }
            val intent = DesktopVideoFailureLoginIntent(window, shell, assembly, entry, failure,
                stillPresented, destination)
            return intent.takeIf { it.admit(routes) {} }
        }
    }
}

/** Metadata of ONE genuine initial PGC/PUGV primary-detail request. This is a
 * borrowed receipt and actual caller, not a task, source, credential or replay owner.
 * Normal request completion retains a displayed Error; cancellation/entry/account
 * retirement does not. The already existing Store -> Video-entry gates are reused. */
internal class DesktopBangumiInitialDetailSource private constructor(
    val window: DesktopOriginalVideoRootWindowEnvironment,
    val assembly: DesktopOriginalVideoOwnerAssembly,
    val entry: BiliPaiNavKey.BangumiPlayer,
    val seasonId: Long,
    val epId: Long,
    val isCourse: Boolean,
    val caller: kotlinx.coroutines.Job,
    val launchCaller: kotlinx.coroutines.Job,
    val primaryInstallation: com.bilipai.desktop.data.DesktopHomeNavRequestReceipt,
    val authorization: com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt,
    private val entryOwns: () -> Boolean,
) {
    private fun owns(): Boolean = !caller.isCancelled && !launchCaller.isCancelled && window.owns() && assembly.owns() && entryOwns() &&
        (window.commands as? DesktopOriginalRootRouteAssembly)?.stack?.any { it === entry } == true

    fun admit(block: () -> Unit): Boolean {
        var applied = false
        try {
            window.repository.withCurrentHomeNavRequest(primaryInstallation, ::owns) {
                window.repository.withPlaybackReceiptAdmission(authorization, ::owns) {
                    assembly.environment.commit { if (owns()) { block(); applied = true } }
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) { return false }
        catch (_: com.bilipai.desktop.data.BiliApiException) { return false }
        return applied
    }
    fun failure(code: Int, message: String): Throwable = DesktopBangumiInitialDetailFailure(this, code, message)

    companion object {
        suspend fun capture(window: DesktopOriginalVideoRootWindowEnvironment,
            assembly: DesktopOriginalVideoOwnerAssembly, entry: BiliPaiNavKey.BangumiPlayer,
            seasonId: Long, epId: Long, isCourse: Boolean, launchCaller: kotlinx.coroutines.Job,
            binding: DesktopOriginalVideoRepositoryBinding, entryOwns: () -> Boolean): DesktopBangumiInitialDetailSource {
            val context = kotlinx.coroutines.currentCoroutineContext()
            context.ensureActive()
            val caller = requireNotNull(context[kotlinx.coroutines.Job])
            launchCaller.ensureActive()
            binding.assertCurrent()
            val primary = window.repository.captureHomeNavRequest(window.root.capturedEpoch, window.root.entry.gate.mid) {
                caller.isActive && window.owns() && assembly.owns() && entryOwns()
            }
            val authorization = binding.captureBootstrapAuthorization().receipt
            binding.assertCurrent()
            val source = DesktopBangumiInitialDetailSource(window, assembly, entry,
                seasonId, epId, isCourse, caller, launchCaller, primary, authorization, entryOwns)
            if (!source.admit {}) throw kotlinx.coroutines.CancellationException("PGC initial detail source retired")
            return source
        }
    }
}

/** Public Error DTO exposes only this public Throwable type. Its constructor and
 * request source stay module-internal. The message is the unchanged original text. */
class DesktopBangumiInitialDetailFailure internal constructor(
    internal val source: DesktopBangumiInitialDetailSource,
    val code: Int,
    message: String,
    private val latestLoad: (() -> Boolean)? = null,
) : Exception(message) {
    internal fun forDisplayedRead(latest: () -> Boolean): DesktopBangumiInitialDetailFailure {
        check(latestLoad == null)
        return DesktopBangumiInitialDetailFailure(source, code, requireNotNull(message), latest)
    }
    internal fun admit(block: () -> Unit): Boolean {
        val current = latestLoad ?: return false
        var applied = false
        source.admit { if (current()) { block(); applied = true } }
        return applied
    }
}

/** Explicit user Login for the SAME displayed initial detail failure. Decoded
 * primary -101 is required; no text matching, dedicated playurl or native evidence. */
internal class DesktopBangumiFailureLoginIntent private constructor(
    private val source: DesktopBangumiInitialDetailSource,
    private val displayedError: com.android.purebilibili.feature.bangumi.BangumiPlayerState.Error,
    private val stillPresented: () -> Boolean,
) : DesktopReadFailureLoginIntent {
    override val root: DesktopHomeRetainedRoot get() = source.window.root
    override val sourceEpoch: Long get() = source.primaryInstallation.epoch
    override val sourceMid: Long? get() = source.primaryInstallation.mid
    override val destination: BiliPaiNavKey get() = source.entry

    private fun current(routes: DesktopOriginalRootRouteAssembly): Boolean {
        val failure = displayedError.desktopInitialDetailFailure ?: return false
        return failure.source === source && failure.code == -101 && displayedError.isLoginRequired &&
            source.window.commands === routes && routes.root === root && routes.owns() &&
            sourceEpoch == root.capturedEpoch && sourceMid == root.entry.gate.mid &&
            source.window.currentKey() === source.entry && routes.currentKey === source.entry &&
            !source.window.inPictureInPicture() && stillPresented()
    }
    override fun admit(routes: DesktopOriginalRootRouteAssembly, block: () -> Unit): Boolean {
        check(EventQueue.isDispatchThread())
        var applied = false
        displayedError.desktopInitialDetailFailure?.admit { if (current(routes)) { block(); applied = true } }
        return applied
    }
    companion object {
        fun capture(routes: DesktopOriginalRootRouteAssembly, entry: BiliPaiNavKey.BangumiPlayer,
            error: com.android.purebilibili.feature.bangumi.BangumiPlayerState.Error?,
            stillPresented: () -> Boolean): DesktopBangumiFailureLoginIntent? {
            check(EventQueue.isDispatchThread())
            val failure = error?.desktopInitialDetailFailure ?: return null
            if (failure.source.entry !== entry || failure.code != -101 || !error.isLoginRequired) return null
            return DesktopBangumiFailureLoginIntent(failure.source, error, stillPresented)
                .takeIf { it.admit(routes) {} }
        }
    }
}
