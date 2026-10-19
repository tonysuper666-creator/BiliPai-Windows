package com.bilipai.desktop.ui.kit

import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
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
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    // ---- Slice X2b: menus, overlay scrollbar, toast ----

    @Test fun partTwoTokensAreTheContractNumbers() {
        val scrollbar = tokens.getValue("scrollbar").jsonObject
        val toast = tokens.getValue("toast_ms").jsonObject
        val menu = kit.getValue("menu").jsonObject
        val flyout = DesktopUxContract.section("flyout")
        val kitScrollbar = kit.getValue("scrollbar").jsonObject
        assertEquals(DesktopUxContract.int(scrollbar, "thumb"), DESKTOP_KIT_SCROLLBAR_THUMB_DP)
        assertEquals(DesktopUxContract.int(scrollbar, "hit_strip"), DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP)
        assertEquals(DesktopUxContract.int(toast, "plain"), DESKTOP_KIT_TOAST_PLAIN_MS)
        assertEquals(DesktopUxContract.int(toast, "with_action"), DESKTOP_KIT_TOAST_ACTION_MS)
        assertEquals(DesktopUxContract.int(menu, "width"), DESKTOP_KIT_MENU_WIDTH_DP)
        assertEquals(DesktopUxContract.int(menu, "popover_width"), DESKTOP_KIT_POPOVER_WIDTH_DP)
        assertEquals(DesktopUxContract.int(flyout, "padding"), DESKTOP_KIT_MENU_PADDING_DP)
        assertEquals(DesktopUxContract.int(flyout, "anchor_gap"), DESKTOP_KIT_MENU_GAP_DP)
        assertEquals(DesktopUxContract.int(kitScrollbar, "idle_alpha_percent"), DESKTOP_KIT_SCROLLBAR_IDLE_ALPHA_PERCENT)
        assertEquals(DesktopUxContract.int(kitScrollbar, "hover_alpha_percent"), DESKTOP_KIT_SCROLLBAR_HOVER_ALPHA_PERCENT)
        assertTrue(DESKTOP_KIT_SCROLLBAR_THUMB_DP < DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP)
        assertTrue(DESKTOP_KIT_SCROLLBAR_IDLE_ALPHA_PERCENT < DESKTOP_KIT_SCROLLBAR_HOVER_ALPHA_PERCENT)
        // Spec 5.1: the menu rows are the control height; the menu radius is its own token.
        assertEquals(32, DESKTOP_KIT_CONTROL_HEIGHT_DP)
        assertEquals(12, DESKTOP_KIT_RADIUS_MENU_DP)
    }

    private fun place(row: kotlinx.serialization.json.JsonObject): DesktopKitMenuPosition {
        val anchor = DesktopUxContract.ints(row.getValue("anchor"))
        val menu = DesktopUxContract.ints(row.getValue("menu"))
        val window = DesktopUxContract.ints(row.getValue("window"))
        return desktopKitPlaceMenu(
            DesktopKitMenuPlacement.valueOf(DesktopUxContract.string(row, "placement")),
            anchor[0], anchor[1], anchor[2], anchor[3], menu[0], menu[1], window[0], window[1], DesktopUxContract.int(row, "gap"),
        )
    }

    @Test fun menuPlacementFollowsTheContractRows() {
        val rows = DesktopUxContract.objects(kit.getValue("menu_place"))
        assertTrue(rows.size >= 19)
        for (row in rows) {
            val expect = DesktopUxContract.ints(row.getValue("expect"))
            assertEquals(DesktopKitMenuPosition(expect[0], expect[1]), place(row), DesktopUxContract.string(row, "id"))
        }
        assertEquals(DesktopKitMenuPlacement.entries.map { it.name }.toSet(), rows.map { DesktopUxContract.string(it, "placement") }.toSet())
    }

    /** The same rule as a candidate list per axis: the first candidate that lies wholly inside the free range. */
    private fun pick(candidates: List<Int>, size: Int, limit: Int, gap: Int): Int {
        val low = gap
        val high = limit - gap
        val inside = candidates.firstOrNull { it >= low && it + size <= high }
        if (inside != null) return inside
        return if (high - low >= size) candidates[0].coerceIn(low, high - size) else low
    }

    private fun oracle(
        placement: DesktopKitMenuPlacement, left: Int, top: Int, right: Int, bottom: Int,
        width: Int, height: Int, windowWidth: Int, windowHeight: Int, gap: Int,
    ): DesktopKitMenuPosition {
        val (xs, ys) = when (placement) {
            DesktopKitMenuPlacement.AT_POINT -> listOf(left, left - width) to listOf(top, top - height)
            DesktopKitMenuPlacement.BELOW_START -> listOf(left, right - width) to listOf(bottom + gap, top - gap - height)
            DesktopKitMenuPlacement.BELOW_END -> listOf(right - width, left) to listOf(bottom + gap, top - gap - height)
        }
        return DesktopKitMenuPosition(pick(xs, width, windowWidth, gap), pick(ys, height, windowHeight, gap))
    }

    @Test fun menuPlacementAgreesWithTheCandidateOracleOverADenseGridAndStaysInsideTheGap() {
        var checked = 0
        for ((windowWidth, windowHeight) in listOf(200 to 150, 480 to 320, 1000 to 700)) {
            for ((width, height) in listOf(100 to 60, 220 to 300, 220 to 800, 500 to 50, 1 to 1)) {
                for (gap in listOf(0, DESKTOP_KIT_MENU_GAP_DP)) {
                    for (placement in DesktopKitMenuPlacement.entries) {
                        for (left in -30..(windowWidth + 30) step 23) {
                            for (top in -30..(windowHeight + 30) step 19) {
                                for ((anchorWidth, anchorHeight) in listOf(0 to 0, 90 to 32)) {
                                    val actual = desktopKitPlaceMenu(
                                        placement, left, top, left + anchorWidth, top + anchorHeight,
                                        width, height, windowWidth, windowHeight, gap,
                                    )
                                    val expected = oracle(placement, left, top, left + anchorWidth, top + anchorHeight, width, height, windowWidth, windowHeight, gap)
                                    assertEquals(expected, actual, "$placement $left,$top $width x $height in $windowWidth x $windowHeight gap $gap")
                                    for ((position, size, limit) in listOf(Triple(actual.x, width, windowWidth), Triple(actual.y, height, windowHeight))) {
                                        if (size <= limit - 2 * gap) assertTrue(position >= gap && position + size <= limit - gap)
                                        else assertEquals(gap, position)
                                    }
                                    checked++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked > 30_000, "checked $checked")
    }

    @Test fun menuHeightLimitFollowsTheContractRows() {
        for (row in DesktopUxContract.objects(kit.getValue("menu_max_height"))) {
            assertEquals(
                DesktopUxContract.int(row, "expect"),
                desktopKitMenuMaxHeightPx(DesktopUxContract.int(row, "window_height"), DesktopUxContract.int(row, "gap")),
                row.toString(),
            )
        }
        for (height in -5..40) assertTrue(desktopKitMenuMaxHeightPx(height, DESKTOP_KIT_MENU_GAP_DP) >= 1, "height $height")
    }

    @Test fun scrollbarNumbersFollowTheContractRows() {
        val scrollbar = kit.getValue("scrollbar").jsonObject
        for (row in DesktopUxContract.objects(scrollbar.getValue("needed"))) {
            val actual = desktopKitScrollbarNeeded(DesktopUxContract.double(row, "content"), DesktopUxContract.double(row, "viewport"))
            assertEquals(row.getValue("expect").jsonPrimitive.boolean, actual, row.toString())
        }
        for (table in listOf("thumb", "thumb_wider_than_the_strip")) {
            for (row in DesktopUxContract.objects(scrollbar.getValue(table))) {
                val density = DesktopUxContract.double(row, "density").toFloat()
                val strip = DesktopUxContract.int(row, "strip")
                val thumb = desktopKitScrollbarThumbPx(density, strip.toFloat())
                assertEquals(DesktopUxContract.int(row, "thumb").toFloat(), thumb, row.toString())
                assertEquals(DesktopUxContract.int(row, "inset").toFloat(), desktopKitScrollbarThumbInsetPx(strip.toFloat(), thumb), row.toString())
                // The strip column of the main table is the layout rounding (ties up) of the 12 dp strip.
                if (table == "thumb") assertEquals(strip, (DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP * density).roundToInt(), row.toString())
            }
        }
    }

    @Test fun theThumbIsAWholePixelWidthInsideTheStripAtEveryScaleAndZoom() {
        var checked = 0
        for (scale in listOf(100, 125, 150, 175, 200, 225, 250)) {
            for (zoom in 50..200 step 5) {
                val density = scale / 100f * (zoom / 100f)
                val strip = (DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP * density).roundToInt().toFloat()
                val thumb = desktopKitScrollbarThumbPx(density, strip)
                val inset = desktopKitScrollbarThumbInsetPx(strip, thumb)
                val label = "scale $scale zoom $zoom"
                assertEquals(thumb, kotlin.math.floor(thumb), label)
                assertTrue(thumb >= 1f && thumb <= strip, label)
                assertTrue(inset >= 0f && inset + thumb <= strip, label)
                checked++
            }
        }
        assertEquals(7 * 31, checked)
    }

    @Test fun toastDurationsAndScriptsFollowTheContractRows() {
        val toast = kit.getValue("toast").jsonObject
        for (row in DesktopUxContract.objects(toast.getValue("duration"))) {
            val actual = desktopKitToastDurationMs(row.getValue("has_action").jsonPrimitive.boolean)
            assertEquals(DesktopUxContract.int(row, "ms").toLong(), actual, row.toString())
        }
        assertEquals(2400L, desktopKitToastDurationMs(false))
        assertEquals(5000L, desktopKitToastDurationMs(true))
        val scripts = DesktopUxContract.objects(toast.getValue("script"))
        assertTrue(scripts.size >= 5)
        for (script in scripts) {
            var state = DesktopKitToastState()
            for (step in DesktopUxContract.objects(script.getValue("steps"))) {
                state = when (DesktopUxContract.string(step, "op")) {
                    "show" -> state.show(DesktopUxContract.string(step, "message"), step.getValue("action").jsonPrimitive.contentOrNull)
                    "dismiss" -> state.dismiss(DesktopUxContract.int(step, "id").toLong())
                    else -> error("unknown toast op in $step")
                }
                val label = DesktopUxContract.string(script, "id") + ": " + step
                val expect = step.getValue("expect")
                if (expect is JsonNull) {
                    assertNull(state.current, label)
                } else {
                    val entry = expect.jsonObject
                    val expected = DesktopKitToastEntry(
                        DesktopUxContract.int(entry, "id").toLong(), DesktopUxContract.string(entry, "message"),
                        entry.getValue("action").jsonPrimitive.contentOrNull, DesktopUxContract.int(entry, "ms").toLong(),
                    )
                    assertEquals(expected, state.current, label)
                }
            }
        }
    }

    @Test fun oneToastAtATimeIdsOnlyGrowAndAStaleDismissNeverClosesANewerToast() {
        var state = DesktopKitToastState()
        val seen = mutableListOf<Long>()
        for (index in 0 until 12) {
            state = state.show("message $index", if (index % 3 == 0) "Undo" else null)
            val current = state.current!!
            seen += current.id
            assertEquals((index + 1).toLong(), state.lastId)
            assertEquals(desktopKitToastDurationMs(index % 3 == 0), current.durationMs)
            if (index % 4 == 0) {
                state = state.dismiss(current.id)
                assertNull(state.current)
            }
        }
        assertEquals(seen.sorted().distinct(), seen)
        val two = DesktopKitToastState().show("first").show("second")
        for (stale in listOf(0L, 1L, 3L, 99L, -1L)) assertEquals(2L, two.dismiss(stale).current?.id)
        assertNull(two.dismiss(2L).current)
        // Blank text changes nothing: the very same state comes back.
        assertSame(two, two.show("   "))
        assertSame(two, two.show(""))
    }
}
