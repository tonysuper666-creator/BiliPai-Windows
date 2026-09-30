package com.bilipai.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.space.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class DesktopSpaceVideoQuery(val order: VideoSortOrder, val categoryId: Int, val keyword: String)
internal class DesktopSpaceVideoBrowserState {
    var order by mutableStateOf(VideoSortOrder.PUBDATE)
    var categoryId by mutableIntStateOf(0)
    var keyword by mutableStateOf("")
    var draft by mutableStateOf("")
    var categories by mutableStateOf(emptyList<SpaceVideoCategory>())
    var layout by mutableStateOf(defaultSpaceContributionVideoLayoutMode())
    val controlsScroll = ScrollState(0)
}
internal class DesktopSpaceVideoPageState {
    val feed = CommunityFeedState<SpaceVideoItem, Int?>()
    val grid = LazyGridState()
    var total by mutableIntStateOf(0)
    var locateRequested by mutableStateOf(false)
    var locateMessage by mutableStateOf<String?>(null)
}

/** Original sort/category semantics, actual grid/list rendering, and a raw playlist callback for Root's player owner. */
@Composable
fun DesktopSpaceVideoBrowser(mid: Long, repository: DesktopRepository, backend: DesktopSpaceContributionsRepository,
    onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onPlaylist: (SpaceExternalPlaylist) -> Unit,
    onLogin: () -> Unit, progressByBvid: Map<String, SpaceWatchProgress> = emptyMap(),
    localPositionMs: (String) -> Long = { 0L }, locateBvid: String? = null) {
    require(mid > 0)
    val account by repository.account.collectAsState(); val epoch by repository.sessionEpochFlow.collectAsState()
    val memory = LocalDesktopBrowseMemory.current
    val owner = listOf("up-space-video-browser", epoch, account?.mid, mid)
    val browser = remember(memory, owner) { memory?.screen(owner) { DesktopSpaceVideoBrowserState() } ?: DesktopSpaceVideoBrowserState() }
    val query = DesktopSpaceVideoQuery(browser.order, browser.categoryId, browser.keyword)
    val page = remember(memory, owner, query) { memory?.screen(owner + query) { DesktopSpaceVideoPageState() } ?: DesktopSpaceVideoPageState() }
    val feed = page.feed; val scope = rememberCoroutineScope()
    var busy by remember(page) { mutableStateOf(false) }
    val generation = remember(page, feed.reloadRevision) { Any() }
    val activeGeneration by rememberUpdatedState(generation)
    val positions by rememberUpdatedState(localPositionMs)
    val progress by rememberUpdatedState(progressByBvid)
    val rows = feed.rows
    fun card(item: SpaceVideoItem) = desktopSpaceVideoCard(item, mid, progress[item.bvid], positions(item.bvid))
    suspend fun fetch(cursor: Int?, replace: Boolean) {
        val request = generation; val revision = feed.reloadRevision
        busy = true
        try {
            val result = backend.videos(mid, cursor, query.order, query.categoryId, query.keyword)
            if (activeGeneration === request && feed.acceptBatch(revision, CommunityBatch(result.items, result.nextPage), replace) { it.bvid.ifBlank { it.aid.toString() } }) {
                page.total = result.data.page.count
                if (query.categoryId == 0 && query.keyword.isBlank() && cursor == null) browser.categories = desktopOriginalSpaceVideoCategories(result.items)
                page.locateMessage = null
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (activeGeneration === request) feed.acceptFailure(revision, failure, cursor, replace)
        } finally { if (activeGeneration === request) busy = false }
    }
    LaunchedEffect(generation) { if (!feed.initialized && feed.failure == null) fetch(null, true) }
    LaunchedEffect(page.locateRequested, rows, busy, feed.failure, feed.next, locateBvid) {
        val target = locateBvid?.takeIf(String::isNotBlank)
        if (!page.locateRequested || target == null) return@LaunchedEffect
        when (val action = resolveSpaceLocateTargetPageAction(target, rows, busy, feed.next != null, feed.failure != null)) {
            is SpaceLocateTargetPageAction.Found -> { page.grid.animateScrollToItem(action.index + 1); page.locateRequested = false; page.locateMessage = "已定位上次播放的视频" }
            SpaceLocateTargetPageAction.LoadMore -> fetch(feed.next, false)
            SpaceLocateTargetPageAction.LoadFailed -> { page.locateRequested = false; page.locateMessage = "加载失败，可重试后继续定位" }
            SpaceLocateTargetPageAction.Missing -> { page.locateRequested = false; page.locateMessage = "当前筛选中没有上次播放的视频" }
            SpaceLocateTargetPageAction.Wait -> Unit
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(browser.controlsScroll).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoSortOrder.entries.forEach { order -> FilterChip(browser.order == order, onClick = { browser.order = order }, label = { Text(order.displayName) }) }
            TextButton(onClick = { browser.layout = toggleSpaceContributionVideoLayoutMode(browser.layout) }) {
                Text(if (browser.layout == SpaceContributionVideoLayoutMode.GRID) "单列" else "网格")
            }
        }
        if (browser.categories.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(remember(browser) { ScrollState(0) }).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(browser.categoryId == 0, onClick = { browser.categoryId = 0 }, label = { Text("全部分区") })
            browser.categories.forEach { category -> FilterChip(browser.categoryId == category.tid, onClick = { browser.categoryId = category.tid }, label = { Text(category.name) }) }
        }
        Row(Modifier.padding(20.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = browser.draft, onValueChange = { browser.draft = it }, singleLine = true, label = { Text("搜索 TA 的视频") }, modifier = Modifier.weight(1f))
            Button(onClick = { browser.keyword = browser.draft.trim() }) { Text("搜索") }
            TextButton(onClick = { browser.draft = ""; browser.keyword = ""; browser.categoryId = 0 }) { Text("全部") }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(260.dp), state = page.grid, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${page.total.takeIf { it > 0 } ?: rows.size} 个视频", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { buildExternalPlaylistFromSpaceVideos(rows)?.let(onPlaylist) }, enabled = rows.isNotEmpty()) { Text("播放全部") }
                        TextButton(onClick = { page.locateRequested = true }, enabled = !locateBvid.isNullOrBlank() && feed.initialized) { Text("定位上次播放") }
                        TextButton(onClick = { feed.invalidate() }, enabled = !busy) { Text("刷新") }
                    }
                    page.locateMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            items(rows, key = { resolveSpaceContributionVideoItemKey(browser.layout, it.bvid, it.aid) },
                span = { GridItemSpan(resolveSpaceContributionVideoGridSpan(browser.layout, maxLineSpan)) }) { video ->
                val videoCard = card(video)
                val progressState = resolveSpaceVideoProgressState(video, positions(video.bvid), progress[video.bvid])
                val badge = resolveSpaceVideoChargeBadgeLabel(video)
                if (browser.layout == SpaceContributionVideoLayoutMode.SINGLE_COLUMN) {
                    CommunityVideoRow(videoCard, onVideo, onUser) {
                        if (badge != null) Text(badge, color = MaterialTheme.colorScheme.primary)
                        if (progressState.showProgressBar) { Text("已播放 ${progressState.progressSec / 60}:${(progressState.progressSec % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall); LinearProgressIndicator(progress = { progressState.progressFraction }, modifier = Modifier.fillMaxWidth()) }
                    }
                } else Card(Modifier.fillMaxWidth().clickable { onVideo(videoCard) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AsyncImage(model = imageUrl(videoCard.cover), contentDescription = videoCard.title, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(videoCard.title, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                            Text(videoCard.author, modifier = Modifier.clickable { onUser(mid) }, style = MaterialTheme.typography.bodySmall)
                            Text("${videoCard.playCount} 次播放 · ${video.length}", style = MaterialTheme.typography.bodySmall)
                            if (badge != null) Text(badge, color = MaterialTheme.colorScheme.primary)
                            if (progressState.showProgressBar) { Text("已播放 ${progressState.progressSec / 60}:${(progressState.progressSec % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall); LinearProgressIndicator(progress = { progressState.progressFraction }, modifier = Modifier.fillMaxWidth()) }
                        }
                    }
                }
            }
            feed.failure?.let { failure -> item(span = { GridItemSpan(maxLineSpan) }) { CommunityFailure(failure, onLogin) {
                if (!busy) { busy = true; scope.launch { fetch(if (feed.failedReplace) null else feed.failedCursor, feed.failedReplace) } }
            } } }
            if (busy) item(span = { GridItemSpan(maxLineSpan) }) { DesktopLoadingIndicator(Modifier.fillMaxWidth()) }
            if (!busy && rows.isEmpty() && feed.failure == null) item(span = { GridItemSpan(maxLineSpan) }) { Text("这个筛选下暂无投稿", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!busy && feed.initialized && feed.next != null) item(span = { GridItemSpan(maxLineSpan) }) { Button(onClick = {
                if (!busy) { busy = true; scope.launch { fetch(feed.next, false) } }
            }) { Text("加载更多") } }
        }
    }
}
