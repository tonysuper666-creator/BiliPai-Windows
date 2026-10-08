package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.DynamicApi
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.home.DesktopOriginalHomeViewModel
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Stateless admission around the existing SessionStore; no credential, list or planner store.
 * Lock order is SessionStore -> this entry. close() never acquires the SessionStore monitor. */
internal class DesktopHomeRetainedGate(
    private val sessions: DesktopDynamicCacheSessionGuard,
    private val capturedOwner: DesktopDynamicCacheOwner,
    private val currentEpoch: () -> Long,
    private val currentMid: () -> Long?,
    private val rootAlive: () -> Boolean,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val lock = Any()
    @Volatile private var closed = false
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + job)
    val epoch: Long get() = capturedOwner.epoch
    val mid: Long? get() = capturedOwner.mid.takeIf { it > 0L }
    fun owns(): Boolean = !closed && job.isActive && rootAlive() && currentEpoch() == epoch &&
        (currentMid()?.takeIf { it > 0L } ?: 0L) == capturedOwner.mid
    fun assertOwned() { if (!owns()) throw CancellationException("Home entry retired") }
    fun commit(block: () -> Unit): Boolean {
        var applied = false
        sessions.withCurrentDynamicCacheOwner(capturedOwner) {
            synchronized(lock) { if (owns()) { block(); applied = true } }
        }
        return applied
    }
    override fun close() { synchronized(lock) { if (!closed) { closed = true; job.cancel() } } }
    suspend fun closeAndJoin() { close(); job.join() }
}

/** Root retains this once per actual account epoch, outside conditional page drawing.
 * The original Home VM is also the only TodayWatch planner. Runtime receives this owner,
 * so replacement/shutdown closes requests and joins the VM before constructing its successor. */
