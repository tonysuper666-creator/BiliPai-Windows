package com.android.purebilibili.feature.video.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.ui.LocalAppThemeConfig
import com.android.purebilibili.core.ui.skeleton.CommentListItemSkeleton

/** 评论骨架项在 LazyColumn 中的统一 contentType，让骨架槽位形态一致。 */
internal const val VIDEO_COMMENT_ITEM_CONTENT_TYPE = "video_comment_item"

private const val VIDEO_COMMENT_FADE_IN_MILLIS = 220
private const val VIDEO_COMMENT_FADE_OUT_MILLIS = 160
private const val VIDEO_COMMENT_PLACEMENT_DAMPING = 0.9f
private const val VIDEO_COMMENT_PLACEMENT_STIFFNESS = 220f

/**
 * 评论项动画：新项淡入、离场项淡出、位移用弹簧。
 *
 * 骨架槽位被真实评论替换时，旧的骨架项淡出、新的评论项淡入同时发生，视觉上就是
 * 「骨架 → 内容」的交叉淡入淡出，但不需要把整个评论区降级成单个 lazy item，
 * 从而保住了稳定槽位与滚动锚点。
 */
@Composable
internal fun LazyItemScope.videoCommentItemModifier(): Modifier =
    if (LocalAppThemeConfig.current.uiEntranceAnimationEnabled) {
        Modifier.animateItem(
            fadeInSpec = tween(durationMillis = VIDEO_COMMENT_FADE_IN_MILLIS),
            placementSpec = spring(
                dampingRatio = VIDEO_COMMENT_PLACEMENT_DAMPING,
                stiffness = VIDEO_COMMENT_PLACEMENT_STIFFNESS,
            ),
            fadeOutSpec = tween(durationMillis = VIDEO_COMMENT_FADE_OUT_MILLIS),
        )
    } else {
        Modifier
    }

/**
 * 骨架「稳定槽位」：拆成多个独立 lazy item，而不是以前那种「1 个 item 里塞 N 行」。
 *
 * 旧写法把 5 行骨架塞进同一个 item，数据到达后这个 item 被 N 个真实评论 item 取代，
 * 锚点 key 变化 + item 内部高度变化会一起把列表整体顶一下。改成与真实评论同量级的
 * 多个独立 item 之后，替换是「一个槽位换一个槽位」，列表锚点不失效。
 *
 * @param slotCount 需要铺的槽位数量，用 [com.android.purebilibili.feature.video.viewmodel.resolveVideoCommentSkeletonSlotCount] 按视口高度估算。
 * @param keyPrefix 槽位 key 前缀，同一页面里多处使用骨架时不能重复。
 */
internal fun LazyListScope.videoCommentSkeletonItems(
    slotCount: Int,
    keyPrefix: String,
) {
    val slots = List(slotCount.coerceAtLeast(1)) { it }
    items(
        items = slots,
        key = { "$keyPrefix$it" },
        contentType = { VIDEO_COMMENT_ITEM_CONTENT_TYPE },
    ) { slotIndex ->
        CommentListItemSkeleton(
            modifier = videoCommentItemModifier(),
            slotIndex = slotIndex,
        )
    }
}
