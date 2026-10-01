// Original source app/src/main/java/com/android/purebilibili/navigation/AppTopLevelNavigationPolicy.kt
// LF SHA256 cbb6d92e8bcba3dfdf5df6dff06b8e61ccccaea9973a2d5e04fd0b5cd54917e6
package com.android.purebilibili.navigation

import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.toLegacyRoute

internal data class BottomPagerRenderBudget(
    val isTransitionRunning: Boolean,
    val forceLowBlurBudget: Boolean,
    val deferProfileImmersiveBackground: Boolean
)

internal const val BOTTOM_BAR_MAX_VISIBLE_ITEMS = 5
// 底栏最多有 5 个栏目；预组合其余 4 页，避免跨多页动画途中临时创建中间页面。

internal const val BOTTOM_PAGER_MAX_PRELOAD_DISTANCE = BOTTOM_BAR_MAX_VISIBLE_ITEMS - 1

internal fun resolveBottomPagerPageForRoute(
    route: String?,
    visibleItems: List<BottomNavItem>
): Int? {
    val routeBase = route?.substringBefore("?") ?: return null
    return visibleItems.indexOfFirst { item -> item.route == routeBase }
        .takeIf { it >= 0 }
}

internal fun resolveBottomPagerItemForPage(
    page: Int,
    visibleItems: List<BottomNavItem>
): BottomNavItem {
    return visibleItems.getOrNull(page) ?: BottomNavItem.HOME
}

internal fun shouldResetNavigation3BackStackForBottomPager(
    currentStack: List<BiliPaiNavKey>
): Boolean {
    // Sibling tabs switch entirely inside the pager. BiliPai only needs Nav3 here when leaving a
    // secondary destination; rebuilding an existing MainHost invalidates the
    // composable that currently owns the pager mutation.
    return currentStack.size != 1 || currentStack.singleOrNull() != BiliPaiNavKey.MainHost
}

internal fun resolveVisibleBottomBarItems(
    orderedVisibleTabIds: List<String>
): List<BottomNavItem> {
    return orderedVisibleTabIds
        .mapNotNull { id -> BottomNavItem.entries.find { it.name == id } }
        .take(BOTTOM_BAR_MAX_VISIBLE_ITEMS)
}

internal fun resolveActiveBottomTabRoute(
    currentKey: BiliPaiNavKey?,
    currentBottomItem: BottomNavItem
): String? {
    if (currentKey == null || currentKey == BiliPaiNavKey.MainHost) {
        return currentBottomItem.route
    }
    val route = currentKey.toLegacyRoute()
    return if (route == BiliPaiNavKey.MainHost.routeBase) currentBottomItem.route else route
}

internal fun shouldShowBottomBarForNavigation(
    activeRoute: String?,
    visibleBottomBarRoutes: Set<String>,
    useSideNavigation: Boolean,
    shouldHideBottomBarOnTablet: Boolean,
    shouldDeferReveal: Boolean
): Boolean {
    return !activeRoute.orEmpty().startsWith("story") &&
        activeRoute in visibleBottomBarRoutes &&
        !useSideNavigation &&
        !shouldHideBottomBarOnTablet &&
        !shouldDeferReveal
}

internal fun shouldMountSidebarForNavigation(
    routeAllowsSidebar: Boolean,
    isVideoDetailDestination: Boolean
): Boolean = routeAllowsSidebar && !isVideoDetailDestination

internal fun resolveBottomPagerSaveableStateKey(item: BottomNavItem): String {
    return "bottom:${item.route}"
}

internal fun resolveBottomPagerBeyondViewportPageCount(
    pageCount: Int,
    contentReady: Boolean
): Int {
    if (!contentReady) return 0
    return pageCount.coerceIn(1, BOTTOM_BAR_MAX_VISIBLE_ITEMS) - 1
}

internal fun resolveBottomPagerRenderBudget(isNavigating: Boolean): BottomPagerRenderBudget {
    return BottomPagerRenderBudget(
        isTransitionRunning = isNavigating,
        forceLowBlurBudget = isNavigating,
        deferProfileImmersiveBackground = isNavigating
    )
}

internal fun shouldEnableBottomPagerUserScroll(): Boolean = false

/**
 * BiliPai MainScreen composition:
 * `if (isCurrentPage || contentReady) XxxPager(...)`
 *
 * After first-frame ready, lightweight bottom-tab slots stay mounted so
 * [MainBottomPagerState.switchToPage] `animateScrollBy` far jumps
 * (rightmost → home) scroll across real pages instead of empty Boxes.
 * Story is intentionally excluded while inactive: mounting it creates a real media player and
 * playback loading session, which is not safe or useful as visual-only pager precomposition.
 *
 * Before ready, only mount start / selected / current to keep cold start light.
 */

internal fun shouldComposeBottomPagerPage(
    item: BottomNavItem,
    page: Int,
    currentPage: Int,
    selectedPage: Int,
    isNavigating: Boolean,
    navigationStartPage: Int,
    contentReady: Boolean
): Boolean {
    val isTransitionParticipant = page == currentPage ||
        page == selectedPage ||
        page == navigationStartPage
    if (item == BottomNavItem.STORY) {
        return isTransitionParticipant
    }
    if (contentReady) {
        return true
    }
    return isTransitionParticipant
}
