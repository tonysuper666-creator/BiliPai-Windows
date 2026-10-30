"""Slice W3 checks: window bounds persistence (spec 3.5, hunk RH8). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_window_bounds.py

What is proved.

  ContractTest       the section window_bounds of the contract (keys, constants, kinds, layouts, hand written golden rows) equals what the
                     Kotlin model declares; the layout of the measured 5120x2160 panel at Windows scale 150% follows from displays.primary;
                     the golden rows are reproduced by the Python reference model (ux_window_model.py)
  PlanOracleTest     the model agrees with an independent oracle (cell counting for the overlap, brute force search for the nearest position,
                     sorting for the monitor choice) over thousands of random layouts of a miniature contract, and keeps the invariants
                     of the plan over a grid of the real layouts: on the monitor, at least the minimum, stable when planned again; the
                     golden rows have teeth (a changed rule moves at least one row)
  TrackerOracleTest  the tracker agrees with a declarative oracle over every sequence of up to three samples from a small alphabet, for
                     several seeds
  SourceShapeTest    the Kotlin model is pure (no import at all), its text carries the transliterated lines, the adapter reads and writes
                     the windows_ux window keys only, reads the zoom namespace and never writes it, saves a settle time after the last
                     change and not on dispose (the shell freezes the store first; pinned by source facts), and nothing in the new main
                     files throws
  MainHunkTest       restored window state reaches the window and fullscreen consumers; the title stays static
  FilesTest          line endings and control bytes of every file of the slice, the JVM test classes exist and create no window

What is not proved. That the Kotlin compiles or runs. DesktopWindowsUxWindowBoundsModelTest and DesktopWindowsUxWindowBoundsAdapterTest are
the proof of the pure parts on the Linux compile later. These are [W] items for the user's PC: that a restored window really lands where the
plan says on a multi monitor Windows desktop with mixed scale factors (AWT user space origin of a monitor with another scale than the primary
one), that Compose reports the maximised and the snapped states the way the tracker assumes, that the invisible frame border really is the
7 to 8 units the tolerance of 16 assumes, and that the taskbar is 48 units high. The listener of the pinned Compose window code was read in the
class file (componentResized writes placement and size, componentMoved writes the position, in AWT units, in every placement); a compiler and a
window are still needed.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in desktop/tools/tests as spec 8.2 and 9.2
say; Root adds each suite to the validation plan as an explicit `-s desktop/tools/ux -p <file>` line.

Slice notes, kept here with the pins they explain.
  * Where the controller lives. The windows_ux controller of slice X0 is created in the shell (hunk RH1), after the window exists. The
    bounds are needed before the window is created and must be saved when the application ends, so this slice does not hoist the
    controller (that would edit DesktopShell.kt, which hunk RH8 does not name). It reads the pure decode function at startup and writes with
    the pure edit functions and the same atomic plugin store call. The two writers use disjoint keys, so they cannot disturb each other.
  * Hand-off for W6 (the dynamic title): it edits the `title =` line of Main.kt; add its hunk to MAIN_HUNKS below and keep the base blob pin
    in step in the same commit.
"""
import copy
import itertools
import math
import os
import random
import re
import subprocess
import sys
import unittest
import zipfile
from fractions import Fraction
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_model as model  # noqa: E402
import ux_source as source  # noqa: E402
import ux_window_model as wm  # noqa: E402

CONTRACT = model.load()
SECTION = CONTRACT[wm.SECTION]

MODEL_KT = wm.MODEL_KT
ADAPTER_KT = wm.ADAPTER_KT
MAIN_KT = source.K / "Main.kt"
MODEL_TEST_KT = source.KT / "appearance/DesktopWindowsUxWindowBoundsModelTest.kt"
ADAPTER_TEST_KT = source.KT / "appearance/DesktopWindowsUxWindowBoundsAdapterTest.kt"
MODEL_CODE = source.mask_kotlin(source.read(MODEL_KT))
ADAPTER_CODE = source.mask_kotlin(source.read(ADAPTER_KT))



def squash(text):
    return " ".join(text.split())


def b(x, y, width, height, maximized=False):
    return wm.bounds(x, y, width, height, maximized)


def screen(x, y, width, height, primary=False):
    return {"x": x, "y": y, "width": width, "height": height, "primary": primary}


# --------------------------------------------------------------------------------------
# Contract
# --------------------------------------------------------------------------------------

