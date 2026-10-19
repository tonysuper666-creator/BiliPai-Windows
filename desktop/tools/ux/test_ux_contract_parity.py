"""Contract parity of the desktop UX program (slice X0). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_contract_parity.py

What is proved. The contract JSON is canonical, equals its Kotlin test copy byte for byte, and every derived value in
it (display dp, resolved widths, every golden expectation) is reproduced by the reference model from the parameters
in the same file. The spec tables 3.6 (Home feed) and 4.3 (player layout) are recomputed from the rules and compared
with the spec text. The model satisfies the spec's properties over sweeps. The Kotlin flag store agrees with the
contract (enum values, defaults, keys, ranges). This file proves nothing about Kotlin compilation or behaviour.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in
desktop/tools/tests as spec 8.2 and 9.2 say; Root adds each suite to the validation plan as an explicit
`-s desktop/tools/ux -p <file>` line. Slice X0 is also over the "about 600 changed lines" cap (spec 9.1): the window
bounds commands and the window key constants belong to slice W3 and could move there; they stay in X0 because the
contract, the model and their tests already carry them.
"""
import copy
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_model as model  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()

# Spec 3.6 golden table (AUTO rail), verbatim: W, rail, Wc, columns, card (320 = capped), grid.
SPEC_FEED_TABLE = (
    (960, "collapsed", 888, 3, 269.3, 840),
    (1088, "collapsed", 1016, 4, 230.0, 968),
    (1279, "collapsed", 1207, 5, 219.0, 1159),
    (1280, "expanded", 1064, 4, 242.0, 1016),
    (1600, "expanded", 1384, 5, 254.4, 1336),
    (1920, "expanded", 1704, 7, 222.9, 1656),
    (2048, "expanded", 1832, 7, 241.1, 1784),
    (2560, "expanded", 2344, 8, 273.0, 2296),
    (2730, "expanded", 2514, 8, 294.2, 2466),
    (3000, "expanded", 2784, 8, 320, 2672),
    (3413, "expanded", 3197, 10, 300.5, 3149),
    (3840, "expanded", 3624, 10, 320, 3344),
)
# Spec 4.3 corrected table, verbatim: W, H, mode, panel state, panel, video w, video h, tier, bars axis, bars.
SPEC_PLAYER_TABLE = (
    (960, 680, "windowed", "AUTO", 0, 960, 540, "L", "top_bottom", 70),
    (960, 680, "windowed", "OPEN", 340, 620, 349, "S", "top_bottom", 166),
    (1088, 720, "windowed", "AUTO", 0, 1088, 612, "L", "top_bottom", 54),
    (1088, 720, "windowed", "OPEN", 340, 748, 421, "M", "top_bottom", 150),
    (1280, 690, "windowed", "AUTO", 340, 940, 529, "M", "top_bottom", 81),
    (2048, 1101, "windowed", "AUTO", 451, 1597, 899, "L", "top_bottom", 101),
    (2048, 1101, "windowed", "CLOSED", 0, 1957, 1101, "L", "left_right", 45),
    (2048, 1152, "fullscreen", "AUTO", 0, 2048, 1152, "L", "none", 0),
    (2731, 1101, "windowed", "AUTO", 560, 1957, 1101, "L", "left_right", 107),
    (2731, 1152, "fullscreen", "OPEN", 560, 2048, 1152, "L", "left_right", 62),
    (2731, 1152, "fullscreen", "CLOSED", 0, 2048, 1152, "L", "left_right", 342),
    (3413, 1440, "windowed", "AUTO", 560, 2560, 1440, "L", "left_right", 146),
)
SPEC_PLAYER_IDS = (
    "min-default", "min-panel-open", "w1088-default", "w1088-panel-open", "w1280-default", "secondary-default",
    "secondary-cinema", "secondary-fullscreen", "primary-default", "primary-fullscreen-panel-open",
    "primary-fullscreen-panel-closed", "primary-100-default",
)
# Rows where the recomputation disagrees with the spec text. Empty: all 24 rows reproduce. The one row that needs a
# remark (feed W 2730, card 294.25 exactly) is a tie that the spec rounded half to even, as the model does.
KNOWN_DISAGREEMENTS = ()

BOTTOM_BAR = source.REPO / "app/src/main/java/com/android/purebilibili/feature/home/components/BottomBar.kt"
MODEL_KT = source.K / "appearance/DesktopWindowsUxModel.kt"
CONTROLLER_KT = source.K / "appearance/DesktopWindowsUx.kt"
STRINGS_KT = source.K / "appearance/DesktopWindowsUxStrings.kt"


def feed_golden_by_width():
    return {row["width_dp"]: row for row in CONTRACT["feed"]["golden"] if row["id"].startswith("spec-")}


