package com.bilipai.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.core.store.TodayWatchFeedbackSnapshot
import com.android.purebilibili.data.model.response.PopularSeriesPeriod
import com.android.purebilibili.data.model.response.VideoshotData
import com.android.purebilibili.data.model.response.VideoItem
import com.android.purebilibili.data.model.response.RecommendationFeedbackReason
import com.android.purebilibili.feature.home.*
import com.android.purebilibili.feature.partition.*
import com.android.purebilibili.feature.video.ui.components.*
import com.bilipai.desktop.data.*
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.plugins.DesktopPluginRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import java.util.WeakHashMap

private val discoveryGridScroll = WeakHashMap<CommunityFeedState<*, *>, LazyStaggeredGridState>()

private class DiscoveryScreenState(section: DiscoverySection, regionId: Int) {
    var mode by mutableStateOf(section)
    var selectedRegion by mutableIntStateOf(regionId.takeIf { it > 0 } ?: allPartitions.first { resolvePartitionBangumiType(it.id) == null }.id)
    var rankingRegion by mutableIntStateOf(0)
    var period by mutableStateOf<Int?>(null)
    var periods by mutableStateOf<List<PopularSeriesPeriod>?>(null)
    var periodsError by mutableStateOf<Throwable?>(null)
    var periodsRevision by mutableIntStateOf(0)
}

@Composable
fun DiscoveryContentScreen(section: DiscoverySection, discovery: DesktopDiscoveryRepository, repository: DesktopRepository, plugins: DesktopPluginStore,
    onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onLogin: () -> Unit,
    onBangumiPartition: (Int) -> Unit, regionId: Int = 0,
    onPlayQueue: (List<VideoCard>, VideoCard) -> Unit = { _, video -> onVideo(video) }, runtime: DesktopPluginRuntime? = null,
    onRestart: (() -> Unit)? = null, isClosing: () -> Boolean = { false }) {
    val account by repository.account.collectAsState()
    val epoch by repository.sessionEpochFlow.collectAsState()
    val capturedEpoch = epoch
    val capturedMid = account?.mid
    val latestIsClosing by rememberUpdatedState(isClosing)
    val feedbackGuard = remember(discovery, capturedMid, capturedEpoch) {
        DesktopDiscoveryStorageGuard(
            factory = { openDesktopDiscoveryFeedback(discovery, capturedMid) },
            sessionEpoch = { repository.sessionEpoch },
            stillOwned = { !latestIsClosing() && repository.sessionEpoch == capturedEpoch &&
                repository.account.value?.mid == capturedMid },
        )
    }
    DesktopDiscoveryStorageBoundary(feedbackGuard, capturedEpoch, onRestart, Modifier.fillMaxSize()) { feedback ->
        DiscoveryContentReady(section, discovery, repository, plugins, onVideo, onUser, onLogin, onBangumiPartition,
            regionId, onPlayQueue, runtime, capturedMid, feedback)
    }
}