class ContractTest(unittest.TestCase):
    def test_the_section_has_exactly_the_documented_keys(self):
        self.assertEqual({"about", "default_size", "frame_allowance", "min_base", "edge_tolerance", "settle_ms", "plan_kinds", "placements", "rules",
                          "layouts", "golden_plan", "minimum_extent", "golden_tracker"}, set(SECTION))
        self.assertIn(wm.SECTION, list(CONTRACT)[-3:], "the section follows the sections of the earlier slices")

    def test_the_contract_and_its_kotlin_copy_are_in_sync(self):
        self.assertEqual([], model.problems())

    def test_the_golden_rows_of_the_contract_are_reproduced_by_the_reference_model(self):
        self.assertEqual([], wm.problems(CONTRACT))

    def test_the_values_are_the_ones_the_spec_and_the_earlier_behaviour_fix(self):
        self.assertEqual([1600, 1000], SECTION["default_size"], "desktop first-launch client target")
        self.assertEqual([16, 39], SECTION["frame_allowance"], "maximum AWT frame insets measured at 100% and 150%")
        self.assertEqual(CONTRACT["tokens"]["min_window_dp"], SECTION["min_base"], "the minimum window of the display scale contract")
        self.assertEqual(list(wm.KINDS), SECTION["plan_kinds"])
        self.assertEqual(list(wm.PLACEMENTS), SECTION["placements"])
        self.assertGreater(SECTION["settle_ms"], 0)
        self.assertGreaterEqual(SECTION["edge_tolerance"], 8, "the invisible frame border of a Windows 10 or 11 window is 7 or 8 units")

    def test_the_kotlin_constants_equal_the_contract(self):
        text = source.read(MODEL_KT)
        begin, end = "// UX-WINDOW-BOUNDS-BEGIN", "// UX-WINDOW-BOUNDS-END"
        self.assertEqual(1, text.count(begin))
        self.assertEqual(1, text.count(end))
        block = text[text.index(begin) + len(begin):text.index(end)]
        constants = dict(re.findall(r"internal const val (\w+) = (\d+)L?\n", block))
        self.assertEqual({"WINDOWS_UX_WINDOW_DEFAULT_WIDTH": str(SECTION["default_size"][0]),
                          "WINDOWS_UX_WINDOW_DEFAULT_HEIGHT": str(SECTION["default_size"][1]),
                          "WINDOWS_UX_WINDOW_FRAME_WIDTH": str(SECTION["frame_allowance"][0]),
                          "WINDOWS_UX_WINDOW_FRAME_HEIGHT": str(SECTION["frame_allowance"][1]),
                          "WINDOWS_UX_WINDOW_MIN_BASE_WIDTH": str(SECTION["min_base"][0]),
                          "WINDOWS_UX_WINDOW_MIN_BASE_HEIGHT": str(SECTION["min_base"][1]),
                          "WINDOWS_UX_WINDOW_EDGE_TOLERANCE": str(SECTION["edge_tolerance"]),
                          "WINDOWS_UX_WINDOW_SETTLE_MS": str(SECTION["settle_ms"])}, constants)
        self.assertIn("WINDOWS_UX_WINDOW_SETTLE_MS = %dL" % SECTION["settle_ms"], block, "a Long constant")

    def test_the_kotlin_enums_equal_the_contract_lists(self):
        text = source.read(MODEL_KT)
        kinds = re.search(r"internal enum class DesktopWindowsUxPlanKind \{(.*?)\n\}", text, re.S).group(1)
        self.assertEqual(SECTION["plan_kinds"], re.findall(r"^    ([A-Z_]+),$", kinds, re.M))
        placement = re.search(r"internal enum class DesktopWindowsUxPlacement \{ ([^}]*) \}", text).group(1)
        self.assertEqual(SECTION["placements"], [name.strip() for name in placement.split(",")])

    def test_the_zoom_range_of_the_minimum_extent_is_the_display_scale_range(self):
        zoom = CONTRACT["tokens"]["zoom_percent"]
        display = source.read(source.K / "appearance/DesktopWindowsDisplayScale.kt")
        self.assertIn("WINDOWS_DISPLAY_MIN_PERCENT = %d" % zoom["min"], display)
        self.assertIn("WINDOWS_DISPLAY_MAX_PERCENT = %d" % zoom["max"], display)
        for percent, width, height in ((zoom["min"] - 40, 480, 340), (zoom["max"] + 799, 1920, 1360)):
            self.assertEqual((width, height), tuple(wm.minimum_extent(CONTRACT, base, percent) for base in SECTION["min_base"]))

    def test_the_minimum_extent_at_the_default_zoom_is_the_1200_by_850_the_window_already_enforces(self):
        default = CONTRACT["tokens"]["zoom_percent"]["default"]
        self.assertEqual((1200, 850), tuple(wm.minimum_extent(CONTRACT, base, default) for base in SECTION["min_base"]))
        # desktopWindowsSafeMinimumSize: 960 * percent / 100 and 680 * percent / 100 in AWT units (system density cancels).
        scale = source.read(source.K / "appearance/DesktopWindowsDisplayScaleScope.kt")
        self.assertIn("(960 * physicalDensity / awtScaleX)", scale)
        self.assertIn("(680 * physicalDensity / awtScaleY)", scale)

    def test_every_row_names_a_known_layout_and_every_layout_is_used(self):
        layouts = SECTION["layouts"]
        used = {row["layout"] for row in SECTION["golden_plan"]}
        self.assertEqual(set(layouts), used)
        for name, layout in layouts.items():
            self.assertTrue(layout["about"].strip(), name)
            self.assertLessEqual(sum(1 for item in layout["screens"] if item.get("primary")), 1, name)
            for item in layout["screens"]:
                self.assertGreater(item["width"], 0, name)
                self.assertGreater(item["height"], 0, name)

    def test_row_ids_are_unique_and_the_rows_cover_every_kind_and_the_named_cases(self):
        for key in ("golden_plan", "golden_tracker"):
            ids = [row["id"] for row in SECTION[key]]
            self.assertEqual(len(ids), len(set(ids)), key)
        kinds = {row["expect"]["kind"] for row in SECTION["golden_plan"]}
        self.assertEqual(set(wm.KINDS), kinds)
        text = " ".join(row["id"] for row in SECTION["golden_plan"])
        for word in ("vanished", "negative", "5k2k", "maximised", "straddling", "equal-shares", "touching", "primary", "tolerat"):
            self.assertIn(word, text)
        # a negative origin really is in the layouts
        origins = [item for layout in SECTION["layouts"].values() for item in layout["screens"]]
        self.assertTrue(any(item["x"] < 0 for item in origins))
        self.assertTrue(any(item["y"] < 0 for item in origins))

    def test_the_measured_primary_panel_layout_follows_from_the_display_facts(self):
        display = CONTRACT["displays"]["primary"]
        self.assertEqual("measured", display["status"])
        self.assertEqual([5120, 2160], display["px"])
        self.assertEqual(150, display["windows_scale_percent"])
        scale = Fraction(display["windows_scale_percent"], 100)
        width, height = (model.round_int(Fraction(pixels) / scale) for pixels in display["px"])
        self.assertEqual((3413, 1440), (width, height))
        taskbar = 48  # assumed, a real-window item
        layout = SECTION["layouts"]["primary-5k2k-150"]["screens"]
        self.assertEqual([{"x": 0, "y": 0, "width": width, "height": height - taskbar, "primary": True}], layout)
        # the 4K 32 inch (assumed display) and the 1080p layouts follow the same arithmetic
        second = CONTRACT["displays"]["secondary"]
        second_scale = Fraction(second["windows_scale_percent"], 100)
        second_size = tuple(model.round_int(Fraction(pixels) / second_scale) for pixels in second["px"])
        self.assertEqual((2560, 1440), second_size)
        self.assertEqual({"x": 0, "y": 0, "width": 2560, "height": 1440 - taskbar, "primary": True},
                         SECTION["layouts"]["secondary-4k-150"]["screens"][0])
        self.assertEqual([{"x": 0, "y": 0, "width": 1536, "height": 864 - taskbar, "primary": True}],
                         SECTION["layouts"]["laptop-1080-125"]["screens"])
        self.assertEqual([{"x": 0, "y": 0, "width": 1920, "height": 1080 - taskbar, "primary": True}],
                         SECTION["layouts"]["laptop-1080-100"]["screens"])

    def test_the_rows_use_the_same_units_as_the_flag_store(self):
        limits = CONTRACT["flags"]["window"]
        for row in SECTION["golden_plan"]:
            saved = row["saved"]
            if saved is not None and wm.is_sane(CONTRACT, saved):
                self.assertLessEqual(abs(saved["x"]), limits["max_abs_origin"])
        self.assertIn("AWT", limits["unit"])


# --------------------------------------------------------------------------------------
# The plan against an independent oracle
# --------------------------------------------------------------------------------------

def miniature():
    """A contract with small numbers, so that a brute force oracle can enumerate every position."""
    contract = copy.deepcopy(CONTRACT)
    contract["flags"]["window"]["min_extent"] = 1
    section = contract[wm.SECTION]
    section["edge_tolerance"] = 3
    section["min_base"] = [20, 14]
    section["default_size"] = [40, 30]
    return contract


def cells(start, length):
    return set(range(start, start + length))


def oracle_default(sec, screens, zoom):
    """First launch: add frame to client target, honour zoom, fit and centre in the usable display."""
    live = [s for s in screens if min(s["width"], s["height"]) > 0]
    chosen = next((s for s in live if s["primary"]), live[0] if live else None)
    extents = [max(default + frame, math.floor(base * min(200, max(50, zoom)) / 100 + .5))
               for default, frame, base in zip(sec["default_size"], sec["frame_allowance"], sec["min_base"])]
    if chosen is not None:
        extents = [min(extents[i], chosen[key]) for i, key in enumerate(("width", "height"))]
    return dict(kind="DEFAULT", x=chosen["x"] + (chosen["width"] - extents[0]) // 2 if chosen else None,
                y=chosen["y"] + (chosen["height"] - extents[1]) // 2 if chosen else None,
                width=extents[0], height=extents[1], maximized=False,
                screen=next((i for i, item in enumerate(screens) if item is chosen), None))


