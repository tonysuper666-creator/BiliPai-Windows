package com.bilipai.desktop.ui.kit

import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tokens and pure logic of the component kit against the shared contract ("tokens", "rail.metrics" and "kit"). No
 * Compose scene, no window, no GPU, no network. DesktopKitTokens only builds Dp values from the constants.
 */
class DesktopKitModelTest {
    private val tokens = DesktopUxContract.section("tokens")
    private val railMetrics = DesktopUxContract.section("rail").getValue("metrics").jsonObject
    private val kit = DesktopUxContract.section("kit")

    private fun bools(element: kotlinx.serialization.json.JsonElement): List<Boolean> = element.jsonArray.map { it.jsonPrimitive.boolean }

    @Test fun tokenConstantsAreTheContractNumbers() {
        val rowHeight = tokens.getValue("row_height").jsonObject
        val settings = DesktopUxContract.ints(rowHeight.getValue("settings"))
        val iconHit = DesktopUxContract.ints(tokens.getValue("icon_hit"))
        val radius = tokens.getValue("radius").jsonObject
        val ring = tokens.getValue("focus_ring").jsonObject
        val motion = DesktopUxContract.ints(tokens.getValue("motion_ms"))
        val delay = tokens.getValue("tooltip_delay_ms").jsonObject
        val pill = DesktopUxContract.ints(railMetrics.getValue("selected_pill"))
        assertEquals(DesktopUxContract.int(tokens, "control_height"), DESKTOP_KIT_CONTROL_HEIGHT_DP)
        assertEquals(DesktopUxContract.int(rowHeight, "list"), DESKTOP_KIT_ROW_LIST_DP)
        assertEquals(DesktopUxContract.int(rowHeight, "nav"), DESKTOP_KIT_ROW_NAV_DP)
        assertEquals(listOf(DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP, DESKTOP_KIT_ROW_SETTINGS_DP, DESKTOP_KIT_ROW_SETTINGS_COMFORTABLE_DP), settings)
        assertEquals(DesktopUxContract.int(tokens, "icon"), DESKTOP_KIT_ICON_DP)
        assertEquals(listOf(DESKTOP_KIT_ICON_HIT_DP, DESKTOP_KIT_ICON_HIT_LARGE_DP), iconHit)
        assertEquals(DesktopUxContract.int(tokens, "hit_floor"), DESKTOP_KIT_HIT_FLOOR_DP)
        assertEquals(DesktopUxContract.int(radius, "control"), DESKTOP_KIT_RADIUS_CONTROL_DP)
        assertEquals(DesktopUxContract.int(radius, "row"), DESKTOP_KIT_RADIUS_ROW_DP)
        assertEquals(DesktopUxContract.int(radius, "menu"), DESKTOP_KIT_RADIUS_MENU_DP)
        assertEquals(DesktopUxContract.int(radius, "flyout"), DESKTOP_KIT_RADIUS_FLYOUT_DP)
        assertEquals(DesktopUxContract.int(radius, "panel"), DESKTOP_KIT_RADIUS_PANEL_DP)
        assertEquals(DesktopUxContract.int(ring, "outer"), DESKTOP_KIT_FOCUS_RING_OUTER_DP)
        assertEquals(DesktopUxContract.int(ring, "inner"), DESKTOP_KIT_FOCUS_RING_INNER_DP)
        assertEquals(listOf(DESKTOP_KIT_MOTION_FAST_MS, DESKTOP_KIT_MOTION_SLOW_MS), motion)
        assertEquals(DesktopUxContract.int(delay, "kit"), DESKTOP_KIT_TOOLTIP_DELAY_MS)
        assertEquals(DesktopUxContract.int(delay, "player"), DESKTOP_KIT_TOOLTIP_DELAY_PLAYER_MS)
        assertEquals(DesktopUxContract.int(delay, "rail_expanded"), DESKTOP_KIT_TOOLTIP_DELAY_RAIL_EXPANDED_MS)
        assertEquals(DesktopUxContract.int(railMetrics, "hover_percent"), DESKTOP_KIT_HOVER_PERCENT)
        assertEquals(DesktopUxContract.int(railMetrics, "pressed_percent"), DESKTOP_KIT_PRESSED_PERCENT)
        assertEquals(DesktopUxContract.int(railMetrics, "selected_fill_percent"), DESKTOP_KIT_SELECTED_FILL_PERCENT)
        assertEquals(DesktopUxContract.int(railMetrics, "item_gap"), DESKTOP_KIT_NAV_ICON_GAP_DP)
        assertEquals(listOf(DESKTOP_KIT_NAV_PILL_WIDTH_DP, DESKTOP_KIT_NAV_PILL_HEIGHT_DP), pill)
        assertEquals(DesktopUxContract.int(railMetrics, "badge"), DESKTOP_KIT_BADGE_DP)
        assertEquals(DesktopUxContract.int(railMetrics, "badge_dot"), DESKTOP_KIT_BADGE_DOT_DP)
        assertEquals(DesktopUxContract.int(kit, "disabled_alpha_percent"), DESKTOP_KIT_DISABLED_ALPHA_PERCENT)
        assertEquals(DesktopUxContract.int(kit, "pointer_focus_window_ms").toLong(), DESKTOP_KIT_POINTER_FOCUS_WINDOW_MS)
    }

