package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxDetailsPanel
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure player layout policy against the shared contract: golden rows, the spec table, exact ties, invariants. No window, no GPU, no network. */
class DesktopPlayerLayoutPolicyTest {
    private val player = DesktopUxContract.section("player")
    private val rules = player.getValue("rules").jsonObject

    private fun panelOf(row: JsonObject): DesktopWindowsUxDetailsPanel = when (row["panel"]?.jsonPrimitive?.content) {
        "OPEN" -> DesktopWindowsUxDetailsPanel.OPEN
        "CLOSED" -> DesktopWindowsUxDetailsPanel.CLOSED
        else -> DesktopWindowsUxDetailsPanel.AUTO
    }

    private fun layoutOf(row: JsonObject): DesktopPlayerLayout = resolveDesktopPlayerLayout(
        widthDp = DesktopUxContract.int(row, "w"),
        heightDp = DesktopUxContract.int(row, "h"),
        panelDefaultOpen = desktopPlayerPanelDefaultOpen(
            if ("entry_width" in row) DesktopUxContract.int(row, "entry_width") else DesktopUxContract.int(row, "w"),
        ),
        fullscreen = row["mode"]?.jsonPrimitive?.content == "fullscreen",
        panel = panelOf(row),
        userPanelWidthDp = if ("user_panel_width" in row) DesktopUxContract.int(row, "user_panel_width") else null,
    )

    /** The layout of a page that was entered at entry dp (the current width unless given): the default is decided from it once. */
    private fun layout(
        w: Int,
        h: Int,
        fullscreen: Boolean = false,
        panel: DesktopWindowsUxDetailsPanel = DesktopWindowsUxDetailsPanel.AUTO,
        entry: Int = w,
        user: Int? = null,
    ) = resolveDesktopPlayerLayout(w, h, desktopPlayerPanelDefaultOpen(entry), fullscreen, panel, user)

    @Test fun everyRuleOfTheContractHasItsConstant() {
        val aspect = DesktopUxContract.ints(rules.getValue("aspect"))
        assertEquals(aspect, listOf(DESKTOP_PLAYER_ASPECT_WIDTH, DESKTOP_PLAYER_ASPECT_HEIGHT))
        assertEquals(DesktopUxContract.int(rules, "default_open_from_width"), DESKTOP_PLAYER_PANEL_DEFAULT_OPEN_FROM_WIDTH_DP)
        assertEquals(DesktopUxContract.int(rules, "dock_min_window_width"), DESKTOP_PLAYER_DOCK_MIN_WINDOW_WIDTH_DP)
        assertEquals(DesktopUxContract.int(rules, "fullscreen_min_aspect"), DESKTOP_PLAYER_FULLSCREEN_MIN_ASPECT)
        assertEquals(DesktopUxContract.int(rules, "panel_min"), DESKTOP_PLAYER_PANEL_MIN_DP)
        assertEquals(DesktopUxContract.int(rules, "panel_max"), DESKTOP_PLAYER_PANEL_MAX_DP)
        assertEquals(DesktopUxContract.int(rules, "default_percent"), DESKTOP_PLAYER_PANEL_DEFAULT_PERCENT)
        assertEquals(DesktopUxContract.ints(rules.getValue("default_clamp")), listOf(DESKTOP_PLAYER_PANEL_DEFAULT_LOW_DP, DESKTOP_PLAYER_PANEL_DEFAULT_HIGH_DP))
        assertEquals(DesktopUxContract.int(rules, "min_stage"), DESKTOP_PLAYER_MIN_STAGE_DP)
        assertEquals(DesktopUxContract.int(rules, "tier_l_min"), DESKTOP_PLAYER_TIER_L_MIN_DP)
        assertEquals(DesktopUxContract.int(rules, "tier_m_min"), DESKTOP_PLAYER_TIER_M_MIN_DP)
        assertEquals(DesktopUxContract.int(rules, "dock_capsule_max"), DESKTOP_PLAYER_DOCK_CAPSULE_MAX_DP)
        assertEquals(DesktopUxContract.int(rules, "dock_capsule_margin"), DESKTOP_PLAYER_DOCK_CAPSULE_MARGIN_DP)
    }