class ContractFileTest(unittest.TestCase):
    def test_contract_is_canonical_and_in_sync_with_its_derived_values_and_the_kotlin_copy(self):
        self.assertEqual([], model.problems())

    def test_kotlin_test_copy_is_byte_identical(self):
        self.assertEqual(model.CONTRACT_PATH.read_bytes(), model.KOTLIN_COPY_PATH.read_bytes())

    def test_no_byte_order_mark_and_only_lf(self):
        raw = model.CONTRACT_PATH.read_bytes()
        self.assertFalse(raw.startswith(b"\xef\xbb\xbf"))
        self.assertNotIn(b"\r", raw)
        self.assertTrue(raw.endswith(b"}\n"))

    def test_schema_and_sections(self):
        self.assertEqual(1, CONTRACT["schema"])
        for section in ("displays", "tokens", "flags", "rail", "feed", "player", "chrome", "pointer", "shortcuts", "flyout"):
            self.assertIn(section, CONTRACT)

    def test_x0_files_are_lf_only(self):
        here = Path(__file__).resolve().parent
        files = [model.CONTRACT_PATH, model.KOTLIN_COPY_PATH, here / "ux_model.py", here / "ux_source.py",
                 here / "test_ux_contract_parity.py", here / "test_ux_source_hygiene.py", source.ALLOWLIST,
                 MODEL_KT, CONTROLLER_KT, STRINGS_KT, source.K / "DesktopShell.kt",
                 source.KT / "ux/DesktopUxContract.kt", source.KT / "ux/DesktopUxContractTest.kt",
                 source.KT / "appearance/DesktopWindowsUxModelTest.kt",
                 source.KT / "appearance/DesktopWindowsUxControllerTest.kt"]
        source.assert_lf_only(self, *files)

    def test_canonical_dump_is_idempotent_and_ordered(self):
        text = model.canonical_text(CONTRACT)
        self.assertEqual(text, model.canonical_text(model.load()))
        self.assertEqual(list(CONTRACT), list(model.load()))


class DisplaysTest(unittest.TestCase):
    def test_primary_is_the_measured_5k2k_panel(self):
        primary = CONTRACT["displays"]["primary"]
        self.assertEqual([5120, 2160], primary["px"])
        self.assertEqual(150, primary["windows_scale_percent"])
        self.assertEqual(125, primary["app_zoom_percent"])
        self.assertEqual("measured", primary["status"])
        self.assertEqual(165, primary["refresh_hz"])
        self.assertEqual(39, primary["diagonal_inch"])

    def test_secondary_is_the_assumed_4k_panel(self):
        secondary = CONTRACT["displays"]["secondary"]
        self.assertEqual([3840, 2160], secondary["px"])
        self.assertEqual(150, secondary["windows_scale_percent"])
        self.assertEqual("assumed", secondary["status"])

    def test_derived_dp_rows_come_from_the_parameters(self):
        self.assertEqual({"width_dp": 2731, "screen_height_dp": 1152, "maximised_height_dp": 1101},
                         CONTRACT["displays"]["primary"]["derived"])
        self.assertEqual({"width_dp": 2048, "screen_height_dp": 1152, "maximised_height_dp": 1101},
                         CONTRACT["displays"]["secondary"]["derived"])
        # Root decision D2: a maximised window is about 2730 dp wide (5120 / 1.875 = 2730.67).
        self.assertLessEqual(abs(CONTRACT["displays"]["primary"]["derived"]["width_dp"] - 2730), 1)

    def test_extent_arithmetic_for_other_zooms(self):
        self.assertEqual((2560, 1440), model.extent_dp(CONTRACT, "secondary", 100, "screen"))
        self.assertEqual((3413, 1440), model.extent_dp(CONTRACT, "primary", 100, "screen"))
        self.assertEqual((4551, 1920), model.extent_dp(CONTRACT, "primary", 75, "screen"))
        self.assertEqual((1707, 720), model.extent_dp(CONTRACT, "primary", 200, "screen"))
        with self.assertRaises(ValueError):
            model.extent_dp(CONTRACT, "primary", 125, "bogus")

    def test_derived_values_say_which_inputs_are_assumed(self):
        displays = CONTRACT["displays"]
        self.assertEqual("assumed", displays["common"]["maximised_chrome_status"])
        # The width and the screen height of the measured panel are measured; the maximised height also rests on the
        # assumed chrome. The whole assumed panel is assumed.
        self.assertEqual({"width_dp": "measured", "screen_height_dp": "measured", "maximised_height_dp": "assumed"},
                         displays["primary"]["derived_status"])
        self.assertEqual({"width_dp": "assumed", "screen_height_dp": "assumed", "maximised_height_dp": "assumed"},
                         displays["secondary"]["derived_status"])

    def test_rows_that_rest_on_the_assumed_chrome_are_marked(self):
        displays = CONTRACT["displays"]
        for section, uses_height in (("feed", False), ("player", True)):
            for row in CONTRACT[section]["golden"]:
                if "derive" not in row:
                    self.assertNotIn("derive_status", row, row["id"])
                    continue
                derive = row["derive"]
                maximised = derive.get("extent", "maximised") == "maximised"
                measured = displays[derive["display"]]["status"] == "measured" and not (uses_height and maximised)
                self.assertEqual("measured" if measured else "assumed", row["derive_status"], row["id"])
        player = {row["id"]: row for row in CONTRACT["player"]["golden"]}
        feed = {row["id"]: row for row in CONTRACT["feed"]["golden"]}
        self.assertEqual("assumed", player["primary-default"]["derive_status"])  # 1101 dp high: assumed chrome
        self.assertEqual("measured", player["primary-fullscreen-panel-open"]["derive_status"])  # the whole screen
        self.assertEqual("measured", feed["primary-125"]["derive_status"])  # a width only
        self.assertEqual("assumed", player["secondary-default"]["derive_status"])  # the panel itself is assumed

    def test_the_status_helper_downgrades_only_what_rests_on_the_chrome(self):
        self.assertEqual("measured", model.value_status(CONTRACT, "primary", False))
        self.assertEqual("assumed", model.value_status(CONTRACT, "primary", True))
        self.assertEqual("assumed", model.value_status(CONTRACT, "secondary", False))
        measured = copy.deepcopy(CONTRACT)
        measured["displays"]["common"]["maximised_chrome_status"] = "measured"
        self.assertEqual("measured", model.value_status(measured, "primary", True))
        self.assertEqual("assumed", model.value_status(measured, "secondary", True))
        # A stale status is found: the regenerated copy differs from the edited one.
        edited = copy.deepcopy(CONTRACT)
        edited["displays"]["primary"]["derived_status"]["maximised_height_dp"] = "measured"
        self.assertNotEqual(edited, model.regenerate(edited))

    def test_every_derived_golden_input_equals_its_resolution(self):
        for row in CONTRACT["feed"]["golden"]:
            if "derive" in row:
                self.assertEqual(model.resolve_derive(CONTRACT, row["derive"])[0], row["width_dp"], row["id"])
        for row in CONTRACT["player"]["golden"]:
            if "derive" in row:
                self.assertEqual(model.resolve_derive(CONTRACT, row["derive"]), (row["w"], row["h"]), row["id"])


