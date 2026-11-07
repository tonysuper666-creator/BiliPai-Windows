package com.bilipai.desktop.ui

import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.util.AppFoldPosture
import com.android.purebilibili.core.util.WindowHeightSizeClass
import com.android.purebilibili.core.util.WindowSizeClass
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.core.util.shouldUseSidebarNavigationForLayout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSidebarBreakpointParityTest {
    private fun window(width: WindowWidthSizeClass, device: WindowWidthSizeClass = width) = WindowSizeClass(
        widthSizeClass = width,
        heightSizeClass = WindowHeightSizeClass.Medium,
        widthDp = when (width) {
            WindowWidthSizeClass.Compact -> 480.dp
            WindowWidthSizeClass.Medium -> 700.dp
            else -> 1000.dp
        },
        heightDp = 1000.dp,
        deviceWidthSizeClass = device,
    )

    @Test
    fun tabletRailSurvivesMediumWidthAndReturnsAfterCompactSplit() {
        val tablet = WindowWidthSizeClass.Expanded
        for (width in listOf(tablet, WindowWidthSizeClass.Medium, tablet)) {
            assertTrue(shouldUseSidebarNavigationForLayout(window(width, tablet), true))
            assertFalse(shouldUseSidebarNavigationForLayout(window(width, tablet), false))
        }
        assertFalse(shouldUseSidebarNavigationForLayout(window(WindowWidthSizeClass.Compact, tablet), true))
    }

    @Test
    fun mediumWidthPhoneAndCompactFoldCoverKeepBottomNavigation() {
        val phone = WindowWidthSizeClass.Compact
        assertFalse(shouldUseSidebarNavigationForLayout(window(WindowWidthSizeClass.Medium, phone), true))
        assertFalse(shouldUseSidebarNavigationForLayout(window(phone, WindowWidthSizeClass.Large), true))
        assertTrue(shouldUseSidebarNavigationForLayout(window(WindowWidthSizeClass.Expanded, phone), true))
    }

    @Test
    fun tabletopDisablesRailEvenWhenTabletWindowIsWideEnough() {
        for (width in listOf(WindowWidthSizeClass.Medium, WindowWidthSizeClass.Expanded, WindowWidthSizeClass.Large)) {
            assertFalse(shouldUseSidebarNavigationForLayout(window(width), true, AppFoldPosture.Tabletop))
        }
    }
}
