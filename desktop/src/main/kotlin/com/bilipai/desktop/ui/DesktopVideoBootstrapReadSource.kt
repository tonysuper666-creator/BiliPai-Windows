package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.repository.ContentRequestException
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.bilipai.desktop.data.BiliApiException
import com.bilipai.desktop.data.DesktopHomeNavRequestReceipt
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Original typed bootstrap entry and primary install receipt, captured before openVideoDetail.
 * A covered entry remains valid. Nothing reads a later current video to populate this seed. */
internal class DesktopVideoBootstrapSeed private constructor(
    val window: DesktopOriginalVideoRootWindowEnvironment,
    val assembly: DesktopOriginalVideoOwnerAssembly,
    val route: BiliPaiNavKey.VideoDetail,
    val bootstrapCaller: Job,
    val primaryInstallation: DesktopHomeNavRequestReceipt,
) {
    fun owns(): Boolean = !bootstrapCaller.isCancelled && window.owns() && assembly.owns() &&
        window.commands.containsEntry(route)
    fun accepted(request: PlaybackRequest, token: Long, fallbackResumeMs: Long): DesktopVideoBootstrapAccepted? {
        if (!owns() || request.bvid != route.bvid || request.cid != route.cid ||
            fallbackResumeMs != route.resumePositionMs.coerceAtLeast(0L)) return null
        // The actual leaf assembly's sole original VM has already accepted this SAME request.
        // If another Facade lease owns the load, it keeps the original read with Unknown origin.
        val original = assembly.captureLoadState()
        if (original.currentRequest !== request || original.currentLoadRequestToken != token) return null
        return DesktopVideoBootstrapAccepted(this, request, token, fallbackResumeMs)
    }
    companion object {
        suspend fun capture(window: DesktopOriginalVideoRootWindowEnvironment,
            assembly: DesktopOriginalVideoOwnerAssembly, route: BiliPaiNavKey.VideoDetail): DesktopVideoBootstrapSeed {
            check(java.awt.EventQueue.isDispatchThread())
            val context = currentCoroutineContext(); context.ensureActive()
            val caller = requireNotNull(context[Job])
            if (!window.owns() || !assembly.owns() || window.currentKey() !== route || !window.commands.containsEntry(route))
                throw CancellationException("Actual bootstrap entry retired")
            val primary = window.repository.captureHomeNavRequest(window.root.capturedEpoch, window.root.entry.gate.mid) {
                caller.isActive && window.owns() && assembly.owns()
            }
            return DesktopVideoBootstrapSeed(window, assembly, route, caller, primary)
        }
    }
}

/** SAME accepted original request reference/token. No invented generation. */
internal class DesktopVideoBootstrapAccepted(val seed: DesktopVideoBootstrapSeed,
    val request: PlaybackRequest, val requestToken: Long, val fallbackResumeMs: Long) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopVideoBootstrapAccepted>
}

/** Real request Binding's nonsecret transport projection. Selected main-MID playback records
 * do not imply a dedicated cookie transport; the actual captured CookieJar decides that. */
internal data class DesktopVideoBootstrapAuthorization(
    val receipt: DesktopPlaybackAuthorizationReceipt,
    val usesDedicatedCookieJar: Boolean,
    val selectedPlaybackMid: Long?,
)

internal enum class DesktopVideoBootstrapApiLane { PRIMARY_DETAIL, PRIMARY_PLAYBACK_COOKIE, DEDICATED_PLAYBACK_COOKIE }
internal sealed interface DesktopVideoBootstrapApiParameters {
    data class Detail(val bvid: String, val aid: Long, val requestedCid: Long) : DesktopVideoBootstrapApiParameters
    data class WebPlayUrl(val bvid: String, val cid: Long, val qn: Int, val audioLang: String?) : DesktopVideoBootstrapApiParameters
}
internal data class DesktopVideoBootstrapMappedApiFailure(val error: VideoLoadError,
    val code: Int, val lane: DesktopVideoBootstrapApiLane, val parameters: DesktopVideoBootstrapApiParameters)

/** One captured real launch/request, with the actual original VM body Job bound once when
 * withInvocation enters its existing withContext. No new Job, scope, VM, Flow or cache. */