@Composable
private fun DiscoveryContentReady(section: DiscoverySection, discovery: DesktopDiscoveryRepository, repository: DesktopRepository,
    plugins: DesktopPluginStore, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onLogin: () -> Unit,
    onBangumiPartition: (Int) -> Unit, regionId: Int, onPlayQueue: (List<VideoCard>, VideoCard) -> Unit,
    runtime: DesktopPluginRuntime?, accountMid: Long?, feedbackSource: StateFlow<TodayWatchFeedbackSnapshot>) {
    val inherited = LocalDesktopBrowseMemory.current
    val fallback = remember(discovery, accountMid) { DesktopBrowseMemory() }
    val memory = inherited ?: fallback
    val screenKey = listOf("discovery-screen", section, regionId, accountMid)
    val state = remember(memory, screenKey) { memory.screen(screenKey) { DiscoveryScreenState(section, regionId) } }
    var mode by state::mode
    var selectedRegion by state::selectedRegion
    var rankingRegion by state::rankingRegion
    var period by state::period
    var periods by state::periods
    var periodsError by state::periodsError
    var periodsRevision by state::periodsRevision
    var preview by remember(accountMid) { mutableStateOf<VideoCard?>(null) }
    val feedMode by discovery.feedMode.collectAsState()
    val refreshCount by discovery.refreshCount.collectAsState()
    val scope = rememberCoroutineScope()
    var settingsBusy by remember { mutableStateOf(false) }; var settingsError by remember { mutableStateOf<String?>(null) }
    val config by plugins.feedFilterConfig.collectAsState()
    val enabled by plugins.feedFilterEnabled.collectAsState()
    val filters = remember(enabled, config) { DesktopDiscoveryFilters(enabled, config) }
    var filterDialog by remember(accountMid) { mutableStateOf(false) }
    LaunchedEffect(mode, periodsRevision) {
        if (mode != DiscoverySection.WEEKLY) return@LaunchedEffect
        if (periods != null && periodsRevision == 0) return@LaunchedEffect
        periodsError = null
        try { periods = discovery.weeklyPeriods(); if (periods!!.none { it.number == period }) period = periods!!.firstOrNull()?.number ?: 1 }
        catch (error: Exception) { if (error is CancellationException) throw error; periodsError = error }
    }
    CompositionLocalProvider(LocalDesktopBrowseMemory provides memory, LocalCommunityFeedMemory provides memory.feeds) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiscoverySection.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.label) }) }
        }
        TextButton(onClick = { filterDialog = true }, modifier = Modifier.padding(horizontal = 18.dp)) {
            Text(if (filters.enabled) "推荐筛选：已开启" else "推荐筛选")
        }
        if (mode == DiscoverySection.RECOMMEND) {
            Row(Modifier.padding(horizontal = 18.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DesktopRecommendationMode.entries.forEach { item -> FilterChip(feedMode == item, onClick = {
                    if (!settingsBusy) { settingsBusy = true; settingsError = null; scope.launch {
                        try { discovery.setFeedMode(item) } catch (error: Exception) { if (error is CancellationException) throw error; settingsError = error.message ?: "设置保存失败" }
                        finally { settingsBusy = false }
                    } }
                }, enabled = !settingsBusy, label = { Text(item.label) }) }
                listOf(10, 20, 30).forEach { count -> FilterChip(refreshCount == count, onClick = {
                    if (!settingsBusy) { settingsBusy = true; settingsError = null; scope.launch {
                        try { discovery.setRefreshCount(count) } catch (error: Exception) { if (error is CancellationException) throw error; settingsError = error.message ?: "设置保存失败" }
                        finally { settingsBusy = false }
                    } }
                }, enabled = !settingsBusy, label = { Text("每批 $count") }) }
            }
            Text(feedMode.description, modifier = Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.bodySmall)
            settingsError?.let { Text(it, modifier = Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.error) }
        }
        if (mode == DiscoverySection.REGION || mode == DiscoverySection.RANKING) {
            val categories = if (mode == DiscoverySection.RANKING) listOf(PartitionCategory(0, "全站")) + allPartitions.filter { resolvePartitionBangumiType(it.id) == null } else allPartitions
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                categories.forEach { category ->
                    val selected = if (mode == DiscoverySection.RANKING) rankingRegion == category.id else selectedRegion == category.id
                    FilterChip(selected, onClick = {
                        val type = resolvePartitionBangumiType(category.id)
                        if (type != null) onBangumiPartition(type)
                        else if (mode == DiscoverySection.RANKING) rankingRegion = category.id else selectedRegion = category.id
                    }, label = { Text(category.name) })
                }
            }
        }
        if (mode == DiscoverySection.WEEKLY) {
            if (periodsError != null) CommunityFailure(periodsError!!, onLogin) { periodsRevision++ }
            if (periods == null && periodsError == null) DesktopLoadingIndicator(Modifier.fillMaxWidth())
            periods?.let { choices ->
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.padding(horizontal = 18.dp)) {
                    OutlinedButton(onClick = { menu = true }) { Text(choices.firstOrNull { it.number == period }?.name?.ifBlank { "第 $period 期" } ?: "选择周刊") }
                    DropdownMenu(menu, { menu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        choices.forEach { choice -> DropdownMenuItem(text = { Column { Text(choice.name.ifBlank { "第 ${choice.number} 期" }); if (choice.subject.isNotBlank()) Text(choice.subject, style = MaterialTheme.typography.bodySmall) } },
                            onClick = { period = choice.number; menu = false }) }
                    }
                }
            }
        }
        if (mode != DiscoverySection.WEEKLY || periods != null) {
            val requestRegion = if (mode == DiscoverySection.RANKING) rankingRegion else if (mode == DiscoverySection.REGION) selectedRegion else 0
            DiscoveryFeed(mode, requestRegion, period, accountMid, discovery, filters, runtime, onVideo, onUser, onLogin,
                feedbackSource = feedbackSource, onPreview = { preview = it })
        }
    }
    }
    preview?.let { card -> DiscoveryVideoPreviewDialog(card, repository, discovery, onVideo, onUser,
        onDismiss = { preview = null }, onPlayQueue = onPlayQueue) }
    if (filterDialog) DiscoveryFilterDialog(filters, plugins, onSaved = { filterDialog = false }, onDismiss = { filterDialog = false })
}