    @Test fun everyGoldenRowOfTheContract() {
        val rows = DesktopUxContract.objects(player.getValue("golden"))
        assertEquals(22, rows.size)
        for (row in rows) {
            val id = DesktopUxContract.string(row, "id")
            val expect = row.getValue("expect").jsonObject
            val bars = expect.getValue("bars").jsonObject
            val got = layoutOf(row)
            assertEquals(expect.getValue("docked").jsonPrimitive.boolean, got.docked, id)
            assertEquals(DesktopUxContract.int(expect, "panel"), got.panelWidthDp, id)
            assertEquals(DesktopUxContract.ints(expect.getValue("video")), listOf(got.videoWidthDp, got.videoHeightDp), id)
            assertEquals(DesktopUxContract.string(expect, "tier"), got.tier.name, id)
            assertEquals(DesktopUxContract.string(bars, "axis"), got.barAxis.contractName, id)
            assertEquals(DesktopUxContract.int(bars, "size"), got.barDp, id)
            assertEquals(DesktopUxContract.int(expect, "dock"), got.dockWidthDp, id)
        }
    }

    private data class SpecRow(
        val w: Int, val h: Int, val fullscreen: Boolean, val panel: DesktopWindowsUxDetailsPanel, val panelDp: Int,
        val videoW: Int, val videoH: Int, val tier: DesktopPlayerTier, val axis: DesktopPlayerBarAxis, val bar: Int,
    )

    @Test fun theSpecTableVerbatim() {
        val auto = DesktopWindowsUxDetailsPanel.AUTO
        val open = DesktopWindowsUxDetailsPanel.OPEN
        val closed = DesktopWindowsUxDetailsPanel.CLOSED
        val topBottom = DesktopPlayerBarAxis.TOP_BOTTOM
        val leftRight = DesktopPlayerBarAxis.LEFT_RIGHT
        val l = DesktopPlayerTier.L
        val table = listOf(
            SpecRow(960, 680, false, auto, 0, 960, 540, l, topBottom, 70),
            SpecRow(960, 680, false, open, 340, 620, 349, DesktopPlayerTier.S, topBottom, 166),
            SpecRow(1088, 720, false, auto, 0, 1088, 612, l, topBottom, 54),
            SpecRow(1088, 720, false, open, 340, 748, 421, DesktopPlayerTier.M, topBottom, 150),
            SpecRow(1280, 690, false, auto, 340, 940, 529, DesktopPlayerTier.M, topBottom, 81),
            SpecRow(2048, 1101, false, auto, 451, 1597, 899, l, topBottom, 101),
            SpecRow(2048, 1101, false, closed, 0, 1957, 1101, l, leftRight, 45),
            SpecRow(2048, 1152, true, auto, 0, 2048, 1152, l, DesktopPlayerBarAxis.NONE, 0),
            SpecRow(2731, 1101, false, auto, 560, 1957, 1101, l, leftRight, 107),
            SpecRow(2731, 1152, true, open, 560, 2048, 1152, l, leftRight, 62),
            SpecRow(2731, 1152, true, closed, 0, 2048, 1152, l, leftRight, 342),
            SpecRow(3413, 1440, false, auto, 560, 2560, 1440, l, leftRight, 146),
        )
        for (row in table) {
            val label = "${row.w}x${row.h} ${row.panel}"
            val got = layout(row.w, row.h, row.fullscreen, row.panel)
            assertEquals(row.panelDp, got.panelWidthDp, label)
            assertEquals(row.panelDp > 0, got.docked, label)
            assertEquals(listOf(row.videoW, row.videoH), listOf(got.videoWidthDp, got.videoHeightDp), label)
            assertEquals(row.tier, got.tier, label)
            assertEquals(row.axis, got.barAxis, label)
            assertEquals(row.bar, got.barDp, label)
        }
    }

