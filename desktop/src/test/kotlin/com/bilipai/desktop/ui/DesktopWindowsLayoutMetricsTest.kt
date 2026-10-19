package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode
import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure Home density rule against the shared contract: golden rows, the spec sweep, delegation. No window, no GPU, no network. */
class DesktopWindowsLayoutMetricsTest {
    private val feed = DesktopUxContract.section("feed")
    private val rules = feed.getValue("rules").jsonObject
    private val sweep = feed.getValue("sweep").jsonObject
    private val firstWidth = DesktopUxContract.int(sweep, "from")
    private val lastWidth = DesktopUxContract.int(sweep, "to")

    private fun layoutFor(mode: DesktopWindowsUxRailMode, width: Int, previous: DesktopRailPresentation? = null, minCard: Int? = null):
        Pair<DesktopRailPresentation, DesktopHomeDensityLayout> {
        val presentation = desktopRailPresentation(mode, width, previous)
        val content = desktopHomeDensityContentWidthDp(width, presentation.footprintDp, DesktopWindowsUxHomeLayout.DESKTOP)
        return presentation to resolveDesktopHomeDensity(content, minCard)
    }

    private fun walk(mode: DesktopWindowsUxRailMode, widths: IntProgression): List<Triple<Int, DesktopRailPresentation, DesktopHomeDensityLayout>> {
        val steps = ArrayList<Triple<Int, DesktopRailPresentation, DesktopHomeDensityLayout>>()
        var previous: DesktopRailPresentation? = null
        for (width in widths) {
            val (presentation, layout) = layoutFor(mode, width, previous)
            steps.add(Triple(width, presentation, layout))
            previous = presentation
        }
        return steps
    }

    @Test fun everyRuleOfTheContractHasItsConstant() {
        assertEquals(DesktopUxContract.int(rules, "side_padding"), DESKTOP_HOME_SIDE_PADDING_DP)
        assertEquals(DesktopUxContract.int(rules, "gutter"), DESKTOP_HOME_GUTTER_DP)
        assertEquals(DesktopUxContract.int(rules, "min_card"), DESKTOP_HOME_MIN_CARD_DP)
        assertEquals(DesktopUxContract.int(rules, "max_card"), DESKTOP_HOME_MAX_CARD_DP)
        assertEquals(DesktopUxContract.int(rules, "card_floor"), DESKTOP_HOME_CARD_FLOOR_DP)
        assertEquals(DesktopUxContract.int(rules, "min_columns"), DESKTOP_HOME_MIN_COLUMNS)
        assertEquals(DesktopUxContract.int(rules, "max_columns_below_wide"), DESKTOP_HOME_MAX_COLUMNS_BELOW_WIDE)
        assertEquals(DesktopUxContract.int(rules, "max_columns_wide"), DESKTOP_HOME_MAX_COLUMNS_WIDE)
        assertEquals(DesktopUxContract.int(rules, "wide_from_content_width"), DESKTOP_HOME_WIDE_FROM_CONTENT_DP)
        assertEquals(DesktopUxContract.int(rules, "compact_metadata_off_from_card"), DESKTOP_HOME_COMPACT_METADATA_OFF_FROM_CARD_DP)
        assertEquals(DesktopUxContract.int(rules, "min_content_width"), DESKTOP_HOME_MIN_CONTENT_DP)
        assertEquals(DesktopUxContract.int(rules, "dropped_legacy_cap"), DESKTOP_HOME_LEGACY_CAP_DP)
        assertEquals(1, DESKTOP_HOME_SINGLE_COLUMN_DISPLAY_MODE)
    }

    @Test fun theContentFloorIsTheExpandedSwitchPointMinusTheFootprint() {
        assertEquals(DESKTOP_RAIL_EXPANDED_THRESHOLDS.expandAt - DesktopRailPresentation.EXPANDED.footprintDp, DESKTOP_HOME_MIN_CONTENT_DP)
    }

