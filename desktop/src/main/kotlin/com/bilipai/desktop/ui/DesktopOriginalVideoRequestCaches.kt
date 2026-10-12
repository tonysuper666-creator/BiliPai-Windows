package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.RelatedVideo
import com.android.purebilibili.data.model.response.ViewInfo
import com.android.purebilibili.data.model.response.PlayUrlData
import com.android.purebilibili.data.repository.OnDemandRequestCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicReference

/** Memoized immutable data for ONE captured raw request Job/receipt. A cache never
 * owns or refreshes an API, account, entry or native source. Different captures
 * deliberately do not share the first request's owned API or successful data. */
internal class DesktopOriginalVideoRequestCaches(
    private val ownerJob: Job,
    private val accountScope: String,
    private val playbackAccountScope: String,
    private val invalidatePlayback: (String, Long) -> Unit,
    private val assertOwned: () -> Unit,
) {
    private data class DetailKey(val accountScope: String, val bvid: String, val aid: Long)
    private data class RelatedKey(val accountScope: String, val bvid: String)
    private data class InitialPlayUrlKey(
        val accountScope: String, val bvid: String, val cid: Long,
        val quality: Int, val autoHighest: Boolean, val audioLang: String?,
    )
    private class State(scope: CoroutineScope, accountCurrent: () -> Unit) {
        // Only merge in-flight address reads; the existing Root cache owns results.
        val initial = OnDemandRequestCache<InitialPlayUrlKey, Result<PlayUrlData?>>(
            maxEntries = 1, maxAgeMs = 0L, scope = scope,
        )
        val detail = OnDemandRequestCache<DetailKey, Result<ViewInfo>>(
            maxEntries = 48, maxAgeMs = 120_000L, scope = scope,
            canCache = { result -> accountCurrent(); result.isSuccess },
        )
        val related = OnDemandRequestCache<RelatedKey, Result<List<RelatedVideo>>>(
            maxEntries = 32, maxAgeMs = 45_000L, scope = scope,
            canCache = { result -> accountCurrent(); result.isSuccess },
        )
    }
    private data class ForcedDetail(
        val bvid: String, val aid: Long, val cid: Long,
        val coveredBvid: String, val coveredCid: Long,
    )
    private val forcedDetail = AtomicReference<ForcedDetail?>()
    private val monitor = Any()
    private var state: State? = null

    private fun current() { ownerJob.ensureActive(); assertOwned() }
    private suspend fun cacheState(): State {
        val context = currentCoroutineContext()
        context.ensureActive(); current()
        // Keep the real InvocationElement/bootstrap context from the consumer,
        // but borrow the captured raw owner Job, not that first async waiter's Job.
        // There is no new SupervisorJob or persistent/global worker authority.
        val borrowedScope = CoroutineScope(context.minusKey(Job) + ownerJob + Dispatchers.IO)
        return synchronized(monitor) { state ?: State(borrowedScope, ::current).also { state = it } }
            .also { current() }
    }
    suspend fun detail(bvid: String, aid: Long, load: suspend () -> Result<ViewInfo>): Result<ViewInfo> {
        currentCoroutineContext().ensureActive(); current()
        val value = cacheState().detail.getOrLoad(DetailKey(accountScope, bvid, aid)) {
            current(); load().also { currentCoroutineContext().ensureActive(); current() }
        }
        currentCoroutineContext().ensureActive(); current()
        return value
    }
    fun cachedDetail(bvid: String, aid: Long): ViewInfo? {
        current()
        val cache = synchronized(monitor) { state }
        val cached = cache?.detail?.peek(DetailKey(accountScope, bvid, aid))?.getOrNull()
        // Raw metadata is cached before Shared.detail resolves the requested P.
        // Do not bootstrap a fresh INITIAL request before deferred force invalidation.
        val pending = forcedDetail.get()
        return cached.takeUnless { pending != null && pending.bvid == bvid && pending.aid == aid }
            .also { current() }
    }
    suspend fun related(bvid: String, load: suspend () -> Result<List<RelatedVideo>>): Result<List<RelatedVideo>> {
        currentCoroutineContext().ensureActive(); current()
        val value = cacheState().related.getOrLoad(RelatedKey(accountScope, bvid)) {
            current(); load().also { currentCoroutineContext().ensureActive(); current() }
        }
        currentCoroutineContext().ensureActive(); current()
        return value
    }
    fun cachedRelated(bvid: String): Result<List<RelatedVideo>>? {
        current()
        val cache = synchronized(monitor) { state }
        return cache?.related?.peek(RelatedKey(accountScope, bvid)).also { current() }
    }
    /** Called after the original requested-CID policy, never with raw first-P data. */
    fun resolvedDetail(bvid: String, aid: Long, rawInfo: ViewInfo, info: ViewInfo) {
        current()
        val forced = forcedDetail.get()?.takeIf { it.bvid == bvid && it.aid == aid }
        val expectedCid = forced?.let {
            com.android.purebilibili.data.repository.resolveRequestedVideoCid(it.cid, rawInfo.cid, rawInfo.pages)
        }
        // Coalescing raw detail across different P readers must not let another
        // reader consume this forced route's deferred playback invalidation.
        if (forced != null && info.bvid.isNotBlank() && info.cid > 0L && info.cid == expectedCid) {
            // Already covered before any new metadata/address child starts: do
            // not cancel the fresh INITIAL worker or evict its fresh result.
            if (forced.coveredBvid != info.bvid || forced.coveredCid != info.cid) {
                invalidatePlayback(info.bvid, info.cid)
                invalidateInitialPlayUrls(bvid, info.bvid, info.cid)
            }
            forcedDetail.compareAndSet(forced, null)
        }
        current()
    }
    /** Every forced route needs actual page resolution: a new binding is cold
     * even when Root already holds a warm address for this canonical video. */
    fun forceDetail(bvid: String, aid: Long, cid: Long, coveredBvid: String, coveredCid: Long) {
        current()
        if (bvid.isNotBlank() || aid > 0L)
            forcedDetail.set(ForcedDetail(bvid, aid, cid, coveredBvid, coveredCid))
        current()
    }
    fun invalidateDetail(bvid: String, aid: Long) {
        current(); synchronized(monitor) { state }?.detail?.invalidate(DetailKey(accountScope, bvid, aid)); current()
    }
    fun invalidateRelated(bvid: String) {
        current(); synchronized(monitor) { state }?.related?.invalidate(RelatedKey(accountScope, bvid)); current()
    }
    suspend fun initialPlayUrl(bvid: String, cid: Long, quality: Int,
        autoHighest: Boolean, audioLang: String?, load: suspend () -> PlayUrlData?): PlayUrlData? {
        currentCoroutineContext().ensureActive(); current()
        val value = cacheState().initial.getOrLoad(
            InitialPlayUrlKey(playbackAccountScope, bvid, cid, quality, autoHighest, audioLang),
        ) {
            current()
            // Ordinary API errors are data inside a child of the existing raw Job.
            // Unboxing in the waiting caller preserves the original UseCase mapping
            // without first canceling that Job or its early-detail sibling.
            val loaded = try { Result.success(load()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
            currentCoroutineContext().ensureActive(); current()
            loaded
        }
        currentCoroutineContext().ensureActive(); current()
        return value.getOrThrow()
    }
    fun invalidateInitialPlayUrls(bvid: String, canonicalBvid: String, cid: Long) {
        current()
        synchronized(monitor) { state }?.initial?.invalidateWhere {
            (it.bvid == bvid || it.bvid == canonicalBvid) && (cid <= 0L || it.cid == cid)
        }
        current()
    }
    /** Original completed-cache clearing does not cancel another current waiter. */
    fun clearDetail() { current(); synchronized(monitor) { state }?.detail?.clearCompleted(); current() }
    fun clearRelated() { current(); synchronized(monitor) { state }?.related?.clearCompleted(); current() }
}
