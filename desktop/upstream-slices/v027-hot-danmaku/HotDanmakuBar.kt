package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.purebilibili.core.store.SettingsManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.media3.common.Player
import com.android.purebilibili.core.ui.components.AnimatedCountText
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.feature.video.danmaku.selectFittingHotDanmaku
import com.android.purebilibili.feature.video.danmaku.selectHotDanmaku
import com.android.purebilibili.feature.video.ui.components.DanmakuSameSendConfirmation
import com.android.purebilibili.danmaku.engine.DanmakuItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val HOT_DANMAKU_BAR_HEIGHT_DP = 64

/** Poll fresh snapshots, including replacements with the same size and filter changes. */
@Composable
fun HotDanmakuBar(
    getDanmakuList: () -> List<DanmakuItem>,
    player: Player?,
    likedDanmakuIds: Set<Long>,
    onLikeDanmaku: (Long, Boolean) -> Unit,
    onSendSame: (String) -> Unit,
    isSending: Boolean,
    modifier: Modifier = Modifier,
    onVisibilityChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val expandedMode by remember(context) {
        SettingsManager.getHotDanmakuExpandedMode(context)
    }.collectAsStateWithLifecycle(initialValue = false)
    val rowScrollState = rememberScrollState()
    val latestVisibilityChange by rememberUpdatedState(onVisibilityChange)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val countStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = MaterialTheme.typography.titleLarge.fontSize * 0.9f,
        lineHeight = MaterialTheme.typography.titleLarge.lineHeight * 0.9f,
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        shadow = Shadow(Color.Black, Offset(1f, 1f), 3f),
    )
    val latestGetList by rememberUpdatedState(getDanmakuList)
    var hotItems by remember(player) { mutableStateOf(emptyList<DanmakuItem>()) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    val refresh = {
        hotItems = player?.let { selectHotDanmaku(latestGetList(), it.currentPosition, maxItems = Int.MAX_VALUE) }.orEmpty()
    }
    val latestRefresh by rememberUpdatedState(refresh)
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        while (isActive) {
            latestRefresh()
            delay(1_000L)
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) { latestRefresh() }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    if (hotItems.isEmpty()) SideEffect { latestVisibilityChange(false) }
    DisposableEffect(Unit) {
        onDispose { latestVisibilityChange(false) }
    }
    if (hotItems.isNotEmpty()) {
        BoxWithConstraints(
            modifier = modifier.fillMaxWidth().height(HOT_DANMAKU_BAR_HEIGHT_DP.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val textStyle = MaterialTheme.typography.titleMedium.copy(
                shadow = Shadow(Color.Black, Offset(1f, 1f), 3f),
            )
            val countWidthFor: (Long) -> androidx.compose.ui.unit.Dp = { count ->
                val widthPx = textMeasurer.measure(AnnotatedString("×$count"), style = countStyle).size.width
                with(density) { widthPx.toDp() * 1.15f + 12.dp }.coerceAtLeast(64.dp)
            }
            val visibleItems = remember(hotItems, likedDanmakuIds, maxWidth, textStyle, countStyle, density.density, density.fontScale, expandedMode) {
                if (expandedMode) hotItems.take(3) else selectFittingHotDanmaku(
                    items = hotItems,
                    availableWidthPx = with(density) { (maxWidth - 16.dp).toPx() },
                    spacingPx = with(density) { 6.dp.toPx() },
                ) { item ->
                    val liked = item.danmakuId in likedDanmakuIds
                    val count = item.likeCount + if (liked && item.likeCount < Long.MAX_VALUE) 1L else 0L
                    val textWidth = textMeasurer.measure(AnnotatedString(item.text.orEmpty()), style = textStyle).size.width
                    // 两个按钮、三个间距、条目内边距，以及独立数字槽位。
                    textWidth + with(density) { (116.dp + countWidthFor(count)).toPx() }
                }
            }
            SideEffect { latestVisibilityChange(visibleItems.isNotEmpty()) }
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .then(if (expandedMode) Modifier.horizontalScroll(rowScrollState) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleItems.forEach { item ->
                    key(item.danmakuId) {
                        val liked = item.danmakuId in likedDanmakuIds
                        Row(
                            modifier = Modifier
                                .then(if (expandedMode) Modifier.widthIn(max = 420.dp) else Modifier)
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AppText(
                                text = item.text.orEmpty(),
                                modifier = if (expandedMode) Modifier.weight(1f, fill = false) else Modifier,
                                style = textStyle,
                                color = Color.White,
                                maxLines = 1,
                                overflow = if (expandedMode) TextOverflow.Ellipsis else TextOverflow.Clip,
                                softWrap = false,
                                tapToCopyEnabled = false,
                            )
                            val count = item.likeCount + if (liked && item.likeCount < Long.MAX_VALUE) 1L else 0L
                            val countWidth = countWidthFor(count)
                            // 以目标数字预留独立槽位，递增动画不会把正文和按钮来回挤动。
                            Box(
                                modifier = Modifier.width(countWidth).height(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                AnimatedCountText(
                                    count = count,
                                    prefix = "×",
                                    animateFromZero = true,
                                    blurOnChange = true,
                                    style = countStyle,
                                    color = Color.White,
                                )
                            }
                            AppIconButton(
                                onClick = { onLikeDanmaku(item.danmakuId, !liked) },
                                modifier = Modifier.size(48.dp),
                            ) {
                                AppIcon(
                                    imageVector = if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (liked) "取消点赞" else "点赞弹幕",
                                    tint = if (liked) Color(0xFFFF6699) else Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            AppIconButton(
                                onClick = { pendingText = item.text },
                                enabled = !isSending,
                                modifier = Modifier.size(48.dp),
                            ) {
                                AppIcon(Icons.Filled.Send, contentDescription = "发一条同款弹幕",
                                    tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    pendingText?.let { text ->
        DanmakuSameSendConfirmation(
            text = text,
            isSending = isSending,
            onDismiss = { pendingText = null },
            onConfirm = { pendingText = null; onSendSame(text) },
        )
    }
}
