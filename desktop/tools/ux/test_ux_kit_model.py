"""Tokens and pure logic of the desktop component kit (slice X2a). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_kit_model.py

What is proved. The token constants of ui/kit/DesktopKitTokens.kt (between the UX-KIT-TOKENS markers) are the numbers of
the contract ("tokens" and "rail.metrics"), and the constants of ui/kit/DesktopKitModel.kt are the "kit" numbers. Python
mirrors of the pure functions (ux_kit_model.py, Kotlin integer semantics, round half to even, float32 for the hairline)
reproduce every row of the contract's "kit" tables, and the segment step agrees with a differently shaped oracle over
every enabled mask of up to five segments. The transliterated Kotlin lines are pinned as source needles, so editing the
Kotlin without the mirror, or the mirror without the Kotlin, fails here. DesktopKitModelTest runs the same tables.

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
        source.assert_lf_only(self, *kit.KIT_FILES, kit.KOTLIN_MODEL_TEST, kit.KOTLIN_UI_TEST, here / "ux_kit_model.py",
                              here / "test_ux_kit_model.py", model.CONTRACT_PATH, model.KOTLIN_COPY_PATH)

    def test_the_contract_and_its_kotlin_copy_are_in_sync(self):
        self.assertEqual([], model.problems())


if __name__ == "__main__":
    unittest.main()
