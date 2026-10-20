package com.bilipai.desktop.appearance

import com.bilipai.desktop.ux.DesktopUxContract
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure window bounds restore against the shared contract (section window_bounds): its constants, every golden row of
 * the plan and of the tracker, and a sweep of the plan invariants over several monitor layouts. The layouts include a
 * monitor that vanished, negative origins and the measured 5120x2160 panel at Windows scale 150%.
 * No window, no GPU, no network.
 */
class DesktopWindowsUxWindowBoundsModelTest {
    private val section = DesktopUxContract.section("window_bounds")
    private val layouts = section.getValue("layouts").jsonObject

    private fun optionalInt(element: JsonElement?): Int? = if (element == null || element is JsonNull) null else element.jsonPrimitive.intOrNull

    private fun boundsOf(element: JsonElement?): DesktopWindowsUxWindowBounds? {
        if (element == null || element is JsonNull) return null
        val source = element.jsonObject
        return DesktopWindowsUxWindowBounds(
            x = DesktopUxContract.int(source, "x"), y = DesktopUxContract.int(source, "y"),
            width = DesktopUxContract.int(source, "width"), height = DesktopUxContract.int(source, "height"),
            maximized = source.getValue("maximized").jsonPrimitive.boolean)
    }

    private fun screensOf(layout: String): List<DesktopWindowsUxScreen> =
        DesktopUxContract.objects(layouts.getValue(layout).jsonObject.getValue("screens")).map { screen ->
            DesktopWindowsUxScreen(
                usable = DesktopWindowsUxRect(
                    DesktopUxContract.int(screen, "x"), DesktopUxContract.int(screen, "y"),
                    DesktopUxContract.int(screen, "width"), DesktopUxContract.int(screen, "height")),
                primary = screen["primary"]?.jsonPrimitive?.boolean ?: false)
        }

    @Test fun everyConstantHasItsContractValue() {
        assertEquals(DesktopUxContract.ints(section.getValue("default_size")),
            listOf(WINDOWS_UX_WINDOW_DEFAULT_WIDTH, WINDOWS_UX_WINDOW_DEFAULT_HEIGHT))
        assertEquals(DesktopUxContract.ints(section.getValue("min_base")),
            listOf(WINDOWS_UX_WINDOW_MIN_BASE_WIDTH, WINDOWS_UX_WINDOW_MIN_BASE_HEIGHT))
        assertEquals(DesktopUxContract.int(section, "edge_tolerance"), WINDOWS_UX_WINDOW_EDGE_TOLERANCE)
        assertEquals(DesktopUxContract.int(section, "settle_ms").toLong(), WINDOWS_UX_WINDOW_SETTLE_MS)
        assertEquals(DesktopUxContract.ints(DesktopUxContract.section("tokens").getValue("min_window_dp")),
            listOf(WINDOWS_UX_WINDOW_MIN_BASE_WIDTH, WINDOWS_UX_WINDOW_MIN_BASE_HEIGHT))
        assertEquals(DesktopUxContract.strings(section.getValue("plan_kinds")), DesktopWindowsUxPlanKind.entries.map { it.name })
        assertEquals(DesktopUxContract.strings(section.getValue("placements")), DesktopWindowsUxPlacement.entries.map { it.name })
    }

    @Test fun theDefaultSizeIsTheSizeThatMainOpenedWithBeforeThisSlice() {
        assertEquals(1360, WINDOWS_UX_WINDOW_DEFAULT_WIDTH)
        assertEquals(900, WINDOWS_UX_WINDOW_DEFAULT_HEIGHT)
        val plan = planDesktopWindowsUxWindow(null, screensOf("primary-5k2k-150"), 125)
        assertEquals(DesktopWindowsUxWindowPlan(1360, 900, null, null, false, DesktopWindowsUxPlanKind.DEFAULT, null), plan)
        assertNull(plan.bounds())
    }

    @Test fun theMinimumExtentRowsOfTheContract() {
        val rows = DesktopUxContract.objects(section.getValue("minimum_extent"))
        assertTrue(rows.size >= 8)
        for (row in rows) {
            val percent = DesktopUxContract.int(row, "percent")
            assertEquals(DesktopUxContract.int(row, "width"), desktopWindowsUxMinimumExtent(WINDOWS_UX_WINDOW_MIN_BASE_WIDTH, percent), "width at $percent")
            assertEquals(DesktopUxContract.int(row, "height"), desktopWindowsUxMinimumExtent(WINDOWS_UX_WINDOW_MIN_BASE_HEIGHT, percent), "height at $percent")
        }
    }

