package com.android.purebilibili.feature.home.components.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppShapes
import com.android.purebilibili.core.ui.AppSpacingTokens

/**
 * Shared side-by-side video-card geometry, based on the related-video row.
 *
 * Callers own shell effects (click, selection, shared transition and native snapshot) while this
 * frame keeps cover geometry, spacing, info padding and the trailing action lane identical.
 */
@Composable
internal fun HorizontalVideoCardFrame(
    coverContent: @Composable BoxScope.() -> Unit,
    infoContent: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    coverModifier: Modifier = Modifier,
    coverOverlayModifier: Modifier = Modifier,
    infoModifier: Modifier = Modifier,
    coverOverlayContent: (@Composable BoxScope.() -> Unit)? = null,
    trailingContent: (@Composable BoxScope.() -> Unit)? = null,
    coverAspectRatio: Float = com.android.purebilibili.core.ui.videoCardCoverAspectRatio(),
    stacked: Boolean = false,
    compactPartition: Boolean = false,
    allowPortraitCover: Boolean = false,
    fillInfoHeight: Boolean = compactPartition,
) {
    // MD3、Miuix 和玻璃共用布局；封面保持所选比例。
    val effectiveAspectRatio = coverAspectRatio.takeIf {
        it.isFinite() && if (allowPortraitCover) it > 0f else it > 1f
    }
        ?: HORIZONTAL_VIDEO_CARD_COVER_ASPECT_RATIO
    val infoPadding = if (compactPartition) 4.dp else HORIZONTAL_VIDEO_CARD_INFO_PADDING_DP.dp
    val infoEndPadding = if (trailingContent == null) infoPadding else
        (HORIZONTAL_VIDEO_CARD_ACTION_SIZE_DP + HORIZONTAL_VIDEO_CARD_ACTION_INSET_DP +
            HORIZONTAL_VIDEO_CARD_INFO_SPACING_DP).dp
    val infoArrangement = Arrangement.spacedBy(HORIZONTAL_VIDEO_CARD_INFO_SPACING_DP.dp)

    Box(modifier = modifier) {
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacingTokens.Small),
            ) {
                Box(
                    modifier = coverModifier
                        .fillMaxWidth()
                        .aspectRatio(effectiveAspectRatio)
                        .clip(AppShapes.mediaCover())
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    coverContent()
                    if (coverOverlayContent != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(coverOverlayModifier),
                            content = coverOverlayContent,
                        )
                    }
                }
                Column(
                    modifier = infoModifier
                        .fillMaxWidth()
                        .padding(
                            top = infoPadding,
                            bottom = infoPadding,
                            end = infoEndPadding,
                        ),
                    verticalArrangement = infoArrangement,
                    content = infoContent,
                )
            }
        } else {
            Layout(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    Box(
                        modifier = coverModifier
                            .clip(AppShapes.mediaCover())
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        coverContent()
                        if (coverOverlayContent != null) {
                            Box(
                                modifier = Modifier.fillMaxSize().then(coverOverlayModifier),
                                content = coverOverlayContent,
                            )
                        }
                    }
                    Column(
                        modifier = infoModifier.padding(
                            top = infoPadding,
                            bottom = infoPadding,
                            end = infoEndPadding,
                        ),
                        verticalArrangement = infoArrangement,
                        content = infoContent,
                    )
                },
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val gap = HORIZONTAL_VIDEO_CARD_COVER_INFO_GAP_DP.dp.roundToPx().coerceAtMost(width)
                // 窄列表为封面留出更多空间；宽列表保留文字和操作区的空间。
                val widthDp = width.toDp().value
                val widthFraction = 0.55f - 0.10f * ((widthDp - 320f) / 160f).coerceIn(0f, 1f)
                // 同宽列表的封面尺寸固定，不随标题换行、关注标记或统计信息的高度变化。
                val coverWidth = if (compactPartition) {
                    // 封面以 88dp 高度为基准，压缩封面宽度换取标题单行容量；窄窗口仍为文字保留空间。
                    minOf(
                        (88.dp.toPx() * effectiveAspectRatio).toInt(),
                        (width * 0.54f).toInt(),
                        (width - gap - minOf(92.dp.roundToPx(), (width - gap).coerceAtLeast(0) / 2)).coerceAtLeast(0),
                    )
                } else {
                    (width * widthFraction).toInt().coerceAtMost(width - gap)
                }
                val coverHeight = (coverWidth / effectiveAspectRatio).toInt()
                val infoWidth = (width - gap - coverWidth).coerceAtLeast(0)
                // 按入口选择信息列与封面等高，供底部元信息使用弹性空白；上下排列不扩高。
                val infoMinHeight = if (fillInfoHeight) {
                    coverHeight.coerceAtMost(
                        if (constraints.maxHeight != Constraints.Infinity) constraints.maxHeight else coverHeight
                    )
                } else {
                    0
                }
                val info = measurables[1].measure(
                    Constraints(
                        minWidth = infoWidth, maxWidth = infoWidth,
                        minHeight = infoMinHeight, maxHeight = constraints.maxHeight,
                    )
                )
                val height = constraints.constrainHeight(maxOf(coverHeight, info.height))
                val cover = measurables[0].measure(Constraints.fixed(coverWidth, minOf(coverHeight, height)))
                layout(width, height) {
                    cover.placeRelative(0, 0)
                    info.placeRelative(coverWidth + gap, 0)
                }
            }
        }

        if (trailingContent != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(HORIZONTAL_VIDEO_CARD_ACTION_INSET_DP.dp)
                    .widthIn(min = HORIZONTAL_VIDEO_CARD_ACTION_SIZE_DP.dp)
                    .heightIn(min = HORIZONTAL_VIDEO_CARD_ACTION_SIZE_DP.dp),
                contentAlignment = Alignment.Center,
                content = trailingContent,
            )
        }
    }
}
