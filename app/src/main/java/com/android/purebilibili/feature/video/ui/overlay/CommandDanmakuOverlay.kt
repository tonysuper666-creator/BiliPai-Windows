package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ThumbUp
import com.android.purebilibili.core.ui.components.AppButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonColors
import com.android.purebilibili.core.ui.components.AppIcon
import com.android.purebilibili.core.ui.components.AppIconButton
import com.android.purebilibili.core.ui.components.AppIconButtonDefaults
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.components.AppSurface
import com.android.purebilibili.core.ui.components.AppText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage

import com.android.purebilibili.core.ui.AppIcons
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuItem
import com.android.purebilibili.feature.video.danmaku.CommandDanmakuType
import com.android.purebilibili.feature.video.danmaku.VoteDanmakuKind
import com.android.purebilibili.feature.video.danmaku.VoteOption
import com.android.purebilibili.feature.video.danmaku.resolveGradeStarOptions
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.ContainerLevel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import com.android.purebilibili.feature.video.danmaku.DanmakuViewport

@Composable
internal fun CommandDanmakuOverlay(
    items: List<CommandDanmakuItem>,
    player: Player,
    viewport: DanmakuViewport,
    state: CommandDanmakuOverlayState,
    fontScale: Float,
    onFollowClick: () -> Unit,
    onTripleClick: () -> Unit,
    onVoteSubmit: (CommandDanmakuItem, VoteOption, Int) -> Unit = { _, _, _ -> },
    isFollowing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val currentPosition by produceState(initialValue = player.currentPosition, key1 = player) {
        while (true) {
            value = player.currentPosition
            kotlinx.coroutines.delay(80)
        }
    }
    // 投票弹幕的 vote_id 与动态/视频投票同一套系统，点击后复用标准投票面板。
    var votePanelVoteId by remember { mutableStateOf<Long?>(null) }
    var votePanelInitialOptionIndex by remember { mutableIntStateOf(-1) }

    Box(modifier = modifier.fillMaxSize()) {
        val active = items.filter {
            !state.isDismissed(it.id) &&
                currentPosition in it.startTimeMs..(it.startTimeMs + it.durationMs)
        }
        active.forEach { item ->
            key(item.id) {
                CommandDanmakuCard(
                    item = item,
                    viewport = viewport,
                    state = state,
                    fontScale = fontScale,
                    onFollowClick = onFollowClick,
                    onTripleClick = onTripleClick,
                    onVoteSubmit = onVoteSubmit,
                    isFollowing = isFollowing,
                    onDismiss = { state.dismiss(item.id) },
                    onOpenVotePanel = { voteId, initialOptionIndex ->
                        votePanelInitialOptionIndex = initialOptionIndex ?: -1
                        votePanelVoteId = voteId
                    }
                )
            }
        }
    }

    votePanelVoteId?.let { voteId ->
        com.android.purebilibili.feature.dynamic.components.DynamicVoteDialog(
            voteId = voteId,
            dynamicId = "",
            onDismiss = { votePanelVoteId = null },
            initialOptionIndex = votePanelInitialOptionIndex.takeIf { it >= 0 }
        )
    }
}

