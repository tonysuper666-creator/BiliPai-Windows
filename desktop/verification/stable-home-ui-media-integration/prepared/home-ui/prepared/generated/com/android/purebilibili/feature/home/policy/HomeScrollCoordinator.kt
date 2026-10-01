// Original source app/src/main/java/com/android/purebilibili/feature/home/policy/HomeScrollCoordinator.kt
// LF SHA256 11db1737044b20100e0faf8ab1da8e6d76b573b90f995cc3fcb284f745bc8985
package com.android.purebilibili.feature.home.policy

import com.android.purebilibili.core.store.CommonListHeaderCollapseMode
import com.android.purebilibili.core.store.HomeBarHideType
import com.android.purebilibili.core.store.HomeHeaderCollapseMode
import com.android.purebilibili.feature.home.resolveNextHomeGlobalScrollOffset
import kotlin.math.abs
import kotlin.math.round

internal enum class BottomBarVisibilityIntent {
    SHOW,
    HIDE
}

internal data class HomeScrollUpdate(
    val headerOffsetPx: Float,
    val bottomBarVisibilityIntent: BottomBarVisibilityIntent?,
    val globalScrollOffset: Float?,
    val shouldAnimateHeader: Boolean = false
)

internal data class HomeHeaderSettleTransition(
    val targetOffsetPx: Float,
    val shouldAnimate: Boolean
)

internal fun resolveHomeRecommendationHeaderCollapseMode(
    homeHeaderCollapseMode: HomeHeaderCollapseMode
): HomeHeaderCollapseMode {
    return homeHeaderCollapseMode
}

internal fun quantizeHomeHeaderOffset(
    offsetPx: Float,
    stepPx: Float
): Float {
    if (stepPx <= 0f) return offsetPx
    return round(offsetPx / stepPx) * stepPx
}

internal fun resolveHomeEmbeddedPageTopPaddingPx(
    expandedTopPaddingPx: Float,
    headerOffsetPx: Float,
    collapsedTabInsetPx: Float,
    minimumTopPaddingPx: Float,
): Float = (
    expandedTopPaddingPx + headerOffsetPx - collapsedTabInsetPx
).coerceAtLeast(minimumTopPaddingPx)

internal fun shouldHandleHomeVerticalPreScroll(
    deltaX: Float,
    deltaY: Float,
    minimumVerticalDeltaPx: Float = 0.5f
): Boolean {
    val absoluteDeltaY = abs(deltaY)
    if (absoluteDeltaY < minimumVerticalDeltaPx) return false
    return absoluteDeltaY >= abs(deltaX)
}

internal fun resolveHomeHeaderSettleTransition(
    currentHeaderOffsetPx: Float,
    targetHeaderOffsetPx: Float,
    animationThresholdPx: Float = 0.5f
): HomeHeaderSettleTransition {
    return HomeHeaderSettleTransition(
        targetOffsetPx = targetHeaderOffsetPx,
        shouldAnimate = abs(currentHeaderOffsetPx - targetHeaderOffsetPx) > animationThresholdPx
    )
}

internal fun resolveHomeHeaderTransitionRunning(
    isFeedScrolling: Boolean,
    isPagerScrolling: Boolean,
    isHeaderSettleAnimating: Boolean
): Boolean {
    return isFeedScrolling || isPagerScrolling || isHeaderSettleAnimating
}

internal fun resolveHomeHeaderListIndex(
    displayedEntryIsSubscription: Boolean,
    categoryFirstVisibleIndex: Int,
    subscriptionFirstVisibleIndex: Int,
): Int {
    return if (displayedEntryIsSubscription) {
        subscriptionFirstVisibleIndex
    } else {
        categoryFirstVisibleIndex
    }
}

internal fun canRevealHomeHeaderForList(
    firstVisibleItemIndex: Int,
    listMissing: Boolean,
): Boolean {
    return listMissing || firstVisibleItemIndex == 0
}

@Suppress("UNUSED_PARAMETER")
internal fun reduceHomePreScroll(
    currentHeaderOffsetPx: Float,
    deltaY: Float,
    minHeaderOffsetPx: Float,
    canRevealHeader: Boolean,
    collapseMode: CommonListHeaderCollapseMode = CommonListHeaderCollapseMode.SHOW_AT_TOP_ONLY,
    isHeaderCollapseEnabled: Boolean,
    isBottomBarAutoHideEnabled: Boolean,
    useSideNavigation: Boolean,
    liquidGlassEnabled: Boolean,
    currentGlobalScrollOffset: Float,
    bottomBarVisibilityThresholdPx: Float = 10f,
    hideType: HomeBarHideType = HomeBarHideType.SYNC,
    instantDirectionThresholdPx: Float = 0.5f,
    isHeaderRevealLocked: Boolean = false,
): HomeScrollUpdate {
    // 搜索框跟手下滑收起；离开首屏后保持收起，回顶锁定期内强制展开。
    val nextHeaderOffset = when {
        !isHeaderCollapseEnabled -> 0f
        isHeaderRevealLocked -> 0f
        collapseMode == CommonListHeaderCollapseMode.SHOW_AT_TOP_ONLY && !canRevealHeader ->
            minHeaderOffsetPx
        else -> (currentHeaderOffsetPx + deltaY).coerceIn(minHeaderOffsetPx, 0f)
    }
    val shouldAnimateHeader = false

    val nextBottomBarIntent = when {
        !isBottomBarAutoHideEnabled || useSideNavigation -> null
        deltaY <= -bottomBarVisibilityThresholdPx -> BottomBarVisibilityIntent.HIDE
        deltaY >= bottomBarVisibilityThresholdPx -> BottomBarVisibilityIntent.SHOW
        else -> null
    }

    return HomeScrollUpdate(
        headerOffsetPx = nextHeaderOffset,
        bottomBarVisibilityIntent = nextBottomBarIntent,
        globalScrollOffset = resolveNextHomeGlobalScrollOffset(
            currentOffset = currentGlobalScrollOffset,
            scrollDeltaY = deltaY,
            liquidGlassEnabled = liquidGlassEnabled
        ),
        shouldAnimateHeader = shouldAnimateHeader
    )
}