class SpecTableTest(unittest.TestCase):
    def test_feed_table_recomputed_from_the_rules_matches_the_spec_text(self):
        by_width = feed_golden_by_width()
        disagreements = []
        for width, rail, wc, cols, card, grid in SPEC_FEED_TABLE:
            row = by_width[width]
            expect = row["expect"]
            computed = (expect["rail"], expect["wc"], expect["cols"], expect["card"], expect["grid"])
            if computed != (rail, wc, cols, card, grid):
                disagreements.append(("feed", width, computed, (rail, wc, cols, card, grid)))
        self.assertEqual(list(KNOWN_DISAGREEMENTS), disagreements)

    def test_player_table_recomputed_from_the_rules_matches_the_spec_text(self):
        rows = {row["id"]: row for row in CONTRACT["player"]["golden"]}
        disagreements = []
        for ident, spec in zip(SPEC_PLAYER_IDS, SPEC_PLAYER_TABLE):
            row = rows[ident]
            expect = row["expect"]
            computed = (row["w"], row["h"], row.get("mode", "windowed"), row.get("panel", "AUTO"), expect["panel"],
                        expect["video"][0], expect["video"][1], expect["tier"], expect["bars"]["axis"], expect["bars"]["size"])
            if computed != spec:
                disagreements.append(("player", ident, computed, spec))
        self.assertEqual(list(KNOWN_DISAGREEMENTS), disagreements)

    def test_capped_rows_are_exactly_the_320_rows(self):
        for width, _rail, _wc, _cols, card, _grid in SPEC_FEED_TABLE:
            self.assertEqual(card == 320, feed_golden_by_width()[width]["expect"]["capped"], width)

    def test_the_2730_tie_is_rounded_half_to_even_like_the_spec(self):
        layout = model.feed_row_layout(CONTRACT, feed_golden_by_width()[2730])
        self.assertEqual(model.Fraction(1177, 4), layout["card"])  # 294.25 exactly
        self.assertEqual(294.2, model.round_1dp(layout["card"]))

    def test_feed_and_player_rows_cover_the_edges(self):
        ids = {row["id"] for row in CONTRACT["player"]["golden"]}
        self.assertTrue({"tier-m-959", "tier-s-639", "tier-m-640", "default-closed-1279", "panel-user-min",
                         "panel-user-max", "panel-not-docked-narrow", "fullscreen-ratio-2",
                         "fullscreen-ratio-below-2", "entry-narrow-then-wide"} <= ids)


class RailModelTest(unittest.TestCase):
    def test_footprints_and_panel_geometry(self):
        rail = CONTRACT["rail"]
        self.assertEqual({"expanded": 216, "collapsed": 72, "classic": 172}, rail["footprint"])
        self.assertEqual(rail["footprint"]["expanded"], rail["panel_expanded"] + 2 * rail["margin"])
        # The collapsed panel is a parameter of its own (spec 3.1 gives the 72 dp footprint; the panel holds the 40 dp
        # items), so the footprint is checked against it instead of deriving it from the footprint.
        self.assertEqual(56, rail["panel_collapsed"])
        self.assertEqual(rail["footprint"]["collapsed"], rail["panel_collapsed"] + 2 * rail["margin"])
        self.assertGreaterEqual(rail["panel_collapsed"], rail["metrics"]["item"])

    def test_content_width_floor_is_exactly_the_expanded_switch_point(self):
        floor = CONTRACT["feed"]["rules"]["min_content_width"]
        self.assertEqual(floor, CONTRACT["rail"]["thresholds"]["EXPANDED"]["expand_at"] - CONTRACT["rail"]["footprint"]["expanded"])
        self.assertGreaterEqual(CONTRACT["tokens"]["min_window_dp"][0] - CONTRACT["rail"]["footprint"]["collapsed"], floor)
        self.assertEqual(floor, CONTRACT["rail"]["min_content_width_expanded"])

    def test_auto_hysteresis(self):
        pres = lambda width, previous=None: model.rail_presentation(CONTRACT, "AUTO", width, previous)
        self.assertEqual("collapsed", pres(1279))
        self.assertEqual("expanded", pres(1280))
        self.assertEqual("expanded", pres(1264, "expanded"))
        self.assertEqual("collapsed", pres(1263, "expanded"))
        self.assertEqual("collapsed", pres(1279, "collapsed"))
        self.assertEqual("expanded", pres(1280, "collapsed"))

    def test_expanded_mode_hysteresis(self):
        pres = lambda width, previous=None: model.rail_presentation(CONTRACT, "EXPANDED", width, previous)
        self.assertEqual("collapsed", pres(1099))
        self.assertEqual("expanded", pres(1100))
        self.assertEqual("expanded", pres(1084, "expanded"))
        self.assertEqual("collapsed", pres(1083, "expanded"))
        self.assertEqual("collapsed", pres(1115, "collapsed"))
        self.assertEqual("expanded", pres(1116, "collapsed"))

    def test_fixed_modes(self):
        for width in (960, 1500, 4000):
            self.assertEqual("collapsed", model.rail_presentation(CONTRACT, "COLLAPSED", width))
            self.assertEqual("classic", model.rail_presentation(CONTRACT, "CLASSIC_TEXT", width))

    def test_groups_cover_every_non_story_bottom_nav_item(self):
        text = source.read(BOTTOM_BAR)
        body = text[text.index("enum class BottomNavItem("):]
        body = body[:body.index("\n}\n")]
        enum_names = set(re.findall(r"(?m)^    ([A-Z][A-Z_]*)\($", body))
        self.assertIn("STORY", enum_names)
        groups = CONTRACT["rail"]["groups"]
        grouped = [item for name in ("browse", "library", "footer") for item in groups[name]]
        self.assertEqual(len(grouped), len(set(grouped)), "an item sits in two groups")
        not_enum = {"DOWNLOADS", "ACCOUNT_SWITCH"}
        self.assertEqual(enum_names - set(groups["excluded"]), set(grouped) - not_enum)
        self.assertEqual({"STORY"}, set(groups["excluded"]))

    def test_fixed_destinations_are_nine_known_items_in_group_order(self):
        fixed = CONTRACT["rail"]["fixed_destinations"]
        self.assertEqual(9, len(fixed))
        groups = CONTRACT["rail"]["groups"]
        grouped = groups["browse"] + groups["library"] + groups["footer"]
        self.assertTrue(set(fixed) <= set(grouped))
        self.assertNotIn("ACCOUNT_SWITCH", fixed)
        self.assertEqual(fixed, [item for item in grouped if item in fixed])


