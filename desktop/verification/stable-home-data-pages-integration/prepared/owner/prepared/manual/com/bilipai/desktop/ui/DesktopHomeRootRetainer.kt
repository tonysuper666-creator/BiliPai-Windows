package com.bilipai.desktop.ui

import com.bilipai.desktop.plugins.DesktopTodayWatchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Window-level binding only. No feed/planner/account/cache algorithm lives here.
 * Root remembers this outside section/video conditionals. The actual Runtime bridge serializes
 * retirement and construction of its ONE original Home owner. */
internal class DesktopHomeRootRetainer(
    private val recommendations: DesktopTodayWatchRepository,
    private val currentEpoch: () -> Long,
    private val rootAlive: () -> Boolean,
) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val _entry = MutableStateFlow<DesktopHomeRetainedEntry?>(null)
    val entry: StateFlow<DesktopHomeRetainedEntry?> = _entry.asStateFlow()
    fun isActive(): Boolean = !closed.get() && rootAlive()
    fun current(): DesktopHomeRetainedEntry? = _entry.value?.takeIf {
        isActive() && it.capturedEpoch == currentEpoch() && it.isCurrentOwner()
    }

    /** Pass immutable epoch/MID snapshots from Root's epoch effect to the real factory.
     * stillOwned is required in the factory's rootAlive gate; otherwise close could race init. */
    suspend fun install(
        capturedEpoch: Long,
        factory: (stillOwned: () -> Boolean) -> DesktopHomeRetainedEntry,
    ): DesktopHomeRetainedEntry {
        currentCoroutineContext().ensureActive()
        if (!isActive() || capturedEpoch != currentEpoch())
            throw CancellationException("Home Root retired before installation")
        val owner = recommendations.installOwner(capturedEpoch) {
            if (!isActive() || capturedEpoch != currentEpoch())
                throw CancellationException("Home Root retired before construction")
            factory { isActive() && currentEpoch() == capturedEpoch }
        }
        val selected = owner as? DesktopHomeRetainedEntry
            ?: error("Root Runtime must bind the same retained original Home entry")
        currentCoroutineContext().ensureActive()
        val published = selected.gate.commit {
            synchronized(lock) {
                if (isActive() && selected.capturedEpoch == currentEpoch()) _entry.value = selected
            }
        }
        if (!published || current() !== selected) {
            selected.close()
            throw CancellationException("Home Root retired before publication")
        }
        return selected
    }

    override fun close() {
        // Never take SessionStore or entry locks while holding this projection lock.
        closed.set(true)
        val old = synchronized(lock) { _entry.value.also { _entry.value = null } }
        old?.close()
    }

    suspend fun closeAndJoin() = withContext(NonCancellable) {
        closed.set(true)
        val old = synchronized(lock) { _entry.value.also { _entry.value = null } }
        old?.closeAndJoin()
    }
}
