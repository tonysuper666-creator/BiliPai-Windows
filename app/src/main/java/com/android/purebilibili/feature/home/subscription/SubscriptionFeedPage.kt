package com.android.purebilibili.feature.home.subscription

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.OverlayClip
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import com.android.purebilibili.core.util.animateScrollToTop
import com.android.purebilibili.core.util.Logger
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.plugin.feed.FeedBlock
import com.android.purebilibili.core.plugin.feed.ArticleNoteStore
import com.android.purebilibili.core.plugin.feed.SavedArticleNote
import com.android.purebilibili.core.plugin.feed.FeedInline
import com.android.purebilibili.core.plugin.feed.ParsedFeedItem
import com.android.purebilibili.core.plugin.feed.FeedSource
import com.android.purebilibili.core.plugin.feed.FeedReadingStore
import com.android.purebilibili.core.plugin.feed.FeedConditionalStore
import com.android.purebilibili.core.plugin.feed.SubscriptionFeedStore
import com.android.purebilibili.core.plugin.feed.feedItemKey
import com.android.purebilibili.core.plugin.feed.mergeCachedFeedItems
import com.android.purebilibili.core.plugin.feed.cleanFeedSummary
import com.android.purebilibili.core.plugin.feed.feedBodyNeedsRemoteFetch
import com.android.purebilibili.core.plugin.feed.isHttpFeedUrl
import com.android.purebilibili.core.plugin.feed.fetchArticleHtml
import com.android.purebilibili.core.plugin.feed.loadEnabledFeedSources
import com.android.purebilibili.core.plugin.feed.loadFeedSources
import com.android.purebilibili.core.plugin.feed.parseFeedHtml
import com.android.purebilibili.core.plugin.feed.stabilizeFeedOrder
import com.android.purebilibili.core.plugin.feed.feedPlainText
import com.android.purebilibili.feature.video.note.VideoNoteContentCodec
import com.android.purebilibili.feature.video.note.VideoNoteEditorDocument
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.core.store.HomeWallpaperEffectMode
import com.android.purebilibili.core.ui.LocalBottomBarContentPadding
import com.android.purebilibili.core.ui.LocalBottomBarVisible
import com.android.purebilibili.core.ui.LocalSetBottomBarVisible
import com.android.purebilibili.core.ui.LocalGlobalWallpaperBackdropVisible
import com.android.purebilibili.feature.home.HomeWallpaperBackdrop
import com.android.purebilibili.feature.home.resolveHomeWallpaperBackdropAppearance
import com.android.purebilibili.feature.home.resolveHomeWallpaperUri
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AdaptivePullToRefreshBox
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.AppTopBar
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.ImmersiveAppScaffold
import com.android.purebilibili.core.ui.rememberAppBackIcon
import com.android.purebilibili.core.ui.components.AppAssistChip
import com.android.purebilibili.core.ui.components.AppDropdownMenu
import com.android.purebilibili.core.ui.components.AppDropdownMenuItem
import com.android.purebilibili.core.ui.components.AppButton
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.AppTextButton
import com.android.purebilibili.feature.dynamic.components.ImagePreviewDialog
import com.android.purebilibili.feature.dynamic.components.isImagePreviewSourceHidden
import com.android.purebilibili.feature.dynamic.components.imagePreviewSourceBounds
import com.android.purebilibili.feature.dynamic.components.rememberImagePreviewSourceRect
import com.android.purebilibili.feature.dynamic.components.prepareImagePreviewSourceTransition
import com.android.purebilibili.feature.home.homeFeedPinchZoom
import java.time.Instant
import java.time.ZoneId

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun SubscriptionFeedPage(
    contentPadding: PaddingValues,
    articleContentPadding: PaddingValues = contentPadding,
    scrollToTopRequestId: Int,
    listState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    gridColumns: Int = 1,
    pinchEnabled: Boolean = false,
    pinchBounds: IntRange = 1..1,
    onColumnsChange: (Int) -> Unit = {},
    onPinchEnd: (Int) -> Unit = {},
    onArticleOpenChanged: (Boolean) -> Unit = {},
    onOpenPluginSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val subscriptionRevision by SubscriptionFeedStore.revision.collectAsStateWithLifecycle()
    var sources by remember { mutableStateOf<List<FeedSource>>(emptyList()) }
    var items by remember { mutableStateOf<List<ParsedFeedItem>>(emptyList()) }
    var readKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cachedBodies by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var unreadOnly by remember { mutableStateOf(false) }
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<ParsedFeedItem?>(null) }
    var previewImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    var previewSourceRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var previewSourceRects by remember { mutableStateOf<Map<Int, androidx.compose.ui.geometry.Rect>>(emptyMap()) }
    var webUrl by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val transitionState = remember { SeekableTransitionState<ParsedFeedItem?>(null) }
    val isArticleOpen = opened != null || transitionState.currentState != null || transitionState.targetState != null

    LaunchedEffect(isArticleOpen) {
        onArticleOpenChanged(isArticleOpen)
    }
    DisposableEffect(Unit) {
        onDispose { onArticleOpenChanged(false) }
    }
    PredictiveBackHandler(enabled = isArticleOpen) { progress ->
        var lastFraction = 0f
        try {
            progress.collect { event ->
                lastFraction = event.progress.coerceIn(0f, 1f)
                transitionState.seekTo(
                    fraction = lastFraction,
                    targetState = null
                )
            }
            val remainingMs = ((1f - lastFraction) * 360).toInt().coerceIn(100, 360)
            transitionState.animateTo(
                targetState = null,
                animationSpec = tween(remainingMs, easing = LinearEasing)
            )
            opened = null
        } catch (cancelled: CancellationException) {
            val currentOpened = opened
            if (currentOpened != null) {
                withContext(NonCancellable) {
                    if (lastFraction > 0.001f) {
                        val startFraction = lastFraction
                        val durationMs = (startFraction * 200).toInt().coerceIn(50, 180)
                        runCatching {
                            val startTime = withFrameMillis { it }
                            while (true) {
                                val currentTime = withFrameMillis { it }
                                val elapsed = currentTime - startTime
                                val p = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                                val eased = 1f - FastOutSlowInEasing.transform(p)
                                transitionState.seekTo(
                                    fraction = startFraction * eased,
                                    targetState = null
                                )
                                if (p >= 1f) break
                            }
                        }
                    }
                    runCatching { transitionState.seekTo(fraction = 0f, targetState = null) }
                    runCatching { transitionState.snapTo(currentOpened) }
                }
            }
        }
    }
    var reloadToken by remember { mutableIntStateOf(0) }

    LaunchedEffect(scrollToTopRequestId) {
        if (scrollToTopRequestId > 0 && !isArticleOpen) listState.animateScrollToTop()
    }
    LaunchedEffect(reloadToken, subscriptionRevision) {
        loading = true
        try {
            val loadedSources = withContext(Dispatchers.IO) { loadEnabledFeedSources(context) }
            sources = loadedSources
            if (selectedSourceId != null && loadedSources.none { it.id == selectedSourceId }) {
                selectedSourceId = null
            }
            val cache = FeedReadingStore.load(context)
            readKeys = cache.readKeys.toSet()
            cachedBodies = cache.fullBodies
            val enabledIds = loadedSources.map { it.id }.toSet()
            items = mergeCachedFeedItems(cache.items, emptyList(), enabledIds)
            val snapshot = loadFeedSources(loadedSources, FeedConditionalStore.load(context)) { update ->
                val preserveOrder = listState.firstVisibleItemIndex > 0 ||
                    listState.firstVisibleItemScrollOffset > 0
                val merged = mergeCachedFeedItems(cache.items, update.items, enabledIds)
                items = stabilizeFeedOrder(items, merged, preserveOrder)
            }
            val merged = mergeCachedFeedItems(cache.items, snapshot.items, enabledIds)
            val preserveOrder = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            items = stabilizeFeedOrder(items, merged, preserveOrder)
            snapshot.errors.forEach { Logger.w("SubscriptionFeed", it) }
            runCatching { FeedReadingStore.saveItems(context, merged) }
                .onFailure { Logger.w("SubscriptionFeed", "本地缓存保存失败: ${it.message}") }
            if (snapshot.validators.isNotEmpty()) {
                runCatching { FeedConditionalStore.update(context, snapshot.validators) }
                    .onFailure { Logger.w("SubscriptionFeed", "刷新状态保存失败: ${it.message}") }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Logger.w("SubscriptionFeed", "刷新失败，保留已显示内容: ${failure.message}")
        } finally {
            loading = false
        }
    }

    val visibleItems = items.filter { item ->
        (selectedSourceId == null || item.sourceId == selectedSourceId) &&
            (!unreadOnly || feedItemKey(item) !in readKeys || feedItemKey(item) == opened?.let(::feedItemKey))
    }
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        val transition = rememberTransition(transitionState, label = "subscription-article")
        transition.AnimatedContent(
            transitionSpec = {
                fadeIn(tween(360, easing = LinearEasing)) togetherWith fadeOut(tween(360, easing = LinearEasing))
            },
            contentKey = { it?.let { "${it.sourceId}:${it.id}" } ?: "grid" },
            modifier = Modifier.fillMaxSize(),
        ) { article ->
            if (article != null) {
                SubscriptionArticleScreen(
                    item = article,
                    scrollToTopRequestId = scrollToTopRequestId,
                    contentPadding = articleContentPadding,
                    cachedBody = cachedBodies[feedItemKey(article)],
                    isRead = feedItemKey(article) in readKeys,
                    onOpenUrl = { url -> webUrl = url },
                    onReadChange = { read ->
                        val key = feedItemKey(article)
                        readKeys = if (read) readKeys + key else readKeys - key
                        scope.launch {
                            runCatching { FeedReadingStore.setRead(context, key, read) }
                                .onFailure { Logger.w("SubscriptionFeed", "阅读状态保存失败: ${it.message}") }
                        }
                    },
                    onFullBody = { body ->
                        val key = feedItemKey(article)
                        cachedBodies = cachedBodies + (key to body)
                        scope.launch {
                            runCatching { FeedReadingStore.saveFullBody(context, key, body) }
                                .onFailure { Logger.w("SubscriptionFeed", "正文缓存保存失败: ${it.message}") }
                        }
                    },
                    onBack = {
                        scope.launch {
                            transitionState.animateTo(
                                targetState = null,
                                animationSpec = tween(360, easing = LinearEasing)
                            )
                            opened = null
                        }
                    },
                    onOpenImages = { images, index, rect, rects ->
                        previewImages = images
                        previewIndex = index
                        previewSourceRect = rect
                        previewSourceRects = rects
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                )
            } else {
                AdaptivePullToRefreshBox(
                    isRefreshing = loading,
                    onRefresh = { reloadToken += 1 },
                    indicatorTopInset = contentPadding.calculateTopPadding(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    SubscriptionFeedGrid(
                        sources = sources,
                        visibleItems = visibleItems,
                        loading = loading,
                        unreadOnly = unreadOnly,
                        onUnreadOnlyChange = { unreadOnly = it },
                        readKeys = readKeys,
                        selectedSourceId = selectedSourceId,
                        onSelectSource = { selectedSourceId = it },
                        onRefresh = { reloadToken += 1 },
                        onOpenPluginSettings = onOpenPluginSettings,
                        onOpen = { item ->
                            opened = item
                            val key = feedItemKey(item)
                            readKeys = readKeys + key
                            scope.launch {
                                runCatching { FeedReadingStore.setRead(context, key, true) }
                                    .onFailure { Logger.w("SubscriptionFeed", "阅读状态保存失败: ${it.message}") }
                            }
                            scope.launch {
                                transitionState.animateTo(
                                    targetState = item,
                                    animationSpec = tween(360, easing = LinearEasing)
                                )
                            }
                        },
                        contentPadding = contentPadding,
                        listState = listState,
                        gridColumns = gridColumns,
                        pinchEnabled = pinchEnabled,
                        pinchBounds = pinchBounds,
                        onColumnsChange = onColumnsChange,
                        onPinchEnd = onPinchEnd,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                    )
                }
            }
        }
    }
    if (webUrl != null) {
        com.android.purebilibili.feature.web.WebViewScreen(
            url = webUrl.orEmpty(),
            title = "原文",
            onBack = { webUrl = null },
        )
    }
    if (previewImages.isNotEmpty()) {
        ImagePreviewDialog(
            images = previewImages,
            initialIndex = previewIndex.coerceIn(0, previewImages.lastIndex),
            sourceRect = previewSourceRect,
            sourceRects = previewSourceRects,
            // FeedArticleImage 用 MaterialTheme.shapes.medium 裁角，回位圆角保持一致
            sourceCornerRadiusDp = with(density) {
                (MaterialTheme.shapes.medium as? CornerBasedShape)?.topStart
                    ?.toPx(Size.Unspecified, this)?.toDp()?.value
            } ?: 12f,
            onDismiss = {
                previewImages = emptyList()
                previewSourceRects = emptyMap()
            },
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SubscriptionFeedGrid(
    sources: List<FeedSource>,
    visibleItems: List<ParsedFeedItem>,
    loading: Boolean,
    unreadOnly: Boolean,
    onUnreadOnlyChange: (Boolean) -> Unit,
    readKeys: Set<String>,
    selectedSourceId: String?,
    onSelectSource: (String?) -> Unit,
    onRefresh: () -> Unit,
    onOpenPluginSettings: () -> Unit,
    onOpen: (ParsedFeedItem) -> Unit,
    contentPadding: PaddingValues,
    listState: LazyStaggeredGridState,
    gridColumns: Int,
    pinchEnabled: Boolean,
    pinchBounds: IntRange,
    onColumnsChange: (Int) -> Unit,
    onPinchEnd: (Int) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(gridColumns.coerceAtLeast(1)),
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .homeFeedPinchZoom(
                enabled = pinchEnabled,
                currentColumns = gridColumns.coerceAtLeast(1),
                bounds = pinchBounds,
                onColumnsChange = onColumnsChange,
                onGestureEnd = onPinchEnd,
            ),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppAssistChip(onClick = { onSelectSource(null) }, label = { AppText("全部") })
                AppAssistChip(
                    onClick = { onUnreadOnlyChange(!unreadOnly) },
                    label = { AppText(if (unreadOnly) "✓ 只看未读" else "只看未读") },
                )
                sources.forEach { source ->
                    AppAssistChip(
                        onClick = { onSelectSource(source.id) },
                        label = { AppText(source.title) },
                    )
                }
                AppTextButton(onClick = onRefresh, enabled = !loading) {
                    AppText(if (loading) "刷新中" else "刷新")
                }
            }
        }
        if (sources.isEmpty() && !loading) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppText("还没有可用的订阅源。到插件中心添加 RSS 或 Atom 地址，或启用已添加的订阅。")
                    AppButton(
                        onClick = onOpenPluginSettings,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        AppText("去添加订阅")
                    }
                }
            }
        }
        if (sources.isNotEmpty() && visibleItems.isEmpty() && !loading) {
            item(span = StaggeredGridItemSpan.FullLine) {
                AppText(if (unreadOnly) "没有未读文章" else "暂无文章，下拉后可刷新订阅。")
            }
        }
        items(visibleItems, key = { "${it.sourceId}:${it.id}" }) { item ->
            SubscriptionFeedCard(
                item = item,
                isRead = feedItemKey(item) in readKeys,
                onClick = { onOpen(item) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
        item(span = StaggeredGridItemSpan.FullLine) { Spacer(Modifier.height(28.dp)) }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SubscriptionFeedCard(
    item: ParsedFeedItem,
    isRead: Boolean,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    AppSurface(
        modifier = with(sharedTransitionScope) {
            Modifier
                .fillMaxWidth()
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(subscriptionSharedKey(item)),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> tween(360, easing = LinearEasing) },
                    clipInOverlayDuringTransition = OverlayClip(AppShapes.container(ContainerLevel.Card)),
                )
                .clip(AppShapes.container(ContainerLevel.Card))
                .clickable(onClick = onClick)
        },
        color = AppSurfaceTokens.cardContainer(),
        tonalElevation = 0.dp,
    ) {
        Column {
            if (!item.imageUrl.isNullOrBlank()) {
                FeedCoverImage(
                    url = item.imageUrl,
                    aspectRatio = item.coverAspectRatio,
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppText(
                    text = item.title.ifBlank { item.link },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isRead) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AppText(
                    text = listOf(item.sourceTitle, item.author, formatFeedAge(item.publishedEpochSec))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FeedCoverImage(
    url: String,
    aspectRatio: Float,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio.coerceIn(0.62f, 1.35f)),
        contentScale = ContentScale.Crop,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SubscriptionArticleScreen(
    item: ParsedFeedItem,
    scrollToTopRequestId: Int,
    contentPadding: PaddingValues,
    cachedBody: String?,
    isRead: Boolean,
    onReadChange: (Boolean) -> Unit,
    onFullBody: (String) -> Unit,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
    onOpenImages: (
        List<String>,
        Int,
        androidx.compose.ui.geometry.Rect?,
        Map<Int, androidx.compose.ui.geometry.Rect>
    ) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val articleScope = rememberCoroutineScope()
    val articleListState = rememberLazyListState()
    // Consume only new presses: opening another article must not replay an old request.
    var lastScrollToTopRequestId by remember(item.sourceId, item.id, item.link) {
        mutableIntStateOf(scrollToTopRequestId)
    }
    LaunchedEffect(scrollToTopRequestId) {
        if (scrollToTopRequestId != lastScrollToTopRequestId) {
            lastScrollToTopRequestId = scrollToTopRequestId
            // Match the recommendation tab's atomic return-to-top, regardless of distance.
            articleListState.scrollToItem(0)
        }
    }
    var articleHtml by remember(item.sourceId, item.id, item.link) {
        mutableStateOf(cachedBody ?: item.htmlContent.ifBlank { item.summary })
    }
    var loadingBody by remember(item.sourceId, item.id, item.link) { mutableStateOf(false) }
    var retryToken by remember(item.sourceId, item.id, item.link) { mutableIntStateOf(0) }
    var pureReading by rememberSaveable(item.sourceId, item.id, item.link) { mutableStateOf(false) }
    var readerControlsVisible by rememberSaveable(item.sourceId, item.id, item.link) { mutableStateOf(true) }
    val readingChromeVisible = !pureReading || readerControlsVisible
    val bottomBarVisible = LocalBottomBarVisible.current
    val setBottomBarVisible by rememberUpdatedState(LocalSetBottomBarVisible.current)
    DisposableEffect(pureReading) {
        val wasVisible = bottomBarVisible
        onDispose { if (pureReading) setBottomBarVisible(wasVisible) }
    }
    LaunchedEffect(pureReading, bottomBarVisible) {
        if (pureReading && bottomBarVisible) setBottomBarVisible(false)
    }
    BackHandler(enabled = pureReading) {
        pureReading = false
        readerControlsVisible = true
    }
    val wallpaperEnabled by remember(context) { SettingsManager.getSubscriptionArticleWallpaperEnabled(context) }
        .collectAsStateWithLifecycle(initialValue = false)
    val configuredWallpaperUri by remember(context) { SettingsManager.getHomeWallpaperUri(context) }
        .collectAsStateWithLifecycle(initialValue = "")
    val splashWallpaperUri by remember(context) { SettingsManager.getSplashWallpaperUri(context) }
        .collectAsStateWithLifecycle(initialValue = "")
    val wallpaperMode by remember(context) { SettingsManager.getHomeWallpaperEffectMode(context) }
        .collectAsStateWithLifecycle(initialValue = HomeWallpaperEffectMode.SOFT_BLUR)
    val wallpaperUri = resolveHomeWallpaperUri(configuredWallpaperUri, splashWallpaperUri)
    val articleBackground = MaterialTheme.colorScheme.surface
    val dataSaverActive = remember(context) { SettingsManager.isDataSaverActive(context) }
    val wallpaperAppearance = remember(wallpaperEnabled, wallpaperUri, wallpaperMode, articleBackground, dataSaverActive) {
        resolveHomeWallpaperBackdropAppearance(
            hasWallpaper = wallpaperEnabled && wallpaperUri.isNotBlank(),
            effectMode = if (wallpaperMode == HomeWallpaperEffectMode.OFF) HomeWallpaperEffectMode.SOFT_BLUR else wallpaperMode,
            isDarkTheme = articleBackground.luminance() < 0.5f,
            isDataSaverActive = dataSaverActive,
            globalWallpaper = true,
        )
    }
    var actionsExpanded by remember { mutableStateOf(false) }
    var fontScale by remember { mutableIntStateOf(1) }
    // 文章笔记（本地）：按文章链接为键，写笔记/摘录/摘要草稿共用一个编辑器。
    val noteRevision by ArticleNoteStore.revision.collectAsStateWithLifecycle()
    val savedArticleNote = remember(noteRevision, item.link) { ArticleNoteStore.get(context, item.link) }
    var noteEditorVisible by remember { mutableStateOf(false) }
    var notePrefillDocument by remember { mutableStateOf<VideoNoteEditorDocument?>(null) }
    var savingNote by remember { mutableStateOf(false) }
    var excerptMode by remember { mutableStateOf(false) }
    val pendingExcerpts = remember { mutableStateListOf<String>() }
    val articlePlainText = remember(articleHtml) { feedPlainText(articleHtml) }
    val summaryDraft = remember(item.title, articlePlainText) {
        buildArticleSummaryDraft(articleTitle = item.title, articlePlainText = articlePlainText)
    }
    LaunchedEffect(Unit) {
        fontScale = com.android.purebilibili.core.store.SettingsManager
            .getSubscriptionArticleFontScale(context).first()
    }
    val textScale = remember(fontScale) { floatArrayOf(0.88f, 1f, 1.18f)[fontScale.coerceIn(0, 2)] }
    LaunchedEffect(item.sourceId, item.id, item.link, retryToken) {
        if (retryToken == 0 && (cachedBody != null || !feedBodyNeedsRemoteFetch(item))) return@LaunchedEffect
        loadingBody = true
        fetchArticleHtml(item.link)
            .onSuccess { fetched ->
                if (com.android.purebilibili.core.plugin.feed.feedPlainText(fetched).length >
                    com.android.purebilibili.core.plugin.feed.feedPlainText(articleHtml).length
                ) {
                    articleHtml = fetched
                    onFullBody(fetched)
                }
            }
            .onFailure { Logger.w("SubscriptionFeed", "正文补全失败: ${it.message}") }
        loadingBody = false
    }
    val blocks = remember(articleHtml) {
        parseFeedHtml(articleHtml, item.link).ifEmpty {
            listOf(FeedBlock.Paragraph(listOf(FeedInline.Text(cleanFeedSummary(item.summary).ifBlank { item.title }))))
        }
    }
    val readingBlocks = blocks
    val onReadingLinkClick: (String) -> Unit = onOpenUrl
    val imageUrls = remember(blocks) {
        blocks.filterIsInstance<FeedBlock.Image>().map { it.url }.distinct()
    }
    val imageSourceRects = remember(item.sourceId, item.id, item.link) {
        mutableMapOf<Int, androidx.compose.ui.geometry.Rect>()
    }
    val layoutDirection = LocalLayoutDirection.current
    val articleTopBar: @Composable () -> Unit = {
        AppTopBar(
            title = "文章",
            navigationIcon = {
                AppIconButton(onClick = onBack) {
                    AppIcon(rememberAppBackIcon(), contentDescription = "返回")
                }
            },
            actions = {
                AppIconButton(onClick = {
                    notePrefillDocument = null
                    noteEditorVisible = true
                }) {
                    AppIcon(
                        Icons.Outlined.EditNote,
                        contentDescription = if (savedArticleNote != null) "编辑笔记" else "写笔记"
                    )
                }
                AppTextButton(
                    onClick = {
                        fontScale = (fontScale + 1) % 3
                        articleScope.launch {
                            com.android.purebilibili.core.store.SettingsManager
                                .setSubscriptionArticleFontScale(context, fontScale)
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    AppText("Aa")
                }
                AppIconButton(onClick = {
                    shareFeedArticle(context, item)
                }) {
                    AppIcon(Icons.Default.Share, contentDescription = "分享文章")
                }
                Box {
                    AppIconButton(onClick = { actionsExpanded = true }) {
                        AppIcon(Icons.Default.MoreVert, contentDescription = "阅读选项")
                    }
                    AppDropdownMenu(
                        expanded = actionsExpanded,
                        onDismissRequest = { actionsExpanded = false },
                    ) {
                        AppDropdownMenuItem(
                            text = { AppText(if (pureReading) "退出纯净模式" else "纯净模式（隐藏界面组件）") },
                            onClick = {
                                pureReading = !pureReading
                                readerControlsVisible = !pureReading
                                if (pureReading) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "已进入纯净模式，轻点正文空白处显示工具栏",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                actionsExpanded = false
                            },
                        )
                        AppDropdownMenuItem(
                            text = {
                                AppText(
                                    if (wallpaperUri.isBlank()) "正文壁纸（请先设置首页壁纸）"
                                    else if (wallpaperEnabled) "正文壁纸：开" else "正文壁纸：关"
                                )
                            },
                            enabled = wallpaperUri.isNotBlank(),
                            onClick = {
                                articleScope.launch {
                                    SettingsManager.setSubscriptionArticleWallpaperEnabled(context, !wallpaperEnabled)
                                }
                                actionsExpanded = false
                            },
                        )
                        AppDropdownMenuItem(
                            text = { AppText(if (excerptMode) "退出摘录模式" else "摘录模式（点选段落进笔记）") },
                            onClick = {
                                excerptMode = !excerptMode
                                if (!excerptMode) pendingExcerpts.clear()
                                actionsExpanded = false
                            },
                        )
                        AppDropdownMenuItem(
                            text = { AppText(if (isRead) "标未读" else "标已读") },
                            onClick = {
                                onReadChange(!isRead)
                                actionsExpanded = false
                            },
                        )
                        AppDropdownMenuItem(
                            text = { AppText("复制正文") },
                            onClick = {
                                copyFeedText(context, feedBlocksPlainText(readingBlocks).ifBlank { item.title })
                                actionsExpanded = false
                            },
                        )
                        if (isHttpFeedUrl(item.link)) {
                            AppDropdownMenuItem(
                                text = { AppText("重新读取正文") },
                                enabled = !loadingBody,
                                onClick = {
                                    retryToken += 1
                                    actionsExpanded = false
                                },
                            )
                            AppDropdownMenuItem(
                                text = { AppText("打开原文") },
                                onClick = {
                                    onOpenUrl(item.link)
                                    actionsExpanded = false
                                },
                            )
                        }
                    }
                }
            },
        )
    }
    AppSurface(
        modifier = with(sharedTransitionScope) {
            modifier
                .fillMaxSize()
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(subscriptionSharedKey(item)),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> tween(360, easing = LinearEasing) },
                    clipInOverlayDuringTransition = OverlayClip(AppShapes.container(ContainerLevel.Card)),
                )
        },
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalGlobalWallpaperBackdropVisible provides false) {
                ImmersiveAppScaffold(
                    containerColor = Color.Transparent,
                    topBarSurfaceColor = articleBackground,
                    topBar = {
                        if (readingChromeVisible) {
                            articleTopBar()
                        } else {
                            // Pure reading hides controls, while preserving status-bar chrome
                            // and its existing progressive/Haze source and render-mode policy.
                            Spacer(
                                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                            )
                        }
                    },
                ) { scaffoldPadding ->
                    // The scaffold records its content for both progressive blur and Haze.
                    // Capture the opaque wallpaper base with the article, not transparent
                    // text alone; chrome stays outside this source so it cannot sample itself.
                    Box(Modifier.fillMaxSize()) {
                        HomeWallpaperBackdrop(
                            wallpaperUri = wallpaperUri,
                            appearance = wallpaperAppearance,
                            baseColor = articleBackground,
                            isDataSaverActive = dataSaverActive,
                        )
                        LazyColumn(
                            state = articleListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (pureReading) Modifier.pointerInput(Unit) {
                                        detectTapGestures(onTap = { readerControlsVisible = !readerControlsVisible })
                                    } else Modifier
                                ),
                            contentPadding = PaddingValues(
                                start = contentPadding.calculateStartPadding(layoutDirection),
                                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                                end = contentPadding.calculateEndPadding(layoutDirection),
                                bottom = if (pureReading) scaffoldPadding.calculateBottomPadding() + 16.dp
                                    else contentPadding.calculateBottomPadding(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            item {
                                Column(
                                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AppText(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    if (!pureReading) {
                                        AppText(
                                            text = listOf(item.sourceTitle, item.author, formatFeedAge(item.publishedEpochSec))
                                                .filter { it.isNotBlank() }
                                                .joinToString(" · "),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (loadingBody) {
                                            FeedBodySkeleton()
                                        }
                                    }
                                }
                            }
                            lazyListItems(readingBlocks) { block ->
                                val excerptText = if (excerptMode) feedBlockExcerptText(block) else null
                                val excerptSelected = excerptText != null && excerptText in pendingExcerpts
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Box(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().then(
                                        if (excerptText != null) {
                                            Modifier
                                                .clip(MaterialTheme.shapes.medium)
                                                .background(
                                                    if (excerptSelected) MaterialTheme.colorScheme.primaryContainer
                                                    else Color.Transparent
                                                )
                                                .clickable {
                                                    if (excerptSelected) {
                                                        pendingExcerpts.remove(excerptText)
                                                    } else {
                                                        pendingExcerpts.add(excerptText)
                                                    }
                                                }
                                        } else {
                                            Modifier
                                        }
                                    )) {
                                        when (block) {
                                            is FeedBlock.Heading -> SelectionContainer {
                                                FeedInlineText(
                                                    block.inlines,
                                                    fontScale = textScale,
                                                    onLinkClick = onReadingLinkClick,
                                                    style = when (block.level) {
                                                        1 -> MaterialTheme.typography.headlineSmall
                                                        2 -> MaterialTheme.typography.titleLarge
                                                        else -> MaterialTheme.typography.titleMedium
                                                    },
                                                )
                                            }
                                            is FeedBlock.Paragraph -> SelectionContainer {
                                                FeedInlineText(block.inlines, fontScale = textScale, onLinkClick = onReadingLinkClick)
                                            }
                                            is FeedBlock.Quote -> SelectionContainer {
                                                FeedInlineText(
                                                    block.inlines,
                                                    modifier = Modifier.padding(start = 12.dp),
                                                    italic = true,
                                                    fontScale = textScale,
                                                    onLinkClick = onReadingLinkClick,
                                                )
                                            }
                                            is FeedBlock.Code -> SelectionContainer {
                                                AppText(
                                                    block.text,
                                                    modifier = Modifier.fillMaxWidth()
                                                        .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                                                        .horizontalScroll(rememberScrollState())
                                                        .padding(12.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                            is FeedBlock.Image -> FeedArticleImage(
                                                url = block.url,
                                                alt = block.alt,
                                                pageIndex = imageUrls.indexOf(block.url).coerceAtLeast(0),
                                                galleryRects = imageSourceRects,
                                                onClick = { rect ->
                                                    val index = imageUrls.indexOf(block.url).coerceAtLeast(0)
                                                    onOpenImages(imageUrls, index, rect, imageSourceRects.toMap())
                                                },
                                            )
                                            is FeedBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                block.items.forEach { line ->
                                                    Row {
                                                        AppText("• ")
                                                        FeedInlineText(line, modifier = Modifier.weight(1f), fontScale = textScale, onLinkClick = onReadingLinkClick)
                                                    }
                                                }
                                            }
                                            is FeedBlock.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                block.items.forEachIndexed { index, line ->
                                                    Row {
                                                        AppText("${index + 1}. ")
                                                        FeedInlineText(line, modifier = Modifier.weight(1f), fontScale = textScale, onLinkClick = onReadingLinkClick)
                                                    }
                                                }
                                            }
                                            is FeedBlock.EmbeddedLink -> AppTextButton(
                                                onClick = { onOpenUrl(block.url) },
                                                modifier = Modifier.heightIn(min = 48.dp),
                                            ) { AppText(block.title) }
                                        }
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(28.dp)) }
                        }
                    }
                }
            }
            if (excerptMode) {
                // 摘录提示按钮悬浮于文章层之上：需要让出底栏（含导航栏）与音频播放条的
                // 高度，否则会被两者盖住。64dp ≈ 音频横条自身高度。
                val excerptBottomClearance = maxOf(
                    32.dp,
                    LocalBottomBarContentPadding.current + 64.dp,
                )
                AppButton(
                    onClick = {
                        notePrefillDocument = buildArticleExcerptDocument(
                            articleTitle = item.title,
                            excerpts = pendingExcerpts.toList()
                        )
                        noteEditorVisible = true
                        excerptMode = false
                        pendingExcerpts.clear()
                    },
                    enabled = pendingExcerpts.isNotEmpty(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = excerptBottomClearance),
                ) {
                    AppText(
                        if (pendingExcerpts.isEmpty()) "摘录模式：点选段落加入笔记"
                        else "已选 ${pendingExcerpts.size} 段 · 加入笔记"
                    )
                }
            }
            ArticleNoteEditorSheet(
                visible = noteEditorVisible,
                articleTitle = item.title,
                sourceTitle = item.sourceTitle,
                savedNoteTitle = savedArticleNote?.noteTitle,
                savedNoteContent = savedArticleNote?.content,
                prefillDocument = notePrefillDocument,
                summaryDraft = summaryDraft,
                saving = savingNote,
                onDismiss = {
                    noteEditorVisible = false
                    notePrefillDocument = null
                },
                onSave = { document ->
                    savingNote = true
                    articleScope.launch {
                        val encoded = VideoNoteContentCodec.encode(document)
                        withContext(Dispatchers.IO) {
                            ArticleNoteStore.save(
                                context,
                                SavedArticleNote(
                                    link = item.link,
                                    articleTitle = item.title,
                                    sourceTitle = item.sourceTitle,
                                    noteTitle = document.title,
                                    content = encoded.content,
                                    updatedAtEpochMs = System.currentTimeMillis()
                                )
                            )
                        }
                        savingNote = false
                        noteEditorVisible = false
                        notePrefillDocument = null
                        android.widget.Toast.makeText(context, "笔记已保存", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onDelete = {
                    articleScope.launch {
                        withContext(Dispatchers.IO) { ArticleNoteStore.remove(context, item.link) }
                        noteEditorVisible = false
                        android.widget.Toast.makeText(context, "笔记已删除", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

@Composable
private fun FeedInlineText(
    inlines: List<FeedInline>,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    italic: Boolean = false,
    fontScale: Float = 1f,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val linkInteractionListener = remember(onLinkClick) {
        onLinkClick?.let { handler ->
            androidx.compose.ui.text.LinkInteractionListener { link ->
                (link as? LinkAnnotation.Url)?.url?.let(handler)
            }
        }
    }
    val annotated = buildAnnotatedString {
        inlines.forEach { inline ->
            when (inline) {
                is FeedInline.Text -> withStyle(
                    SpanStyle(
                        fontWeight = if (inline.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (inline.italic || italic) FontStyle.Italic else FontStyle.Normal,
                    )
                ) {
                    append(inline.text)
                }
                is FeedInline.Link -> withLink(
                    LinkAnnotation.Url(
                        inline.url,
                        TextLinkStyles(SpanStyle(color = linkColor)),
                        linkInteractionListener,
                    )
                ) {
                    append(inline.text)
                }
            }
        }
    }
    val scaledStyle = if (fontScale != 1f) {
        style.copy(fontSize = style.fontSize * fontScale, lineHeight = style.lineHeight * fontScale)
    } else {
        style
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = scaledStyle.copy(lineHeight = scaledStyle.fontSize * 1.55f),
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun FeedBodySkeleton(modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(0.92f, 1f, 0.78f, 1f, 0.64f).forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .background(barColor, MaterialTheme.shapes.small),
            )
        }
    }
}

@Composable
private fun FeedArticleImage(
    url: String,
    alt: String,
    pageIndex: Int,
    galleryRects: MutableMap<Int, androidx.compose.ui.geometry.Rect>,
    modifier: Modifier = Modifier,
    onClick: (androidx.compose.ui.geometry.Rect?) -> Unit,
) {
    var failed by remember(url) { mutableStateOf(false) }
    val sourceRect = rememberImagePreviewSourceRect()
    val sourceHidden = isImagePreviewSourceHidden(sourceRect.value)
    if (failed) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            AppText(alt.ifBlank { "图片无法显示" })
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = alt.ifBlank { "查看图片" },
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(MaterialTheme.shapes.medium)
                .alpha(if (sourceHidden) 0f else 1f)
                .imagePreviewSourceBounds(sourceRect)
                .onGloballyPositioned { coordinates ->
                    galleryRects[pageIndex] = coordinates.boundsInWindow()
                }
                .clickable(
                    interactionSource = null,
                    indication = null,
                    enabled = !sourceHidden,
                ) {
                    prepareImagePreviewSourceTransition(sourceRect.value)
                    onClick(sourceRect.value)
                },
            contentScale = ContentScale.Fit,
            onError = { failed = true },
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
private fun subscriptionSharedKey(item: ParsedFeedItem): String = "subscription:${item.sourceId}:${item.id}"

private fun feedBlocksPlainText(blocks: List<FeedBlock>): String {
    return blocks.joinToString("\n\n") { block ->
        when (block) {
            is FeedBlock.Heading -> inlinePlainText(block.inlines)
            is FeedBlock.Paragraph -> inlinePlainText(block.inlines)
            is FeedBlock.Quote -> inlinePlainText(block.inlines)
            is FeedBlock.Code -> block.text
            is FeedBlock.BulletList -> block.items.joinToString("\n") { "• ${inlinePlainText(it)}" }
            is FeedBlock.NumberedList -> block.items.mapIndexed { index, line ->
                "${index + 1}. ${inlinePlainText(line)}"
            }.joinToString("\n")
            is FeedBlock.Image -> block.alt
            is FeedBlock.EmbeddedLink -> "${block.title} ${block.url}"
        }
    }.trim()
}

private fun inlinePlainText(inlines: List<FeedInline>): String {
    return inlines.joinToString("") { inline ->
        when (inline) {
            is FeedInline.Text -> inline.text
            is FeedInline.Link -> inline.text
        }
    }
}

private fun copyFeedText(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("订阅正文", text))
    android.widget.Toast.makeText(context, "已复制正文", android.widget.Toast.LENGTH_SHORT).show()
}

internal fun formatFeedAge(epochSec: Long?, nowSec: Long = System.currentTimeMillis() / 1000): String {
    if (epochSec == null || epochSec <= 0L) return ""
    val delta = (nowSec - epochSec).coerceAtLeast(0L)
    return when {
        delta < 60 -> "刚刚"
        delta < 3600 -> "${delta / 60}分钟前"
        delta < 86_400 -> "${delta / 3600}小时前"
        delta < 86_400 * 30 -> "${delta / 86_400}天前"
        else -> Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }
}

private fun shareFeedArticle(context: android.content.Context, item: ParsedFeedItem) {
    val shareText = buildSubscriptionArticleShareText(item.title, item.sourceTitle, item.link)
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, item.title)
        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "分享文章"))
}
