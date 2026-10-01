// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeTopTabGesturePolicy.kt
// LF SHA256 eb5b47502b302db15a22bc88224e9e5680b975dc4c286b8ce29469367c1fc55a
package com.android.purebilibili.feature.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class HomeTopTabGestureAction {
    NONE,
    COLLAPSE,
    EXPAND
}

internal fun resolveHomeTopTabGestureAction(
    dragDeltaPx: Float,
    isCollapsed: Boolean,
    thresholdPx: Float
): HomeTopTabGestureAction {
    if (thresholdPx <= 0f) return HomeTopTabGestureAction.NONE
    return when {
        !isCollapsed && dragDeltaPx >= thresholdPx -> HomeTopTabGestureAction.COLLAPSE
        isCollapsed && dragDeltaPx <= -thresholdPx -> HomeTopTabGestureAction.EXPAND
        else -> HomeTopTabGestureAction.NONE
    }
}

internal fun resolveHomeTopCollapsedHandleHeight(): Dp = 12.dp

internal fun resolveHomeTopTabPresentationHeight(
    expandedHeight: Dp,
    isCollapsed: Boolean,
    collapsedHandleHeight: Dp = resolveHomeTopCollapsedHandleHeight()
): Dp {
    return if (isCollapsed) collapsedHandleHeight else expandedHeight
}