    @Test fun everyGoldenRowOfTheContract() {
        for (row in DesktopUxContract.objects(feed.getValue("golden"))) {
            val id = DesktopUxContract.string(row, "id")
            val width = DesktopUxContract.int(row, "width_dp")
            val mode = row["mode"]?.jsonPrimitive?.content?.let { DesktopWindowsUxRailMode.valueOf(it) } ?: DesktopWindowsUxRailMode.AUTO
            val minCard = if ("min_card" in row) DesktopUxContract.int(row, "min_card") else null
            val (presentation, layout) = layoutFor(mode, width, minCard = minCard)
            val expect = row.getValue("expect").jsonObject
            assertEquals(DesktopUxContract.string(expect, "rail"), presentation.contractName, id)
            assertEquals(DesktopUxContract.int(expect, "wc"), layout.contentWidthDp, id)
            assertEquals(DesktopUxContract.int(expect, "cols"), layout.columns, id)
            assertEquals(DesktopUxContract.int(expect, "grid"), layout.gridWidthDp, id)
            assertEquals(expect.getValue("capped").jsonPrimitive.boolean, layout.capped, id)
            // The contract rounds the exact card to one decimal.
            assertTrue(abs(layout.cardWidthDp - DesktopUxContract.double(expect, "card")) <= 0.05 + 1e-9, id)
        }
    }

    @Test fun theSpecTableVerbatim() {
        // W, rail, Wc, columns, card, grid (spec 3.6, AUTO rail).
        val table = listOf(
            listOf(960, "collapsed", 888, 3, 269.3, 840), listOf(1088, "collapsed", 1016, 4, 230.0, 968),
            listOf(1279, "collapsed", 1207, 5, 219.0, 1159), listOf(1280, "expanded", 1064, 4, 242.0, 1016),
            listOf(1600, "expanded", 1384, 5, 254.4, 1336), listOf(1920, "expanded", 1704, 7, 222.9, 1656),
            listOf(2048, "expanded", 1832, 7, 241.1, 1784), listOf(2560, "expanded", 2344, 8, 273.0, 2296),
            listOf(2730, "expanded", 2514, 8, 294.2, 2466), listOf(3000, "expanded", 2784, 8, 320.0, 2672),
            listOf(3413, "expanded", 3197, 10, 300.5, 3149), listOf(3840, "expanded", 3624, 10, 320.0, 3344),
        )
        for (row in table) {
            val width = row[0] as Int
            val (presentation, layout) = layoutFor(DesktopWindowsUxRailMode.AUTO, width)
            assertEquals(row[1], presentation.contractName, width.toString())
            assertEquals(row[2], layout.contentWidthDp, width.toString())
            assertEquals(row[3], layout.columns, width.toString())
            assertEquals(row[5], layout.gridWidthDp, width.toString())
            assertTrue(abs(layout.cardWidthDp - (row[4] as Double)) <= 0.05 + 1e-9, width.toString())
            assertEquals(row[4] == 320.0, layout.capped, width.toString())
        }
    }

    @Test fun sweepPropertiesOfTheSpec() {
        val switchPoints = mapOf(DesktopWindowsUxRailMode.AUTO to 1280, DesktopWindowsUxRailMode.EXPANDED to 1100)
        for (mode in listOf(DesktopWindowsUxRailMode.AUTO, DesktopWindowsUxRailMode.EXPANDED, DesktopWindowsUxRailMode.COLLAPSED)) {
            val drops = ArrayList<Int>()
            val lastColumns = HashMap<DesktopRailPresentation, Int>()
            var previousColumns = 0
            for (width in firstWidth..lastWidth) {
                val (presentation, layout) = layoutFor(mode, width)
                val label = "$mode $width"
                assertTrue(layout.contentWidthDp >= DESKTOP_HOME_MIN_CONTENT_DP, label)
                assertTrue(layout.cardWidthDp >= DESKTOP_HOME_MIN_CARD_DP && layout.cardWidthDp <= DESKTOP_HOME_MAX_CARD_DP, label)
                assertTrue(layout.columns >= DESKTOP_HOME_MIN_COLUMNS, label)
                val limit = if (layout.contentWidthDp >= DESKTOP_HOME_WIDE_FROM_CONTENT_DP) DESKTOP_HOME_MAX_COLUMNS_WIDE else DESKTOP_HOME_MAX_COLUMNS_BELOW_WIDE
                assertTrue(layout.columns <= limit, label)
                assertTrue(layout.gridWidthDp <= layout.contentWidthDp - 2 * DESKTOP_HOME_SIDE_PADDING_DP, label)
                assertTrue(layout.columns >= (lastColumns[presentation] ?: 0), label)
                lastColumns[presentation] = layout.columns
                if (width > firstWidth && layout.columns < previousColumns) drops.add(width)
                previousColumns = layout.columns
            }
            val expected = switchPoints[mode]
            assertEquals(if (expected == null) emptyList<Int>() else listOf(expected), drops, mode.name)
        }
    }

