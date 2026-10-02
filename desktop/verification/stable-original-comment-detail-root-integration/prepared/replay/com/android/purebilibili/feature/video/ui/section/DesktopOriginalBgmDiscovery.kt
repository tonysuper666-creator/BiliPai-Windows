package com.android.purebilibili.feature.video.ui.section
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.LocalPlatformContext as LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.android.purebilibili.core.ui.*
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.core.store.HomeFeedCardStyle
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.home.components.cards.ElegantVideoCard
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
import com.android.purebilibili.feature.video.ui.components.VideoCardSkeleton
import com.android.purebilibili.feature.video.ui.components.ShimmerContainer
import com.android.purebilibili.feature.video.ui.components.SkeletonBox
import kotlinx.coroutines.delay

private const val BGM_DISCOVERY_LOAD_DELAY_MS = 420L
private const val BGM_RECOMMEND_PAGE_SIZE = 5
private const val BGM_RECOMMEND_ROW_START_INDEX = 4
private const val AUDIO_NOW_PLAYING_BAR_CLEARANCE_DP = 64
private val BGM_DETAIL_CARD_HEIGHT = 168.dp

@Composable
internal fun DesktopOriginalInlineBgmSection(
    bgmList: List<BgmInfo>,
    onBgmClick: (BgmInfo) -> Unit = {},
    onRelatedVideoClick: (String, Long) -> Unit = { _, _ -> }
) {
    if (bgmList.isEmpty()) return

    var showSheet by remember(bgmList.map(BgmInfo::musicId)) { mutableStateOf(false) }
    val leadSong = bgmList.first()
    val headerText = remember(bgmList, leadSong) {
        buildString {
            append("发现音乐")
            val title = leadSong.musicTitle.ifBlank { "未知音乐" }
            append("《")
            append(title)
            append("》")
            if (bgmList.size > 1) {
                append("等")
                append(bgmList.size)
                append("首音乐")
            }
            // PiliPlus 式单行：艺人内联，避免双行卡片
            val actor = leadSong.actor.takeIf { it.isNotBlank() && bgmList.size == 1 }
            if (actor != null) {
                append(" · ")
                append(actor)
            }
        }
    }

    BgmInfoRow(
        title = headerText,
        subtitle = null,
        showIndicator = false,
        onClick = {
            if (bgmList.size == 1) onBgmClick(leadSong) else showSheet = true
        }
    )

    if (showSheet) {
        BgmSelectionSheet(
            title = "发现音乐",
            bgmList = bgmList,
            onDismiss = { showSheet = false },
            onBgmClick = { bgm ->
                showSheet = false
                onBgmClick(bgm)
            },
            onRelatedVideoClick = onRelatedVideoClick
        )
    }
}

/**
 * [新增] 背景音乐信息行
 */