    @Test fun everyControlRespectsTheHitFloorAndTheDpViewMatches() {
        assertTrue(DESKTOP_KIT_CONTROL_HEIGHT_DP >= DESKTOP_KIT_HIT_FLOOR_DP)
        assertTrue(minOf(DESKTOP_KIT_ICON_HIT_DP, DESKTOP_KIT_ICON_HIT_LARGE_DP) >= DESKTOP_KIT_HIT_FLOOR_DP)
        assertTrue(DESKTOP_KIT_ROW_NAV_DP >= DESKTOP_KIT_HIT_FLOOR_DP && DESKTOP_KIT_ROW_LIST_DP >= DESKTOP_KIT_HIT_FLOOR_DP)
        assertEquals(DESKTOP_KIT_CONTROL_HEIGHT_DP.toFloat(), DesktopKitTokens.controlHeight.value)
        assertEquals(DESKTOP_KIT_ICON_HIT_DP.toFloat(), DesktopKitTokens.iconHit.value)
        assertEquals(DESKTOP_KIT_ICON_HIT_LARGE_DP.toFloat(), DesktopKitTokens.iconHitLarge.value)
        assertEquals(DESKTOP_KIT_RADIUS_PANEL_DP.toFloat(), DesktopKitTokens.radiusPanel.value)
    }

    @Test fun theWashIsTheRailRuleAndSelectedIsStrongerThanPressed() {
        assertTrue(DESKTOP_KIT_PRESSED_PERCENT > DESKTOP_KIT_HOVER_PERCENT)
        assertTrue(DESKTOP_KIT_SELECTED_FILL_PERCENT > DESKTOP_KIT_PRESSED_PERCENT)
        for (row in DesktopUxContract.objects(kit.getValue("wash"))) {
            val enabled = row.getValue("enabled").jsonPrimitive.boolean
            val hovered = row.getValue("hovered").jsonPrimitive.boolean
            val pressed = row.getValue("pressed").jsonPrimitive.boolean
            assertEquals(DesktopUxContract.int(row, "percent"), desktopKitWashPercent(enabled, hovered, pressed), row.toString())
        }
    }

    @Test fun aHairlineIsExactlyOnePixelAtEveryDensity() {
        val densities = kit.getValue("hairline_densities").jsonArray.map { it.jsonPrimitive.double.toFloat() }
        assertTrue(densities.size >= 8)
        for (density in densities) {
            val dp = desktopKitHairlineDp(density)
            assertTrue(abs(dp * density - 1f) < 1e-5f, "density $density: ${dp * density}")
            assertEquals(1, (dp * density).roundToInt(), "density $density")
        }
        // Every Windows scale times every zoom step of the contract (50..200 in steps of 5).
        for (scale in listOf(100, 125, 150, 175, 200, 225, 250)) {
            for (zoom in 50..200 step 5) {
                val density = scale / 100f * (zoom / 100f)
                assertEquals(1, (desktopKitHairlineDp(density) * density).roundToInt(), "scale $scale zoom $zoom")
            }
        }
        assertEquals(1f, desktopKitHairlineDp(0f))
        assertEquals(1f, desktopKitHairlineDp(-2f))
    }