def oracle_plan(contract, saved, screens, zoom):
    """The plan by its definition, written differently from the model: counted cells, a sort, a brute force search."""
    sec = contract[wm.SECTION]
    limits = contract["flags"]["window"]
    sane = saved is not None and all(limits["min_extent"] <= saved[k] <= limits["max_extent"] for k in ("width", "height")) \
        and all(abs(saved[k]) <= limits["max_abs_origin"] for k in ("x", "y"))
    if not sane:
        return oracle_default(sec, screens, zoom)
    percent = min(max(zoom, 50), 200)
    min_w = math.floor(Fraction(sec["min_base"][0] * percent, 100) + Fraction(1, 2))  # half up
    min_h = math.floor(Fraction(sec["min_base"][1] * percent, 100) + Fraction(1, 2))
    live = [(i, s) for i, s in enumerate(screens) if s["width"] > 0 and s["height"] > 0]
    if not live:
        return {"kind": "UNVALIDATED", "x": None, "y": None, "width": max(saved["width"], min_w), "height": max(saved["height"], min_h),
                "maximized": saved["maximized"], "screen": None}

    def shared(screen_):
        return len(cells(saved["x"], saved["width"]) & cells(screen_["x"], screen_["width"])) * \
            len(cells(saved["y"], saved["height"]) & cells(screen_["y"], screen_["height"]))

    ranked = sorted(live, key=lambda item: (-shared(item[1]), item[0]))
    vanished = shared(ranked[0][1]) == 0
    if vanished:
        flagged = [item for item in live if item[1]["primary"]]
        index, usable = (flagged or live)[0]
    else:
        index, usable = ranked[0]
    width = saved["width"]
    if width < min_w:
        width = min_w
    if width > usable["width"]:
        width = usable["width"]
    height = saved["height"]
    if height < min_h:
        height = min_h
    if height > usable["height"]:
        height = usable["height"]
    tolerance = sec["edge_tolerance"]
    if vanished:
        free_x, free_y = usable["width"] - width, usable["height"] - height
        x = usable["x"] + free_x // 2
        y = usable["y"] + free_y // 2
        kind = "RELOCATED"
    else:
        allowed_x = range(usable["x"] - tolerance, usable["x"] + usable["width"] + tolerance - width + 1)
        allowed_y = range(usable["y"], usable["y"] + usable["height"] + tolerance - height + 1)
        x = min(allowed_x, key=lambda value: (abs(value - saved["x"]), value))
        y = min(allowed_y, key=lambda value: (abs(value - saved["y"]), value))
        kind = "KEPT" if (x, y, width, height) == (saved["x"], saved["y"], saved["width"], saved["height"]) else "ADJUSTED"
    return {"kind": kind, "x": x, "y": y, "width": width, "height": height, "maximized": saved["maximized"], "screen": index}


def random_layout(rng):
    count = rng.choice((0, 1, 1, 2, 2, 3))
    screens = []
    for _ in range(count):
        screens.append(screen(rng.randint(-140, 140), rng.randint(-100, 100), rng.randint(30, 120), rng.randint(25, 90),
                              rng.random() < 0.4))
    if rng.random() < 0.1 and screens:
        screens[0]["width"] = 0  # an empty monitor is ignored
    return screens


