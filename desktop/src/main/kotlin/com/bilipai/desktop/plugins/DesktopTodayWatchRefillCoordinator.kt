package com.bilipai.desktop.plugins

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Merge rapid queue opens into one pending rebuild; account changes and restore cancel ownership. */
internal class DesktopTodayWatchRefillCoordinator(private val scope: CoroutineScope,
    private val currentEpoch: () -> Long?, private val rebuild: suspend () -> Unit) {
    private val mutex = Mutex()
    private var pendingEpoch: Long? = null
    private var pending = false
    private var job: Job? = null
    @Volatile private var stopped = false

    suspend fun request(epoch: Long) = mutex.withLock {
        if (stopped || epoch != currentEpoch()) return@withLock
        pendingEpoch = epoch; pending = true
        if (job == null) job = scope.launch(start = CoroutineStart.LAZY) {
            val owned = currentCoroutineContext()[Job]
            try {
                while (mutex.withLock {
                    if (stopped || !pending || pendingEpoch != currentEpoch()) {
                        if (job === owned) job = null
                        false
                    } else { pending = false; true }
                }) rebuild()
            } finally {
                withContext(NonCancellable) { mutex.withLock { if (job === owned) job = null } }
            }
        }.also { it.start() }
    }
    suspend fun clear() = mutex.withLock { pending = false; pendingEpoch = null; job?.cancel(); job = null }
    suspend fun shutdownForRestore() {
        stopped = true
        scope.coroutineContext[Job]?.cancelAndJoin()
        mutex.withLock { pending = false; pendingEpoch = null; job = null }
    }
}
