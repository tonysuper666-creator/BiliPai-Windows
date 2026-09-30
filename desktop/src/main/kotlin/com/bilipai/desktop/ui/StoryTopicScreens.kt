package com.bilipai.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.search.*
import com.android.purebilibili.feature.video.ui.pager.resolveCommittedPage
import com.android.purebilibili.feature.video.ui.pager.resolvePortraitCoverContentScale
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage

/** A single portrait pager. The native player surface stays owned by the root playback host. */
@Composable
fun DesktopStoryScreen(data: DesktopStoryTopicDataSource, seed: DesktopStorySeed = DesktopStorySeed(),
    onlyVerticalRecommendations: Boolean = false, isActive: Boolean = true,
    playback: DesktopStoryPlaybackSnapshot,
    onPlaybackRequest: (DesktopStoryPlaybackRequest) -> Unit,
    onReleasePlayback: (DesktopStoryOwner) -> Unit,
    onBack: () -> Unit, onUser: (Long) -> Unit, onSearch: () -> Unit,
    onRetryPlayback: (DesktopStoryOwner) -> Unit = {},
    nativeInput: DesktopStoryNativeInputBinding? = null,
    playerContent: @Composable (DesktopStoryOwner, VideoCard, Modifier) -> Unit) {
    val epoch by data.sessionEpoch.collectAsState()
    val scope = rememberCoroutineScope()
    val controller = remember(data, epoch, seed, onlyVerticalRecommendations) { DesktopStoryController(data, scope, seed, onlyVerticalRecommendations) }
    val state by controller.state.collectAsState()
    val latestRequest by rememberUpdatedState(onPlaybackRequest)
    val latestRelease by rememberUpdatedState(onReleasePlayback)
    val latestRetry by rememberUpdatedState(onRetryPlayback)
    var hasRequestedPlayback by remember(controller) { mutableStateOf(false) }
    var lastCommitted by remember(controller) { mutableIntStateOf(-1) }
    var lastBvid by remember(controller) { mutableStateOf("") }
    var revision by remember(controller) { mutableLongStateOf(0) }
    val pager = rememberPagerState { state.pages.size }
    val queue = remember(state.pages) { controller.queue() }
    val focusManager = LocalFocusManager.current
    val latestActive by rememberUpdatedState(isActive)
    val latestNativeInput by rememberUpdatedState(nativeInput)
    DisposableEffect(controller, nativeInput?.surface) {
        val surface = nativeInput?.surface
        val bridge = surface?.let {
            DesktopStoryNativeInputBridge(it, token = {
                val binding = latestNativeInput
                if (!latestActive || binding == null || !controller.currentEpochIsOwned() || !binding.owns(controller.owner)) null
                else DesktopStoryNativeInputToken(controller.owner, binding.sourceVersion())
            }, onPageStep = { direction, expected ->
                val binding = latestNativeInput
                val target = pager.settledPage + direction
                if (!latestActive || binding == null || !binding.owns(controller.owner) ||
                    !controller.currentEpochIsOwned() || binding.sourceVersion() != expected.sourceVersion ||
                    controller.owner != expected.owner || pager.isScrollInProgress || target !in 0 until pager.pageCount) false
                else { scope.launch {
                    val current = latestNativeInput
                    if (latestActive && controller.currentEpochIsOwned() && current?.owns(expected.owner) == true &&
                        current.sourceVersion() == expected.sourceVersion) pager.animateScrollToPage(target)
                }; true }
            }, onPlayerKey = { action, expected ->
                val current = latestNativeInput
                latestActive && controller.currentEpochIsOwned() && current?.owns(expected.owner) == true &&
                    current.sourceVersion() == expected.sourceVersion && current.onPlayerKey(action, expected.sourceVersion)
            }, onNativeFocus = { focusManager.clearFocus(force = true) })
        }
        onDispose { bridge?.close() }
    }
    DisposableEffect(controller) { onDispose {
        controller.close()
        if (hasRequestedPlayback) latestRelease(controller.owner)
    } }
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(controller, isActive) {
        if (!isActive && hasRequestedPlayback) {
            latestRelease(controller.owner); hasRequestedPlayback = false; lastCommitted = -1
        }
    }
    LaunchedEffect(controller, isActive, pager.settledPage, pager.isScrollInProgress, queue.getOrNull(pager.settledPage)?.bvid) {
        if (!isActive || queue.isEmpty() || !controller.currentEpochIsOwned()) return@LaunchedEffect
        val page = resolveCommittedPage(pager.isScrollInProgress, pager.settledPage,
            lastCommitted.takeIf { lastBvid == queue.getOrNull(pager.settledPage)?.bvid } ?: -1) ?: return@LaunchedEffect
        if (page !in queue.indices) return@LaunchedEffect
        lastCommitted = page; lastBvid = queue[page].bvid; hasRequestedPlayback = true
        latestRequest(DesktopStoryPlaybackRequest(controller.owner, ++revision, queue, page, select = true))
        controller.commitPage(page)
    }
    LaunchedEffect(controller, queue) {
        if (isActive && hasRequestedPlayback && pager.settledPage in queue.indices && controller.currentEpochIsOwned())
            latestRequest(DesktopStoryPlaybackRequest(controller.owner, ++revision, queue, pager.settledPage, select = false))
    }
    LaunchedEffect(controller, playback.owner, playback.bvid, playback.queueIndex, playback.cid) {
        if (playback.owner == controller.owner && isActive) {
            val page = playback.queueIndex.takeIf { it in queue.indices }
                ?: queue.indexOfFirst { it.bvid == playback.bvid && (playback.cid <= 0 || it.preferredCid <= 0 || it.preferredCid == playback.cid) }
            if (page >= 0 && page != pager.settledPage) {
                // A root auto-continue/queue action is already playing: scroll without reloading it.
                lastCommitted = page; lastBvid = queue[page].bvid; controller.commitPage(page); pager.animateScrollToPage(page)
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { event ->
        val direction = when (event.key) { Key.DirectionDown, Key.PageDown -> 1; Key.DirectionUp, Key.PageUp -> -1; else -> 0 }
        if (event.type != KeyEventType.KeyDown || direction == 0 || !isActive || pager.isScrollInProgress) false
        else { val target = pager.settledPage + direction
            if (target in queue.indices) scope.launch { pager.animateScrollToPage(target) }; true }
    }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onBack) { AppText("‹ 返回", color = Color.White) }
            AppText("Story", color = Color.White, modifier = Modifier.weight(1f))
            AppTextButton(onSearch) { AppText("搜索", color = Color.White) }
            AppTextButton(controller::refresh, enabled = !state.feed.isLoading) { AppText("刷新", color = Color.White) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (queue.isNotEmpty()) VerticalPager(pager, modifier = Modifier.fillMaxSize(), key = { queue[it].bvid }, userScrollEnabled = isActive) { page ->
                val card = queue[page]
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).aspectRatio(9f / 16f).background(Color.Black)) {
                        if (page == pager.settledPage && isActive) playerContent(controller.owner, card, Modifier.fillMaxSize())
                        else if (card.cover.isNotBlank()) AsyncImage(imageUrl(card.cover),card.title,
                            modifier = Modifier.fillMaxSize(), contentScale = resolvePortraitCoverContentScale())
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            AppText(card.title, color = Color.White)
                            if (card.author.isNotBlank()) AppTextButton({ onUser(card.authorMid) }, enabled = card.authorMid > 0) { AppText(card.author, color = Color.White) }
                        }
                        AppText("${page + 1} / ${queue.size}", color = Color.White)
                        AppTextButton({ scope.launch { pager.animateScrollToPage((page - 1).coerceAtLeast(0)) } }, enabled = page > 0) { AppText("上一条", color = Color.White) }
                        AppTextButton({ scope.launch { pager.animateScrollToPage(page + 1) } }, enabled = page < queue.lastIndex) { AppText("下一条", color = Color.White) }
                    }
                }
            }
            else if (!state.feed.isLoading && state.feed.error == null) AppText("暂时没有可播放的推荐视频", color = Color.White)
        }
        if (state.feed.isLoading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        val playbackError = playback.takeIf { it.owner == controller.owner }?.error
        val error = playbackError ?: state.feed.error
        error?.let { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText(it, color = Color.White, modifier = Modifier.weight(1f))
            AppTextButton({
                if (playbackError != null) latestRetry(controller.owner)
                else if (queue.isEmpty()) controller.refresh() else controller.loadMore()
            }, enabled = playbackError == null || !playback.loading) { AppText("重试", color = Color.White) }
        } }
    }
}

