package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.RelatedVideo
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/** Entry-owned readonly presentation witness. Minted inside the SAME real raw
 * Binding admission. Its lifetime is the exact original request/token + entry
 * + account receipt, not a completed network Job. No API, media, cookie or cache
 * is retained or exposed; navigation starts Root's existing fresh route capture. */
internal class DesktopOriginalVideoMetadataAdmission internal constructor(
    val receipt: DesktopPlaybackAuthorizationReceipt,
    private val subjectCurrent: () -> Boolean,
    private val entryAdmission: ((() -> Unit) -> Boolean),
) {
    /** Cheap subject predicate for a NEW Factory request's own receipt gate.
     * Never substitute this predicate for account admission when mutating UI. */
    fun ownsSubject(): Boolean = subjectCurrent()
    fun admit(action: () -> Unit): Boolean = try {
        var applied = false
        subjectCurrent() && entryAdmission {
            if (subjectCurrent()) { action(); applied = true }
        } && applied
    } catch (_: CancellationException) { false }
    fun isCurrent(): Boolean = admit {}
    fun assertCurrent() {
        if (!isCurrent()) throw CancellationException("Readonly video metadata entry retired")
    }
}

/** Borrow the actual main-load coroutine context, including its captured
 * Invocation/Bootstrap elements and real parent Job. No new parent/Supervisor,
 * latest repository, Native publication or detached request authority. */
internal class DesktopOriginalVideoMetadataReadScope internal constructor(
    private val context: CoroutineContext,
    private val repository: DesktopOriginalVideoLoadRepository,
    private val assertBinding: () -> Unit,
    val presentation: DesktopOriginalVideoMetadataAdmission,
) {
    private val parent = checkNotNull(context[Job])
    fun assertCurrent() {
        parent.ensureActive(); assertBinding(); presentation.assertCurrent()
    }
    fun launch(start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit): Job {
        assertCurrent()
        return CoroutineScope(context).launch(start = start) {
            assertCurrent(); block()
        }
    }
    suspend fun cachedRelated(bvid: String): Result<List<RelatedVideo>>? {
        currentCoroutineContext().ensureActive(); assertCurrent()
        return repository.getCachedRelatedVideosResult(bvid).also {
            currentCoroutineContext().ensureActive(); assertCurrent()
        }
    }
    suspend fun related(bvid: String): Result<List<RelatedVideo>> {
        currentCoroutineContext().ensureActive(); assertCurrent()
        val result = try { repository.getRelatedVideosResult(bvid) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        currentCoroutineContext().ensureActive(); assertCurrent()
        return result
    }
}

/** Genuine Windows connectivity observation. The current WinRT ABI does not
 * report downstream capacity, so null means "no estimate", exactly as the
 * original scheduler's no-estimate case. Packet media bitrate is not throughput.
 * No estimate does not make a desktop link constrained: the related list waits
 * for playback headroom only when Windows reports no internet access, a mobile
 * (WWAN/cellular) link, or a measured rate at or below 1500 kbps. An unmetered
 * wired or Wi-Fi link without an estimate loads the list in parallel with the
 * video, like upstream. The property name stays because the pinned detail
 * holder reads it; its comment about deferring unknown is superseded here.
 */
internal data class DesktopOriginalVideoRelatedNetworkObservation(
    val connection: DesktopHomeNetworkObservation,
    val downstreamBandwidthKbps: Int?,
) {
    val constrainedOrUnknown: Boolean get() = !connection.hasInternetAccess ||
        connection.isMobileNetwork ||
        (downstreamBandwidthKbps != null && downstreamBandwidthKbps <= 1_500)
}
