package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.data.model.response.UgcEpisode
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.feature.video.player.PlaylistUiState
import com.android.purebilibili.feature.video.screen.ExternalPlaylistQueueSheetContent
import com.android.purebilibili.feature.video.screen.resolveExternalPlaylistQueueTitle
import com.android.purebilibili.feature.video.ui.components.CollectionSheet
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.*
import java.util.concurrent.atomic.AtomicBoolean

/** Reference identity is required even if a replacement source has the same BV/CID. */
private class CollectionQueueIdentity(private val owner: Any) {
    override fun equals(other: Any?) = other is CollectionQueueIdentity && other.owner === owner
    override fun hashCode() = System.identityHashCode(owner)
}

private val LocalDesktopCollectionNativeWindow = staticCompositionLocalOf { false }

/** Required ordinary-video Root slot. Opening a window does not acquire a new source. */
internal class DesktopWindowsVideoCollectionQueuePresentation(
    val assembly: DesktopOriginalVideoOwnerAssembly,
    val success: VideoPlaybackUiState.Success,
    val sourceOwner: DesktopOriginalVideoAcceptedPublication,
    val showCollection: Boolean,
    val showPlaybackQueue: Boolean,
    val stillOwned: () -> Boolean,
    val dismissCollection: () -> Unit,
    val dismissPlaybackQueue: () -> Unit,
)

/** The original sheet's complete content runs in a bounded native client, above MPV.
 * Other original consumers retain their original AppModalBottomSheet presentation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DesktopWindowsCollectionModalSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    windowInsets: WindowInsets,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalDesktopCollectionNativeWindow.current) {
        AppSurface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxSize(), content = content)
        }
    } else {
        AppModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState,
            windowInsets = windowInsets, content = content)
    }
}

internal data class DesktopWindowsUgcEpisodeSelection(val queue: List<VideoCard>, val selected: VideoCard)

/** Same existing season queue mapping. A part chip retains its exact CID and P index. */
internal fun desktopWindowsUgcEpisodeSelection(
    details: VideoDetails, currentCid: Long, episode: UgcEpisode,
): DesktopWindowsUgcEpisodeSelection? {
    val queue = desktopUgcCollection(details, currentCid = currentCid)?.queue.orEmpty()
    val bvid = discoveryEpisodeBvid(episode)
    val card = queue.firstOrNull { it.bvid == bvid && (episode.cid <= 0 || it.preferredCid == episode.cid) }
        ?: queue.firstOrNull { it.bvid == bvid } ?: return null
    val selected = card.copy(preferredCid = episode.cid.takeIf { it > 0 } ?: card.preferredCid,
        pageIndex = episode.pages.indexOfFirst { it.cid == episode.cid }.coerceAtLeast(0))
    return DesktopWindowsUgcEpisodeSelection(queue.toMutableList().apply { set(indexOf(card), selected) }, selected)
}

/** Root supplies the actual accepted source object, not just BV/CID or a route name.
 * The original intro/tabs/sort/subscription/share/rows/part chips are unchanged. */
@Composable internal fun DesktopWindowsVideoCollectionSheetHost(
    details: VideoDetails,
    currentCid: Long,
    bindings: DesktopCollectionBindings,
    sourceOwner: Any,
    stillOwned: () -> Boolean,
    onPlayQueue: (List<VideoCard>, VideoCard) -> Unit,
    onDismiss: () -> Unit,
) {
    val season = details.raw?.ugc_season ?: return
    key(CollectionQueueIdentity(sourceOwner), bindings, details.bvid, currentCid, season.id) {
        val alive = remember { AtomicBoolean(true) }
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestQueue by rememberUpdatedState(onPlayQueue)
        val latestDismiss by rememberUpdatedState(onDismiss)
        fun owned() = alive.get() && bindings.isOwned() && latestOwned()
        val platform = remember { bindings.forWindow(::owned) }
        fun dismiss() { alive.set(false); latestDismiss() }
        DisposableEffect(alive) { onDispose { alive.set(false) } }
        if (owned()) DesktopWindowsPlayerDialog("视频合集", ::dismiss, preferredHeightDp = 640) {
            CompositionLocalProvider(LocalDesktopCollectionBindings provides platform,
                LocalDesktopCollectionNativeWindow provides true) {
                CollectionSheet(season, details.bvid, currentCid, onDismiss = ::dismiss,
                    onEpisodeClick = { episode ->
                        if (owned()) {
                            desktopWindowsUgcEpisodeSelection(details, currentCid, episode)?.let { selection ->
                                // Admission to the actual Root queue is still the caller's final callback.
                                dismiss()
                                latestQueue(selection.queue, selection.selected)
                            }
                        }
                    })
            }
        }
    }
}

