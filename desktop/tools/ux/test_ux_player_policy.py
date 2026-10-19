"""Player policy layer of the desktop UX program (slice X1b, spec 4.3 to 4.7, 6.5, 7 and 10.1). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_player_policy.py

What is proved.
  * Layout (ui/DesktopPlayerLayoutPolicy.kt). panelDefaultOpen is a required parameter, decided once at page entry by
    desktopPlayerPanelDefaultOpen, so a resize cannot flip the panel. Every constant equals the contract rule. The transliterated Kotlin resolver
    (ux_player_model.layout_kt, exact Long arithmetic) reproduces all 22 contract golden rows and the spec 4.3 table written
    out again here, and agrees with the exact rational oracle ux_model.player_layout on dense sweeps over window size,
    fullscreen, panel state, user panel width, entry width and video aspect, including every exact rounding tie. Tier edges,
    the once-at-entry panel rule, the fullscreen docking limit and the panel clamps are checked on their own.
  * Chrome (ui/DesktopPlayerChromePolicy.kt, the idleNanos parameter of DesktopWindowsFullscreenChromeState.kt). The numbers
    equal the contract. The hold names are the contract list in order. The set of holds is empty exactly when the existing
    fullscreen rule counts the video as playing, over every PlayerState combination, and the sibling rule equals the existing
    function with fullscreen on. idleNanos keeps 4 s as its default and goes before the clock so that every existing call
    site still compiles.
  * Shortcuts (ui/DesktopPlayerShortcutPolicy.kt). The Kotlin table equals the contract player rows in content, order, status
    and the paused-only mark; the collision rules hold for the shell and player tables together; the resolver mirror agrees
    with an oracle written from the spec prose on every key under all 16 modifier combinations; the Esc chain order is
    spec 6.5 with decision D4; the difference to the vendored key map is written down and pinned.
  * Seek model (ui/DesktopPlayerSeekModel.kt): buffered fraction with null, NaN and infinity, the fine scrub factor (knots of
    the contract, monotone, never below 0.1), chapter gaps.
  * Quality rows (ui/DesktopPlayerQualityRows.kt): the gate thresholds equal the vendored QualitySelectionMenu, the row states
    agree with an oracle built from the vendored hints for every login, VIP and switchable combination, the automatic row. The
    target of the automatic row follows the vendored resolveAutoHighestTargetQuality (account ceiling 129, 80 and 64; 126 only
    with Dolby Vision, 125 only with HDR), checked against an oracle parsed from that function over every combination.
  * Strings: the accessible-name set of spec 7, tooltips that agree with the key table, the dock and flyout labels of spec 4.5 and
    4.9 that the policies need, no forbidden text.
  * Source: the files are pure (no Compose, AWT, Windows or throwing construct), plain LF, with Chinese only in the strings
    object, and no top-level name of the slice is declared anywhere else in desktop/src.

What is not proved. That the Kotlin compiles or runs: the JVM test classes of the slice are the proof, on the Linux compile later.

Scope and deviation (named follow-up slice X1c). Two of the eight policies of the X1b spec row are NOT in this slice, and nothing
here covers them: DesktopPlayerPointerPolicy.kt (the pointer table of spec 6.2, the click state machine of 6.3 with an injected
clock, the wheel accumulator that excludes Ctrl) and DesktopPlayerFlyoutGeometry.kt (flip then clamp on a fake screen and
flyoutHeightDp, spec 4.9), with DesktopPlayerPointerPolicyTest, DesktopPlayerFlyoutGeometryTest and their Python mirrors. The row is
over the slice cap of about 600 changed lines once both are added (the six main Kotlin files here are 630 lines, 437 of them code),
so it is cut at the policy boundary and the two files are slice X1c; neither exists anywhere in the tree. Until X1c lands, the contract blocks
pointer.* (double_click_tolerance_dp, osd_flash_delay_cap_ms, wheel, volume_step, volume_slider) and flyout.* (widths, radius,
padding, row_height, placements, dismiss_reasons, speed, the menu row caps) have no Kotlin consumer and no parity test, and the
decisions D3 (pause at once, undo on a double click) and D13 (the wheel over the video is the volume) are implemented nowhere. What
is here from the pointer block is only the fine-scrub knots and the key seek steps (the Kotlin key table of the shortcuts block).

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in desktop/tools/tests as
spec 8.2 and 9.2 say; Root adds each suite to the validation plan as an explicit `-s desktop/tools/ux -p <file>` line.
"""
import itertools
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_model as model  # noqa: E402
import ux_player_model as pm  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()
LAYOUT_TEXT = pm.read(pm.LAYOUT_KT)
CHROME_TEXT = pm.read(pm.CHROME_KT)
SHORTCUT_TEXT = pm.read(pm.SHORTCUT_KT)
SEEK_TEXT = pm.read(pm.SEEK_KT)
QUALITY_TEXT = pm.read(pm.QUALITY_KT)
STRINGS_TEXT = pm.read(pm.STRINGS_KT)
STATE_TEXT = pm.read(pm.CHROME_STATE_KT)
SHELL_STRINGS_TEXT = pm.read(shell.STRINGS_KT)
SHELL_SHORTCUT_TEXT = pm.read(shell.SHORTCUTS_KT)

C = {}
for _text in (LAYOUT_TEXT, CHROME_TEXT, SEEK_TEXT, QUALITY_TEXT):
    C.update(pm.constants(_text))

PLAYER_ROWS = pm.player_rows(SHORTCUT_TEXT)
PLAYER_TABLE = pm.player_table(PLAYER_ROWS)
SHELL_RESERVED = set(shell.string_set(SHELL_SHORTCUT_TEXT, "DESKTOP_SHELL_RESERVED_CHORDS"))
SHELL_ZOOM = set(shell.string_set(SHELL_SHORTCUT_TEXT, "DESKTOP_SHELL_ZOOM_KEYS"))
CONTRACT_PLAYER_ROWS = [row for row in CONTRACT["shortcuts"]["rows"] if row["scope"] == "player"]


class NeedleCase(unittest.TestCase):
    def assertHas(self, text, *needles):
        """Whitespace-insensitive containment of every needle in the Kotlin text."""
        flat = pm.squash(text)
        for needle in needles:
            self.assertIn(pm.squash(needle), flat, needle)


# Spec 4.3 corrected table, verbatim: W, H, fullscreen, panel state, panel, video w, video h, tier, bars axis, bars.
SPEC_PLAYER_TABLE = (
    (960, 680, False, "AUTO", 0, 960, 540, "L", "top_bottom", 70),
    (960, 680, False, "OPEN", 340, 620, 349, "S", "top_bottom", 166),
    (1088, 720, False, "AUTO", 0, 1088, 612, "L", "top_bottom", 54),
    (1088, 720, False, "OPEN", 340, 748, 421, "M", "top_bottom", 150),
    (1280, 690, False, "AUTO", 340, 940, 529, "M", "top_bottom", 81),
    (2048, 1101, False, "AUTO", 451, 1597, 899, "L", "top_bottom", 101),
    (2048, 1101, False, "CLOSED", 0, 1957, 1101, "L", "left_right", 45),
    (2048, 1152, True, "AUTO", 0, 2048, 1152, "L", "none", 0),
    (2731, 1101, False, "AUTO", 560, 1957, 1101, "L", "left_right", 107),
    (2731, 1152, True, "OPEN", 560, 2048, 1152, "L", "left_right", 62),
    (2731, 1152, True, "CLOSED", 0, 2048, 1152, "L", "left_right", 342),
    (3413, 1440, False, "AUTO", 560, 2560, 1440, "L", "left_right", 146),
)


def layout(width, height, fullscreen=False, panel="AUTO", entry_width=None, user_panel=None, aspect_w=None, aspect_h=None):
    """The layout of a page entered at entry_width dp (the current width unless given), the way the leaf does it: the panel default
    is decided once from the entry width and handed to the resolver, which has no default for it (it is required in Kotlin)."""
    entry = width if entry_width is None else entry_width
    return pm.layout_kt(C, width, height, pm.panel_default_open_kt(C, entry), fullscreen, panel, user_panel, aspect_w, aspect_h)


def row_kwargs(row):
    return dict(fullscreen=row.get("mode") == "fullscreen", panel=row.get("panel", "AUTO"), entry_width=row.get("entry_width"),
                user_panel=row.get("user_panel_width"))


# --------------------------------------------------------------------------------------
# Constants against the contract
# --------------------------------------------------------------------------------------

class ContractNumbersTest(unittest.TestCase):
    def test_every_layout_rule_of_the_contract_has_its_constant(self):
        rules = CONTRACT["player"]["rules"]
        self.assertEqual(rules["aspect"], [C["DESKTOP_PLAYER_ASPECT_WIDTH"], C["DESKTOP_PLAYER_ASPECT_HEIGHT"]])
        self.assertEqual(rules["default_open_from_width"], C["DESKTOP_PLAYER_PANEL_DEFAULT_OPEN_FROM_WIDTH_DP"])
        self.assertEqual(rules["dock_min_window_width"], C["DESKTOP_PLAYER_DOCK_MIN_WINDOW_WIDTH_DP"])
        self.assertEqual(rules["fullscreen_min_aspect"], C["DESKTOP_PLAYER_FULLSCREEN_MIN_ASPECT"])
        self.assertEqual((rules["panel_min"], rules["panel_max"]), (C["DESKTOP_PLAYER_PANEL_MIN_DP"], C["DESKTOP_PLAYER_PANEL_MAX_DP"]))
        self.assertEqual(rules["default_percent"], C["DESKTOP_PLAYER_PANEL_DEFAULT_PERCENT"])
        self.assertEqual(rules["default_clamp"], [C["DESKTOP_PLAYER_PANEL_DEFAULT_LOW_DP"], C["DESKTOP_PLAYER_PANEL_DEFAULT_HIGH_DP"]])
        self.assertEqual(rules["min_stage"], C["DESKTOP_PLAYER_MIN_STAGE_DP"])
        self.assertEqual((rules["tier_l_min"], rules["tier_m_min"]), (C["DESKTOP_PLAYER_TIER_L_MIN_DP"], C["DESKTOP_PLAYER_TIER_M_MIN_DP"]))
        self.assertEqual((rules["dock_capsule_max"], rules["dock_capsule_margin"]),
                         (C["DESKTOP_PLAYER_DOCK_CAPSULE_MAX_DP"], C["DESKTOP_PLAYER_DOCK_CAPSULE_MARGIN_DP"]))

    def test_the_layout_constants_are_the_marked_block_and_nothing_else_is_hidden_there(self):
        block = pm.constants(pm.marked_block(LAYOUT_TEXT, "PLAYER-LAYOUT"))
        self.assertEqual(15, len(block))
        self.assertTrue(set(block) <= set(C))
        self.assertEqual({"DESKTOP_PLAYER_MAX_EXTENT_DP": 100000, "DESKTOP_PLAYER_MAX_ASPECT_PART": 10000},
                         {name: C[name] for name in C if name not in block and name.startswith("DESKTOP_PLAYER_MAX_")})

    def test_every_chrome_number_of_the_contract_has_its_constant(self):
        chrome = CONTRACT["chrome"]
        self.assertEqual(chrome["idle_ms"], C["DESKTOP_PLAYER_CHROME_IDLE_MS"])
        self.assertEqual(chrome["leave_idle_ms"], C["DESKTOP_PLAYER_CHROME_LEAVE_IDLE_MS"])
        self.assertEqual(chrome["fade_out_ms"], C["DESKTOP_PLAYER_CHROME_FADE_OUT_MS"])
        self.assertEqual(chrome["fade_in_ms"], C["DESKTOP_PLAYER_CHROME_FADE_IN_MS"])
        self.assertEqual(chrome["move_threshold_px"], C["DESKTOP_PLAYER_CHROME_MOVE_THRESHOLD_PX"])
        self.assertEqual(chrome["buffering"]["spinner_after_ms"], C["DESKTOP_PLAYER_BUFFERING_SPINNER_AFTER_MS"])
        self.assertEqual(chrome["buffering"]["text_after_ms"], C["DESKTOP_PLAYER_BUFFERING_TEXT_AFTER_MS"])
        self.assertEqual(2_500_000_000, C["DESKTOP_PLAYER_CHROME_IDLE_MS"] * 1_000_000)
        self.assertIn("DESKTOP_PLAYER_CHROME_IDLE_NANOS = DESKTOP_PLAYER_CHROME_IDLE_MS * 1_000_000L", pm.squash(CHROME_TEXT))

    def test_seek_numbers_of_the_contract(self):
        knots = [[x, y] for x, y in re.findall(r"(\d+)\.0 to (\d+\.\d+)", pm.squash(SEEK_TEXT))]
        kotlin_knots = [[int(x), float(y)] for x, y in knots]
        self.assertEqual(CONTRACT["pointer"]["fine_scrub_knots"], kotlin_knots)
        self.assertEqual(CONTRACT["player"]["overlay"]["seek_rail"]["chapter_gap"], C["DESKTOP_PLAYER_CHAPTER_GAP_DP"])

    def test_the_contract_has_fifty_player_rows_and_the_kotlin_table_has_as_many(self):
        self.assertEqual(50, len(CONTRACT_PLAYER_ROWS))
        self.assertEqual(50, len(PLAYER_ROWS))