    @Test fun anExactGridIsColumnsTimesCardPlusGutters() {
        for (width in firstWidth..lastWidth) {
            val (_, layout) = layoutFor(DesktopWindowsUxRailMode.AUTO, width)
            val exact = layout.columns * layout.cardWidthDp + (layout.columns - 1) * DESKTOP_HOME_GUTTER_DP
            assertTrue(abs(exact - layout.gridWidthDp) <= 1e-9, width.toString())
        }
    }

    @Test fun cardPresetsAreHonouredWithTheFloor() {
        for (preset in listOf(160, 200, 260, 320)) {
            for (width in firstWidth..lastWidth) {
                val (_, layout) = layoutFor(DesktopWindowsUxRailMode.AUTO, width, minCard = preset)
                assertTrue(layout.cardWidthDp >= preset && layout.cardWidthDp <= DESKTOP_HOME_MAX_CARD_DP, "$preset $width")
            }
        }
        val below = layoutFor(DesktopWindowsUxRailMode.AUTO, 1600, minCard = 100).second
        val floor = layoutFor(DesktopWindowsUxRailMode.AUTO, 1600, minCard = 160).second
        assertEquals(floor, below)
        assertTrue(floor.columns >= layoutFor(DesktopWindowsUxRailMode.AUTO, 1600, minCard = 320).second.columns)
    }

    @Test fun wideningAndNarrowingStayMonotoneInsideAPresentation() {
        for (mode in listOf(DesktopWindowsUxRailMode.AUTO, DesktopWindowsUxRailMode.EXPANDED)) {
            for ((widths, sign) in listOf((firstWidth..lastWidth) to 1, (lastWidth downTo firstWidth) to -1)) {
                var lastColumns = 0
                var lastRail: DesktopRailPresentation? = null
                for ((width, presentation, layout) in walk(mode, widths)) {
                    if (presentation == lastRail) assertTrue(sign * (layout.columns - lastColumns) >= 0, "$mode $width")
                    lastColumns = layout.columns
                    lastRail = presentation
                    assertTrue(layout.cardWidthDp >= DESKTOP_HOME_MIN_CARD_DP && layout.cardWidthDp <= DESKTOP_HOME_MAX_CARD_DP, "$mode $width")
                }
            }
        }
    }

    @Test fun atMostEightColumnsBelow2800AndTenFrom2800() {
        val below = resolveDesktopHomeDensity(2799)
        assertEquals(8, below.columns)
        assertTrue(below.capped)
        val at = resolveDesktopHomeDensity(2800)
        assertEquals(10, at.columns)
        assertEquals(false, at.capped)
        assertTrue(abs(at.cardWidthDp - 260.8) <= 1e-9)
        for (width in firstWidth..lastWidth) {
            val (_, layout) = layoutFor(DesktopWindowsUxRailMode.COLLAPSED, width)
            assertTrue(layout.columns <= if (layout.contentWidthDp >= 2800) 10 else 8, width.toString())
        }
    }

    @Test fun aCardOverTheMaximumIsHeldAndTheGridIsCentred() {
        val side = DESKTOP_HOME_SIDE_PADDING_DP.toDouble()
        for (mode in listOf(DesktopWindowsUxRailMode.AUTO, DesktopWindowsUxRailMode.EXPANDED, DesktopWindowsUxRailMode.COLLAPSED)) {
            for (width in firstWidth..lastWidth) {
                val layout = layoutFor(mode, width).second
                val label = "$mode $width"
                assertTrue(layout.sideInsetDp >= side, label)
                if (layout.capped) {
                    assertEquals(DESKTOP_HOME_MAX_CARD_DP.toDouble(), layout.cardWidthDp, label)
                    assertEquals(layout.columns * DESKTOP_HOME_MAX_CARD_DP + (layout.columns - 1) * DESKTOP_HOME_GUTTER_DP, layout.gridWidthDp, label)
                    assertTrue(layout.sideInsetDp > side, label)
                } else {
                    assertEquals(side, layout.sideInsetDp, label)
                    assertEquals(layout.contentWidthDp - 2 * DESKTOP_HOME_SIDE_PADDING_DP, layout.gridWidthDp, label)
                }
            }
        }
        assertEquals(56.0, layoutFor(DesktopWindowsUxRailMode.AUTO, 3000).second.sideInsetDp)
    }

