package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.feature.home.components.BottomNavItem
import com.android.purebilibili.navigation.AppSystemBackAction
import com.android.purebilibili.navigation.resolveAppSystemBackAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLargeScreenAccessibilityTest {
    @Test fun focusAxisFollowsTheShellPlacement() {
        assertEquals(
            DesktopLargeScreenFocusAxis.VERTICAL,
            resolveDesktopLargeScreenFocusAxis(DesktopShellNavigationPlacement.SIDEBAR),
        )
        assertEquals(
            DesktopLargeScreenFocusAxis.HORIZONTAL,
            resolveDesktopLargeScreenFocusAxis(DesktopShellNavigationPlacement.BOTTOM_DOCK),
        )
        assertNull(resolveDesktopLargeScreenFocusAxis(DesktopShellNavigationPlacement.HIDDEN))
    }

    @Test fun arrowsMoveOnThePlacementAxisAndLeaveTabAlone() {
        val vertical = DesktopLargeScreenFocusAxis.VERTICAL
        val horizontal = DesktopLargeScreenFocusAxis.HORIZONTAL
        assertEquals(DesktopLargeScreenFocusStep.PREVIOUS, resolveDesktopLargeScreenFocusStep(Key.DirectionUp, vertical))
        assertEquals(DesktopLargeScreenFocusStep.NEXT, resolveDesktopLargeScreenFocusStep(Key.DirectionDown, vertical))
        assertNull(resolveDesktopLargeScreenFocusStep(Key.DirectionLeft, vertical))
        assertNull(resolveDesktopLargeScreenFocusStep(Key.DirectionRight, vertical))
        assertNull(resolveDesktopLargeScreenFocusStep(Key.Tab, vertical))
        assertNull(resolveDesktopLargeScreenFocusStep(Key.Escape, vertical))
        assertEquals(DesktopLargeScreenFocusStep.PREVIOUS, resolveDesktopLargeScreenFocusStep(Key.DirectionLeft, horizontal))
        assertEquals(DesktopLargeScreenFocusStep.NEXT, resolveDesktopLargeScreenFocusStep(Key.DirectionRight, horizontal))
        assertNull(resolveDesktopLargeScreenFocusStep(Key.DirectionUp, horizontal))
        assertNull(resolveDesktopLargeScreenFocusStep(Key.DirectionDown, horizontal))
        assertEquals(
            androidx.compose.ui.focus.FocusDirection.Up,
            desktopLargeScreenFocusDirection(DesktopLargeScreenFocusStep.PREVIOUS, vertical),
        )
        assertEquals(
            androidx.compose.ui.focus.FocusDirection.Down,
            desktopLargeScreenFocusDirection(DesktopLargeScreenFocusStep.NEXT, vertical),
        )
        assertEquals(
            androidx.compose.ui.focus.FocusDirection.Left,
            desktopLargeScreenFocusDirection(DesktopLargeScreenFocusStep.PREVIOUS, horizontal),
        )
        assertEquals(
            androidx.compose.ui.focus.FocusDirection.Right,
            desktopLargeScreenFocusDirection(DesktopLargeScreenFocusStep.NEXT, horizontal),
        )
    }

    @Test fun escapeLeavesFullscreenBeforeClosingAnOpenDynamicDrawer() {
        assertEquals(
            DesktopLargeScreenEscapeAction.EXIT_FULLSCREEN,
            resolveDesktopLargeScreenEscapeAction(fullscreen = true, dynamicDrawerExpanded = true),
        )
        assertEquals(
            DesktopLargeScreenEscapeAction.CLOSE_DYNAMIC_DRAWER,
            resolveDesktopLargeScreenEscapeAction(fullscreen = false, dynamicDrawerExpanded = true),
        )
        assertEquals(
            DesktopLargeScreenEscapeAction.SYSTEM_BACK,
            resolveDesktopLargeScreenEscapeAction(fullscreen = false, dynamicDrawerExpanded = false),
        )
    }

    @Test fun systemBackKeepsTheOriginalMainHostPolicy() {
        assertEquals(
            AppSystemBackAction.NAVIGATE_UP,
            resolveAppSystemBackAction(isAtMainHostRoot = false, currentBottomItem = BottomNavItem.DYNAMIC),
        )
        assertEquals(
            AppSystemBackAction.RETURN_TO_HOME_TAB,
            resolveAppSystemBackAction(isAtMainHostRoot = true, currentBottomItem = BottomNavItem.DYNAMIC),
        )
        assertEquals(
            AppSystemBackAction.FINISH_ACTIVITY,
            resolveAppSystemBackAction(isAtMainHostRoot = true, currentBottomItem = BottomNavItem.HOME),
        )
    }

    @Test fun iconOnlyChromeUsesOneDescriptionAndTheShared48DpTarget() {
        assertEquals(48.dp, AppChromeSizeTokens.MinimumTouchTarget)
        assertEquals(AppChromeSizeTokens.MinimumTouchTarget, desktopLargeScreenMinimumTarget())
        assertEquals("搜索", DesktopShellSearchDescription)
        assertEquals("下载与离线", DesktopShellDownloadsDescription)
        assertEquals("收起关注列表", resolveDynamicSidebarHeaderDescription(expanded = true))
        assertEquals("展开关注列表", resolveDynamicSidebarHeaderDescription(expanded = false))
        assertEquals("隐藏已隐藏用户", resolveDynamicHiddenUsersDescription(showingHidden = true))
        assertEquals("显示隐藏用户", resolveDynamicHiddenUsersDescription(showingHidden = false))
        assertEquals("展开左侧关注列表", resolveDynamicDrawerHandleDescription(onRight = false))
        assertEquals("展开右侧关注列表", resolveDynamicDrawerHandleDescription(onRight = true))
        assertEquals("关注用户，已置顶，已隐藏", resolveDynamicUserContentDescription("  ", hidden = true, pinned = true))
        assertEquals("小明", resolveDynamicUserContentDescription("小明", hidden = false, pinned = false))
        assertEquals("小明，已置顶", resolveDynamicUserContentDescription("小明", hidden = false, pinned = true))
        assertEquals("展开顶部操作", resolveDynamicActionDockDescription(collapsed = true))
        assertEquals("折叠顶部操作", resolveDynamicActionDockDescription(collapsed = false))
        assertEquals("关注列表位置", resolveDynamicDisplayModeDescription())
        assertEquals("收起直播，2 人", resolveDynamicLiveFoldDescription(expanded = true, count = 2))
        assertEquals("展开直播，2 人", resolveDynamicLiveFoldDescription(expanded = false, count = 2))
    }
}