internal class DesktopHomeRetainedEntry private constructor(
    val gate: DesktopHomeRetainedGate,
    val requests: DesktopHomeRootRequestBinding,
    val viewModel: DesktopOriginalHomeViewModel,
    val embeddedPages: DesktopHomeEmbeddedRetainedOwner,
) : DesktopOriginalTodayWatchOwner, AutoCloseable {
    override val capturedEpoch: Long get() = gate.epoch
    override val todayWatchState get() = viewModel.todayWatchState
    val ui: DesktopOriginalHomeStateOwner get() = viewModel
    override fun isCurrentOwner() = gate.owns() && viewModel.isCurrentOwner()
    override suspend fun reloadTodayWatch(forceHistory: Boolean) = viewModel.reloadTodayWatch(forceHistory)
    override suspend fun consumeTodayWatchBvid(bvid: String) = viewModel.consumeTodayWatchBvid(bvid)
    override fun close() {
        gate.close()
        try { embeddedPages.close() }
        finally { try { requests.close() } finally { viewModel.close() } }
    }
    override suspend fun closeAndJoin() = withContext(NonCancellable) {
        try { close(); embeddedPages.closeAndJoin() }
        finally { try { viewModel.closeAndJoin() } finally { gate.closeAndJoin() } }
    }

    companion object {
        /** Required Root bindings are real existing consumers, not optional fake callbacks. */
        fun create(
            repository: DesktopRepository,
            discoveryPreferences: DesktopDiscoveryPreferences,
            globalContext: DesktopPluginContext,
            parentScope: CoroutineScope,
            capturedEpoch: Long,
            capturedMid: Long?,
            rootAlive: () -> Boolean,
            homeSettings: DesktopHomeSettingsPort,
            incrementalTimelineRefresh: StateFlow<Boolean>,
            privacyModeEnabledSync: () -> Boolean,
            identityAnalyticsFactory: (DesktopHomeRetainedGate) -> DesktopHomeIdentityAnalytics,
            actualBlockedRepository: DesktopBlockedUpRepository,
            onAuthenticationInvalidated: (DesktopHomeAuthenticationInvalidation) -> Unit,
            feedback: (String) -> Unit,
            embeddedPagesFactory: (DesktopHomeRetainedGate, DesktopHomeRootRequestBinding) -> DesktopHomeEmbeddedRetainedOwner,
        ): DesktopHomeRetainedEntry {
            val guard = repository.dynamicCacheSessionGuard
            require(actualBlockedRepository.store.context.store === globalContext.store) {
                "Home requires the same Root global block store"
            }
            val owner = guard.dynamicCacheOwner() ?: throw CancellationException("Home session unavailable")
            if (owner.epoch != capturedEpoch || owner.mid != (capturedMid ?: 0L))
                throw CancellationException("Home epoch changed before construction")
            val gate = DesktopHomeRetainedGate(guard, owner, { repository.sessionEpoch },
                { repository.account.value?.mid }, rootAlive, parentScope)
            var binding: DesktopHomeRootRequestBinding? = null
            var homeVm: DesktopOriginalHomeViewModel? = null
            var pages: DesktopHomeEmbeddedRetainedOwner? = null
            try {
                gate.assertOwned()
                val requests = DesktopHomeRootRequestBinding(repository, discoveryPreferences, gate.scope,
                    capturedEpoch, capturedMid, gate::owns, gate::commit, onAuthenticationInvalidated)
                binding = requests
                val ownedDynamic = repository.ownedHomeService(DynamicApi::class.java,
                    "https://api.bilibili.com/", capturedEpoch,
                    { gate.owns() && requests.isMountedSourceCurrent() })
                // Reuse the sole original fetch/pagination implementation, HOME_FOLLOW/video
                // scope. No Community page DTO or second TodayWatch algorithm is introduced.
                val originalFollow = DesktopOriginalDynamicTimelineRepository(
                    { type, offset, baseline -> ownedDynamic.getDynamicFeed(type, offset, baseline) }, gate::owns)
                val follow = object : DesktopHomeFollowRequests {
                    override fun currentUpdateBaseline(scope: DynamicFeedScope, type: String) =
                        originalFollow.currentUpdateBaseline(scope, type)
                    override suspend fun getDynamicFeed(refresh: Boolean, scope: DynamicFeedScope,
                        type: String, incrementalRefresh: Boolean) =
                        originalFollow.getDynamicFeed(refresh, scope, type, incrementalRefresh)
                    override fun hasMoreData(scope: DynamicFeedScope, type: String) = originalFollow.hasMoreData(scope, type)
                    override fun syncPaginationAfterRefresh(scope: DynamicFeedScope, type: String,
                        offset: String, hasMore: Boolean) = originalFollow.syncPaginationAfterRefresh(scope, type, offset, hasMore = hasMore)
                }
                val refreshTip = homeSettings.homeSettings.map { it.homeRefreshTipVisible }
                    .stateIn(gate.scope, SharingStarted.Eagerly, homeSettings.homeSettings.value.homeRefreshTipVisible)
                val environment = DesktopHomeDataEnvironment(capturedEpoch, gate.scope, gate::owns, gate::commit,
                    { gate.assertOwned(); capturedMid != null && capturedMid > 0L },
                    privacyModeEnabledSync, identityAnalyticsFactory(gate), globalContext,
                    DesktopHomeFollowingCache(globalContext.store, gate::commit), incrementalTimelineRefresh, refreshTip,
                    requests.ports.video, requests.ports.history, requests.ports.live, requests.ports.messages,
                    requests.ports.actions, follow, desktopHomeBlockedPort(actualBlockedRepository, repository, gate, requests),
                    requests.ports.following, { message -> gate.commit { feedback(message) } })
                val vm = DesktopOriginalHomeViewModel(environment)
                homeVm = vm
                val embedded = embeddedPagesFactory(gate, requests)
                pages = embedded
                if (!gate.owns()) { vm.close(); throw CancellationException("Home owner retired during construction") }
                return DesktopHomeRetainedEntry(gate, requests, vm, embedded)
            } catch (failure: Throwable) {
                gate.close()
                for (cleanup in listOf<() -> Unit>({ pages?.close() }, { binding?.close() }, { homeVm?.close() })) {
                    try { cleanup() } catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
                }
                throw failure
            }
        }
    }
}