    @Test fun theOldCapIsGoneFromTheDesktopContentWidth() {
        assertEquals(1832, desktopHomeDensityContentWidthDp(2048, 216, DesktopWindowsUxHomeLayout.DESKTOP))
        assertEquals(1280, desktopHomeDensityContentWidthDp(2048, 216, DesktopWindowsUxHomeLayout.CLASSIC))
        assertEquals(1000, desktopHomeDensityContentWidthDp(1000, 72, DesktopWindowsUxHomeLayout.CLASSIC))
        assertEquals(928, desktopHomeDensityContentWidthDp(1000, 72, DesktopWindowsUxHomeLayout.DESKTOP))
        assertTrue(layoutFor(DesktopWindowsUxRailMode.AUTO, 3000).second.gridWidthDp > DESKTOP_HOME_LEGACY_CAP_DP)
    }

    @Test fun delegationCases() {
        val desktop = DesktopWindowsUxHomeLayout.DESKTOP
        val classic = DesktopWindowsUxHomeLayout.CLASSIC
        assertNull(desktopHomeDensityDelegation(desktop, 0, 0))
        assertEquals(DesktopHomeDensityDelegation.SINGLE_COLUMN_MODE, desktopHomeDensityDelegation(desktop, 1, 0))
        // The vendored function ignores a pinned count in single-column mode, so that reason comes first.
        assertEquals(DesktopHomeDensityDelegation.SINGLE_COLUMN_MODE, desktopHomeDensityDelegation(desktop, 1, 4))
        assertEquals(DesktopHomeDensityDelegation.PINNED_COLUMN_COUNT, desktopHomeDensityDelegation(desktop, 0, 4))
        assertEquals(DesktopHomeDensityDelegation.PINNED_COLUMN_COUNT, desktopHomeDensityDelegation(desktop, 0, 1))
        assertNull(desktopHomeDensityDelegation(desktop, 0, -1))
        assertNull(desktopHomeDensityDelegation(desktop, 2, 0))
        assertEquals(DesktopHomeDensityDelegation.PINNED_COLUMN_COUNT, desktopHomeDensityDelegation(desktop, 2, 3))
        assertEquals(DesktopHomeDensityDelegation.CLASSIC_LAYOUT, desktopHomeDensityDelegation(classic, 0, 0))
        assertEquals(DesktopHomeDensityDelegation.CLASSIC_LAYOUT, desktopHomeDensityDelegation(classic, 1, 4))
        assertEquals(DesktopHomeDensityDelegation.CLASSIC_LAYOUT, desktopHomeDensityDelegation(classic, 0, 4))
    }

    @Test fun compactMetadataTurnsOffFromACardOf200() {
        assertTrue(desktopHomeDensityCompactMetadata(true, 199.9999))
        assertEquals(false, desktopHomeDensityCompactMetadata(true, 200.0))
        assertEquals(false, desktopHomeDensityCompactMetadata(true, 320.0))
        assertEquals(false, desktopHomeDensityCompactMetadata(false, 100.0))
        assertEquals(false, desktopHomeDensityCompactMetadata(false, 199.0))
        assertTrue(desktopHomeDensityCompactMetadata(true, 160.0))
        // The default minimum card is 216, so the desktop grid never keeps compact metadata at the default preset.
        for (width in firstWidth..lastWidth) {
            assertEquals(false, desktopHomeDensityCompactMetadata(true, layoutFor(DesktopWindowsUxRailMode.AUTO, width).second.cardWidthDp), width.toString())
        }
        // A 160 dp preset at 1280 gives a 190.4 dp card, which keeps compact metadata.
        assertTrue(desktopHomeDensityCompactMetadata(true, layoutFor(DesktopWindowsUxRailMode.AUTO, 1280, minCard = 160).second.cardWidthDp))
    }
}
