package com.android.purebilibili.feature.space

import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** 渐进模糊需要内容连续穿过顶部；关闭后才启用资料折叠和标签吸顶。 */
internal fun shouldPinSpaceContentTabs(progressiveTopBlurEnabled: Boolean): Boolean =
    !progressiveTopBlurEnabled

/** 标签到达工具栏下缘后显示顶层副本；原 item 回收后仍保持吸顶。 */
internal fun shouldShowSpacePinnedTabs(
    tabsOffsetPx: Int?,
    beforeContentPaddingPx: Int,
    chromeBottomPx: Int,
    firstVisibleItemIndex: Int,
): Boolean = if (tabsOffsetPx != null) {
    tabsOffsetPx + beforeContentPaddingPx <= chromeBottomPx
} else firstVisibleItemIndex > 1

/** 根据资料实际剩余高度收尾，长简介、大字号和宽屏不共用固定折叠距离。 */
internal fun resolveSpaceHeaderClosingFraction(
    remainingHeightPx: Float,
    toolbarHeightPx: Float,
): Float = (1f - remainingHeightPx / toolbarHeightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)

/** 定位前先确保标签经过测量，避免首屏尚未出现的标签被当作零高度。 */
internal suspend fun LazyStaggeredGridState.measureSpacePinnedTabsHeightPx(): Int {
    layoutInfo.visibleItemsInfo.firstOrNull { it.key == "space_tabs" }?.let { return it.size.height }
    scrollToItem(1)
    return snapshotFlow {
        layoutInfo.visibleItemsInfo.firstOrNull { it.key == "space_tabs" }?.size?.height
    }.filterNotNull().first()
}