class FeedModelTest(unittest.TestCase):
    def test_sweep_properties_over_window_widths(self):
        rules = CONTRACT["feed"]["rules"]
        sweep = CONTRACT["feed"]["sweep"]
        switch_points = {"AUTO": 1280, "EXPANDED": 1100}
        for mode in sweep["rail_modes"]:
            previous = None
            drops = []
            for width in range(sweep["from"], sweep["to"] + 1):
                layout = model.feed_layout(CONTRACT, width, mode)
                self.assertGreaterEqual(layout["wc"], rules["min_content_width"], (mode, width))
                self.assertGreaterEqual(layout["card"], rules["min_card"], (mode, width))
                self.assertLessEqual(layout["card"], rules["max_card"], (mode, width))
                self.assertGreaterEqual(layout["columns"], rules["min_columns"])
                limit = rules["max_columns_wide"] if layout["wc"] >= rules["wide_from_content_width"] else rules["max_columns_below_wide"]
                self.assertLessEqual(layout["columns"], limit)
                usable = layout["wc"] - 2 * rules["side_padding"]
                self.assertLessEqual(layout["grid"], usable)
                self.assertEqual(layout["grid"], layout["columns"] * layout["card"] + (layout["columns"] - 1) * rules["gutter"])
                if previous is not None and layout["columns"] < previous["columns"]:
                    drops.append((width, previous["rail"], layout["rail"]))
                previous = layout
            for width, before, after in drops:
                self.assertNotEqual(before, after, (mode, width))
                self.assertEqual(switch_points[mode], width)
            self.assertEqual(1 if mode in switch_points else 0, len(drops), (mode, drops))

    def test_columns_are_non_decreasing_inside_one_rail_presentation(self):
        for mode in ("AUTO", "EXPANDED"):
            last = {}
            for width in range(960, 4201):
                layout = model.feed_layout(CONTRACT, width, mode)
                rail = layout["rail"]
                self.assertGreaterEqual(layout["columns"], last.get(rail, 0), (mode, width))
                last[rail] = layout["columns"]

    def test_user_card_preset_is_honoured_with_a_floor(self):
        self.assertGreaterEqual(model.feed_layout(CONTRACT, 1600)["columns"],
                                model.feed_layout(CONTRACT, 1600, min_card=320)["columns"])
        self.assertEqual(model.feed_layout(CONTRACT, 1600, min_card=100)["columns"],
                         model.feed_layout(CONTRACT, 1600, min_card=160)["columns"])
        for preset in CONTRACT["feed"]["rules"]["card_presets"]:
            layout = model.feed_layout(CONTRACT, 2400, min_card=preset)
            self.assertLessEqual(layout["card"], 320)
            self.assertGreaterEqual(layout["card"], preset)
            self.assertGreaterEqual(layout["columns"], 2)

    def test_the_old_cap_is_dropped_and_matches_the_vendored_constant(self):
        text = source.read(source.REPO / "app/src/main/java/com/android/purebilibili/feature/home/HomeFeedGridPolicy.kt")
        self.assertIn("resolveHomeFeedMaxContentWidth(): Dp = %d.dp" % CONTRACT["feed"]["rules"]["dropped_legacy_cap"], text)
        self.assertGreater(model.feed_layout(CONTRACT, 3000)["grid"], CONTRACT["feed"]["rules"]["dropped_legacy_cap"])