    @Test fun everyGoldenPlanRowOfTheContract() {
        val rows = DesktopUxContract.objects(section.getValue("golden_plan"))
        assertTrue(rows.size >= 30)
        val kinds = HashSet<String>()
        for (row in rows) {
            val id = DesktopUxContract.string(row, "id")
            val zoom = optionalInt(row["zoom_percent"]) ?: 125
            val plan = planDesktopWindowsUxWindow(boundsOf(row["saved"]), screensOf(DesktopUxContract.string(row, "layout")), zoom)
            val expect = row.getValue("expect").jsonObject
            assertEquals(DesktopUxContract.string(expect, "kind"), plan.kind.name, id)
            assertEquals(optionalInt(expect["x"]), plan.x, "$id x")
            assertEquals(optionalInt(expect["y"]), plan.y, "$id y")
            assertEquals(DesktopUxContract.int(expect, "width"), plan.width, "$id width")
            assertEquals(DesktopUxContract.int(expect, "height"), plan.height, "$id height")
            assertEquals(expect.getValue("maximized").jsonPrimitive.boolean, plan.maximized, "$id maximized")
            assertEquals(optionalInt(expect["screen"]), plan.screenIndex, "$id screen")
            kinds.add(plan.kind.name)
        }
        assertEquals(DesktopWindowsUxPlanKind.entries.map { it.name }.toSet(), kinds)
    }

    @Test fun thePlanOfTheMeasuredPrimaryPanelIsDerivedFromItsPixelsAndScale() {
        // 5120x2160 px at Windows scale 150% is 3413x1440 AWT units, less an assumed 48 unit taskbar.
        val usable = screensOf("primary-5k2k-150").single().usable
        assertEquals(DesktopWindowsUxRect(0, 0, 3413, 1440 - 48), usable)
    }

    @Test fun theStartupSeedIsThePlanBoundsOrTheSavedBoundsWhileTheMonitorsAreUnknown() {
        val saved = DesktopWindowsUxWindowBounds(400, 600, 1360, 900, true)
        val onScreen = desktopWindowsUxStartup(saved, screensOf("primary-5k2k-150"), 125)
        assertEquals(DesktopWindowsUxWindowBounds(400, 508, 1360, 900, true), onScreen.seed)
        assertEquals(onScreen.plan.bounds(), onScreen.seed)
        val unknown = desktopWindowsUxStartup(saved, emptyList(), 125)
        assertEquals(DesktopWindowsUxPlanKind.UNVALIDATED, unknown.plan.kind)
        assertNull(unknown.plan.bounds())
        assertEquals(saved, unknown.seed)
        val nothing = desktopWindowsUxStartup(null, screensOf("primary-5k2k-150"), 125)
        assertNull(nothing.seed)
    }

    @Test fun aHugeButSaneWindowOnNoMonitorIsRelocatedAndShrunkToTheMonitor() {
        val plan = planDesktopWindowsUxWindow(
            DesktopWindowsUxWindowBounds(-99999, -99999, 32768, 32768), screensOf("primary-5k2k-150"), 125)
        assertEquals(DesktopWindowsUxRect(0, 0, 3413, 1392), DesktopWindowsUxRect(plan.x!!, plan.y!!, plan.width, plan.height))
    }

    @Test fun overlapAreaIsZeroForTouchingAndDisjointRectangles() {
        val a = DesktopWindowsUxRect(0, 0, 100, 100)
        assertEquals(0L, desktopWindowsUxOverlapArea(a, DesktopWindowsUxRect(100, 0, 50, 50)))
        assertEquals(0L, desktopWindowsUxOverlapArea(a, DesktopWindowsUxRect(0, 100, 50, 50)))
        assertEquals(0L, desktopWindowsUxOverlapArea(a, DesktopWindowsUxRect(-60, -60, 50, 50)))
        assertEquals(1L, desktopWindowsUxOverlapArea(a, DesktopWindowsUxRect(99, 99, 50, 50)))
        assertEquals(2500L, desktopWindowsUxOverlapArea(a, DesktopWindowsUxRect(-25, -25, 75, 75)))
    }

    @Test fun everyGoldenTrackerRowOfTheContract() {
        val rows = DesktopUxContract.objects(section.getValue("golden_tracker"))
        assertTrue(rows.size >= 12)
        for (row in rows) {
            val id = DesktopUxContract.string(row, "id")
            val tracker = DesktopWindowsUxWindowTracker(boundsOf(row["seed"]))
            for (sample in DesktopUxContract.objects(row.getValue("samples"))) {
                tracker.accept(DesktopWindowsUxWindowSample(
                    placement = DesktopWindowsUxPlacement.valueOf(DesktopUxContract.string(sample, "placement")),
                    minimized = sample.getValue("minimized").jsonPrimitive.boolean,
                    x = DesktopUxContract.int(sample, "x"), y = DesktopUxContract.int(sample, "y"),
                    width = DesktopUxContract.int(sample, "width"), height = DesktopUxContract.int(sample, "height")))
            }
            assertEquals(boundsOf(row["expect"]), tracker.bounds(), id)
        }
    }