/** Captured view of the ONE original playlist. queueOwner must identify its actual
 * current session; sourceOwner identifies the accepted native source at opening. */
internal class DesktopWindowsVideoQueueSnapshot(
    val queueOwner: Any,
    val sourceOwner: Any,
    val state: PlaylistUiState,
)

/** Run this inside Root's original queue admission, immediately before playAt.
 * Equal-value replacement lists/owners are deliberately rejected (queue ABA). */
internal fun desktopWindowsVideoQueueSelectionIsCurrent(
    expected: DesktopWindowsVideoQueueSnapshot,
    actualQueueOwner: Any,
    actualSourceOwner: Any,
    actualState: PlaylistUiState,
    index: Int,
    item: PlaylistItem,
): Boolean = expected.queueOwner === actualQueueOwner && expected.sourceOwner === actualSourceOwner &&
    expected.state.playlist === actualState.playlist && expected.state.currentIndex == actualState.currentIndex &&
    expected.state.isExternalPlaylist == actualState.isExternalPlaylist &&
    expected.state.externalPlaylistSource == actualState.externalPlaylistSource &&
    index in expected.state.playlist.indices && expected.state.playlist[index] === item &&
    actualState.playlist[index] === item

/** Whole original external queue renderer; no second manager, queue copy, transport
 * or shuffle state is created. Root performs its original playAt/load operation. */
@Composable internal fun DesktopWindowsVideoPlaybackQueueHost(
    snapshot: DesktopWindowsVideoQueueSnapshot,
    stillOwned: () -> Boolean,
    onSelect: (DesktopWindowsVideoQueueSnapshot, Int, PlaylistItem) -> Boolean,
    onDismiss: () -> Unit,
) {
    if (snapshot.state.playlist.isEmpty()) return
    key(CollectionQueueIdentity(snapshot.queueOwner), CollectionQueueIdentity(snapshot.sourceOwner)) {
        val alive = remember { AtomicBoolean(true) }
        val latestOwned by rememberUpdatedState(stillOwned)
        val latestSelect by rememberUpdatedState(onSelect)
        val latestDismiss by rememberUpdatedState(onDismiss)
        fun owned() = alive.get() && latestOwned()
        fun dismiss() { alive.set(false); latestDismiss() }
        DisposableEffect(alive) { onDispose { alive.set(false) } }
        if (owned()) DesktopWindowsPlayerDialog("播放队列", ::dismiss, preferredHeightDp = 560) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val listMaxHeight = (maxHeight - 94.dp).coerceAtLeast(0.dp)
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::dismiss) { Text("关闭播放队列") }
                    }
                    ExternalPlaylistQueueSheetContent(
                        title = resolveExternalPlaylistQueueTitle(snapshot.state.externalPlaylistSource),
                        playlist = snapshot.state.playlist,
                        currentIndex = snapshot.state.currentIndex,
                        listMaxHeight = listMaxHeight,
                        bottomSpacerHeight = 8.dp,
                        onVideoSelected = { index, item ->
                            if (owned() && latestSelect(snapshot, index, item)) dismiss()
                        },
                    )
                }
            }
        }
    }
}
