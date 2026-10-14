package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.util.AppFoldPosture
import com.android.purebilibili.core.util.WindowHeightSizeClass
import com.android.purebilibili.core.util.WindowSizeClass
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.data.model.response.DrawItem
import com.android.purebilibili.data.model.response.DrawMajor
import com.android.purebilibili.data.model.response.DynamicContentModule
import com.android.purebilibili.data.model.response.DynamicItem
import com.android.purebilibili.data.model.response.DynamicMajor
import com.android.purebilibili.data.model.response.DynamicModules
import com.android.purebilibili.data.model.response.OpusContentBlock
import com.android.purebilibili.data.model.response.OpusMajor
import com.android.purebilibili.data.model.response.OpusPic
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

    @Test fun pageDirectionMirrorsHorizontalRailTabActionsAndHomeSplit() {
        val left = DesktopDynamicPageLayoutDirection.LEFT
        val right = DesktopDynamicPageLayoutDirection.RIGHT
        val horizontalLeft = resolveDynamicPageDirectionEffects(DesktopDynamicDisplayMode.HORIZONTAL, left, false)
        assertFalse(horizontalLeft.upRailReversed)
        assertFalse(horizontalLeft.tabActionsOnStart)
        assertFalse(horizontalLeft.detailContentOnRight)
        val horizontalRight = resolveDynamicPageDirectionEffects(DesktopDynamicDisplayMode.HORIZONTAL, right, false)
        assertTrue(horizontalRight.upRailReversed)
        assertTrue(horizontalRight.tabActionsOnStart)
        assertTrue(horizontalRight.detailContentOnRight)
        val sidebarCompact = resolveDynamicPageDirectionEffects(DesktopDynamicDisplayMode.SIDEBAR, right, false)
        assertTrue(sidebarCompact.sidebarOnRight)
        assertFalse(sidebarCompact.upRailReversed)
        assertFalse(sidebarCompact.tabActionsOnStart)
        assertTrue(sidebarCompact.detailContentOnRight)
        val sidebarSplit = resolveDynamicPageDirectionEffects(DesktopDynamicDisplayMode.SIDEBAR, right, true)
        assertTrue(sidebarSplit.sidebarOnRight)
        assertTrue(sidebarSplit.upRailReversed)
        assertTrue(sidebarSplit.tabActionsOnStart)
        assertTrue(sidebarSplit.detailContentOnRight)
        val sidebarSplitLeft = resolveDynamicPageDirectionEffects(DesktopDynamicDisplayMode.SIDEBAR, left, true)
        assertFalse(sidebarSplitLeft.sidebarOnRight)
        assertFalse(sidebarSplitLeft.upRailReversed)
        assertFalse(sidebarSplitLeft.tabActionsOnStart)
        assertFalse(sidebarSplitLeft.detailContentOnRight)
        val drawerSplit = resolveDynamicPageDirectionEffects(DesktopDynamicDisplayMode.DRAWER_LEFT, right, true)
        assertTrue(drawerSplit.sidebarOnRight)
        assertTrue(drawerSplit.upRailReversed)
        assertTrue(drawerSplit.tabActionsOnStart)
        assertTrue(drawerSplit.detailContentOnRight)
        val horizontalSplit = resolveDynamicPageDirectionEffects(DesktopDynamicDisplayMode.HORIZONTAL, right, true)
        assertEquals(horizontalRight, horizontalSplit)
    }

    @Test fun detailImageLayoutToggleProjectsDrawItemsOntoTheExpandedPainter() {
        assertEquals(DesktopDynamicDetailImagePresentation.EXPANDED_BODY, resolveDynamicDetailImagePresentation(0))
        assertEquals(DesktopDynamicDetailImagePresentation.THUMBNAIL_GRID, resolveDynamicDetailImagePresentation(1))
        assertEquals(1, nextDynamicDetailImageLayoutValue(0))
        assertEquals(0, nextDynamicDetailImageLayoutValue(1))
        val draw = dynamicItem(
            draw = listOf(DrawItem(src = " https://img.example/a.jpg ", width = 800, height = 600, live_url = "https://img.example/a.mp4")),
        )
        assertEquals(draw, projectDynamicDetailImagesForLayout(draw, layoutValue = 1, isDetail = true))
        assertEquals(draw, projectDynamicDetailImagesForLayout(draw, layoutValue = 0, isDetail = false))
        val expanded = projectDynamicDetailImagesForLayout(draw, layoutValue = 0, isDetail = true)
        assertEquals(listOf("https://img.example/a.jpg"), expanded.modules.module_dynamic?.major?.opus?.pics?.map { it.url })
        assertEquals(800, expanded.modules.module_dynamic?.major?.opus?.pics?.single()?.width)
        assertEquals("https://img.example/a.mp4", expanded.modules.module_dynamic?.major?.opus?.pics?.single()?.live_url)
        assertEquals(" https://img.example/a.jpg ", expanded.modules.module_dynamic?.major?.draw?.items?.single()?.src)
        val blank = dynamicItem(draw = listOf(DrawItem(src = "  ")))
        assertEquals(blank, projectDynamicDetailImagesForLayout(blank, layoutValue = 0, isDetail = true))
        val opus = dynamicItem(
            draw = listOf(DrawItem(src = "https://img.example/draw.jpg")),
            opus = OpusMajor(pics = listOf(OpusPic(url = "https://img.example/opus.jpg"))),
        )
        assertEquals(opus, projectDynamicDetailImagesForLayout(opus, layoutValue = 0, isDetail = true))
        val blocks = dynamicItem(
            draw = listOf(DrawItem(src = "https://img.example/draw.jpg")),
            opus = OpusMajor(contentBlocks = listOf(OpusContentBlock.Image(OpusPic(url = "https://img.example/block.jpg")))),
        )
        assertEquals(blocks, projectDynamicDetailImagesForLayout(blocks, layoutValue = 0, isDetail = true))
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

private fun dynamicItem(draw: List<DrawItem>, opus: OpusMajor? = null): DynamicItem = DynamicItem(
    id_str = "1",
    modules = DynamicModules(
        module_dynamic = DynamicContentModule(
            major = DynamicMajor(draw = DrawMajor(items = draw), opus = opus),
        ),
    ),
)