@Composable
private fun CommandDanmakuCard(
    item: CommandDanmakuItem,
    viewport: DanmakuViewport,
    state: CommandDanmakuOverlayState,
    fontScale: Float,
    onFollowClick: () -> Unit,
    onTripleClick: () -> Unit,
    onVoteSubmit: (CommandDanmakuItem, VoteOption, Int) -> Unit,
    isFollowing: Boolean,
    onDismiss: () -> Unit,
    onOpenVotePanel: (Long, Int?) -> Unit
) {
    val containerWidth = viewport.widthPx
    val containerHeight = viewport.heightPx
    val (xRatio, yRatio) = when (item.type) {
        CommandDanmakuType.ATTENTION -> mapAttentionPosition(item.posX, item.posY)
        CommandDanmakuType.VOTE -> 0.08f to 0.10f
        CommandDanmakuType.UP -> 0.08f to 0.10f
        CommandDanmakuType.LINK -> 0.08f to 0.18f
        CommandDanmakuType.TEXT -> 0.08f to 0.10f
    }
    val requestedCardWidthDp = when (item.type) {
        CommandDanmakuType.ATTENTION -> resolveAttentionCommandCardWidthDp(item.attentionType)
        // Five 48dp star targets fit without scrolling while leaving the close control above.
        CommandDanmakuType.VOTE -> if (item.voteKind == VoteDanmakuKind.GRADE) 260 else 224
        else -> 220
    }
    val density = LocalDensity.current
    val visualDensity = remember(density.density, density.fontScale, fontScale) {
        Density(density.density, density.fontScale * fontScale.coerceIn(0.3f, 2f))
    }
    val requestedCardWidthPx = with(visualDensity) { requestedCardWidthDp.dp.roundToPx() }
    val cardWidthPx = resolveCommandDanmakuCardWidthPx(
        containerWidthPx = containerWidth,
        requestedCardWidthPx = requestedCardWidthPx
    )
    val cardWidthDp = with(density) { cardWidthPx.toDp() }
    val maxCardHeightDp = with(visualDensity) { containerHeight.toDp() }
    // Start conservatively at the full viewport height so the first frame cannot extend below it;
    // onSizeChanged then tightens the position to the measured card height.
    var measuredCardHeightPx by remember(item.id, viewport, density.fontScale) {
        mutableIntStateOf(containerHeight)
    }
    val x = resolveCommandDanmakuHorizontalOffsetPx(containerWidth, cardWidthPx, xRatio)
    val y = resolveCommandDanmakuVerticalOffsetPx(
        containerHeightPx = containerHeight,
        cardHeightPx = measuredCardHeightPx,
        yRatio = yRatio
    )

    Box(
        modifier = Modifier
            .offset { IntOffset(x, y) }
            .width(cardWidthDp)
            .heightIn(max = with(density) { containerHeight.toDp() })
            .onSizeChanged { measuredCardHeightPx = it.height }
    ) {
        CompositionLocalProvider(LocalDensity provides visualDensity) {
            AppSurface(
                modifier = Modifier.fillMaxWidth(),
                color = resolveCommandDanmakuContainerColor(item.type),
                contentColor = Color.White,
                shape = AppShapes.container(ContainerLevel.Chip)
            ) {
        Box {
            when (item.type) {
                CommandDanmakuType.ATTENTION -> AttentionCommandCard(
                    item = item,
                    isFollowing = isFollowing,
                    onFollowClick = onFollowClick,
                    onTripleClick = onTripleClick,
                    onDismiss = onDismiss,
                )
                CommandDanmakuType.VOTE -> VoteCommandCard(
                    item = item,
                    state = state,
                    maxHeightDp = maxCardHeightDp,
                    onVoteSubmit = onVoteSubmit,
                    onOpenVotePanel = onOpenVotePanel
                )
                else -> InfoCommandCard(item)
            }
            if (item.type != CommandDanmakuType.ATTENTION) {
                CommandDanmakuCloseButton(
                    onDismiss = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(1.dp)
                )
            }
        }
            }
        }
    }
}