class PlanOracleTest(unittest.TestCase):
    def test_the_model_agrees_with_the_oracle_over_random_layouts_of_a_miniature_contract(self):
        contract = miniature()
        rng = random.Random(20261020)
        kinds = set()
        for _ in range(6000):
            screens = random_layout(rng)
            saved = None if rng.random() < 0.05 else b(rng.randint(-200, 260), rng.randint(-160, 200), rng.randint(1, 160),
                                                      rng.randint(1, 120), rng.random() < 0.5)
            zoom = rng.choice((50, 80, 100, 125, 150, 200, 250))
            got, want = wm.plan(contract, saved, screens, zoom), oracle_plan(contract, saved, screens, zoom)
            self.assertEqual(want, got, (saved, screens, zoom))
            kinds.add(got["kind"])
        self.assertEqual(set(wm.KINDS), kinds, "the random sweep reaches every kind")

    def test_the_interval_oracle_reproduces_every_golden_row_of_the_contract(self):
        # The hand written rows against a second formulation of the rules (interval arithmetic, a sort for the monitor choice).
        for row in SECTION["golden_plan"]:
            screens = wm.row_screens(CONTRACT, row)
            self.assertEqual(row["expect"], oracle_plan_fast(CONTRACT, row["saved"], screens, row.get("zoom_percent", 125)), row["id"])

    def test_invariants_over_a_grid_of_the_real_layouts(self):
        names = ("primary-5k2k-150", "laptop-1080-125", "primary-5k2k-with-4k-left", "primary-5k2k-with-1080-above",
                 "two-1080-side-by-side", "primary-listed-second", "primary-1080-with-1080-up-left")
        xs = (-6000, -2600, -2000, -400, -17, -16, -1, 0, 1, 500, 1600, 1900, 3000, 3400, 3413, 3500, 6000)
        ys = (-1200, -1032, -900, -300, -1, 0, 1, 300, 600, 1400, 2000)
        sizes = ((100, 100), (640, 480), (960, 680), (1360, 900), (1720, 1399), (3000, 1800), (32768, 32768))
        edge = SECTION["edge_tolerance"]
        checked = 0
        for name in names:
            screens = wm.layout_screens(CONTRACT, name)
            for zoom in (50, 100, 125, 200):
                min_w = wm.minimum_extent(CONTRACT, SECTION["min_base"][0], zoom)
                min_h = wm.minimum_extent(CONTRACT, SECTION["min_base"][1], zoom)
                for x, y, (width, height) in itertools.product(xs, ys, sizes):
                    saved = b(x, y, width, height, (x + y) % 2 == 0)
                    label = (name, zoom, saved)
                    plan = wm.plan(CONTRACT, saved, screens, zoom)
                    usable = screens[plan["screen"]]
                    self.assertLessEqual(plan["width"], usable["width"], label)
                    self.assertLessEqual(plan["height"], usable["height"], label)
                    self.assertGreaterEqual(plan["width"], min(min_w, usable["width"]), label)
                    self.assertGreaterEqual(plan["height"], min(min_h, usable["height"]), label)
                    self.assertGreaterEqual(plan["x"], usable["x"] - edge, label)
                    self.assertLessEqual(plan["x"] + plan["width"], usable["x"] + usable["width"] + edge, label)
                    self.assertGreaterEqual(plan["y"], usable["y"], label)
                    self.assertLessEqual(plan["y"] + plan["height"], usable["y"] + usable["height"] + edge, label)
                    self.assertEqual(saved["maximized"], plan["maximized"], label)
                    again = wm.plan(CONTRACT, wm.plan_bounds(plan), screens, zoom)
                    self.assertEqual(dict(plan, kind="KEPT"), again, label)
                    checked += 1
        self.assertGreater(checked, 10000)

    def test_default_is_centred_and_only_unknown_screens_leave_the_position_undecided(self):
        sane = b(100, 80, 1360, 900)
        self.assertEqual("DEFAULT", wm.plan(CONTRACT, None, [screen(0, 0, 1920, 1032, True)], 125)["kind"])
        self.assertEqual("DEFAULT", wm.plan(CONTRACT, b(0, 0, 99, 900), [screen(0, 0, 1920, 1032, True)], 125)["kind"])
        self.assertEqual("DEFAULT", wm.plan(CONTRACT, b(0, 0, 900, 32769), [screen(0, 0, 1920, 1032, True)], 125)["kind"])
        self.assertEqual("DEFAULT", wm.plan(CONTRACT, b(100001, 0, 900, 900), [screen(0, 0, 1920, 1032, True)], 125)["kind"])
        fresh = wm.plan(CONTRACT, None, [screen(0, 0, 1920, 1032, True)], 125)
        self.assertEqual((152, 0, 1616, 1032, 0), tuple(fresh[k] for k in ("x", "y", "width", "height", "screen")))
        self.assertEqual(wm.plan_bounds(fresh), wm.startup_seed(fresh, None))
        for screens in ([], [screen(0, 0, 0, 100, True)], [screen(0, 0, 100, -1, True)]):
            result = wm.plan(CONTRACT, sane, screens, 125)
            self.assertEqual("UNVALIDATED", result["kind"])
            self.assertIsNone(result["x"])
            self.assertIsNone(wm.plan_bounds(result))
            self.assertEqual(sane, wm.startup_seed(result, sane))
        default = wm.plan(CONTRACT, None, [], 125)
        self.assertIsNone(wm.startup_seed(default, None))

    def test_the_monitor_index_refers_to_the_list_that_was_passed_in_even_when_an_empty_monitor_is_skipped(self):
        screens = [screen(0, 0, 0, 0), screen(0, 0, 1920, 1032, True)]
        result = wm.plan(CONTRACT, b(100, 100, 1300, 900), screens, 125)
        self.assertEqual(1, result["screen"])
        self.assertEqual("KEPT", result["kind"])

    def test_the_golden_rows_have_teeth(self):
        # A changed rule moves at least one row, so the rows cannot pass by accident.
        def mutated(change):
            contract = copy.deepcopy(CONTRACT)
            change(contract[wm.SECTION])
            return wm.problems(contract)

        def tolerance(value):
            def change(section):
                section["edge_tolerance"] = value
            return change

        def min_base(section):
            section["min_base"] = [900, 600]

        def default(section):
            section["default_size"] = [1280, 800]

        for change in (tolerance(0), tolerance(8), tolerance(40), min_base, default):
            self.assertTrue(mutated(change))

        def touching_counts(a, c):
            width = min(a["x"] + a["width"], c["x"] + c["width"]) - max(a["x"], c["x"])
            height = min(a["y"] + a["height"], c["y"] + c["height"]) - max(a["y"], c["y"])
            return max(width, 0) * max(height, 0) + (1 if width == 0 and height > 0 else 0)

        with mock.patch.object(wm, "overlap_area", touching_counts):
            self.assertTrue(wm.problems(CONTRACT))
        with mock.patch.object(wm, "minimum_extent", lambda contract, base, percent: base * percent // 100 - 1):
            self.assertTrue(wm.problems(CONTRACT))

    def test_the_tolerance_is_asymmetric_at_the_top_edge(self):
        screens = [screen(0, 0, 1920, 1032, True)]
        edge = SECTION["edge_tolerance"]
        left = wm.plan(CONTRACT, b(-edge, 0, 1300, 900), screens, 125)
        self.assertEqual(("KEPT", -edge), (left["kind"], left["x"]))
        beyond = wm.plan(CONTRACT, b(-edge - 1, 0, 1300, 900), screens, 125)
        self.assertEqual(("ADJUSTED", -edge), (beyond["kind"], beyond["x"]))
        top = wm.plan(CONTRACT, b(100, -1, 1300, 900), screens, 125)
        self.assertEqual(("ADJUSTED", 0), (top["kind"], top["y"]))
        bottom = wm.plan(CONTRACT, b(100, 1032 + edge - 900, 1300, 900), screens, 125)
        self.assertEqual("KEPT", bottom["kind"])
        past = wm.plan(CONTRACT, b(100, 1032 + edge - 900 + 1, 1300, 900), screens, 125)
        self.assertEqual(("ADJUSTED", 1032 + edge - 900), (past["kind"], past["y"]))


def oracle_plan_fast(contract, saved, screens, zoom):
    """The oracle for the real numbers: the same definition with interval arithmetic instead of enumeration. Used on the golden rows
    only (cell counting would take minutes at 3413 by 1392)."""
    sec = contract[wm.SECTION]
    if saved is None or not wm.is_sane(contract, saved):
        return oracle_default(sec, screens, zoom)
    percent = min(max(zoom, 50), 200)
    min_w = (sec["min_base"][0] * percent * 2 + 100) // 200
    min_h = (sec["min_base"][1] * percent * 2 + 100) // 200
    live = [(i, s) for i, s in enumerate(screens) if s["width"] > 0 and s["height"] > 0]
    if not live:
        return {"kind": "UNVALIDATED", "x": None, "y": None, "width": max(saved["width"], min_w), "height": max(saved["height"], min_h),
                "maximized": saved["maximized"], "screen": None}

    def shared(s):
        w = min(saved["x"] + saved["width"], s["x"] + s["width"]) - max(saved["x"], s["x"])
        h = min(saved["y"] + saved["height"], s["y"] + s["height"]) - max(saved["y"], s["y"])
        return w * h if w > 0 and h > 0 else 0

    ranked = sorted(live, key=lambda item: (-shared(item[1]), item[0]))
    vanished = shared(ranked[0][1]) == 0
    index, usable = ((([item for item in live if item[1]["primary"]]) or live)[0]) if vanished else ranked[0]
    width = min(saved["width"] if saved["width"] >= min_w else min_w, usable["width"])
    height = min(saved["height"] if saved["height"] >= min_h else min_h, usable["height"])
    tolerance = sec["edge_tolerance"]

    def nearest(value, low, high):
        return low if value < low else high if value > high else value

    if vanished:
        x, y, kind = usable["x"] + (usable["width"] - width) // 2, usable["y"] + (usable["height"] - height) // 2, "RELOCATED"
    else:
        x = nearest(saved["x"], usable["x"] - tolerance, usable["x"] + usable["width"] + tolerance - width)
        y = nearest(saved["y"], usable["y"], usable["y"] + usable["height"] + tolerance - height)
        kind = "KEPT" if (x, y, width, height) == (saved["x"], saved["y"], saved["width"], saved["height"]) else "ADJUSTED"
    return {"kind": kind, "x": x, "y": y, "width": width, "height": height, "maximized": saved["maximized"], "screen": index}


# --------------------------------------------------------------------------------------
# The tracker against a declarative oracle
# --------------------------------------------------------------------------------------

def oracle_tracker(contract, seed, samples):
    """The bounds by their definition: the rectangle is the last sane floating sample, else the sane seed, else the first sane
    maximised sample; the flag follows the last decisive sample (a maximised one, or a floating one with a sane rectangle)."""
    seed_ok = seed is not None and wm.is_sane(contract, seed)
    usable = [item for item in samples if not item["minimized"]]

    def rect(item):
        return (item["x"], item["y"], item["width"], item["height"])

    def sane(item):
        return wm.is_sane(contract, b(*rect(item)))

    floats = [item for item in usable if item["placement"] == "FLOATING" and sane(item)]
    maxes = [item for item in usable if item["placement"] == "MAXIMIZED" and sane(item)]
    if floats:
        chosen = rect(floats[-1])
    elif seed_ok:
        chosen = (seed["x"], seed["y"], seed["width"], seed["height"])
    elif maxes:
        chosen = rect(maxes[0])
    else:
        return None
    decisive = [item for item in usable if item["placement"] == "MAXIMIZED" or (item["placement"] == "FLOATING" and sane(item))]
    flag = decisive[-1]["placement"] == "MAXIMIZED" if decisive else (seed["maximized"] if seed_ok else False)
    return b(*chosen, flag)


class TrackerOracleTest(unittest.TestCase):
    def test_the_tracker_agrees_with_the_oracle_over_every_short_sequence(self):
        rects = ((100, 50, 1400, 950), (700, 300, 1300, 850), (0, 0, 0, 0))
        alphabet = [wm.sample(placement, *rect, minimized=minimized)
                    for placement in wm.PLACEMENTS for minimized in (False, True) for rect in rects]
        seeds = (None, b(10, 20, 1360, 900), b(10, 20, 1360, 900, True), b(0, 0, 5, 5, True))
        checked = 0
        for seed in seeds:
            for length in range(0, 4):
                for samples in itertools.product(alphabet, repeat=length):
                    want = oracle_tracker(CONTRACT, seed, samples)
                    got = wm.run_tracker(CONTRACT, seed, list(samples))
                    self.assertEqual(want, got, (seed, samples))
                    checked += 1
        self.assertGreater(checked, 20000)

    def test_a_tracker_that_saves_the_maximised_rectangle_would_be_caught_by_the_golden_rows(self):
        rows = {row["id"]: row for row in SECTION["golden_tracker"]}
        row = rows["maximise-keeps-the-floating-rectangle"]
        wrong = wm.run_tracker(CONTRACT, None, [row["samples"][1]])
        self.assertNotEqual(row["expect"], wrong)

    def test_the_golden_tracker_rows_cover_the_documented_behaviours(self):
        ids = " ".join(row["id"] for row in SECTION["golden_tracker"])
        for word in ("fullscreen", "minimised", "maximise", "restore", "insane", "seed", "fallback"):
            self.assertIn(word, ids)


# --------------------------------------------------------------------------------------
# Source shape
# --------------------------------------------------------------------------------------

MODEL_NEEDLES = (
    "if (saved == null || !saved.isSane()) {",
    "val minWidth = desktopWindowsUxMinimumExtent(WINDOWS_UX_WINDOW_MIN_BASE_WIDTH, zoomPercent)",
    "val minHeight = desktopWindowsUxMinimumExtent(WINDOWS_UX_WINDOW_MIN_BASE_HEIGHT, zoomPercent)",
    "val candidates = screens.withIndex().filter { it.value.usable.width > 0 && it.value.usable.height > 0 }",
    "if (area > bestArea) {",
    "val vanished = best == null",
    "val target = best ?: candidates.firstOrNull { it.value.primary } ?: candidates.first()",
    "val width = minOf(maxOf(saved.width, minWidth), usable.width)",
    "val height = minOf(maxOf(saved.height, minHeight), usable.height)",
    "x = usable.x + (usable.width - width) / 2",
    "y = usable.y + (usable.height - height) / 2",
    "x = minOf(maxOf(saved.x, usable.x - edge), usable.right + edge - width)",
    "y = minOf(maxOf(saved.y, usable.y), usable.bottom + edge - height)",
    "x == saved.x && y == saved.y && width == saved.width && height == saved.height -> DesktopWindowsUxPlanKind.KEPT",
    "(base * zoomPercent.coerceIn(WINDOWS_DISPLAY_MIN_PERCENT, WINDOWS_DISPLAY_MAX_PERCENT) + 50) / 100",
    "return if (width > 0L && height > 0L) width * height else 0L",
    "if (sample.minimized) return",
    "if (floating == null && sane) floating = DesktopWindowsUxRect(sample.x, sample.y, sample.width, sample.height)",
    "val seed = if (fromPlan != null) fromPlan else if (plan.kind == DesktopWindowsUxPlanKind.UNVALIDATED) saved else null",
)


def missing_needles(code, needles):
    flat = squash(code)
    return [needle for needle in needles if squash(needle) not in flat]


class SourceShapeTest(unittest.TestCase):
    def test_the_model_file_is_pure(self):
        text = source.read(MODEL_KT)
        self.assertTrue(text.startswith("package com.bilipai.desktop.appearance\n"))
        self.assertEqual([], re.findall(r"(?m)^import .*$", text), "the pure model imports nothing")
        for forbidden in ("androidx", "java.", "javax.", "kotlinx", "Toolkit", "ComposeWindow", "WindowState", "Composable",
                          "Dispatchers", "Thread", "System.", "File", "Path"):
            self.assertNotIn(forbidden, MODEL_CODE, forbidden)

    def test_the_model_carries_the_transliterated_lines(self):
        self.assertEqual([], missing_needles(MODEL_CODE, MODEL_NEEDLES))

    def test_the_needle_check_has_teeth(self):
        mutations = (
            ("if (area > bestArea) {", "if (area >= bestArea) {"),
            ("usable.right + edge - width)", "usable.right - width)"),
            ("y = minOf(maxOf(saved.y, usable.y), usable.bottom + edge - height)",
             "y = minOf(maxOf(saved.y, usable.y - edge), usable.bottom + edge - height)"),
            ("if (sample.minimized) return", "if (sample.minimized) Unit"),
            ("width * height else 0L", "width * height else 1L"),
        )
        for old, new in mutations:
            self.assertIn(old, MODEL_CODE)
            self.assertTrue(missing_needles(MODEL_CODE.replace(old, new), MODEL_NEEDLES), old)

    def test_nothing_in_the_new_main_files_throws(self):
        for name, code in (("model", MODEL_CODE), ("adapter", ADAPTER_CODE)):
            for pattern in (r"\bthrow\b", r"\berror\(", r"\brequire\(", r"\bcheck\(", r"\brequireNotNull\(", r"\bcheckNotNull\(", r"!!",
                            r"\bTODO\("):
                self.assertIsNone(re.search(pattern, code), "%s contains %s" % (name, pattern))

    def test_the_new_main_files_are_inside_the_armed_hygiene_scopes(self):
        strict = {source.rel(path) for path in source.kotlin_files(source.STRICT_TEXT_GLOBS)}
        self.assertIn("appearance/DesktopWindowsUxWindowBoundsModel.kt", strict)
        self.assertIn("appearance/DesktopWindowsUxWindowBounds.kt", strict)
        for path in (MODEL_KT, ADAPTER_KT):
            self.assertEqual([], source.cjk_positions(source.read(path)), path.name)
            self.assertEqual([], source.hook_uses(source.read(path)), path.name)
            self.assertEqual([], source.key_fallback_uses(source.read(path)), path.name)

    def test_the_adapter_imports_are_the_expected_ones(self):
        text = source.read(ADAPTER_KT)
        self.assertEqual([
            "androidx.compose.runtime.Composable", "androidx.compose.runtime.LaunchedEffect",
            "androidx.compose.runtime.remember", "androidx.compose.runtime.snapshotFlow", "androidx.compose.ui.unit.DpSize",
            "androidx.compose.ui.unit.dp", "androidx.compose.ui.window.WindowPlacement", "androidx.compose.ui.window.WindowPosition",
            "androidx.compose.ui.window.WindowState", "androidx.compose.ui.window.rememberWindowState",
            "com.bilipai.desktop.plugins.DesktopPluginStore", "com.bilipai.desktop.plugins.DesktopPreferenceSnapshot",
            "kotlinx.coroutines.Dispatchers", "kotlinx.coroutines.delay", "kotlinx.coroutines.flow.collectLatest",
            "kotlinx.coroutines.withContext", "java.awt.GraphicsEnvironment", "java.awt.Toolkit", "kotlin.math.roundToInt",
        ], re.findall(r"(?m)^import ([\w.]+)$", text))

    def test_the_store_writes_only_through_the_namespace_checked_atomic_update_and_only_the_window_edits(self):
        flat = squash(ADAPTER_CODE)
        self.assertEqual(1, ADAPTER_CODE.count("store.updateFromSnapshot("))
        self.assertIn("store.updateFromSnapshot(WINDOWS_UX_NAMESPACE) { edits }", flat)
        self.assertIn("val edits = desktopWindowsUxEdits(DesktopWindowsUxCommand.SetWindowBounds(bounds))", flat)
        self.assertIn("desktopWindowsUxEffectiveEdits(store.preferences(WINDOWS_UX_NAMESPACE), edits)", flat)
        for banned in ("store.update(", "updateOriginal", "Files.", "writeText", "FileOutputStream", "windowsUx.", "DesktopWindowsUxController"):
            self.assertNotIn(banned, ADAPTER_CODE, banned)
        # the edits of the command are the five window keys and nothing else (pure function of X0)
        model_text = source.read(source.K / "appearance/DesktopWindowsUxModel.kt")
        self.assertIn("is DesktopWindowsUxCommand.SetWindowBounds -> {", model_text)

    def test_the_zoom_namespace_is_only_read(self):
        code = ADAPTER_CODE
        self.assertEqual(1, code.count("WINDOWS_DISPLAY_NAMESPACE"))
        self.assertIn("decodeDesktopWindowsDisplay(DesktopPreferenceSnapshot(store.preferences(WINDOWS_DISPLAY_NAMESPACE))).percent",
                      squash(code))
        self.assertNotIn("scale_percent", code)
        # and the only updateFromSnapshot call uses the windows_ux namespace
        self.assertEqual(0, len(re.findall(r"updateFromSnapshot\(WINDOWS_DISPLAY", code)))

    def test_the_load_reads_the_pure_decoder_and_never_throws(self):
        flat = squash(ADAPTER_CODE)
        self.assertIn("decodeDesktopWindowsUx(DesktopPreferenceSnapshot(store.preferences(WINDOWS_UX_NAMESPACE))).windowBounds", flat)
        self.assertGreaterEqual(ADAPTER_CODE.count("catch (_: Exception)"), 4)
        # The default screen device throws an AWTError (an Error) when no screen exists; the probe must survive that too.
        self.assertEqual(1, ADAPTER_CODE.count("catch (_: java.awt.AWTError)"))
        self.assertLess(ADAPTER_CODE.index("environment.defaultScreenDevice"), ADAPTER_CODE.index("catch (_: java.awt.AWTError)"))

    def test_the_window_state_is_built_from_the_plan_with_the_earlier_state_as_the_default(self):
        flat = squash(ADAPTER_CODE)
        self.assertIn("placement = if (plan.maximized) WindowPlacement.Maximized else WindowPlacement.Floating", flat)
        self.assertIn("WindowPosition(planX.dp, planY.dp) else WindowPosition.PlatformDefault", flat)
        self.assertIn("size = DpSize(plan.width.dp, plan.height.dp)", flat)
        self.assertIn("desktopWindowsUxStartup(bounds.load(), desktopWindowsUxProbeScreens(), desktopWindowsUxStoredZoomPercent(store))", flat)
        self.assertEqual(1, ADAPTER_CODE.count("rememberWindowState("))
        self.assertIn("DesktopWindowsUxWindowBoundsEffect(state, bounds, startup.seed)", flat)

    def test_the_effect_saves_after_the_settle_time_only(self):
        flat = squash(ADAPTER_CODE)
        self.assertIn("snapshotFlow { desktopWindowsUxSampleOf(state) }.collectLatest { sample ->", flat)
        self.assertLess(flat.index("delay(WINDOWS_UX_WINDOW_SETTLE_MS)"), flat.index("tracker.accept(sample)"))
        self.assertIn("if (next != null) withContext(Dispatchers.IO) { bounds.save(next) }", flat)
        self.assertEqual(1, ADAPTER_CODE.count("LaunchedEffect("))
        self.assertEqual(1, ADAPTER_CODE.count("bounds.save("), "the effect is the only caller of save")
        self.assertNotIn("DisposableEffect", ADAPTER_CODE)
        self.assertNotIn("onDispose", ADAPTER_CODE)

    def test_there_is_no_save_when_the_composition_ends_because_the_shell_freezes_the_store_first(self):
        # The facts the design rests on. If one of them changes, a save on dispose may work and the settle time could shrink.
        main = source.read(MAIN_KT)
        close_app = main[main.index("fun closeApp("):main.index("val windowsDisplayScale = remember(")]
        self.assertLess(close_app.index("shutdown.get().invoke()"), close_app.index("exitApplication()"))
        shell = source.read(source.K / "DesktopShell.kt")
        registration = shell[shell.index("SideEffect {\n        registerShutdown?.invoke {"):]
        registration = registration[:registration.index("var mediaActive by remember")]
        self.assertIn("pluginRuntime.shutdownForRestore()", registration)
        runtime = source.read(source.K / "plugins/DesktopPluginRuntime.kt")
        self.assertIn("discovery?.freezeWritesForRestore()\n                store.freezeWrites()", runtime)
        store = source.read(source.K / "plugins/DesktopPluginStore.kt")
        update = store[store.index("internal fun updateFromSnapshot("):store.index("/** Publish a new snapshot only after")]
        self.assertIn("check(!backing.writesFrozen)", update)
        self.assertIn("settle_ms", " ".join(SECTION["rules"]))
        self.assertIn("no save when the application ends", " ".join(SECTION["rules"]))

    def test_the_sample_maps_every_placement_and_ignores_an_unspecified_position_or_size(self):
        flat = squash(ADAPTER_CODE)
        for name in ("Floating -> DesktopWindowsUxPlacement.FLOATING", "Maximized -> DesktopWindowsUxPlacement.MAXIMIZED",
                     "Fullscreen -> DesktopWindowsUxPlacement.FULLSCREEN"):
            self.assertIn("WindowPlacement." + name, flat)
        self.assertIn("if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) return null", flat)
        self.assertIn("minimized = state.isMinimized", flat)

    def test_the_monitor_probe_uses_the_same_awt_calls_as_the_existing_minimum_size_code(self):
        main = source.read(MAIN_KT)
        self.assertIn("window.graphicsConfiguration.bounds", main)
        self.assertIn("getScreenInsets(window.graphicsConfiguration)", main)
        flat = squash(ADAPTER_CODE)
        self.assertIn("toolkit.getScreenInsets(configuration)", flat)
        self.assertIn("if (GraphicsEnvironment.isHeadless())", flat)
        self.assertIn("x = bounds.x + insets.left", flat)
        self.assertIn("width = bounds.width - insets.left - insets.right", flat)

    def test_the_top_level_names_of_the_new_files_are_new_in_their_package(self):
        names = []
        for path in (MODEL_KT, ADAPTER_KT):
            code = source.mask_kotlin(source.read(path))
            names += re.findall(r"(?m)^(?:internal |private )?(?:data |enum |sealed )?(?:class|object|interface) (\w+)", code)
            names += re.findall(r"(?m)^(?:internal |private )?(?:const val|val) (\w+)", code)
            names += re.findall(r"(?m)^(?:@Composable )?(?:internal |private )?fun (?:<[^>]+> )?(\w+)\(", code)
        self.assertGreater(len(set(names)), 20)
        self.assertEqual(len(names), len(set(names)), "a name is declared twice inside the slice")
        own = {MODEL_KT.resolve(), ADAPTER_KT.resolve()}
        others = [path for path in (source.K / "appearance").glob("*.kt") if path.resolve() not in own]
        self.assertGreater(len(others), 10)
        for path in others:
            body = source.mask_kotlin(source.read(path))
            for name in set(names):
                self.assertIsNone(re.search(r"(?m)^(?:@Composable )?(?:internal |private |public )?(?:data |enum |sealed )?"
                                            r"(?:class|object|interface|const val|val|fun) (?:<[^>]+> )?%s\b" % re.escape(name), body),
                                  "%s is declared again in %s" % (name, source.relative_posix(path)))


# --------------------------------------------------------------------------------------
# Hunk RH8
# --------------------------------------------------------------------------------------

class MainHunkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(MAIN_KT)

    def test_window_bounds_are_restored_and_shared_by_window_and_fullscreen(self):
        code = source.mask_kotlin(self.text)
        self.assertIn("rememberDesktopWindowsUxWindowState(applicationPluginStore)", code)
        self.assertIn("state = windowState", code)
        self.assertIn("rememberDesktopWindowsFullscreenControl(window, windowState)", code)
        self.assertIn("windowState.placement == WindowPlacement.Fullscreen", code)

    def test_the_window_state_comes_from_the_store_backed_helper_and_the_old_call_is_gone(self):
        self.assertEqual(1, self.text.count("rememberDesktopWindowsUxWindowState("))
        self.assertNotIn("rememberWindowState", self.text)
        self.assertNotIn("1360", self.text)
        self.assertNotIn("900.dp", self.text)
        self.assertNotIn("androidx.compose.ui.unit.dp", self.text)
        self.assertEqual(0, len(re.findall(r"\b\d+\.dp\b", self.text)), "dp is not imported any more, so no dp literal may remain")

    def test_the_helper_gets_the_application_store_that_is_declared_before_it(self):
        call = self.text.index("rememberDesktopWindowsUxWindowState(applicationPluginStore)")
        declaration = self.text.index("val applicationPluginStore = remember {")
        self.assertLess(declaration, call)
        self.assertLess(call, self.text.index("val applicationScope = rememberCoroutineScope()"))
        self.assertLess(self.text.index("val gpuCapability = remember {"), call, "the call keeps its place after the GPU probe line")

    def test_the_window_state_consumers_are_untouched(self):
        self.assertEqual(5, len(re.findall(r"\bwindowState\b", self.text)))
        for line in ("state = windowState", "rememberDesktopWindowsDanmakuPresentation(window, windowState)",
                     "rememberDesktopWindowsFullscreenControl(window, windowState)",
                     "isFullscreen = { windowState.placement == WindowPlacement.Fullscreen }"):
            self.assertEqual(1, self.text.count(line), line)
        self.assertIn("import androidx.compose.ui.window.WindowPlacement\n", self.text)

    def test_the_title_stays_static(self):
        self.assertEqual(1, self.text.count('title = "BiliPai Windows",'))

    def test_the_other_pins_of_the_file_hold(self):
        for line in ("DesktopNvidiaCapabilityMonitor.process().also { it.warmUp() }",
                     "MpvPlayer(veyraComponent = veyraComponent, gpuCapability = gpuCapability)",
                     "val windowsDisplayScale = remember(applicationPluginStore, applicationScope) {",
                     "androidx.compose.ui.window.Window\n"):
            self.assertIn(line, self.text)

    def test_nothing_else_of_main_mentions_the_flags_store_or_the_bounds(self):
        for needle in ("WINDOWS_UX_NAMESPACE", "windows_ux", "DesktopWindowsUxCommand", "windowBounds", "window_x"):
            self.assertNotIn(needle, self.text)

    def test_the_helper_is_the_only_new_reference_and_names_the_expected_file(self):
        self.assertIn("fun rememberDesktopWindowsUxWindowState(store: DesktopPluginStore): WindowState {", source.read(ADAPTER_KT))


# --------------------------------------------------------------------------------------
# The Compose API the adapter uses, in the pinned jars
# --------------------------------------------------------------------------------------

def read_members(data):
    """({field names}, {(method name, descriptor)}) of one class file: the constant pool, then the field and method tables."""
    def u2(at):
        return int.from_bytes(data[at:at + 2], "big")

    def u4(at):
        return int.from_bytes(data[at:at + 4], "big")

    pool, at, index, count = {}, 10, 1, u2(8)
    while index < count:
        tag = data[at]
        if tag == 1:
            length = u2(at + 1)
            pool[index] = data[at + 3:at + 3 + length].decode("utf-8", "replace")
            at += 3 + length
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            at += 5
        elif tag in (5, 6):
            at += 9
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            at += 3
        elif tag == 15:
            at += 4
        else:
            raise ValueError("constant pool tag %d" % tag)
        index += 1
    at += 6  # access flags, this class, super class
    at += 2 + 2 * u2(at)  # the interface count and the interfaces

    def members(at):
        found = []
        count_here = u2(at)
        at += 2
        for _ in range(count_here):
            # access flags, name, descriptor, attribute count, then the attributes (name, 4 byte length, bytes)
            found.append((pool[u2(at + 2)], pool[u2(at + 4)]))
            attributes = u2(at + 6)
            at += 8
            for _ in range(attributes):
                at += 6 + u4(at + 2)
        return found, at

    fields, at = members(at)
    methods, at = members(at)
    return {name for name, _descriptor in fields}, set(methods)


class PinnedApiTest(unittest.TestCase):
    """The members of the Compose window API that the adapter and its tests use exist, with these descriptors, in the pinned jars.
    A zipfile scan, not a compiler. Skipped (visibly) when the pinned jars are not on this machine."""

    @classmethod
    def setUpClass(cls):
        version = re.search(r'id\("org\.jetbrains\.compose"\) version "([^"]+)"', source.read(source.REPO / "desktop/build.gradle.kts")).group(1)
        roots = [Path(item) for item in os.environ.get("BILIPAI_UX_JAR_DIRS", "").split(os.pathsep) if item]
        try:
            common = subprocess.run(["git", "-C", str(source.REPO), "rev-parse", "--git-common-dir"], capture_output=True, text=True,
                                    timeout=30).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            common = ""
        if common:
            checkout = Path(common).resolve().parent
            roots += [checkout / ".local/fsr/base-runtime", checkout.parent / "BiliPai/.local/fsr/base-runtime"]
        cls.jars = {}
        for family in ("ui-desktop", "ui-unit-desktop"):
            pattern = re.compile(r"^%s-%s(-[0-9a-f]+)?\.jar$" % (re.escape(family), re.escape(version)))
            for root in roots:
                found = sorted(jar for jar in root.glob("*.jar") if pattern.match(jar.name)) if root.is_dir() else []
                if found:
                    cls.jars[family] = found[0]
                    break
        cls.cache = {}

    def members(self, family, internal_name):
        if family not in self.jars:
            self.skipTest("the pinned %s jar is not on this machine" % family)
        key = (family, internal_name)
        if key not in self.cache:
            with zipfile.ZipFile(self.jars[family]) as archive:
                self.cache[key] = read_members(archive.read(internal_name + ".class"))
        return self.cache[key]

    def test_the_reader_finds_what_a_synthetic_class_file_declares(self):
        # constant pool: 1 utf8 "A", 2 class A, 3 utf8 "f", 4 utf8 "I", 5 utf8 "m", 6 utf8 "()V"
        def two(*numbers):
            return b"".join(number.to_bytes(2, "big") for number in numbers)

        def utf8(text):
            return bytes([1]) + two(len(text)) + text.encode("ascii")

        pool = utf8("A") + bytes([7]) + two(1) + utf8("f") + utf8("I") + utf8("m") + utf8("()V")
        head = b"\xca\xfe\xba\xbe" + two(0, 65, 7)  # minor, major, pool count (6 entries + 1)
        header = two(1, 2, 0, 0)  # access flags, this class, super class, interface count
        fields = two(1) + two(1, 3, 4, 0)  # one field: access, name, descriptor, no attributes
        methods = two(1) + two(1, 5, 6, 0)
        # a method with one attribute: name index, length 3, three bytes
        with_attribute = two(1) + two(1, 5, 6, 1) + two(1) + (3).to_bytes(4, "big") + b"abc"
        self.assertEqual(({"f"}, {("m", "()V")}), read_members(head + pool + header + fields + methods + two(0)))
        self.assertEqual(({"f"}, {("m", "()V")}), read_members(head + pool + header + fields + with_attribute + two(0)))

    def test_window_state_and_position(self):
        _fields, methods = self.members("ui-desktop", "androidx/compose/ui/window/WindowState")
        for member in (("getPlacement", "()Landroidx/compose/ui/window/WindowPlacement;"), ("isMinimized", "()Z"),
                       ("getPosition", "()Landroidx/compose/ui/window/WindowPosition;"), ("getSize-MYxV2XQ", "()J")):
            self.assertIn(member, methods)
        _fields, methods = self.members("ui-desktop", "androidx/compose/ui/window/WindowPosition")
        self.assertIn(("getX-D9Ej5fM", "()F"), methods)
        self.assertIn(("getY-D9Ej5fM", "()F"), methods)
        fields, _methods = self.members("ui-desktop", "androidx/compose/ui/window/WindowPosition$PlatformDefault")
        self.assertIn("INSTANCE", fields)
        _fields, methods = self.members("ui-desktop", "androidx/compose/ui/window/WindowPosition_desktopKt")
        self.assertIn(("WindowPosition-YgX7TsA", "(FF)Landroidx/compose/ui/window/WindowPosition$Absolute;"), methods)
        self.assertIn(("WindowPosition", "(Landroidx/compose/ui/Alignment;)Landroidx/compose/ui/window/WindowPosition$Aligned;"), methods)

    def test_the_placement_enum_has_exactly_the_three_entries_the_sample_maps(self):
        fields, _methods = self.members("ui-desktop", "androidx/compose/ui/window/WindowPlacement")
        self.assertEqual({"Floating", "Maximized", "Fullscreen"}, {name for name in fields if not name.startswith("$")})

    def test_remember_window_state_takes_placement_position_and_a_dp_size(self):
        _fields, methods = self.members("ui-desktop", "androidx/compose/ui/window/WindowState_desktopKt")
        remember = ("rememberWindowState-Y_qrr4g", "(Landroidx/compose/ui/window/WindowPlacement;ZLandroidx/compose/ui/window/WindowPosition;J"
                                                     "Landroidx/compose/runtime/Composer;II)Landroidx/compose/ui/window/WindowState;")
        self.assertIn(remember, methods)
        factory = "(Landroidx/compose/ui/window/WindowPlacement;ZLandroidx/compose/ui/window/WindowPosition;J)Landroidx/compose/ui/window/WindowState;"
        self.assertTrue({("WindowState-aAmSZ4M", factory), ("WindowState-vDgmGy4", factory)} & methods, "the WindowState factory the tests use")

    def test_dp_and_dp_size(self):
        _fields, methods = self.members("ui-unit-desktop", "androidx/compose/ui/unit/DpKt")
        self.assertIn(("DpSize-YgX7TsA", "(FF)J"), methods)
        _fields, methods = self.members("ui-unit-desktop", "androidx/compose/ui/unit/Dp")
        self.assertIn(("getValue", "()F"), methods)
        _fields, methods = self.members("ui-unit-desktop", "androidx/compose/ui/unit/DpSize")
        self.assertIn(("getWidth-D9Ej5fM", "(J)F"), methods)
        self.assertIn(("getHeight-D9Ej5fM", "(J)F"), methods)
        _fields, methods = self.members("ui-unit-desktop", "androidx/compose/ui/unit/DpSize$Companion")
        self.assertIn(("getUnspecified-MYxV2XQ", "()J"), methods)


# --------------------------------------------------------------------------------------
# Files
# --------------------------------------------------------------------------------------

class FilesTest(unittest.TestCase):
    def test_line_endings_and_control_bytes(self):
        source.assert_lf_only(self, MODEL_KT, ADAPTER_KT, MAIN_KT, MODEL_TEST_KT, ADAPTER_TEST_KT, Path(__file__).resolve(),
                              wm.HERE / "ux_window_model.py", model.CONTRACT_PATH, model.KOTLIN_COPY_PATH)

    def test_the_jvm_test_classes_exist_and_create_no_window(self):
        for path, name in ((MODEL_TEST_KT, "DesktopWindowsUxWindowBoundsModelTest"), (ADAPTER_TEST_KT, "DesktopWindowsUxWindowBoundsAdapterTest")):
            text = source.read(path)
            self.assertIn("package com.bilipai.desktop.appearance\n", text)
            self.assertIn("class %s {" % name, text)
            self.assertGreaterEqual(text.count("@Test fun "), 8, name)
            for forbidden in ("ImageComposeScene", "ComposeWindow", "application {", "runComposeUiTest", "createComposeRule",
                              "java.net", "HttpURLConnection", "ProcessBuilder"):
                self.assertNotIn(forbidden, text, name + ": " + forbidden)
            self.assertIsNone(re.search(r"\bWindow\(", text), name + ": a Window is created")

    def test_the_model_test_reads_the_contract_copy_and_the_adapter_test_uses_a_real_temporary_store(self):
        self.assertIn('DesktopUxContract.section("window_bounds")', source.read(MODEL_TEST_KT))
        adapter = source.read(ADAPTER_TEST_KT)
        self.assertIn('Files.createTempDirectory("bilipai-windows-ux-bounds-', adapter)
        self.assertIn("WindowState(", adapter)

    def test_the_new_python_files_stay_in_the_ux_folder(self):
        self.assertEqual(wm.HERE, Path(__file__).resolve().parent)
        self.assertEqual("desktop/tools/ux", source.relative_posix(wm.HERE))

    def test_the_main_edit_is_the_only_reserved_file_of_the_slice_that_this_suite_knows_about(self):
        # The registry keeps DesktopShell.kt, DesktopOriginalRootChrome.kt, the leaf and the build file for other hunks.
        shell = source.read(source.K / "DesktopShell.kt")
        self.assertNotIn("rememberDesktopWindowsUxWindowState", shell)
        self.assertNotIn("DesktopWindowsUxWindowBounds", shell)


if __name__ == "__main__":
    unittest.main()
