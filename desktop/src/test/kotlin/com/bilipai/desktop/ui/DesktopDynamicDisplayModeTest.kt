package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.util.AppFoldPosture
import com.android.purebilibili.core.util.WindowHeightSizeClass
import com.android.purebilibili.core.util.WindowSizeClass
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.feature.dynamic.SidebarUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDynamicDisplayModeTest {
    @Test fun fiveModesKeepOriginalLabelsPredicatesAndSidebarDefault() {
        assertEquals(DesktopDynamicDisplayMode.SIDEBAR, parseDesktopDynamicDisplayMode(null))
        assertEquals(DesktopDynamicDisplayMode.SIDEBAR, parseDesktopDynamicDisplayMode(" "))
        assertEquals(DesktopDynamicDisplayMode.SIDEBAR, parseDesktopDynamicDisplayMode("NOT_A_MODE"))
        assertEquals(DesktopDynamicDisplayMode.DRAWER_LEFT, parseDesktopDynamicDisplayMode("DRAWER_LEFT"))
        assertEquals(
            listOf("左侧竖条", "右侧竖条", "顶部横条", "左侧抽屉", "右侧抽屉"),
            DesktopDynamicDisplayMode.entries.map(::resolveDynamicDisplayModeLabel),
        )
        assertTrue(DesktopDynamicDisplayMode.HORIZONTAL.isHorizontalUserList())
        assertFalse(DesktopDynamicDisplayMode.SIDEBAR.isHorizontalUserList())
        assertTrue(DesktopDynamicDisplayMode.SIDEBAR.isFixedSidebar())
        assertTrue(DesktopDynamicDisplayMode.SIDEBAR_RIGHT.isFixedSidebar())
        assertFalse(DesktopDynamicDisplayMode.DRAWER_LEFT.isFixedSidebar())
        assertTrue(DesktopDynamicDisplayMode.SIDEBAR_RIGHT.isRightAligned())
        assertTrue(DesktopDynamicDisplayMode.DRAWER_RIGHT.isRightAligned())
        assertFalse(DesktopDynamicDisplayMode.SIDEBAR.isRightAligned())
        assertTrue(DesktopDynamicDisplayMode.DRAWER_LEFT.isDrawer())
        assertTrue(DesktopDynamicDisplayMode.DRAWER_RIGHT.isDrawer())
        assertFalse(DesktopDynamicDisplayMode.HORIZONTAL.isDrawer())
    }

    @Test fun sidebarWidthLiquidSpecAndListPaddingMatchOriginalNumbers() {
        assertEquals(68f, resolveDynamicSidebarWidth(true).value)
        assertEquals(60f, resolveDynamicSidebarWidth(false).value)
        assertEquals(50, resolveDynamicTopBarHeightDp())
        assertEquals(50, resolveDynamicSidebarReturnHeaderHeightDp())
        val liquid = resolveDynamicTopBarLiquidTabSpec()
        assertEquals(50, liquid.heightDp)
        assertEquals(42, liquid.indicatorHeightDp)
        assertEquals(13, liquid.labelFontSizeSp)
        assertEquals(72, resolveDynamicTopBarTabItemWidthDp())
        assertEquals(156, resolveDynamicListTopPaddingExtraDp(isHorizontalMode = true, shouldShowHorizontalUserList = true))
        assertEquals(60, resolveDynamicListTopPaddingExtraDp(isHorizontalMode = true, shouldShowHorizontalUserList = false))
        assertEquals(60, resolveDynamicListTopPaddingExtraDp(isHorizontalMode = false, shouldShowHorizontalUserList = true))
        assertEquals(0, resolveWindowsDynamicListTopPaddingExtraDp(topBarOverlaysFeed = false, horizontalUserListOverlaysFeed = false))
        assertEquals(60, resolveWindowsDynamicListTopPaddingExtraDp(topBarOverlaysFeed = true, horizontalUserListOverlaysFeed = false))
        assertEquals(96, resolveWindowsDynamicListTopPaddingExtraDp(topBarOverlaysFeed = false, horizontalUserListOverlaysFeed = true))
        assertEquals(156, resolveWindowsDynamicListTopPaddingExtraDp(topBarOverlaysFeed = true, horizontalUserListOverlaysFeed = true))
        assertEquals(0.70f, resolveDynamicSidebarFlingDampingFactor())
        assertTrue(shouldAnimateSidebarItemCascade(7, hasScrolled = false, initialEntranceActive = true))
        assertFalse(shouldAnimateSidebarItemCascade(8, hasScrolled = false, initialEntranceActive = true))
        assertFalse(shouldAnimateSidebarItemCascade(0, hasScrolled = true, initialEntranceActive = true))
        val face = resolveDynamicSidebarUserAvatarUrl("http://example.com/a.jpg")
        assertEquals("https://example.com/a.jpg", face)
        assertEquals(listOf(face), resolveDynamicSidebarAvatarPrefetchUrls(
            listOf(SidebarUser(1, "a", "http://example.com/a.jpg")), limit = 1))
        val surface = Color.White
        assertEquals(surface, resolveDynamicSidebarContainerColor(surface, globalWallpaperVisible = false))
        assertTrue(resolveDynamicSidebarContainerColor(surface, globalWallpaperVisible = true).alpha < 1f)
    }

    @Test fun shellUsesOriginalSidebarDrawerAndExpandedRailPolicies() {
        val expanded = WindowSizeClass(WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Expanded, 900.dp, 1000.dp)
        val compact = WindowSizeClass(WindowWidthSizeClass.Compact, WindowHeightSizeClass.Medium, 400.dp, 800.dp)
        val large = WindowSizeClass(WindowWidthSizeClass.Large, WindowHeightSizeClass.Expanded, 1300.dp, 1000.dp)
        assertEquals(
            DesktopShellNavigationPlacement.SIDEBAR,
            resolveDesktopShellNavigationPlacement(false, expanded, tabletUseSidebar = true, AppFoldPosture.None),
        )
        assertEquals(
            DesktopShellNavigationPlacement.BOTTOM_DOCK,
            resolveDesktopShellNavigationPlacement(false, expanded, tabletUseSidebar = true, AppFoldPosture.Tabletop),
        )
        assertEquals(
            DesktopShellNavigationPlacement.BOTTOM_DOCK,
            resolveDesktopShellNavigationPlacement(false, expanded, tabletUseSidebar = false, AppFoldPosture.None),
        )
        assertEquals(
            DesktopShellNavigationPlacement.BOTTOM_DOCK,
            resolveDesktopShellNavigationPlacement(false, compact, tabletUseSidebar = true, AppFoldPosture.None),
        )
        assertEquals(
            DesktopShellNavigationPlacement.HIDDEN,
            resolveDesktopShellNavigationPlacement(true, large, tabletUseSidebar = true, AppFoldPosture.None),
        )
        assertTrue(resolveDesktopShellSidebarExpanded(true, large, AppFoldPosture.None))
        assertFalse(resolveDesktopShellSidebarExpanded(true, large, AppFoldPosture.Book))
        assertFalse(resolveDesktopShellSidebarExpanded(true, expanded, AppFoldPosture.None))
        assertFalse(resolveDesktopShellSidebarExpanded(false, large, AppFoldPosture.None))
    }

    @Test fun collapsePredicatesUseZeroToleranceAndIgnorePinnedTopBar() {
        assertFalse(shouldCollapseDynamicTopBar(false, 3, 40, DynamicHeaderCollapseTriggerPx))
        assertFalse(shouldCollapseDynamicTopBar(true, 0, 0, DynamicHeaderCollapseTriggerPx))
        assertTrue(shouldCollapseDynamicTopBar(true, 0, 1, DynamicHeaderCollapseTriggerPx))
        assertTrue(shouldCollapseDynamicTopBar(true, 1, 0, DynamicHeaderCollapseTriggerPx))
        assertFalse(shouldCollapseDynamicHorizontalUserList(0, 0, DynamicHeaderCollapseTriggerPx))
        assertTrue(shouldCollapseDynamicHorizontalUserList(0, 1, DynamicHeaderCollapseTriggerPx))
        val scroll = DesktopDynamicFeedChromeScroll()
        scroll.publish(0, 0)
        scroll.publish(2, 12)
        assertEquals(2, scroll.firstVisibleItemIndex)
        assertEquals(12, scroll.firstVisibleItemScrollOffset)
    }

    @Test fun pageDirectionMirrorsSidebarAndDetailWithoutMovingTheLeftDefault() {
        assertEquals(DesktopDynamicPageLayoutDirection.LEFT, DesktopDynamicPageLayoutDirection.fromValue(0))
        assertEquals(DesktopDynamicPageLayoutDirection.LEFT, DesktopDynamicPageLayoutDirection.fromValue(9))
        assertEquals(DesktopDynamicPageLayoutDirection.RIGHT, DesktopDynamicPageLayoutDirection.fromValue(1))
        assertFalse(resolveDynamicSidebarOnRight(DesktopDynamicDisplayMode.SIDEBAR, DesktopDynamicPageLayoutDirection.LEFT))
        assertTrue(resolveDynamicSidebarOnRight(DesktopDynamicDisplayMode.SIDEBAR, DesktopDynamicPageLayoutDirection.RIGHT))
        assertTrue(resolveDynamicSidebarOnRight(DesktopDynamicDisplayMode.SIDEBAR_RIGHT, DesktopDynamicPageLayoutDirection.LEFT))
        assertFalse(resolveDynamicSidebarOnRight(DesktopDynamicDisplayMode.SIDEBAR_RIGHT, DesktopDynamicPageLayoutDirection.RIGHT))
        assertTrue(resolveDynamicSidebarOnRight(DesktopDynamicDisplayMode.DRAWER_RIGHT, DesktopDynamicPageLayoutDirection.LEFT))
        assertFalse(resolveDynamicSidebarOnRight(DesktopDynamicDisplayMode.DRAWER_LEFT, DesktopDynamicPageLayoutDirection.LEFT))
        assertTrue(resolveDynamicSidebarOnRight(DesktopDynamicDisplayMode.DRAWER_LEFT, DesktopDynamicPageLayoutDirection.RIGHT))
        assertFalse(resolveDynamicDetailContentOnRight(DesktopDynamicPageLayoutDirection.LEFT))
        assertTrue(resolveDynamicDetailContentOnRight(DesktopDynamicPageLayoutDirection.RIGHT))
    }

    @Test fun overflowGroupsKeepOriginalLabelsAndSelection() {
        val chosen = mutableListOf<String>()
        val groups = buildDynamicPageOverflowGroups(
            displayMode = DesktopDynamicDisplayMode.HORIZONTAL,
            previewTextVisible = true,
            detailImageLayoutValue = 1,
            topBarCollapseOnScroll = false,
            pageLayoutDirection = DesktopDynamicPageLayoutDirection.RIGHT,
            onDisplayMode = { chosen += it.name },
            onPreviewText = { chosen += "preview:$it" },
            onDetailLayout = { chosen += "layout:$it" },
            onTopBarCollapse = { chosen += "collapse:$it" },
            onDirection = { chosen += it.name },
        )
        assertEquals(
            listOf("左侧竖条", "右侧竖条", "顶部横条", "左侧抽屉", "右侧抽屉"),
            groups[0].map { it.label },
        )
        assertTrue(groups[0][2].selected)
        assertEquals(listOf("左侧", "右侧"), groups[1].map { it.label })
        assertTrue(groups[1][1].selected)
        assertEquals(listOf("展开大图", "缩略图"), groups[2].map { it.label })
        assertTrue(groups[2][1].selected)
        assertEquals("动态图片默认显示文字", groups[3].single().label)
        assertTrue(groups[3].single().selected)
        assertEquals("浏览动态时收起顶部栏", groups[4].single().label)
        assertFalse(groups[4].single().selected)
        groups[0][4].onClick!!.invoke()
        groups[3].single().onClick!!.invoke()
        groups[2][0].onClick!!.invoke()
        groups[4].single().onClick!!.invoke()
        groups[1][0].onClick!!.invoke()
        assertEquals(listOf("DRAWER_RIGHT", "preview:false", "layout:0", "collapse:true", "LEFT"), chosen)
    }
}