class PlayerModelTest(unittest.TestCase):
    def test_sweep_invariants(self):
        rules = CONTRACT["player"]["rules"]
        ratio = model.Fraction(*rules["aspect"])
        for width in range(640, 4201, 7):
            for height in (500, 680, 900, 1101, 1152, 1440):
                for mode in ("windowed", "fullscreen"):
                    for panel in ("AUTO", "OPEN", "CLOSED"):
                        layout = model.player_layout(CONTRACT, width, height, mode, panel)
                        label = (width, height, mode, panel)
                        stage = width - layout["panel"]
                        if layout["docked"]:
                            self.assertGreaterEqual(layout["panel"], rules["panel_min"], label)
                            self.assertLessEqual(layout["panel"], rules["panel_max"], label)
                            self.assertLessEqual(layout["panel"], width - rules["min_stage"], label)
                            if mode == "fullscreen":
                                self.assertGreaterEqual(model.Fraction(width, height), 2, label)
                            else:
                                self.assertGreaterEqual(width, rules["dock_min_window_width"], label)
                        else:
                            self.assertEqual(0, layout["panel"], label)
                        self.assertEqual(layout["video_h"] * ratio, layout["video_w"], label)
                        self.assertLessEqual(layout["video_w"], stage, label)
                        self.assertLessEqual(layout["video_h"], height, label)
                        self.assertTrue(layout["video_w"] == stage or layout["video_h"] == height, label)
                        expected_tier = "L" if layout["video_w"] >= 960 else "M" if layout["video_w"] >= 640 else "S"
                        self.assertEqual(expected_tier, layout["tier"], label)
                        self.assertEqual(min(1280, layout["video_w"] - 32), layout["dock"], label)

    def test_auto_panel_is_decided_once_at_entry(self):
        for width in (700, 1000, 1279, 1280, 1600, 2400):
            narrow = model.player_layout(CONTRACT, width, 900, panel="AUTO", entry_width=1200)
            wide = model.player_layout(CONTRACT, width, 900, panel="AUTO", entry_width=1300)
            self.assertFalse(narrow["docked"], width)
            self.assertEqual(width >= 800, wide["docked"], width)

    def test_fullscreen_docks_only_from_aspect_two(self):
        self.assertFalse(model.player_layout(CONTRACT, 2303, 1152, "fullscreen", "OPEN")["docked"])
        self.assertTrue(model.player_layout(CONTRACT, 2304, 1152, "fullscreen", "OPEN")["docked"])

    def test_user_panel_width_is_clamped_and_never_leaves_the_stage_too_narrow(self):
        for user, expected in ((0, 320), (319, 320), (320, 320), (400, 400), (560, 560), (561, 560), (5000, 560)):
            layout = model.player_layout(CONTRACT, 1920, 900, panel="OPEN", user_panel_width=user)
            self.assertEqual(expected, layout["panel"], user)
        narrow = model.player_layout(CONTRACT, 760, 600, panel="OPEN", user_panel_width=400)
        self.assertFalse(narrow["docked"])
        self.assertEqual(0, narrow["panel"])

    def test_tier_edges(self):
        tiers = {w: model.player_layout(CONTRACT, w, 2000)["tier"] for w in (639, 640, 959, 960)}
        self.assertEqual({639: "S", 640: "M", 959: "M", 960: "L"}, tiers)

    def test_height_binds_on_ultrawide_and_extra_width_goes_to_the_panel_first(self):
        wide = model.player_layout(CONTRACT, 3413, 1440, panel="OPEN")
        self.assertEqual(1440, wide["video_h"])
        self.assertEqual(560, wide["panel"])
        self.assertEqual("left_right", wide["axis"])


class ShortcutTableTest(unittest.TestCase):
    def setUp(self):
        self.table = CONTRACT["shortcuts"]
        self.rows = self.table["rows"]

    def test_chords_parse_and_have_a_scope_and_an_action(self):
        for row in self.rows:
            model.parse_chord(row["chord"])
            self.assertIn(row["scope"], ("shell", "player"), row)
            self.assertRegex(row["action"], r"^[A-Z][A-Z_]*$")

    def test_chord_parser_rejects_bad_forms(self):
        for bad in ("Ctrl+", "Shift+Ctrl+K", "Ctrl+Ctrl+K", "Hyper+K", ""):
            with self.assertRaises(ValueError, msg=bad):
                model.parse_chord(bad)
        self.assertEqual((("Ctrl", "Shift"), "Left"), model.parse_chord("Ctrl+Shift+Left"))

    def test_no_duplicate_chord_inside_a_scope(self):
        seen = set()
        for row in self.rows:
            ident = (row["scope"], row["chord"])
            self.assertNotIn(ident, seen, row)
            seen.add(ident)

    def test_no_bare_printable_key_on_shell_pages_and_no_ctrl_alt(self):
        for row in self.rows:
            mods, key = model.parse_chord(row["chord"])
            self.assertFalse("Ctrl" in mods and "Alt" in mods, row)
            if row["scope"] == "shell":
                printable = len(key) == 1 or key in ("Comma", "Period", "Slash", "LeftBracket", "RightBracket", "Space")
                self.assertFalse(printable and not mods, row)

    def test_no_chord_collides_with_the_zoom_decoder_or_the_reserved_set(self):
        zoom_keys = set(self.table["zoom_decoder_keys"])
        reserved = set(self.table["reserved"])
        for row in self.rows:
            mods, key = model.parse_chord(row["chord"])
            self.assertFalse("Ctrl" in mods and "Alt" not in mods and key in zoom_keys, row)
            self.assertNotIn(row["chord"], reserved, row)

    def test_ctrl_digits_are_one_to_nine_and_follow_the_fixed_destinations(self):
        digits = [row for row in self.rows if row["scope"] == "shell" and row["chord"].startswith("Ctrl+") and row["chord"][5:].isdigit()]
        self.assertEqual(["Ctrl+%d" % n for n in range(1, 10)], [row["chord"] for row in digits])
        self.assertEqual(CONTRACT["rail"]["fixed_destinations"], [row["arg"] for row in digits])
        self.assertTrue(all(row["action"] == "GO_DESTINATION" for row in digits))

    def test_a_chord_in_both_scopes_has_one_action_unless_listed_as_dual(self):
        by_scope = {}
        for row in self.rows:
            by_scope.setdefault(row["chord"], {})[row["scope"]] = row["action"]
        for chord, actions in by_scope.items():
            if len(actions) == 2 and len(set(actions.values())) == 2:
                self.assertIn(chord, self.table["dual_meaning"], chord)
        self.assertIn("Ctrl+B", by_scope)

    def test_enter_and_backspace_are_not_taken(self):
        chords = {row["chord"] for row in self.rows}
        for chord in self.table["not_taken"]:
            self.assertNotIn(chord, chords)

    def test_zoom_keys_match_the_decoder_in_source(self):
        text = source.read(source.K / "appearance/DesktopWindowsDisplayScaleInput.kt")
        decoded = set(re.findall(r"VK_(\w+)", text[text.index("fun desktopWindowsScaleKey"):text.index("class DesktopWindowsScaleWheelAccumulator")]))
        names = {"PLUS": "Plus", "EQUALS": "Equals", "ADD": "Add", "MINUS": "Minus", "SUBTRACT": "Subtract", "0": "0", "NUMPAD0": "Numpad0"}
        self.assertEqual({names[code] for code in decoded}, set(self.table["zoom_decoder_keys"]))


class FlagsParityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(MODEL_KT)

    def block(self, name):
        begin, end = "// %s-BEGIN" % name, "// %s-END" % name
        self.assertEqual(1, self.text.count(begin))
        self.assertEqual(1, self.text.count(end))
        return self.text[self.text.index(begin) + len(begin):self.text.index(end)]

    def test_namespace_and_numeric_limits(self):
        flags = CONTRACT["flags"]
        self.assertIn('const val WINDOWS_UX_NAMESPACE = "%s"' % flags["namespace"], self.text)
        width = flags["details_panel_width"]
        self.assertIn("WINDOWS_UX_DETAILS_PANEL_MIN_WIDTH_DP = %d" % width["min"], self.text)
        self.assertIn("WINDOWS_UX_DETAILS_PANEL_MAX_WIDTH_DP = %d" % width["max"], self.text)
        window = flags["window"]
        self.assertIn("WINDOWS_UX_WINDOW_MIN_EXTENT = %d" % window["min_extent"], self.text)
        self.assertIn("WINDOWS_UX_WINDOW_MAX_EXTENT = %d" % window["max_extent"], self.text)
        self.assertIn("WINDOWS_UX_WINDOW_MAX_ORIGIN = %d" % window["max_abs_origin"], self.text)

    def test_storage_keys(self):
        flags = CONTRACT["flags"]
        kotlin_keys = re.findall(r'const val (UX_KEY_\w+) = "(\w+)"', self.block("UX-FLAG-KEYS"))
        expected = [field["key"] for field in flags["fields"]] + [flags["details_panel_width"]["key"]] + flags["window"]["keys"]
        self.assertEqual(sorted(expected), sorted(value for _name, value in kotlin_keys))
        self.assertEqual(len(kotlin_keys), len({value for _name, value in kotlin_keys}))

    def test_reset_keys_are_the_layout_keys_in_order(self):
        constants = dict(re.findall(r'const val (UX_KEY_\w+) = "(\w+)"', self.block("UX-FLAG-KEYS")))
        match = re.search(r"UX_LAYOUT_KEYS: List<String> = listOf\(([^)]*)\)", self.text)
        names = re.findall(r"UX_KEY_\w+", match.group(1))
        self.assertEqual(CONTRACT["flags"]["reset_keys"], [constants[name] for name in names])

    def test_enum_values(self):
        enums = {name: [value.strip() for value in body.split(",")]
                 for name, body in re.findall(r"internal enum class (\w+) \{ ([^}]*) \}", self.block("UX-FLAG-ENUMS"))}
        self.assertEqual(len(CONTRACT["flags"]["fields"]), len(enums))
        for field in CONTRACT["flags"]["fields"]:
            self.assertEqual(field["values"], enums[field["enum"]], field["key"])

    def test_defaults(self):
        block = self.block("UX-FLAG-DEFAULTS")
        defaults = {name: expr.strip() for name, expr in re.findall(r"val (\w+): [\w?]+ = ([^,\n]+),", block)}
        for field in CONTRACT["flags"]["fields"]:
            self.assertEqual("%s.%s" % (field["enum"], field["default"]), defaults[field["kotlin"]], field["key"])
            self.assertIn(field["default"], field["values"])
        width = CONTRACT["flags"]["details_panel_width"]
        self.assertEqual("null", defaults[width["kotlin"]])
        self.assertIsNone(width["default"])
        self.assertEqual("null", defaults[CONTRACT["flags"]["window"]["kotlin"]])

    def test_spec_defaults(self):
        defaults = {field["key"]: field["default"] for field in CONTRACT["flags"]["fields"]}
        self.assertEqual("AUTO", defaults["rail_mode"])
        self.assertEqual("LEGACY", defaults["player_layout"])
        self.assertEqual(("VOLUME", "PAUSE", "FULLSCREEN"), (defaults["wheel_action"], defaults["click_action"], defaults["double_click_action"]))

    def test_player_layout_values_match_the_spec(self):
        values = {field["key"]: field["values"] for field in CONTRACT["flags"]["fields"]}
        self.assertEqual(["AUTO", "EXPANDED", "COLLAPSED", "CLASSIC_TEXT"], values["rail_mode"])
        self.assertEqual(["LEGACY", "DESKTOP"], values["player_layout"])
        self.assertEqual(["DESKTOP", "CLASSIC"], values["home_layout"])
        self.assertEqual(["ON", "OFF"], values["card_interactions"])
        self.assertEqual(["AUTO", "OPEN", "CLOSED"], values["details_panel"])
        self.assertEqual(["VOLUME", "SEEK", "NONE"], values["wheel_action"])
        self.assertEqual(["PAUSE", "NONE"], values["click_action"])
        self.assertEqual(["FULLSCREEN", "NONE"], values["double_click_action"])

    def test_details_panel_width_range_is_the_layout_panel_range(self):
        width = CONTRACT["flags"]["details_panel_width"]
        rules = CONTRACT["player"]["rules"]
        self.assertEqual((rules["panel_min"], rules["panel_max"]), (width["min"], width["max"]))


