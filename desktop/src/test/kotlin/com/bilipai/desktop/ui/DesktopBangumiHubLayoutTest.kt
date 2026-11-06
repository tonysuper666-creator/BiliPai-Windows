package com.bilipai.desktop.ui

import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeFeedCardWidthPreset
import com.android.purebilibili.feature.home.resolveHomeFeedMaxContentWidth
import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bangumi hub width and homepage columns at the three windows from the desktop audit (C2).
 * The page calls the Home feed functions with the unpinned desktop arguments; these checks are that call.
 */
class DesktopBangumiHubLayoutTest {
    private val desktop = DesktopWindowsUxHomeLayout.DESKTOP
    private val windows = listOf(960, 1600, 3413)

    private fun homeWidth(windowDp: Int, layout: DesktopWindowsUxHomeLayout = desktop): Int {
        val rail = desktopRailPresentation(DesktopWindowsUxRailMode.AUTO, windowDp).footprintDp
        return desktopHomeFeedMeasuredWidth(windowDp.dp, rail, layout, delegated = false).value.toInt()
    }

    private fun homeColumns(contentWidthDp: Int, layout: DesktopWindowsUxHomeLayout = desktop): Int =
        desktopResolveHomeFeedGridColumns(
            layout = layout,
            contentWidthDp = contentWidthDp,
            displayMode = 0,
            fixedColumnCount = 0,
            cardWidthPreset = HomeFeedCardWidthPreset.AUTO,
        )

    @Test fun theThreeAuditedWindowsMatchTheHomeFeedAndFitWithoutAHorizontalScroll() {
        val expect = mapOf(960 to (888 to 3), 1600 to (1384 to 5), 3413 to (3197 to 10))
        for (window in windows) {
            val width = homeWidth(window)
            val columns = homeColumns(width)
            val density = resolveDesktopHomeDensity(width)
            assertEquals(expect.getValue(window).first, width, "width $window")
            assertEquals(expect.getValue(window).second, columns, "columns $window")
            assertEquals(density.columns, columns, "density $window")
            assertTrue(width <= window, "width $window")
            assertTrue(density.gridWidthDp <= width, "grid $window ${density.gridWidthDp}")
            assertTrue(columns >= DESKTOP_HOME_MIN_COLUMNS, "columns $window")
        }
    }

    @Test fun classicKeepsTheHomeCapAndDropsThePhoneCap() {
        val vendored = resolveHomeFeedMaxContentWidth().value.toInt()
        assertEquals(vendored, homeWidth(3413, DesktopWindowsUxHomeLayout.CLASSIC))
        assertTrue(vendored > 800)
        assertEquals(
            resolveDesktopHomeDensity(homeWidth(1600)).columns,
            homeColumns(homeWidth(1600)),
        )
    }

    @Test fun aNarrowWindowStaysAtLeastTwoColumnsInsideTheContentWidth() {
        val width = homeWidth(960)
        val columns = homeColumns(width)
        assertEquals(3, columns)
        assertTrue(resolveDesktopHomeDensity(width).gridWidthDp <= width)
    }
}
