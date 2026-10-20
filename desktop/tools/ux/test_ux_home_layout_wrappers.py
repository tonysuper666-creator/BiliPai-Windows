"""Slice H1 number checks: the Home feed wrappers against the vendored functions, the density rule and the contract. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_layout_wrappers.py

What is proved. ux_home_layout_model.py holds Python ports of the three vendored layout functions the wrappers delegate to and
line-by-line mirrors (Kotlin integer semantics) of the functions of ui/DesktopHomeFeedLayoutPolicy.kt and ui/DesktopHomeFeedLayout.kt.

  VendoredPortTest      the ports transliterate the vendored Kotlin as it is: the three vendored files are pinned by sha256, the transliterated
                        lines by needle, the width class thresholds, the enum orders and the card width presets are parsed from the vendored text
  KotlinMirrorPinTest   the mirrored Kotlin lines of the new functions are pinned as needles, so editing a function without its mirror fails here
  WrapperNumbersTest    every golden row of the contract (the 12 spec rows and the rows for the other rail modes and card presets), the sweep
                        W 960..4200 in every rail mode and card preset against the exact rational reference (ux_model.feed_layout), the sweep
                        properties, the delegation cases (CLASSIC for every input, a pinned count and the single-column mode in the desktop layout:
                        the column function, the feed width held at the vendored cap, the vendored card layout, and the card sizes of the Story
                        mode and of a pinned two or three columns that the second review computed), the feed width of both layouts, the card
                        the feed draws, and the compact metadata rule against an independent oracle.
                        "Drawn" means computed from the card layout's own padding and spacing with the formula of the vendored cover request
                        (HomeScreen.kt), so the golden grid, the maximum card and the centring are checked on what the feed really gets; the
                        side space is rounded up to a whole dp (the vendored layout takes an Int), so a held grid is at most 1 dp narrower than
                        the contract grid and a held card at most 1 / columns narrower than the maximum card
  JvmTestFileTest       DesktopHomeFeedLayoutTest exists with its test count, uses no window or network, reads the contract, and calls the wrappers
                        with the right argument order

What is not proved. That the Kotlin compiles or runs: DesktopHomeFeedLayoutTest is the proof, on the Linux compile later. The generator edits and
the Gradle wiring are in test_ux_home_layout_adaptations.py.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in desktop/tools/tests as spec 8.2 says;
Root adds each suite to the validation plan as an explicit `-s desktop/tools/ux -p <file>` line.
"""
import hashlib
import math
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_home_layout_model as hm  # noqa: E402
import ux_model as model  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()
RAIL_TEXT = shell.read(shell.RAIL_KT)
METRICS_TEXT = shell.read(shell.METRICS_KT)
LAYOUT_TEXT = source.read(hm.LAYOUT_KT)
POLICY_TEXT = source.read(hm.POLICY_KT)
LIMITS = shell.thresholds(RAIL_TEXT)
CONSTS = dict(shell.int_constants(RAIL_TEXT))
CONSTS.update(shell.int_constants(METRICS_TEXT))
FOOTPRINT = {"expanded": CONSTS["DESKTOP_RAIL_FOOTPRINT_EXPANDED_DP"], "collapsed": CONSTS["DESKTOP_RAIL_FOOTPRINT_COLLAPSED_DP"],
             "classic": CONSTS["DESKTOP_RAIL_FOOTPRINT_CLASSIC_DP"]}
FIRST, LAST = CONTRACT["feed"]["sweep"]["from"], CONTRACT["feed"]["sweep"]["to"]
PRESETS = tuple(hm.PRESET_MIN_CARD.values())
STYLES = ("CURRENT", "OFFICIAL", "BILIPAI")
JVM_TEST = source.KT / "ui/DesktopHomeFeedLayoutTest.kt"
JVM_TEST_COUNT = 18

# The vendored files the ports transliterate, as LF text: sha256 and byte count.
VENDORED_PINS = {
    hm.GRID_POLICY: ("7abce54b9d14203d7c4aa008d1fb1b144e02d3685a256de7d562b547aed3cec4", 2839),
    hm.STYLE_POLICY: ("857d9615df833228e526506168c6e68ff387a224249d8f6687a949d19dd3d0e7", 3684),
    hm.DENSITY_POLICY: ("078e67c2e210c78d58113460b545e7bd84c6c0dad23571bf2a5d688a4a96222b", 1196),
}


def has(text, needle):
    return shell.squash(needle) in shell.squash(text)


def layout_of(mode, width, min_card=None, delegated=False):
    """The rail presentation (a fresh evaluation) and the content width the mirrored wrapper measures for a window."""
    rail = shell.rail_presentation_kt(LIMITS, mode, width)
    return rail, int(hm.measured_width_kt(width, FOOTPRINT[rail], "DESKTOP", delegated))  # the Kotlin test takes .value.toInt()


def drawn(layout, style, wc, columns, delegated=False):
    """(card, grid, geometry) the feed draws for the card layout the wrapper returns for a flag, a style, a content width and a column count."""
    geometry = hm.card_layout_geometry_kt(CONSTS, layout, style, columns, wc, delegated)
    card, grid = hm.drawn_card_kt(geometry, wc, columns)
    return card, grid, geometry


def wanted_card(min_card):
    """The card width a preset wants: the preset, at least the floor; the default is the minimum card."""
    return max(CONSTS["DESKTOP_HOME_MIN_CARD_DP"] if min_card is None else min_card, CONSTS["DESKTOP_HOME_CARD_FLOOR_DP"])


