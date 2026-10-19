package com.bilipai.desktop.ux

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shape and internal-consistency checks of the shared contract resource. No window, no GPU, no network.
 * The policies that read the contract (rail, feed, player layout) are tested against it by their own slices;
 * this class proves that the resource is on the classpath, parses, and that its derived rows agree with
 * their own parameters in plain integer arithmetic.
 */
class DesktopUxContractTest {
    private val feed = DesktopUxContract.section("feed")
    private val player = DesktopUxContract.section("player")

    @Test fun theResourceParsesAndHasEverySection() {
        assertEquals(1, DesktopUxContract.int(DesktopUxContract.document, "schema"))
        for (name in listOf("displays", "tokens", "flags", "rail", "feed", "player", "chrome", "pointer", "shortcuts", "flyout")) {
            assertTrue(name in DesktopUxContract.document, name)
        }
    }

    @Test fun displaysCarryTheMeasuredPrimaryAndTheAssumedSecondary() {
        val displays = DesktopUxContract.section("displays")
        val primary = displays.getValue("primary").jsonObject
        val secondary = displays.getValue("secondary").jsonObject
        assertEquals(listOf(5120, 2160), DesktopUxContract.ints(primary.getValue("px")))
        assertEquals("measured", DesktopUxContract.string(primary, "status"))
        assertEquals(150, DesktopUxContract.int(primary, "windows_scale_percent"))
        assertEquals(125, DesktopUxContract.int(primary, "app_zoom_percent"))
        assertEquals(listOf(3840, 2160), DesktopUxContract.ints(secondary.getValue("px")))
        assertEquals("assumed", DesktopUxContract.string(secondary, "status"))
        // dp = px / (windows scale x app zoom): 5120 / 1.875 and 3840 / 1.875.
        assertEquals(2731, DesktopUxContract.int(primary.getValue("derived").jsonObject, "width_dp"))
        assertEquals(2048, DesktopUxContract.int(secondary.getValue("derived").jsonObject, "width_dp"))
        assertEquals(1101, DesktopUxContract.int(primary.getValue("derived").jsonObject, "maximised_height_dp"))
    }

    @Test fun statusMarkersAndTheSmallParametersAreInTheResource() {
        // The maximised height rests on the assumed chrome, even on the measured panel.
        val primaryStatus = DesktopUxContract.section("displays").getValue("primary").jsonObject.getValue("derived_status").jsonObject
        assertEquals("measured", DesktopUxContract.string(primaryStatus, "width_dp"))
        assertEquals("assumed", DesktopUxContract.string(primaryStatus, "maximised_height_dp"))
        val rail = DesktopUxContract.section("rail")
        assertEquals(56, DesktopUxContract.int(rail, "panel_collapsed"))
        assertEquals(DesktopUxContract.int(rail.getValue("footprint").jsonObject, "collapsed"),
            DesktopUxContract.int(rail, "panel_collapsed") + 2 * DesktopUxContract.int(rail, "margin"))
        val sidePanel = player.getValue("overlay").jsonObject.getValue("side_panel").jsonObject
        assertEquals(44, DesktopUxContract.int(sidePanel, "header"))
        assertEquals(6, DesktopUxContract.int(sidePanel, "splitter_hit"))
        assertEquals("none", DesktopUxContract.string(DesktopUxContract.section("tokens"), "motion_reduced"))
    }

    @Test fun feedRowsAgreeWithTheirOwnParameters() {
        val rules = feed.getValue("rules").jsonObject
        val gutter = DesktopUxContract.int(rules, "gutter")
        val padding = DesktopUxContract.int(rules, "side_padding")
        val maxCard = DesktopUxContract.int(rules, "max_card")
        val rows = DesktopUxContract.objects(feed.getValue("golden"))
        assertTrue(rows.size >= 12)
        for (row in rows) {
            val id = DesktopUxContract.string(row, "id")
            val expect = row.getValue("expect").jsonObject
            val columns = DesktopUxContract.int(expect, "cols")
            val card = DesktopUxContract.double(expect, "card")
            val grid = DesktopUxContract.int(expect, "grid")
            val usable = DesktopUxContract.int(expect, "wc") - 2 * padding
            assertTrue(grid <= usable, id)
            assertTrue(card <= maxCard, id)
            // The card is rounded to one decimal, so the sum may drift by half a tenth per column.
            assertTrue(abs(columns * card + (columns - 1) * gutter - grid) <= columns * 0.05 + 1e-9, id)
            // A capped grid is narrower than the room it centres in; an uncapped grid fills it exactly.
            assertEquals(grid < usable, expect.getValue("capped").jsonPrimitive.boolean, id)
        }
    }

    @Test fun playerRowsAgreeWithTheirOwnParameters() {
        val rules = player.getValue("rules").jsonObject
        val ratio = DesktopUxContract.ints(rules.getValue("aspect"))
        val tierL = DesktopUxContract.int(rules, "tier_l_min")
        val tierM = DesktopUxContract.int(rules, "tier_m_min")
        val rows = DesktopUxContract.objects(player.getValue("golden"))
        assertTrue(rows.size >= 12)
        for (row in rows) {
            val id = DesktopUxContract.string(row, "id")
            val width = DesktopUxContract.int(row, "w")
            val height = DesktopUxContract.int(row, "h")
            val expect = row.getValue("expect").jsonObject
            val panel = DesktopUxContract.int(expect, "panel")
            val video = DesktopUxContract.ints(expect.getValue("video"))
            assertTrue(video[0] <= width - panel && video[1] <= height, id)
            // 16:9 up to the rounding of both sides.
            assertTrue(abs(video[0] * ratio[1] - video[1] * ratio[0]) <= ratio[0] + ratio[1], id)
            assertTrue(video[0] == width - panel || video[1] == height, id)
            val tier = DesktopUxContract.string(expect, "tier")
            assertEquals(if (video[0] >= tierL) "L" else if (video[0] >= tierM) "M" else "S", tier, id)
            val bars = expect.getValue("bars").jsonObject
            val axis = DesktopUxContract.string(bars, "axis")
            val size = DesktopUxContract.int(bars, "size")
            when (axis) {
                "none" -> assertEquals(0, size, id)
                "left_right" -> assertTrue(abs(video[0] + 2 * size - (width - panel)) <= 1, id)
                "top_bottom" -> assertTrue(abs(video[1] + 2 * size - height) <= 1, id)
                else -> throw AssertionError("unknown bars axis $axis in $id")
            }
        }
    }

    @Test fun shortcutRowsHaveUniqueChordsInsideAScope() {
        val rows = DesktopUxContract.objects(DesktopUxContract.section("shortcuts").getValue("rows"))
        val seen = HashSet<String>()
        for (row in rows) {
            val identity = DesktopUxContract.string(row, "scope") + " " + DesktopUxContract.string(row, "chord")
            assertTrue(seen.add(identity), identity)
        }
        assertTrue(rows.size > 60)
    }

    @Test fun railFootprintsAreTheSpecNumbers() {
        val footprint = DesktopUxContract.section("rail").getValue("footprint").jsonObject
        assertEquals(216, DesktopUxContract.int(footprint, "expanded"))
        assertEquals(72, DesktopUxContract.int(footprint, "collapsed"))
        assertEquals(172, DesktopUxContract.int(footprint, "classic"))
        assertEquals(9, DesktopUxContract.section("rail").getValue("fixed_destinations").jsonArray.size)
    }
}
