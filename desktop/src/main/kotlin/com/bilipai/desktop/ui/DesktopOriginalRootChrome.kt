package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.navigation3.*
import kotlinx.coroutines.CoroutineScope

internal class DesktopOriginalRootChromeBindings(
    val owner:DesktopOriginalLinkedDockOwner,
    val preferences:DesktopOriginalRootNavigationPreferences,
    val scope:CoroutineScope,
    val actions:DesktopOriginalFrostedNavigationActions,
    val audio:DesktopOriginalAudioNowPlayingBinding?,
    val nowPlayingVisibility:()->DesktopOriginalNowPlayingVisibility,
    val nowPlayingNavigation:()->DesktopOriginalNowPlayingNavigation,
    val navigationBarsBottom:()->Dp,
    val dynamicUnreadCount:()->Int,
    val searchLaunchKey:()->Int,
    val cardTransitionEnabled:()->Boolean,
    val accountSwitcher:(()->Unit)?,
    val onSourceReady:(Boolean)->Unit,
)

/** Windows chrome uses a fixed sidebar and a separate audio row. No overlay dock,
 * captured page layer, phone navigation gesture or screen-width animation is mounted. */
@Composable internal fun DesktopOriginalRootChrome(
    routes: DesktopOriginalRootRouteAssembly,
    pages: DesktopOriginalRootPageBindings,
    binding: DesktopOriginalRootChromeBindings,
    currentItem: BottomNavItem,
    visibleItems: List<BottomNavItem>,
    onItemClick: (BottomNavItem) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!routes.owns() || !binding.owner.isOwned()) return
    val key = routes.currentKey
    val video = key is BiliPaiNavKey.VideoDetail || key is BiliPaiNavKey.AudioMode ||
        key is BiliPaiNavKey.BangumiPlayer || key is BiliPaiNavKey.OfflineVideoPlayer
    val audio = binding.audio
    val snapshot = audio?.observeSnapshot()
    val visibility = binding.nowPlayingVisibility()
    val audioVisible = !video && audio != null && snapshot != null && audio.ownsSnapshot(snapshot) &&
        snapshot.active && visibility.sessionActive && !visibility.inPipMode
    val navigation = binding.nowPlayingNavigation()
    SideEffect {
        binding.onSourceReady(false) // The desktop layout never needs a captured phone transition layer.
        audio?.publishBarOverlayVisible(audioVisible)
    }
    DisposableEffect(audio) { onDispose { audio?.publishBarOverlayVisible(false) } }
    CompositionLocalProvider(
        LocalBottomBarVisible provides false,
        LocalBottomBarContentPadding provides 0.dp,
        LocalSetBottomBarVisible provides pages.setBottomBarVisible,
        LocalGlobalWallpaperBackdropVisible provides false,
    ) {
        Row(Modifier.fillMaxSize()) {
            if (!video) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.width(172.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("BiliPai", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
                    TextButton(onClick = { if (routes.owns()) binding.actions.searchClick() }, modifier = Modifier.fillMaxWidth()) { Text("搜索") }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    visibleItems.forEach { item ->
                        val selected = key == BiliPaiNavKey.MainHost && item == currentItem || key.toLegacyRoute() == item.route
                        if (selected) FilledTonalButton(onClick = { if (routes.owns()) onItemClick(item) }, modifier = Modifier.fillMaxWidth()) { Text(item.label) }
                        else TextButton(onClick = { if (routes.owns()) onItemClick(item) }, modifier = Modifier.fillMaxWidth()) { Text(item.label) }
                    }
                    TextButton(onClick = { if (routes.owns()) routes.push(BiliPaiNavKey.DownloadList) }, modifier = Modifier.fillMaxWidth()) { Text("下载与离线") }
                    binding.accountSwitcher?.let { switch ->
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        TextButton(onClick = { if (routes.owns()) switch() }, modifier = Modifier.fillMaxWidth()) { Text("切换账号") }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                if (audioVisible && audio != null && snapshot != null) Surface(tonalElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(snapshot.item.title, Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { audio.previous(snapshot) }) { Text("上一首") }
                        Button(onClick = { audio.playPause(snapshot, navigation) }) { Text(if (snapshot.isPlaying) "暂停" else "播放") }
                        TextButton(onClick = { audio.next(snapshot) }) { Text("下一首") }
                        TextButton(onClick = { audio.expand(snapshot, navigation) }) { Text("打开播放器") }
                        TextButton(onClick = { audio.dismiss(snapshot, navigation) }) { Text("关闭") }
                    }
                }
            }
        }
    }
}