class PinnedConstantsTest(unittest.TestCase):
    """Numbers of existing pinned classes that the contract repeats. A JVM test pins each class; these source pins
    keep the contract honest where the cloud plan does not run that class (spec 10.4)."""

    def test_zoom_contract(self):
        text = source.read(source.K / "appearance/DesktopWindowsDisplayScale.kt")
        zoom = CONTRACT["tokens"]["zoom_percent"]
        for name, key in (("DEFAULT", "default"), ("MIN", "min"), ("MAX", "max"), ("STEP", "step")):
            self.assertIn("WINDOWS_DISPLAY_%s_PERCENT = %d" % (name, zoom[key]), text)

    def test_legacy_fullscreen_idle(self):
        text = source.read(source.K / "ui/DesktopWindowsFullscreenChromeState.kt")
        nanos = CONTRACT["chrome"]["legacy_fullscreen_idle_ms"] * 1000000
        # Slice X1b turned the private IDLE_NANOS into the idleNanos constructor parameter whose default is this constant.
        self.assertIn("const val DEFAULT_IDLE_NANOS = %s" % "{:,}".format(nanos).replace(",", "_") + "L", text)
        self.assertIn("private val idleNanos: Long = DEFAULT_IDLE_NANOS,", text)

    def test_minimum_window(self):
        text = source.read(source.K / "appearance/DesktopWindowsDisplayScaleScope.kt")
        width, height = CONTRACT["tokens"]["min_window_dp"]
        self.assertIn("(%d * physicalDensity / awtScaleX)" % width, text)
        self.assertIn("(%d * physicalDensity / awtScaleY)" % height, text)

    def test_classic_rail_width(self):
        text = source.read(source.K / "ui/DesktopOriginalRootChrome.kt")
        self.assertEqual(1, text.count("Box(Modifier.width(%d.dp).fillMaxHeight())" % CONTRACT["rail"]["footprint"]["classic"]))

    def test_details_tab_enum_and_the_modulo_three_pins(self):
        # Spec 4.11: the old panel keeps its three tabs and its test hard-codes % 3, so the new panel has its own enum.
        panel = source.read(source.K / "ui/DesktopWindowsVideoDetailsPanel.kt")
        match = re.search(r"enum class DesktopWindowsVideoDetailsTab\([^)]*\) \{([^}]*)\}", panel)
        self.assertIsNotNone(match)
        self.assertEqual(["INTRODUCTION", "COMMENTS", "RELATED"], re.findall(r"([A-Z][A-Z_]*)\(", match.group(1)))
        test = source.read(source.KT / "ui/DesktopWindowsVideoDetailsPanelLayoutTest.kt")
        self.assertEqual(1, test.count("(tab.ordinal + 1) % 3"))
        self.assertEqual(1, test.count("(selected.ordinal + 1) % 3"))

    def test_reduced_motion_marker_and_its_accessor(self):
        self.assertEqual("none", CONTRACT["tokens"]["motion_reduced"])
        self.assertTrue(CONTRACT["tokens"]["motion_ms"])
        self.assertIn("fun rememberSystemReduceMotion", source.read(source.K / "settings/DesktopSystemReduceMotion.kt"))

    def test_side_panel_numbers_of_the_spec(self):
        # Spec 4.11: header 44, splitter hit 6 dp (a drag handle, so below the pointer hit floor on purpose).
        self.assertEqual({"header": 44, "splitter_hit": 6}, CONTRACT["player"]["overlay"]["side_panel"])

    def test_new_idle_is_shorter_than_the_legacy_one(self):
        chrome = CONTRACT["chrome"]
        self.assertLess(chrome["idle_ms"], chrome["legacy_fullscreen_idle_ms"])
        self.assertLess(chrome["leave_idle_ms"], chrome["idle_ms"])
        self.assertGreater(chrome["fade_out_ms"], chrome["fade_in_ms"])

    def test_tokens_respect_the_hit_floor(self):
        tokens = CONTRACT["tokens"]
        self.assertGreaterEqual(tokens["control_height"], tokens["hit_floor"])
        self.assertGreaterEqual(min(tokens["icon_hit"]), tokens["hit_floor"])
        self.assertGreaterEqual(CONTRACT["rail"]["metrics"]["item"], tokens["hit_floor"])
        self.assertGreaterEqual(CONTRACT["player"]["overlay"]["dock"]["button_hit"], tokens["hit_floor"])


