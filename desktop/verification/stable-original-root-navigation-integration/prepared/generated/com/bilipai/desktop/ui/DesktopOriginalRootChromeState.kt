// Original source app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt
// LF SHA256 218267eba2d04714c57d0a67d319d6c11856c7fca4471cee294ed9e999aefa59
package com.bilipai.desktop.ui
import androidx.compose.runtime.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.core.ui.rememberAppBottomBarContentPadding
import com.android.purebilibili.feature.home.components.*
import com.android.purebilibili.feature.video.player.PlaylistItem
import com.android.purebilibili.navigation.*
import com.android.purebilibili.navigation3.BiliPaiReturnSessionState

internal class DesktopOriginalRootChromeState(
    val sideBarMountGate:Boolean,
    val bottomBarCanMount:Boolean,
    val finalBottomBarVisible:Boolean,
    val collapseLinkedPlaybackDock:Boolean,
    val bottomBarVisibilityState:MutableTransitionState<Boolean>,
    val bottomBarContentPadding:Dp,
)
@Composable internal fun desktopOriginalRootChromeState(
    currentRoute:String?, activeBottomTabRoute:String?, bottomBarMountRoute:String?,
    visibleBottomBarRoutes:Set<String>, isTabletLayout:Boolean, useSideNavigation:Boolean,
    isVideoDetailDestination:Boolean, navigation3ReturnSession:BiliPaiReturnSessionState,
    cardTransitionEnabled:Boolean, driveBottomBarByProgress:Boolean,
    videoCardSourceChromeVisible:Boolean, bottomBarVisibilityMode:DesktopFavoriteNavigationTypes.BottomBarVisibilityMode,
    isBottomBarVisible:Boolean, setBottomBarVisible:(Boolean)->Unit,
    isBottomBarFloating:Boolean, audioNowPlayingBarEnabled:Boolean, audioNowPlayingActive:Boolean,
    audioNowPlayingItem:PlaylistItem?, currentBottomNavItem:BottomNavItem, scrollOffsetState:MutableFloatState,
    bottomBarUiSkinDecoration:BottomBarUiSkinDecoration?, navigationBarsBottom:Dp,
):DesktopOriginalRootChromeState {
val isSettingsScreen = activeBottomTabRoute == ScreenRoutes.Settings.route
val shouldHideBottomBarOnTablet = isTabletLayout && isSettingsScreen

// [UX] 底栏仅在“用户配置为可见的一级入口”显示；Story 始终沉浸式隐藏。
val isBottomBarDestination = shouldShowBottomBarForNavigation(
    activeRoute = activeBottomTabRoute,
    visibleBottomBarRoutes = visibleBottomBarRoutes,
    useSideNavigation = false,
    shouldHideBottomBarOnTablet = false,
    shouldDeferReveal = false
)
val shouldDeferBottomBarReveal = shouldDeferBottomBarRevealOnVideoReturn(
    isReturningFromDetail = navigation3ReturnSession.isReturningFromDetail,
    activeBottomTabRoute = activeBottomTabRoute,
    cardTransitionEnabled = cardTransitionEnabled
)
val bottomBarMountGate = shouldShowBottomBarForNavigation(
    activeRoute = bottomBarMountRoute,
    visibleBottomBarRoutes = visibleBottomBarRoutes,
    useSideNavigation = useSideNavigation,
    shouldHideBottomBarOnTablet = shouldHideBottomBarOnTablet,
    shouldDeferReveal = false
)
val sideBarRouteGate = shouldShowBottomBarForNavigation(
    activeRoute = bottomBarMountRoute,
    visibleBottomBarRoutes = visibleBottomBarRoutes,
    useSideNavigation = false,
    shouldHideBottomBarOnTablet = shouldHideBottomBarOnTablet,
    shouldDeferReveal = false
)
// Every video detail needs the full content width, including non-fullscreen playback.
// A transparent sidebar still reserves space in the Row.
val sideBarMountGate = shouldMountSidebarForNavigation(
    routeAllowsSidebar = sideBarRouteGate,
    isVideoDetailDestination = isVideoDetailDestination
)
val showBottomBar = shouldShowBottomBarForNavigation(
    activeRoute = bottomBarMountRoute,
    visibleBottomBarRoutes = visibleBottomBarRoutes,
    useSideNavigation = useSideNavigation,
    shouldHideBottomBarOnTablet = shouldHideBottomBarOnTablet,
    shouldDeferReveal = shouldDeferBottomBarReveal
)

// 核心可见性逻辑：
// 1. 永久隐藏模式 -> 始终隐藏
// 2. 始终显示模式 -> 始终显示
// 3. 向下浏览时隐藏模式 -> 由子页面通过 LocalSetBottomBarVisible 控制，初始为 true
// 根据模式强制重置状态（防止模式切换后状态卡死）
LaunchedEffect(bottomBarVisibilityMode) {
    setBottomBarVisible(true)
}

// 视频详情页只通过可见性退出底栏，避免写入隐藏状态后返回首页卡住。
LaunchedEffect(
    currentRoute,
    activeBottomTabRoute,
    isBottomBarDestination,
    navigation3ReturnSession.isReturningFromDetail,
    navigation3ReturnSession.isQuickReturnFromDetail,
    cardTransitionEnabled
) {
    if (!isBottomBarDestination) return@LaunchedEffect
    if (
        shouldDelayBottomBarRevealAfterVideoReturn(
            isReturningFromDetail = navigation3ReturnSession.isReturningFromDetail,
            isBottomBarDestination = isBottomBarDestination,
            cardTransitionEnabled = cardTransitionEnabled
        )
    ) {
        kotlinx.coroutines.delay(
            resolveVideoReturnBottomBarRevealDelayMs(
                cardTransitionEnabled = cardTransitionEnabled,
                isQuickReturnFromDetail = navigation3ReturnSession.isQuickReturnFromDetail
            )
        )
    }
    setBottomBarVisible(true)
}

// 最终决定是否显示：
// - 必须是用户配置的可见主入口页面
// - 不是侧边栏模式
// - 不是故事模式
// - 且 (模式为始终显示 OR (模式为向下浏览时隐藏 AND 当前状态为可见))
// - 且 模式不是永久隐藏
val finalBottomBarVisible = showBottomBar &&
    (driveBottomBarByProgress || videoCardSourceChromeVisible) &&
    bottomBarVisibilityMode != DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.ALWAYS_HIDDEN &&
    (
        bottomBarVisibilityMode == DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.ALWAYS_VISIBLE ||
            isBottomBarVisible ||
            (isBottomBarFloating && audioNowPlayingBarEnabled && audioNowPlayingActive &&
                audioNowPlayingItem != null)
    )
// This raw signal is intentionally independent from the user's bottom-bar visibility
// mode: the linked playback strip still compacts on downward browsing when the bar itself
// is configured to remain visible.
val collapseLinkedPlaybackDock = !isBottomBarVisible ||
    (currentBottomNavItem == BottomNavItem.DYNAMIC && scrollOffsetState.floatValue > 50f)
val bottomBarVisibilityState = remember { MutableTransitionState(finalBottomBarVisible) }
bottomBarVisibilityState.targetState = finalBottomBarVisible
val bottomBarCanMount =
    bottomBarVisibilityMode != DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.ALWAYS_HIDDEN &&
        (
            bottomBarMountGate ||
                driveBottomBarByProgress ||
                bottomBarVisibilityState.currentState ||
                bottomBarVisibilityState.targetState
        )
val bottomBarReservesSpace = bottomBarCanMount &&
    !driveBottomBarByProgress &&
    (bottomBarVisibilityState.currentState || bottomBarVisibilityState.targetState)
val bottomBarContentPadding = rememberAppBottomBarContentPadding(
    navigationBarsBottom = navigationBarsBottom,
    reserveBottomBar = bottomBarReservesSpace && !useSideNavigation,
    isBottomBarFloating = isBottomBarFloating,
    hasUiSkinDecoration = bottomBarUiSkinDecoration != null,
)


return DesktopOriginalRootChromeState(sideBarMountGate,bottomBarCanMount,finalBottomBarVisible,
    collapseLinkedPlaybackDock,bottomBarVisibilityState,bottomBarContentPadding)
}