@Composable
private fun InfoCommandCard(item: CommandDanmakuItem) {
    Row(
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 52.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.iconUrl.isNotBlank()) {
            AsyncImage(
                model = item.iconUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AppText(
                text = if (item.type == CommandDanmakuType.LINK) "关联视频" else "UP 主提示",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.74f)
            )
            AppText(
                text = item.linkTitle.ifBlank { item.content },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val ATTENTION_ICON_SLOT_DP = 32
private const val ATTENTION_TOUCH_TARGET_DP = 48
private const val ATTENTION_FOLLOW_TARGET_DP = 78

private val attentionCommandButtonColors = ButtonColors(
    containerColor = Color.Transparent,
    contentColor = Color.White,
    disabledContainerColor = Color.Transparent,
    disabledContentColor = Color.White.copy(alpha = 0.7f),
)

@Composable
private fun AttentionCommandCard(
    item: CommandDanmakuItem,
    isFollowing: Boolean,
    onFollowClick: () -> Unit,
    onTripleClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val showTriple = item.attentionType == 1 || item.attentionType == 2

    fun dispatchAction(target: AttentionCommandAction) {
        val action = resolveAttentionCommandClickAction(
            attentionType = item.attentionType,
            action = target,
            isFollowing = isFollowing,
        )
        if (action.shouldFollow) onFollowClick()
        if (action.shouldTriple) onTripleClick()
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AppSurface(
            modifier = Modifier.matchParentSize().padding(vertical = 6.dp),
            color = Color.Black.copy(alpha = 0.54f),
            shape = RoundedCornerShape(8.dp),
        ) {}
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = ATTENTION_TOUCH_TARGET_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showTriple) {
                    AttentionCommandTripleButton(
                        onClick = { dispatchAction(AttentionCommandAction.TRIPLE) },
                    )
                } else {
                    Box(
                        modifier = Modifier.size(
                            width = ATTENTION_ICON_SLOT_DP.dp,
                            height = ATTENTION_TOUCH_TARGET_DP.dp,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.iconUrl.isNotBlank()) {
                            AsyncImage(
                                model = item.iconUrl,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp).clip(CircleShape),
                            )
                        } else {
                            AppIcon(Icons.Rounded.Person, contentDescription = null, tint = Color.White)
                        }
                    }
                }
                if (item.attentionType != 1) {
                    AttentionCommandFollowButton(
                        isFollowing = isFollowing,
                        onClick = { dispatchAction(AttentionCommandAction.FOLLOW) },
                    )
                }
            }
            CommandDanmakuCloseButton(
                onDismiss = onDismiss,
                containerColor = Color.Transparent,
            )
        }
    }
}