@Composable
private fun DiscoveryFeed(section: DiscoverySection, regionId: Int, period: Int?, accountMid: Long?, discovery: DesktopDiscoveryRepository,
    filters: DesktopDiscoveryFilters, runtime: DesktopPluginRuntime?, onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit,
    onLogin: () -> Unit, feedbackSource: StateFlow<TodayWatchFeedbackSnapshot>, onPreview: (VideoCard) -> Unit) {
    val scope = rememberCoroutineScope(); val memory = LocalCommunityFeedMemory.current ?: LocalDesktopBrowseMemory.current?.feeds; val namespace = LocalCommunityFeedNamespace.current
    val feedMode by discovery.feedMode.collectAsState(); val refreshCount by discovery.refreshCount.collectAsState()
    val feedback by feedbackSource.collectAsState()
    val blockedCreators by remember(discovery, accountMid) { discovery.blockedCreators(accountMid) }.collectAsState()
    val blockedMigrationError by discovery.blockedUps.migrationError.collectAsState()
    val nativePlugins = runtime?.plugins?.collectAsState()?.value
    val jsonPlugins = runtime?.jsonPlugins?.collectAsState()?.value
    val pluginConfiguration = runtime?.store?.snapshot("plugin_prefs")?.collectAsState()?.value
    val key = listOf("discovery", section, regionId, period, namespace, accountMid,
        feedMode.takeIf { section == DiscoverySection.RECOMMEND }, refreshCount.takeIf { section == DiscoverySection.RECOMMEND })
    val page = remember(memory, key) { memory?.page<DiscoveryPage, Int>(key) ?: CommunityFeedState() }
    val scroll = remember(page) { discoveryGridScroll.getOrPut(page) { LazyStaggeredGridState() } }
    var busy by remember(key) { mutableStateOf(false) }; var error by page::failure
    var feedbackVideo by remember(key) { mutableStateOf<VideoItem?>(null) }
    var feedbackBusy by remember(key) { mutableStateOf(false) }; var feedbackMessage by page::statusMessage
    var showBlocked by remember(key) { mutableStateOf(false) }
    val token = remember(key) { Any() }; val activeToken by rememberUpdatedState(token)
    val originals = page.rows.flatMap { it.items }.distinctBy { it.bvid }.filter { it.bvid.isNotBlank() && it.title.isNotBlank() }
    val cards = remember(originals, filters, section, feedback, blockedCreators, runtime, nativePlugins, jsonPlugins, pluginConfiguration) {
        val feedbackFiltered = filterHomeVideosByNotInterestedFeedback(originals.filter { it.owner.mid !in blockedCreators },
            feedback.dislikedBvids, feedback.dislikedCreatorMids, feedback.dislikedKeywords)
        (runtime?.filterFeedItems(feedbackFiltered, discoveryFeedKind(section))
            ?: filterDiscoveryItems(feedbackFiltered, filters, section)).map(::discoveryVideoCard)
    }
    suspend fun fetch(requestPage: Int, replace: Boolean) {
        val requestToken = token; busy = true; error = null; page.failedCursor = null
        try {
            val result = discovery.page(section, requestPage, regionId, period).copy(requestPage = requestPage)
            if (requestToken === activeToken) {
                page.rows = if (replace) listOf(result) else page.rows + result
                page.next = result.nextPage; page.initialized = true
            }
        } catch (failure: Exception) { if (failure is CancellationException) throw failure
            if (requestToken === activeToken) { error = failure; page.failedCursor = requestPage; page.failedReplace = replace }
        } finally { if (requestToken === activeToken) busy = false }
    }
    LaunchedEffect(token) { if (!page.initialized && error == null) fetch(1, true) }
    // The original HomeRefresh policy advances popular/region batches, and recommendation idx.
    fun refreshPage(): Int {
        val previous = page.rows.lastOrNull()?.requestPage ?: 0
        if (section == DiscoverySection.RECOMMEND) return resolveRecommendFeedRequestIndex(false, true, (previous - 1).coerceAtLeast(0)) + 1
        return resolvePagedFeedPageToFetch(false, true, previous, section == DiscoverySection.POPULAR || section == DiscoverySection.REGION)
    }
    DesktopHomeCardGrid(state = scroll, modifier = Modifier.fillMaxSize()) { cardLayout ->
        item(span = StaggeredGridItemSpan.FullLine) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(page.rows.lastOrNull()?.title?.takeIf { it.isNotBlank() } ?: section.label, style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !busy, onClick = { if (!busy) { busy = true; scope.launch { fetch(refreshPage(), true) } } }) { Text("换一批 / 刷新") }
                }
                page.rows.lastOrNull()?.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text("${cards.size} 个视频", style = MaterialTheme.typography.labelMedium)
                page.rows.lastOrNull()?.let { result ->
                    if (result.actualSources.isNotEmpty()) Text("本批来源：" + result.actualSources.joinToString(" + ") { if (it == DesktopRecommendationMode.WEB) "Web" else "App" }, style = MaterialTheme.typography.bodySmall)
                    if (result.sourceNotice.isNotBlank()) Text(result.sourceNotice, style = MaterialTheme.typography.bodySmall)
                }
                if (cards.size < originals.size) Text("按筛选和反馈规则隐藏 ${originals.size - cards.size} 个视频", style = MaterialTheme.typography.bodySmall)
                feedbackMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (feedbackBusy) DesktopLoadingIndicator(Modifier.fillMaxWidth())

                blockedMigrationError?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { discovery.blockedUps.migrateLegacyDiscoveryMids() } }) { Text("重试黑名单迁移") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !feedbackBusy, onClick = { showBlocked = true }) { Text("屏蔽名单 · ${blockedCreators.size}") }
                    TextButton(enabled = !feedbackBusy && feedback.dislikedBvids.isNotEmpty(), onClick = { feedbackBusy = true; scope.launch {
                        try { discovery.clearFeedback(accountMid); feedbackMessage = "已清除不感兴趣记录" }
                        catch (error: Exception) { if (error is CancellationException) throw error; feedbackMessage = error.message ?: "清除失败" }
                        finally { feedbackBusy = false }
                    } }) { Text("清除不感兴趣记录") }
                }
            }
        }
        items(cards, key = { it.bvid }) { card ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DiscoveryVideoTile(card, cardLayout) { onVideo(card) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = card.authorMid > 0, onClick = { onUser(card.authorMid) }) { Text("UP 主页") }
                    TextButton(onClick = { onPreview(card) }) { Text("预览与合集") }
                    TextButton(enabled = !feedbackBusy, onClick = { feedbackVideo = originals.firstOrNull { it.bvid == card.bvid } }) { Text("不感兴趣") }
                }
            }
        }
        if (error != null) item(span = StaggeredGridItemSpan.FullLine) { CommunityFailure(error!!, onLogin) {
            if (!busy) { val retry = page.failedCursor ?: 1; val replace = page.failedReplace; busy = true; scope.launch { fetch(retry, replace) } }
        } }
        if (busy) item(span = StaggeredGridItemSpan.FullLine) { DesktopLoadingIndicator(Modifier.fillMaxWidth()) }
        if (!busy && cards.isEmpty() && error == null) item(span = StaggeredGridItemSpan.FullLine) { Text("暂无视频") }
        if (!busy && page.next != null) item(span = StaggeredGridItemSpan.FullLine) { Button(onClick = {
            if (!busy) { val next = page.next ?: return@Button; busy = true; scope.launch { fetch(next, false) } }
        }) { Text("加载更多") } }
    }
    feedbackVideo?.let { video -> DiscoveryFeedbackDialog(video, onDismiss = { feedbackVideo = null }) { reason ->
        feedbackVideo = null; feedbackBusy = true; scope.launch {
            try { val result = discovery.notInterested(video, reason, accountMid)
                feedbackMessage = listOfNotNull(result.message, result.creator?.message, result.serverFeedbackError).joinToString("；")
            } catch (error: Exception) { if (error is CancellationException) throw error; feedbackMessage = error.message ?: "反馈失败" }
            finally { feedbackBusy = false }
        }
    } }
    if (showBlocked) AlertDialog(onDismissRequest = { showBlocked = false }, title = { Text("屏蔽名单") }, text = {
        Column(Modifier.width(560.dp).heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (blockedCreators.isEmpty()) Text("暂无屏蔽的 UP 主")
            blockedCreators.forEach { mid -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onUser(mid) }) { Text("UID $mid") }
                TextButton(enabled = !feedbackBusy, onClick = { feedbackBusy = true; scope.launch {
                    try { feedbackMessage = discovery.unblockCreator(mid, accountMid).message }
                    catch (error: Exception) { if (error is CancellationException) throw error; feedbackMessage = error.message ?: "解除屏蔽失败" }
                    finally { feedbackBusy = false }
                } }) { Text("解除屏蔽并同步 B 站") }
            } }
            feedbackMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (accountMid == null) TextButton(onClick = onLogin) { Text("登录以同步 B 站黑名单") }
        }
    }, confirmButton = { TextButton(onClick = { showBlocked = false }) { Text("关闭") } })
}