    @Test fun exactTiesRoundHalfToEvenNotUp() {
        // 9 * 968 / 16 = 544.5, 9 * 1016 / 16 = 571.5, 9 * 1000 / 16 = 562.5: the even neighbours are 544, 572 and 562.
        for ((width, expected) in listOf(968 to 544, 1016 to 572, 1000 to 562)) {
            val got = layout(width, 700)
            assertEquals(expected, got.videoHeightDp, width.toString())
            assertEquals(width, got.videoWidthDp, width.toString())
        }
        assertEquals(listOf(0, 0, 2, 2, 4, 4), listOf(0L, 1L, 4L, 5L, 8L, 9L).map { desktopPlayerRoundHalfEven(it, 2L).toInt() })
        assertEquals(listOf(2, 2, 3, 4), listOf(3L, 4L, 6L, 7L).map { desktopPlayerRoundHalfEven(it, 2L).toInt() })
        assertEquals(listOf(1, 2, 2), listOf(4L, 5L, 6L).map { desktopPlayerRoundHalfEven(it, 3L).toInt() })
    }

    @Test fun everyWidthBoundHeightAndBarEqualsAnExactDecimalOracle() {
        // No panel below 1280 dp, a tall window: the video is width by 9 / 16 of the width, and the bar is half the rest. Both are
        // exact decimals with at most five digits, so BigDecimal rounds the true value, with the ties, as the contract does.
        val height = 2000
        var ties = 0
        for (width in 1..1279) {
            val got = layout(width, height)
            val exactHeight = BigDecimal(9 * width).divide(BigDecimal(16))
            val exactBar = (BigDecimal(height) - exactHeight).divide(BigDecimal(2))
            assertEquals(exactHeight.setScale(0, RoundingMode.HALF_EVEN).toInt(), got.videoHeightDp, "height of $width")
            assertEquals(exactBar.setScale(0, RoundingMode.HALF_EVEN).toInt(), got.barDp, "bar of $width")
            assertEquals(DesktopPlayerBarAxis.TOP_BOTTOM, got.barAxis, width.toString())
            if (exactHeight.remainder(BigDecimal.ONE).compareTo(BigDecimal("0.5")) == 0) ties++
        }
        assertTrue(ties > 50, "the sweep must contain exact ties")
    }

    @Test fun tierEdgesUseTheExactVideoWidth() {
        assertEquals(listOf(DesktopPlayerTier.M, DesktopPlayerTier.L), listOf(layout(959, 680).tier, layout(960, 680).tier))
        assertEquals(listOf(DesktopPlayerTier.S, DesktopPlayerTier.M), listOf(layout(639, 400).tier, layout(640, 400).tier))
        // 19:10 video, 505 dp high: the exact width is 959.5, which rounds to 960 but is still tier M.
        val tie = resolveDesktopPlayerLayout(1000, 505, desktopPlayerPanelDefaultOpen(1000), aspectWidth = 19, aspectHeight = 10)
        assertEquals(listOf(960, 505), listOf(tie.videoWidthDp, tie.videoHeightDp))
        assertEquals(DesktopPlayerTier.M, tie.tier)
        assertEquals(DesktopPlayerTier.L, resolveDesktopPlayerLayout(1000, 506, desktopPlayerPanelDefaultOpen(1000), aspectWidth = 19, aspectHeight = 10).tier)
    }

    @Test fun theDefaultPanelIsDecidedOnceAtEntryAndNeverOnResize() {
        assertFalse(layout(1600, 900, entry = 1200).docked)
        assertTrue(layout(1000, 900, entry = 1280).docked)
        assertFalse(layout(1600, 900, entry = 1279).docked)
        assertTrue(layout(1600, 900, entry = 1280).docked)
        val open = DesktopWindowsUxDetailsPanel.OPEN
        val closed = DesktopWindowsUxDetailsPanel.CLOSED
        assertEquals(layout(1000, 900, panel = open), layout(1000, 900, panel = open, entry = 100))
        assertEquals(layout(2500, 900, panel = closed), layout(2500, 900, panel = closed, entry = 5000))
        assertTrue(desktopPlayerPanelDefaultOpen(1280))
        assertFalse(desktopPlayerPanelDefaultOpen(1279))
        assertTrue(desktopPlayerPanelWanted(DesktopWindowsUxDetailsPanel.AUTO, desktopPlayerPanelDefaultOpen(1280)))
        assertFalse(desktopPlayerPanelWanted(DesktopWindowsUxDetailsPanel.AUTO, desktopPlayerPanelDefaultOpen(1279)))
        assertTrue(desktopPlayerPanelWanted(open, false))
        assertFalse(desktopPlayerPanelWanted(closed, true))
    }

