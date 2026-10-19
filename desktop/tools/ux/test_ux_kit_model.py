"""Tokens and pure logic of the desktop component kit (slices X2a and X2b). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_kit_model.py

What is proved. The token constants of ui/kit/DesktopKitTokens.kt (between the UX-KIT-TOKENS markers) are the numbers of
the contract ("tokens" and "rail.metrics"), and the constants of ui/kit/DesktopKitModel.kt are the "kit" numbers. Python
mirrors of the pure functions (ux_kit_model.py, Kotlin integer semantics, round half to even, float32 for the hairline)
reproduce every row of the contract's "kit" tables, and the segment step agrees with a differently shaped oracle over
every enabled mask of up to five segments. The transliterated Kotlin lines are pinned as source needles, so editing the
Kotlin without the mirror, or the mirror without the Kotlin, fails here. DesktopKitModelTest runs the same tables.

Slice X2b adds the same treatment for the menu placement (the pure flip and clamp, against an oracle written as a
candidate list and over a dense grid), the menu height limit, the overlay scrollbar numbers (strip, thumb and inset at every
Windows scale times every zoom step), the toast durations and one-at-a-time state, and the WCAG contrast of the fixed
player dark palette.

What is not proved. That the Kotlin compiles or runs (DesktopKitModelTest and DesktopKitUiTest on the Linux compile are
the proof), and any composable behaviour: the guard suite reads the composable source, DesktopKitUiTest runs it.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in
desktop/tools/tests as spec 8.2 says; Root adds each suite to the validation plan as an explicit
`-s desktop/tools/ux -p <file>` line.
"""
import itertools
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_kit_model as kit  # noqa: E402
import ux_model as model  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()
KIT_TABLE = CONTRACT["kit"]
TOKENS_TEXT = kit.read(kit.TOKENS_KT)
MODEL_TEXT = kit.read(kit.MODEL_KT)
CONSTS = dict(kit.token_constants(TOKENS_TEXT))
CONSTS.update(kit.model_constants(MODEL_TEXT))


