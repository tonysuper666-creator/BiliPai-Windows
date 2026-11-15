package com.bilipai.desktop.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.home.resolveHomeFeedMaxContentWidth
import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the poster grid policy and checks that the compiled bangumi page consumes it. */
class DesktopBangumiHubLayoutTest {
    private fun hubWidth(window: Int, layout: DesktopWindowsUxHomeLayout = DesktopWindowsUxHomeLayout.DESKTOP): Int {
        val rail = desktopRailPresentation(DesktopWindowsUxRailMode.AUTO, window).footprintDp
        return desktopHomeFeedMeasuredWidth(window.dp, rail, layout, delegated = false).value.toInt()
    }

    @Test fun theAuditedWindowsAddPosterColumnsWithoutHorizontalOverflow() {
        for ((window, expected) in mapOf(960 to (888 to 7), 1600 to (1384 to 11), 3413 to (3197 to 26))) {
            val width = hubWidth(window)
            val cells = with(desktopBangumiPosterGridCells()) {
                Density(1f).calculateCrossAxisCellSizes(width - 24, 10)
            }
            assertEquals(expected.first, width, "hub width at $window")
            assertEquals(expected.second, cells.size, "poster columns at $window")
            assertTrue(cells.all { it in 112..128 }, "poster widths at $window: $cells")
            assertEquals(width - 24, cells.sum() + 10 * (cells.size - 1), "no overflow at $window")
        }
    }

    @Test fun classicKeepsTheHomeCapAndDropsThePhoneCap() {
        val vendored = resolveHomeFeedMaxContentWidth().value.toInt()
        assertEquals(vendored, hubWidth(3413, DesktopWindowsUxHomeLayout.CLASSIC))
        assertTrue(vendored > 800)
    }

    @Test fun theCompiledBangumiPageUsesPosterDensityAndTheUncappedWidth() {
        val folder = "com/android/purebilibili/feature/bangumi/"
        val root = File("build/patched-upstream/app/src/main/java/$folder")
        val transition = File("build/transition-patched/$folder")
        val hub = listOf(File(root, "BangumiHubContent.kt"), File(transition, "BangumiHubContent.kt"))
            .filter { it.isFile }.single().readText()
        val screen = if (File(root, "BangumiScreen.kt").isFile) File(root, "BangumiScreen.kt")
            else File(transition, "DesktopOriginalBangumiScreen.kt")
        assertEquals(1, Regex("columns = com\\.bilipai\\.desktop\\.ui\\.desktopBangumiPosterGridCells\\(\\)").findAll(hub).count())
        assertEquals(2, Regex("columns = GridCells\\.Adaptive\\(112\\.dp\\)").findAll(hub).count(), "index and search minimums")
        assertTrue("desktopResolveHomeFeedGridColumns(" !in hub, "posters must not use the video-card policy")
        assertTrue("PosterGridCard(" in hub)
        assertEquals(3, Regex("maxWidth = desktopBangumiHubWidth").findAll(screen.readText()).count())
    }
}