# --------------------------------------------------------------------------------------
# Layout
# --------------------------------------------------------------------------------------

class LayoutTest(NeedleCase):
    def test_every_golden_row_of_the_contract(self):
        for row in CONTRACT["player"]["golden"]:
            self.assertEqual(row["expect"], layout(row["w"], row["h"], **row_kwargs(row)), row["id"])
        self.assertEqual(22, len(CONTRACT["player"]["golden"]))

    def test_the_spec_table_written_out(self):
        for width, height, fullscreen, panel, panel_dp, video_w, video_h, tier, axis, bar in SPEC_PLAYER_TABLE:
            got = layout(width, height, fullscreen, panel)
            self.assertEqual((panel_dp, [video_w, video_h], tier, {"axis": axis, "size": bar}),
                             (got["panel"], got["video"], got["tier"], got["bars"]), (width, height, panel))
            self.assertEqual(panel_dp > 0, got["docked"], (width, height, panel))

    def test_the_corrected_rows_of_the_spec_at_the_narrow_widths(self):
        # The draft assumed the panel open by default; below 1280 dp the default is closed, and an opened panel is the second row.
        self.assertEqual((0, [960, 540], "L"), tuple(layout(960, 680)[key] for key in ("panel", "video", "tier")))
        self.assertEqual((340, [620, 349], "S"), tuple(layout(960, 680, panel="OPEN")[key] for key in ("panel", "video", "tier")))
        self.assertEqual((340, [748, 421], "M"), tuple(layout(1088, 720, panel="OPEN")[key] for key in ("panel", "video", "tier")))

    def test_mirror_equals_the_exact_oracle_on_a_dense_sweep(self):
        checked = docked = 0
        for width in range(100, 4400, 53):
            for height in (1, 17, 90, 200, 540, 680, 720, 1080, 1101, 1440, 2160):
                for fullscreen in (False, True):
                    for panel in ("AUTO", "OPEN", "CLOSED"):
                        for user in (None, 300, 450, 600):
                            for aspect in ((16, 9), (4, 3), (21, 9)):
                                got = layout(width, height, fullscreen, panel, None, user, *aspect)
                                want = pm.oracle_layout(CONTRACT, width, height, fullscreen, panel, None, user, list(aspect))
                                self.assertEqual(want, got, (width, height, fullscreen, panel, user, aspect))
                                checked += 1
                                docked += got["docked"]
        self.assertGreater(checked, 30000)
        self.assertGreater(docked, 3000)

    def test_mirror_equals_the_oracle_at_every_width_for_two_heights_and_every_rounding_tie(self):
        ties = 0
        for height in (680, 1101):
            for width in range(1, 4400):
                got = layout(width, height)
                self.assertEqual(pm.oracle_layout(CONTRACT, width, height), got, (width, height))
                # A tie is a video height that is exactly n + 1/2: the width-bound case, 9 * width / 16.
                if width < 1280 and width * 9 < height * 16 and (width * 9) % 16 == 8:
                    ties += 1
        self.assertGreater(ties, 20)

    def test_exact_ties_round_half_to_even_not_up(self):
        # 9 * 968 / 16 = 544.5, 9 * 1016 / 16 = 571.5, 9 * 1000 / 16 = 562.5: the even neighbours are 544, 572, 562.
        for width, expected in ((968, 544), (1016, 572), (1000, 562)):
            got = layout(width, 700)
            self.assertEqual(expected, got["video"][1], width)
            self.assertEqual(width, got["video"][0])
        # n / 2 for n = 0, 1, 4, 5, 8, 9 is 0, 0.5, 2, 2.5, 4, 4.5 and rounds to the even 0, 0, 2, 2, 4, 4; 3 / 2 and 7 / 2 go up.
        self.assertEqual([0, 0, 2, 2, 4, 4], [pm.round_half_even_kt(n, 2) for n in (0, 1, 4, 5, 8, 9)])
        self.assertEqual([2, 2, 3, 4], [pm.round_half_even_kt(n, 2) for n in (3, 4, 6, 7)])
        self.assertEqual([1, 2, 2], [pm.round_half_even_kt(n, 3) for n in (4, 5, 6)])

    def test_tier_edges_use_the_exact_video_width(self):
        self.assertEqual(("M", 959), (layout(959, 680)["tier"], layout(959, 680)["video"][0]))
        self.assertEqual("L", layout(960, 680)["tier"])
        self.assertEqual("S", layout(639, 400)["tier"])
        self.assertEqual("M", layout(640, 400)["tier"])
        # 19:10 video, 505 dp high: the exact width is 959.5, which rounds half to even to 960 and is still tier M.
        tie = layout(1000, 505, aspect_w=19, aspect_h=10)
        self.assertEqual(([960, 505], "M"), (tie["video"], tie["tier"]))
        self.assertEqual("L", layout(1000, 506, aspect_w=19, aspect_h=10)["tier"])
        narrow = layout(959, 10000)
        self.assertEqual(("M", 959), (narrow["tier"], narrow["video"][0]))
        self.assertEqual(pm.oracle_layout(CONTRACT, 1000, 505, aspect=[19, 10]), tie)

    def test_the_default_panel_is_decided_once_at_entry_and_never_on_resize(self):
        narrow_then_wide = layout(1600, 900, entry_width=1200)
        self.assertEqual((False, 0), (narrow_then_wide["docked"], narrow_then_wide["panel"]))
        wide_then_narrow = layout(1000, 900, entry_width=1280)
        self.assertTrue(wide_then_narrow["docked"])
        self.assertEqual(0, layout(1600, 900, entry_width=1279)["panel"])
        self.assertTrue(layout(1600, 900, entry_width=1280)["docked"])
        self.assertEqual(layout(1000, 900, panel="OPEN"), layout(1000, 900, panel="OPEN", entry_width=100))
        self.assertEqual(layout(2500, 900, panel="CLOSED"), layout(2500, 900, panel="CLOSED", entry_width=5000))
        self.assertTrue(pm.panel_default_open_kt(C, 1280))
        self.assertFalse(pm.panel_default_open_kt(C, 1279))
        self.assertTrue(pm.panel_wanted_kt("AUTO", pm.panel_default_open_kt(C, 1280)))
        self.assertFalse(pm.panel_wanted_kt("AUTO", pm.panel_default_open_kt(C, 1279)))
        self.assertTrue(pm.panel_wanted_kt("OPEN", False))
        self.assertFalse(pm.panel_wanted_kt("CLOSED", True))

    def test_a_remembered_default_keeps_the_panel_across_every_resize(self):
        wide_entry = pm.panel_default_open_kt(C, 1400)
        narrow_entry = pm.panel_default_open_kt(C, 900)
        self.assertEqual((True, False), (wide_entry, narrow_entry))
        for width in range(800, 4001, 37):
            self.assertTrue(pm.layout_kt(C, width, 900, wide_entry)["docked"], width)
        for width in range(640, 4001, 37):
            self.assertFalse(pm.layout_kt(C, width, 900, narrow_entry)["docked"], width)

    def test_the_resolver_takes_the_remembered_default_as_a_required_parameter_and_no_entry_width(self):
        self.assertHas(
            LAYOUT_TEXT,
            "internal fun resolveDesktopPlayerLayout( widthDp: Int, heightDp: Int, panelDefaultOpen: Boolean, fullscreen: Boolean = false,",
            "internal fun desktopPlayerPanelWanted(panel: DesktopWindowsUxDetailsPanel, panelDefaultOpen: Boolean): Boolean",
            "DesktopWindowsUxDetailsPanel.AUTO -> panelDefaultOpen",
        )
        self.assertNotIn("panelDefaultOpen: Boolean =", pm.squash(LAYOUT_TEXT))  # no default value, so a caller cannot omit it
        # The only code that still names an entry width is the one function that decides the default at page entry.
        lines = [line.strip() for line in source.mask_kotlin(LAYOUT_TEXT).splitlines() if "entryWidthDp" in line]
        self.assertEqual(["internal fun desktopPlayerPanelDefaultOpen(entryWidthDp: Int): Boolean = "
                          "entryWidthDp >= DESKTOP_PLAYER_PANEL_DEFAULT_OPEN_FROM_WIDTH_DP"], lines)

    def test_the_panel_docks_windowed_from_640_and_in_fullscreen_only_from_an_aspect_of_two(self):
        self.assertFalse(layout(639, 400, panel="OPEN")["docked"])
        self.assertTrue(layout(800, 400, panel="OPEN")["docked"])
        self.assertTrue(layout(2304, 1152, True, "OPEN")["docked"])
        self.assertFalse(layout(2303, 1152, True, "OPEN")["docked"])
        self.assertFalse(layout(1920, 1080, True, "OPEN")["docked"])
        self.assertTrue(layout(3440, 1440, True, "OPEN")["docked"])

    def test_the_panel_width_clamps_and_the_stage_stays_wide_enough(self):
        self.assertEqual(320, layout(1600, 900, panel="OPEN", user_panel=100)["panel"])
        self.assertEqual(560, layout(2000, 1000, panel="OPEN", user_panel=900)["panel"])
        self.assertEqual(400, layout(2000, 1000, panel="OPEN", user_panel=400)["panel"])
        # width - 480 binds: a 1000 dp window leaves 520 for a 560 dp panel request.
        self.assertEqual(520, layout(1000, 600, panel="OPEN", user_panel=560)["panel"])
        # Below 320 after that cut the panel does not dock.
        self.assertEqual((False, 0), tuple(layout(790, 600, panel="OPEN", user_panel=560)[k] for k in ("docked", "panel")))
        self.assertEqual((True, 320), tuple(layout(800, 600, panel="OPEN", user_panel=560)[k] for k in ("docked", "panel")))
        for width in range(640, 4400, 31):
            for panel in (layout(width, 800, panel="OPEN"), layout(width, 800, panel="OPEN", user_panel=560)):
                self.assertTrue(panel["panel"] == 0 or 320 <= panel["panel"] <= min(560, width - 480), width)

    def test_extra_width_goes_to_the_panel_first_then_to_the_margins(self):
        wide = layout(3000, 1000, panel="OPEN")
        self.assertEqual(560, wide["panel"])
        self.assertEqual("left_right", wide["bars"]["axis"])
        self.assertEqual(1000, wide["video"][1])
        height_bound = layout(3000, 1000, panel="CLOSED")
        self.assertEqual([1778, 1000], height_bound["video"])

    def test_the_dock_capsule_width(self):
        self.assertEqual(1280, layout(2048, 1101)["dock"])
        self.assertEqual(928, layout(960, 680)["dock"])
        self.assertEqual(0, layout(300, 10)["dock"])  # a video narrower than the margin has no negative dock
        self.assertEqual(0, layout(1, 1)["dock"])
        for width in range(100, 4400, 61):
            for height in (90, 700, 1101):
                got = layout(width, height)
                self.assertTrue(0 <= got["dock"] <= 1280, (width, height))

    def test_invariants_of_every_layout_of_the_sweep(self):
        for width in range(100, 4400, 41):
            for height in (60, 400, 680, 1101, 1440):
                for fullscreen, panel in itertools.product((False, True), ("AUTO", "OPEN", "CLOSED")):
                    got = layout(width, height, fullscreen, panel)
                    video_w, video_h = got["video"]
                    label = (width, height, fullscreen, panel)
                    self.assertTrue(video_w <= width and video_h <= height, label)
                    self.assertTrue(video_w + got["panel"] <= width + 1, label)
                    if got["docked"]:
                        self.assertTrue(got["panel"] >= 320 and width - got["panel"] >= 480 - 1, label)
                        self.assertTrue(width >= 2 * height if fullscreen else width >= 640, label)
                    if got["bars"]["axis"] == "left_right":
                        self.assertEqual(height, video_h, label)
                    if got["bars"]["axis"] == "top_bottom":
                        self.assertEqual(width - got["panel"], video_w, label)
                    if got["tier"] == "L":
                        self.assertGreaterEqual(video_w, 960, label)
                    if got["tier"] == "S":
                        self.assertLessEqual(video_w, 640, label)

    def test_bad_input_gives_the_empty_layout_and_a_bad_aspect_the_default_aspect(self):
        for width, height in ((0, 700), (700, 0), (-5, 700), (100001, 700), (700, 100001)):
            self.assertEqual(pm.EMPTY_LAYOUT, layout(width, height), (width, height))
        self.assertEqual(layout(1000, 700), layout(1000, 700, aspect_w=0, aspect_h=9))
        self.assertEqual(layout(1000, 700), layout(1000, 700, aspect_w=16, aspect_h=-1))
        self.assertEqual(layout(1000, 700), layout(1000, 700, aspect_w=10001, aspect_h=9))
        self.assertNotEqual(layout(1000, 700), layout(1000, 700, aspect_w=4, aspect_h=3))

    def test_no_product_of_the_largest_input_overflows_a_long(self):
        limit, part = C["DESKTOP_PLAYER_MAX_EXTENT_DP"], C["DESKTOP_PLAYER_MAX_ASPECT_PART"]
        d = 100 * part  # the largest common denominator
        products = {
            "w * d": limit * d,
            "stageN * ah": limit * d * part,
            "h * aw * d": limit * part * d,
            "h * d * aw": limit * d * part,
            "2 * d * ah": 2 * d * part,
            "dock max * videoWidthD": C["DESKTOP_PLAYER_DOCK_CAPSULE_MAX_DP"] * d * part,
            "tier edge * videoWidthD": C["DESKTOP_PLAYER_TIER_L_MIN_DP"] * d * part,
        }
        for name, value in products.items():
            self.assertLess(value, 2 ** 63, name)
        self.assertLess(max(products.values()) * 1000, 2 ** 63)  # three orders of magnitude to spare

    def test_the_kotlin_resolver_lines_are_pinned(self):
        self.assertHas(
            LAYOUT_TEXT,
            "val d = 100L * ah",
            "val allowed = if (fullscreen) w >= DESKTOP_PLAYER_FULLSCREEN_MIN_ASPECT * h else w >= DESKTOP_PLAYER_DOCK_MIN_WINDOW_WIDTH_DP",
            "if (allowed && desktopPlayerPanelWanted(panel, panelDefaultOpen)) {",
            "userPanelWidthDp.coerceIn(DESKTOP_PLAYER_PANEL_MIN_DP, DESKTOP_PLAYER_PANEL_MAX_DP) * d",
            "val share = (DESKTOP_PLAYER_PANEL_DEFAULT_PERCENT * w * ah) "
            ".coerceIn(DESKTOP_PLAYER_PANEL_DEFAULT_LOW_DP * d, DESKTOP_PLAYER_PANEL_DEFAULT_HIGH_DP * d)",
            "minOf(DESKTOP_PLAYER_PANEL_MAX_DP * d, maxOf(share, w * d - h * aw * 100L))",
            "val fitted = minOf(candidate, (w - DESKTOP_PLAYER_MIN_STAGE_DP) * d)",
            "if (fitted >= DESKTOP_PLAYER_PANEL_MIN_DP * d) panelN = fitted",
            "val stageN = w * d - panelN",
            "val heightBound = stageN * ah >= h * aw * d",
            "val videoWidthN = if (heightBound) h * aw else stageN",
            "val videoWidthD = if (heightBound) ah else d",
            "val videoHeightN = if (heightBound) h else stageN * ah",
            "val videoHeightD = if (heightBound) 1L else d * aw",
            "val sideN = if (heightBound) stageN * ah - h * aw * d else 0L",
            "val verticalN = if (heightBound) 0L else h * d * aw - stageN * ah",
            "sideN > 0L -> DesktopPlayerBarAxis.LEFT_RIGHT to desktopPlayerRoundHalfEven(sideN, 2L * d * ah)",
            "verticalN > 0L -> DesktopPlayerBarAxis.TOP_BOTTOM to desktopPlayerRoundHalfEven(verticalN, 2L * d * aw)",
            "videoWidthN >= DESKTOP_PLAYER_TIER_L_MIN_DP * videoWidthD -> DesktopPlayerTier.L",
            "videoWidthN >= DESKTOP_PLAYER_TIER_M_MIN_DP * videoWidthD -> DesktopPlayerTier.M",
            "DESKTOP_PLAYER_DOCK_CAPSULE_MAX_DP * videoWidthD, videoWidthN - DESKTOP_PLAYER_DOCK_CAPSULE_MARGIN_DP * videoWidthD, ).coerceAtLeast(0L)",
            "docked = panelN > 0L,",
            "twiceRemainder < denominator -> quotient twiceRemainder > denominator -> quotient + 1L "
            "else -> if (quotient % 2L == 0L) quotient else quotient + 1L",
            "DesktopWindowsUxDetailsPanel.OPEN -> true DesktopWindowsUxDetailsPanel.CLOSED -> false "
            "DesktopWindowsUxDetailsPanel.AUTO -> panelDefaultOpen",
            "internal fun desktopPlayerPanelDefaultOpen(entryWidthDp: Int): Boolean = "
            "entryWidthDp >= DESKTOP_PLAYER_PANEL_DEFAULT_OPEN_FROM_WIDTH_DP",
        )

    def test_no_floating_point_is_involved_in_the_layout(self):
        code = source.mask_kotlin(LAYOUT_TEXT)
        for banned in ("Double", "Float", ".toDouble", ".toFloat", "roundToInt", "kotlin.math", "round("):
            self.assertNotIn(banned, code, banned)

    def test_the_bar_axis_names_are_the_contract_keys(self):
        names = re.findall(r'([A-Z_]+)\("([a-z_]+)"\)', LAYOUT_TEXT[LAYOUT_TEXT.index("enum class DesktopPlayerBarAxis"):])
        self.assertEqual([("NONE", "none"), ("LEFT_RIGHT", "left_right"), ("TOP_BOTTOM", "top_bottom")], names)
        axes = {row["expect"]["bars"]["axis"] for row in CONTRACT["player"]["golden"]}
        self.assertEqual({"none", "left_right", "top_bottom"}, axes)
        self.assertEqual(["L", "M", "S"], pm.enum_entries(LAYOUT_TEXT, "DesktopPlayerTier"))


