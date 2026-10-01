package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.home.TodayWatchPlan
import com.bilipai.desktop.ui.DesktopOriginalTodayWatchOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DesktopTodayWatchState(val plan: TodayWatchPlan? = null, val loading: Boolean = false,
    val notice: String? = null, val error: String? = null)

/** Existing public recommendation surface, now only an owner binding/projection. All planner,
 * consumed/history/expanded-cache/refill algorithms live in the ONE original Home VM. */
class DesktopTodayWatchRepository(private val currentEpoch: () -> Long?, parentScope: CoroutineScope) {
    private data class Binding(val owner: DesktopOriginalTodayWatchOwner, val epoch: Long)
    private val lock = Mutex()
    private val scopeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val projectionScope = CoroutineScope(parentScope.coroutineContext + scopeJob)
    private var projection: Job? = null
    private var binding: Binding? = null
    private var waitingEpoch = currentEpoch()
    private var waiting = CompletableDeferred<Binding>()
    private var stopped = false
    private val _state = MutableStateFlow(if (waitingEpoch == null) DesktopTodayWatchState(error = "推荐服务尚未初始化")
        else DesktopTodayWatchState(loading = true, notice = "首页推荐正在初始化"))
    val state: StateFlow<DesktopTodayWatchState> = _state.asStateFlow()

    internal suspend fun bindOwner(owner: DesktopOriginalTodayWatchOwner): Unit = lock.withLock { publishLocked(owner) }

    /** Root keeps this call outside the conditional Home page. Close/join precedes construction,
     * so an epoch replacement cannot run two original planners, even during asynchronous init. */
    internal suspend fun installOwner(epoch: Long, factory: () -> DesktopOriginalTodayWatchOwner): DesktopOriginalTodayWatchOwner = lock.withLock {
        check(!stopped) { "推荐服务已停止" }
        if (epoch != currentEpoch()) throw CancellationException("Home epoch retired before construction")
        binding?.takeIf { it.epoch == epoch && it.owner.isCurrentOwner() }?.let { return@withLock it.owner }
        retireLocked()
        currentCoroutineContext().ensureActive()
        if (epoch != currentEpoch()) throw CancellationException("Home epoch retired during retirement")
        val owner = factory()
        try {
            currentCoroutineContext().ensureActive()
            publishLocked(owner)
            owner
        } catch (failure: Throwable) {
            withContext(NonCancellable) { owner.closeAndJoin() }
            throw failure
        }
    }

    private suspend fun publishLocked(owner: DesktopOriginalTodayWatchOwner) {
        check(!stopped) { "推荐服务已停止" }
        val epoch = currentEpoch() ?: error("推荐服务尚未初始化")
        check(owner.capturedEpoch == epoch && owner.isCurrentOwner()) { "首页来源已失效" }
        val existing = binding
        if (existing?.owner === owner) return
        retireLocked()
        if (waitingEpoch != epoch || waiting.isCompleted) {
            waiting.cancel(CancellationException("Home epoch replaced"))
            waitingEpoch = epoch
            waiting = CompletableDeferred()
        }
        val next = Binding(owner, epoch)
        if (currentEpoch() != epoch || !owner.isCurrentOwner())
            throw CancellationException("Home epoch replaced during retirement")
        binding = next
        _state.value = owner.todayWatchState.value
        projection = projectionScope.launch {
            owner.todayWatchState.collect { value ->
                lock.withLock {
                    if (!stopped && binding === next && currentEpoch() == next.epoch && owner.isCurrentOwner())
                        _state.value = value
                }
            }
        }
        waiting.complete(next)
    }

    private suspend fun awaitOwner(): Binding {
        while (true) {
            val pair = lock.withLock {
                check(!stopped) { "推荐服务已停止" }
                val epoch = currentEpoch() ?: error("推荐服务尚未初始化")
                if (waitingEpoch != epoch) {
                    retireLocked()
                    waiting.cancel(CancellationException("Home epoch replaced"))
                    waitingEpoch = epoch
                    waiting = CompletableDeferred()
                }
                val current = binding
                if (current != null && (current.epoch != epoch || !current.owner.isCurrentOwner())) {
                    retireLocked()
                    waiting.cancel(CancellationException("Home owner retired"))
                    waiting = CompletableDeferred()
                    _state.value = DesktopTodayWatchState(loading = true, notice = "首页推荐正在初始化")
                }
                binding?.takeIf { it.epoch == epoch && it.owner.isCurrentOwner() } to waiting
            }
            pair.first?.let { return it }
            try { pair.second.await() }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                lock.withLock { if (stopped) throw cancelled }
            }
        }
    }

    suspend fun reload(forceHistory: Boolean = false) {
        val selected = awaitOwner()
        selected.owner.reloadTodayWatch(forceHistory)
        ensureCurrent(selected)
    }

    suspend fun consume(bvid: String): Boolean {
        val selected = awaitOwner()
        val result = selected.owner.consumeTodayWatchBvid(bvid)
        ensureCurrent(selected)
        return result
    }

    private suspend fun ensureCurrent(selected: Binding) = lock.withLock {
        if (stopped || binding !== selected || currentEpoch() != selected.epoch || !selected.owner.isCurrentOwner())
            throw CancellationException("Home owner retired")
    }

    private suspend fun retireLocked() {
        val old = binding
        binding = null
        projection?.cancel() // It can be waiting for this lock; do not join under the lock.
        projection = null
        if (old != null) withContext(NonCancellable) { old.owner.closeAndJoin() }
    }

    internal suspend fun accountChanged(epoch: Long): Unit = lock.withLock {
        if (stopped || epoch != currentEpoch() || epoch == waitingEpoch) return@withLock
        retireLocked()
        waiting.cancel(CancellationException("Home epoch replaced"))
        waitingEpoch = epoch
        waiting = CompletableDeferred()
        _state.value = DesktopTodayWatchState(loading = true, notice = "首页推荐正在初始化")
    }

    internal suspend fun shutdownForRestore(): Unit = withContext(NonCancellable) {
        lock.withLock {
            if (stopped) return@withLock
            stopped = true
            waiting.cancel(CancellationException("Home runtime stopped"))
            retireLocked()
            _state.value = _state.value.copy(loading = false)
            scopeJob.cancel()
        }
        scopeJob.join()
    }
}