@Composable private fun DiscoveryFeedbackDialog(video: VideoItem, onDismiss: () -> Unit, onReason: (RecommendationFeedbackReason) -> Unit) {
    val reasons = remember(video) { resolveHomeNotInterestedReasons(video) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("不感兴趣") }, text = {
        Column(Modifier.width(560.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("屏蔽 UP 主会保存到本机，并在已登录时同步 B 站黑名单。", style = MaterialTheme.typography.bodySmall)
            reasons.forEach { reason -> OutlinedButton(onClick = { onReason(reason) }, modifier = Modifier.fillMaxWidth()) { Text(reason.name) } }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun DiscoveryVideoTile(card: VideoCard, layout: HomeFeedCardLayout, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        AsyncImage(card.cover, card.title, Modifier.fillMaxWidth().aspectRatio(layout.coverAspectRatio), contentScale = ContentScale.Crop)
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.title, minLines = layout.titleMinLines, maxLines = layout.titleMaxLines, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(card.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Text("${FormatUtils.formatStat(card.playCount)} 次播放 · ${FormatUtils.formatDuration(card.duration)}", style = MaterialTheme.typography.labelMedium)
            if (card.publishedAt > 0) Text(FormatUtils.formatPublishTime(card.publishedAt), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun DiscoveryVideoPreviewDialog(card: VideoCard, repository: DesktopRepository, discovery: DesktopDiscoveryRepository,
    onVideo: (VideoCard) -> Unit, onUser: (Long) -> Unit, onDismiss: () -> Unit,
    onPlayQueue: (List<VideoCard>, VideoCard) -> Unit = { _, video -> onVideo(video) }) {
    var details by remember(card.bvid) { mutableStateOf<VideoDetails?>(null) }
    var failure by remember(card.bvid) { mutableStateOf<Throwable?>(null) }; var revision by remember { mutableIntStateOf(0) }
    var partIndex by remember(card.bvid) { mutableIntStateOf(0) }
    var shots by remember { mutableStateOf<VideoshotData?>(null) }; var shotsError by remember { mutableStateOf<String?>(null) }
    var seconds by remember(card.bvid, partIndex) { mutableFloatStateOf(0f) }
    LaunchedEffect(card.bvid, revision) {
        failure = null; details = null
        try { details = repository.videoDetails(card.bvid); partIndex = details!!.pages.indexOfFirst { it.cid == card.preferredCid }.coerceAtLeast(0) }
        catch (error: Exception) { if (error is CancellationException) throw error; failure = error }
    }
    val part = details?.pages?.getOrNull(partIndex)
    LaunchedEffect(details?.bvid, part?.cid) {
        shots = null; shotsError = null
        val current = details ?: return@LaunchedEffect; val cid = part?.cid ?: return@LaunchedEffect
        try { shots = discovery.videoShots(current.bvid, cid) }
        catch (error: Exception) { if (error is CancellationException) throw error; shotsError = error.message ?: "时间预览加载失败" }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(details?.title ?: card.title) }, text = {
        Column(Modifier.width(680.dp).heightIn(max = 650.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val info = details
            if (info == null && failure == null) DesktopLoadingIndicator(Modifier.fillMaxWidth())
            failure?.let { Text(it.message ?: "详情加载失败", color = MaterialTheme.colorScheme.error); TextButton(onClick = { revision++ }) { Text("重试") } }
            if (info != null) {
                val data = shots
                if (data?.isValid == true && part != null) {
                    DiscoveryShotImage(data, (seconds * 1000).toLong(), part.duration * 1000)
                    Slider(seconds, { seconds = it }, valueRange = 0f..part.duration.toFloat().coerceAtLeast(1f))
                    Text(FormatUtils.formatDuration(seconds.toInt()))
                } else AsyncImage(info.cover, info.title, Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Crop)
                shotsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(info.author, modifier = Modifier.clickable(enabled = info.authorMid > 0) { onUser(info.authorMid) })
                info.raw?.stat?.let { Text("播放 ${FormatUtils.formatStat(it.view.toLong())} · 点赞 ${FormatUtils.formatStat(it.like.toLong())} · 投币 ${it.coin} · 收藏 ${it.favorite} · 弹幕 ${it.danmaku} · 评论 ${it.reply}") }
                Text(info.description.ifBlank { "暂无简介" })
                if (info.pages.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    info.pages.forEachIndexed { index, value -> FilterChip(index == partIndex, { partIndex = index }, label = { Text("P${index + 1} ${value.title}") }) }
                }
                Button(onClick = { onVideo(card.copy(preferredCid = part?.cid ?: 0, pageIndex = partIndex, progressSeconds = seconds.toInt())); onDismiss() }) { Text("从预览位置播放") }
                DiscoveryUgcCollectionPanel(info, onVideo, onPlayQueue, part?.cid ?: 0)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun DiscoveryShotImage(shots: VideoshotData, positionMs: Long, durationMs: Long) {
    val preview = shots.getPreviewInfo(positionMs, durationMs) ?: return
    val painter = rememberAsyncImagePainter(imageUrl(preview.first))
    Canvas(Modifier.fillMaxWidth().aspectRatio(shots.img_x_size.toFloat() / shots.img_y_size)) {
        val scale = size.width / shots.img_x_size
        clipRect { translate(-preview.second * scale, -preview.third * scale) {
            with(painter) { draw(Size(shots.img_x_len * shots.img_x_size * scale, shots.img_y_len * shots.img_y_size * scale)) }
        } }
    }
}

@Composable
fun DiscoveryUgcCollectionPanel(details: VideoDetails, onVideo: (VideoCard) -> Unit,
    onPlayQueue: (List<VideoCard>, VideoCard) -> Unit = { _, video -> onVideo(video) }, currentCid: Long = 0) {
    var sort by remember(details.raw?.ugc_season?.id) { mutableStateOf(CollectionSortMode.ASCENDING) }
    val collection = remember(details, sort, currentCid) { desktopUgcCollection(details, sort, currentCid) } ?: return
    Text(collection.season.title, style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CollectionSortMode.entries.forEach { item ->
        FilterChip(sort == item, { sort = item }, label = { Text(resolveCollectionSortLabel(item)) })
    } }
    collection.queue.firstOrNull()?.let { first -> Button(onClick = { onPlayQueue(collection.queue, first) }) { Text("播放整个合集 · ${collection.queue.size} 集") } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        collection.sections.forEach { section ->
            item { Text(section.title.ifBlank { "合集" }, style = MaterialTheme.typography.titleMedium) }
            items(section.episodes, key = { "${section.id}:${it.id}:${it.bvid}:${it.cid}" }) { episode ->
                val selected = isCurrentUgcEpisode(details.bvid, currentCid, episode)
                val bvid = discoveryEpisodeBvid(episode)
                val card = collection.queue.firstOrNull { it.bvid == bvid && (episode.cid <= 0 || it.preferredCid == episode.cid) }
                    ?: collection.queue.firstOrNull { it.bvid == bvid }
                if (card != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onPlayQueue(collection.queue, card) }) { Text((if (selected) "▶ " else "") + card.title) }
                    resolveCollectionEpisodePublishTimeText(episode).takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (episode.pages.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        episode.pages.forEachIndexed { index, page -> TextButton(onClick = {
                            onPlayQueue(collection.queue, card.copy(preferredCid = page.cid, pageIndex = index))
                        }) { Text("P${index + 1} ${page.part}") } }
                    }
                }
            }
        }
    }
}
