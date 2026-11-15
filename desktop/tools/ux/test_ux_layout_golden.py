"""Layout golden tables of the desktop UX program (slice X1a): the rail model and the Home density rule. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_layout_golden.py

What is proved. The pure Kotlin files ui/DesktopWindowsRailModel.kt and ui/DesktopWindowsLayoutMetrics.kt carry the
numbers, tables and orders of the contract (parsed from the Kotlin text and compared). Python mirrors of their
functions (ux_shell_model.py, Kotlin integer semantics, fed with the constants parsed from the Kotlin text) reproduce
every golden row of the contract and the spec tables, and agree with the exact rational reference of ux_model over the
whole sweep W 960..4200, every rail mode and the card presets. The transliterated Kotlin lines are pinned as source
needles, so editing the Kotlin without the mirror, or the mirror without the Kotlin, fails here. Rail labels equal the
vendored BottomNavItem labels. The delegation cases, the grid cap and centring, the compact-metadata rule, the
Ctrl+number mapping, the unread badge and the hysteresis behaviour have their own tests.

What is not proved. That the Kotlin compiles or runs: DesktopWindowsRailModelTest and DesktopWindowsLayoutMetricsTest
are the proof, on the Linux compile later. A mirror is only as faithful as the needles that bind it.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in
desktop/tools/tests as spec 8.2 and 9.2 say; Root adds each suite to the validation plan as an explicit
`-s desktop/tools/ux -p <file>` line.

Hysteresis note. The spec says the content width stays at least 884 in EXPANDED mode. That is true of a fresh
evaluation. With the collapse-below band (1084) an expanded rail survives down to W 1084, where the content is 868 wide
(still above the 840 dp Expanded size class). test_the_expanded_band_lets_the_content_shrink_to_868 pins that fact.
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_model as model  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()
RAIL_TEXT = shell.read(shell.RAIL_KT)
METRICS_TEXT = shell.read(shell.METRICS_KT)
SHORTCUTS_TEXT = shell.read(shell.SHORTCUTS_KT)
STRINGS_TEXT = shell.read(shell.STRINGS_KT)
LIMITS = shell.thresholds(RAIL_TEXT)
CONSTS = dict(shell.int_constants(RAIL_TEXT))
CONSTS.update(shell.int_constants(METRICS_TEXT))
FOOTPRINT = {"expanded": CONSTS["DESKTOP_RAIL_FOOTPRINT_EXPANDED_DP"], "collapsed": CONSTS["DESKTOP_RAIL_FOOTPRINT_COLLAPSED_DP"],
             "classic": CONSTS["DESKTOP_RAIL_FOOTPRINT_CLASSIC_DP"]}
ITEMS = shell.rail_items(RAIL_TEXT)
BOTTOM_NAV = shell.bottom_nav_items()
FIRST, LAST = CONTRACT["feed"]["sweep"]["from"], CONTRACT["feed"]["sweep"]["to"]
MIN_CARDS = (None, 100, 160, 260, 320)

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
# Spec 3.2 and 3.3 numbers, verbatim.
SPEC_RAIL = {"AUTO": (1280, 1264, 1280), "EXPANDED": (1100, 1084, 1116)}


def fresh(mode, width, min_card=None):
    return shell.window_home_layout_kt(CONSTS, LIMITS, mode, width, None, min_card)


def traverse(mode, widths, min_card=None):
    """Window layouts as the width moves through widths, the rail presentation carried along as its memory."""
    previous, rows = None, []
    for width in widths:
        row = shell.window_home_layout_kt(CONSTS, LIMITS, mode, width, previous, min_card)
        previous = row["rail"]
        rows.append((width, row))
    return rows


# --------------------------------------------------------------------------------------
# Rail: the Kotlin tables against the contract
# --------------------------------------------------------------------------------------

class RailKotlinTableTest(unittest.TestCase):
    def test_footprints_and_badge_cap_equal_the_contract(self):
        rail = CONTRACT["rail"]
        self.assertEqual(rail["footprint"], {"expanded": FOOTPRINT["expanded"], "collapsed": FOOTPRINT["collapsed"],
                                             "classic": FOOTPRINT["classic"]})
        self.assertEqual(rail["metrics"]["badge_cap"], CONSTS["DESKTOP_RAIL_BADGE_CAP"])
        self.assertEqual((216, 72, 99), (FOOTPRINT["expanded"], FOOTPRINT["collapsed"], CONSTS["DESKTOP_RAIL_BADGE_CAP"]))

    def test_the_presentation_enum_names_each_footprint_once(self):
        found = shell.presentations(RAIL_TEXT)
        self.assertEqual(["EXPANDED", "COLLAPSED", "CLASSIC"], list(found))
        for name, (contract_name, const) in found.items():
            self.assertEqual(name.lower(), contract_name)
            self.assertEqual(CONTRACT["rail"]["footprint"][contract_name], CONSTS[const], name)

    def test_thresholds_equal_the_contract_and_the_spec(self):
        for mode, limits in CONTRACT["rail"]["thresholds"].items():
            self.assertEqual((limits["expand_at"], limits["collapse_below"], limits["reexpand_at"]), LIMITS[mode], mode)
        self.assertEqual(SPEC_RAIL, LIMITS)
        self.assertEqual({"AUTO", "EXPANDED"}, set(LIMITS))

    def test_the_rows_follow_the_contract_groups_in_order(self):
        groups = CONTRACT["rail"]["groups"]
        for group in ("browse", "library", "footer"):
            self.assertEqual(groups[group], [name for name, kind, _bottom in ITEMS if kind == group.upper()], group)
        self.assertEqual(groups["browse"] + groups["library"] + groups["footer"], [name for name, _g, _b in ITEMS])

    def test_bottom_nav_names_are_real_constants_and_cover_every_non_story_item(self):
        vendored = [name for name, _label in BOTTOM_NAV]
        for name, _group, bottom in ITEMS:
            if name in ("DOWNLOADS", "ACCOUNT_SWITCH"):
                self.assertIsNone(bottom, name)
            else:
                self.assertEqual(name, bottom)
                self.assertIn(bottom, vendored)
        mapped = {bottom for _n, _g, bottom in ITEMS if bottom}
        self.assertEqual(set(vendored) - set(CONTRACT["rail"]["groups"]["excluded"]), mapped)
        self.assertEqual(set(CONTRACT["rail"]["groups"]["excluded"]), set(shell.string_set(RAIL_TEXT, "DESKTOP_RAIL_EXCLUDED_BOTTOM_NAV")))

    def test_the_fixed_destinations_equal_the_contract(self):
        fixed = shell.fixed_destinations(RAIL_TEXT)
        self.assertEqual(CONTRACT["rail"]["fixed_destinations"], fixed)
        self.assertEqual(9, len(fixed))
        self.assertNotIn("ACCOUNT_SWITCH", fixed)
        self.assertNotIn("PLUGINS", fixed)
        self.assertNotIn("SETTINGS", fixed)
        order = [name for name, _g, _b in ITEMS]
        self.assertEqual(fixed, [name for name in order if name in fixed], "the fixed numbers follow the display order")

    def test_rail_labels_keep_upstream_names_except_the_desktop_home_entry(self):
        labels = shell.rail_labels(STRINGS_TEXT)
        self.assertEqual({name for name, _g, _b in ITEMS}, set(labels))
        vendored = dict(BOTTOM_NAV)
        for name, _group, bottom in ITEMS:
            if bottom is not None:
                self.assertEqual("首页" if name == "HOME" else vendored[bottom], labels[name], name)

    def test_the_rows_the_rail_adds_and_the_chrome_texts_equal_the_existing_rail(self):
        chrome = shell.read(shell.ROOT_CHROME)
        labels = shell.rail_labels(STRINGS_TEXT)
        for needle in ('Text("BiliPai"', 'Text("搜索")', 'Text("%s")' % labels["DOWNLOADS"], 'Text("%s")' % labels["ACCOUNT_SWITCH"],
                       'Text("上一首")', 'Text("打开播放器")', 'Text("关闭")'):
            self.assertIn(needle, chrome, needle)
        for needle in ('const val WORDMARK = "BiliPai"', 'const val SEARCH = "搜索"', 'const val NOW_PLAYING_PREVIOUS = "上一首"',
                       'const val NOW_PLAYING_PLAY = "播放"', 'const val NOW_PLAYING_PAUSE = "暂停"',
                       'const val NOW_PLAYING_NEXT = "下一首"', 'const val NOW_PLAYING_OPEN = "打开播放器"',
                       'const val NOW_PLAYING_CLOSE = "关闭"'):
            self.assertEqual(1, STRINGS_TEXT.count(needle), needle)

    def test_the_spec_text_says_watch_later_with_the_legacy_alias(self):
        # Spec 3.2 writes WATCHLATER as the longer legacy alias; the task and the spec both say labels equal
        # BottomNavItem.label, which is the short form. The strings follow the enum (reported as a deviation).
        text = shell.read(shell.BOTTOM_BAR)
        self.assertIn('listOf("稍后再看")', text)
        self.assertEqual("稍后看", shell.rail_labels(STRINGS_TEXT)["WATCHLATER"])

    def test_the_tooltip_and_announcement_functions_are_pinned_whole(self):
        text = shell.squash(STRINGS_TEXT)
        for needle in (
            'fun zoomToast(percent: Int): String = "界面缩放 ${percent}%"',
            'fun unreadAnnouncement(label: String, unreadCount: Int): String = "${label}，${unreadCount} 条未读"',
            # squash normalizes the full-width separator; the raw spelling is checked below.
            'fun collapsedTooltip(label: String, number: Int?): String = if (number == null) label else "$label Ctrl+$number"',
            'fun expandedTooltip(number: Int?): String? = if (number == null) null else "Ctrl+$number"',
            "DesktopRailGroup.BROWSE -> GROUP_BROWSE DesktopRailGroup.LIBRARY -> GROUP_LIBRARY DesktopRailGroup.FOOTER -> null",
        ):
            self.assertEqual(1, text.count(needle), needle)

    def test_spec_strings_are_spelled_as_in_the_spec_but_the_rail_auto_text_is_the_guard_safe_variant(self):
        # Spec 3.2 joins the last two words of the restore-to-automatic label directly. test_nvidia_preferences_schema.py
        # and test_nvidia_ui_strings.py forbid that exact four-character run in the sources (a guard against an NVIDIA
        # manual mode), so the strings object spells it with one connecting character: an accepted deviation.
        self.assertNotIn("恢复自动", STRINGS_TEXT)
        for needle in ('const val RAIL_NAME = "主导航"', 'const val SEARCH_HINT = "Ctrl K"', 'const val GROUP_BROWSE = "浏览"',
                       'const val GROUP_LIBRARY = "媒体库"', 'const val RAIL_COLLAPSE = "折叠导航栏"',
                       'const val RAIL_EXPAND = "展开导航栏"', 'const val RAIL_AUTO = "导航栏恢复为自动"',
                       'const val RAIL_SAVE_FAILED = "无法保存导航栏设置"', '"界面缩放 ${percent}%"', '"${label}，${unreadCount} 条未读"',
                       '"$label　Ctrl+$number"', '"Ctrl+$number"'):
            self.assertEqual(1, STRINGS_TEXT.count(needle), needle)
        self.assertEqual({"BROWSE", "LIBRARY", "FOOTER"}, set(re.findall(r"DesktopRailGroup\.([A-Z]+) ->", STRINGS_TEXT)))


# --------------------------------------------------------------------------------------
# Rail: the mirror against the contract and the reference model
# --------------------------------------------------------------------------------------

class RailMirrorTest(unittest.TestCase):
    def test_every_contract_golden_row(self):
        for row in CONTRACT["rail"]["golden"]:
            rail = shell.rail_presentation_kt(LIMITS, row["mode"], row["width_dp"], row.get("previous"))
            self.assertEqual(row["expect"], {"rail": rail, "footprint": FOOTPRINT[rail]}, row["id"])

    def test_the_mirror_equals_the_reference_for_every_width_mode_and_memory(self):
        for mode in ("AUTO", "EXPANDED", "COLLAPSED", "CLASSIC_TEXT"):
            for previous in (None, "expanded", "collapsed", "classic"):
                for width in range(0, 5001):
                    self.assertEqual(model.rail_presentation(CONTRACT, mode, width, previous),
                                     shell.rail_presentation_kt(LIMITS, mode, width, previous), (mode, previous, width))

    def test_the_hysteresis_edges_are_the_spec_numbers(self):
        auto = lambda width, previous=None: shell.rail_presentation_kt(LIMITS, "AUTO", width, previous)
        self.assertEqual(["collapsed", "expanded"], [auto(1279), auto(1280)])
        self.assertEqual(["expanded", "collapsed"], [auto(1264, "expanded"), auto(1263, "expanded")])
        self.assertEqual(["collapsed", "expanded"], [auto(1279, "collapsed"), auto(1280, "collapsed")])
        wide = lambda width, previous=None: shell.rail_presentation_kt(LIMITS, "EXPANDED", width, previous)
        self.assertEqual(["collapsed", "expanded"], [wide(1099), wide(1100)])
        self.assertEqual(["expanded", "collapsed"], [wide(1084, "expanded"), wide(1083, "expanded")])
        self.assertEqual(["collapsed", "expanded"], [wide(1115, "collapsed"), wide(1116, "collapsed")])

    def test_a_classic_memory_starts_a_fresh_decision(self):
        for width in (1000, 1263, 1264, 1279, 1280, 3000):
            self.assertEqual(shell.rail_presentation_kt(LIMITS, "AUTO", width, None),
                             shell.rail_presentation_kt(LIMITS, "AUTO", width, "classic"), width)

    def test_widening_and_narrowing_switch_at_different_widths(self):
        for mode, up, down in (("AUTO", 1280, 1263), ("EXPANDED", 1116, 1083)):
            widening = traverse(mode, range(FIRST, LAST + 1))
            narrowing = traverse(mode, range(LAST, FIRST - 1, -1))
            self.assertEqual(up, next(w for w, row in widening if row["rail"] == "expanded"), mode)
            self.assertEqual(down, next(w for w, row in narrowing if row["rail"] == "collapsed"), mode)
            self.assertEqual(1, sum(1 for (_a, x), (_b, y) in zip(widening, widening[1:]) if x["rail"] != y["rail"]), mode)
            self.assertEqual(1, sum(1 for (_a, x), (_b, y) in zip(narrowing, narrowing[1:]) if x["rail"] != y["rail"]), mode)

    def test_a_fresh_start_expands_at_the_expand_threshold_not_the_re_expand_one(self):
        self.assertEqual("expanded", shell.rail_presentation_kt(LIMITS, "EXPANDED", 1100))
        self.assertEqual("collapsed", shell.rail_presentation_kt(LIMITS, "EXPANDED", 1100, "collapsed"))
        self.assertEqual("expanded", shell.rail_presentation_kt(LIMITS, "AUTO", 1280, "collapsed"))

    def test_the_content_floor_holds_for_a_fresh_evaluation(self):
        floor = CONSTS["DESKTOP_HOME_MIN_CONTENT_DP"]
        self.assertEqual(floor, CONTRACT["feed"]["rules"]["min_content_width"])
        for mode in ("AUTO", "EXPANDED", "COLLAPSED"):
            for width in range(FIRST, LAST + 1):
                self.assertGreaterEqual(fresh(mode, width)["wc"], floor, (mode, width))

    def test_the_expanded_band_lets_the_content_shrink_to_868(self):
        narrowing = traverse("EXPANDED", range(LAST, FIRST - 1, -1))
        floor = min(row["wc"] for _w, row in narrowing)
        self.assertEqual(LIMITS["EXPANDED"][1] - FOOTPRINT["expanded"], floor)
        self.assertEqual(868, floor)
        self.assertLess(floor, CONSTS["DESKTOP_HOME_MIN_CONTENT_DP"])
        self.assertGreaterEqual(floor, 840)  # still the Expanded size class of the vendored policy
        auto = min(row["wc"] for _w, row in traverse("AUTO", range(LAST, FIRST - 1, -1)))
        self.assertGreaterEqual(auto, CONSTS["DESKTOP_HOME_MIN_CONTENT_DP"])

    def test_collapsed_and_classic_modes_ignore_the_width(self):
        for width in (0, 960, 1280, 4200, 100000):
            self.assertEqual("collapsed", shell.rail_presentation_kt(LIMITS, "COLLAPSED", width, "expanded"))
            self.assertEqual("classic", shell.rail_presentation_kt(LIMITS, "CLASSIC_TEXT", width, "expanded"))


class RailRowsMirrorTest(unittest.TestCase):
    def setUp(self):
        self.excluded = set(shell.string_set(RAIL_TEXT, "DESKTOP_RAIL_EXCLUDED_BOTTOM_NAV"))
        self.visible = [name for name, _label in BOTTOM_NAV if name != "STORY"]

    def names(self, entries):
        return [item if item is not None else "?" + bottom for item, bottom in entries]

    def test_the_default_rail_is_the_spec_order_without_an_account_row(self):
        entries = shell.rail_entries_kt(ITEMS, self.excluded, self.visible, False)
        self.assertEqual(["HOME", "DYNAMIC", "LIVE", "LISTEN_VIDEO", "HISTORY", "FAVORITE", "WATCHLATER", "DOWNLOADS", "PROFILE",
                          "PLUGINS", "SETTINGS"], self.names(entries))

    def test_the_account_row_leads_the_footer_and_never_shifts_a_number(self):
        with_account = shell.rail_entries_kt(ITEMS, self.excluded, self.visible, True)
        self.assertEqual(["HOME", "DYNAMIC", "LIVE", "LISTEN_VIDEO", "HISTORY", "FAVORITE", "WATCHLATER", "DOWNLOADS",
                          "ACCOUNT_SWITCH", "PROFILE", "PLUGINS", "SETTINGS"], self.names(with_account))
        fixed = shell.fixed_destinations(RAIL_TEXT)
        for entries in (with_account, shell.rail_entries_kt(ITEMS, self.excluded, self.visible, False)):
            shown = [name for name in self.names(entries) if name in fixed]
            self.assertEqual(fixed, shown)

    def test_story_never_gets_a_row_even_when_offered(self):
        entries = shell.rail_entries_kt(ITEMS, self.excluded, self.visible + ["STORY"], False)
        self.assertNotIn("?STORY", self.names(entries))

    def test_an_item_the_table_does_not_know_joins_the_browse_group(self):
        entries = shell.rail_entries_kt(ITEMS, self.excluded, self.visible + ["NEW_ITEM"], False)
        names = self.names(entries)
        self.assertEqual(names.index("LISTEN_VIDEO") + 1, names.index("?NEW_ITEM"))
        self.assertLess(names.index("?NEW_ITEM"), names.index("HISTORY"))

    def test_rows_without_a_visible_bottom_item_vanish_but_the_downloads_row_stays(self):
        self.assertEqual(["HOME", "DOWNLOADS"], self.names(shell.rail_entries_kt(ITEMS, self.excluded, ["HOME"], False)))
        self.assertEqual(["DOWNLOADS"], self.names(shell.rail_entries_kt(ITEMS, self.excluded, [], False)))

    def test_groups_come_out_in_non_decreasing_order(self):
        rank = {"BROWSE": 0, "LIBRARY": 1, "FOOTER": 2}
        group_of = {name: group for name, group, _b in ITEMS}
        for account in (False, True):
            for extra in ([], ["NEW_ITEM"]):
                entries = shell.rail_entries_kt(ITEMS, self.excluded, self.visible + extra, account)
                ranks = [rank[group_of[item]] if item else 0 for item, _bottom in entries]
                self.assertEqual(sorted(ranks), ranks)

    def test_the_shell_item_names_the_chrome_offers_are_all_known(self):
        # The existing root stack offers BottomNavItem.entries minus STORY; none of them is unknown to the table.
        self.assertEqual([], [n for n in self.visible if n not in {b for _i, _g, b in ITEMS if b}])

    def test_ctrl_number_mapping_over_the_fixed_destinations(self):
        fixed = shell.fixed_destinations(RAIL_TEXT)
        by_chord = {row["chord"]: row["arg"] for row in shell.contract_shell_rows(CONTRACT) if row["action"] == "GO_DESTINATION"}
        for number in range(-2, 13):
            mirrored = fixed[number - 1] if 0 <= number - 1 < len(fixed) else None  # getOrNull(number - 1)
            self.assertEqual(by_chord.get("Ctrl+%d" % number), mirrored, number)
        self.assertEqual(["HOME", "DOWNLOADS", "PROFILE"], [fixed[0], fixed[7], fixed[8]])

    def test_selection_rule_truth_table_and_the_existing_expression(self):
        for on_main in (False, True):
            for same_item in (False, True):
                for same_route in (False, True):
                    got = shell.rail_selected_kt(on_main, "HOME", "HOME" if same_item else "LIVE", "r" if same_route else "x", "r")
                    self.assertEqual(on_main and same_item or same_route, got)
        chrome = shell.read(shell.ROOT_CHROME)
        self.assertIn("key == BiliPaiNavKey.MainHost && item == currentItem || key.toLegacyRoute() == item.route", chrome)
        self.assertIn("onMainHost && itemName == currentItemName || currentLegacyRoute == itemRoute", shell.squash(RAIL_TEXT))

    def test_clicking_the_selected_home_or_dynamic_row_refreshes(self):
        self.assertEqual("HOME_DOUBLE_TAP", shell.rail_click_kt("HOME", True))
        self.assertEqual("DYNAMIC_DOUBLE_TAP", shell.rail_click_kt("DYNAMIC", True))
        for item in ("HOME", "DYNAMIC", "LIVE", "PROFILE", "DOWNLOADS", None):
            self.assertEqual("NAVIGATE", shell.rail_click_kt(item, False), item)
        for item in ("LIVE", "HISTORY", "PROFILE", "SETTINGS", "DOWNLOADS", None):
            self.assertEqual("NAVIGATE", shell.rail_click_kt(item, True), item)

    def test_badge_text_and_kind(self):
        cap = CONSTS["DESKTOP_RAIL_BADGE_CAP"]
        text = lambda count: shell.badge_text_kt(count, cap)
        self.assertEqual([None, None, "1", "9", "10", "99", "99+", "99+", "99+"], [text(n) for n in (-5, 0, 1, 9, 10, 99, 100, 101, 100000)])
        for count in (-1, 0):
            self.assertEqual("NONE", shell.badge_kind_kt(count, "expanded"))
            self.assertEqual("NONE", shell.badge_kind_kt(count, "collapsed"))
        self.assertEqual("PILL", shell.badge_kind_kt(3, "expanded"))
        self.assertEqual("DOT", shell.badge_kind_kt(3, "collapsed"))
        self.assertEqual({"badge": 18, "badge_cap": 99, "badge_dot": 8}, {key: CONTRACT["rail"]["metrics"][key] for key in ("badge", "badge_cap", "badge_dot")})


# --------------------------------------------------------------------------------------
# Rail: the explicit mode a toggle writes and the context menu (spec 3.2 and 3.3)
# --------------------------------------------------------------------------------------

RAIL_MODES = re.search(r"enum class DesktopWindowsUxRailMode \{ ([A-Z_, ]+) \}",
                       shell.read(source.K / "appearance/DesktopWindowsUxModel.kt")).group(1).replace(" ", "").split(",")
PRESENTATIONS = ("expanded", "collapsed", "classic")
MENU_ACTIONS = shell.menu_actions(RAIL_TEXT)
SPEC_MENU_LABELS = {"COLLAPSE": "折叠导航栏", "EXPAND": "展开导航栏", "RESTORE_AUTO": "导航栏恢复为自动"}


def reachable_presentations(mode):
    """What a rail in this mode can show: the mirror run over every width and every memory."""
    return sorted({shell.rail_presentation_kt(LIMITS, mode, width, previous)
                   for previous in (None,) + PRESENTATIONS for width in range(0, 5001)})


class RailMenuMirrorTest(unittest.TestCase):
    def test_the_menu_actions_carry_the_explicit_modes_of_the_spec_and_their_labels(self):
        self.assertEqual({"COLLAPSE": "COLLAPSED", "EXPAND": "EXPANDED", "RESTORE_AUTO": "AUTO"}, MENU_ACTIONS)
        self.assertEqual(["COLLAPSE", "EXPAND", "RESTORE_AUTO"], list(MENU_ACTIONS))
        self.assertEqual(["AUTO", "EXPANDED", "COLLAPSED", "CLASSIC_TEXT"], RAIL_MODES)
        self.assertTrue(set(MENU_ACTIONS.values()) < set(RAIL_MODES))  # real flag values, and CLASSIC_TEXT is never written
        self.assertEqual(SPEC_MENU_LABELS, shell.rail_menu_labels(STRINGS_TEXT))
        self.assertEqual(3, len(set(shell.rail_menu_labels(STRINGS_TEXT).values())))

    def test_the_reachable_presentations_of_each_mode(self):
        self.assertEqual({"AUTO": ["collapsed", "expanded"], "EXPANDED": ["collapsed", "expanded"], "COLLAPSED": ["collapsed"],
                          "CLASSIC_TEXT": ["classic"]}, {mode: reachable_presentations(mode) for mode in RAIL_MODES})

    def test_the_menu_table_of_the_spec(self):
        menu = lambda mode, presentation: shell.rail_menu_actions_kt(MENU_ACTIONS, mode, presentation)
        self.assertEqual(["COLLAPSE"], menu("AUTO", "expanded"))
        self.assertEqual(["EXPAND"], menu("AUTO", "collapsed"))
        self.assertEqual(["COLLAPSE", "RESTORE_AUTO"], menu("EXPANDED", "expanded"))
        self.assertEqual(["RESTORE_AUTO"], menu("EXPANDED", "collapsed"))  # a window too narrow for the expanded rail
        self.assertEqual(["EXPAND", "RESTORE_AUTO"], menu("COLLAPSED", "collapsed"))
        self.assertEqual([], menu("CLASSIC_TEXT", "classic"))  # the classic block has no menu

    def test_the_menu_mirror_equals_the_spec_oracle_for_every_mode_and_presentation(self):
        for mode in RAIL_MODES:
            for presentation in PRESENTATIONS:  # a total function: pairs the rail cannot reach included
                self.assertEqual(shell.oracle_rail_menu(mode, presentation),
                                 shell.rail_menu_actions_kt(MENU_ACTIONS, mode, presentation), (mode, presentation))

    def test_every_entry_changes_the_mode_and_does_what_its_label_says(self):
        for mode in RAIL_MODES:
            for presentation in reachable_presentations(mode):
                for action in shell.rail_menu_actions_kt(MENU_ACTIONS, mode, presentation):
                    written = MENU_ACTIONS[action]
                    self.assertNotEqual(mode, written, (mode, presentation, action))
                    if action == "COLLAPSE":
                        self.assertEqual("expanded", presentation)
                        for width in range(0, 5001, 7):
                            self.assertEqual("collapsed", shell.rail_presentation_kt(LIMITS, written, width, presentation), width)
                    if action == "EXPAND":
                        self.assertEqual("collapsed", presentation)
                        self.assertEqual("expanded", shell.rail_presentation_kt(LIMITS, written, LIMITS["EXPANDED"][2], presentation))
                        self.assertEqual("collapsed", shell.rail_presentation_kt(LIMITS, written, LIMITS["EXPANDED"][0] - 1, presentation))

    def test_the_toggle_table(self):
        toggle = shell.rail_toggle_mode_kt
        self.assertEqual("COLLAPSED", toggle("AUTO", "expanded"))
        self.assertEqual("EXPANDED", toggle("AUTO", "collapsed"))
        self.assertEqual("COLLAPSED", toggle("EXPANDED", "expanded"))
        self.assertIsNone(toggle("EXPANDED", "collapsed"))  # already the opposite: the window is too narrow, nothing to write
        self.assertEqual("EXPANDED", toggle("COLLAPSED", "collapsed"))
        self.assertIsNone(toggle("CLASSIC_TEXT", "classic"))  # the classic block has no collapsed form and keeps its choice

    def test_the_toggle_is_total_never_writes_the_mode_in_force_and_never_leaves_classic(self):
        for mode in RAIL_MODES:
            for presentation in PRESENTATIONS:
                result = shell.rail_toggle_mode_kt(mode, presentation)
                self.assertNotEqual(mode, result, (mode, presentation))
                self.assertIn(result, (None, "COLLAPSED", "EXPANDED"), (mode, presentation))  # never AUTO or CLASSIC_TEXT
                if presentation == "classic":
                    self.assertIsNone(result, mode)

    def test_the_toggle_and_the_first_menu_entry_are_the_same_write(self):
        for mode in RAIL_MODES:
            for presentation in PRESENTATIONS:
                result = shell.rail_toggle_mode_kt(mode, presentation)
                menu = shell.rail_menu_actions_kt(MENU_ACTIONS, mode, presentation)
                if result is not None:
                    self.assertEqual(result, MENU_ACTIONS[menu[0]], (mode, presentation))
                else:
                    self.assertTrue(all(MENU_ACTIONS[action] == "AUTO" for action in menu), (mode, presentation))

    def test_two_toggles_return_to_the_first_presentation_when_the_window_is_wide_enough(self):
        mode, shown = "AUTO", "expanded"
        for width in (1116, 1280, 1500, 4200):
            first = shell.rail_toggle_mode_kt(mode, shown)
            collapsed = shell.rail_presentation_kt(LIMITS, first, width, shown)
            second = shell.rail_toggle_mode_kt(first, collapsed)
            self.assertEqual(("COLLAPSED", "collapsed", "EXPANDED"), (first, collapsed, second), width)
            self.assertEqual("expanded", shell.rail_presentation_kt(LIMITS, second, width, collapsed), width)


# --------------------------------------------------------------------------------------
# Home density: the Kotlin constants against the contract
# --------------------------------------------------------------------------------------

class HomeKotlinTableTest(unittest.TestCase):
    NAMES = {
        "side_padding": "DESKTOP_HOME_SIDE_PADDING_DP",
        "gutter": "DESKTOP_HOME_GUTTER_DP",
        "min_card": "DESKTOP_HOME_MIN_CARD_DP",
        "max_card": "DESKTOP_HOME_MAX_CARD_DP",
        "card_floor": "DESKTOP_HOME_CARD_FLOOR_DP",
        "min_columns": "DESKTOP_HOME_MIN_COLUMNS",
        "max_columns_below_wide": "DESKTOP_HOME_MAX_COLUMNS_BELOW_WIDE",
        "max_columns_wide": "DESKTOP_HOME_MAX_COLUMNS_WIDE",
        "wide_from_content_width": "DESKTOP_HOME_WIDE_FROM_CONTENT_DP",
        "compact_metadata_off_from_card": "DESKTOP_HOME_COMPACT_METADATA_OFF_FROM_CARD_DP",
        "min_content_width": "DESKTOP_HOME_MIN_CONTENT_DP",
        "dropped_legacy_cap": "DESKTOP_HOME_LEGACY_CAP_DP",
    }

    def test_every_rule_of_the_contract_has_its_constant_and_value(self):
        rules = CONTRACT["feed"]["rules"]
        block = shell.int_constants(shell.marked_block(METRICS_TEXT, "HOME-DENSITY"))
        self.assertEqual(set(self.NAMES.values()), set(block))
        for key, name in self.NAMES.items():
            self.assertEqual(rules[key], block[name], key)
        self.assertEqual(set(rules) - {"card_presets"}, set(self.NAMES), "a contract rule has no Kotlin constant")

    def test_the_spec_numbers(self):
        self.assertEqual((24, 16, 216, 320, 160, 2, 8, 10, 2800, 200), tuple(CONSTS[self.NAMES[key]] for key in (
            "side_padding", "gutter", "min_card", "max_card", "card_floor", "min_columns", "max_columns_below_wide",
            "max_columns_wide", "wide_from_content_width", "compact_metadata_off_from_card")))

    def test_the_content_floor_is_the_expanded_switch_point_minus_the_footprint(self):
        self.assertEqual(LIMITS["EXPANDED"][0] - FOOTPRINT["expanded"], CONSTS["DESKTOP_HOME_MIN_CONTENT_DP"])

    def test_the_single_column_mode_and_the_legacy_cap_are_the_vendored_values(self):
        text = shell.read(shell.GRID_POLICY)
        self.assertEqual(1, text.count("val isSingleColumnMode = displayMode == %d" % CONSTS["DESKTOP_HOME_SINGLE_COLUMN_DISPLAY_MODE"]))
        self.assertEqual(1, text.count("if (!isSingleColumnMode && fixedColumnCount > 0) {"))
        self.assertEqual(1, text.count("resolveHomeFeedMaxContentWidth(): Dp = %d.dp" % CONSTS["DESKTOP_HOME_LEGACY_CAP_DP"]))

    def test_the_card_presets_of_the_contract_are_the_vendored_ones(self):
        text = shell.read(source.REPO / "app/src/main/java/com/android/purebilibili/core/store/SettingsManager.kt")
        start = text.index("enum class HomeFeedCardWidthPreset(")
        found = [int(n) for n in re.findall(r'\(\d+, "[^"]+", (\d+)\)', text[start:text.index("companion object", start)])]
        self.assertEqual(CONTRACT["feed"]["rules"]["card_presets"], found)


# --------------------------------------------------------------------------------------
# Home density: the mirror against the contract, the spec and the exact reference
# --------------------------------------------------------------------------------------

class HomeMirrorTest(unittest.TestCase):
    def test_every_contract_golden_row(self):
        for row in CONTRACT["feed"]["golden"]:
            width = row["width_dp"]
            layout = shell.window_home_layout_kt(CONSTS, LIMITS, row.get("mode", "AUTO"), width, row.get("previous"), row.get("min_card"))
            expect = row["expect"]
            self.assertEqual((expect["rail"], expect["wc"], expect["cols"], expect["grid"], expect["capped"]),
                             (layout["rail"], layout["wc"], layout["cols"], layout["grid"], layout["capped"]), row["id"])
            self.assertLessEqual(abs(layout["card"] - expect["card"]), 0.05 + 1e-9, row["id"])

    def test_the_spec_table_verbatim(self):
        for width, rail, wc, cols, card, grid in SPEC_FEED_TABLE:
            layout = fresh("AUTO", width)
            self.assertEqual((rail, wc, cols, grid), (layout["rail"], layout["wc"], layout["cols"], layout["grid"]), width)
            self.assertLessEqual(abs(layout["card"] - card), 0.05 + 1e-9, width)
            self.assertEqual(card == 320, layout["capped"], width)

    def test_the_mirror_equals_the_exact_reference_over_the_whole_sweep(self):
        for mode in CONTRACT["feed"]["sweep"]["rail_modes"] + ["CLASSIC_TEXT"]:
            for min_card in MIN_CARDS:
                for width in range(FIRST, LAST + 1):
                    exact = model.feed_layout(CONTRACT, width, mode, None, min_card)
                    got = fresh(mode, width, min_card)
                    label = (mode, min_card, width)
                    self.assertEqual((exact["rail"], exact["wc"], exact["columns"], exact["grid"], exact["capped"]),
                                     (got["rail"], got["wc"], got["cols"], got["grid"], got["capped"]), label)
                    self.assertLessEqual(abs(float(exact["card"]) - got["card"]), 1e-9, label)

    def test_sweep_properties_of_the_spec(self):
        switch = {"AUTO": 1280, "EXPANDED": 1100}
        min_card, max_card = CONSTS["DESKTOP_HOME_MIN_CARD_DP"], CONSTS["DESKTOP_HOME_MAX_CARD_DP"]
        for mode in ("AUTO", "EXPANDED", "COLLAPSED"):
            rows = [(width, fresh(mode, width)) for width in range(FIRST, LAST + 1)]
            drops = []
            for (_w0, before), (w1, after) in zip(rows, rows[1:]):
                if after["cols"] < before["cols"]:
                    drops.append((w1, before["rail"], after["rail"]))
            for _width, row in rows:
                self.assertTrue(min_card <= row["card"] <= max_card, (mode, _width, row["card"]))
            self.assertEqual(1 if mode in switch else 0, len(drops), (mode, drops))
            for width, before, after in drops:
                self.assertNotEqual(before, after)
                self.assertEqual(switch[mode], width)
            last = {}
            for width, row in rows:
                self.assertGreaterEqual(row["cols"], last.get(row["rail"], 0), (mode, width))
                last[row["rail"]] = row["cols"]

    def test_card_presets_are_honoured_with_the_floor(self):
        for preset in (160, 200, 260, 320):
            for mode in ("AUTO", "EXPANDED", "COLLAPSED"):
                for width in range(FIRST, LAST + 1):
                    row = fresh(mode, width, preset)
                    self.assertGreaterEqual(row["card"], min(preset, 320), (preset, mode, width))
                    self.assertLessEqual(row["card"], 320)
        self.assertEqual(fresh("AUTO", 1600, 100)["cols"], fresh("AUTO", 1600, 160)["cols"])
        self.assertGreaterEqual(fresh("AUTO", 1600, 160)["cols"], fresh("AUTO", 1600, 320)["cols"])

    def test_widening_and_narrowing_stay_monotone_inside_a_presentation(self):
        for mode in ("AUTO", "EXPANDED"):
            for widths, sign in ((range(FIRST, LAST + 1), 1), (range(LAST, FIRST - 1, -1), -1)):
                rows = traverse(mode, widths)
                last_cols, last_rail = None, None
                for width, row in rows:
                    if row["rail"] == last_rail:
                        self.assertGreaterEqual(sign * (row["cols"] - last_cols), 0, (mode, width))
                    last_cols, last_rail = row["cols"], row["rail"]
                    self.assertTrue(CONSTS["DESKTOP_HOME_MIN_CARD_DP"] <= row["card"] <= CONSTS["DESKTOP_HOME_MAX_CARD_DP"], (mode, width))

    def test_at_most_eight_columns_below_2800_and_ten_from_2800(self):
        for width in range(FIRST, LAST + 1):
            row = fresh("COLLAPSED", width)
            self.assertLessEqual(row["cols"], 10 if row["wc"] >= 2800 else 8, width)
        below, at = shell.home_density_kt(CONSTS, 2799), shell.home_density_kt(CONSTS, 2800)
        self.assertEqual((8, True), (below[0], below[3]))  # eight capped 320 dp cards
        self.assertEqual((10, False), (at[0], at[3]))  # ten cards of 260.8 dp
        self.assertAlmostEqual(260.8, at[1], places=9)

    def test_a_card_over_the_maximum_is_held_and_the_grid_is_centred(self):
        for mode in ("AUTO", "EXPANDED", "COLLAPSED"):
            for width in range(FIRST, LAST + 1):
                row = fresh(mode, width)
                inset = shell.home_side_inset_kt(row["wc"], row["grid"])
                side = CONSTS["DESKTOP_HOME_SIDE_PADDING_DP"]
                self.assertGreaterEqual(inset, side)
                if row["capped"]:
                    self.assertEqual(320.0, row["card"])
                    self.assertEqual(row["cols"] * 320 + (row["cols"] - 1) * 16, row["grid"])
                    self.assertGreater(inset, side)
                    self.assertLess(row["grid"], row["wc"] - 2 * side)
                else:
                    self.assertEqual(side, inset)
                    self.assertEqual(row["wc"] - 2 * side, row["grid"])
                    self.assertLessEqual(row["card"], 320)
        self.assertEqual(56.0, shell.home_side_inset_kt(2784, 2672))  # the 3000 dp spec row

    def test_the_old_cap_is_gone_from_the_desktop_content_width(self):
        self.assertEqual(1832, shell.home_content_width_kt(CONSTS, 2048, 216, "DESKTOP"))
        self.assertEqual(1280, shell.home_content_width_kt(CONSTS, 2048, 216, "CLASSIC"))
        self.assertEqual(1000, shell.home_content_width_kt(CONSTS, 1000, 72, "CLASSIC"))
        self.assertEqual(928, shell.home_content_width_kt(CONSTS, 1000, 72, "DESKTOP"))
        self.assertGreater(fresh("AUTO", 3000)["grid"], CONTRACT["feed"]["rules"]["dropped_legacy_cap"])

    def test_delegation_cases(self):
        delegate = lambda layout, mode, fixed: shell.home_delegation_kt(CONSTS, layout, mode, fixed)
        cases = (
            (("DESKTOP", 0, 0), None),
            (("DESKTOP", 1, 0), "SINGLE_COLUMN_MODE"),
            (("DESKTOP", 1, 4), "SINGLE_COLUMN_MODE"),  # the vendored function ignores a pinned count in single-column mode
            (("DESKTOP", 0, 4), "PINNED_COLUMN_COUNT"),
            (("DESKTOP", 0, 1), "PINNED_COLUMN_COUNT"),
            (("DESKTOP", 0, -1), None),
            (("DESKTOP", 2, 0), None),
            (("DESKTOP", 2, 3), "PINNED_COLUMN_COUNT"),
            (("CLASSIC", 0, 0), "CLASSIC_LAYOUT"),
            (("CLASSIC", 1, 4), "CLASSIC_LAYOUT"),
            (("CLASSIC", 0, 4), "CLASSIC_LAYOUT"),
        )
        for args, expected in cases:
            self.assertEqual(expected, delegate(*args), args)

    def test_compact_metadata_turns_off_from_a_card_of_200(self):
        compact = lambda original, card: shell.home_compact_metadata_kt(CONSTS, original, card)
        self.assertEqual([True, False, False, False, False], [compact(True, 199.9999), compact(True, 200.0), compact(True, 320.0),
                                                              compact(False, 100.0), compact(False, 199.0)])
        self.assertTrue(compact(True, 160.0))
        # At the default minimum card (216) the metadata is never compact on the desktop grid.
        for mode in ("AUTO", "EXPANDED", "COLLAPSED"):
            for width in range(FIRST, LAST + 1):
                self.assertFalse(compact(True, fresh(mode, width)["card"]), (mode, width))
        self.assertTrue(compact(True, fresh("AUTO", 1280, 160)["card"]))  # a 190.4 dp card keeps compact metadata


# --------------------------------------------------------------------------------------
# The Kotlin source: purity, pins of the mirrored lines, names, line endings
# --------------------------------------------------------------------------------------

KOTLIN_FILES = {"rail": shell.RAIL_KT, "metrics": shell.METRICS_KT, "shortcuts": shell.SHORTCUTS_KT, "strings": shell.STRINGS_KT}
JVM_TESTS = ("DesktopWindowsRailModelTest.kt", "DesktopWindowsLayoutMetricsTest.kt", "DesktopShellShortcutsTest.kt")


class KotlinSourceShapeTest(unittest.TestCase):
    def test_the_files_import_exactly_what_they_need(self):
        imports = lambda text: sorted(re.findall(r"(?m)^import (\S+)$", text))
        self.assertEqual(["com.bilipai.desktop.appearance.DesktopWindowsUxRailMode"], imports(RAIL_TEXT))
        self.assertEqual(["com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout", "kotlin.math.max", "kotlin.math.min"], imports(METRICS_TEXT))
        self.assertEqual([], imports(SHORTCUTS_TEXT))
        self.assertEqual([], imports(STRINGS_TEXT))
        for text in (RAIL_TEXT, METRICS_TEXT, SHORTCUTS_TEXT, STRINGS_TEXT):
            self.assertEqual(1, len(re.findall(r"(?m)^package com\.bilipai\.desktop\.ui$", text)))

    def test_no_ui_toolkit_or_platform_dependency_and_nothing_that_throws(self):
        for name, path in KOTLIN_FILES.items():
            code = source.mask_kotlin(shell.read(path))
            for banned in ("androidx.", "java.awt", "javax.swing", "com.sun.", "kotlinx.coroutines", "@Composable"):
                self.assertFalse(banned in code, "%s mentions %s" % (name, banned))
            for pattern in (r"\bDp\b", r"\bModifier\b", r"\bremember\b", r"\bStateFlow\b", r"\bToolkit\b", r"\bCompositionLocal"):
                self.assertIsNone(re.search(pattern, code), "%s mentions %s" % (name, pattern))
            for pattern in (r"\bthrow\b", r"\berror\(", r"\brequire\(", r"\bcheck\(", r"\brequireNotNull\(", r"!!", r"\bTODO\("):
                self.assertIsNone(re.search(pattern, code), "%s contains %s" % (name, pattern))

    def test_only_the_strings_object_holds_chinese_and_the_others_are_plain_ascii(self):
        self.assertTrue(source.cjk_positions(STRINGS_TEXT))
        for name in ("rail", "metrics", "shortcuts"):
            text = shell.read(KOTLIN_FILES[name])
            self.assertEqual([], source.cjk_positions(text), name)
            self.assertTrue(text.isascii(), name + " holds a non-ASCII character")

    def test_the_marked_blocks_exist_once(self):
        for text, names in ((RAIL_TEXT, ("RAIL-METRICS", "RAIL-THRESHOLDS", "RAIL-FIXED")), (METRICS_TEXT, ("HOME-DENSITY",)),
                            (SHORTCUTS_TEXT, ("SHELL-SHORTCUTS", "SHELL-RESERVED"))):
            for name in names:
                self.assertTrue(shell.marked_block(text, name).strip(), name)

    def test_the_mirrored_rail_lines_are_pinned(self):
        text = shell.squash(RAIL_TEXT)
        for needle in (
            "DesktopWindowsUxRailMode.COLLAPSED -> DesktopRailPresentation.COLLAPSED",
            "DesktopWindowsUxRailMode.CLASSIC_TEXT -> DesktopRailPresentation.CLASSIC",
            "DesktopWindowsUxRailMode.AUTO -> desktopRailWithHysteresis(DESKTOP_RAIL_AUTO_THRESHOLDS, windowWidthDp, previous)",
            "DesktopWindowsUxRailMode.EXPANDED -> desktopRailWithHysteresis(DESKTOP_RAIL_EXPANDED_THRESHOLDS, windowWidthDp, previous)",
            "DesktopRailPresentation.EXPANDED -> windowWidthDp >= limits.collapseBelow",
            "DesktopRailPresentation.COLLAPSED -> windowWidthDp >= limits.reexpandAt",
            "else -> windowWidthDp >= limits.expandAt",
            "return if (expanded) DesktopRailPresentation.EXPANDED else DesktopRailPresentation.COLLAPSED",
            "DESKTOP_RAIL_FIXED_DESTINATIONS.getOrNull(number - 1)",
            "val index = DESKTOP_RAIL_FIXED_DESTINATIONS.indexOf(item) return if (index < 0) null else index + 1",
            "val visible = visibleBottomNavNames.filter { it !in DESKTOP_RAIL_EXCLUDED_BOTTOM_NAV }",
            "item == DesktopRailItem.ACCOUNT_SWITCH -> accountSwitcherAvailable name == null -> true else -> name in visible",
            ".filter { name -> DesktopRailItem.entries.none { it.bottomNavName == name } }",
            "val (browse, rest) = known.partition { it.group == DesktopRailGroup.BROWSE } return browse + unknown + rest",
            "val group: DesktopRailGroup get() = item?.group ?: DesktopRailGroup.BROWSE",  # an unknown item joins the browse group
            "!selected -> DesktopRailClick.NAVIGATE item == DesktopRailItem.HOME -> DesktopRailClick.HOME_DOUBLE_TAP "
            "item == DesktopRailItem.DYNAMIC -> DesktopRailClick.DYNAMIC_DOUBLE_TAP else -> DesktopRailClick.NAVIGATE",
            "unreadCount <= 0 -> null unreadCount > DESKTOP_RAIL_BADGE_CAP -> \"$DESKTOP_RAIL_BADGE_CAP+\" else -> unreadCount.toString()",
            "unreadCount <= 0 -> DesktopRailBadgeKind.NONE presentation == DesktopRailPresentation.COLLAPSED -> DesktopRailBadgeKind.DOT "
            "else -> DesktopRailBadgeKind.PILL",
            "val target = when (presentation) { DesktopRailPresentation.EXPANDED -> DesktopWindowsUxRailMode.COLLAPSED "
            "DesktopRailPresentation.COLLAPSED -> DesktopWindowsUxRailMode.EXPANDED DesktopRailPresentation.CLASSIC -> null } "
            "return if (target == mode) null else target",
            "COLLAPSE(DesktopWindowsUxRailMode.COLLAPSED), EXPAND(DesktopWindowsUxRailMode.EXPANDED), "
            "RESTORE_AUTO(DesktopWindowsUxRailMode.AUTO),",
            "if (presentation == DesktopRailPresentation.CLASSIC) return emptyList() "
            "val toggle = if (presentation == DesktopRailPresentation.EXPANDED) { DesktopRailMenuAction.COLLAPSE } "
            "else { DesktopRailMenuAction.EXPAND } "
            "return listOf(toggle, DesktopRailMenuAction.RESTORE_AUTO).filter { it.mode != mode }",
        ):
            self.assertIn(needle, text, needle)

    def test_the_mirrored_density_lines_are_pinned(self):
        text = shell.squash(METRICS_TEXT)
        for needle in (
            "val usable = contentWidthDp - 2 * DESKTOP_HOME_SIDE_PADDING_DP",
            "val wanted = max(minCardDp ?: DESKTOP_HOME_MIN_CARD_DP, DESKTOP_HOME_CARD_FLOOR_DP)",
            "val columnLimit = if (contentWidthDp >= DESKTOP_HOME_WIDE_FROM_CONTENT_DP) { DESKTOP_HOME_MAX_COLUMNS_WIDE } else { "
            "DESKTOP_HOME_MAX_COLUMNS_BELOW_WIDE }",
            "val columns = ((usable + DESKTOP_HOME_GUTTER_DP) / (wanted + DESKTOP_HOME_GUTTER_DP)) "
            ".coerceIn(DESKTOP_HOME_MIN_COLUMNS, columnLimit)",
            "val room = usable - (columns - 1) * DESKTOP_HOME_GUTTER_DP",
            "return if (room > columns * DESKTOP_HOME_MAX_CARD_DP) {",
            "cardWidthDp = DESKTOP_HOME_MAX_CARD_DP.toDouble(),",
            "gridWidthDp = columns * DESKTOP_HOME_MAX_CARD_DP + (columns - 1) * DESKTOP_HOME_GUTTER_DP,",
            "cardWidthDp = room.toDouble() / columns,",
            "gridWidthDp = usable,",
            # The capped flag belongs to the branch that holds the card at the maximum; the other branch is not capped.
            "gridWidthDp = columns * DESKTOP_HOME_MAX_CARD_DP + (columns - 1) * DESKTOP_HOME_GUTTER_DP, capped = true,",
            "cardWidthDp = room.toDouble() / columns, gridWidthDp = usable, capped = false,",
            "val sideInsetDp: Double get() = (contentWidthDp - gridWidthDp) / 2.0",
            "DesktopWindowsUxHomeLayout.DESKTOP -> windowWidthDp - railFootprintDp",
            "DesktopWindowsUxHomeLayout.CLASSIC -> min(windowWidthDp, DESKTOP_HOME_LEGACY_CAP_DP)",
            "homeLayout == DesktopWindowsUxHomeLayout.CLASSIC -> DesktopHomeDensityDelegation.CLASSIC_LAYOUT "
            "displayMode == DESKTOP_HOME_SINGLE_COLUMN_DISPLAY_MODE -> DesktopHomeDensityDelegation.SINGLE_COLUMN_MODE "
            "fixedColumnCount > 0 -> DesktopHomeDensityDelegation.PINNED_COLUMN_COUNT else -> null",
            "originalCompactMetadata && cardWidthDp < DESKTOP_HOME_COMPACT_METADATA_OFF_FROM_CARD_DP",
        ):
            self.assertIn(needle, text, needle)

    def test_every_top_level_name_is_declared_once_in_the_source_tree(self):
        declared = []
        for path in KOTLIN_FILES.values():
            code = source.mask_kotlin(shell.read(path))
            declared += re.findall(r"(?m)^(?:internal |private )?(?:const val|val|fun|data class|enum class|object|sealed interface) (\w+)", code)
        self.assertEqual(len(declared), len(set(declared)), "a name is declared twice inside the slice")
        pattern = re.compile(r"(?m)^(?:internal |private |public )?(?:const val|val|fun|data class|enum class|object|class|interface|"
                             r"sealed interface) (%s)\b" % "|".join(re.escape(name) for name in declared))
        elsewhere = {}
        for path in sorted(source.K.rglob("*.kt")):
            if path in KOTLIN_FILES.values():
                continue
            for name in pattern.findall(shell.read(path)):
                elsewhere.setdefault(name, []).append(source.rel(path))
        self.assertEqual({}, elsewhere)

    def test_the_jvm_tests_exist_and_every_new_file_is_lf_only(self):
        tests = [source.KT / "ui" / name for name in JVM_TESTS]
        here = Path(__file__).resolve().parent
        source.assert_lf_only(self, *KOTLIN_FILES.values(), *tests, here / "ux_shell_model.py", here / "test_ux_layout_golden.py",
                              here / "test_ux_shortcut_table.py")


if __name__ == "__main__":
    unittest.main()