class VendoredPortTest(unittest.TestCase):
    def test_the_vendored_files_are_the_pinned_bytes(self):
        for path, (digest, size) in VENDORED_PINS.items():
            data = path.read_bytes().replace(b"\r\n", b"\n")
            self.assertEqual((digest, size), (hashlib.sha256(data).hexdigest(), len(data)), path.name)

    def test_the_grid_function_lines_the_port_transliterates(self):
        text = source.read(hm.GRID_POLICY)
        for needle in (
            "internal fun resolveHomeFeedMaxContentWidth(): Dp = 1280.dp",
            "val isSingleColumnMode = displayMode == 1",
            "if (!isSingleColumnMode && fixedColumnCount > 0) { return fixedColumnCount }",
            "val minColumnWidthDp = if (isSingleColumnMode) { 280 } else { cardWidthPreset.minCardWidthDp ?: 180 }",
            "val maxColumns = if (isSingleColumnMode) { 2 } else { when (widthSizeClass) {",
            "WindowWidthSizeClass.Compact -> 4 WindowWidthSizeClass.Medium -> 6 WindowWidthSizeClass.Expanded -> 6 "
            "WindowWidthSizeClass.Large -> 7 WindowWidthSizeClass.ExtraLarge -> 8",
            "val columns = contentWidthDp / minColumnWidthDp",
            "val minColumns = if (!isSingleColumnMode && contentWidthDp >= 300) 2 else 1",
            "return columns.coerceIn(minColumns, maxColumns)",
            "widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Medium",
        ):
            self.assertTrue(has(text, needle), needle)
        self.assertEqual(hm.VENDORED_CAP_DP, 1280)
        self.assertEqual((hm.VENDORED_DEFAULT_MIN_CARD, hm.VENDORED_SINGLE_COLUMN_MIN), (180, 280))
        self.assertEqual({"Compact": 4, "Medium": 6, "Expanded": 6, "Large": 7, "ExtraLarge": 8}, hm.VENDORED_MAX_COLUMNS)

    def test_the_density_policy_lines_the_port_transliterates(self):
        text = source.read(hm.DENSITY_POLICY)
        for needle in (
            "val denseLargeGrid = widthSizeClass >= WindowWidthSizeClass.Expanded && gridColumns >= 6",
            "val styleUsesCompactMetadata = style != HomeFeedCardStyle.CURRENT",
            "compactMetadata = styleUsesCompactMetadata || denseLargeGrid,",
        ):
            self.assertTrue(has(text, needle), needle)
        self.assertEqual(6, hm.VENDORED_DENSE_COLUMNS)
        style = source.read(hm.STYLE_POLICY)
        self.assertTrue(has(style, "val compactMetadata: Boolean get() = density.compactMetadata"))
        self.assertTrue(has(style, "val density = resolveHomeFeedCardDensityPolicy( style = style, gridColumns = gridColumns, widthSizeClass = widthSizeClass, )"))

    def test_the_vendored_card_geometry_is_six_dp_for_every_style(self):
        text = source.read(hm.STYLE_POLICY)
        start = text.index("internal fun resolveHomeFeedCardLayout(")
        body = text[start:]
        for field in ("outerPaddingDp", "itemSpacingDp", "verticalItemSpacingDp"):
            self.assertEqual(3, len(re.findall(r"(?m)^\s+%s = 6,$" % field, body)), field)
            self.assertEqual(3, len(re.findall(r"\b%s = \d+," % field, body)), field)  # no other value is assigned in any style
        self.assertTrue(has(text, "val verticalItemSpacingDp: Int = itemSpacingDp,"))
        self.assertEqual((6, 6, 6), hm.VENDORED_GEOMETRY)
        for style in STYLES:
            self.assertEqual((6, 6, 6), hm.vendored_card_geometry(style))
        with self.assertRaises(ValueError):
            hm.vendored_card_geometry("OTHER")

    def test_the_vendored_feed_draws_the_card_layout_geometry(self):
        # The drawn-card checks rely on how the vendored Home screen uses the layout: the grid content padding and the horizontal
        # arrangement come from outerPaddingDp and itemSpacingDp, the vertical spacing from verticalItemSpacingDp, and the cover request
        # computes the card width with the formula that hm.drawn_card_kt repeats.
        text = source.read(source.REPO / "app/src/main/java/com/android/purebilibili/feature/home/HomeScreen.kt")
        self.assertTrue(has(text, "start = homeFeedCardLayout.outerPaddingDp.dp, end = homeFeedCardLayout.outerPaddingDp.dp,"))
        self.assertTrue(has(text, "val homeFeedHorizontalArrangement = Arrangement.spacedBy(homeFeedCardLayout.itemSpacingDp.dp)"))
        self.assertTrue(has(text, "verticalItemSpacing = homeFeedCardLayout.verticalItemSpacingDp.dp,"))
        self.assertTrue(has(text, "val cardWidthDp = ( contentWidth.value - homeFeedCardLayout.outerPaddingDp * 2f - "
                                  "homeFeedCardLayout.itemSpacingDp * (effectiveGridColumns - 1).coerceAtLeast(0) ) / effectiveGridColumns.coerceAtLeast(1)"))
        self.assertTrue(has(text, "modifier = Modifier.responsiveContentWidth(maxWidth = contentWidth).fillMaxSize(),"))
        self.assertEqual(((1000 - 2 * 24 - 16 * 3) / 4, 1000 - 48), hm.drawn_card_kt((24, 16, 16), 1000, 4))
        self.assertEqual((952.0, 952), hm.drawn_card_kt((24, 16, 16), 1000, 0))

    def test_the_width_classes_follow_the_vendored_thresholds_and_order(self):
        text = source.read(hm.WIDTH_UTILS)
        start = text.index("internal fun resolveWindowWidthSizeClass(widthDp: Dp)")
        body = text[start:text.index("\n}\n", start)]
        thresholds = [int(n) for n in re.findall(r"widthDp < (\d+)\.dp", body)]
        self.assertEqual([600, 840, 1200, 1600], thresholds)
        enum = text[text.index("enum class WindowWidthSizeClass {"):]
        enum = enum[:enum.index("}")]
        self.assertEqual(list(hm.WIDTH_CLASSES), re.findall(r"\b(Compact|Medium|Expanded|Large|ExtraLarge)\b", enum))
        for width in (0, 599, 600, 839, 840, 1199, 1200, 1599, 1600, 3000):
            expected = hm.WIDTH_CLASSES[sum(width >= t for t in thresholds)]
            self.assertEqual(expected, hm.width_class_of(width), width)

    def test_the_card_width_presets_are_the_vendored_ones(self):
        text = source.read(source.REPO / "app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt")
        start = text.index("enum class HomeFeedCardWidthPreset(")
        rows = re.findall(r'(?m)^    (\w+)\(\d+, "[^"]+", (\d+|null)\)', text[start:text.index("companion object", start)])
        found = {name: (None if value == "null" else int(value)) for name, value in rows}
        self.assertEqual(hm.PRESET_MIN_CARD, found)
        self.assertEqual(sorted(v for v in found.values() if v), CONTRACT["feed"]["rules"]["card_presets"])

    def test_the_port_matches_known_vendored_results(self):
        port = hm.vendored_grid_columns
        # (content width, display mode, pinned count, preset minimum, width class) -> columns, by hand from the vendored function.
        cases = (
            ((1000, 0, 0, None, "Expanded"), 5), ((1000, 0, 0, 200, "Expanded"), 5), ((2000, 0, 0, None, "Large"), 7),
            ((2000, 0, 0, None, "ExtraLarge"), 8), ((2000, 0, 0, 320, "ExtraLarge"), 6), ((299, 0, 0, None, "Medium"), 1),
            ((300, 0, 0, None, "Medium"), 2), ((3000, 1, 0, None, "ExtraLarge"), 2), ((250, 1, 0, None, "Compact"), 1),
            ((3000, 0, 4, None, "ExtraLarge"), 4), ((3000, 1, 4, None, "ExtraLarge"), 2),
        )
        for args, expected in cases:
            self.assertEqual(expected, port(*args), args)