# --------------------------------------------------------------------------------------
# Chrome
# --------------------------------------------------------------------------------------

BOOLEAN_FIELDS = ("ready", "loading", "paused", "ended", "audio_only", "first_frame", "paused_for_cache", "has_error")


def all_states():
    for values in itertools.product((False, True), repeat=len(BOOLEAN_FIELDS)):
        for native_paused in (False, True, None):
            state = dict(zip(BOOLEAN_FIELDS, values))
            state["native_paused"] = native_paused
            yield state


class ChromeTest(NeedleCase):
    HOLD_ORDER = pm.enum_entries(CHROME_TEXT, "DesktopPlayerChromeHold")

    def test_the_hold_names_are_the_contract_list_in_order(self):
        self.assertEqual(CONTRACT["chrome"]["hold_while"], self.HOLD_ORDER)

    def test_playback_holds_equal_the_oracle_on_every_player_state(self):
        count = 0
        for state in all_states():
            for problem in (False, True):
                self.assertEqual(pm.oracle_holds(state, problem), set(pm.playback_holds_kt(state, problem)), (state, problem))
                count += 1
        self.assertEqual(256 * 3 * 2, count)

    def test_no_playback_hold_exactly_when_the_existing_rule_counts_the_video_as_playing(self):
        for state in all_states():
            for problem in (False, True):
                existing = pm.existing_can_auto_hide(True, True, True, False, state, problem)
                self.assertEqual(existing, not pm.playback_holds_kt(state, problem), (state, problem))

    def test_the_sibling_rule_equals_the_existing_one_with_fullscreen_on_and_ignores_fullscreen(self):
        for state in all_states():
            for problem in (False, True):
                for active, focused, held in itertools.product((False, True), repeat=3):
                    existing = pm.existing_can_auto_hide(True, active, focused, held, state, problem)
                    self.assertEqual(existing, pm.chrome_can_auto_hide_kt(active, focused, held, state, problem),
                                     (state, problem, active, focused, held))
        self.assertFalse(pm.existing_can_auto_hide(False, True, True, False, pm.default_state(), False))
        self.assertTrue(pm.chrome_can_auto_hide_kt(True, True, False, pm.default_state(), False))

    def test_the_existing_fullscreen_rule_is_the_one_that_was_mirrored(self):
        self.assertHas(
            STATE_TEXT,
            "): Boolean = fullscreen && active && windowFocused && !interactionHeld && !playbackProblem && "
            "shouldAutoHideFullscreenControls(showControls = true, gestureMode = FullscreenGestureMode.None, "
            "isPlaying = state.ready && state.firstVideoFrameReady && state.nativePaused == false && !state.audioOnly && "
            "!state.loading && !state.paused && !state.ended && !state.pausedForCache && state.error == null)",
        )
        vendored = pm.read(source.REPO / "app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/FullscreenPlayerOverlayPollingPolicy.kt")
        self.assertHas(vendored, "return showControls && gestureMode == FullscreenGestureMode.None && isPlaying")

    def test_every_hold_has_a_reason_and_the_interaction_holds_follow_the_playback_ones(self):
        playing = pm.default_state()
        every = {"flyout_open": True, "scrubbing": True, "dragging_volume": True, "dock_hovered": True, "top_bar_hovered": True}
        self.assertEqual([], pm.chrome_holds_kt(self.HOLD_ORDER, playing, False, {}))
        self.assertEqual(["FLYOUT_OPEN", "SCRUBBING", "DRAGGING_VOLUME", "DOCK_HOVERED", "TOP_BAR_HOVERED"],
                         pm.chrome_holds_kt(self.HOLD_ORDER, playing, False, every))
        broken = pm.default_state(paused=True, ended=True, audio_only=True, has_error=True, loading=True)
        self.assertEqual(self.HOLD_ORDER, pm.chrome_holds_kt(self.HOLD_ORDER, broken, False, every))
        for name, key in (("FLYOUT_OPEN", "flyout_open"), ("SCRUBBING", "scrubbing"), ("DRAGGING_VOLUME", "dragging_volume"),
                          ("DOCK_HOVERED", "dock_hovered"), ("TOP_BAR_HOVERED", "top_bar_hovered")):
            self.assertEqual([name], pm.chrome_holds_kt(self.HOLD_ORDER, playing, False, {key: True}))
        self.assertEqual(["ERRORED"], pm.chrome_holds_kt(self.HOLD_ORDER, playing, True, {}))
        self.assertEqual(["PAUSED"], pm.chrome_holds_kt(self.HOLD_ORDER, pm.default_state(native_paused=True), False, {}))
        self.assertEqual(["BUFFERING"], pm.chrome_holds_kt(self.HOLD_ORDER, pm.default_state(native_paused=None), False, {}))

    def test_the_leave_idle_move_threshold_cursor_and_buffering_rules(self):
        self.assertEqual(600, pm.remaining_idle_ms_kt(C, 2500, True))
        self.assertEqual(300, pm.remaining_idle_ms_kt(C, 300, True))
        self.assertEqual(2500, pm.remaining_idle_ms_kt(C, 2500, False))
        self.assertEqual(0, pm.remaining_idle_ms_kt(C, 0, True))
        self.assertEqual([False, False, True, True, True, True, False],
                         [pm.move_counts_kt(C, dx, dy) for dx, dy in ((0, 0), (1, 1), (2, 0), (0, -2), (-2, 0), (1, 2), (1, 0))])
        self.assertTrue(pm.cursor_hidden_kt(False, True))
        self.assertFalse(pm.cursor_hidden_kt(True, True))
        self.assertFalse(pm.cursor_hidden_kt(False, False))
        self.assertEqual(["NONE", "NONE", "SPINNER", "SPINNER", "SPINNER_WITH_TEXT", "SPINNER_WITH_TEXT"],
                         [pm.buffering_kt(C, ms) for ms in (0, 399, 400, 4999, 5000, 60000)])
        self.assertEqual(["NONE", "SPINNER", "SPINNER_WITH_TEXT"], pm.enum_entries(CHROME_TEXT, "DesktopPlayerBufferingPresentation"))

    def test_the_kotlin_chrome_lines_are_pinned(self):
        self.assertHas(
            CHROME_TEXT,
            "if (state.paused || state.nativePaused == true) holds.add(DesktopPlayerChromeHold.PAUSED)",
            "if (state.loading || state.pausedForCache || !state.ready || !state.firstVideoFrameReady || state.nativePaused == null) {",
            "holds.add(DesktopPlayerChromeHold.BUFFERING)",
            "if (state.ended) holds.add(DesktopPlayerChromeHold.ENDED)",
            "if (state.error != null || playbackProblem) holds.add(DesktopPlayerChromeHold.ERRORED)",
            "if (state.audioOnly) holds.add(DesktopPlayerChromeHold.AUDIO_ONLY)",
            "DesktopPlayerChromeHold.entries.filterTo(linkedSetOf<DesktopPlayerChromeHold>()) { it in playback || flags[it] == true }",
            "): Boolean = active && windowFocused && !interactionHeld && desktopPlayerPlaybackHolds(state, playbackProblem).isEmpty()",
            "if (pointerLeftCarrier) minOf(remainingMs, DESKTOP_PLAYER_CHROME_LEAVE_IDLE_MS) else remainingMs",
            "dxPx.toLong() * dxPx + dyPx.toLong() * dyPx >= threshold * threshold",
            "!chromeVisible && pointerOverVideo",
            "bufferingMs >= DESKTOP_PLAYER_BUFFERING_TEXT_AFTER_MS -> DesktopPlayerBufferingPresentation.SPINNER_WITH_TEXT "
            "bufferingMs >= DESKTOP_PLAYER_BUFFERING_SPINNER_AFTER_MS -> DesktopPlayerBufferingPresentation.SPINNER "
            "else -> DesktopPlayerBufferingPresentation.NONE",
        )

    def test_the_interaction_fields_are_the_five_hold_names_and_default_off(self):
        self.assertHas(
            CHROME_TEXT,
            "internal data class DesktopPlayerChromeInteraction( val flyoutOpen: Boolean = false, val scrubbing: Boolean = false, "
            "val draggingVolume: Boolean = false, val dockHovered: Boolean = false, val topBarHovered: Boolean = false, )",
            "DesktopPlayerChromeHold.FLYOUT_OPEN to interaction.flyoutOpen, DesktopPlayerChromeHold.SCRUBBING to interaction.scrubbing, "
            "DesktopPlayerChromeHold.DRAGGING_VOLUME to interaction.draggingVolume, DesktopPlayerChromeHold.DOCK_HOVERED to "
            "interaction.dockHovered, DesktopPlayerChromeHold.TOP_BAR_HOVERED to interaction.topBarHovered,",
        )

    def test_idle_nanos_keeps_four_seconds_as_its_default_and_comes_before_the_clock(self):
        self.assertHas(
            STATE_TEXT,
            "internal class DesktopWindowsFullscreenChromeState( private val idleNanos: Long = DEFAULT_IDLE_NANOS, "
            "private val clockNanos: () -> Long = System::nanoTime, ) {",
            "val remaining = (idleNanos - elapsed).coerceAtLeast(0L)",
            "companion object { internal const val DEFAULT_IDLE_NANOS = 4_000_000_000L }",
        )
        self.assertEqual(CONTRACT["chrome"]["legacy_fullscreen_idle_ms"] * 1_000_000, 4_000_000_000)
        self.assertNotIn("IDLE_NANOS =", STATE_TEXT.replace("DEFAULT_IDLE_NANOS =", ""))
        # The parameter is spelled in the class comment, its declaration and the one place that reads it.
        self.assertEqual(3, len(re.findall(r"\bidleNanos\b", STATE_TEXT)))
        # The constant is read once, as the parameter default, and declared once.
        self.assertEqual(2, len(re.findall(r"\bDEFAULT_IDLE_NANOS\b", source.mask_kotlin(STATE_TEXT))))

    def test_every_construction_of_the_state_in_the_tree_still_compiles_with_the_new_parameter_order(self):
        # A trailing lambda binds to the last parameter (the clock), so a call with only a lambda, with no argument, or with
        # idleNanos named or first stays valid; a call that passes the clock positionally in the first slot would not.
        pattern = re.compile(r"DesktopWindowsFullscreenChromeState(?!\w)\s*(\(|\{)([^)\n{]*)")
        seen = 0
        for path in sorted((source.REPO / "desktop/src").rglob("*.kt")):
            text = source.mask_kotlin(pm.read(path))  # comments and string contents do not construct anything
            for match in pattern.finditer(text):
                if "class DesktopWindowsFullscreenChromeState" in text[max(0, match.start() - 20):match.end()]:
                    continue
                seen += 1
                if match.group(1) == "(":
                    self.assertIsNotNone(re.fullmatch(r"|idleNanos\s*=.*|DESKTOP_PLAYER_CHROME_IDLE_NANOS", match.group(2).strip()),
                                         "%s: %s" % (path.name, match.group(0)))
        self.assertGreaterEqual(seen, 7)

    def test_remaining_idle_millis_with_the_default_and_with_the_player_idle(self):
        legacy = CONTRACT["chrome"]["legacy_fullscreen_idle_ms"] * 1_000_000
        player = C["DESKTOP_PLAYER_CHROME_IDLE_MS"] * 1_000_000
        # The existing JVM test: one nanosecond short of four seconds leaves 1 ms, four seconds leaves none, three seconds 1000.
        self.assertEqual([1, 0, 1000, 4000], [pm.remaining_idle_millis_kt(legacy, e) for e in (3_999_999_999, 4_000_000_000, 3_000_000_000, 0)])
        self.assertEqual([1, 0, 1, 2500], [pm.remaining_idle_millis_kt(player, e) for e in (2_499_999_999, 2_500_000_000, 2_499_000_001, 0)])
        self.assertEqual(4000, pm.remaining_idle_millis_kt(legacy, -5))  # a clock that goes back does not lengthen the wait


