package com.android.purebilibili.feature.video.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.feature.video.viewmodel.CommentSortMode
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * 普通视频与番剧共用的浮动评论栏。跟随滚动隐藏、居中对齐仅属于液态玻璃；
 * 关闭玻璃时保留原生排序控件的位置与可见性，列表占位由调用方保持固定。
 */
@Composable
internal fun VideoCommentFloatingHeader(
    count: Int,
    sortMode: CommentSortMode,
    onSortModeChange: (CommentSortMode) -> Unit,
    onSearchClick: () -> Unit,
    backdrop: Backdrop?,
    liquidGlassEnabled: Boolean,
    listAtTop: Boolean,
    motionEnabled: Boolean,
    showNativeTitle: Boolean,
    /** 详情页可在玻璃关闭时也使用紧凑标题；番剧等宿主保持原生默认。 */
    compactTitleTypography: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val nativeSortDockLiftDp = remember {
        resolveCommentSortDockViewportOverflowDp(
            containerHeightDp = AppChromeSizeTokens.BottomBarMatchedSegmentedControlHeightDp,
            indicatorHeightDp = AppChromeSizeTokens.BottomBarMatchedSegmentedIndicatorHeightDp,
        )
    }
    AnimatedVisibility(
        visible = !liquidGlassEnabled || listAtTop,
        enter = if (motionEnabled) fadeIn(tween(120)) else EnterTransition.None,
        exit = if (motionEnabled) fadeOut(tween(90)) else ExitTransition.None,
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
            if (liquidGlassEnabled || showNativeTitle) {
                CommentListHeader(
                    count = count,
                    title = "${sortMode.label}评论",
                    liquidGlassEffectsEnabled = liquidGlassEnabled,
                    compactTypography = compactTitleTypography,
                    modifier = Modifier.align(
                        if (liquidGlassEnabled) Alignment.CenterStart else Alignment.TopStart
                    ),
                )
            }
            CommentSortFilterBar(
                sortMode = sortMode,
                onSortModeChange = onSortModeChange,
                modifier = if (liquidGlassEnabled) {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(top = 6.dp, end = 16.dp)
                } else {
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 16.dp)
                        .offset(y = (-nativeSortDockLiftDp).dp)
                },
                miuixBackdrop = if (liquidGlassEnabled) backdrop else null,
                liquidGlassEffectsEnabled = liquidGlassEnabled,
                onSearchClick = onSearchClick,
            )
        }
    }
}