@Composable
fun BgmInfoRow(
    title: String,
    subtitle: String? = null,
    expanded: Boolean = false,
    showIndicator: Boolean = false,
    onClick: () -> Unit = {}
) {
    val indicatorRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "BgmExpandIndicator"
    )

    AppSurface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        shape = AppShapes.container(ContainerLevel.Chip),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = "BGM",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    AppText(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (showIndicator) {
                AppIcon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(indicatorRotation)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BgmSelectionSheet(
    title: String,
    bgmList: List<BgmInfo>,
    onDismiss: () -> Unit,
    onBgmClick: (BgmInfo) -> Unit,
    onRelatedVideoClick: (String, Long) -> Unit
) {
    val requests = com.bilipai.desktop.audio.LocalDesktopBgmDiscoveryRequests.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val itemKeys = remember(bgmList) {
        bgmList.mapIndexed(::resolveBgmItemKey)
    }
    val aid = remember(bgmList) {
        bgmList.firstOrNull()?.jumpUrl?.let { resolveQueryLongParam(it, "aid") } ?: 0L
    }
    val cid = remember(bgmList) {
        bgmList.firstOrNull()?.jumpUrl?.let { resolveQueryLongParam(it, "cid") } ?: 0L
    }
    val itemStateByKey = remember(itemKeys) {
        mutableStateMapOf<String, BgmSheetItemState>().also { map ->
            itemKeys.forEach { key -> map[key] = BgmSheetItemState() }
        }
    }
    val listState = rememberLazyListState()
    var selectedItemKey by remember(itemKeys) {
        mutableStateOf(itemKeys.firstOrNull().orEmpty())
    }
    val selectedIndex = remember(selectedItemKey, itemKeys) {
        itemKeys.indexOf(selectedItemKey).takeIf { it >= 0 } ?: 0
    }
    val selectedBgm = bgmList.getOrElse(selectedIndex) { bgmList.first() }
    val selectedData = itemStateByKey[selectedItemKey] ?: BgmSheetItemState()
    val shouldShowDetailSkeleton = remember(selectedData) {
        shouldShowBgmDetailSkeleton(selectedData)
    }

    LaunchedEffect(selectedItemKey, selectedBgm.musicId, aid, cid) {
        if (!shouldLoadBgmDiscoveryItem(selectedData, selectedBgm.musicId, aid, cid)) return@LaunchedEffect

        delay(BGM_DISCOVERY_LOAD_DELAY_MS)
        itemStateByKey[selectedItemKey] = selectedData.copy(
            status = BgmDiscoveryLoadStatus.Loading,
            errorMessage = null,
            isAppendingRecommendations = false
        )

        val detail = requests.getBgmDetail(
            musicId = selectedBgm.musicId,
            aid = aid,
            cid = cid
        )
        val recommendedVideos = requests.getBgmRecommendVideos(
            musicId = selectedBgm.musicId,
            aid = aid,
            cid = cid,
            page = 1,
            pageSize = BGM_RECOMMEND_PAGE_SIZE
        )
        val loadedDetail = detail.getOrNull()
        val loadedVideos = recommendedVideos.getOrDefault(emptyList())
        val errorMessage = detail.exceptionOrNull()?.message
            ?: recommendedVideos.exceptionOrNull()?.message

        itemStateByKey[selectedItemKey] = BgmSheetItemState(
            status = if (detail.isSuccess || recommendedVideos.isSuccess) {
                BgmDiscoveryLoadStatus.Loaded
            } else {
                BgmDiscoveryLoadStatus.Error
            },
            detail = loadedDetail,
            recommendedVideos = loadedVideos,
            errorMessage = errorMessage,
            nextRecommendPage = if (recommendedVideos.isSuccess) 2 else 1,
            hasMoreRecommendations = if (recommendedVideos.isSuccess) {
                loadedVideos.size >= BGM_RECOMMEND_PAGE_SIZE
            } else {
                true
            },
            isAppendingRecommendations = false
        )
    }

    val selectedMusicId = selectedBgm.musicId
    val selectedStatLine = remember(selectedData.detail) {
        resolveBgmStatLine(selectedData.detail)
    }
    val shouldShowInitialRecommendationPlaceholders = remember(selectedData) {
        shouldShowInitialBgmRecommendationPlaceholders(selectedData)
    }
    val recommendedVideoRows = remember(selectedData.recommendedVideos) {
        selectedData.recommendedVideos.chunked(2)
    }

    com.android.purebilibili.core.ui.AppModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.68f),
            // 底部额外预留系统导航栏与悬浮播放条的高度，
            // 避免「使用该音乐的视频」最后一张卡片被遮挡。
            contentPadding = PaddingValues(
                bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding() + AUDIO_NOW_PLAYING_BAR_CLEARANCE_DP.dp
            )
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    AppIconButton(onClick = onDismiss) {
                        AppIcon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "关闭",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (bgmList.size > 1) {
                item {
                    BgmSelectionStrip(
                        bgmList = bgmList,
                        itemKeys = itemKeys,
                        selectedItemKey = selectedItemKey,
                        onSelect = { selectedItemKey = it }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                BgmDetailCard(
                    bgm = selectedBgm,
                    detail = selectedData.detail,
                    isLoading = shouldShowDetailSkeleton,
                    statLine = selectedStatLine,
                    onOpenMusic = { onBgmClick(selectedBgm) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                BgmDiscoveryRelatedHeader()
            }

            if (shouldShowInitialRecommendationPlaceholders) {
                repeat((BGM_RECOMMEND_PAGE_SIZE + 1) / 2) { placeholderRowIndex ->
                    item(key = "bgm-recommend-placeholder-row-$placeholderRowIndex") {
                        BgmRecommendVideoSkeletonRow(indexBase = placeholderRowIndex * 2)
                    }
                }
            } else if (selectedData.recommendedVideos.isNotEmpty()) {
                itemsIndexed(
                    items = recommendedVideoRows,
                    key = { rowIndex, videos -> resolveBgmRecommendRowKey(rowIndex, videos) }
                ) { rowIndex, rowVideos ->
                    BgmRecommendVideoCardRow(
                        rowVideos = rowVideos,
                        rowIndex = rowIndex,
                        onVideoClick = { video ->
                            onDismiss()
                            onRelatedVideoClick(
                                video.bvid,
                                video.cid
                            )
                        }
                    )
                }
            } else if (selectedData.errorMessage?.isNotBlank() == true) {
                item(key = "bgm-recommend-error") {
                    AppText(
                        text = selectedData.errorMessage.takeIf { it.isNotBlank() } ?: "音乐推荐加载失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                item(key = "bgm-recommend-empty") {
                    AppText(
                        text = "暂无相关视频",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            if (selectedData.isAppendingRecommendations) {
                item(key = "bgm-recommend-loading-more") {
                    BgmRecommendVideoSkeletonRow(indexBase = recommendedVideoRows.size * 2)
                }
            }

            if (selectedMusicId.isBlank() || aid <= 0L || cid <= 0L) {
                item {
                    AppText(
                        text = "暂时无法加载更多音乐信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }

    LaunchedEffect(selectedItemKey) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(selectedItemKey, selectedMusicId, aid, cid) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to layoutInfo.totalItemsCount
        }.collect { (lastVisibleIndex, _) ->
            val currentState = itemStateByKey[selectedItemKey] ?: return@collect
            if (!shouldLoadMoreBgmRecommendations(currentState, selectedMusicId, aid, cid)) return@collect

            val lastRecommendationIndex =
                resolveBgmRecommendRowItemIndex((currentState.recommendedVideos.size - 1) / 2)
            if (lastVisibleIndex < lastRecommendationIndex - 1) return@collect

            loadMoreBgmRecommendations(
                itemStateByKey = itemStateByKey,
                itemKey = selectedItemKey,
                bgm = selectedBgm,
                aid = aid,
                cid = cid,
                requests = requests
            )
        }
    }
}

@Composable
private fun BgmDiscoveryRelatedHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        AppText(
            text = "使用该音乐的视频",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun BgmRecommendVideoCardRow(
    rowVideos: List<BgmRecommendVideo>,
    rowIndex: Int,
    onVideoClick: (BgmRecommendVideo) -> Unit
) {
    val preferences = checkNotNull(com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences.current)
    val homeSettings by preferences.settings.collectAsState(preferences.initialSettings())
    val homeFeedCardStyle = homeSettings.homeFeedCardStyle
    val cardLayout = remember(homeFeedCardStyle) {
        resolveHomeFeedCardLayout(homeFeedCardStyle)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = cardLayout.outerPaddingDp.dp),
        horizontalArrangement = Arrangement.spacedBy(cardLayout.itemSpacingDp.dp)
    ) {
        rowVideos.forEachIndexed { columnIndex, video ->
            ElegantVideoCard(
                video = bgmRecommendVideoToVideoItem(video),
                index = rowIndex * 2 + columnIndex,
                animationEnabled = false,
                showPublishTime = true,
                isDataSaverActive = true,
                preferLowQualityCover = true,
                coverAspectRatio = cardLayout.coverAspectRatio,
                compactMetadata = cardLayout.compactMetadata,
                modifier = Modifier.weight(1f),
                onClick = { _, _ ->
                    onVideoClick(video)
                }
            )
        }
        if (rowVideos.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun BgmRecommendVideoSkeletonRow(
    indexBase: Int
) {
    val preferences = checkNotNull(com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences.current)
    val homeSettings by preferences.settings.collectAsState(preferences.initialSettings())
    val homeFeedCardStyle = homeSettings.homeFeedCardStyle
    val cardLayout = remember(homeFeedCardStyle) {
        resolveHomeFeedCardLayout(homeFeedCardStyle)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = cardLayout.outerPaddingDp.dp),
        horizontalArrangement = Arrangement.spacedBy(cardLayout.itemSpacingDp.dp)
    ) {
        VideoCardSkeleton(
            modifier = Modifier.weight(1f),
            index = indexBase,
            coverAspectRatio = cardLayout.coverAspectRatio
        )
        VideoCardSkeleton(
            modifier = Modifier.weight(1f),
            index = indexBase + 1,
            coverAspectRatio = cardLayout.coverAspectRatio
        )
    }
}

private fun bgmRecommendVideoToVideoItem(video: BgmRecommendVideo): VideoItem {
    return VideoItem(
        id = if (video.aid > 0L) video.aid else video.cid,
        bvid = video.bvid,
        aid = video.aid,
        cid = video.cid,
        title = video.title,
        pic = video.cover,
        owner = Owner(
            mid = video.mid,
            name = video.upNickName
        ),
        stat = Stat(
            view = video.play,
            danmaku = 0
        ),
        duration = video.duration
    )
}

@Composable
private fun BgmSelectionStrip(
    bgmList: List<BgmInfo>,
    itemKeys: List<String>,
    selectedItemKey: String,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coverSizePx = remember(density) { with(density) { 76.dp.roundToPx() } }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(bgmList, key = { index, bgm -> itemKeys.getOrElse(index) { resolveBgmItemKey(index, bgm) } }) { index, bgm ->
            val itemKey = itemKeys.getOrElse(index) { resolveBgmItemKey(index, bgm) }
            val selected = itemKey == selectedItemKey
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .clickable { onSelect(itemKey) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(AppShapes.container(ContainerLevel.Floating))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            }
                        )
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
                            },
                            shape = AppShapes.container(ContainerLevel.Floating)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (bgm.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(FormatUtils.fixImageUrl(bgm.coverUrl))
                                .size(coverSizePx, coverSizePx)
                                .crossfade(false)
                                .build(),
                            contentDescription = bgm.musicTitle,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        AppIcon(
                            imageVector = Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                AppText(
                    text = bgm.musicTitle.ifBlank { "未知音乐" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BgmDetailCard(
    bgm: BgmInfo,
    detail: BgmDetailData?,
    isLoading: Boolean,
    statLine: String?,
    onOpenMusic: () -> Unit
) {
    val scoreText = remember(detail) { resolveBgmScoreText(detail) }
    val displayStatLine = remember(statLine) { statLine ?: resolveUnavailableBgmStatLine() }
    val commentText = remember(detail) { resolveBgmCommentText(detail) }
    val description = remember(detail, bgm) {
        bgm.actor.takeIf { it.isNotBlank() }
            ?: detail?.originArtist?.takeIf { it.isNotBlank() }
            ?: "点击查看这首音乐的完整详情"
    }

    AppSurface(
        onClick = onOpenMusic,
        enabled = !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = AppShapes.container(ContainerLevel.Floating),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        if (isLoading) {
            BgmDetailCardSkeleton()
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BGM_DETAIL_CARD_HEIGHT)
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                BgmDetailCover(
                    coverUrl = detail?.mvCover.orEmpty().ifBlank { bgm.coverUrl },
                    title = detail?.musicTitle.orEmpty().ifBlank { bgm.musicTitle }
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Top
                ) {
                    AppText(
                        text = detail?.musicTitle.orEmpty().ifBlank { bgm.musicTitle.ifBlank { "未知音乐" } },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(
                            imageVector = Icons.Outlined.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        AppText(
                            text = scoreText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    AppText(
                        text = displayStatLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    AppText(
                        text = commentText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AppText(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.weight(1f, fill = true))
                    AppText(
                        text = "打开音乐详情",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable(onClick = onOpenMusic)
                    )
                }
            }
        }
    }
}

@Composable
private fun BgmDetailCardSkeleton() {
    ShimmerContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BGM_DETAIL_CARD_HEIGHT)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonBox(
                modifier = Modifier.size(112.dp),
                height = 112.dp,
                cornerRadius = 22.dp
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(
                    modifier = Modifier.fillMaxWidth(0.72f),
                    height = 20.dp,
                    cornerRadius = 10.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonBox(
                    modifier = Modifier.fillMaxWidth(0.46f),
                    height = 16.dp,
                    cornerRadius = 8.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonBox(
                    modifier = Modifier.fillMaxWidth(0.88f),
                    height = 14.dp,
                    cornerRadius = 7.dp
                )
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBox(
                    modifier = Modifier.fillMaxWidth(0.34f),
                    height = 14.dp,
                    cornerRadius = 7.dp
                )
                Spacer(modifier = Modifier.height(10.dp))
                SkeletonBox(
                    modifier = Modifier.fillMaxWidth(0.94f),
                    height = 14.dp,
                    cornerRadius = 7.dp
                )
                Spacer(modifier = Modifier.height(6.dp))
                SkeletonBox(
                    modifier = Modifier.fillMaxWidth(0.66f),
                    height = 14.dp,
                    cornerRadius = 7.dp
                )
                Spacer(modifier = Modifier.height(12.dp))
                SkeletonBox(
                    modifier = Modifier.width(84.dp),
                    height = 14.dp,
                    cornerRadius = 7.dp
                )
            }
        }
    }
}

@Composable
private fun BgmDetailCover(
    coverUrl: String,
    title: String
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val coverSizePx = remember(density) { with(density) { 112.dp.roundToPx() } }
    Box(
        modifier = Modifier
            .size(width = 112.dp, height = 112.dp)
            .clip(AppShapes.container(ContainerLevel.Floating))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(FormatUtils.fixImageUrl(coverUrl))
                    .size(coverSizePx, coverSizePx)
                    .crossfade(false)
                    .build(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            AppIcon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(34.dp)
            )
        }
    }
}

private fun resolveBgmScoreText(detail: BgmDetailData?): String {
    detail ?: return "音乐信息"
    return when {
        detail.musicHot > 0L -> "最新热度 ${FormatUtils.formatStat(detail.musicHot)}"
        else -> "播放 ${FormatUtils.formatStat(detail.listenPv)}"
    }
}

private fun resolveBgmStatLine(detail: BgmDetailData?): String? {
    detail ?: return null
    return listOf(
        "${FormatUtils.formatStat(detail.listenPv.coerceAtLeast(0L))}播放",
        "${FormatUtils.formatStat(detail.wishCount.coerceAtLeast(0).toLong())}想听",
        "${FormatUtils.formatStat(detail.musicShares.coerceAtLeast(0).toLong())}分享"
    ).joinToString(" · ")
}

private fun resolveUnavailableBgmStatLine(): String {
    return "--播放 · --想听 · --分享"
}

private fun resolveBgmCommentText(detail: BgmDetailData?): String {
    detail ?: return "--评论"
    return "${FormatUtils.formatStat((detail.musicComment?.nums ?: 0).coerceAtLeast(0).toLong())}评论"
}

private fun resolveBgmRecommendRowKey(
    rowIndex: Int,
    videos: List<BgmRecommendVideo>
): String {
    val stablePart = videos.joinToString(separator = "|") { video ->
        video.bvid.ifBlank { "${video.aid}:${video.cid}:${video.title}" }
    }
    return "bgm-recommend-row-$rowIndex:$stablePart"
}

private fun resolveBgmRecommendRowItemIndex(rowIndex: Int): Int {
    return BGM_RECOMMEND_ROW_START_INDEX + rowIndex
}

private fun resolveQueryLongParam(url: String, key: String): Long {
    return com.bilipai.desktop.audio.desktopBgmQueryParameter(url, key)?.toLongOrNull() ?: 0L
}

internal enum class BgmDiscoveryLoadStatus {
    Idle,
    Loading,
    Loaded,
    Error
}

internal data class BgmSheetItemState(
    val status: BgmDiscoveryLoadStatus = BgmDiscoveryLoadStatus.Idle,
    val detail: BgmDetailData? = null,
    val recommendedVideos: List<BgmRecommendVideo> = emptyList(),
    val errorMessage: String? = null,
    val nextRecommendPage: Int = 2,
    val hasMoreRecommendations: Boolean = true,
    val isAppendingRecommendations: Boolean = false
)

internal fun resolveBgmItemKey(index: Int, bgm: BgmInfo): String {
    val stablePart = bgm.musicId
        .trim()
        .ifBlank {
            bgm.jumpUrl.trim().ifBlank {
                bgm.musicTitle.trim().ifBlank { "unknown" }
            }
        }
    return "$index:$stablePart"
}

internal fun shouldLoadBgmDiscoveryItem(
    state: BgmSheetItemState,
    musicId: String,
    aid: Long,
    cid: Long
): Boolean {
    if (musicId.isBlank() || aid <= 0L || cid <= 0L) return false
    return state.status == BgmDiscoveryLoadStatus.Idle ||
        state.status == BgmDiscoveryLoadStatus.Error
}

internal fun shouldShowBgmDetailSkeleton(
    state: BgmSheetItemState
): Boolean {
    return state.status != BgmDiscoveryLoadStatus.Loaded && state.detail == null
}

internal fun shouldShowInitialBgmRecommendationPlaceholders(
    state: BgmSheetItemState
): Boolean {
    return state.recommendedVideos.isEmpty() &&
        state.status != BgmDiscoveryLoadStatus.Error
}

internal fun shouldLoadMoreBgmRecommendations(
    state: BgmSheetItemState,
    musicId: String,
    aid: Long,
    cid: Long
): Boolean {
    if (musicId.isBlank() || aid <= 0L || cid <= 0L) return false
    if (state.status == BgmDiscoveryLoadStatus.Loading) return false
    if (state.isAppendingRecommendations) return false
    if (!state.hasMoreRecommendations) return false
    return state.recommendedVideos.isNotEmpty()
}

private suspend fun loadMoreBgmRecommendations(
    itemStateByKey: MutableMap<String, BgmSheetItemState>,
    itemKey: String,
    bgm: BgmInfo,
    aid: Long,
    cid: Long,
    requests: com.bilipai.desktop.audio.DesktopBgmDiscoveryRequests,
) {
    val currentState = itemStateByKey[itemKey] ?: return
    if (!shouldLoadMoreBgmRecommendations(currentState, bgm.musicId, aid, cid)) return

    itemStateByKey[itemKey] = currentState.copy(
        isAppendingRecommendations = true,
        errorMessage = null
    )

    val result = requests.getBgmRecommendVideos(
        musicId = bgm.musicId,
        aid = aid,
        cid = cid,
        page = currentState.nextRecommendPage,
        pageSize = BGM_RECOMMEND_PAGE_SIZE
    )
    val incomingVideos = result.getOrDefault(emptyList())
    val mergedVideos = mergeBgmRecommendedVideos(
        existing = currentState.recommendedVideos,
        incoming = incomingVideos
    )
    val appendedCount = mergedVideos.size - currentState.recommendedVideos.size

    val latestState = itemStateByKey[itemKey] ?: currentState
    itemStateByKey[itemKey] = latestState.copy(
        recommendedVideos = if (result.isSuccess) mergedVideos else latestState.recommendedVideos,
        errorMessage = result.exceptionOrNull()?.message,
        nextRecommendPage = if (result.isSuccess && appendedCount > 0) {
            currentState.nextRecommendPage + 1
        } else {
            currentState.nextRecommendPage
        },
        hasMoreRecommendations = if (result.isSuccess) {
            incomingVideos.size >= BGM_RECOMMEND_PAGE_SIZE && appendedCount > 0
        } else {
            latestState.hasMoreRecommendations
        },
        isAppendingRecommendations = false
    )
}

private fun mergeBgmRecommendedVideos(
    existing: List<BgmRecommendVideo>,
    incoming: List<BgmRecommendVideo>
): List<BgmRecommendVideo> {
    if (incoming.isEmpty()) return existing
    val seenKeys = existing.mapTo(mutableSetOf()) { video ->
        if (video.bvid.isNotBlank()) video.bvid else "${video.aid}:${video.cid}"
    }
    val appended = incoming.filter { video ->
        val key = if (video.bvid.isNotBlank()) video.bvid else "${video.aid}:${video.cid}"
        seenKeys.add(key)
    }
    return existing + appended
}