# --------------------------------------------------------------------------------------
# Shortcuts
# --------------------------------------------------------------------------------------

KEYS = sorted({model.parse_chord(row["chord"])[1] for row in CONTRACT["shortcuts"]["rows"]}
              | set("ABCDEFGHIJKLMNOPQRSTUVWXYZ") | set("0123456789") | {"F%d" % n for n in range(1, 13)}
              | {"Numpad%d" % n for n in range(10)}
              | {"Plus", "Equals", "Add", "Minus", "Subtract", "Enter", "Backspace", "Tab", "Delete", "PageUp", "PageDown",
                 "Insert", "Menu", "Period", "Slash", "Comma", "Space", "Esc", "Home", "End"})


def mirror(key, ctrl=False, alt=False, shift=False, meta=False, key_down=True, text_editor=False, paused=False):
    return pm.resolve_player_key_kt(PLAYER_TABLE, SHELL_RESERVED, SHELL_ZOOM, key, ctrl, alt, shift, meta, key_down, text_editor, paused)


def oracle(key, ctrl=False, alt=False, shift=False, meta=False, key_down=True, text_editor=False, paused=False):
    return pm.oracle_player_resolve(CONTRACT, key, ctrl, alt, shift, meta, key_down, text_editor, paused)


class ShortcutTest(NeedleCase):
    def test_the_kotlin_table_equals_the_contract_player_rows_in_content_order_and_status(self):
        status = {"existing-at-root": "EXISTING_AT_ROOT", "omitted-until-mpv-api": "OMITTED_UNTIL_MPV_API"}
        expected = [(row["chord"], row["action"], row.get("arg", 0), status.get(row.get("status"), "ACTIVE"), row.get("when") == "paused")
                    for row in CONTRACT_PLAYER_ROWS]
        self.assertEqual(expected, PLAYER_ROWS)

    def test_the_digit_rows_are_generated_over_zero_to_nine_times_ten(self):
        self.assertEqual(1, pm.squash(SHORTCUT_TEXT).count(pm.squash(pm.GENERATED_DIGITS)))
        digits = [row for row in PLAYER_ROWS if row[1] == "SEEK_PERCENT"]
        self.assertEqual([(str(n), "SEEK_PERCENT", n * 10, "ACTIVE", False) for n in range(10)], digits)

    def test_every_action_kind_of_the_contract_is_an_enum_entry_and_back(self):
        kinds = pm.enum_entries(SHORTCUT_TEXT, "DesktopPlayerActionKind")
        self.assertEqual(sorted({row["action"] for row in CONTRACT_PLAYER_ROWS}), sorted(kinds))
        self.assertEqual(len(kinds), len(set(kinds)))
        self.assertEqual(["ACTIVE", "EXISTING_AT_ROOT", "OMITTED_UNTIL_MPV_API"], pm.enum_entries(SHORTCUT_TEXT, "DesktopPlayerShortcutStatus"))
        self.assertEqual(["CLOSE_POPUP", "LEAVE_FULLSCREEN", "LEAVE_PAGE", "NONE"], pm.enum_entries(SHORTCUT_TEXT, "DesktopPlayerEscStep"))

    def test_the_collision_rules_hold_for_the_shell_and_player_tables_together(self):
        rows = [{"scope": "shell", "chord": chord, "action": kind} for chord, kind, *_rest in shell.shortcut_rows(SHELL_SHORTCUT_TEXT)]
        rows += [{"scope": "player", "chord": chord, "action": kind} for chord, kind, *_rest in PLAYER_ROWS]
        table = CONTRACT["shortcuts"]
        args = (SHELL_ZOOM, table["reserved"], table["not_taken"], table["dual_meaning"])
        self.assertEqual([], shell.shortcut_violations(rows, *args))
        broken = rows + [{"scope": "player", "chord": "Space", "action": "PLAY_PAUSE"}]
        self.assertEqual(["duplicate"], sorted({code for code, _row in shell.shortcut_violations(broken, *args)}))
        zoom = rows + [{"scope": "player", "chord": "Ctrl+Plus", "action": "SEEK"}]
        self.assertIn("zoom-overlap", {code for code, _row in shell.shortcut_violations(zoom, *args)})
        enter = rows + [{"scope": "player", "chord": "Enter", "action": "FULLSCREEN_TOGGLE"}]
        self.assertIn("not-taken", {code for code, _row in shell.shortcut_violations(enter, *args)})

    def test_the_resolver_reuses_the_shell_reserved_and_zoom_sets_and_the_chord_builder(self):
        self.assertHas(
            SHORTCUT_TEXT,
            "if (event.ctrl && key in DESKTOP_SHELL_ZOOM_KEYS) return null",
            "val chord = desktopShellChord(event.copy(key = key))",
            "if (chord in DESKTOP_SHELL_RESERVED_CHORDS) return null",
        )
        self.assertEqual(set(CONTRACT["shortcuts"]["reserved"]) | set(CONTRACT["shortcuts"]["not_taken"]), SHELL_RESERVED)
        self.assertEqual(set(CONTRACT["shortcuts"]["zoom_decoder_keys"]), SHELL_ZOOM)

    def test_the_mirror_equals_the_oracle_on_every_key_modifier_and_state(self):
        checked = claimed = 0
        for key in KEYS:
            for ctrl, alt, shift, meta in itertools.product((False, True), repeat=4):
                for text_editor, key_down, paused in itertools.product((False, True), repeat=3):
                    args = (key, ctrl, alt, shift, meta, key_down, text_editor, paused)
                    got = mirror(*args)
                    self.assertEqual(oracle(*args), got, args)
                    checked += 1
                    claimed += got is not None
        self.assertGreater(checked, 10000)
        self.assertGreater(claimed, 100)

    def test_every_active_row_resolves_to_its_action(self):
        for chord, kind, arg, status, _paused in PLAYER_ROWS:
            mods, key = model.parse_chord(chord)
            result = mirror(key, "Ctrl" in mods, "Alt" in mods, "Shift" in mods, paused=True)
            self.assertEqual((kind, arg) if status == "ACTIVE" else None, result, chord)

    def test_a_text_editor_owning_focus_stops_every_chord_and_a_release_is_never_claimed(self):
        for chord, *_rest in PLAYER_ROWS:
            mods, key = model.parse_chord(chord)
            args = ("Ctrl" in mods, "Alt" in mods, "Shift" in mods)
            self.assertIsNone(mirror(key, *args, text_editor=True, paused=True), chord)
            self.assertIsNone(mirror(key, *args, key_down=False, paused=True), chord)

    def test_altgr_and_the_windows_key_are_never_claimed(self):
        for chord, *_rest in PLAYER_ROWS:
            mods, key = model.parse_chord(chord)
            self.assertIsNone(mirror(key, True, True, "Shift" in mods), chord + " with Ctrl+Alt")
            self.assertIsNone(mirror(key, "Ctrl" in mods, "Alt" in mods, "Shift" in mods, meta=True), chord + " with Meta")

    def test_the_player_keys_of_the_spec(self):
        expect = {
            "Space": ("PLAY_PAUSE", 0), "K": ("PLAY_PAUSE", 0), "J": ("SEEK", -10), "L": ("SEEK", 10), "M": ("MUTE", 0),
            "F": ("FULLSCREEN_TOGGLE", 0), "T": ("SIDE_PANEL_OR_CINEMA", 0), "P": ("MINI_PLAYER", 0), "I": ("VIDEO_INFO", 0),
            "C": ("SUBTITLE_TOGGLE", 0), "D": ("DANMAKU_TOGGLE", 0), "S": ("SCREENSHOT", 0), "Home": ("SEEK_START", 0),
            "End": ("SEEK_END", 0), "Left": ("SEEK", -5), "Right": ("SEEK", 5), "Up": ("VOLUME", 5), "Down": ("VOLUME", -5),
            "Esc": ("ESC_CHAIN", 0), "Menu": ("CONTEXT_MENU", 0), "LeftBracket": ("PART", -1), "RightBracket": ("PART", 1),
        }
        for key, want in expect.items():
            self.assertEqual(want, mirror(key), key)
        self.assertEqual(("SEEK", -10), mirror("Left", shift=True))
        self.assertEqual(("SEEK", 10), mirror("Right", shift=True))
        self.assertEqual(("CHAPTER", -1), mirror("Left", ctrl=True))
        self.assertEqual(("CHAPTER", 1), mirror("Right", ctrl=True))
        self.assertEqual(("SIDE_PANEL_OR_CINEMA", 0), mirror("B", ctrl=True))
        self.assertEqual(("FULLSCREEN_TOGGLE", 0), mirror("Enter", alt=True))
        self.assertEqual(("SUBTITLE_FLYOUT", 0), mirror("C", shift=True))
        self.assertEqual(("PART", -1), mirror("P", shift=True))
        self.assertEqual(("PART", 1), mirror("N", shift=True))
        self.assertEqual(("SPEED_STEP", -1), mirror("Comma", shift=True))
        self.assertEqual(("SPEED_STEP", 1), mirror("Period", shift=True))
        self.assertEqual(("SPEED_SET", 1), mirror("1", shift=True))
        self.assertEqual(("SPEED_SET", 2), mirror("2", shift=True))
        self.assertEqual(("CONTEXT_MENU", 0), mirror("F10", shift=True))
        self.assertEqual(("SHORTCUT_HELP", 0), mirror("Slash", shift=True))
        for digit in range(10):
            self.assertEqual(("SEEK_PERCENT", digit * 10), mirror(str(digit)))
        self.assertEqual(("SEEK_PERCENT", 30), mirror("3"))
        self.assertIsNone(mirror("3", shift=True))
        self.assertIsNone(mirror("3", ctrl=True))

    def test_the_keys_that_are_not_taken_or_not_ours(self):
        for key in ("Q", "W", "E", "R"):  # the engagement keys are a non-goal (spec 1.5)
            self.assertIsNone(mirror(key), key)
            self.assertIsNone(mirror(key, shift=True), key)
        self.assertIsNone(mirror("Enter"))  # decision D16
        self.assertIsNone(mirror("Backspace"))  # Windows convention: Back
        self.assertIsNone(mirror("F11"))  # stays with the shell root
        self.assertIsNone(mirror("F6"))  # the shell cycles panes
        self.assertEqual(("ESC_CHAIN", 0), mirror("Esc"))
        self.assertIsNone(mirror("Esc", ctrl=True))
        self.assertIsNone(mirror("Tab"))
        self.assertIsNone(mirror("A", ctrl=True))  # Ctrl+A, C, V, X, Z, Y belong to the editors
        for key in ("Plus", "Equals", "Add", "Minus", "Subtract", "0", "Numpad0"):
            for shift in (False, True):
                self.assertIsNone(mirror(key, ctrl=True, shift=shift), (key, shift))  # the zoom keys

    def test_the_frame_step_is_in_the_table_and_not_claimed_even_while_paused(self):
        self.assertEqual({"Comma": ("FRAME_STEP", -1, "OMITTED_UNTIL_MPV_API", True), "Period": ("FRAME_STEP", 1, "OMITTED_UNTIL_MPV_API", True)},
                         {chord: PLAYER_TABLE[chord] for chord in ("Comma", "Period")})
        for paused in (False, True):
            self.assertIsNone(mirror("Comma", paused=paused))
            self.assertIsNone(mirror("Period", paused=paused))
        self.assertEqual(("FULLSCREEN_TOGGLE", 0, "EXISTING_AT_ROOT", False), PLAYER_TABLE["F11"])

    def test_a_paused_only_row_is_claimed_only_while_paused_and_only_when_it_is_active(self):
        # Comma and Period are not ACTIVE yet, so the paused-only rule is checked on a synthetic table that makes them ACTIVE: the
        # resolver takes the table as a parameter (byChord), and the mirror and the spec-prose oracle are fed the same rows.
        rows = [("Comma", "FRAME_STEP", -1, "ACTIVE", True), ("Period", "FRAME_STEP", 1, "ACTIVE", False),
                ("N", "FRAME_STEP", 1, "OMITTED_UNTIL_MPV_API", True), ("B", "FRAME_STEP", -1, "EXISTING_AT_ROOT", False)]
        synthetic = pm.player_table(rows)
        status_names = {"OMITTED_UNTIL_MPV_API": "omitted-until-mpv-api", "EXISTING_AT_ROOT": "existing-at-root"}
        contract_rows = []
        for chord, kind, arg, status, paused in rows:
            row = {"scope": "player", "chord": chord, "action": kind, "arg": arg}
            if status in status_names:
                row["status"] = status_names[status]
            if paused:
                row["when"] = "paused"
            contract_rows.append(row)
        contract = {**CONTRACT, "shortcuts": {**CONTRACT["shortcuts"], "rows": contract_rows}}

        def resolve(key, paused):
            return pm.resolve_player_key_kt(synthetic, SHELL_RESERVED, SHELL_ZOOM, key, paused=paused)
        self.assertIsNone(resolve("Comma", False))
        self.assertEqual(("FRAME_STEP", -1), resolve("Comma", True))
        self.assertEqual(("FRAME_STEP", 1), resolve("Period", False))
        self.assertEqual(("FRAME_STEP", 1), resolve("Period", True))
        self.assertIsNone(resolve("N", True))  # an omitted row is never claimed, even while paused
        self.assertIsNone(resolve("B", True))  # a row that stays with the root is never claimed
        self.assertIsNone(resolve("Space", True))  # the synthetic table replaces the shipped one
        self.assertIsNone(pm.resolve_player_key_kt(synthetic, SHELL_RESERVED, SHELL_ZOOM, "Comma", text_editor=True, paused=True))
        for key in ("Comma", "Period", "N", "B", "Space", "Left"):
            for ctrl, alt, shift, meta in itertools.product((False, True), repeat=4):
                for paused in (False, True):
                    got = pm.resolve_player_key_kt(synthetic, SHELL_RESERVED, SHELL_ZOOM, key, ctrl, alt, shift, meta, paused=paused)
                    self.assertEqual(pm.oracle_player_resolve(contract, key, ctrl, alt, shift, meta, paused=paused), got,
                                     (key, ctrl, alt, shift, meta, paused))
        # The shipped table is the default argument, so a call without the table equals a call with it.
        self.assertHas(SHORTCUT_TEXT, "byChord: Map<String, DesktopPlayerShortcut> = desktopPlayerShortcutByChord,")

    def test_the_keypad_digits_are_the_digits_but_never_the_zoom_chord(self):
        for digit in range(10):
            self.assertEqual(("SEEK_PERCENT", digit * 10), mirror("Numpad%d" % digit), digit)
        self.assertEqual(("SPEED_SET", 1), mirror("Numpad1", shift=True))
        self.assertEqual(("SPEED_SET", 2), mirror("Numpad2", shift=True))
        self.assertIsNone(mirror("Numpad0", ctrl=True))
        self.assertIsNone(mirror("Numpad5", alt=True))
        self.assertIsNone(mirror("Numpad5", ctrl=True))
        self.assertIsNone(mirror("NumpadAdd"))
        self.assertIsNone(mirror("Numpad"))
        self.assertIsNone(mirror("Numpad12"))

    def test_the_escape_chain_order_and_decision_d4(self):
        order = [(popup, fullscreen, layout, pm.esc_step_kt(popup, fullscreen, layout))
                 for popup, fullscreen, layout in itertools.product((True, False), (True, False), ("LEGACY", "DESKTOP"))]
        self.assertEqual([
            (True, True, "LEGACY", "CLOSE_POPUP"), (True, True, "DESKTOP", "CLOSE_POPUP"),
            (True, False, "LEGACY", "CLOSE_POPUP"), (True, False, "DESKTOP", "CLOSE_POPUP"),
            (False, True, "LEGACY", "LEAVE_FULLSCREEN"), (False, True, "DESKTOP", "LEAVE_FULLSCREEN"),
            (False, False, "LEGACY", "LEAVE_PAGE"), (False, False, "DESKTOP", "NONE"),
        ], order)
        self.assertHas(
            SHORTCUT_TEXT,
            "popupOpen -> DesktopPlayerEscStep.CLOSE_POPUP fullscreen -> DesktopPlayerEscStep.LEAVE_FULLSCREEN "
            "layout == DesktopWindowsUxPlayerLayout.LEGACY -> DesktopPlayerEscStep.LEAVE_PAGE else -> DesktopPlayerEscStep.NONE",
        )
        root = pm.read(source.K / "DesktopShell.kt")
        self.assertIn("if (isFullscreen()) setOriginalFullscreen(false) else homeRootRef.get()?.navigation?.requestBack()", root)

    def test_the_difference_to_the_vendored_key_map_is_written_down(self):
        vendored = pm.squash(pm.read(pm.VENDORED_KEY_POLICY))
        self.assertIn("internal const val KEYBOARD_SEEK_SHORT_STEP_MS = 5_000L", vendored)
        self.assertIn("internal const val KEYBOARD_SEEK_LONG_STEP_MS = 10_000L", vendored)
        seconds = CONTRACT["pointer"]["key_seek_seconds"]
        self.assertEqual((5, 10, 10), (seconds["arrow"], seconds["shift_arrow"], seconds["j_l"]))
        shifted = {"shift": True}
        kept = [
            ("Key.Spacebar, Key.K -> PlayerKeyAction.PlayPause", [("Space", {}, ("PLAY_PAUSE", 0)), ("K", {}, ("PLAY_PAUSE", 0))]),
            ("Key.DirectionLeft -> PlayerKeyAction.SeekRelative(-KEYBOARD_SEEK_SHORT_STEP_MS)", [("Left", {}, ("SEEK", -5))]),
            ("Key.DirectionRight -> PlayerKeyAction.SeekRelative(KEYBOARD_SEEK_SHORT_STEP_MS)", [("Right", {}, ("SEEK", 5))]),
            ("Key.J -> PlayerKeyAction.SeekRelative(-KEYBOARD_SEEK_LONG_STEP_MS)", [("J", {}, ("SEEK", -10))]),
            ("Key.DirectionUp -> PlayerKeyAction.VolumeUp", [("Up", {}, ("VOLUME", 5))]),
            ("Key.DirectionDown -> PlayerKeyAction.VolumeDown", [("Down", {}, ("VOLUME", -5))]),
            ("Key.M -> PlayerKeyAction.ToggleMute", [("M", {}, ("MUTE", 0))]),
            ("Key.D -> PlayerKeyAction.ToggleDanmaku", [("D", {}, ("DANMAKU_TOGGLE", 0))]),
            ("Key.S -> PlayerKeyAction.TakeScreenshot", [("S", {}, ("SCREENSHOT", 0))]),
            ("Key.LeftBracket -> PlayerKeyAction.PreviousPart", [("LeftBracket", {}, ("PART", -1))]),
            ("Key.RightBracket -> PlayerKeyAction.NextPart", [("RightBracket", {}, ("PART", 1))]),
            ("Key.Zero, Key.NumPad0 -> PlayerKeyAction.SeekPercent(0.0f)", [("0", {}, ("SEEK_PERCENT", 0)), ("Numpad0", {}, ("SEEK_PERCENT", 0))]),
            ("Key.Five, Key.NumPad5 -> PlayerKeyAction.SeekPercent(0.5f)", [("5", {}, ("SEEK_PERCENT", 50)), ("Numpad5", {}, ("SEEK_PERCENT", 50))]),
            ("Key.Nine, Key.NumPad9 -> PlayerKeyAction.SeekPercent(0.9f)", [("9", {}, ("SEEK_PERCENT", 90)), ("Numpad9", {}, ("SEEK_PERCENT", 90))]),
            ("Key.DirectionLeft -> PlayerKeyAction.SeekRelative(-KEYBOARD_SEEK_LONG_STEP_MS)", [("Left", shifted, ("SEEK", -10))]),
            ("Key.DirectionRight -> PlayerKeyAction.SeekRelative(KEYBOARD_SEEK_LONG_STEP_MS)", [("Right", shifted, ("SEEK", 10))]),
            ("Key.One, Key.NumPad1 -> PlayerKeyAction.SetSpeed(1.0f)", [("1", shifted, ("SPEED_SET", 1)), ("Numpad1", shifted, ("SPEED_SET", 1))]),
            ("Key.Two, Key.NumPad2 -> PlayerKeyAction.SetSpeed(2.0f)", [("2", shifted, ("SPEED_SET", 2)), ("Numpad2", shifted, ("SPEED_SET", 2))]),
        ]
        for needle, keys in kept:
            self.assertIn(pm.squash(needle), vendored, needle)
            for key, modifiers, want in keys:
                self.assertEqual(want, mirror(key, **modifiers), (needle, key))
        # The intentional differences: L was the phone screen lock and is now +10 s, Enter is not taken (decision D16), and
        # the engagement keys Q, W, E and R are a non-goal. Each vendored line is pinned so that a change of the map is seen.
        for needle in ("Key.L -> PlayerKeyAction.ToggleScreenLock", "Key.F, Key.Enter, Key.NumPadEnter -> PlayerKeyAction.ToggleFullscreen",
                       "Key.Q -> PlayerKeyAction.ToggleLike", "Key.W -> PlayerKeyAction.Coin", "Key.E -> PlayerKeyAction.ToggleFavorite",
                       "Key.R -> PlayerKeyAction.TripleAction"):
            self.assertIn(pm.squash(needle), vendored, needle)
        self.assertEqual(("SEEK", 10), mirror("L"))
        self.assertIsNone(mirror("Enter"))
        self.assertEqual(("FULLSCREEN_TOGGLE", 0), mirror("F"))

    def test_the_resolver_lines_are_pinned(self):
        self.assertHas(
            SHORTCUT_TEXT,
            "if (!event.keyDown || context.textEditorFocused) return null",
            "if (event.meta || (event.ctrl && event.alt)) return null",
            'val keypadDigit = !event.ctrl && event.key.length == 7 && event.key.startsWith("Numpad") && event.key[6].isDigit()',
            "val key = if (keypadDigit) event.key.substring(6) else event.key",
            "val shortcut = byChord[chord] ?: return null",
            "if (shortcut.status != DesktopPlayerShortcutStatus.ACTIVE || (shortcut.whenPaused && !context.paused)) return null",
            "DESKTOP_PLAYER_SHORTCUTS.associateBy { it.chord }",
            "internal data class DesktopPlayerKeyContext(val textEditorFocused: Boolean = false, val paused: Boolean = false)",
            "internal data class DesktopPlayerAction(val kind: DesktopPlayerActionKind, val arg: Int = 0)",
            "internal data class DesktopPlayerShortcut( val chord: String, val action: DesktopPlayerAction, "
            "val status: DesktopPlayerShortcutStatus = DesktopPlayerShortcutStatus.ACTIVE, val whenPaused: Boolean = false, )",
        )

    def test_the_table_is_shell_independent_and_uses_the_shell_key_data_type(self):
        self.assertHas(
            SHORTCUT_TEXT,
            "internal fun resolveDesktopPlayerKey( event: DesktopShellKeyData, context: DesktopPlayerKeyContext, "
            "byChord: Map<String, DesktopPlayerShortcut> = desktopPlayerShortcutByChord, ): DesktopPlayerAction? {",
        )
        self.assertEqual(["import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout"],
                         re.findall(r"(?m)^import .*$", SHORTCUT_TEXT))