@Composable
internal fun DesktopTopicDetailScreen(topicId: Long, data: DesktopStoryTopicDataSource,
    community: DesktopCommunityRepository, navigation: CommunityNavigation,
    onBack: () -> Unit, onTopic: (Long) -> Unit) {
    val epoch by data.sessionEpoch.collectAsState()
    val scope = rememberCoroutineScope()
    val controller = remember(data, topicId, epoch) { DesktopTopicController(topicId, data, scope) }
    val state by controller.state.collectAsState()
    val cardOwner=remember(controller){object:DesktopDynamicCardItemsOwner {
        override fun mutateDynamicItems(transform:(List<com.android.purebilibili.data.model.response.DynamicItem>)->List<com.android.purebilibili.data.model.response.DynamicItem>) {
            controller.mutateDynamicItems(transform)
        }
    }}
    LocalDesktopDynamicCardStateRegistry.current?.register(cardOwner)
    var composing by remember(controller) { mutableStateOf(false) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.load() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(20.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onBack) { AppText("‹ 返回") }
            AppText(state.details?.topicItem?.name.orEmpty().ifBlank { "话题" }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            AppButton({ composing = !composing }, enabled = state.details?.topicItem?.id?.let { it > 0 } == true,
                modifier = Modifier.width(TOPIC_PARTICIPATE_BUTTON_WIDTH_DP.dp)) { AppText(if (composing) "收起发布" else "参与话题") }
        }
        if (composing) state.details?.topicItem?.let { topic -> DesktopTopicComposer(topic, epoch, data, community, navigation,
            onDismiss = { composing = false }, onPublished = { composing = false; controller.refreshSelectedFeed() }) }
        DesktopTopicContent(state, onUser = navigation.onUser, onSort = controller::selectSort,
            onMore = controller::loadMore, onRetry = controller::load) { item ->
            CommunityDynamicCard(item, community, navigation)
            item.modules.module_dynamic?.topic?.takeIf { it.id > 0 }?.let { topic ->
                AppTextButton({ onTopic(topic.id) }) { AppText("#${topic.name}") }
            }
        }
    }
}

