package com.bilipai.desktop.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Review test for TON-1266 (PR #107 / #111): the recommend grid of the bangumi hub shows 3:4 posters.
 * Before: GridCells.Adaptive(112.dp) inside the 800 dp phone cap. After: the actual page policy
 * over the uncapped hub width. Both grids use contentPadding 12 + 12 and spacedBy(10.dp).
 * Widening the page must not show fewer posters per row than the capped page did at the same window.
 */
class DesktopBangumiPosterDensityReviewTest {
    private val padding = 24
    private val gap = 10

    private fun hubWidth(windowDp: Int): Int {
        val rail = desktopRailPresentation(DesktopWindowsUxRailMode.AUTO, windowDp).footprintDp
        return desktopHomeFeedMeasuredWidth(windowDp.dp, rail, DesktopWindowsUxHomeLayout.DESKTOP).value.toInt()
    }

    private fun columnsBefore(windowDp: Int): Int {
        val grid = minOf(hubWidth(windowDp), 800) - padding
        return ((grid + gap) / (112 + gap)).coerceAtLeast(1)
    }

    private fun cellsAfter(windowDp: Int): List<Int> = with(desktopBangumiPosterGridCells()) {
        Density(1f).calculateCrossAxisCellSizes(hubWidth(windowDp) - padding, gap)
    }

    private fun columnsAfter(windowDp: Int): Int = cellsAfter(windowDp).size

    private fun posterWidthAfter(windowDp: Int): Double {
        val columns = columnsAfter(windowDp)
        return (hubWidth(windowDp) - padding - gap * (columns - 1)).toDouble() / columns
    }

    @Test fun wideningTheHubNeverShowsFewerPostersPerRow() {
        val rows = listOf(960, 1280, 1600, 1920, 2560, 3413).map { window ->
            Triple(window, columnsBefore(window), columnsAfter(window))
        }
        val report = rows.joinToString("; ") { (window, before, after) ->
            "window $window dp: $before -> $after per row, poster ${"%.0f".format(posterWidthAfter(window))} dp"
        }
        assertTrue(rows.all { (_, before, after) -> after >= before }, "$report (the follow row on the same page stays 104 dp)")
    }
    @Test fun postersStayNearTheSameMinimumWidthAtEveryReviewedWindow() {
        for (window in listOf(960, 1280, 1600, 1920, 2560, 3413)) {
            val cells = cellsAfter(window)
            assertTrue(cells.all { it in 112..128 }, "window $window: $cells")
            assertEquals(hubWidth(window) - padding, cells.sum() + gap * (cells.size - 1))
        }
    }

}