# --------------------------------------------------------------------------------------
# Seek model
# --------------------------------------------------------------------------------------

KNOTS = [(float(x), float(y)) for x, y in CONTRACT["pointer"]["fine_scrub_knots"]]
INF = float("inf")
NAN = float("nan")


class SeekModelTest(NeedleCase):
    def test_the_buffered_fraction(self):
        self.assertAlmostEqual(0.5, pm.buffered_fraction_kt(30.0, 20.0, 100.0))
        self.assertEqual(1.0, pm.buffered_fraction_kt(500.0, 20.0, 100.0))
        self.assertEqual(0.2, pm.buffered_fraction_kt(0.0, 20.0, 100.0))
        self.assertEqual(0.2, pm.buffered_fraction_kt(-5.0, 20.0, 100.0))  # a negative figure counts as no cache
        self.assertEqual(0.1, pm.buffered_fraction_kt(10.0, -3.0, 100.0))  # a negative position counts as the start
        self.assertEqual(0.0, pm.buffered_fraction_kt(0.0, 0.0, 100.0))
        for buffered, position, duration in ((None, 20.0, 100.0), (NAN, 20.0, 100.0), (INF, 20.0, 100.0), (-INF, 20.0, 100.0),
                                              (10.0, NAN, 100.0), (10.0, INF, 100.0), (10.0, 20.0, NAN), (10.0, 20.0, INF),
                                              (10.0, 20.0, 0.0), (10.0, 20.0, -1.0)):
            self.assertIsNone(pm.buffered_fraction_kt(buffered, position, duration), (buffered, position, duration))

    def test_the_buffered_range_never_ends_before_the_playhead_and_stays_in_the_unit_interval(self):
        for position in (0.0, 1.0, 33.3, 99.9, 100.0, 250.0):
            for buffered in (0.0, 0.5, 10.0, 1000.0):
                got = pm.buffered_fraction_kt(buffered, position, 100.0)
                self.assertTrue(0.0 <= got <= 1.0)
                self.assertGreaterEqual(got, min(position / 100.0, 1.0) - 1e-12)

    def test_the_fine_scrub_factor_at_the_knots_and_between_them(self):
        for distance, factor in KNOTS:
            self.assertEqual(factor, pm.fine_scrub_kt(KNOTS, distance), distance)
        self.assertEqual(1.0, pm.fine_scrub_kt(KNOTS, 0.0))
        self.assertEqual(1.0, pm.fine_scrub_kt(KNOTS, 47.0))
        self.assertAlmostEqual(0.75, pm.fine_scrub_kt(KNOTS, 72.0))
        self.assertAlmostEqual(0.375, pm.fine_scrub_kt(KNOTS, 144.0))
        self.assertAlmostEqual(0.175, pm.fine_scrub_kt(KNOTS, 240.0))
        self.assertEqual(0.1, pm.fine_scrub_kt(KNOTS, 288.0))
        self.assertEqual(0.1, pm.fine_scrub_kt(KNOTS, 5000.0))
        self.assertEqual(0.1, pm.fine_scrub_kt(KNOTS, INF))
        self.assertEqual(0.5, pm.fine_scrub_kt(KNOTS, -96.0))  # above or below the rail is the same distance
        self.assertEqual(1.0, pm.fine_scrub_kt(KNOTS, NAN))

    def test_the_fine_scrub_factor_is_monotone_and_never_below_one_tenth(self):
        previous = 2.0
        distance = 0.0
        while distance <= 400.0:
            factor = pm.fine_scrub_kt(KNOTS, distance)
            self.assertLessEqual(factor, previous, distance)
            self.assertGreaterEqual(factor, 0.1, distance)
            self.assertLessEqual(factor, 1.0, distance)
            previous = factor
            distance += 0.25

    def test_the_fine_scrub_oracle_is_the_piecewise_linear_curve_through_the_knots(self):
        from fractions import Fraction
        points = [(Fraction(x), Fraction(y).limit_denominator(1000)) for x, y in KNOTS]
        for tenth in range(0, 4000, 7):
            distance = Fraction(tenth, 10)
            if distance <= points[0][0]:
                want = points[0][1]
            elif distance >= points[-1][0]:
                want = points[-1][1]
            else:
                for (x0, y0), (x1, y1) in zip(points, points[1:]):
                    if x0 <= distance <= x1:
                        want = y0 + (y1 - y0) * (distance - x0) / (x1 - x0)
                        break
            self.assertAlmostEqual(float(want), pm.fine_scrub_kt(KNOTS, float(distance)), places=12, msg=str(distance))

    def test_chapter_gaps(self):
        rail = 1565.0
        gap = C["DESKTOP_PLAYER_CHAPTER_GAP_DP"]
        three_hundred = 3_600_000
        self.assertEqual([1 / 6, 1 / 3], pm.chapter_gaps_kt([0, 600_000, 1_200_000, three_hundred], three_hundred, rail, gap))
        self.assertEqual([0.25], pm.chapter_gaps_kt([900_000, 900_000], three_hundred, rail, gap))  # duplicates collapse
        self.assertEqual([0.25, 0.5], pm.chapter_gaps_kt([1_800_000, 900_000], three_hundred, rail, gap))  # sorted
        # Two starts closer than a gap width on the rail: the later one is dropped (1000 ms and 1100 ms are 0.04 dp apart).
        self.assertEqual([1000 / three_hundred], pm.chapter_gaps_kt([1000, 1100], three_hundred, rail, gap))
        self.assertEqual([1000 / three_hundred, 6000 / three_hundred], pm.chapter_gaps_kt([1000, 1100, 6000], three_hundred, rail, gap))
        self.assertEqual([], pm.chapter_gaps_kt([-5, 0, three_hundred, three_hundred + 1], three_hundred, rail, gap))
        for bad in ([], ):
            self.assertEqual([], pm.chapter_gaps_kt(bad, three_hundred, rail, gap))
        self.assertEqual([], pm.chapter_gaps_kt([1000], 0, rail, gap))
        self.assertEqual([], pm.chapter_gaps_kt([1000], three_hundred, 0.0, gap))
        self.assertEqual([], pm.chapter_gaps_kt([1000], three_hundred, NAN, gap))
        self.assertEqual([], pm.chapter_gaps_kt([1000], three_hundred, -4.0, gap))

    def test_chapter_gaps_match_an_exact_oracle_on_a_sweep(self):
        from fractions import Fraction
        duration = 5_400_000
        for rail in (320, 800, 1565, 3000):
            for count in (1, 3, 12, 60):
                starts = [(index * 7919 * 13 + 101) % duration for index in range(count)]
                kept = []
                for start in sorted(set(s for s in starts if 0 < s < duration)):
                    if not kept or (Fraction(start, duration) - kept[-1]) * rail >= 2:
                        kept.append(Fraction(start, duration))
                got = pm.chapter_gaps_kt(starts, duration, float(rail), 2.0)
                self.assertEqual([float(f) for f in kept], got, (rail, count))
                for left, right in zip(got, got[1:]):
                    self.assertGreaterEqual((right - left) * rail, 2.0 - 1e-9)

    def test_the_spec_arithmetic_of_a_one_hour_rail(self):
        # Spec 4.3: a 1565 dp rail on a one-hour video is about 2.3 seconds per dp.
        self.assertAlmostEqual(2.3, 3600 / 1565, places=1)

    def test_the_kotlin_seek_lines_are_pinned(self):
        self.assertHas(
            SEEK_TEXT,
            "if (bufferedForwardSeconds == null || !bufferedForwardSeconds.isFinite() || !positionSeconds.isFinite()) return null",
            "if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return null",
            "return ((positionSeconds.coerceAtLeast(0.0) + bufferedForwardSeconds.coerceAtLeast(0.0)) / durationSeconds).coerceIn(0.0, 1.0)",
            "if (distance.isNaN() || distance <= knots.first().first) return knots.first().second",
            "val t = (distance - x0) / (x1 - x0)",
            "return if (t >= 1.0) y1 else (y0 + (y1 - y0) * t).coerceIn(knots.last().second, knots.first().second)",
            "return knots.last().second",
            "if (durationMs <= 0L || !railWidthDp.isFinite() || railWidthDp <= 0.0) return emptyList()",
            "for (start in startsMs.filter { it > 0L && it < durationMs }.distinct().sorted()) {",
            "val fraction = start.toDouble() / durationMs",
            "if (gaps.isEmpty() || (fraction - gaps.last()) * railWidthDp >= gapDp) gaps.add(fraction)",
        )


