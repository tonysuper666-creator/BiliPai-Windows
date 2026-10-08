package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.feature.audio.library.resolveListenVideoPlaybackSelection
import com.android.purebilibili.feature.audio.screen.ListenVideoRoute
import com.android.purebilibili.feature.video.player.ExternalPlaylistSource
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Actual composition caller/active page only. It owns no navigation stack or
 * VM lifetime. MainHost Listen is a physical pager page, not an Inbox-style
 * independently pushed entry; disposing its composition retires UI callbacks. */
internal class DesktopOriginalListenVideoPage(
    val entry: DesktopOriginalListenVideoEntry,
    private val routes: DesktopOriginalRootRouteAssembly,
    private val caller: Job,
    private val active: () -> Boolean,
    private val pagerHosted: Boolean,
) {
    val playlist get() = entry.playlist
    fun isActive() = active()
    fun isCurrentUi(): Boolean = caller.isActive && active() && entry.owns() && routes.owns() &&
        routes.currentKey == (if (pagerHosted) BiliPaiNavKey.MainHost else BiliPaiNavKey.ListenVideo)
    fun runUi(block: () -> Unit) {
        if (!isCurrentUi()) return
        // Real playlist/navigation and VM publications perform their own short
        // admission. Never call navigation/cancel/I/O under the entry gate.
        try { block() } catch (_: CancellationException) { /* This UI caller/Root retired. */ }
    }
}

internal val LocalDesktopOriginalListenVideoPage = staticCompositionLocalOf<DesktopOriginalListenVideoPage> {
    error("Original ListenVideo requires its actual retained Root page")
}

@Composable internal fun DesktopOriginalListenVideoHost(
    entry: DesktopOriginalListenVideoEntry,
    routes: DesktopOriginalRootRouteAssembly,
    active: Boolean,
    pagerHosted: Boolean,
) {
    if (!entry.owns()) return
    val pageScope = rememberCoroutineScope()
    val latestActive by rememberUpdatedState(active)
    val page = remember(entry, routes, pageScope, pagerHosted) {
        DesktopOriginalListenVideoPage(entry, routes, checkNotNull(pageScope.coroutineContext[Job]),
            { latestActive }, pagerHosted)
    }
    CompositionLocalProvider(LocalDesktopOriginalListenVideoPage provides page) {
        // AppNavigation ListenVideo callbacks retain original selection/source
        // and CID semantics. The existing Root playlist and AudioMode leaf are
        // the authorities; no legacy ListenAudioSession.play is invoked.
        ListenVideoRoute(
            onNowPlayingClick = { bvid, _ -> page.runUi {
                routes.push(BiliPaiNavKey.AudioMode(sourceBvid = bvid))
            } },
            onPlayTracks = { tracks, clickedBvid -> page.runUi {
                val selection = resolveListenVideoPlaybackSelection(tracks, clickedBvid)
                if (selection.items.isNotEmpty() && selection.startIndex >= 0) {
                    entry.playlist.setExternalPlaylist(selection.items, selection.startIndex, ExternalPlaylistSource.FAVORITE)
                    val clickedTrack = tracks.firstOrNull { it.bvid == clickedBvid }
                    if (page.isCurrentUi()) routes.push(BiliPaiNavKey.AudioMode(
                        sourceBvid = clickedBvid, sourceCid = clickedTrack?.cid ?: 0))
                }
            } },
            onLogin = { page.runUi { routes.push(BiliPaiNavKey.Login) } },
            viewModel = entry.viewModel,
        )
    }
}