    @Test fun aRememberedDefaultKeepsThePanelAcrossEveryResize() {
        // The leaf decides the default once and passes the same boolean on every resize: the panel then follows only the
        // window size limits (docked from 800 dp with the stage kept at 480), never the width at which the page was entered.
        val wideEntry = desktopPlayerPanelDefaultOpen(1400)
        val narrowEntry = desktopPlayerPanelDefaultOpen(900)
        assertTrue(wideEntry)
        assertFalse(narrowEntry)
        for (width in 800..4000 step 37) {
            assertTrue(resolveDesktopPlayerLayout(width, 900, wideEntry).docked, "wide entry at $width")
        }
        for (width in 640..4000 step 37) {
            assertFalse(resolveDesktopPlayerLayout(width, 900, narrowEntry).docked, "narrow entry at $width")
        }
    }

    @Test fun thePanelDocksWindowedFrom640AndInFullscreenOnlyFromAnAspectOfTwo() {
        val open = DesktopWindowsUxDetailsPanel.OPEN
        assertFalse(layout(639, 400, panel = open).docked)
        assertTrue(layout(800, 400, panel = open).docked)
        assertTrue(layout(2304, 1152, true, open).docked)
        assertFalse(layout(2303, 1152, true, open).docked)
        assertFalse(layout(1920, 1080, true, open).docked)
        assertTrue(layout(3440, 1440, true, open).docked)
    }

    @Test fun thePanelWidthClampsAndTheStageStaysWideEnough() {
        val open = DesktopWindowsUxDetailsPanel.OPEN
        assertEquals(320, layout(1600, 900, panel = open, user = 100).panelWidthDp)
        assertEquals(560, layout(2000, 1000, panel = open, user = 900).panelWidthDp)
        assertEquals(400, layout(2000, 1000, panel = open, user = 400).panelWidthDp)
        assertEquals(520, layout(1000, 600, panel = open, user = 560).panelWidthDp) // width - 480 binds
        val tooNarrow = layout(790, 600, panel = open, user = 560)
        assertFalse(tooNarrow.docked)
        assertEquals(0, tooNarrow.panelWidthDp)
        val justEnough = layout(800, 600, panel = open, user = 560)
        assertTrue(justEnough.docked)
        assertEquals(320, justEnough.panelWidthDp)
        for (width in 640..4400 step 31) {
            for (got in listOf(layout(width, 800, panel = open), layout(width, 800, panel = open, user = 560))) {
                assertTrue(got.panelWidthDp == 0 || got.panelWidthDp in DESKTOP_PLAYER_PANEL_MIN_DP..minOf(DESKTOP_PLAYER_PANEL_MAX_DP, width - 480), width.toString())
            }
        }
    }

    @Test fun extraWidthGoesToThePanelFirstThenToTheMargins() {
        val wide = layout(3000, 1000, panel = DesktopWindowsUxDetailsPanel.OPEN)
        assertEquals(560, wide.panelWidthDp)
        assertEquals(DesktopPlayerBarAxis.LEFT_RIGHT, wide.barAxis)
        assertEquals(1000, wide.videoHeightDp)
        assertEquals(listOf(1778, 1000), layout(3000, 1000, panel = DesktopWindowsUxDetailsPanel.CLOSED).let { listOf(it.videoWidthDp, it.videoHeightDp) })
    }