# --------------------------------------------------------------------------------------
# Quality rows
# --------------------------------------------------------------------------------------

THRESHOLDS = pm.vendored_gate_thresholds()
AUTO_RULES = pm.vendored_auto_rules()
IDS = [127, 126, 125, 120, 116, 112, 100, 80, 74, 64, 32, 16]
LABELS = ["8K", "杜比视界", "HDR 真彩", "4K", "1080P60", "1080P+", "智能修复", "1080P", "720P60", "720P", "480P", "360P"]


def quality_menu(ids, labels, current, switchable, logged_in, vip, switching, auto_preferred, hdr=True, dolby_vision=True):
    """The menu of a device that shows HDR and Dolby Vision unless told otherwise (the two readbacks are required in Kotlin)."""
    return pm.quality_menu_kt(C, ids, labels, current, switchable, logged_in, vip, hdr, dolby_vision, switching, auto_preferred)


class QualityRowsTest(NeedleCase):
    def test_the_gate_thresholds_are_the_ones_of_the_vendored_menu(self):
        self.assertEqual((C["DESKTOP_PLAYER_QUALITY_SMART_REPAIR_ID"], C["DESKTOP_PLAYER_QUALITY_VIP_FROM_ID"],
                          C["DESKTOP_PLAYER_QUALITY_LOGIN_FROM_ID"]), THRESHOLDS)
        self.assertEqual((100, 112, 80), THRESHOLDS)
        chain = pm.read(pm.VENDORED_QUALITY_MANAGER)
        self.assertIn("100 to \"智能修复\"", chain)
        self.assertIn("private val QUALITY_CHAIN = listOf(129, 127, 126, 125, 120, 116, 112, 100, 80, 74, 64, 32, 16)", chain)

    def test_a_higher_id_is_a_better_quality_in_the_original_chain(self):
        chain = [129, 127, 126, 125, 120, 116, 112, 100, 80, 74, 64, 32, 16]
        self.assertEqual(sorted(chain, reverse=True), chain)

    def test_the_row_states_equal_the_vendored_gate_for_every_combination(self):
        checked = 0
        for current in (None, 80, 112, 120, 127, 64):
            for logged_in, vip in itertools.product((False, True), repeat=2):
                for switchable in ([], [127, 120, 80, 64], [16], IDS):
                    menu = quality_menu(IDS, LABELS, current, switchable, logged_in, vip, False, False)
                    for (quality, label, state, enabled), want_label in zip(menu["rows"], LABELS):
                        want = pm.oracle_quality_state(THRESHOLDS, quality, current, logged_in, vip, switchable)
                        self.assertEqual(want, state, (quality, current, logged_in, vip, switchable))
                        self.assertEqual(want_label, label)
                        self.assertEqual(state == "SELECTABLE", enabled)
                        checked += 1
        self.assertEqual(6 * 4 * 4 * 12, checked)

    def test_the_gate_precedence_vip_then_login_then_unavailable(self):
        def state(quality, **kw):
            args = dict(current=None, switchable=[], logged_in=True, vip=True)
            args.update(kw)
            menu = quality_menu([quality], [str(quality)], args["current"], args["switchable"], args["logged_in"], args["vip"], False, False)
            return menu["rows"][0][2]
        self.assertEqual("NEEDS_VIP", state(120, logged_in=False, vip=False))  # the VIP gate comes first
        self.assertEqual("NEEDS_LOGIN", state(80, logged_in=False))
        self.assertEqual("NEEDS_LOGIN", state(100, logged_in=False))  # smart repair with the VIP passes the first gate, not the second
        self.assertEqual("NEEDS_VIP", state(100, vip=False))
        self.assertEqual("SELECTABLE", state(74, logged_in=False, vip=False))  # below 80 needs nothing
        self.assertEqual("SELECTABLE", state(112))
        self.assertEqual("NEEDS_VIP", state(112, vip=False))
        self.assertEqual("UNAVAILABLE", state(64, switchable=[80]))
        self.assertEqual("SELECTABLE", state(64, switchable=[]))  # an empty list means unknown, not none
        self.assertEqual("SELECTABLE", state(64, switchable=[64]))
        self.assertEqual("CURRENT", state(120, current=120, logged_in=False, vip=False, switchable=[16]))

    def test_the_playing_quality_is_the_current_row_and_not_selectable(self):
        menu = quality_menu(IDS, LABELS, 80, [], True, True, False, False)
        states = {row[0]: row[2] for row in menu["rows"]}
        self.assertEqual("CURRENT", states[80])
        self.assertEqual(1, sum(state == "CURRENT" for state in states.values()))
        self.assertEqual("1080P", menu["resolved_label"])
        self.assertEqual([False], [row[3] for row in menu["rows"] if row[0] == 80])
        self.assertIsNone(quality_menu(IDS, LABELS, 999, [], True, True, False, False)["resolved_label"])
        self.assertIsNone(quality_menu(IDS, LABELS, None, [], True, True, False, False)["resolved_label"])

    def test_the_automatic_row_targets_the_best_selectable_quality_above_the_playing_one(self):
        def target(current, **kw):
            args = dict(switchable=[], logged_in=True, vip=True, switching=False, auto=False)
            args.update(kw)
            return quality_menu(IDS, LABELS, current, args["switchable"], args["logged_in"], args["vip"], args["switching"], args["auto"])["auto_target"]
        self.assertEqual(127, target(80))
        self.assertEqual(127, target(None))
        self.assertIsNone(target(127))  # already the best
        self.assertEqual(126, target(125, switchable=[126, 125, 80]))
        self.assertEqual(80, target(64, vip=False))  # the VIP qualities are not obtainable
        self.assertIsNone(target(80, vip=False))  # everything above 1080P needs the VIP
        self.assertIsNone(target(64, vip=False, logged_in=False))  # a guest is capped at 64, as the original resolver does
        self.assertEqual(64, target(32, vip=False, logged_in=False))
        self.assertEqual("SELECTABLE", pm.quality_menu_kt(C, [74], ["74"], 64, [], False, False, True, True, False, False)["rows"][0][2])  # 74 by hand
        self.assertEqual(120, target(116, switchable=[120, 116]))
        self.assertIsNone(target(120, switchable=[120, 116]))
        self.assertEqual(127, target(80, switching=True))  # the target is still named while switching, the rows are disabled

    def test_the_automatic_target_skips_an_hdr_or_dolby_vision_quality_the_device_cannot_show(self):
        hdr_ids = [126, 125, 120, 80]
        labels = [str(quality) for quality in hdr_ids]

        def target(hdr, dolby_vision, current=80):
            return quality_menu(hdr_ids, labels, current, [], True, True, False, False, hdr, dolby_vision)["auto_target"]
        self.assertEqual([120, 125, 126, 126], [target(False, False), target(True, False), target(False, True), target(True, True)])
        self.assertIsNone(target(False, False, current=120))
        self.assertIsNone(target(True, False, current=125))
        self.assertIsNone(quality_menu(IDS, LABELS, 125, [126, 125, 80], True, True, False, False, True, False)["auto_target"])
        # The explicit rows never look at the device (the vendored menu does not either).
        self.assertEqual(quality_menu(IDS, LABELS, 80, [], True, True, False, False, True, True)["rows"],
                         quality_menu(IDS, LABELS, 80, [], True, True, False, False, False, False)["rows"])

    def test_the_automatic_target_equals_the_vendored_resolver_for_every_combination(self):
        checked = 0
        for current in (None, 16, 64, 74, 80, 112, 120, 125, 126, 127):
            for logged_in, vip, hdr, dolby_vision in itertools.product((False, True), repeat=4):
                for switchable in ([], [127, 126, 125, 120, 80, 64], IDS):
                    menu = quality_menu(IDS, LABELS, current, switchable, logged_in, vip, False, False, hdr, dolby_vision)
                    above = [row[0] for row in menu["rows"] if row[2] == "SELECTABLE" and (current is None or row[0] > current)]
                    want = pm.vendored_auto_playable(AUTO_RULES, above, logged_in, vip, hdr, dolby_vision)
                    self.assertEqual(want, menu["auto_target"], (current, logged_in, vip, hdr, dolby_vision, switchable))
                    checked += 1
        self.assertEqual(10 * 16 * 3, checked)

    def test_the_auto_constants_are_the_vendored_resolvers(self):
        self.assertEqual({126: "isDolbyVisionSupported", 125: "isHdrSupported"}, AUTO_RULES["device"])
        self.assertEqual((C["DESKTOP_PLAYER_QUALITY_AUTO_CEILING_VIP_ID"], C["DESKTOP_PLAYER_QUALITY_AUTO_CEILING_LOGGED_IN_ID"],
                          C["DESKTOP_PLAYER_QUALITY_AUTO_CEILING_GUEST_ID"]), AUTO_RULES["ceiling"])
        self.assertEqual((129, 80, 64), AUTO_RULES["ceiling"])
        self.assertEqual({C["DESKTOP_PLAYER_QUALITY_DOLBY_VISION_ID"]: "isDolbyVisionSupported", C["DESKTOP_PLAYER_QUALITY_HDR_ID"]: "isHdrSupported"},
                         AUTO_RULES["device"])
        block = pm.constants(pm.marked_block(QUALITY_TEXT, "PLAYER-QUALITY-AUTO"))
        self.assertEqual({"DESKTOP_PLAYER_QUALITY_AUTO_CEILING_VIP_ID": 129, "DESKTOP_PLAYER_QUALITY_AUTO_CEILING_LOGGED_IN_ID": 80,
                          "DESKTOP_PLAYER_QUALITY_AUTO_CEILING_GUEST_ID": 64, "DESKTOP_PLAYER_QUALITY_DOLBY_VISION_ID": 126,
                          "DESKTOP_PLAYER_QUALITY_HDR_ID": 125}, block)
        # The original resolver is what the capability readbacks of the desktop feed; both readbacks exist as named here.
        capabilities = pm.read(source.K / "ui/DesktopOriginalMpvPlaybackCapabilities.kt")
        self.assertIn("override fun isHdrSupported(): Boolean", capabilities)
        self.assertIn("override fun isDolbyVisionSupported(): Boolean", capabilities)

    def test_switching_disables_the_rows_and_the_automatic_row_and_keeps_their_states(self):
        busy = quality_menu(IDS, LABELS, 80, [], True, True, True, True)
        idle = quality_menu(IDS, LABELS, 80, [], True, True, False, True)
        self.assertEqual([row[:3] for row in idle["rows"]], [row[:3] for row in busy["rows"]])
        self.assertFalse(any(row[3] for row in busy["rows"]))
        self.assertTrue(any(row[3] for row in idle["rows"]))
        self.assertEqual((False, True), (busy["auto_enabled"], idle["auto_enabled"]))
        self.assertEqual((True, True), (busy["auto_selected"], idle["auto_selected"]))
        self.assertEqual((True, False), (busy["switching"], idle["switching"]))

    def test_labels_fall_back_to_the_id_and_the_rows_keep_the_input_order(self):
        menu = quality_menu([80, 64, 32], ["1080P"], None, [], True, True, False, False)
        self.assertEqual([(80, "1080P"), (64, "64"), (32, "32")], [(row[0], row[1]) for row in menu["rows"]])
        self.assertEqual([], quality_menu([], [], 80, [], True, True, False, False)["rows"])

    def test_the_pill_and_the_hints_of_the_strings(self):
        consts = pm.string_constants(STRINGS_TEXT)
        self.assertEqual({"需登录", "大会员", "不可用"}, {consts["QUALITY_NEEDS_LOGIN"], consts["QUALITY_NEEDS_VIP"], consts["QUALITY_UNAVAILABLE"]})
        self.assertEqual("最高可用（自动）", consts["QUALITY_AUTO"])
        self.assertEqual("切换中", consts["QUALITY_SWITCHING"])
        self.assertHas(
            STRINGS_TEXT,
            "DesktopPlayerQualityRowState.NEEDS_LOGIN -> QUALITY_NEEDS_LOGIN DesktopPlayerQualityRowState.NEEDS_VIP -> QUALITY_NEEDS_VIP "
            "DesktopPlayerQualityRowState.UNAVAILABLE -> QUALITY_UNAVAILABLE "
            "DesktopPlayerQualityRowState.CURRENT, DesktopPlayerQualityRowState.SELECTABLE -> null",
            "if (switching) QUALITY_SWITCHING else resolvedLabel ?: QUALITY_PILL_FALLBACK",
        )
        self.assertEqual(["CURRENT", "SELECTABLE", "NEEDS_LOGIN", "NEEDS_VIP", "UNAVAILABLE"], pm.enum_entries(QUALITY_TEXT, "DesktopPlayerQualityRowState"))

    def test_the_kotlin_quality_lines_are_pinned(self):
        self.assertHas(
            QUALITY_TEXT,
            "id == currentQuality -> DesktopPlayerQualityRowState.CURRENT",
            "(id == DESKTOP_PLAYER_QUALITY_SMART_REPAIR_ID || id >= DESKTOP_PLAYER_QUALITY_VIP_FROM_ID) && !vip -> DesktopPlayerQualityRowState.NEEDS_VIP",
            "id >= DESKTOP_PLAYER_QUALITY_LOGIN_FROM_ID && !loggedIn -> DesktopPlayerQualityRowState.NEEDS_LOGIN",
            "switchableQualityIds.isNotEmpty() && id !in switchableQualityIds -> DesktopPlayerQualityRowState.UNAVAILABLE",
            "else -> DesktopPlayerQualityRowState.SELECTABLE",
            "DesktopPlayerQualityRow(id, qualityLabels.getOrNull(index) ?: id.toString(), state, state == DesktopPlayerQualityRowState.SELECTABLE && !switching)",
            "val better = rows.filter { it.state == DesktopPlayerQualityRowState.SELECTABLE && (currentQuality == null || it.id > currentQuality) && "
            "desktopPlayerQualityAutoAllowed(it.id, loggedIn, vip, hdrSupported, dolbyVisionSupported) }",
            "vip -> DESKTOP_PLAYER_QUALITY_AUTO_CEILING_VIP_ID loggedIn -> DESKTOP_PLAYER_QUALITY_AUTO_CEILING_LOGGED_IN_ID "
            "else -> DESKTOP_PLAYER_QUALITY_AUTO_CEILING_GUEST_ID",
            "DESKTOP_PLAYER_QUALITY_DOLBY_VISION_ID -> dolbyVisionSupported DESKTOP_PLAYER_QUALITY_HDR_ID -> hdrSupported else -> true",
            "return deviceSafe && id <= ceiling",
            "loggedIn: Boolean, vip: Boolean, hdrSupported: Boolean, dolbyVisionSupported: Boolean, switching: Boolean, autoPreferred: Boolean, "
            "): DesktopPlayerQualityMenu {",
            "autoTargetId = better.maxOfOrNull { it.id },",
            "val autoEnabled: Boolean get() = !switching",
        )

    def test_the_leaf_builds_its_quality_list_from_the_same_readbacks(self):
        leaf = pm.squash(pm.read(source.K / "ui/DesktopWindowsVideoPhysicalLeaf.kt"))
        self.assertIn("qualities = success?.let { value -> value.qualityIds.mapIndexed { index, id -> id to (value.qualityLabels.getOrNull(index) ?: id.toString()) } }.orEmpty(),", leaf)
        self.assertIn("selectedQuality = success?.currentQuality,", leaf)
        self.assertIn("isQualitySwitching", leaf)
        state = pm.squash(pm.read(source.REPO / "app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt"))
        for field in ("val switchableQualityIds: List<Int> = emptyList(),", "val isQualitySwitching: Boolean = false,", "val isLoggedIn: Boolean = false,",
                      "val isVip: Boolean = false,", "val currentQuality: Int = 64,"):
            self.assertIn(field, state, field)


