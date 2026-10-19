package com.android.purebilibili.feature.video.ui.components

import com.android.purebilibili.core.ui.AppContentColors

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppLazyColumn
import com.android.purebilibili.core.ui.components.AppSingleChoiceRow
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.util.FormatUtils
import com.android.purebilibili.data.model.response.Page
import com.android.purebilibili.feature.home.components.cards.VideoCardCoverDurationText
import kotlinx.coroutines.launch

@Composable
internal fun PageCoverList(
    pages: List<Page>,
    visiblePageIndices: List<Int>,
    currentPageIndex: Int,
    coverUrl: String,
    onPageSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    blockParentVerticalScroll: Boolean = false,
    bottomContentPaddingDp: Int = 12,
    onResetFilters: () -> Unit = {},
) {
    //  首次组合即定位当前分 P：底部面板展开过程中高度未定，初始位置比后续 scrollToItem 更可靠
    val initialPosition = remember(visiblePageIndices) {
        visiblePageIndices.indexOf(currentPageIndex).coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPosition)
    val scope = rememberCoroutineScope()
    var descending by rememberSaveable(pages.firstOrNull()?.cid) { mutableStateOf(false) }
    val orderedIndices = remember(visiblePageIndices, descending) {
        if (descending) visiblePageIndices.asReversed() else visiblePageIndices
    }
    val currentPosition = orderedIndices.indexOf(currentPageIndex)
    val nestedScrollConnection = rememberModalChildScrollConnection()
    LaunchedEffect(orderedIndices, currentPageIndex) {
        // Keep original page indices when sorting / filtering so selecting a row plays the right P.
        if (currentPosition >= 0) listState.scrollToItem(currentPosition)
        else if (orderedIndices.isNotEmpty()) listState.scrollToItem(0)
    }
    fun scrollTo(index: Int) {
        if (index !in orderedIndices.indices) return
        scope.launch { listState.animateScrollToItem(index) }
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EpisodeListNavigationActions(
                onTopClick = { scrollTo(0) },
                onBottomClick = { scrollTo(orderedIndices.lastIndex) },
                onCurrentClick = {
                    if (currentPosition >= 0) scrollTo(currentPosition) else onResetFilters()
                },
                hasItems = orderedIndices.isNotEmpty(),
                hasCurrent = currentPageIndex in pages.indices,
            )
            AppIconButton(onClick = { descending = !descending }) {
                AppIcon(
                    if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                    contentDescription = if (descending) "倒序，点击切换正序" else "正序，点击切换倒序",
                    tint = AppContentColors.secondaryText(),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (orderedIndices.isEmpty()) {
            AppText("没有匹配的分集", modifier = Modifier.padding(16.dp), color = AppContentColors.secondaryText())
        } else {
            AppLazyColumn(
                state = listState,
                modifier = if (blockParentVerticalScroll) {
                    Modifier.fillMaxWidth().weight(1f, fill = false).nestedScroll(nestedScrollConnection)
                } else {
                    Modifier.fillMaxWidth().weight(1f, fill = false)
                },
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomContentPaddingDp.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(orderedIndices.size, key = { position ->
                    val index = orderedIndices[position]
                    "${pages[index].cid}_$index"
                }) { position ->
                    val index = orderedIndices[position]
                    PageCoverRow(
                        page = pages[index],
                        selected = index == currentPageIndex,
                        coverUrl = coverUrl,
                        onClick = { if (index != currentPageIndex) onPageSelect(index) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PageCoverRow(
    page: Page,
    selected: Boolean,
    coverUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    //  分集行沿用应用内统一的封面比例与卡片圆角，选中态交由共享单选行表达
    BoxWithConstraints(modifier = modifier) {
        val coverWidth = (maxWidth * 0.38f).coerceIn(64.dp, 132.dp)
        AppSingleChoiceRow(
            selected = selected,
            onClick = onClick,
            shape = AppShapes.container(ContainerLevel.Card),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .width(coverWidth)
                    .aspectRatio(16f / 9f)
                    .clip(AppShapes.container(ContainerLevel.Chip))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AppIcon(
                    Icons.Outlined.PlayCircleOutline,
                    contentDescription = null,
                    tint = AppContentColors.secondaryText(),
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                )
                val pic = page.firstFrame.ifBlank { coverUrl }
                if (pic.isNotBlank()) {
                    AsyncImage(
                        model = FormatUtils.resolveVideoCoverUrl(pic, useLowQuality = true),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (page.duration > 0) {
                    VideoCardCoverDurationText(
                        text = FormatUtils.formatDuration(page.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                AppText(
                    text = page.part.ifBlank { "第${page.page}P" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) AppContentColors.accent() else AppContentColors.primaryText(),
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                AppText(
                    text = if (selected) "P${page.page} · 正在播放" else "P${page.page}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) AppContentColors.accent() else AppContentColors.secondaryText(),
                )
            }
        }
    }
}