    @Test fun theDockCapsuleWidth() {
        assertEquals(1280, layout(2048, 1101).dockWidthDp)
        assertEquals(928, layout(960, 680).dockWidthDp)
        assertEquals(0, layout(300, 10).dockWidthDp) // a video narrower than the margin has no negative dock
        assertEquals(0, layout(1, 1).dockWidthDp)
        for (width in 100..4400 step 61) {
            for (height in listOf(90, 700, 1101)) {
                assertTrue(layout(width, height).dockWidthDp in 0..DESKTOP_PLAYER_DOCK_CAPSULE_MAX_DP, "$width x $height")
            }
        }
    }

    @Test fun invariantsOfEveryLayoutOfTheSweep() {
        for (width in 100..4400 step 41) {
            for (height in listOf(60, 400, 680, 1101, 1440)) {
                for (fullscreen in listOf(false, true)) {
                    for (panel in DesktopWindowsUxDetailsPanel.entries) {
                        val got = layout(width, height, fullscreen, panel)
                        val label = "$width x $height $fullscreen $panel"
                        assertTrue(got.videoWidthDp <= width && got.videoHeightDp <= height, label)
                        assertTrue(got.videoWidthDp + got.panelWidthDp <= width + 1, label)
                        if (got.docked) {
                            assertTrue(got.panelWidthDp >= DESKTOP_PLAYER_PANEL_MIN_DP && width - got.panelWidthDp >= DESKTOP_PLAYER_MIN_STAGE_DP, label)
                            assertTrue(if (fullscreen) width >= DESKTOP_PLAYER_FULLSCREEN_MIN_ASPECT * height else width >= DESKTOP_PLAYER_DOCK_MIN_WINDOW_WIDTH_DP, label)
                        }
                        if (got.barAxis == DesktopPlayerBarAxis.LEFT_RIGHT) assertEquals(height, got.videoHeightDp, label)
                        if (got.barAxis == DesktopPlayerBarAxis.TOP_BOTTOM) assertEquals(width - got.panelWidthDp, got.videoWidthDp, label)
                        if (got.tier == DesktopPlayerTier.L) assertTrue(got.videoWidthDp >= DESKTOP_PLAYER_TIER_L_MIN_DP, label)
                        if (got.tier == DesktopPlayerTier.S) assertTrue(got.videoWidthDp <= DESKTOP_PLAYER_TIER_M_MIN_DP, label)
                    }
                }
            }
        }
    }

    @Test fun badInputGivesTheEmptyLayoutAndABadAspectTheDefaultAspect() {
        val empty = DesktopPlayerLayout(false, 0, 0, 0, DesktopPlayerTier.S, DesktopPlayerBarAxis.NONE, 0, 0)
        for ((width, height) in listOf(0 to 700, 700 to 0, -5 to 700, 100001 to 700, 700 to 100001)) {
            assertEquals(empty, layout(width, height), "$width x $height")
        }
        val normal = layout(1000, 700)
        assertEquals(normal, resolveDesktopPlayerLayout(1000, 700, false, aspectWidth = 0, aspectHeight = 9))
        assertEquals(normal, resolveDesktopPlayerLayout(1000, 700, false, aspectWidth = 16, aspectHeight = -1))
        assertEquals(normal, resolveDesktopPlayerLayout(1000, 700, false, aspectWidth = 10001, aspectHeight = 9))
        assertFalse(normal == resolveDesktopPlayerLayout(1000, 700, false, aspectWidth = 4, aspectHeight = 3))
    }

    @Test fun theLargestInputsStayInsideALong() {
        val big = DESKTOP_PLAYER_MAX_EXTENT_DP
        val got = resolveDesktopPlayerLayout(big, big, true, aspectWidth = DESKTOP_PLAYER_MAX_ASPECT_PART, aspectHeight = DESKTOP_PLAYER_MAX_ASPECT_PART)
        assertTrue(got.videoWidthDp in 1..big && got.videoHeightDp in 1..big)
        val tall = resolveDesktopPlayerLayout(big, big, true, aspectWidth = 1, aspectHeight = DESKTOP_PLAYER_MAX_ASPECT_PART)
        assertTrue(tall.videoWidthDp in 1..big && tall.videoHeightDp in 1..big)
    }
}