@Composable
private fun CommandDanmakuCloseButton(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.34f),
) {
    AppIconButton(
        onClick = onDismiss,
        modifier = modifier.size(48.dp),
        colors = AppIconButtonDefaults.colors(
            containerColor = containerColor,
            contentColor = Color.White
        )
    ) {
        AppIcon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "关闭提示",
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun AttentionCommandTripleButton(onClick: () -> Unit) {
    var pulseKey by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(
        targetValue = if (pressed) 1.1f else 1f,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "attentionTriplePressScale",
    )
    LaunchedEffect(pulseKey) {
        if (pulseKey > 0) {
            pressed = true
            delay(420)
            pressed = false
        }
    }
    AppButton(
        onClick = {
            pulseKey += 1
            onClick()
        },
        modifier = Modifier
            .width((ATTENTION_ICON_SLOT_DP * 3).dp)
            .heightIn(min = ATTENTION_TOUCH_TARGET_DP.dp)
            .semantics { contentDescription = "一键三连：点赞、投币、收藏" },
        colors = attentionCommandButtonColors,
        elevation = null,
        contentPadding = PaddingValues(0.dp),
    ) {
        // Normalize intrinsic vector padding so the three visible glyphs have comparable weight.
        AttentionCommandIconSlot(Icons.Rounded.ThumbUp, 22.dp, scale)
        AttentionCommandIconSlot(AppIcons.BiliCoin, 21.dp, scale)
        AttentionCommandIconSlot(Icons.Rounded.Star, 28.dp, scale)
    }
}

@Composable
private fun AttentionCommandFollowButton(
    isFollowing: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val contentColor = if (isFollowing) Color.White else MaterialTheme.colorScheme.onPrimary
    AppButton(
        onClick = onClick,
        enabled = !isFollowing,
        modifier = Modifier
            .widthIn(min = ATTENTION_FOLLOW_TARGET_DP.dp)
            .heightIn(min = ATTENTION_TOUCH_TARGET_DP.dp),
        colors = attentionCommandButtonColors,
        elevation = null,
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        AppSurface(
            modifier = Modifier.widthIn(min = 66.dp).heightIn(min = 24.dp),
            color = if (isFollowing) Color.Black.copy(alpha = 0.18f) else primary,
            contentColor = contentColor,
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!isFollowing) {
                    AppIcon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
                AppText(
                    text = if (isFollowing) "已关注" else "关注",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun AttentionCommandIconSlot(
    icon: ImageVector,
    iconSize: Dp,
    scale: State<Float>,
) {
    Box(
        modifier = Modifier.size(
            width = ATTENTION_ICON_SLOT_DP.dp,
            height = ATTENTION_TOUCH_TARGET_DP.dp,
        ),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize).graphicsLayer {
                val currentScale = scale.value
                scaleX = currentScale
                scaleY = currentScale
            },
        )
    }
}

internal enum class AttentionCommandAction {
    FOLLOW,
    TRIPLE,
}

internal data class AttentionCommandClickAction(
    val shouldFollow: Boolean,
    val shouldTriple: Boolean
)

private val attentionFollowAction = AttentionCommandClickAction(shouldFollow = true, shouldTriple = false)
private val attentionTripleAction = AttentionCommandClickAction(shouldFollow = false, shouldTriple = true)
private val attentionNoAction = AttentionCommandClickAction(shouldFollow = false, shouldTriple = false)

internal fun resolveAttentionCommandClickAction(
    attentionType: Int,
    action: AttentionCommandAction,
    isFollowing: Boolean,
): AttentionCommandClickAction = when (action) {
    AttentionCommandAction.FOLLOW -> if (attentionType != 1 && !isFollowing) {
        attentionFollowAction
    } else {
        attentionNoAction
    }
    AttentionCommandAction.TRIPLE -> if (attentionType == 1 || attentionType == 2) {
        attentionTripleAction
    } else {
        attentionNoAction
    }
}

internal fun resolveAttentionCommandCardWidthDp(attentionType: Int): Int = when (attentionType) {
    1 -> ATTENTION_ICON_SLOT_DP * 3 + ATTENTION_TOUCH_TARGET_DP
    2 -> ATTENTION_ICON_SLOT_DP * 3 + ATTENTION_FOLLOW_TARGET_DP + ATTENTION_TOUCH_TARGET_DP
    else -> ATTENTION_ICON_SLOT_DP + ATTENTION_FOLLOW_TARGET_DP + ATTENTION_TOUCH_TARGET_DP
}

internal fun resolveCommandDanmakuContainerColor(type: CommandDanmakuType): Color {
    return when (type) {
        CommandDanmakuType.ATTENTION -> Color.Transparent
        else -> Color.Black.copy(alpha = 0.54f)
    }
}

/**
 * 投票/打分弹幕卡片：普通投票保留选项按钮，打分使用固定五颗星。
 * 星位始终按 API 的合法分数 2/4/6/8/10 对齐，不按服务端列表顺序猜测分数。
 */
@Composable
private fun VoteCommandCard(
    item: CommandDanmakuItem,
    maxHeightDp: Dp,
    state: CommandDanmakuOverlayState,
    onVoteSubmit: (CommandDanmakuItem, VoteOption, Int) -> Unit,
    onOpenVotePanel: (Long, Int?) -> Unit
) {
    val selectedOptionId = state.selection(item.id)?.id
    val selectedGradeScore = state.selection(item.id)?.score
    val isGrade = item.voteKind == VoteDanmakuKind.GRADE
    val title = item.voteTitle.ifBlank { item.content }
    val gradeStarOptions = remember(item.id, item.voteOptions) {
        resolveGradeStarOptions(item.voteOptions)
    }
    val voteIdLong = item.voteId.toLongOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeightDp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(start = 10.dp, end = 52.dp)
                .then(
                    if (!isGrade && voteIdLong != null) {
                        Modifier.clickable { onOpenVotePanel(voteIdLong, null) }
                    } else {
                        Modifier
                    }
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AppText(
                text = if (isGrade) "打分弹幕" else "互动投票",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.74f)
            )
            AppText(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        when {
            isGrade -> GradeStarRating(
                modifier = Modifier.padding(start = 10.dp),
                starOptions = gradeStarOptions,
                selectedScore = selectedGradeScore,
                onSelect = { option ->
                    if (state.select(item.id, option)) {
                        onVoteSubmit(item, option, -1)
                    }
                }
            )
            item.voteOptions.isEmpty() -> {
                // 与官方一致：点击进入标准投票面板参与投票，而非纯提示文本
                if (voteIdLong != null) {
                    AppButton(
                        onClick = { onOpenVotePanel(voteIdLong, null) },
                        shape = AppShapes.container(ContainerLevel.Chip),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.14f),
                            contentColor = Color.White
                        ),
                        elevation = null,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .padding(start = 10.dp, end = 52.dp)
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        AppText(
                            text = "参与投票",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                } else {
                    AppText(
                        text = "点击屏幕参与投票",
                        modifier = Modifier.padding(start = 10.dp, end = 52.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
            else -> item.voteOptions.forEachIndexed { index, option ->
                val isSelected = selectedOptionId == option.id
                val isSubmitted = selectedOptionId != null
                AppButton(
                    onClick = {
                        if (state.select(item.id, option)) {
                            onVoteSubmit(item, option, index)
                        }
                    },
                    enabled = !isSubmitted,
                    shape = AppShapes.container(ContainerLevel.Chip),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White,
                        disabledContainerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f),
                        disabledContentColor = Color.White.copy(alpha = 0.85f)
                    ),
                    elevation = null,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .padding(start = 10.dp, end = 52.dp)
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    AppText(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (selectedOptionId != null) {
            AppText(
                text = if (isGrade) "✓ 已打分" else "✓ 已投票",
                modifier = Modifier.padding(start = 10.dp, end = 52.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun GradeStarRating(
    modifier: Modifier = Modifier,
    starOptions: List<VoteOption?>,
    selectedScore: Int?,
    onSelect: (VoteOption) -> Unit
) {
    val hasAvailableScore = starOptions.any { it != null }
    val selectedIndex = selectedScore?.let { score ->
        starOptions.indexOfFirst { it?.score == score }
    } ?: -1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        starOptions.forEachIndexed { index, option ->
            val isFilled = selectedIndex >= 0 && index <= selectedIndex
            val canSelect = option != null && selectedScore == null
            AppIconButton(
                onClick = { option?.let(onSelect) },
                enabled = canSelect,
                modifier = Modifier.size(48.dp),
                colors = AppIconButtonDefaults.colors(
                    containerColor = if (isFilled) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                    } else {
                        Color.White.copy(alpha = 0.08f)
                    },
                    contentColor = if (option != null) Color(0xFFFFC107) else Color.White.copy(alpha = 0.26f),
                    disabledContainerColor = if (isFilled && option != null) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                    } else {
                        Color.White.copy(alpha = 0.04f)
                    },
                    disabledContentColor = if (isFilled && option != null) {
                        Color(0xFFFFC107)
                    } else {
                        Color.White.copy(alpha = 0.26f)
                    }
                )
            ) {
                AppIcon(
                    imageVector = if (isFilled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (option == null) {
                        "${index + 1}星不可用"
                    } else {
                        "${index + 1}星"
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
    if (!hasAvailableScore) {
        AppText(
            text = "当前评分档位不可提交",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f)
        )
    }
}

internal fun resolveCommandDanmakuCardWidthPx(
    containerWidthPx: Int,
    requestedCardWidthPx: Int
): Int {
    return requestedCardWidthPx.coerceIn(0, containerWidthPx.coerceAtLeast(0))
}

internal fun resolveCommandDanmakuVerticalOffsetPx(
    containerHeightPx: Int,
    cardHeightPx: Int,
    yRatio: Float
): Int {
    val safeHeight = containerHeightPx.coerceAtLeast(0)
    val maxOffset = (safeHeight - cardHeightPx.coerceAtLeast(0)).coerceAtLeast(0)
    return (safeHeight * yRatio).roundToInt().coerceIn(0, maxOffset)
}

internal fun resolveCommandDanmakuHorizontalOffsetPx(
    containerWidthPx: Int,
    cardWidthPx: Int,
    xRatio: Float
): Int {
    val maxOffset = (containerWidthPx - cardWidthPx).coerceAtLeast(0)
    return (containerWidthPx * xRatio).roundToInt().coerceIn(0, maxOffset)
}

internal fun mapAttentionPosition(posX: Float, posY: Float): Pair<Float, Float> {
    val x = ((posX - 118f) / (549f - 118f)).coerceIn(0f, 0.82f)
    val y = ((posY - 82f) / (293f - 82f)).coerceIn(0f, 0.78f)
    return x to y
}