class KotlinMirrorPinTest(unittest.TestCase):
    def test_the_policy_functions_are_the_mirrored_lines(self):
        code = source.mask_kotlin(POLICY_TEXT)
        for needle in (
            "internal fun desktopHomeFeedDesktopWidthDp(windowWidthDp: Float, railFootprintDp: Int): Float { "
            "val rest = windowWidthDp - railFootprintDp return if (rest > 0f) rest else windowWidthDp }",
            "internal fun desktopHomeDensitySideInsetDp(contentWidthDp: Int, columns: Int): Int { val count = max(columns, 1) "
            "val gutters = (count - 1) * DESKTOP_HOME_GUTTER_DP val room = contentWidthDp - 2 * DESKTOP_HOME_SIDE_PADDING_DP - gutters "
            "return if (room > count * DESKTOP_HOME_MAX_CARD_DP) { "
            "(contentWidthDp - count * DESKTOP_HOME_MAX_CARD_DP - gutters + 1) / 2 } else { DESKTOP_HOME_SIDE_PADDING_DP } }",
            "internal fun desktopHomeDensityCardWidthDp(contentWidthDp: Int, columns: Int): Double { val count = max(columns, 1) "
            "val room = contentWidthDp - 2 * desktopHomeDensitySideInsetDp(contentWidthDp, count) - (count - 1) * DESKTOP_HOME_GUTTER_DP "
            "return room.toDouble() / count }",
            "val compact = desktopHomeDensityCompactMetadata( original.compactMetadata, "
            "desktopHomeDensityCardWidthDp(contentWidthDp, gridColumns), )",
            "return original.copy( outerPaddingDp = desktopHomeDensitySideInsetDp(contentWidthDp, gridColumns), "
            "itemSpacingDp = DESKTOP_HOME_GUTTER_DP, verticalItemSpacingDp = DESKTOP_HOME_GUTTER_DP, "
            "density = if (compact == original.compactMetadata) original.density else "
            "original.density.copy(compactMetadata = compact), )",
        ):
            self.assertTrue(has(code, needle), needle)

    def test_the_layout_wrappers_are_the_mirrored_lines(self):
        code = source.mask_kotlin(LAYOUT_TEXT)
        for needle in (
            "internal fun desktopHomeFeedColumnsDelegated(displayMode: Int, fixedColumnCount: Int): Boolean = "
            "desktopHomeDensityDelegation(DesktopWindowsUxHomeLayout.DESKTOP, displayMode, fixedColumnCount) != null",
            "internal fun desktopHomeFeedMeasuredWidth( windowWidth: Dp, railFootprintDp: Int, homeLayout: DesktopWindowsUxHomeLayout, "
            "delegated: Boolean = false, ): Dp = when (homeLayout) {",
            "DesktopWindowsUxHomeLayout.CLASSIC -> minOf(windowWidth, resolveHomeFeedMaxContentWidth())",
            "DesktopWindowsUxHomeLayout.DESKTOP -> if (delegated) { "
            "minOf(desktopHomeFeedDesktopWidthDp(windowWidth.value, railFootprintDp).dp, resolveHomeFeedMaxContentWidth()) } else { "
            "desktopHomeFeedDesktopWidthDp(windowWidth.value, railFootprintDp).dp }",
            "if (desktopHomeDensityDelegation(layout, displayMode, fixedColumnCount) != null) {",
            "resolveDesktopHomeDensity(contentWidthDp, cardWidthPreset.minCardWidthDp).columns",
            "internal fun desktopSelectHomeFeedCardLayout( layout: DesktopWindowsUxHomeLayout, original: HomeFeedCardLayout, "
            "contentWidthDp: Int, gridColumns: Int, delegated: Boolean = false, ): HomeFeedCardLayout = when (layout) {",
            "DesktopWindowsUxHomeLayout.CLASSIC -> original",
            "DesktopWindowsUxHomeLayout.DESKTOP -> if (delegated) original else desktopHomeDensityCardLayout(original, contentWidthDp, gridColumns)",
            "return remember(layout, style, gridColumns, widthSizeClass, contentWidthDp, delegated) { desktopSelectHomeFeedCardLayout( layout, "
            "resolveHomeFeedCardLayout( style = style, gridColumns = gridColumns, widthSizeClass = widthSizeClass, ), "
            "contentWidthDp, gridColumns, delegated, ) }",
        ):
            self.assertTrue(has(code, needle), needle)

    def test_the_constants_the_policy_uses_are_the_contract_ones(self):
        rules = CONTRACT["feed"]["rules"]
        self.assertEqual(rules["side_padding"], CONSTS["DESKTOP_HOME_SIDE_PADDING_DP"])
        self.assertEqual(rules["gutter"], CONSTS["DESKTOP_HOME_GUTTER_DP"])
        self.assertEqual(rules["max_card"], CONSTS["DESKTOP_HOME_MAX_CARD_DP"])
        self.assertEqual(rules["min_card"], CONSTS["DESKTOP_HOME_MIN_CARD_DP"])
        self.assertEqual(rules["card_floor"], CONSTS["DESKTOP_HOME_CARD_FLOOR_DP"])
        self.assertEqual(rules["compact_metadata_off_from_card"], CONSTS["DESKTOP_HOME_COMPACT_METADATA_OFF_FROM_CARD_DP"])
        self.assertEqual(rules["dropped_legacy_cap"], hm.VENDORED_CAP_DP)


