// Original source app/src/main/java/com/android/purebilibili/navigation/AppNavigation.kt
// LF SHA256 218267eba2d04714c57d0a67d319d6c11856c7fca4471cee294ed9e999aefa59
package com.android.purebilibili.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.ui.LocalBottomBarVisible
import com.android.purebilibili.core.ui.transition.LocalVideoCardSharedElementSourceRoute
import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.navigation3.BiliPaiNavKey
import com.android.purebilibili.navigation3.toLegacyRoute

internal fun bottomPagerNavKeyForItem(item: BottomNavItem): BiliPaiNavKey {
                    return when (item) {
                        BottomNavItem.HOME -> BiliPaiNavKey.Home
                        BottomNavItem.DYNAMIC -> BiliPaiNavKey.Dynamic
                        BottomNavItem.STORY -> BiliPaiNavKey.Story()
                        BottomNavItem.HISTORY -> BiliPaiNavKey.History
                        BottomNavItem.LISTEN_VIDEO -> BiliPaiNavKey.ListenVideo
                        BottomNavItem.PROFILE -> BiliPaiNavKey.Profile
                        BottomNavItem.FAVORITE -> BiliPaiNavKey.Favorite
                        BottomNavItem.LIVE -> BiliPaiNavKey.LiveList
                        BottomNavItem.WATCHLATER -> BiliPaiNavKey.WatchLater
                        BottomNavItem.SETTINGS -> BiliPaiNavKey.Settings
                        BottomNavItem.PLUGINS -> BiliPaiNavKey.PluginsSettings()
                    }
                }

@Composable internal fun DesktopOriginalMainHostPager(
    visibleBottomBarItems: List<BottomNavItem>,
    bottomPagerState: PagerState,
    mainBottomPagerState: MainBottomPagerState,
    bottomPagerContentReady: Boolean,
    bottomPagerSaveableStateHolder: SaveableStateHolder,
    resolveMainHostBottomBarVisible: () -> Boolean,
    RenderNavigationContent: @Composable (key: BiliPaiNavKey, isBottomPagerPageActive: Boolean, isBottomPagerHosted: Boolean) -> Unit,
) {
    CompositionLocalProvider(
        LocalBottomBarVisible provides resolveMainHostBottomBarVisible()
    ) {
        // MainHost 已由 NavDisplay entry 外层的
        // VideoCardTransitionBackgroundRouteContent 持有唯一冻结层。
        // 此处不能再给 Pager 页挂同一个 snapshotHandle：嵌套
        // GraphicsLayer.record 会递归录制自身，返回时只剩 shared 卡片、
        // 来源页变黑。页面路由仍通过 Local source route 提供给卡片匹配。
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                modifier = Modifier.fillMaxSize(),
                state = bottomPagerState,
                beyondViewportPageCount = resolveBottomPagerBeyondViewportPageCount(
                    pageCount = visibleBottomBarItems.size,
                    contentReady = bottomPagerContentReady
                ).coerceAtMost(BOTTOM_PAGER_MAX_PRELOAD_DISTANCE),
                userScrollEnabled = shouldEnableBottomPagerUserScroll()
            ) { page ->
                val slotItem = visibleBottomBarItems.getOrNull(page) ?: BottomNavItem.HOME
                if (
                    shouldComposeBottomPagerPage(
                        item = slotItem,
                        page = page,
                        currentPage = bottomPagerState.currentPage,
                        selectedPage = mainBottomPagerState.selectedPage,
                        isNavigating = mainBottomPagerState.isNavigating,
                        navigationStartPage = mainBottomPagerState.navigationStartPage,
                        contentReady = bottomPagerContentReady
                    )
                ) {
                    val pageKey = bottomPagerNavKeyForItem(slotItem)
                    bottomPagerSaveableStateHolder.SaveableStateProvider(
                        resolveBottomPagerSaveableStateKey(slotItem)
                    ) {
                        CompositionLocalProvider(
                            LocalVideoCardSharedElementSourceRoute provides pageKey.toLegacyRoute()
                        ) {
                            RenderNavigationContent(
                                pageKey,
                                page == bottomPagerState.settledPage,
                                true,
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