@Composable
internal fun DesktopTopicContent(state: TopicDetailUiState, onUser: (Long) -> Unit, onSort: (Int) -> Unit,
    onMore: () -> Unit, onRetry: () -> Unit, dynamicCard: @Composable (DynamicItem) -> Unit) {
    if (shouldShowTopicInitialSkeleton(state.isLoading, state.details != null, state.items.size)) {
        AppLinearProgressIndicator(Modifier.fillMaxWidth()); return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            val topic = state.details?.topicItem; val creator = state.details?.topicCreator
            AppCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText("# ${topic?.name.orEmpty().ifBlank { "话题" }}", style = MaterialTheme.typography.titleMedium)
                topic?.description?.takeIf { it.isNotBlank() }?.let { AppText(it) }
                AppText("浏览 ${topic?.view ?: 0} · 讨论 ${topic?.discuss ?: 0}", style = MaterialTheme.typography.bodySmall)
                creator?.takeIf { it.uid > 0 }?.let { AppTextButton({ onUser(it.uid) }) { AppText(it.name) } }
            } }
            if (state.sortOptions.isNotEmpty()) Row(Modifier.padding(top = 12.dp).width(resolveTopicSortControlWidthDp(state.sortOptions.size).dp)) {
                state.sortOptions.forEach { option -> AppFilterChip(state.selectedSortBy == option.sortBy, { onSort(option.sortBy) },
                    label = { AppText(option.sortName.ifBlank { "动态" }) }, enabled = !state.isLoading && !state.isSwitchingSort,
                    modifier = Modifier.width(TOPIC_SORT_ITEM_WIDTH_DP.dp)) }
            }
        }
        itemsIndexed(state.items, key = { index, item -> item.id_str.ifBlank { "empty-$index-${item.hashCode()}" } }) { index, item ->
            dynamicCard(item)
            if (index == state.items.size - 3 && state.hasMore && !state.isLoadingMore && !state.isSwitchingSort)
                LaunchedEffect(state.offset, state.selectedSortBy) { onMore() }
        }
        if (state.isLoadingMore || state.isSwitchingSort) item { AppLinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.error?.let { error -> item { AppText(error, color = MaterialTheme.colorScheme.error); AppTextButton(onRetry) { AppText("重试") } } }
        if (!state.isLoading && !state.isLoadingMore && state.items.isEmpty() && state.error == null) item { AppText("暂无话题动态") }
        if (state.hasMore && !state.isLoadingMore && !state.isSwitchingSort) item { AppTextButton(onMore) { AppText("加载更多") } }
    }
}

