package com.bilipai.desktop.plugins

import com.android.purebilibili.feature.home.TodayWatchPlan
import com.bilipai.desktop.ui.DesktopOriginalTodayWatchOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DesktopTodayWatchState(val plan: TodayWatchPlan? = null, val loading: Boolean = false,
    val notice: String? = null, val error: String? = null)

/** Existing public facade over ONE original Home VM. This owns no history/candidates/plan
 * algorithm/consumed/cache/refill. Serialized lifetime commands join owners with NO monitor
 * or Mutex held. Projection references are only references to the same owner's state.
 */
class DesktopTodayWatchRepository(private val currentEpoch: () -> Long?, parentScope: CoroutineScope) {
    private data class Binding(val owner: DesktopOriginalTodayWatchOwner, val epoch: Long)
    private sealed interface Command {
        data class Install(val epoch: Long, val factory: suspend () -> DesktopOriginalTodayWatchOwner,
            val reply: CompletableDeferred<DesktopOriginalTodayWatchOwner>) : Command
        data class Account(val epoch: Long, val reply: CompletableDeferred<Unit>) : Command
    }
    private val stopped = AtomicBoolean(false)
    private val binding = AtomicReference<Binding?>()
    private val waitLock = Any()
    private var waiting = CompletableDeferred<Binding>()
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var projection: Job? = null // worker-owned, cancelled/joined outside all monitors.
    private val _state = MutableStateFlow(DesktopTodayWatchState(loading = currentEpoch() != null,
        notice = if (currentEpoch() != null) "首页推荐正在初始化" else null,
        error = if (currentEpoch() == null) "推荐数据源未初始化" else null))
    val state: StateFlow<DesktopTodayWatchState> = _state.asStateFlow()
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            for (command in commands) {
                when (command) {
                    is Command.Install -> try {
                        if (stopped.get()) throw CancellationException("Home runtime stopped")
                        command.reply.ensureActive()
                        if (command.epoch != currentEpoch()) throw CancellationException("Home epoch retired before construction")
                        val current = binding.get()
                        if (current != null && current.epoch == command.epoch && current.owner.isCurrentOwner()) {
                            command.reply.complete(current.owner)
                            continue
                        }
                        retire()
                        currentCoroutineContext().ensureActive()
                        command.reply.ensureActive()
                        if (command.epoch != currentEpoch()) throw CancellationException("Home epoch retired during drain")
                        val owner = command.factory()
                        try {
                            command.reply.ensureActive()
                            currentCoroutineContext().ensureActive()
                            if (owner.capturedEpoch != command.epoch || currentEpoch() != command.epoch || !owner.isCurrentOwner())
                                throw CancellationException("Home owner retired during construction")
                            val next = Binding(owner, command.epoch)
                            resetWait(command.epoch)
                            binding.set(next)
                            _state.value = owner.todayWatchState.value
                            projection = scope.launch {
                                owner.todayWatchState.collect { state ->
                                    if (!stopped.get() && binding.get() === next && currentEpoch() == next.epoch && owner.isCurrentOwner())
                                        _state.value = state
                                }
                            }
                            synchronized(waitLock) { waiting.complete(next) }
                            command.reply.complete(owner)
                        } catch (failure: Throwable) {
                            withContext(NonCancellable) { owner.closeAndJoin() }
                            throw failure
                        }
                    } catch (failure: Throwable) {
                        if (failure !is CancellationException && !stopped.get() && currentEpoch() == command.epoch)
                            _state.value = DesktopTodayWatchState(error = "首页推荐初始化失败，请重试")
                        command.reply.completeExceptionally(failure)
                        currentCoroutineContext().ensureActive()
                    }
                    is Command.Account -> try {
                        if (command.epoch == currentEpoch()) {
                            val current = binding.get()
                            if (current == null || current.epoch != command.epoch || !current.owner.isCurrentOwner()) {
                                retire()
                                resetWait(command.epoch)
                                _state.value = DesktopTodayWatchState(loading = true, notice = "首页推荐正在初始化")
                            }
                        }
                        command.reply.complete(Unit)
                    } catch (failure: Throwable) {
                        command.reply.completeExceptionally(failure)
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
        } finally {
            stopped.set(true)
            commands.close()
            withContext(NonCancellable) { retire() }
            synchronized(waitLock) { waiting.cancel(CancellationException("Home runtime stopped")) }
            while (true) {
                val pending = commands.tryReceive().getOrNull() ?: break
                when (pending) {
                    is Command.Install -> pending.reply.cancel(CancellationException("Home runtime stopped"))
                    is Command.Account -> pending.reply.cancel(CancellationException("Home runtime stopped"))
                }
            }
            _state.value = _state.value.copy(loading = false)
        }
    }

    private fun resetWait(epoch: Long) = synchronized(waitLock) {
        waiting.cancel(CancellationException("Home owner replaced"))
        waiting = CompletableDeferred()
    }
    private suspend fun retire() {
        val old = binding.getAndSet(null)
        // Drop old-account presentation before a slow owner drain, not only after replacement.
        _state.value = DesktopTodayWatchState(loading = !stopped.get(),
            notice = if (!stopped.get()) "首页推荐正在初始化" else null)
        projection?.cancelAndJoin()
        projection = null
        if (old != null) withContext(NonCancellable) { old.owner.closeAndJoin() }
    }
    internal suspend fun installOwner(epoch: Long, factory: suspend () -> DesktopOriginalTodayWatchOwner): DesktopOriginalTodayWatchOwner {
        currentCoroutineContext().ensureActive()
        check(!stopped.get()) { "推荐服务已停止" }
        val reply = CompletableDeferred<DesktopOriginalTodayWatchOwner>(currentCoroutineContext()[Job])
        if (commands.trySend(Command.Install(epoch, factory, reply)).isFailure)
            throw CancellationException("Home runtime stopped before installation")
        return reply.await()
    }
    private suspend fun awaitOwner(): Binding {
        while (true) {
            currentCoroutineContext().ensureActive()
            check(!stopped.get()) { "推荐服务已停止" }
            val epoch = currentEpoch() ?: error("推荐数据源未初始化")
            binding.get()?.takeIf { it.epoch == epoch && it.owner.isCurrentOwner() }?.let { return it }
            val wait = synchronized(waitLock) { waiting }
            if (wait.isCompleted) {
                accountChanged(epoch)
                continue
            }
            try { wait.await() }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                if (stopped.get()) throw cancelled
            }
        }
    }
    suspend fun reload(forceHistory: Boolean = false) {
        val current = awaitOwner()
        current.owner.reloadTodayWatch(forceHistory)
        ensureCurrent(current)
    }
    suspend fun consume(bvid: String): Boolean {
        val current = awaitOwner()
        val result = current.owner.consumeTodayWatchBvid(bvid)
        ensureCurrent(current)
        return result
    }
    private fun ensureCurrent(current: Binding) {
        if (stopped.get() || binding.get() !== current || currentEpoch() != current.epoch || !current.owner.isCurrentOwner())
            throw CancellationException("Home owner retired")
    }
    internal suspend fun accountChanged(epoch: Long) {
        if (stopped.get()) return
        val reply = CompletableDeferred<Unit>(currentCoroutineContext()[Job])
        if (commands.trySend(Command.Account(epoch, reply)).isFailure) return
        reply.await()
    }
    internal suspend fun shutdownForRestore() = withContext(NonCancellable) {
        stopped.set(true)
        commands.close()
        job.cancel()
        worker.join()
        job.join()
    }
}