class FlagStoreSourceTest(unittest.TestCase):
    """Source-level facts about the windows_ux store that no Kotlin test can show before a compile."""

    @classmethod
    def setUpClass(cls):
        cls.files = {path.name: source.read(path) for path in (MODEL_KT, CONTROLLER_KT, STRINGS_KT)}

    def code(self, name):
        return source.mask_kotlin(self.files[name])

    def test_the_store_never_touches_the_window_zoom_namespace(self):
        for name, text in self.files.items():
            for needle in ("windows_display", "WINDOWS_DISPLAY", "DisplayScale", "scale_percent"):
                self.assertFalse(needle in text, "%s mentions %s" % (name, needle))

    def test_writes_go_through_the_namespace_checked_atomic_update_only(self):
        controller = self.code("DesktopWindowsUx.kt")
        self.assertEqual(1, controller.count("store.updateFromSnapshot(WINDOWS_UX_NAMESPACE)"))
        for banned in ("store.update(", "updateOriginal", "Files.", "writeText", "FileOutputStream"):
            self.assertFalse(banned in controller, "the controller uses " + banned)

    def test_no_throwing_constructs_in_the_store(self):
        allowed_rethrow = "throw cancelled"
        for name in ("DesktopWindowsUxModel.kt", "DesktopWindowsUx.kt"):
            code = self.code(name).replace(allowed_rethrow, "")
            for pattern in (r"\bthrow\b", r"\berror\(", r"\brequire\(", r"\bcheck\(", r"\brequireNotNull\(",
                            r"\bcheckNotNull\(", r"!!", r"\bTODO\("):
                self.assertIsNone(re.search(pattern, code), "%s contains %s" % (name, pattern))
        self.assertEqual(1, self.files["DesktopWindowsUx.kt"].count(allowed_rethrow))

    def test_the_model_is_pure(self):
        text = self.files["DesktopWindowsUxModel.kt"]
        code = self.code("DesktopWindowsUxModel.kt")
        for banned in ("androidx.compose", "java.awt", "javax.swing", "com.sun.jna", "kotlinx.coroutines"):
            self.assertFalse(banned in code, "the pure model mentions " + banned)
        imports = re.findall(r"(?m)^import (\S+)", text)
        self.assertEqual(sorted(imports), sorted([
            "com.bilipai.desktop.plugins.DesktopPreferenceKey", "com.bilipai.desktop.plugins.DesktopPreferenceSnapshot",
            "kotlinx.serialization.json.JsonElement", "kotlinx.serialization.json.JsonPrimitive",
            "kotlinx.serialization.json.booleanOrNull", "kotlinx.serialization.json.intOrNull"]))

    def test_the_default_local_is_an_immutable_shared_instance(self):
        # Compose caches the default factory result of a CompositionLocal (a LazyValueHolder), so every fixture in a JVM
        # that provides nothing shares ONE object. It must therefore be read-only; a fixture that mutates flags
        # provides its own DesktopWindowsUxMemory().
        text = self.code("DesktopWindowsUx.kt")
        self.assertEqual(1, text.count("staticCompositionLocalOf<DesktopWindowsUx> { DesktopWindowsUxDefaults }"))
        self.assertNotIn("DesktopWindowsUxMemory()", text[text.index("staticCompositionLocalOf"):])
        start = text.index("internal object DesktopWindowsUxDefaults")
        body = text[start:text.index("\n}\n", start)]
        self.assertEqual(1, body.count("override fun submit(command: DesktopWindowsUxCommand): Boolean = false"))
        for banned in ("MutableStateFlow<DesktopWindowsUxSettings>", "state.update", "state.value", "var "):
            self.assertNotIn(banned, body)
        for name in ("DesktopWindowsUxModel.kt", "DesktopWindowsUxStrings.kt"):
            self.assertFalse("staticCompositionLocalOf" in self.files[name], name)

    def test_the_deviation_from_the_display_scale_controller_is_written_down(self):
        # The raw text is read on purpose: the point is that a reader of the class sees the difference.
        text = self.files["DesktopWindowsUx.kt"]
        for phrase in ("Root-minted preference permit", "registered window context", "owns() check",
                       "plain DesktopPluginStore.updateFromSnapshot", "Root is asked to confirm"):
            self.assertIn(phrase, text)

    def test_close_lets_the_queued_commands_finish_and_closeAndJoin_waits_for_them(self):
        text = self.code("DesktopWindowsUx.kt")
        close = text[text.index("override fun close()"):text.index("suspend fun closeAndJoin()")]
        self.assertIn("requests.close()", close)
        self.assertIn("collector.cancel()", close)
        self.assertNotIn("job.cancel()", close, "close() must not cancel the worker that still holds queued commands")
        self.assertNotIn("worker.cancel()", close)
        self.assertEqual(1, text.count("worker.invokeOnCompletion { job.cancel() }"))
        join = text[text.index("suspend fun closeAndJoin()"):]
        self.assertIn("close()", join)
        self.assertIn("worker.join()", join)

    def test_a_late_emission_cannot_undo_a_newer_settled_state(self):
        text = self.code("DesktopWindowsUx.kt")
        self.assertEqual(1, text.count("snapshot.collect { adopt() }"))
        adopt = text[text.index("private fun adopt()"):text.index("private fun settle(")]
        self.assertIn("read(snapshot.value)", adopt)

    def test_an_optimistic_update_carries_the_read_problems(self):
        code = self.code("DesktopWindowsUxModel.kt")
        start = code.index("fun desktopWindowsUxAfter(")
        body = code[start:]
        self.assertIn("current.invalidKeys.filter { it !in edits.keys }", body)
        self.assertIn("current.error == DesktopWindowsUxStrings.UNREADABLE -> current.error", body)
        self.assertIn(".copy(invalidKeys = stillInvalid, error = error)", body)

    def test_all_user_text_lives_in_the_strings_object(self):
        self.assertTrue(source.cjk_positions(self.files["DesktopWindowsUxStrings.kt"]))
        for name in ("DesktopWindowsUxModel.kt", "DesktopWindowsUx.kt"):
            self.assertEqual([], source.cjk_positions(self.files[name]), name)

    def test_defaults_do_not_write_on_construction(self):
        text = self.code("DesktopWindowsUx.kt")
        init = text[text.index("init {"):text.index("private fun read(")]
        self.assertFalse("updateFromSnapshot" in init, "the constructor writes")
        self.assertFalse("write(" in init.replace("write(command)", ""), "the constructor writes")


if __name__ == "__main__":
    unittest.main()