internal fun desktopTopicDraft(topic: TopicItem, text: String, title: String, private: Boolean): DynamicPublishDraft =
    DynamicPublishDraft(text = text, title = title, private = private, topic = topic.takeIf { it.id > 0 }?.let { DynamicPublishTopic(it.id, it.name) })

@Composable
private fun DesktopTopicComposer(topic: TopicItem, epoch: Long, data: DesktopStoryTopicDataSource,
    community: DesktopCommunityRepository, navigation: CommunityNavigation, onDismiss: () -> Unit, onPublished: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }; var title by remember { mutableStateOf("") }
    var private by remember { mutableStateOf(false) }; var images by remember { mutableStateOf(emptyList<CommunityLocalImage>()) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<Throwable?>(null) }
    fun ensureOwner() { if (epoch != data.sessionEpoch.value) throw CancellationException("账号已切换") }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppText("参与 #${topic.name}", style = MaterialTheme.typography.titleMedium)
        AppOutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, labelText = "标题（可选）")
        AppOutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), enabled = !busy, minLines = 3, labelText = "分享你的想法")
        Row(verticalAlignment = Alignment.CenterVertically) { AppCheckbox(private, { private = it }, enabled = !busy); AppText("仅自己可见") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextButton({ scope.launch { try { ensureOwner(); val chosen = selectCommunityImages(true); ensureOwner()
                require(images.size + chosen.size <= 9) { "最多选择 9 张图片" }; images = images + chosen
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure } } }, enabled = !busy) { AppText("选择图片 (${images.size}/9)") }
            AppButton({ busy = true; error = null; scope.launch {
                try { ensureOwner(); val uploads = images.map { image -> ensureOwner(); community.uploadDynamicImage(image.name, image.mime, image.bytes()).also { ensureOwner() } }
                    ensureOwner(); community.publishDynamic(desktopTopicDraft(topic, text, title, private), uploads); ensureOwner(); onPublished()
                } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { error = failure } finally { busy = false }
            } }, enabled = !busy && (text.isNotBlank() || images.isNotEmpty())) { AppText(if (busy) "发布中…" else "发布") }
            AppTextButton(onDismiss, enabled = !busy) { AppText("取消") }
        }
        images.forEach { image -> Row { AppText(image.name, Modifier.weight(1f)); AppTextButton({ images = images - image }, enabled = !busy) { AppText("移除") } } }
        error?.let { CommunityFailure(it, navigation.onLogin) }
    }
}
