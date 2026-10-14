package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.player.PlaylistSession
import com.android.purebilibili.feature.audio.viewmodel.ListenVideoCollectionViewModel
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.CoroutineContext

/** One real retained AudioMode destination. Covering its composition leaves the
 * original Loader running; popping this exact key, account restore or Root close
 * cancels the child scope. No second playlist, source or preference store exists. */
internal class DesktopOriginalListenVideoCollectionEntry(
    val root: DesktopPersonalListsRoot,
    val key: BiliPaiNavKey.AudioMode,
    val listen: DesktopOriginalListenVideoEntry,
    private val destinationRetained: () -> Boolean,
) : AutoCloseable {
    private val job = SupervisorJob(checkNotNull(root.scope.coroutineContext[Job]))
    val scope = CoroutineScope(root.scope.coroutineContext + job)
    val environment = DesktopOriginalListenVideoCollectionEnvironment(listen, root, key, scope, ::owns)
    val viewModel = ListenVideoCollectionViewModel(environment)
    fun owns(): Boolean = job.isActive && root.owns() && listen.owns() && destinationRetained()
    override fun close() { job.cancel() }
}

/** Original collection pagination borrows the existing Listen/Favorites source
 * and the sole Root playlist. Its scope and retention predicate must come from
 * the actual AudioMode destination; this class creates no scope, cache or player. */
internal class DesktopOriginalListenVideoCollectionEnvironment(
    private val entry: DesktopOriginalListenVideoEntry,
    val root: DesktopPersonalListsRoot,
    val destination: BiliPaiNavKey.AudioMode,
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
    fun capturePlaybackTarget(session: PlaylistSession, item: PlaylistItem,
        originalPublicationCurrent: () -> Boolean): DesktopOriginalListenCollectionPlaybackTarget? {
        assertOwned()
        return DesktopOriginalListenCollectionPlaybackTarget.capture(this, session, item, originalPublicationCurrent)
    }
}

/** Immutable selected item from the original collection VM's SAME session.
 * It only admits the initial request for this actual typed destination. Later
 * native ACKs, retries and automatic successors retain their existing authorities. */
internal class DesktopOriginalListenCollectionPlaybackTarget private constructor(
    private val environment: DesktopOriginalListenVideoCollectionEnvironment,
    private val selection: DesktopOriginalListenCollectionQueueSelection,
) {
    val bvid: String get() = selection.bvid
    val cid: Long get() = selection.cid
    fun isCurrent(window: DesktopOriginalVideoRootWindowEnvironment, route: BiliPaiNavKey.AudioMode): Boolean =
        window.root.entry.gate === environment.root.gate && route === environment.destination &&
            window.owns() && selection.isCurrent()
    fun matches(window: DesktopOriginalVideoRootWindowEnvironment, route: BiliPaiNavKey.AudioMode,
        request: PlaybackRequest, fallbackResumeMs: Long): Boolean = isCurrent(window, route) &&
        selection.matches(request, fallbackResumeMs)
    companion object {
        internal fun capture(environment: DesktopOriginalListenVideoCollectionEnvironment,
            session: PlaylistSession, item: PlaylistItem,
            originalPublicationCurrent: () -> Boolean): DesktopOriginalListenCollectionPlaybackTarget? {
            val key = environment.destination
            if ((key.collectionMediaId <= 0L && key.collectionSeasonId <= 0L) ||
                key.sourceBvid.isNotBlank() || key.sourceCid != 0L || key.sourceResumePositionMs != 0L) return null
            val selection = DesktopOriginalListenCollectionQueueSelection.capture(environment.playlist,
                session, item, environment::owns, originalPublicationCurrent) ?: return null
            return DesktopOriginalListenCollectionPlaybackTarget(environment, selection)
        }
    }
}

/** A read-only selection receipt over the real original queue. Appending pages
 * preserves it; changing selection, resolving CID, replacing the session or
 * retiring its original VM publication makes a fresh capture necessary. */
internal class DesktopOriginalListenCollectionQueueSelection private constructor(
    private val playlist: DesktopOriginalVideoPlaylistBinding,
    private val session: PlaylistSession,
    private val item: PlaylistItem,
    private val ownerCurrent: () -> Boolean,
    private val originalPublicationCurrent: () -> Boolean,
) {
    val bvid: String get() = item.bvid
    val cid: Long get() = item.cid
    fun isCurrent(): Boolean = try {
        ownerCurrent() && originalPublicationCurrent() && playlist.isSessionCurrent(session) &&
            playlist.getCurrentItem() === item
    } catch (_: CancellationException) { false }
    fun matches(request: PlaybackRequest, resumeMs: Long): Boolean = isCurrent() &&
        request.bvid == bvid && request.cid == cid && request.aid == 0L && resumeMs == 0L
    companion object {
        internal fun capture(playlist: DesktopOriginalVideoPlaylistBinding, session: PlaylistSession,
            item: PlaylistItem, ownerCurrent: () -> Boolean,
            originalPublicationCurrent: () -> Boolean): DesktopOriginalListenCollectionQueueSelection? =
            DesktopOriginalListenCollectionQueueSelection(playlist, session, item, ownerCurrent,
                originalPublicationCurrent).takeIf { it.isCurrent() }
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