    @Test fun theTrackerKeepsTheLastFloatingRectangleAcrossAMaximisedAndAFullscreenSpell() {
        val tracker = DesktopWindowsUxWindowTracker(null)
        assertNull(tracker.bounds())
        tracker.accept(DesktopWindowsUxWindowSample(DesktopWindowsUxPlacement.FLOATING, false, 100, 50, 1400, 950))
        tracker.accept(DesktopWindowsUxWindowSample(DesktopWindowsUxPlacement.MAXIMIZED, false, -8, -8, 3429, 1400))
        tracker.accept(DesktopWindowsUxWindowSample(DesktopWindowsUxPlacement.FULLSCREEN, false, 0, 0, 3413, 1440))
        assertEquals(DesktopWindowsUxWindowBounds(100, 50, 1400, 950, true), tracker.bounds())
        tracker.accept(DesktopWindowsUxWindowSample(DesktopWindowsUxPlacement.FLOATING, true, -32000, -32000, 160, 28))
        assertEquals(DesktopWindowsUxWindowBounds(100, 50, 1400, 950, true), tracker.bounds())
        tracker.accept(DesktopWindowsUxWindowSample(DesktopWindowsUxPlacement.FLOATING, false, 300, 200, 1300, 850))
        assertEquals(DesktopWindowsUxWindowBounds(300, 200, 1300, 850, false), tracker.bounds())
    }

    @Test fun everyPlanOfTheSweepStaysOnItsMonitorKeepsTheMinimumAndIsStableWhenPlannedAgain() {
        val layoutNames = listOf(
            "primary-5k2k-150", "laptop-1080-125", "primary-5k2k-with-4k-left", "primary-5k2k-with-1080-above",
            "two-1080-side-by-side", "primary-listed-second", "primary-1080-with-1080-up-left")
        val xs = listOf(-6000, -2600, -2000, -400, -17, -16, -1, 0, 1, 500, 1600, 1900, 3000, 3400, 3413, 3500, 6000)
        val ys = listOf(-1200, -1032, -900, -300, -1, 0, 1, 300, 600, 1400, 2000)
        val sizes = listOf(100 to 100, 640 to 480, 960 to 680, 1360 to 900, 1720 to 1399, 3000 to 1800, 32768 to 32768)
        val edge = WINDOWS_UX_WINDOW_EDGE_TOLERANCE
        var checked = 0
        for (name in layoutNames) {
            val screens = screensOf(name)
            for (zoom in listOf(50, 100, 125, 200)) {
                val minWidth = desktopWindowsUxMinimumExtent(WINDOWS_UX_WINDOW_MIN_BASE_WIDTH, zoom)
                val minHeight = desktopWindowsUxMinimumExtent(WINDOWS_UX_WINDOW_MIN_BASE_HEIGHT, zoom)
                for (x in xs) for (y in ys) for ((width, height) in sizes) {
                    val saved = DesktopWindowsUxWindowBounds(x, y, width, height, maximized = (x + y) % 2 == 0)
                    if (!saved.isSane()) continue
                    val label = "$name zoom $zoom $saved"
                    val plan = planDesktopWindowsUxWindow(saved, screens, zoom)
                    val index = assertNotNull(plan.screenIndex, label)
                    val usable = screens[index].usable
                    val left = assertNotNull(plan.x, label)
                    val top = assertNotNull(plan.y, label)
                    assertTrue(plan.width <= usable.width && plan.height <= usable.height, "$label: larger than the monitor")
                    assertTrue(plan.width >= minOf(minWidth, usable.width) && plan.height >= minOf(minHeight, usable.height),
                        "$label: below the minimum")
                    assertTrue(left >= usable.x - edge && left + plan.width <= usable.right + edge, "$label: off the monitor sideways")
                    assertTrue(top >= usable.y && top + plan.height <= usable.bottom + edge, "$label: off the monitor vertically")
                    assertEquals(saved.maximized, plan.maximized, label)
                    assertNotEquals(DesktopWindowsUxPlanKind.DEFAULT, plan.kind, label)
                    assertNotEquals(DesktopWindowsUxPlanKind.UNVALIDATED, plan.kind, label)
                    val again = planDesktopWindowsUxWindow(plan.bounds(), screens, zoom)
                    assertEquals(DesktopWindowsUxPlanKind.KEPT, again.kind, "$label: not stable")
                    assertEquals(plan.copy(kind = DesktopWindowsUxPlanKind.KEPT), again, "$label: not stable")
                    checked += 1
                }
            }
        }
        assertTrue(checked > 10_000, "the sweep checked only $checked plans")
    }
}