internal class DesktopVideoBootstrapReadSource(val accepted: DesktopVideoBootstrapAccepted,
    val factoryCaller: Job, val authorization: DesktopVideoBootstrapAuthorization) {
    private val bodyCaller = AtomicReference<Job?>()
    private val mapped = AtomicReference<DesktopVideoBootstrapMappedApiFailure?>()
    fun bodyJob(): Job? = bodyCaller.get()
    fun bindBodyCaller(job: Job) {
        if (!factoryCaller.isActive || !job.isActive || !bodyCaller.compareAndSet(null, job))
            throw CancellationException("Bootstrap body caller is not the original single invocation")
        if (!admit(true) {}) throw CancellationException("Bootstrap installation retired before body")
    }
    fun accepts(parameters: DesktopVideoBootstrapApiParameters): Boolean = when (parameters) {
        is DesktopVideoBootstrapApiParameters.Detail -> parameters.bvid == accepted.request.bvid &&
            parameters.aid == accepted.request.aid && parameters.requestedCid == accepted.request.cid
        is DesktopVideoBootstrapApiParameters.WebPlayUrl -> parameters.bvid == accepted.request.bvid && parameters.cid > 0L &&
            (accepted.request.cid <= 0L || parameters.cid == accepted.request.cid)
    }
    fun rememberMapped(error: VideoLoadError, trace: DesktopVideoBootstrapApiTrace) {
        if (trace.source === this && factoryCaller.isActive && bodyCaller.get()?.isActive == true &&
            error is VideoLoadError.ApiError && error.code == trace.code)
            mapped.set(DesktopVideoBootstrapMappedApiFailure(error, trace.code, trace.lane, trace.parameters))
    }
    fun mappedFor(error: VideoLoadError): DesktopVideoBootstrapMappedApiFailure? = mapped.get()?.takeIf { it.error === error }
    fun admit(requireActive: Boolean, block: () -> Unit): Boolean {
        fun current(): Boolean = accepted.seed.owns() &&
            (if (requireActive) factoryCaller.isActive && bodyCaller.get()?.isActive != false
             else !factoryCaller.isCancelled && bodyCaller.get()?.isCancelled != true)
        var applied = false
        try {
            accepted.seed.window.repository.withCurrentHomeNavRequest(accepted.seed.primaryInstallation, ::current) {
                accepted.seed.window.repository.withPlaybackReceiptAdmission(authorization.receipt, ::current) {
                    accepted.seed.assembly.environment.commit { if (current()) { block(); applied = true } }
                }
            }
        } catch (_: CancellationException) { return false }
        catch (_: BiliApiException) { return false }
        return applied
    }
    companion object {
        suspend fun capture(accepted: DesktopVideoBootstrapAccepted, state: PlaybackSessionState,
            binding: DesktopOriginalVideoRepositoryBinding): DesktopVideoBootstrapReadSource {
            val caller = requireNotNull(currentCoroutineContext()[Job]); currentCoroutineContext().ensureActive()
            if (state.currentRequest !== accepted.request || state.currentLoadRequestToken != accepted.requestToken || !accepted.seed.owns())
                throw CancellationException("Bootstrap accepted original request replaced")
            val source = DesktopVideoBootstrapReadSource(accepted, caller, binding.captureBootstrapAuthorization())
            if (!source.admit(true) {}) throw CancellationException("Bootstrap primary installation replaced")
            return source
        }
    }
}

internal class DesktopVideoBootstrapInvocationContext(val source: DesktopVideoBootstrapReadSource) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DesktopVideoBootstrapInvocationContext>
}

/** Diagnostic sidecar on the SAME original Throwable. No exception, code or message is replaced.
 * Suppression itself has no stack/credential text; every consumer matches actual source identity. */
internal class DesktopVideoBootstrapApiTrace(val source: DesktopVideoBootstrapReadSource, val code: Int,
    val lane: DesktopVideoBootstrapApiLane, val parameters: DesktopVideoBootstrapApiParameters) :
    RuntimeException("Original bootstrap API evidence", null, false, false)

internal suspend fun desktopVideoBootstrapTagApiFailure(failure: Throwable,
    parameters: DesktopVideoBootstrapApiParameters): Throwable {
    val code = when (failure) { is BiliApiException -> failure.apiCode; is ContentRequestException -> failure.code; else -> return failure }
    if (code == 0) return failure
    val context = currentCoroutineContext()
    if (!context.isActive) return failure
    val source = context[DesktopVideoBootstrapInvocationContext]?.source ?: return failure
    if (!source.factoryCaller.isActive || source.bodyJob()?.isActive != true || !source.accepts(parameters)) return failure
    val lane = when (parameters) {
        is DesktopVideoBootstrapApiParameters.Detail -> DesktopVideoBootstrapApiLane.PRIMARY_DETAIL
        is DesktopVideoBootstrapApiParameters.WebPlayUrl -> if (source.authorization.usesDedicatedCookieJar)
            DesktopVideoBootstrapApiLane.DEDICATED_PLAYBACK_COOKIE else DesktopVideoBootstrapApiLane.PRIMARY_PLAYBACK_COOKIE
    }
    // Another request may have used the same Throwable; its trace is never adopted by this source.
    if (failure.suppressed.filterIsInstance<DesktopVideoBootstrapApiTrace>().none { it.source === source && it.parameters == parameters })
        failure.addSuppressed(DesktopVideoBootstrapApiTrace(source, code, lane, parameters))
    return failure
}

/** Retry classification calls the unobserved classifier. Only actual returned mappings reach here. */
internal suspend fun desktopVideoBootstrapObserveMappedFailure(failure: Throwable, error: VideoLoadError) {
    val context = currentCoroutineContext()
    if (!context.isActive) return
    val source = context[DesktopVideoBootstrapInvocationContext]?.source ?: return
    val trace = failure.suppressed.filterIsInstance<DesktopVideoBootstrapApiTrace>().singleOrNull { it.source === source }
        ?: return
    source.rememberMapped(error, trace)
}

internal data class DesktopVideoBootstrapLoadFailure(val source: DesktopVideoBootstrapReadSource,
    val displayedError: VideoPlaybackUiState.Error,
    val apiEvidence: DesktopVideoBootstrapMappedApiFailure?)