    @Test fun strokeWidthsSnapToWholePixelsWithTiesToEven() {
        for (row in DesktopUxContract.objects(kit.getValue("snap"))) {
            assertEquals(DesktopUxContract.int(row, "snapped").toFloat(), desktopKitSnapStrokePx(DesktopUxContract.double(row, "px").toFloat()), row.toString())
        }
    }

    @Test fun motionIsNoneUnderReducedMotion() {
        for (row in DesktopUxContract.objects(kit.getValue("motion"))) {
            val reduced = row.getValue("reduced").jsonPrimitive.boolean
            assertEquals(DesktopUxContract.int(row, "expect"), desktopKitMotionMs(reduced, DesktopUxContract.int(row, "ms")), row.toString())
        }
        assertEquals(0, desktopKitMotionMs(true, DESKTOP_KIT_MOTION_FAST_MS))
        assertEquals(DESKTOP_KIT_MOTION_SLOW_MS, desktopKitMotionMs(false, DESKTOP_KIT_MOTION_SLOW_MS))
    }

    @Test fun segmentStepFollowsTheContractRows() {
        for (row in DesktopUxContract.objects(kit.getValue("segment_step"))) {
            val actual = desktopKitSegmentStep(bools(row.getValue("enabled")), DesktopUxContract.int(row, "current"), DesktopUxContract.int(row, "delta"))
            assertEquals(DesktopUxContract.int(row, "expect"), actual, row.toString())
        }
    }

    /** The same rule as an explicit walk over the cyclic visiting order, for every mask of up to five segments. */
    private fun stepOracle(enabled: List<Boolean>, current: Int, delta: Int): Int {
        val count = enabled.size
        if (count == 0 || delta == 0) return current
        val order = (1..count).map { k ->
            if (delta > 0) (if (current < 0) k - 1 else (current + k) % count)
            else (if (current < 0) count - k else ((current - k) % count + count) % count)
        }
        return order.firstOrNull { enabled[it] } ?: current
    }

    @Test fun segmentStepAgreesWithTheOracleOverEveryMask() {
        for (count in 0..5) {
            for (mask in 0 until (1 shl count)) {
                val enabled = List(count) { (mask shr it) and 1 == 1 }
                for (current in -1 until count) {
                    for (delta in listOf(-1, 0, 1)) {
                        assertEquals(stepOracle(enabled, current, delta), desktopKitSegmentStep(enabled, current, delta), "$enabled $current $delta")
                    }
                }
            }
        }
    }

    @Test fun segmentEdgeFollowsTheContractRows() {
        for (row in DesktopUxContract.objects(kit.getValue("segment_edge"))) {
            val enabled = bools(row.getValue("enabled"))
            val last = row.getValue("last").jsonPrimitive.boolean
            assertEquals(DesktopUxContract.int(row, "expect"), desktopKitSegmentEdge(enabled, last), row.toString())
        }
    }

    private fun replay(events: List<Pair<String, Long>>): DesktopKitFocusState {
        var state = DesktopKitFocusState()
        for ((name, at) in events) {
            state = when (name) {
                "focus" -> state.onFocus(at)
                "unfocus" -> state.onUnfocus()
                "press" -> state.onPointerPress(at)
                "key" -> state.onNavigationKey()
                else -> error("unknown focus event $name")
            }
        }
        return state
    }

    @Test fun theFocusRingShowsForKeyboardFocusOnly() {
        for (row in DesktopUxContract.objects(kit.getValue("focus"))) {
            val events = row.getValue("events").jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.int.toLong() }
            assertEquals(row.getValue("ring").jsonPrimitive.boolean, replay(events).ringVisible, DesktopUxContract.string(row, "id"))
        }
        assertFalse(DesktopKitFocusState().ringVisible)
        assertEquals(DesktopKitFocusState(), DesktopKitFocusState().onUnfocus())
    }

    @Test fun theLoadingTextIsTheSettingsWording() {
        assertEquals("读取设置…", DesktopKitStrings.LOADING_SETTING)
    }
}
