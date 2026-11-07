package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.feature.audio.screen.ListenVideoCollectionPlayer
import com.android.purebilibili.feature.audio.viewmodel.ListenVideoCollectionViewModel
import com.android.purebilibili.navigation3.BiliPaiNavKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

private class CollectionKeyReference(private val key: BiliPaiNavKey.AudioMode) {
    override fun equals(other: Any?) = other is CollectionKeyReference && other.key === key
    override fun hashCode() = System.identityHashCode(key)
}

/** The collection page is the upstream ListenVideoCollectionPlayer over this destination's retained VM: its own loading page, its own
 * error page with the retry button, and the player slot only once the first track is there. Opening the collection replaces the Root
 * queue, so a destination that was never in front must not do it: the page is first composed when the destination first becomes active
 * and stays composed after that (covering it keeps the loader and the player slot as they were). */
@Composable internal fun DesktopOriginalListenVideoCollectionSurface(
    viewModel: ListenVideoCollectionViewModel,
    mediaId: Long,
    seasonId: Long,
    title: String,
    active: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shown = remember(viewModel) { mutableStateOf(active) }
    LaunchedEffect(active) { if (active) shown.value = true }
    if (shown.value) {
        ListenVideoCollectionPlayer(mediaId = mediaId, seasonId = seasonId, title = title, onBack = onBack,
            viewModel = viewModel) { _, _ -> content() }
    }
}

/** The original collection UI receives its actual retained destination VM and current composition callbacks. */
@Composable internal fun DesktopOriginalListenVideoCollectionHost(
    key: BiliPaiNavKey.AudioMode,
    root: DesktopPersonalListsRoot,
    playlist: DesktopOriginalVideoPlaylistBinding,
    window: DesktopOriginalVideoRootWindowEnvironment,
    active: Boolean,
    onBack: () -> Unit,
    content: @Composable (DesktopOriginalListenVideoCollectionEntry, () -> Unit) -> Unit,
) {
    val entry = remember(root, playlist, CollectionKeyReference(key), window) {
        try {
            root.listenCollection(key, playlist) {
                window.owns() && (window.commands as? DesktopOriginalRootRouteAssembly)?.stack?.any { it === key } == true
            }
        } catch (_: CancellationException) { null }
    } ?: return
    if (!entry.owns()) return
    val scope = rememberCoroutineScope()
    val caller = checkNotNull(scope.coroutineContext[Job])
    val latestActive by rememberUpdatedState(active)
    fun runUi(action: () -> Unit) {
        if (!caller.isActive || !latestActive || !entry.owns() || !window.owns() || window.currentKey() !== key) return
        try { action() } catch (_: CancellationException) { /* Actual destination/Root retired. */ }
    }
    val back = { runUi(onBack) }
    DesktopOriginalListenVideoCollectionSurface(entry.viewModel, key.collectionMediaId, key.collectionSeasonId,
        key.collectionTitle, active, back) { content(entry, back) }
}

@Composable internal fun DesktopOriginalListenCollectionQueueChanged(onBack: () -> Unit) {
    AppSurface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppText("播放队列已切换", style = MaterialTheme.typography.titleMedium)
                AppText("返回后重新打开这个列表", style = MaterialTheme.typography.bodyMedium)
                AppTextButton(onClick = onBack) { AppText("返回") }
            }
        }
    }
}
