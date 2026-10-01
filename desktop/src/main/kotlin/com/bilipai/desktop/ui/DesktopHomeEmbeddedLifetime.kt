package com.bilipai.desktop.ui

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** Transient tasks only: all four original page models descend from the one retained Home gate.
 * Visibility/composition is deliberately absent; covering Home with video never retires a page.
 * The parent alone closes this on actual Home entry/account/restore/window retirement. */
internal class DesktopHomeEmbeddedLifetime(
    parentScope: CoroutineScope,
    private val parentOwns: () -> Boolean,
    private val parentCommit: ((() -> Unit) -> Boolean),
) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + job)
    fun owns(): Boolean = !closed.get() && job.isActive && parentOwns()
    fun assertOwned() { if (!owns()) throw CancellationException("Home embedded pages retired") }
    fun commit(action: () -> Unit): Boolean {
        if (!owns()) return false
        var applied = false
        parentCommit { synchronized(lock) { if (owns()) { action(); applied = true } } }
        return applied
    }
    override fun close() {
        val cancelled = synchronized(lock) { closed.compareAndSet(false, true) }
        if (cancelled) job.cancel() // Never run cancellation callbacks while holding this lock.
    }
    suspend fun closeAndJoin() { close(); withContext(NonCancellable) { job.join() } }
}