# --------------------------------------------------------------------------------------
# Strings
# --------------------------------------------------------------------------------------

# Spec section 7: the accessible names that fixtures and the Java Access Bridge probe rely on.
ACCESSIBLE_NAMES = ("返回", "播放", "暂停", "全屏", "退出全屏", "详情", "关闭详情", "视频详情面板", "更多播放操作", "简介、分P与播放设置",
                    "NVIDIA 增强详情", "NVIDIA 自动增强", "BiliPai", "搜索", "下载与离线")
FORBIDDEN_TEXT = ("force_attempt", "forceAttempt", "FORCE_ATTEMPT", "仍要尝试", "恢复自动")


class StringsTest(unittest.TestCase):
    def test_the_accessible_name_set_of_spec_section_seven_is_present(self):
        player = set(pm.string_constants(STRINGS_TEXT).values())
        shell_names = set(pm.string_constants(SHELL_STRINGS_TEXT).values())
        rail_labels = set(shell.rail_labels(SHELL_STRINGS_TEXT).values())
        for name in ACCESSIBLE_NAMES:
            self.assertTrue(name in player or name in shell_names or name in rail_labels, name)
        self.assertEqual(12, len([name for name in ACCESSIBLE_NAMES if name in player]))  # the other three are shell names

    def test_names_that_exist_today_are_spelled_exactly_as_the_current_code_does(self):
        files = [pm.read(path) for path in (source.K / "ui").glob("*.kt") if path.name.startswith(("DesktopWindowsVideo", "DesktopVideoEnhancement"))]
        joined = "\n".join(files)
        for name in ("返回", "播放", "暂停", "全屏", "退出全屏", "详情", "更多播放操作", "简介、分P与播放设置", "NVIDIA 增强详情", "浮窗"):
            self.assertIn('"%s"' % name, joined, name)
        details = pm.read(source.K / "ui/DesktopWindowsVideoDetailsPanel.kt")
        self.assertIn("视频详情面板", details)

    def test_the_tooltips_name_the_key_the_table_binds(self):
        consts = pm.string_constants(STRINGS_TEXT)
        wanted = {"TOOLTIP_PLAY": "PLAY_PAUSE", "TOOLTIP_MUTE": "MUTE", "TOOLTIP_FULLSCREEN": "FULLSCREEN_TOGGLE", "TOOLTIP_MINI": "MINI_PLAYER",
                  "TOOLTIP_CINEMA": "SIDE_PANEL_OR_CINEMA", "TOOLTIP_DANMAKU": "DANMAKU_TOGGLE", "TOOLTIP_SUBTITLE": "SUBTITLE_TOGGLE",
                  "TOOLTIP_DETAILS": "SIDE_PANEL_OR_CINEMA"}
        self.assertEqual(sorted(wanted), sorted(name for name in consts if name.startswith("TOOLTIP_")))
        for name, kind in wanted.items():
            match = re.fullmatch(r"(.+)（(.+)）", consts[name])
            self.assertIsNotNone(match, name)
            chord = "Space" if match.group(2) == "空格" else match.group(2)
            self.assertIn(chord, PLAYER_TABLE, name)
            self.assertEqual(kind, PLAYER_TABLE[chord][0], name)
            self.assertEqual("ACTIVE", PLAYER_TABLE[chord][2], name)

    def test_the_dock_and_flyout_labels_of_the_spec(self):
        consts = pm.string_constants(STRINGS_TEXT)
        for name, text in (("LABEL_PIN", "置顶"), ("LABEL_DANMAKU", "弹"), ("LABEL_SUBTITLE", "字幕"), ("LABEL_SPEED", "倍速"),
                           ("LABEL_SIDE_PANEL", "侧栏"), ("SPONSOR_SKIP", "跳过片头 ›"), ("SUBTITLE_NONE_TOOLTIP", "此视频无字幕"),
                           ("SPEED_EDIT_LIST", "编辑倍速列表…"), ("SWITCH_TO_CLASSIC_LAYOUT", "切换到经典播放器布局")):
            self.assertEqual(text, consts[name], name)

    def test_the_kdoc_claims_no_more_than_the_object_holds(self):
        doc = STRINGS_TEXT[:STRINGS_TEXT.index("internal object DesktopPlayerStrings")]
        self.assertNotIn("Every user-facing text", doc)
        self.assertIn("added by the slice that builds each page (V5)", pm.squash(doc))

    def test_the_osd_and_state_texts_of_the_spec(self):
        consts = pm.string_constants(STRINGS_TEXT)
        for name, text in (("MUTED", "已静音"), ("SCREENSHOT_SAVED", "截图已保存"), ("WINDOW_PINNED", "已置顶窗口"), ("BUFFERING", "缓冲中…"),
                           ("REPLAY", "重新播放"), ("ERROR_TITLE", "播放出错"), ("RETRY", "重试"), ("PIP_ACTIVE", "正在小窗播放"),
                           ("PIP_RETURN", "返回播放器"), ("AUDIO_ONLY", "仅音频播放")):
            self.assertEqual(text, consts[name], name)
        flat = pm.squash(STRINGS_TEXT)
        for needle in ('fun danmakuToggle(on: Boolean): String = if (on) "弹幕：已开启" else "弹幕：已关闭"',
                       'fun subtitleToggle(label: String?): String = "字幕：" + (label ?: "已关闭")',
                       'fun volumePill(percent: Int): String = "音量 $percent%"'):
            self.assertIn(pm.squash(needle), flat, needle)

    def test_the_player_strings_hold_no_forbidden_text_and_no_duplicate_constant(self):
        names = re.findall(r"const val (\w+) =", STRINGS_TEXT)
        self.assertEqual(len(names), len(set(names)))
        for word in FORBIDDEN_TEXT:
            self.assertNotIn(word, STRINGS_TEXT)

    def test_the_strings_object_is_the_only_x1b_file_with_chinese_text(self):
        for path in pm.X1B_MAIN_FILES:
            positions = source.cjk_positions(pm.read(path))
            if path == pm.STRINGS_KT:
                self.assertTrue(positions)
            else:
                self.assertEqual([], positions, path.name)


