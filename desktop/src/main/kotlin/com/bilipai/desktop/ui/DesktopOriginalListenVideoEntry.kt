package com.bilipai.desktop.ui

import com.android.purebilibili.feature.audio.library.DesktopOriginalListenVideoLibraryDataSource
import com.android.purebilibili.feature.audio.viewmodel.ListenVideoViewModel
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** One retained original ListenVideo VM, child of the already mounted personal
 * lists Root. The original Loader inside that VM owns its original in-memory
 * preview/index caches. This adapter creates no transport, playlist or store. */
internal class DesktopOriginalListenVideoEntry(
    private val root: DesktopPersonalListsRoot,
    val playlist: DesktopOriginalVideoPlaylistBinding,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob(root.scope.coroutineContext[Job])
    val scope = CoroutineScope(root.scope.coroutineContext + job)
    lateinit var environment: DesktopOriginalListenVideoEnvironment; private set
    lateinit var viewModel: ListenVideoViewModel; private set
    fun owns() = !closed.get() && job.isActive && root.owns()
    fun assertOwned() { if (!owns()) throw CancellationException("ListenVideo entry retired") }
    fun commit(block: () -> Unit): Boolean {
        var applied = false
        root.gate.commit { if (owns()) { block(); applied = true } }
        return applied
    }
    fun install(favorites: DesktopFavoriteEnvironment) {
        assertOwned(); check(!this::viewModel.isInitialized)
        environment = DesktopOriginalListenVideoEnvironment(this, favorites)
        viewModel = ListenVideoViewModel(environment)
    }
    override fun close() { if (closed.compareAndSet(false, true)) job.cancel() }
}

/** The required FavoriteEnvironment is the existing Root factory's same
 * ownedHomeService/epoch graph. Only original read protocols are used here. */
internal class DesktopOriginalListenVideoEnvironment(
    private val entry: DesktopOriginalListenVideoEntry,
    private val favorites: DesktopFavoriteEnvironment,
) {
    val scope get() = entry.scope
    val dataSource = DesktopOriginalListenVideoLibraryDataSource(favorites)
    fun assertOwned() { entry.assertOwned(); favorites.assertOwned() }
    fun currentMid(): Long { assertOwned(); return favorites.currentMid() ?: 0L }
    fun commit(block: () -> Unit) {
        assertOwned()
        if (!entry.commit { assertOwned(); block() })
            throw CancellationException("ListenVideo publication retired")
    }
    fun <T> setState(state: MutableStateFlow<T>, value: T) = commit { state.value = value }
    fun <T> updateState(state: MutableStateFlow<T>, transform: (T) -> T) = commit { state.update(transform) }
    fun request(context: CoroutineContext): DesktopOriginalListenVideoRequest {
        assertOwned()
        return DesktopOriginalListenVideoRequest(this, checkNotNull(context[Job]))
    }
}

/** Captures the actual launched original VM Job, rather than a later request
 * or the currently visible page. Covering Listen keeps the VM; cancel/retry or
 * account/restore/window retirement prevents its late private/UI publication. */
internal class DesktopOriginalListenVideoRequest(
    private val environment: DesktopOriginalListenVideoEnvironment,
    private val caller: Job,
) {
    fun check() { caller.ensureActive(); environment.assertOwned() }
    fun commit(block: () -> Unit) {
        check()
        environment.commit { check(); block() }
    }
    fun <T> updateState(state: MutableStateFlow<T>, transform: (T) -> T) = commit { state.update(transform) }
}