class WrapperNumbersTest(unittest.TestCase):
    def test_every_golden_row_through_the_mirrored_wrappers(self):
        side, gutter, maximum = (CONSTS["DESKTOP_HOME_SIDE_PADDING_DP"], CONSTS["DESKTOP_HOME_GUTTER_DP"], CONSTS["DESKTOP_HOME_MAX_CARD_DP"])
        capped_ids = []
        for row in CONTRACT["feed"]["golden"]:
            expect = row["expect"]
            mode = row.get("mode", "AUTO")
            rail, wc = layout_of(mode, row["width_dp"])
            self.assertEqual(expect["rail"], rail, row["id"])
            self.assertEqual(expect["wc"], wc, row["id"])
            columns = hm.grid_columns_kt(CONSTS, "DESKTOP", wc, 0, 0, row.get("min_card"), "ExtraLarge")
            self.assertEqual(expect["cols"], columns, row["id"])
            # What the feed draws: the card layout's own padding and spacing.
            card, grid, (outer, item, vertical) = drawn("DESKTOP", "BILIPAI", wc, columns)
            self.assertEqual((gutter, gutter), (item, vertical), row["id"])
            self.assertEqual(expect["grid"], grid, row["id"])
            if expect["capped"]:
                capped_ids.append(row["id"])
                # Held at the maximum card and centred: the side space is half of what the grid leaves over (these rows have an even rest).
                self.assertEqual(maximum, card, row["id"])
                self.assertEqual(0, (wc - expect["grid"]) % 2, row["id"])
                self.assertEqual((wc - expect["grid"]) // 2, outer, row["id"])
                self.assertGreater(outer, side, row["id"])
            else:
                self.assertLessEqual(abs(card - expect["card"]), 0.05 + 1e-9, row["id"])
                self.assertEqual(side, outer, row["id"])
        self.assertEqual(["spec-w3000", "spec-w3840", "preset-260-w1600"], capped_ids)

    def test_the_twelve_spec_rows_are_in_the_contract(self):
        ids = [row["id"] for row in CONTRACT["feed"]["golden"] if row["id"].startswith("spec-")]
        self.assertEqual(12, len(ids))

    def test_the_mirror_equals_the_exact_reference_over_the_whole_sweep(self):
        maximum = CONSTS["DESKTOP_HOME_MAX_CARD_DP"]
        held = 0
        for mode in CONTRACT["feed"]["sweep"]["rail_modes"] + ["CLASSIC_TEXT"]:
            for min_card in PRESETS:
                for width in range(FIRST, LAST + 1):
                    exact = model.feed_layout(CONTRACT, width, mode, None, min_card)
                    rail, wc = layout_of(mode, width)
                    columns = hm.grid_columns_kt(CONSTS, "DESKTOP", wc, 0, 0, min_card, "ExtraLarge")
                    label = (mode, min_card, width)
                    self.assertEqual((exact["rail"], exact["wc"], exact["columns"]), (rail, wc, columns), label)
                    card, grid, (outer, item, vertical) = drawn("DESKTOP", "BILIPAI", wc, columns)
                    self.assertEqual((16, 16), (item, vertical), label)
                    self.assertEqual(card, hm.card_width_kt(CONSTS, wc, columns), label)
                    # The side space is the exact side inset of the reference rounded up (the vendored layout takes whole dp).
                    self.assertEqual(-((exact["grid"] - wc) // 2), outer, label)
                    # The feed never draws a card above the maximum, nor below the card the preset wants (less the rounding of a held grid).
                    self.assertLessEqual(card, maximum, label)
                    self.assertGreaterEqual(card, wanted_card(min_card) - 1 / columns - 1e-9, label)
                    if exact["capped"]:
                        held += 1
                        self.assertIn(exact["grid"] - grid, (0, 1), label)
                        self.assertGreaterEqual(card, maximum - 1 / columns - 1e-9, label)
                    else:
                        self.assertEqual(exact["grid"], grid, label)
                        self.assertEqual(CONSTS["DESKTOP_HOME_SIDE_PADDING_DP"], outer, label)
                        self.assertLess(abs(card - float(exact["card"])), 1e-9, label)
        self.assertEqual(25084, held)  # the held grids are a real part of the sweep, not a corner of it

    def test_the_default_grid_is_held_at_the_maximum_card_for_exactly_the_widths_of_the_review(self):
        # The widths where the rule caps the grid with the default card width in the automatic rail mode: before the card layout
        # carried the side space, the feed drew cards of about 341 dp at W = 3000 and 356 dp at W = 3840 there.
        widths = [width for width in range(FIRST, LAST + 1) if shell.home_density_kt(CONSTS, layout_of("AUTO", width)[1])[3]]
        ranges = []
        for width in widths:
            if ranges and width == ranges[-1][1] + 1:
                ranges[-1][1] = width
            else:
                ranges.append([width, width])
        self.assertEqual([[2937, 3015], [3609, 4200]], ranges)
        self.assertEqual(671, len(widths))
        for width in (2937, 3000, 3015, 3609, 3840, 4200):
            rail, wc = layout_of("AUTO", width)
            columns = hm.grid_columns_kt(CONSTS, "DESKTOP", wc, 0, 0, None, "ExtraLarge")
            card = drawn("DESKTOP", "BILIPAI", wc, columns)[0]
            self.assertLessEqual(card, CONSTS["DESKTOP_HOME_MAX_CARD_DP"], width)
            self.assertGreaterEqual(card, CONSTS["DESKTOP_HOME_MAX_CARD_DP"] - 1 / columns - 1e-9, width)
            # The vendored geometry (6 dp padding and spacing) would have drawn a wider card: the review's numbers.
            vendored_card = hm.drawn_card_kt(hm.VENDORED_GEOMETRY, wc, columns)[0]
            self.assertGreater(vendored_card, CONSTS["DESKTOP_HOME_MAX_CARD_DP"], width)
        self.assertAlmostEqual(341.0, hm.drawn_card_kt(hm.VENDORED_GEOMETRY, layout_of("AUTO", 3000)[1], 8)[0], delta=0.5)
        self.assertAlmostEqual(356.0, hm.drawn_card_kt(hm.VENDORED_GEOMETRY, layout_of("AUTO", 3840)[1], 10)[0], delta=0.5)

    def test_the_sweep_properties_of_the_spec_hold_for_the_drawn_card(self):
        switch = {"AUTO": 1280, "EXPANDED": 1100}
        for mode in CONTRACT["feed"]["sweep"]["rail_modes"]:
            drops, last = [], {}
            previous_columns = 0
            for width in range(FIRST, LAST + 1):
                rail, wc = layout_of(mode, width)
                columns = hm.grid_columns_kt(CONSTS, "DESKTOP", wc, 0, 0, None, "ExtraLarge")
                label = (mode, width)
                self.assertGreaterEqual(wc, CONSTS["DESKTOP_HOME_MIN_CONTENT_DP"], label)
                # Spec 3.6: the card the feed draws is within [216, 320].
                card = drawn("DESKTOP", "BILIPAI", wc, columns)[0]
                self.assertGreaterEqual(card, CONSTS["DESKTOP_HOME_MIN_CARD_DP"], label)
                self.assertLessEqual(card, CONSTS["DESKTOP_HOME_MAX_CARD_DP"], label)
                self.assertGreaterEqual(columns, last.get(rail, 0), label)
                last[rail] = columns
                if width > FIRST and columns < previous_columns:
                    drops.append(width)
                previous_columns = columns
            self.assertEqual([switch[mode]] if mode in switch else [], drops, mode)

    def test_classic_is_the_vendored_function_for_every_input(self):
        count = 0
        for min_card in PRESETS:
            for width_class in hm.WIDTH_CLASSES:
                for display_mode in (0, 1, 2):
                    for fixed in (-1, 0, 1, 3, 6):
                        for width in range(200, 4201, 37):
                            expected = hm.vendored_grid_columns(width, display_mode, fixed, min_card, width_class)
                            got = hm.grid_columns_kt(CONSTS, "CLASSIC", width, display_mode, fixed, min_card, width_class)
                            self.assertEqual(expected, got, (min_card, width_class, display_mode, fixed, width))
                            count += 1
        self.assertGreater(count, 20000)

    def test_a_pinned_count_and_the_single_column_mode_stay_vendored_in_the_desktop_layout(self):
        for min_card in PRESETS:
            for width_class in hm.WIDTH_CLASSES:
                for width in range(FIRST, LAST + 1, 29):
                    for display_mode, fixed in ((0, 4), (0, 1), (1, 0), (1, 4), (2, 3)):
                        self.assertEqual(hm.vendored_grid_columns(width, display_mode, fixed, min_card, width_class),
                                         hm.grid_columns_kt(CONSTS, "DESKTOP", width, display_mode, fixed, min_card, width_class),
                                         (min_card, width_class, display_mode, fixed, width))
        self.assertEqual(4, hm.grid_columns_kt(CONSTS, "DESKTOP", 3000, 0, 4, None, "ExtraLarge"))
        self.assertEqual(2, hm.grid_columns_kt(CONSTS, "DESKTOP", 3000, 1, 4, None, "ExtraLarge"))

    def test_the_delegation_is_the_single_column_mode_or_a_stored_count(self):
        for display_mode in (0, 1, 2):
            for fixed in (-1, 0, 1, 3, 6):
                expected = display_mode == 1 or fixed > 0
                self.assertEqual(expected, hm.columns_delegated_kt(CONSTS, display_mode, fixed), (display_mode, fixed))
                # It is the decision that the column function of the desktop layout makes (desktopHomeDensityDelegation).
                self.assertEqual(expected, shell.home_delegation_kt(CONSTS, "DESKTOP", display_mode, fixed) is not None, (display_mode, fixed))
        self.assertEqual([False, True, True, True], [hm.columns_delegated_kt(CONSTS, 0, 0), hm.columns_delegated_kt(CONSTS, 1, 0),
                                                       hm.columns_delegated_kt(CONSTS, 0, 4), hm.columns_delegated_kt(CONSTS, 1, 4)])

    def test_a_delegated_desktop_feed_is_the_vendored_feed_in_width_and_cards(self):
        for mode in CONTRACT["feed"]["sweep"]["rail_modes"]:
            for width in range(FIRST, LAST + 1, 7):
                rail = shell.rail_presentation_kt(LIMITS, mode, width)
                free = hm.measured_width_kt(width, FOOTPRINT[rail], "DESKTOP")
                held = hm.measured_width_kt(width, FOOTPRINT[rail], "DESKTOP", True)
                # The room the rail leaves, held at the vendored cap; never wider than the undelegated desktop feed.
                self.assertEqual(min(free, hm.VENDORED_CAP_DP), held, (mode, width))
                self.assertLessEqual(held, hm.VENDORED_CAP_DP, (mode, width))
                self.assertLessEqual(held, free, (mode, width))
                # The layout flag still decides first: CLASSIC is the vendored cap on the window, delegated or not.
                self.assertEqual(hm.measured_width_kt(width, FOOTPRINT[rail], "CLASSIC"), hm.measured_width_kt(width, FOOTPRINT[rail], "CLASSIC", True))
        for style in STYLES:
            for columns in range(1, 11):
                for width in range(884, 3625, 61):
                    self.assertEqual((6, 6, 6), hm.card_layout_geometry_kt(CONSTS, "DESKTOP", style, columns, width, True), (style, columns, width))
                    self.assertEqual((6, 6, 6), hm.card_layout_geometry_kt(CONSTS, "CLASSIC", style, columns, width, True), (style, columns, width))
                    for width_class in hm.WIDTH_CLASSES:
                        vendored = hm.vendored_compact_metadata(style, columns, width_class)
                        self.assertEqual(vendored, hm.card_layout_compact_kt(CONSTS, "DESKTOP", style, columns, width_class, width, True),
                                         (style, columns, width_class, width))
        # Without the delegation the desktop geometry is unchanged: the default of the parameter is false.
        self.assertEqual(hm.card_geometry_kt(CONSTS, 1064, 2), hm.card_layout_geometry_kt(CONSTS, "DESKTOP", "BILIPAI", 2, 1064))

    def test_the_story_mode_and_a_pinned_count_draw_the_vendored_cards_the_review_computed(self):
        def vendored_feed(width, display_mode, fixed):
            self.assertTrue(hm.columns_delegated_kt(CONSTS, display_mode, fixed))
            rail = shell.rail_presentation_kt(LIMITS, "AUTO", width)
            wc = int(hm.measured_width_kt(width, FOOTPRINT[rail], "DESKTOP", True))
            columns = hm.grid_columns_kt(CONSTS, "DESKTOP", wc, display_mode, fixed, None, hm.width_class_of(width))
            card, grid, geometry = drawn("DESKTOP", "BILIPAI", wc, columns, True)
            self.assertEqual((6, 6, 6), geometry)
            # The vendored column function on the same content width gives the same columns.
            self.assertEqual(hm.vendored_grid_columns(wc, display_mode, fixed, None, hm.width_class_of(width)), columns)
            return wc, columns, card, grid

        # Story mode (display mode 1, two columns): the cap of 1280 dp bounds the feed, as before the desktop layout.
        self.assertEqual((1064, 2, 523.0, 1052), vendored_feed(1280, 1, 0))
        self.assertEqual((1280, 2, 631.0, 1268), vendored_feed(1920, 1, 0))
        self.assertEqual((1280, 2, 631.0, 1268), vendored_feed(2731, 1, 0))
        # A pinned count of 2 or 3 at W = 2731.
        self.assertEqual((1280, 2, 631.0, 1268), vendored_feed(2731, 0, 2))
        wc, columns, card, grid = vendored_feed(2731, 0, 3)
        self.assertEqual((1280, 3, 1268), (wc, columns, grid))
        self.assertAlmostEqual(418.667, card, places=3)
        # What the same inputs drew before the delegation reached the feed: cards of the density rule, 320 dp, with the rest empty on both sides.
        for width, empty in ((1280, 204), (1920, 524)):
            rail = shell.rail_presentation_kt(LIMITS, "AUTO", width)
            wc_free = int(hm.measured_width_kt(width, FOOTPRINT[rail], "DESKTOP"))
            old_card, old_grid, (old_outer, _item, _vertical) = drawn("DESKTOP", "BILIPAI", wc_free, 2)
            self.assertEqual(320.0, old_card)
            self.assertEqual(empty, old_outer)

    def test_the_desktop_columns_without_a_delegation_ignore_the_size_class(self):
        for width in range(FIRST, LAST + 1, 13):
            wc = layout_of("AUTO", width)[1]
            seen = {hm.grid_columns_kt(CONSTS, "DESKTOP", wc, 0, 0, None, width_class) for width_class in hm.WIDTH_CLASSES}
            self.assertEqual(1, len(seen), width)

    def test_the_feed_width_of_both_layouts(self):
        for width in range(300, 4201, 17):
            for footprint in (0, 72, 172, 216):
                self.assertEqual(min(width, 1280), hm.measured_width_kt(width, footprint, "CLASSIC"), (width, footprint))
                self.assertEqual(width - footprint if width > footprint else width, hm.measured_width_kt(width, footprint, "DESKTOP"), (width, footprint))
        self.assertEqual(2514, hm.measured_width_kt(2730, 216, "DESKTOP"))
        self.assertEqual(928.5, hm.measured_width_kt(1000.5, 72, "DESKTOP"))
        self.assertEqual(928, int(hm.measured_width_kt(1000.5, 72, "DESKTOP")))
        self.assertEqual(100, hm.measured_width_kt(100, 216, "DESKTOP"))
        self.assertEqual(1, hm.measured_width_kt(217, 216, "DESKTOP"))
        self.assertGreater(hm.measured_width_kt(3000, 216, "DESKTOP"), hm.VENDORED_CAP_DP)

    def test_a_card_width_is_the_room_of_the_columns_divided_by_them(self):
        card = lambda width, columns: hm.card_width_kt(CONSTS, width, columns)
        self.assertEqual(312.0, card(360, 1))
        # A card wider than the maximum is held at it by the side space; an odd rest is rounded up, so the card is a dp narrower.
        self.assertEqual([320.0, 319.0, 320.0, 320.0], [card(400, 1), card(401, 1), card(400, 0), card(400, -3)])
        self.assertAlmostEqual(294.25, card(2514, 8), places=9)
        self.assertEqual(320.0, card(2784, 8))
        maximum = CONSTS["DESKTOP_HOME_MAX_CARD_DP"]
        # For the rule's own columns it is the card of the rule when the grid is not held, and the maximum card less the rounding when it is.
        for width in range(FIRST, LAST + 1):
            wc = layout_of("AUTO", width)[1]
            density = shell.home_density_kt(CONSTS, wc)
            real = card(wc, density[0])
            if density[3]:
                self.assertLessEqual(real, maximum, width)
                self.assertGreaterEqual(real, maximum - 1 / density[0] - 1e-9, width)
            else:
                self.assertAlmostEqual(density[1], real, places=9, msg=width)

    def test_a_side_inset_is_the_side_padding_or_the_centring_rest_rounded_up(self):
        inset = lambda width, columns: hm.side_inset_kt(CONSTS, width, columns)
        self.assertEqual([24, 40, 41], [inset(360, 1), inset(400, 1), inset(401, 1)])
        self.assertEqual([inset(400, 1), inset(400, 1)], [inset(400, 0), inset(400, -3)])
        # The contract rows that the rule holds at the maximum card: centred exactly.
        self.assertEqual([56, 140, 28], [inset(2784, 8), inset(3624, 10), inset(1384, 4)])
        for width in range(FIRST, LAST + 1):
            wc = layout_of("AUTO", width)[1]
            for min_card in PRESETS:
                columns, _card, grid, capped = shell.home_density_kt(CONSTS, wc, min_card)
                # The rule's own side inset (the exact half of what the grid leaves over) rounded up.
                self.assertEqual(-((grid - wc) // 2), inset(wc, columns), (width, min_card))
                self.assertEqual(capped, inset(wc, columns) > CONSTS["DESKTOP_HOME_SIDE_PADDING_DP"], (width, min_card))
                self.assertEqual(math.ceil(shell.home_side_inset_kt(wc, grid)), inset(wc, columns), (width, min_card))

    def test_the_card_layout_geometry_is_the_vendored_six_for_classic_and_the_rule_for_desktop(self):
        for style in STYLES:
            for columns in range(1, 11):
                for width in range(884, 3625, 61):
                    self.assertEqual((6, 6, 6), hm.card_layout_geometry_kt(CONSTS, "CLASSIC", style, columns, width), (style, columns, width))
                    outer, item, vertical = hm.card_layout_geometry_kt(CONSTS, "DESKTOP", style, columns, width)
                    self.assertEqual((16, 16), (item, vertical), (style, columns, width))
                    self.assertEqual(hm.side_inset_kt(CONSTS, width, columns), outer, (style, columns, width))
                    self.assertGreaterEqual(outer, 24, (style, columns, width))
        with self.assertRaises(ValueError):
            hm.card_layout_geometry_kt(CONSTS, "CLASSIC", "OTHER", 2, 1000)

    def test_the_compact_metadata_rule_against_an_independent_oracle(self):
        for style in STYLES:
            for width_class in hm.WIDTH_CLASSES:
                for columns in range(1, 11):
                    original = hm.vendored_compact_metadata(style, columns, width_class)
                    for width in range(884, 3625, 61):
                        # Oracle: compact stays only where the vendored layout is compact and the card the feed draws is narrower than 200.
                        # The card is drawn from the side space: 24, or the rest rounded up when the grid is held at the maximum card.
                        held = width - 2 * 24 - (columns - 1) * 16 > columns * 320
                        side = -((columns * 320 + (columns - 1) * 16 - width) // 2) if held else 24
                        expected = original and (width - 2 * side - (columns - 1) * 16) / columns < 200
                        got = hm.card_layout_compact_kt(CONSTS, "DESKTOP", style, columns, width_class, width)
                        self.assertEqual(expected, got, (style, width_class, columns, width))
                        self.assertEqual(original, hm.card_layout_compact_kt(CONSTS, "CLASSIC", style, columns, width_class, width))
        # Compact metadata only ever turns off.
        self.assertFalse(hm.card_compact_kt(CONSTS, False, 900, 1))

    def test_the_default_grid_never_keeps_compact_metadata_on_the_desktop_feed(self):
        for mode in CONTRACT["feed"]["sweep"]["rail_modes"]:
            for width in range(FIRST, LAST + 1):
                wc = layout_of(mode, width)[1]
                columns = hm.grid_columns_kt(CONSTS, "DESKTOP", wc, 0, 0, None, "ExtraLarge")
                for style in STYLES:
                    self.assertFalse(hm.card_layout_compact_kt(CONSTS, "DESKTOP", style, columns, "ExtraLarge", wc), (mode, width, style))
        # A pinned eight columns on a narrow feed keeps it: the card is under 200 dp.
        self.assertTrue(hm.card_layout_compact_kt(CONSTS, "DESKTOP", "BILIPAI", 8, "Large", 1384))


class JvmTestFileTest(unittest.TestCase):
    TEXT = source.read(JVM_TEST) if JVM_TEST.is_file() else ""

    def test_the_jvm_class_exists_with_its_tests(self):
        self.assertTrue(JVM_TEST.is_file())
        self.assertEqual(JVM_TEST_COUNT, len(re.findall(r"(?m)^    @Test fun \w+\(\) \{$", self.TEXT)))
        self.assertEqual(JVM_TEST_COUNT, self.TEXT.count("@Test"))
        self.assertIn("\nclass DesktopHomeFeedLayoutTest {\n", self.TEXT)
        self.assertTrue(self.TEXT.startswith("package com.bilipai.desktop.ui\n"))

    def test_the_jvm_class_needs_no_window_gpu_or_network(self):
        code = source.mask_kotlin(self.TEXT)
        for forbidden in ("ImageComposeScene", "SwingPanel", "application {", "Window(", "java.net", "HttpClient", "java.awt", "ComposeContent",
                          "runComposeUiTest", "Thread.sleep", "System.getenv"):
            self.assertNotIn(forbidden, code, forbidden)
        self.assertIn("DesktopUxContract.section(\"feed\")", self.TEXT)
        self.assertEqual([], source.cjk_positions(self.TEXT))

    def test_the_jvm_class_reads_the_contract_and_the_vendored_functions(self):
        for name in ("resolveHomeFeedGridColumns", "resolveHomeFeedCardLayout", "resolveHomeFeedMaxContentWidth", "desktopResolveHomeFeedGridColumns",
                     "desktopHomeFeedMeasuredWidth", "desktopHomeFeedDesktopWidthDp", "desktopHomeDensityCardWidthDp", "desktopHomeDensityCardLayout",
                     "desktopHomeDensitySideInsetDp", "desktopSelectHomeFeedCardLayout", "desktopHomeFeedColumnsDelegated"):
            self.assertIn(name + "(", self.TEXT, name)

    def test_the_column_wrapper_is_called_with_the_layout_first(self):
        code = source.mask_kotlin(self.TEXT)
        calls = list(re.finditer(r"\bdesktopResolveHomeFeedGridColumns\(", code))
        self.assertGreaterEqual(len(calls), 8)
        for match in calls:
            close = code.index("\n", match.end())
            arguments = code[match.end():close]
            first = arguments.split(",", 1)[0].strip()
            self.assertIn(first, ("desktop", "classic"), arguments)

    def test_the_jvm_class_imports_what_it_uses(self):
        imports = re.findall(r"(?m)^import (\S+)$", self.TEXT)
        self.assertEqual(len(imports), len(set(imports)))
        code = source.mask_kotlin(self.TEXT)
        body = code[code.index("\n", code.rindex("import ")):]
        for imported in imports:
            simple = imported.rsplit(".", 1)[1]
            self.assertGreaterEqual(len(re.findall(r"\b%s\b" % simple, body)), 1, imported)

    def test_the_file_is_lf_text(self):
        source.assert_lf_only(self, JVM_TEST, hm.LAYOUT_KT, hm.POLICY_KT, hm.HERE / "ux_home_layout_model.py", Path(__file__))


if __name__ == "__main__":
    unittest.main()