class TokenParityTest(unittest.TestCase):
    def test_the_token_block_is_marked_once_and_names_exactly_the_mapped_constants(self):
        self.assertEqual(set(kit.token_map(CONTRACT)), set(kit.token_constants(TOKENS_TEXT)))

    def test_every_token_constant_is_its_contract_number(self):
        found = kit.token_constants(TOKENS_TEXT)
        for name, expected in kit.token_map(CONTRACT).items():
            self.assertEqual(expected, found[name], name)

    def test_the_model_constants_are_the_kit_numbers(self):
        self.assertEqual(KIT_TABLE["disabled_alpha_percent"], CONSTS["DESKTOP_KIT_DISABLED_ALPHA_PERCENT"])
        self.assertEqual(KIT_TABLE["pointer_focus_window_ms"], CONSTS["DESKTOP_KIT_POINTER_FOCUS_WINDOW_MS"])
        self.assertEqual(-(2 ** 63) // 2, CONSTS["DESKTOP_KIT_NEVER_PRESSED_MS"])

    def test_the_sentinel_is_far_enough_in_the_past_and_cannot_overflow(self):
        never, window = CONSTS["DESKTOP_KIT_NEVER_PRESSED_MS"], CONSTS["DESKTOP_KIT_POINTER_FOCUS_WINDOW_MS"]
        for now in (0, 10 ** 12, 9 * 10 ** 12, -9 * 10 ** 12):
            difference = now - never
            self.assertLess(difference, 2 ** 63)
            self.assertGreater(difference, window)

    def test_the_wash_numbers_are_the_rail_rule(self):
        metrics = CONTRACT["rail"]["metrics"]
        self.assertEqual(metrics["hover_percent"], CONSTS["DESKTOP_KIT_HOVER_PERCENT"])
        self.assertEqual(metrics["pressed_percent"], CONSTS["DESKTOP_KIT_PRESSED_PERCENT"])
        self.assertEqual(metrics["selected_fill_percent"], CONSTS["DESKTOP_KIT_SELECTED_FILL_PERCENT"])
        self.assertLess(CONSTS["DESKTOP_KIT_HOVER_PERCENT"], CONSTS["DESKTOP_KIT_PRESSED_PERCENT"])
        self.assertLess(CONSTS["DESKTOP_KIT_PRESSED_PERCENT"], CONSTS["DESKTOP_KIT_SELECTED_FILL_PERCENT"])

    def test_every_control_height_respects_the_hit_floor(self):
        floor = CONSTS["DESKTOP_KIT_HIT_FLOOR_DP"]
        for name in ("DESKTOP_KIT_CONTROL_HEIGHT_DP", "DESKTOP_KIT_ICON_HIT_DP", "DESKTOP_KIT_ICON_HIT_LARGE_DP",
                     "DESKTOP_KIT_ROW_LIST_DP", "DESKTOP_KIT_ROW_NAV_DP", "DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP"):
            self.assertGreaterEqual(CONSTS[name], floor, name)

    def test_the_dp_view_only_wraps_the_constants(self):
        match = re.search(r"internal object DesktopKitTokens \{(.*?)\n\}\n", TOKENS_TEXT, re.S)
        self.assertIsNotNone(match)
        members = re.findall(r"(?m)^    val (\w+): Dp = (DESKTOP_KIT_\w+_DP)\.dp$", match.group(1))
        self.assertEqual(12, len(members))
        for _member, constant in members:
            self.assertIn(constant, CONSTS)
        self.assertEqual(12, len(re.findall(r"(?m)^    val ", match.group(1))))


class MirrorTest(unittest.TestCase):
    def test_a_hairline_is_one_pixel_at_every_density_of_the_contract(self):
        self.assertGreaterEqual(len(KIT_TABLE["hairline_densities"]), 8)
        for density in KIT_TABLE["hairline_densities"]:
            dp = kit.hairline_dp(density)
            self.assertLess(abs(kit.f32(dp * density) - 1.0), 1e-5, density)
            self.assertEqual(1, kit.layout_px(dp, density), density)

    def test_a_hairline_is_one_pixel_at_every_windows_scale_times_every_zoom_step(self):
        zoom = CONTRACT["tokens"]["zoom_percent"]
        for scale in (100, 125, 150, 175, 200, 225, 250):
            for percent in range(zoom["min"], zoom["max"] + 1, zoom["step"]):
                density = scale / 100.0 * (percent / 100.0)
                self.assertEqual(1, kit.layout_px(kit.hairline_dp(density), density), (scale, percent))

    def test_a_hairline_of_a_non_positive_density_is_one_dp(self):
        self.assertEqual(1.0, kit.hairline_dp(0.0))
        self.assertEqual(1.0, kit.hairline_dp(-2.0))

    def test_a_whole_dp_line_is_not_a_whole_pixel_at_fractional_densities(self):
        # The reason for the hairline rule: a 1 dp line is 1.5625 px at 125 percent of 125 percent, which lays out as
        # 2 px, while the hairline height lays out as exactly 1 px.
        self.assertEqual(2, kit.layout_px(1.0, 1.5625))
        self.assertEqual(2, kit.layout_px(1.0, 1.875))
        self.assertEqual(1, kit.layout_px(kit.hairline_dp(1.5625), 1.5625))
        self.assertEqual(1, kit.layout_px(kit.hairline_dp(1.875), 1.875))

    def test_every_snap_row(self):
        for row in KIT_TABLE["snap"]:
            self.assertEqual(float(row["snapped"]), kit.snap_stroke_px(row["px"]), row)

    def test_every_wash_row(self):
        for row in KIT_TABLE["wash"]:
            self.assertEqual(row["percent"], kit.wash_percent(CONSTS, row["enabled"], row["hovered"], row["pressed"]), row)
        self.assertEqual(8, len(KIT_TABLE["wash"]))
        self.assertEqual(8, len({(r["enabled"], r["hovered"], r["pressed"]) for r in KIT_TABLE["wash"]}))

    def test_every_motion_row(self):
        for row in KIT_TABLE["motion"]:
            self.assertEqual(row["expect"], kit.motion_ms(row["reduced"], row["ms"]), row)
        self.assertEqual("none", CONTRACT["tokens"]["motion_reduced"])

    def test_every_segment_step_row(self):
        for row in KIT_TABLE["segment_step"]:
            self.assertEqual(row["expect"], kit.segment_step(row["enabled"], row["current"], row["delta"]), row)

    def test_the_segment_step_agrees_with_the_oracle_over_every_mask(self):
        checked = 0
        for count in range(0, 6):
            for mask in range(1 << count):
                enabled = [(mask >> bit) & 1 == 1 for bit in range(count)]
                for current in range(-1, count):
                    for delta in (-1, 0, 1):
                        self.assertEqual(kit.step_oracle(enabled, current, delta), kit.segment_step(enabled, current, delta),
                                         (enabled, current, delta))
                        checked += 1
        self.assertEqual(963, checked)

    def test_a_step_never_selects_a_disabled_segment_and_never_changes_without_a_target(self):
        for count in range(1, 6):
            for enabled in itertools.product((True, False), repeat=count):
                for current in range(-1, count):
                    for delta in (-1, 1):
                        target = kit.segment_step(list(enabled), current, delta)
                        if target != current:
                            self.assertTrue(enabled[target], (enabled, current, delta))
                        if not any(enabled):
                            self.assertEqual(current, target)

    def test_every_segment_edge_row(self):
        for row in KIT_TABLE["segment_edge"]:
            self.assertEqual(row["expect"], kit.segment_edge(row["enabled"], row["last"]), row)

    def test_every_focus_row(self):
        for row in KIT_TABLE["focus"]:
            state = kit.run_focus(CONSTS, [tuple(event) for event in row["events"]])
            self.assertEqual(row["ring"], state.ring_visible, row["id"])
        self.assertFalse(kit.run_focus(CONSTS, []).ring_visible)

    def test_the_focus_rows_cover_both_orders_the_window_edge_and_the_reset(self):
        ids = {row["id"] for row in KIT_TABLE["focus"]}
        for needed in ("keyboard-focus", "press-then-focus", "focus-then-press", "press-then-focus-at-the-window-edge",
                       "press-then-focus-after-the-window", "navigation-key-after-press", "refocus-by-keyboard-later"):
            self.assertIn(needed, ids)
        edge = CONSTS["DESKTOP_KIT_POINTER_FOCUS_WINDOW_MS"]
        rows = {row["id"]: row for row in KIT_TABLE["focus"]}
        self.assertEqual(1000 + edge, rows["press-then-focus-at-the-window-edge"]["events"][1][1])
        self.assertEqual(1000 + edge + 1, rows["press-then-focus-after-the-window"]["events"][1][1])


class SourceNeedleTest(unittest.TestCase):
    """The mirrored Kotlin lines, whitespace-insensitively. A change on either side breaks one of these."""

    def needle(self, text):
        self.assertIn(kit.squash(text), kit.squash(MODEL_TEXT))

    def test_the_hairline_and_the_snap(self):
        self.needle("internal fun desktopKitHairlineDp(density: Float): Float = if (density > 0f) 1f / density else 1f")
        self.needle("internal fun desktopKitSnapStrokePx(px: Float): Float = round(px).coerceAtLeast(1f)")
        self.assertIn("import kotlin.math.round", MODEL_TEXT)

    def test_the_wash_and_the_motion(self):
        self.needle("""internal fun desktopKitWashPercent(enabled: Boolean, hovered: Boolean, pressed: Boolean): Int = when {
            !enabled -> 0
            pressed -> DESKTOP_KIT_PRESSED_PERCENT
            hovered -> DESKTOP_KIT_HOVER_PERCENT
            else -> 0
        }""")
        self.needle("internal fun desktopKitMotionMs(reducedMotion: Boolean, durationMs: Int): Int = if (reducedMotion) 0 else durationMs")

    def test_the_segment_step_and_edge(self):
        self.needle("""internal fun desktopKitSegmentStep(enabled: List<Boolean>, current: Int, delta: Int): Int {
            val count = enabled.size
            if (count == 0 || delta == 0) return current
            var index = current
            repeat(count) {
                index = if (index < 0) {
                    if (delta > 0) 0 else count - 1
                } else {
                    ((index + delta) % count + count) % count
                }
                if (enabled[index]) return index
            }
            return current
        }""")
        self.needle("""internal fun desktopKitSegmentEdge(enabled: List<Boolean>, last: Boolean): Int =
            if (last) enabled.indexOfLast { it } else enabled.indexOfFirst { it }""")

    def test_the_focus_state(self):
        self.needle("""internal data class DesktopKitFocusState(
            val focused: Boolean = false,
            val pointerDriven: Boolean = false,
            val lastPressMs: Long = DESKTOP_KIT_NEVER_PRESSED_MS,
        ) {
            val ringVisible: Boolean get() = focused && !pointerDriven""")
        self.needle("copy(focused = true, pointerDriven = (nowMs - lastPressMs) in 0L..DESKTOP_KIT_POINTER_FOCUS_WINDOW_MS)")
        self.needle("fun onUnfocus(): DesktopKitFocusState = copy(focused = false, pointerDriven = false)")
        self.needle("copy(pointerDriven = pointerDriven || focused, lastPressMs = nowMs)")
        self.needle("fun onNavigationKey(): DesktopKitFocusState = copy(pointerDriven = false)")

    def test_the_composables_read_the_mirrored_functions(self):
        focus, controls = kit.squash(kit.read(kit.FOCUS_KT)), kit.squash(kit.read(kit.CONTROLS_KT))
        self.assertIn("desktopKitWashPercent(enabled, hovered, pressed) / 100f", focus)
        self.assertIn("desktopKitMotionMs(reduced, DESKTOP_KIT_MOTION_FAST_MS)", focus)
        self.assertIn("desktopKitSnapStrokePx(DESKTOP_KIT_FOCUS_RING_OUTER_DP * density)", focus)
        self.assertIn("focus = focus.onFocus(desktopKitNowMs())", focus)
        self.assertIn("focus = focus.onUnfocus()", focus)
        self.assertIn("focus = focus.onPointerPress(desktopKitNowMs())", focus)
        self.assertIn("focus = focus.onNavigationKey()", focus)
        self.assertIn("desktopKitSegmentStep(flags, selectedIndex, -1)", controls)
        self.assertIn("desktopKitSegmentStep(flags, selectedIndex, 1)", controls)
        self.assertIn("desktopKitSegmentEdge(flags, last = false)", controls)
        self.assertIn("desktopKitSegmentEdge(flags, last = true)", controls)
        self.assertIn("desktopKitHairlineDp(density).dp", controls)

    def test_the_menu_placement_and_the_height_limit(self):
        self.needle("""private fun desktopKitFitAxis(preferred: Int, alternative: Int, size: Int, low: Int, high: Int): Int {
            val preferredFits = preferred >= low && preferred + size <= high
            val alternativeFits = alternative >= low && alternative + size <= high
            val chosen = if (preferredFits || !alternativeFits) preferred else alternative
            return chosen.coerceIn(low, maxOf(low, high - size))
        }""")
        self.needle("""val x = when (placement) {
            DesktopKitMenuPlacement.AT_POINT ->
                desktopKitFitAxis(anchorLeft, anchorLeft - menuWidth, menuWidth, gap, windowWidth - gap)
            DesktopKitMenuPlacement.BELOW_START ->
                desktopKitFitAxis(anchorLeft, anchorRight - menuWidth, menuWidth, gap, windowWidth - gap)
            DesktopKitMenuPlacement.BELOW_END ->
                desktopKitFitAxis(anchorRight - menuWidth, anchorLeft, menuWidth, gap, windowWidth - gap)
        }""")
        self.needle("""val y = if (atPoint) {
            desktopKitFitAxis(anchorTop, anchorTop - menuHeight, menuHeight, gap, windowHeight - gap)
        } else {
            desktopKitFitAxis(anchorBottom + gap, anchorTop - gap - menuHeight, menuHeight, gap, windowHeight - gap)
        }""")
        self.needle("internal enum class DesktopKitMenuPlacement { AT_POINT, BELOW_START, BELOW_END }")
        self.needle("internal fun desktopKitMenuMaxHeightPx(windowHeight: Int, gap: Int): Int = (windowHeight - 2 * gap).coerceAtLeast(1)")

    def test_the_scrollbar_functions(self):
        self.needle("internal fun desktopKitScrollbarNeeded(contentSize: Double, viewportSize: Double): Boolean = contentSize - viewportSize > 1.0")
        self.needle("""internal fun desktopKitScrollbarThumbPx(density: Float, stripPx: Float): Float =
            minOf(desktopKitSnapStrokePx(DESKTOP_KIT_SCROLLBAR_THUMB_DP * density), stripPx)""")
        self.needle("internal fun desktopKitScrollbarThumbInsetPx(stripPx: Float, thumbPx: Float): Float = round((stripPx - thumbPx) / 2f).coerceAtLeast(0f)")

    def test_the_toast_state(self):
        self.needle("""internal fun desktopKitToastDurationMs(hasAction: Boolean): Long =
            (if (hasAction) DESKTOP_KIT_TOAST_ACTION_MS else DESKTOP_KIT_TOAST_PLAIN_MS).toLong()""")
        self.needle("internal data class DesktopKitToastEntry(val id: Long, val message: String, val actionLabel: String?, val durationMs: Long)")
        self.needle("""internal data class DesktopKitToastState(val current: DesktopKitToastEntry? = null, val lastId: Long = 0L) {""")
        self.needle("""val text = message.trim()
            if (text.isEmpty()) return this
            val label = actionLabel?.trim()?.takeIf { it.isNotEmpty() }
            val id = lastId + 1
            return DesktopKitToastState(DesktopKitToastEntry(id, text, label, desktopKitToastDurationMs(label != null)), id)""")
        self.needle("fun dismiss(id: Long): DesktopKitToastState = if (current?.id == id) copy(current = null) else this")

    def test_the_new_composables_read_the_mirrored_functions(self):
        menus, scrollbar = kit.squash(kit.read(kit.MENUS_KT)), kit.squash(kit.read(kit.SCROLLBAR_KT))
        for needle in ("desktopKitPlaceMenu(placement, left, top, if (atPoint) left else anchorBounds.right, "
                       "if (atPoint) top else anchorBounds.bottom, popupContentSize.width, popupContentSize.height, "
                       "windowSize.width, windowSize.height, gapPx,)",
                       "desktopKitMenuMaxHeightPx(LocalWindowInfo.current.containerSize.height, gapPx)"):
            self.assertIn(needle, menus)
        for needle in ("desktopKitScrollbarThumbPx(density.density, size.width)",
                       "desktopKitScrollbarThumbInsetPx(size.width, thumb)",
                       "desktopKitScrollbarNeeded(adapter.contentSize, adapter.viewportSize)",
                       "desktopKitMotionMs(reduced, DESKTOP_KIT_MOTION_SLOW_MS)",
                       "DESKTOP_KIT_SCROLLBAR_IDLE_ALPHA_PERCENT / 100f", "DESKTOP_KIT_SCROLLBAR_HOVER_ALPHA_PERCENT / 100f"):
            self.assertIn(needle, scrollbar)
        toast = kit.squash(kit.read(kit.TOAST_KT))
        self.assertIn("state.show(message, actionLabel)", toast)
        self.assertIn("state = state.dismiss(id)", toast)
        self.assertIn("delay(lifetimeMillis(entry))", toast)
        self.assertIn("{ it.durationMs }", toast)


MENU_TABLE = KIT_TABLE["menu_place"]
SCROLLBAR_TABLE = KIT_TABLE["scrollbar"]
TOAST_TABLE = KIT_TABLE["toast"]


class MenuGeometryTest(unittest.TestCase):
    def place(self, row):
        return kit.place_menu(row["placement"], tuple(row["anchor"]), tuple(row["menu"]), tuple(row["window"]), row["gap"])

    def test_the_menu_numbers_are_the_contract_numbers(self):
        self.assertEqual(220, KIT_TABLE["menu"]["width"])
        self.assertEqual(320, KIT_TABLE["menu"]["popover_width"])
        self.assertEqual(CONTRACT["flyout"]["padding"], CONSTS["DESKTOP_KIT_MENU_PADDING_DP"])
        self.assertEqual(CONTRACT["flyout"]["anchor_gap"], CONSTS["DESKTOP_KIT_MENU_GAP_DP"])
        # Spec 5.1: the context menu rows are the 32 dp control height, and the menu radius is the menu token.
        self.assertEqual(32, CONSTS["DESKTOP_KIT_CONTROL_HEIGHT_DP"])
        self.assertEqual(12, CONSTS["DESKTOP_KIT_RADIUS_MENU_DP"])

    def test_every_golden_row_of_the_mirror_and_of_the_oracle(self):
        self.assertGreaterEqual(len(MENU_TABLE), 19)
        for row in MENU_TABLE:
            self.assertEqual(tuple(row["expect"]), self.place(row), row["id"])
            self.assertEqual(tuple(row["expect"]), kit.place_menu_oracle(
                row["placement"], tuple(row["anchor"]), tuple(row["menu"]), tuple(row["window"]), row["gap"]), row["id"])

    def test_the_rows_cover_every_placement_the_flips_and_the_clamps(self):
        ids = {row["id"] for row in MENU_TABLE}
        self.assertEqual(set(kit.PLACEMENTS), {row["placement"] for row in MENU_TABLE})
        self.assertEqual(len(ids), len(MENU_TABLE))
        for needed in ("at-point-fits", "at-point-flips-left", "at-point-flips-up", "at-point-flips-both",
                       "at-point-corner-is-clamped-into-the-gap", "at-point-exact-fit-at-the-right-edge",
                       "at-point-one-pixel-over-flips", "at-point-menu-taller-than-the-window", "below-start-flips-up",
                       "below-start-aligns-to-the-end-at-the-right-edge", "below-end-aligns-to-the-start-at-the-left-edge",
                       "below-end-neither-side-fits", "at-point-gap-zero-fits-flush"):
            self.assertIn(needed, ids)
        # The exact fit and the one pixel over differ by one pixel on the anchor and flip the menu.
        rows = {row["id"]: row for row in MENU_TABLE}
        self.assertEqual(772 + 1, rows["at-point-one-pixel-over-flips"]["anchor"][0])
        self.assertEqual(772, rows["at-point-exact-fit-at-the-right-edge"]["expect"][0])
        self.assertNotEqual(773, rows["at-point-one-pixel-over-flips"]["expect"][0])

    def test_the_mirror_agrees_with_the_oracle_over_a_dense_grid(self):
        checked = 0
        for window in ((200, 150), (480, 320), (1000, 700)):
            for menu in ((100, 60), (220, 300), (220, 800), (500, 50), (1, 1)):
                for gap in (0, 8):
                    for placement in kit.PLACEMENTS:
                        for left in range(-30, window[0] + 31, 23):
                            for top in range(-30, window[1] + 31, 19):
                                for width, height in ((0, 0), (90, 32)):
                                    anchor = (left, top, left + width, top + height)
                                    got = kit.place_menu(placement, anchor, menu, window, gap)
                                    self.assertEqual(kit.place_menu_oracle(placement, anchor, menu, window, gap), got,
                                                     (placement, anchor, menu, window, gap))
                                    checked += 1
        self.assertGreater(checked, 30000)

    def test_the_result_stays_in_the_free_range_and_never_flips_without_need(self):
        gap = CONSTS["DESKTOP_KIT_MENU_GAP_DP"]
        for window in ((300, 200), (1000, 700)):
            for menu in ((100, 60), (220, 300), (220, 800)):
                for placement in kit.PLACEMENTS:
                    for left in range(-20, window[0] + 21, 31):
                        for top in range(-20, window[1] + 21, 29):
                            anchor = (left, top, left + 80, top + 32)
                            x, y = kit.place_menu(placement, anchor, menu, window, gap)
                            for position, size, limit in ((x, menu[0], window[0]), (y, menu[1], window[1])):
                                low, high = gap, limit - gap
                                if size <= high - low:
                                    self.assertTrue(low <= position and position + size <= high, (placement, anchor, menu, window))
                                else:
                                    self.assertEqual(low, position, (placement, anchor, menu, window))

    def test_a_menu_that_fits_at_the_preferred_corner_does_not_flip(self):
        gap = CONSTS["DESKTOP_KIT_MENU_GAP_DP"]
        window, menu = (1000, 700), (220, 300)
        for left in range(gap, 1000 - gap - 220 + 1, 37):
            for top in range(gap, 700 - gap - 300 + 1, 41):
                self.assertEqual((left, top), kit.place_menu("AT_POINT", (left, top, left, top), menu, window, gap))
                # Under a 32 dp tall control the menu starts one gap below it, as long as it fits there.
                if top + 32 + gap + 300 <= 700 - gap:
                    self.assertEqual((left, top + 32 + gap),
                                     kit.place_menu("BELOW_START", (left, top, left + 100, top + 32), menu, window, gap))

    def test_the_height_limit(self):
        for row in KIT_TABLE["menu_max_height"]:
            self.assertEqual(row["expect"], kit.menu_max_height_px(row["window_height"], row["gap"]), row)
        for height in range(-5, 40):
            self.assertGreaterEqual(kit.menu_max_height_px(height, 8), 1)
        self.assertEqual(684, kit.menu_max_height_px(700, CONSTS["DESKTOP_KIT_MENU_GAP_DP"]))


class ScrollbarModelTest(unittest.TestCase):
    def test_the_scrollbar_numbers_are_the_contract_numbers(self):
        self.assertEqual(CONTRACT["tokens"]["scrollbar"]["thumb"], CONSTS["DESKTOP_KIT_SCROLLBAR_THUMB_DP"])
        self.assertEqual(CONTRACT["tokens"]["scrollbar"]["hit_strip"], CONSTS["DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP"])
        self.assertEqual(SCROLLBAR_TABLE["idle_alpha_percent"], CONSTS["DESKTOP_KIT_SCROLLBAR_IDLE_ALPHA_PERCENT"])
        self.assertEqual(SCROLLBAR_TABLE["hover_alpha_percent"], CONSTS["DESKTOP_KIT_SCROLLBAR_HOVER_ALPHA_PERCENT"])
        # The thumb is narrower than its hit strip, and the hover alpha is stronger than the idle one.
        self.assertLess(CONSTS["DESKTOP_KIT_SCROLLBAR_THUMB_DP"], CONSTS["DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP"])
        self.assertLess(CONSTS["DESKTOP_KIT_SCROLLBAR_IDLE_ALPHA_PERCENT"], CONSTS["DESKTOP_KIT_SCROLLBAR_HOVER_ALPHA_PERCENT"])

    def test_the_two_private_copies_used_the_same_alphas(self):
        # The rail (DesktopOriginalRootChrome) and the player menu (DesktopWindowsPlayerMenu) both draw .28 and .60 on a 4 dp
        # thumb. This slice does not edit them; once a later slice moves them to the kit scrollbar the pin steps aside.
        for path in (kit.source.K / "ui/DesktopOriginalRootChrome.kt", kit.source.K / "ui/DesktopWindowsPlayerMenu.kt"):
            text = kit.read(path)
            if "DesktopKitVerticalScrollbar" in text:
                continue
            self.assertIn("unhoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .28f)", text, path.name)
            self.assertIn("hoverColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .60f)", text, path.name)
            self.assertIn("thickness = 4.dp", text, path.name)

    def test_every_needed_row(self):
        for row in SCROLLBAR_TABLE["needed"]:
            self.assertEqual(row["expect"], kit.scrollbar_needed(row["content"], row["viewport"]), row)

    def test_every_thumb_row(self):
        for row in SCROLLBAR_TABLE["thumb"] + SCROLLBAR_TABLE["thumb_wider_than_the_strip"]:
            thumb = kit.scrollbar_thumb_px(CONSTS, row["density"], row["strip"])
            self.assertEqual(float(row["thumb"]), thumb, row)
            self.assertEqual(float(row["inset"]), kit.scrollbar_thumb_inset_px(row["strip"], thumb), row)

    def test_the_strip_column_is_the_layout_rounding_of_twelve_dp(self):
        for row in SCROLLBAR_TABLE["thumb"]:
            self.assertEqual(kit.kotlin_round_to_int(CONSTS["DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP"] * row["density"]), row["strip"], row)

    def test_the_thumb_is_a_whole_pixel_width_inside_the_strip_at_every_scale_and_zoom(self):
        zoom = CONTRACT["tokens"]["zoom_percent"]
        checked = 0
        for scale in (100, 125, 150, 175, 200, 225, 250):
            for percent in range(zoom["min"], zoom["max"] + 1, zoom["step"]):
                density = kit.f32(scale / 100.0 * (percent / 100.0))
                strip = kit.kotlin_round_to_int(CONSTS["DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP"] * density)
                thumb = kit.scrollbar_thumb_px(CONSTS, density, strip)
                inset = kit.scrollbar_thumb_inset_px(strip, thumb)
                label = (scale, percent)
                self.assertEqual(thumb, float(int(thumb)), label)
                self.assertGreaterEqual(thumb, 1.0, label)
                self.assertLessEqual(thumb, strip, label)
                self.assertGreaterEqual(inset, 0.0, label)
                self.assertLessEqual(inset + thumb, strip, label)
                if thumb < strip:
                    self.assertLessEqual(abs(thumb - CONSTS["DESKTOP_KIT_SCROLLBAR_THUMB_DP"] * density), 0.5, label)
                checked += 1
        self.assertEqual(7 * 31, checked)


class ToastModelTest(unittest.TestCase):
    def test_the_durations_are_the_contract_numbers(self):
        self.assertEqual(CONTRACT["tokens"]["toast_ms"]["plain"], CONSTS["DESKTOP_KIT_TOAST_PLAIN_MS"])
        self.assertEqual(CONTRACT["tokens"]["toast_ms"]["with_action"], CONSTS["DESKTOP_KIT_TOAST_ACTION_MS"])
        self.assertEqual((2400, 5000), (CONSTS["DESKTOP_KIT_TOAST_PLAIN_MS"], CONSTS["DESKTOP_KIT_TOAST_ACTION_MS"]))
        for row in TOAST_TABLE["duration"]:
            self.assertEqual(row["ms"], kit.toast_duration_ms(CONSTS, row["has_action"]), row)
        self.assertEqual(2, len(TOAST_TABLE["duration"]))

    def test_every_script_step(self):
        self.assertGreaterEqual(len(TOAST_TABLE["script"]), 5)
        for script in TOAST_TABLE["script"]:
            for step, observed in kit.run_toast_script(CONSTS, script["steps"]):
                self.assertEqual(step["expect"], observed, (script["id"], step))

    def test_the_scripts_cover_replace_stale_timer_blank_text_and_id_reuse(self):
        ids = {script["id"] for script in TOAST_TABLE["script"]}
        for needed in ("plain", "a-new-toast-replaces-the-shown-one", "a-replaced-action-toast-loses-its-action",
                       "blank-text-is-ignored-and-takes-no-id", "ids-are-never-reused"):
            self.assertIn(needed, ids)

    def test_one_toast_at_a_time_and_ids_only_grow(self):
        state = kit.ToastState(CONSTS)
        seen = []
        for index in range(12):
            state = state.show("message %d" % index, "Undo" if index % 3 == 0 else None)
            seen.append(state.current.id)
            self.assertEqual(index + 1, state.last_id)
            self.assertEqual(kit.toast_duration_ms(CONSTS, index % 3 == 0), state.current.ms)
            if index % 4 == 0:
                state = state.dismiss(state.current.id)
                self.assertIsNone(state.current)
        self.assertEqual(sorted(set(seen)), seen)

    def test_a_stale_dismiss_never_closes_a_newer_toast(self):
        state = kit.ToastState(CONSTS).show("first").show("second")
        for stale in (0, 1, 3, 99, -1):
            self.assertEqual(2, state.dismiss(stale).current.id)
        self.assertIsNone(state.dismiss(2).current)


class DarkPaletteTest(unittest.TestCase):
    PALETTE = kit.palette_constants(kit.read(kit.DARK_THEME_KT))

    # (foreground, background, least WCAG contrast): text 4.5 (7 for primary text), accents and outlines 3 as UI parts.
    PAIRS = (
        ("ON_SURFACE", "BACKGROUND", 7.0), ("ON_SURFACE", "SURFACE", 7.0), ("ON_SURFACE", "CONTAINER_LOW", 7.0),
        ("ON_SURFACE", "CONTAINER", 7.0), ("ON_SURFACE", "CONTAINER_HIGH", 7.0), ("ON_SURFACE", "CONTAINER_HIGHEST", 7.0),
        ("ON_SURFACE_VARIANT", "BACKGROUND", 4.5), ("ON_SURFACE_VARIANT", "SURFACE", 4.5),
        ("ON_SURFACE_VARIANT", "CONTAINER_LOW", 4.5), ("ON_SURFACE_VARIANT", "CONTAINER", 4.5),
        ("ON_SURFACE_VARIANT", "CONTAINER_HIGH", 4.5), ("ON_SURFACE_VARIANT", "CONTAINER_HIGHEST", 4.5),
        ("PRIMARY", "SURFACE", 4.5), ("PRIMARY", "CONTAINER_HIGHEST", 4.5), ("ON_PRIMARY", "PRIMARY", 4.5),
        ("ON_PRIMARY_CONTAINER", "PRIMARY_CONTAINER", 4.5), ("SECONDARY", "SURFACE", 4.5),
        ("ON_SECONDARY", "SECONDARY", 4.5), ("ON_SECONDARY_CONTAINER", "SECONDARY_CONTAINER", 4.5),
        ("ERROR", "SURFACE", 4.5), ("ON_ERROR", "ERROR", 4.5), ("ON_ERROR_CONTAINER", "ERROR_CONTAINER", 4.5),
        ("OUTLINE", "SURFACE", 3.0), ("OUTLINE", "CONTAINER_HIGHEST", 3.0), ("OUTLINE_VARIANT", "SURFACE", 1.3),
    )

    def value(self, name):
        return self.PALETTE["DESKTOP_PLAYER_DARK_" + name]

    def test_the_palette_is_marked_once_and_every_colour_is_opaque(self):
        self.assertEqual(22, len(self.PALETTE))
        for name, argb in self.PALETTE.items():
            self.assertEqual(0xFF, (argb >> 24) & 0xFF, name)

    def test_every_pair_meets_its_contrast(self):
        for foreground, background, least in self.PAIRS:
            ratio = kit.contrast(self.value(foreground), self.value(background))
            self.assertGreaterEqual(ratio, least, "%s on %s is %.2f" % (foreground, background, ratio))

    def test_the_surfaces_step_up_in_brightness_and_the_palette_is_dark(self):
        order = ("BACKGROUND", "SURFACE", "CONTAINER_LOW", "CONTAINER", "CONTAINER_HIGH", "CONTAINER_HIGHEST")
        lights = [kit.luminance(self.value(name)) for name in order]
        self.assertEqual(sorted(lights), lights)
        self.assertLess(lights[-1], 0.05)
        self.assertGreater(kit.luminance(self.value("ON_SURFACE")), 0.5)

    def test_the_contrast_function_matches_the_wcag_extremes(self):
        self.assertAlmostEqual(21.0, kit.contrast(0xFFFFFFFF, 0xFF000000), places=6)
        self.assertAlmostEqual(1.0, kit.contrast(0xFF123456, 0xFF123456), places=6)
        self.assertAlmostEqual(kit.contrast(0xFF123456, 0xFFABCDEF), kit.contrast(0xFFABCDEF, 0xFF123456), places=9)
        with self.assertRaises(ValueError):
            kit.luminance(0x80FFFFFF)


class JvmMirrorTest(unittest.TestCase):
    """DesktopKitModelTest runs the same contract tables as this suite."""

    @classmethod
    def setUpClass(cls):
        cls.text = kit.read(kit.KOTLIN_MODEL_TEST)

    def test_it_reads_every_table_of_the_kit_section(self):
        for table in ("hairline_densities", "snap", "wash", "motion", "segment_step", "segment_edge", "focus"):
            self.assertIn('kit.getValue("%s")' % table, self.text, table)
        for name in ("disabled_alpha_percent", "pointer_focus_window_ms"):
            self.assertIn('"%s"' % name, self.text, name)

    def test_it_reads_the_tables_of_slice_x2b(self):
        for table in ("menu", "menu_place", "menu_max_height", "scrollbar", "toast"):
            self.assertIn('kit.getValue("%s")' % table, self.text, table)
        for name in ("idle_alpha_percent", "hover_alpha_percent", "needed", "thumb", "thumb_wider_than_the_strip",
                     "duration", "script", "popover_width"):
            self.assertIn('"%s"' % name, self.text, name)
        for function in ("desktopKitPlaceMenu", "desktopKitMenuMaxHeightPx", "desktopKitScrollbarNeeded",
                         "desktopKitScrollbarThumbPx", "desktopKitScrollbarThumbInsetPx", "desktopKitToastDurationMs",
                         "DesktopKitToastState"):
            self.assertIn(function, self.text, function)

    def test_it_names_every_token_constant(self):
        for name in kit.token_map(CONTRACT):
            self.assertIn(name, self.text, name)

    def test_the_test_classes_are_the_ones_reported(self):
        self.assertIn("class DesktopKitModelTest {", self.text)
        self.assertIn("class DesktopKitUiTest {", kit.read(kit.KOTLIN_UI_TEST))
        self.assertIn("package com.bilipai.desktop.ui.kit", self.text)


class FileHygieneTest(unittest.TestCase):
    def test_the_slice_files_are_lf_only_without_control_characters(self):
        here = Path(__file__).resolve().parent
        source.assert_lf_only(self, *kit.KIT_FILES, kit.TOAST_KT, kit.KOTLIN_MODEL_TEST, kit.KOTLIN_UI_TEST,
                              here / "ux_kit_model.py", here / "test_ux_kit_model.py", model.CONTRACT_PATH,
                              model.KOTLIN_COPY_PATH)

    def test_the_contract_and_its_kotlin_copy_are_in_sync(self):
        self.assertEqual([], model.problems())


if __name__ == "__main__":
    unittest.main()
