package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopTodayWatchRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/** Window-level retained projection of the ONE bridge owner. Not keyed by Home drawing,
 * section, MID alone, source video or modal state. Root closes it before Runtime/store freeze.
 */
internal class DesktopHomeRootRetainer(
    private val recommendations: DesktopTodayWatchRepository,
    private val currentEpoch: () -> Long,
    private val rootAlive: () -> Boolean,
) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val mutableRoot = MutableStateFlow<DesktopHomeRetainedRoot?>(null)
    val root: StateFlow<DesktopHomeRetainedRoot?> = mutableRoot.asStateFlow()
    fun isActive() = !closed.get() && rootAlive()
    fun current() = mutableRoot.value?.takeIf {
        isActive() && it.capturedEpoch == currentEpoch() && it.isCurrentOwner()
    }
    suspend fun install(epoch: Long, mid: Long?, factory: DesktopHomeRootFactory): DesktopHomeRetainedRoot {
        currentCoroutineContext().ensureActive()
        if (!isActive() || epoch != currentEpoch()) throw CancellationException("Home Root retired before install")
        val owner = recommendations.installOwner(epoch) {
            if (!isActive() || epoch != currentEpoch()) throw CancellationException("Home Root retired before construction")
            factory.create(epoch, mid) { isActive() && currentEpoch() == epoch }
        }
        val result = owner as? DesktopHomeRetainedRoot
            ?: error("Runtime must bind the same complete retained Home assembly")
        currentCoroutineContext().ensureActive()
        val accepted = result.entry.requests.withMountedPublication {
            synchronized(lock) {
                if (isActive() && epoch == currentEpoch()) mutableRoot.value = result
            }
        }
        if (!accepted || current() !== result) {
            withContext(NonCancellable) { result.closeAndJoin() }
            throw CancellationException("Home Root retired before publication")
        }
        return result
    }
    /** Synchronous admission retirement only; the actual owner remains available for drain. */
    fun retire() { closed.set(true) }
    override fun close() {
        closed.set(true)
        val old = synchronized(lock) { mutableRoot.value.also { mutableRoot.value = null } }
        old?.close()
    }
    suspend fun closeAndJoin() = withContext(NonCancellable) {
        closed.set(true)
        val old = synchronized(lock) { mutableRoot.value.also { mutableRoot.value = null } }
        old?.closeAndJoin() // Outside projection/Store monitors. Pending factory is also closed by Runtime.
    }
}