# --------------------------------------------------------------------------------------
# Source
# --------------------------------------------------------------------------------------

JVM_TEST_FILES = [source.KT / "ui" / name for name in (
    "DesktopPlayerLayoutPolicyTest.kt", "DesktopPlayerChromePolicyTest.kt", "DesktopPlayerShortcutPolicyTest.kt",
    "DesktopPlayerSeekModelTest.kt", "DesktopPlayerQualityRowsTest.kt")]
PY_FILES = [Path(__file__).resolve().parent / name for name in ("ux_player_model.py", "test_ux_player_policy.py")]


class SourceTest(unittest.TestCase):
    def test_every_file_of_the_slice_is_plain_lf(self):
        source.assert_lf_only(self, *pm.X1B_MAIN_FILES, pm.CHROME_STATE_KT, *JVM_TEST_FILES, *PY_FILES)

    def test_the_main_files_are_ascii_except_the_strings_object_and_declare_the_ui_package(self):
        for path in pm.X1B_MAIN_FILES:
            text = pm.read(path)
            self.assertTrue(text.startswith("package com.bilipai.desktop.ui\n"), path.name)
            if path != pm.STRINGS_KT:
                self.assertTrue(text.isascii(), path.name)

    def test_the_policy_files_are_pure(self):
        allowed_imports = {
            "DesktopPlayerLayoutPolicy.kt": ["import com.bilipai.desktop.appearance.DesktopWindowsUxDetailsPanel"],
            "DesktopPlayerChromePolicy.kt": ["import com.bilipai.desktop.player.PlayerState"],
            "DesktopPlayerShortcutPolicy.kt": ["import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout"],
            "DesktopPlayerSeekModel.kt": ["import kotlin.math.abs"],
            "DesktopPlayerQualityRows.kt": [],
            "DesktopPlayerStrings.kt": [],
        }
        for path in pm.X1B_MAIN_FILES:
            text = pm.read(path)
            self.assertEqual(allowed_imports[path.name], re.findall(r"(?m)^import .*$", text), path.name)
            code = source.mask_kotlin(text)
            for banned in ("androidx", "java.awt", "javax.swing", "KeyEvent", "Toolkit", "System.currentTimeMillis", "Composable", "remember(",
                           "mutableStateOf", "KeyFallback", "MpvPlayer", "SwingPanel", "LocalDensity"):
                self.assertNotIn(banned, code, "%s uses %s" % (path.name, banned))
            for pattern in (r"\bthrow\b", r"\berror\(", r"\brequire\(", r"\bcheck\(", r"\brequireNotNull\(", r"\bcheckNotNull\(", r"!!",
                            r"\bTODO\(", r"\bassert\("):
                self.assertIsNone(re.search(pattern, code), "%s contains %s" % (path.name, pattern))

    def test_the_chrome_state_edit_adds_no_import_and_changes_only_its_constructor_and_constant(self):
        imports = re.findall(r"(?m)^import (.*)$", STATE_TEXT)
        self.assertEqual([
            "androidx.compose.runtime.getValue", "androidx.compose.runtime.mutableLongStateOf", "androidx.compose.runtime.mutableStateOf",
            "androidx.compose.runtime.setValue", "com.android.purebilibili.feature.video.ui.overlay.FullscreenGestureMode",
            "com.android.purebilibili.feature.video.ui.overlay.shouldAutoHideFullscreenControls", "com.bilipai.desktop.player.PlayerState",
            "androidx.compose.ui.Modifier", "androidx.compose.ui.input.pointer.PointerEventType",
            "androidx.compose.ui.input.pointer.onPointerEvent",
        ], imports)
        self.assertNotIn("DesktopPlayer", STATE_TEXT)

    def test_no_top_level_name_of_the_slice_is_declared_anywhere_else_in_desktop_src(self):
        declaration = re.compile(r"(?m)^(?:internal |private |public )?(?:const )?(?:data |enum |sealed |value )?"
                                 r"(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]+>\s*)?(?:[\w.<>?, ]+\.)?(\w+)")
        mine = {}
        for path in pm.X1B_MAIN_FILES:
            for match in declaration.finditer(pm.read(path)):
                mine.setdefault(match.group(1), []).append(path.name)
        self.assertGreater(len(mine), 60)
        self.assertEqual([], [name for name, files in mine.items() if len(files) > 1])
        found = []
        for path in sorted((source.REPO / "desktop/src").rglob("*.kt")):
            if path in pm.X1B_MAIN_FILES:
                continue
            for match in declaration.finditer(pm.read(path)):
                if match.group(1) in mine:
                    found.append((match.group(1), path.name))
        self.assertEqual([], found)

    def test_the_java_dependent_math_is_the_only_jvm_surface_and_there_is_no_floor_div_on_negatives(self):
        # desktopPlayerRoundHalfEven takes a non-negative numerator; every caller passes a value held at or above zero.
        code = pm.squash(source.mask_kotlin(LAYOUT_TEXT))
        for numerator in ("panelN", "sideN", "verticalN", "videoWidthN", "videoHeightN", "dockN"):
            self.assertIn("desktopPlayerRoundHalfEven(%s," % numerator, code, numerator)
        self.assertIn(".coerceAtLeast(0L)", code)


if __name__ == "__main__":
    unittest.main()
