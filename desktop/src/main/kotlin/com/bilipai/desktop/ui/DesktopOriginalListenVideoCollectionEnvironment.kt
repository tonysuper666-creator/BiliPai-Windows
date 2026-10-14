package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.player.PlaylistSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.CoroutineContext

/** Original collection pagination borrows the existing Listen/Favorites source
 * and the sole Root playlist. Its scope and retention predicate must come from
 * the actual AudioMode destination; this class creates no scope, cache or player. */
internal class DesktopOriginalListenVideoCollectionEnvironment(
    private val entry: DesktopOriginalListenVideoEntry,
    val scope: CoroutineScope,
    private val destinationRetained: () -> Boolean,
) {
    private val destinationJob = checkNotNull(scope.coroutineContext[Job])
    val dataSource get() = entry.environment.dataSource
    internal val playlist get() = entry.playlist
    fun owns(): Boolean = destinationJob.isActive && destinationRetained() && entry.owns()
    fun assertOwned() {
        destinationJob.ensureActive()
        if (!owns()) throw CancellationException("Original collection destination retired")
        entry.environment.assertOwned()
    }
    fun commit(block: () -> Unit) {
        assertOwned()
        if (!entry.commit { assertOwned(); block() })
            throw CancellationException("Original collection publication retired")
    }
    fun <T> setState(state: MutableStateFlow<T>, value: T) = commit { state.value = value }
    fun <T> updateState(state: MutableStateFlow<T>, transform: (T) -> T) = commit { state.update(transform) }
    fun request(context: CoroutineContext): DesktopOriginalListenVideoCollectionRequest {
        assertOwned()
        return DesktopOriginalListenVideoCollectionRequest(this, checkNotNull(context[Job]))
    }
}

/** The actual original load Job, rather than a later retry or visible page.
 * Destination/caller admission is also checked inside the existing playlist's
 * Root mutation, so a cancelled collection cannot replace or append another queue. */
internal class DesktopOriginalListenVideoCollectionRequest(
    private val environment: DesktopOriginalListenVideoCollectionEnvironment,
    private val caller: Job,
) {
    fun owns(): Boolean = caller.isActive && environment.owns()
    fun check() { caller.ensureActive(); environment.assertOwned() }
    fun commit(block: () -> Unit) {
        check()
        environment.commit { check(); block() }
    }
    fun <T> read(block: () -> T): T {
        var value: Any? = null
        commit { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    fun <T> updateState(state: MutableStateFlow<T>, transform: (T) -> T) = commit { state.update(transform) }
    fun setExternalPlaylist(items: List<PlaylistItem>, index: Int, source: ExternalPlaylistSource): PlaylistSession {
        check()
        return environment.playlist.setExternalPlaylistIfCallerCurrent(items, index, source, ::owns)
    }
    fun appendPlaylist(items: List<PlaylistItem>, session: PlaylistSession): Boolean {
        check()
        return environment.playlist.addAllToPlaylistIfCallerCurrent(items, session, ::owns)
    }
}
