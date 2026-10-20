"""Slice H1 generator checks: the Home density layer on the generated Home text, its hook, its Gradle inputs and the wrappers it calls. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_layout_adaptations.py

What is proved. desktop/tools/ux_home_layout.py is run through the real sole Home producer (extract-upstream-home-page.py), three
times into temporary directories (with the layer, again with the layer, and with the layer patched out):

  GeneratorOutputTest  every anchor occurs exactly once in the text the layer receives; the layer changes exactly the generated Home screen,
                       the generated category page and the audit record, nothing else; the output is deterministic; reverse-applying the
                       edits gives back the received text byte for byte; a mutated or doubled anchor, an already layered text and a layer
                       with its names taken all fail closed with AssertionError; a file the layer does not own passes through as the same
                       object; the audit rows have the v043 shape and the right hashes; no vendored call of the three layout functions is
                       left; the brackets balance as before; every named argument of a wrapper call is a parameter of the wrapper and every
                       parameter without a default is given; the declarations the calls need come in the order the file needs them
  WiringTest           the hook is one line, in the right place, calling the module through the producer's own import path; the module is an
                       input of both Home Gradle tasks (hunk RH3b) and nothing else names it
  KotlinSourceTest     the wrappers and the policy functions are declared once, the imports resolve to real declarations in the right
                       package, the files are plain LF text without Chinese, hooks or key fallback, the vendored layout functions are only
                       reached through the wrappers

What is not proved. That the Kotlin compiles or runs, and that the generated Home screen compiles with the wrapper calls in it: both belong to
the Linux compile later (DesktopHomeFeedLayoutTest is the JVM proof of the numbers). The pure numbers are in test_ux_home_layout_wrappers.py.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in desktop/tools/tests as spec 8.2
says; Root adds each suite to the validation plan as an explicit `-s desktop/tools/ux -p <file>` line.

Slice notes (H1), kept here with the pins they explain.
  * The default home_layout is DESKTOP (spec 5.2, release R2), so the merge of H1 changes the Home grid for everyone who has chosen nothing:
    the feed takes the whole width the rail leaves, the column count follows the density rule and the card metadata is no longer compact
    from a card of 200 dp. CLASSIC (the 首页布局 row of 界面布局, turned on by this slice) runs the vendored functions themselves. This is the
    same visible-at-merge situation as the rail (slice S1); Root is asked to confirm it for R2, or to hold it back behind an availability
    constant until the L4 look check.
  * Fix round H1 (after the first review): the vendored card layout draws 6 dp padding and spacing, so the density rule's 24 dp side padding,
    16 dp gutter and the centring of a grid held at the 320 dp card were numbers of a model, not of the feed. The DESKTOP card layout now
    carries them in outerPaddingDp, itemSpacingDp and verticalItemSpacingDp (the fields the generated feed draws from, pinned below), the
    side space is the centring rest rounded up to a whole dp (the vendored layout takes an Int), and the tests compute the drawn card and
    grid from the layout's own values. Consequence stated here: the model works on the whole-dp content width (the column rule does the
    same), and the real window width has a fraction of a dp, so a drawn card can exceed 320 dp by less than 1 / columns dp.
  * Fix round H1 (after the second review): the first fix applied the 24/16 geometry and the 320 dp cap to every column count, the
    single-column display mode and a pinned count included, which took a Story card from 523 or 631 dp to 320 dp and a pinned two or three
    columns at W = 2731 from 631 or 419 dp to about 320 dp, with the rest of the feed empty. The screen now computes one delegation value
    (desktopHomeFeedColumnsDelegated: the single-column display mode, or a stored column count) before the feed width and hands it to the
    feed width, to both card layouts and, through a new defaulted parameter of HomeCategoryPageContent, to the category page. A delegated
    DESKTOP feed is the vendored feed: the vendored column function, the vendored card layout (the same instance, 6 dp) and the vendored
    1280 dp cap, applied to the width the rail leaves (the Story mode at W = 1280 draws 523 dp cards, at 1920 and 2731 it draws 631 dp, the
    numbers of the review). Interpretation recorded for Root: the cap also goes with a pinned count, because the cap is what bounds a pinned
    two or three columns (on the uncapped 2514 dp feed they would be 830 dp wide); spec 3.6 says "delegate to the original function" and
    "the 1280 dp cap is dropped", and this reads the first as covering the whole feed of those two modes. A pinch that has not ended is not
    a stored count yet: while it runs, the pinched columns use the density geometry, and when it ends the stored count delegates and the feed
    takes the vendored width and cards. The category page gets the same value, so its gutter and metadata follow the screen. The Python
    and JVM rows are in test_ux_home_layout_wrappers.py and DesktopHomeFeedLayoutTest.
  * The spec names three replacements; the card layout call sites (two in the screen, one in the category page) go through
    desktopResolveHomeFeedCardLayout too (spec 3.6, "restores full metadata by card width"), and the delegation value reaches the category
    page through two more edits (the argument of its call in the screen and its new parameter; the value itself is declared inside the edit
    of the feed width), so the layer has eight edits in two files. Those two plumbing edits call no wrapper; the test of the edit list
    (changed in this fix round, with exact values) asks them to name desktopHomeDelegated instead and keeps the wrapper-call requirement
    for the six others.
  * The hook is one physical line (the module is imported inline), where the v043 pattern uses an import line and a call line.
"""
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

REPO = source.REPO
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import ux_home_layout as layer  # noqa: E402

PRODUCER = TOOLS / "extract-upstream-home-page.py"
BUILD = REPO / "desktop/build.gradle.kts"
LAYER_PY = TOOLS / "ux_home_layout.py"
LAYOUT_KT = source.K / "ui/DesktopHomeFeedLayout.kt"
POLICY_KT = source.K / "ui/DesktopHomeFeedLayoutPolicy.kt"
SETTINGS_KT = source.K / "ui/DesktopWindowsUxSettings.kt"
HOME_DIR = "com/android/purebilibili/feature/home/"
SCREEN_PATH = HOME_DIR + layer.SCREEN
CATEGORY_PATH = HOME_DIR + layer.CATEGORY
AUDIT_PATH = "home-page-v029-load-adaptations.json"
PATHS = {layer.SCREEN: SCREEN_PATH, layer.CATEGORY: CATEGORY_PATH}

WRAPPER_FILES = {
    "desktopHomeFeedLayoutKey": LAYOUT_KT,
    "desktopHomeFeedColumnsDelegated": LAYOUT_KT,
    "desktopHomeFeedContentWidth": LAYOUT_KT,
    "desktopResolveHomeFeedGridColumns": LAYOUT_KT,
    "desktopResolveHomeFeedCardLayout": LAYOUT_KT,
}
# Functions of the layout file that the generated text does not call: the pure selection the card layout wrapper makes (it is tested without a composition).
PURE_LAYOUT_NAMES = ("desktopSelectHomeFeedCardLayout",)
POLICY_NAMES = ("desktopHomeFeedDesktopWidthDp", "desktopHomeDensitySideInsetDp", "desktopHomeDensityCardWidthDp", "desktopHomeDensityCardLayout")


def load_producer():
    spec = importlib.util.spec_from_file_location("ux_h1_home_page", PRODUCER)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def tree(root):
    root = Path(root)
    return {path.relative_to(root).as_posix(): path.read_bytes() for path in sorted(root.rglob("*")) if path.is_file()}


def matching_paren(code, open_index):
    depth = 0
    for index in range(open_index, len(code)):
        if code[index] == "(":
            depth += 1
        elif code[index] == ")":
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError("unbalanced call")


def top_level_arguments(code, open_index):
    """The argument texts of the call whose "(" is at open_index, split on the commas at the call's own depth."""
    close = matching_paren(code, open_index)
    arguments, depth, start = [], 0, open_index + 1
    for index in range(open_index + 1, close):
        char = code[index]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            arguments.append(code[start:index].strip())
            start = index + 1
    tail = code[start:close].strip()
    if tail:
        arguments.append(tail)
    return arguments


def declared_parameters(text, name):
    """[(parameter name, has a default)] of the single top-level function called name."""
    code = source.mask_kotlin(text)
    found = [match.end() - 1 for match in re.finditer(r"\bfun %s\(" % re.escape(name), code)]
    if len(found) != 1:
        raise AssertionError("%s is declared %d times" % (name, len(found)))
    parameters = []
    for argument in top_level_arguments(code, found[0]):
        head = argument.split(":", 1)[0].strip()
        parameters.append((head, "=" in argument.split(":", 1)[1].split("->")[0] if ":" in argument else False))
    return parameters


class GeneratorOutputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-uxh1-")
        root = Path(cls.temp.name)
        producer = load_producer()
        producer.generate(REPO, root / "a")
        producer.generate(REPO, root / "b")
        with mock.patch.object(layer, "apply_home_layout", lambda repo, name, text, audits: text):
            producer.generate(REPO, root / "p")
        cls.layered, cls.again, cls.plain = tree(root / "a"), tree(root / "b"), tree(root / "p")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def text(self, files, name):
        return files[PATHS[name]].decode("utf-8")

    def test_the_layer_changes_exactly_the_two_generated_files_and_the_audit_record(self):
        self.assertEqual(set(self.plain), set(self.layered))
        changed = {name for name in self.layered if self.layered[name] != self.plain[name]}
        self.assertEqual({SCREEN_PATH, CATEGORY_PATH, AUDIT_PATH}, changed)

    def test_the_output_is_deterministic(self):
        self.assertEqual(self.layered, self.again)

    def test_every_anchor_occurs_exactly_once_in_the_text_the_layer_receives(self):
        for name, edits in layer.EDITS.items():
            received = self.text(self.plain, name)
            produced = self.text(self.layered, name)
            for label, before, after in edits:
                self.assertEqual(1, received.count(before), label)
                self.assertEqual(0, received.count(after), label)
                self.assertEqual(1, produced.count(after), label)
                self.assertEqual(0, produced.count(before), label)

    def test_the_edits_are_the_eight_of_the_slice_in_two_files(self):
        self.assertEqual({layer.SCREEN, layer.CATEGORY}, set(layer.EDITS))
        self.assertEqual(["ux-home-feed-content-width", "ux-home-grid-columns-key", "ux-home-grid-columns-delegate",
                          "ux-home-card-layout-remembered", "ux-home-card-layout-feed-box", "ux-home-category-delegated-argument"],
                         [e[0] for e in layer.EDITS[layer.SCREEN]])
        self.assertEqual(["ux-home-category-delegated-parameter", "ux-home-card-layout-category"], [e[0] for e in layer.EDITS[layer.CATEGORY]])
        plumbing = {"ux-home-category-delegated-argument", "ux-home-category-delegated-parameter"}
        for edits in layer.EDITS.values():
            for label, before, after in edits:
                self.assertNotEqual(before, after, label)
                if label in plumbing:
                    self.assertIn("desktopHomeDelegated", after, label)
                    self.assertNotIn("desktopHomeDelegated", before, label)
                else:
                    self.assertTrue(after.count("com.bilipai.desktop.ui.desktop") >= 1, label)

    def test_reverse_applying_the_edits_gives_back_the_received_text(self):
        for name in layer.EDITS:
            self.assertEqual(self.text(self.plain, name), layer.reverse(name, self.text(self.layered, name)), name)

    def test_the_text_between_the_edits_is_untouched(self):
        # The layered file differs from the received one by the edits and by nothing else: its length is the received one plus the
        # difference of every before/after pair.
        for name, edits in layer.EDITS.items():
            expected = len(self.text(self.plain, name)) + sum(len(a) - len(b) for _label, b, a in edits)
            self.assertEqual(expected, len(self.text(self.layered, name)), name)

    def test_a_mutated_or_doubled_anchor_fails_closed(self):
        for name, edits in layer.EDITS.items():
            received = self.text(self.plain, name)
            for label, before, _after in edits:
                self.assertIn("(", before, label)
                with self.assertRaises(AssertionError, msg=label + " missing"):
                    layer.apply_home_layout(REPO, name, received.replace(before, before.replace("(", "( ", 1), 1), [])
                with self.assertRaises(AssertionError, msg=label + " doubled"):
                    layer.apply_home_layout(REPO, name, received + "\n" + before, [])

    def test_an_already_layered_text_is_refused(self):
        for name in layer.EDITS:
            with self.assertRaises(AssertionError):
                layer.apply_home_layout(REPO, name, self.text(self.layered, name), [])

    def test_a_taken_name_is_refused_before_any_edit(self):
        for name in layer.EDITS:
            for introduced in layer.INTRODUCED:
                with self.assertRaises(AssertionError, msg=introduced):
                    layer.apply_home_layout(REPO, name, self.text(self.plain, name) + "\nval %s = 1\n" % introduced, [])

    def test_a_file_the_layer_does_not_own_passes_through_as_the_same_object(self):
        audits = []
        for relative, data in self.plain.items():
            if not relative.endswith(".kt") or Path(relative).name in layer.EDITS:
                continue
            body = data.decode("utf-8")
            self.assertIs(body, layer.apply_home_layout(REPO, Path(relative).name, body, audits), relative)
        self.assertEqual([], audits)

    def test_the_audit_rows_have_the_v043_shape_and_the_right_hashes(self):
        plain = json.loads(self.plain[AUDIT_PATH].decode("utf-8"))["audits"]
        layered = json.loads(self.layered[AUDIT_PATH].decode("utf-8"))
        rows = layered["audits"]
        mine = [row for row in rows if str(row.get("id", "")).startswith("ux-home-")]
        self.assertEqual([row for row in rows if row not in mine], plain)
        expected = {label: (before, after) for edits in layer.EDITS.values() for label, before, after in edits}
        self.assertEqual(sorted(expected), sorted(row["id"] for row in mine))
        for row in mine:
            self.assertEqual({"id", "beforeSha256", "afterSha256", "inverseRestoresInput"}, set(row), row["id"])
            before, after = expected[row["id"]]
            self.assertEqual(hashlib.sha256(before.encode("utf-8")).hexdigest(), row["beforeSha256"])
            self.assertEqual(hashlib.sha256(after.encode("utf-8")).hexdigest(), row["afterSha256"])
            self.assertIs(True, row["inverseRestoresInput"])
        for name, edits in layer.EDITS.items():
            order = [row["id"] for row in mine if row["id"] in {label for label, _b, _a in edits}]
            self.assertEqual([label for label, _b, _a in edits], order, name)

    def test_no_vendored_call_of_the_three_layout_functions_is_left(self):
        for name in layer.EDITS:
            produced = self.text(self.layered, name)
            for function in ("resolveHomeFeedGridColumns", "resolveHomeFeedCardLayout"):
                self.assertIsNone(re.search(r"(?<![A-Za-z0-9_])%s\(" % function, produced), (name, function))
            self.assertNotIn("resolveHomeFeedMaxContentWidth", produced, name)
            received = self.text(self.plain, name)
            # The vendored stored-count call stays where it was; the delegation value is the one new reader of it (screen only).
            extra = 1 if name == layer.SCREEN else 0
            self.assertEqual(received.count("resolveHomeFeedStoredColumnCount(") + extra, produced.count("resolveHomeFeedStoredColumnCount("), name)

    def test_the_brackets_balance_as_before(self):
        for name in layer.EDITS:
            for pair in ("()", "{}", "[]"):
                counts = []
                for files in (self.plain, self.layered):
                    code = source.mask_kotlin(self.text(files, name))
                    counts.append(code.count(pair[0]) - code.count(pair[1]))
                self.assertEqual(counts[0], counts[1], (name, pair))

    def test_the_declarations_come_before_their_uses(self):
        produced = self.text(self.layered, layer.SCREEN)
        for needle in ("val desktopHomeLayout = ", "val gridColumns = remember(", "desktopHomeLayout,\n        contentWidth,",
                       "layout = desktopHomeLayout,", "val desktopHomeDelegated = "):
            self.assertEqual(1, produced.count(needle), needle)
        self.assertLess(produced.index("val desktopHomeDelegated = "), produced.index("val contentWidth = if"))
        self.assertLess(produced.index("val desktopHomeDelegated = "), produced.index("desktopHomeFeedContentWidth("))
        self.assertLess(produced.index("val contentWidth = if"), produced.index("val desktopHomeLayout = "))
        self.assertLess(produced.index("val desktopHomeLayout = "), produced.index("val gridColumns = remember("))
        self.assertLess(produced.index("desktopHomeLayout,\n        contentWidth,"), produced.index("layout = desktopHomeLayout,"))
        self.assertLess(produced.index("layout = desktopHomeLayout,"), produced.index("desktopResolveHomeFeedCardLayout("))
        self.assertEqual(3, produced.count("desktopHomeLayout"))
        self.assertEqual(1, produced.count("desktopHomeFeedContentWidth(windowSizeClass.widthDp, delegated = desktopHomeDelegated)"))
        # The width call sits in the expanded branch only, where the vendored cap sat, and the narrow branch is the vendored one.
        branch = produced[produced.index("val contentWidth = if"):produced.index("// 是否为单列模式")]
        self.assertIn("if (windowSizeClass.isExpandedScreen) {\n        com.bilipai.desktop.ui.desktopHomeFeedContentWidth("
                      "windowSizeClass.widthDp, delegated = desktopHomeDelegated)\n    } else {\n        windowSizeClass.widthDp\n    }", branch)

    def test_the_delegation_value_is_the_vendored_stored_count_and_display_mode_handed_to_every_consumer(self):
        screen = self.text(self.layered, layer.SCREEN)
        category = self.text(self.layered, layer.CATEGORY)
        # One declaration, from the display mode and the stored count of the window width class, exactly as the column remember reads them.
        declaration = ("    val desktopHomeDelegated = com.bilipai.desktop.ui.desktopHomeFeedColumnsDelegated(\n"
                       "        displayMode = displayMode,\n"
                       "        fixedColumnCount = resolveHomeFeedStoredColumnCount(\n"
                       "            widthSizeClass = windowSizeClass.widthSizeClass,\n"
                       "            compactColumnCount = homeSettings.gridColumnCountCompact,\n"
                       "            defaultColumnCount = homeSettings.gridColumnCount,\n"
                       "        ),\n"
                       "    )\n")
        self.assertEqual(1, screen.count(declaration))
        column_remember = screen[screen.index("val gridColumns = remember("):screen.index("var interactiveColumns")]
        self.assertIn("            fixedColumnCount = resolveHomeFeedStoredColumnCount(\n"
                      "                widthSizeClass = windowSizeClass.widthSizeClass,\n"
                      "                compactColumnCount = homeSettings.gridColumnCountCompact,\n"
                      "                defaultColumnCount = homeSettings.gridColumnCount,\n"
                      "            ),\n", column_remember)
        # Handed to the feed width, to both card layouts and to the category page (the declaration, three delegated = arguments and the
        # name and value of the category page argument: six uses); nowhere else in the screen.
        self.assertEqual(6, len(re.findall(r"\bdesktopHomeDelegated\b", screen)))
        self.assertEqual(3, screen.count("delegated = desktopHomeDelegated"))
        self.assertEqual(1, screen.count("desktopHomeDelegated = desktopHomeDelegated,"))
        self.assertLess(screen.index("val desktopHomeDelegated = "), screen.index("val homeFeedCardLayout = "))
        # The category page declares it once, defaulted (the vendored callers and tests name every argument), and passes it to the card layout.
        self.assertEqual(1, category.count("    desktopHomeDelegated: Boolean = false,\n"))
        self.assertEqual(1, category.count("        delegated = desktopHomeDelegated,\n"))
        self.assertEqual(2, len(re.findall(r"\bdesktopHomeDelegated\b", category)))
        call = screen[screen.index("HomeCategoryPageContent("):]
        self.assertTrue(call.startswith("HomeCategoryPageContent(\n                                     desktopHomeDelegated = desktopHomeDelegated,\n"
                                        "                                     category = category,\n"))
        self.assertEqual(1, screen.count("HomeCategoryPageContent("))
        # The single-column display mode is the vendored one: the screen's own flag for it reads the same display mode.
        self.assertEqual(1, screen.count("val isSingleColumnMode = displayMode == 1"))

    def test_every_wrapper_call_names_only_declared_parameters_and_gives_every_required_one(self):
        wrappers = {name: declared_parameters(source.read(path), name) for name, path in WRAPPER_FILES.items()}
        calls = 0
        for file_name in layer.EDITS:
            code = source.mask_kotlin(self.text(self.layered, file_name))
            for name, parameters in wrappers.items():
                for match in re.finditer(r"com\.bilipai\.desktop\.ui\.%s\(" % re.escape(name), code):
                    calls += 1
                    arguments = top_level_arguments(code, match.end() - 1)
                    named = [argument.split("=", 1)[0].strip() for argument in arguments if re.match(r"^\w+\s*=[^=]", argument)]
                    positional = len(arguments) - len(named)
                    declared = [parameter for parameter, _default in parameters]
                    self.assertTrue(set(named) <= set(declared), (name, named, declared))
                    self.assertEqual(len(named), len(set(named)), (name, named))
                    given = set(named) | set(declared[:positional])
                    required = {parameter for parameter, default in parameters if not default}
                    self.assertTrue(required <= given, (name, sorted(required - given)))
        # 1 key, 1 delegation, 1 content width, 1 column call and 3 card layout calls.
        self.assertEqual(7, calls)

    def test_the_generated_feed_draws_the_geometry_of_the_card_layout(self):
        # The desktop card layout carries the side padding (the centring of a held grid) and the gutter in outerPaddingDp, itemSpacingDp and
        # verticalItemSpacingDp (DesktopHomeFeedLayoutPolicy.kt). That works only while the generated feed takes its padding and spacing
        # from the layout values and from nothing else, and the cover request computes the card width from the same values.
        screen = self.text(self.layered, layer.SCREEN)
        category = self.text(self.layered, layer.CATEGORY)
        for needle, minimum in (("start = homeFeedCardLayout.outerPaddingDp.dp,", 2), ("end = homeFeedCardLayout.outerPaddingDp.dp,", 2),
                                ("Arrangement.spacedBy(homeFeedCardLayout.itemSpacingDp.dp)", 1),
                                ("verticalItemSpacing = homeFeedCardLayout.verticalItemSpacingDp.dp,", 1),
                                ("homeFeedCardLayout.outerPaddingDp * 2f", 1), ("homeFeedCardLayout.itemSpacingDp * (effectiveGridColumns - 1)", 1)):
            self.assertGreaterEqual(screen.count(needle), minimum, needle)
        for needle in ("Arrangement.spacedBy(cardLayout.itemSpacingDp.dp)", "verticalItemSpacing = cardLayout.verticalItemSpacingDp.dp,"):
            self.assertEqual(1, category.count(needle), needle)
        # No other value is assigned to the three fields in the generated feed, and the category page takes its content padding from the screen.
        for text in (screen, category):
            self.assertIsNone(re.search(r"\b(outerPaddingDp|itemSpacingDp|verticalItemSpacingDp)\s*=\s*\d", text))
        self.assertIn("contentPadding: PaddingValues,", category)

    def test_the_card_layout_call_sites_repeat_the_vendored_named_arguments(self):
        for file_name in layer.EDITS:
            code = source.mask_kotlin(self.text(self.layered, file_name))
            for match in re.finditer(r"desktopResolveHomeFeedCardLayout\(", code):
                names = [argument.split("=", 1)[0].strip() for argument in top_level_arguments(code, match.end() - 1)]
                self.assertEqual(["style", "gridColumns", "widthSizeClass", "delegated"], names)
        code = source.mask_kotlin(self.text(self.layered, layer.SCREEN))
        match = re.search(r"desktopResolveHomeFeedGridColumns\(", code)
        names = [argument.split("=", 1)[0].strip() for argument in top_level_arguments(code, match.end() - 1)]
        self.assertEqual(["layout", "contentWidthDp", "displayMode", "fixedColumnCount", "cardWidthPreset", "widthSizeClass"], names)


class WiringTest(unittest.TestCase):
    HOOK = '            body=__import__("ux_home_layout").apply_home_layout(repo,target.name,body,home_load_audits)'

    def test_the_hook_is_one_line_after_the_last_layer_and_before_the_vip_badge_branch(self):
        lines = source.read(PRODUCER).split("\n")
        hooks = [index for index, line in enumerate(lines) if "ux_home_layout" in line]
        self.assertEqual(1, len(hooks))
        index = hooks[0]
        self.assertEqual(self.HOOK, lines[index])
        self.assertEqual("            body=apply_recommendation_badge(repo,target.name,body,home_load_audits)", lines[index - 1])
        self.assertEqual("            if target.name=='UserVipBadge.kt':", lines[index + 1])
        self.assertIn("        for source in work.rglob(\"*.kt\"):", lines[:index])

    def test_the_hook_calls_the_module_attribute_at_call_time(self):
        # The tests patch ux_home_layout.apply_home_layout; the hook must look the attribute up each time (no from-import binding at load).
        text = source.read(PRODUCER)
        self.assertEqual(0, len(re.findall(r"(?m)^(from|import) ux_home_layout", text)))
        self.assertEqual(1, text.count('__import__("ux_home_layout").apply_home_layout('))

    def task_block(self, name):
        build = source.read(BUILD)
        start = build.index("val %s by tasks.registering(" % name)
        end = build.index("\n}\n", start)
        return build[start:end]

    def test_the_module_is_an_input_of_both_home_tasks(self):
        line = '    inputs.files("tools/ux_home_layout.py")\n'
        original = self.task_block("extractOriginalHomePage") + "\n"
        full = self.task_block("extractUpstreamHomeFullCard") + "\n"
        self.assertEqual(1, original.count(line))
        self.assertEqual(1, full.count(line))
        self.assertEqual(2, source.read(BUILD).count("tools/ux_home_layout.py"))
        self.assertIn('    inputs.files("tools/extract-upstream-home-page.py", "tools/sync-upstream.py")\n' + line, original)
        self.assertIn('"tools/extract-appearance-platform.py", "tools/extract-upstream-settings-home.py", "tools/sync-upstream.py")\n' + line, full)

    def test_the_producer_the_original_task_runs_is_the_hooked_one(self):
        block = self.task_block("extractOriginalHomePage")
        self.assertIn('"tools/extract-upstream-home-core-refresh.py"', block)
        self.assertIn("tools/extract-upstream-home-page.py", block)
        core = source.read(TOOLS / "extract-upstream-home-core-refresh.py")
        self.assertIn("desktop/tools/extract-upstream-home-page.py", core)

    def test_only_the_hook_and_the_tests_name_the_module(self):
        names = sorted(path.name for path in TOOLS.glob("*.py") if path.name != "ux_home_layout.py" and "ux_home_layout" in source.read(path))
        self.assertEqual(["extract-upstream-home-page.py"], names)

    def test_no_other_generator_names_the_generated_files_with_these_anchors(self):
        # The layer is the only writer of the wrapper names into generated text.
        for path in TOOLS.glob("*.py"):
            if path.name == "ux_home_layout.py":
                continue
            self.assertNotIn("desktopResolveHomeFeedGridColumns", source.read(path), path.name)
            self.assertNotIn("desktopHomeFeedContentWidth", source.read(path), path.name)


def declaring_packages(name):
    """Packages of the Kotlin files in the repository that declare a top-level name (git grep, read only)."""
    pattern = r"(^|[ \t])(fun|class|object|interface|typealias|val|var)[ \t]+(<[^>]*>[ \t]+)?([A-Za-z0-9_<>?, ]+\.)?%s\b" % re.escape(name)
    result = subprocess.run(["git", "-c", "core.autocrlf=false", "grep", "--untracked", "-l", "-E", pattern, "--", "app", "design-system", "desktop/src/main"],
                            cwd=str(REPO), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    packages = set()
    for line in result.stdout.decode("utf-8", "replace").splitlines():
        if not line.endswith(".kt"):
            continue
        match = re.search(r"(?m)^package ([\w.]+)", source.read(REPO / line))
        if match:
            packages.add(match.group(1))
    return packages


def has(text, needle):
    """Whitespace-insensitive containment (the shell model's squash also drops the space inside brackets)."""
    return shell.squash(needle) in shell.squash(text)


class KotlinSourceTest(unittest.TestCase):
    LAYOUT = source.read(LAYOUT_KT)
    POLICY = source.read(POLICY_KT)

    def test_the_wrappers_are_declared_once_in_the_layout_file_and_nowhere_else(self):
        for name, path in list(WRAPPER_FILES.items()) + [(name, LAYOUT_KT) for name in PURE_LAYOUT_NAMES]:
            code = source.mask_kotlin(source.read(path))
            self.assertEqual(1, len(re.findall(r"\bfun %s\(" % name, code)), name)
        for path in sorted(source.K.rglob("*.kt")) + sorted(source.KT.rglob("*.kt")):
            if path in (LAYOUT_KT, POLICY_KT):
                continue
            code = source.mask_kotlin(source.read(path))
            for name in list(WRAPPER_FILES) + list(PURE_LAYOUT_NAMES) + list(POLICY_NAMES) + ["desktopHomeFeedMeasuredWidth"]:
                self.assertIsNone(re.search(r"\bfun %s\(" % name, code), (source.relative_posix(path), name))

    def test_the_policy_functions_are_declared_once_in_the_policy_file(self):
        code = source.mask_kotlin(self.POLICY)
        for name in POLICY_NAMES:
            self.assertEqual(1, len(re.findall(r"\binternal fun %s\(" % name, code)), name)
        self.assertEqual(4, len(re.findall(r"(?m)^internal fun ", code)))
        layout = source.mask_kotlin(self.LAYOUT)
        self.assertEqual(1, len(re.findall(r"\binternal fun desktopHomeFeedMeasuredWidth\(", layout)))

    def test_the_composable_wrappers_are_composable_and_the_numeric_ones_are_not(self):
        squashed = shell.squash(source.mask_kotlin(self.LAYOUT))
        for name in ("desktopHomeFeedLayoutKey", "desktopHomeFeedContentWidth", "desktopResolveHomeFeedCardLayout"):
            self.assertEqual(1, squashed.count("@Composable internal fun %s(" % name), name)
        for name in ("desktopHomeFeedMeasuredWidth", "desktopHomeFeedColumnsDelegated", "desktopResolveHomeFeedGridColumns",
                     "desktopSelectHomeFeedCardLayout"):
            self.assertEqual(1, squashed.count("internal fun %s(" % name), name)
            self.assertEqual(0, squashed.count("@Composable internal fun %s(" % name), name)
        self.assertNotIn("@Composable", source.mask_kotlin(self.POLICY))

    def test_the_policy_file_has_no_compose_awt_or_windows_dependency(self):
        imports = sorted(re.findall(r"(?m)^import (\S+)$", self.POLICY))
        self.assertEqual(["com.android.purebilibili.feature.home.HomeFeedCardLayout", "kotlin.math.max"], imports)
        code = source.mask_kotlin(self.POLICY)
        for forbidden in ("androidx.", "java.awt", "javax.", "System.", "Composable", "remember"):
            self.assertNotIn(forbidden, code, forbidden)

    def test_the_imports_are_exactly_what_the_layout_file_needs(self):
        imports = sorted(re.findall(r"(?m)^import (\S+)$", self.LAYOUT))
        self.assertEqual(sorted([
            "androidx.compose.runtime.Composable", "androidx.compose.runtime.collectAsState", "androidx.compose.runtime.getValue",
            "androidx.compose.runtime.remember", "androidx.compose.ui.unit.Dp", "androidx.compose.ui.unit.dp",
            "com.android.purebilibili.core.store.HomeFeedCardStyle", "com.android.purebilibili.core.store.HomeFeedCardWidthPreset",
            "com.android.purebilibili.core.util.LocalWindowSizeClass", "com.android.purebilibili.core.util.WindowWidthSizeClass",
            "com.android.purebilibili.feature.home.HomeFeedCardLayout", "com.android.purebilibili.feature.home.resolveHomeFeedCardLayout",
            "com.android.purebilibili.feature.home.resolveHomeFeedGridColumns", "com.android.purebilibili.feature.home.resolveHomeFeedMaxContentWidth",
            "com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout", "com.bilipai.desktop.appearance.LocalDesktopWindowsUx",
        ]), imports)
        code = source.mask_kotlin(self.LAYOUT)
        body = code[code.index("\n", code.rindex("import ")):]
        for imported in imports:
            simple = imported.rsplit(".", 1)[1]
            if simple == "getValue":
                continue  # the operator of the "by" delegate; it is never spelled out
            self.assertGreaterEqual(len(re.findall(r"\b%s\b" % simple, body)), 1, imported)
        self.assertIn(" by LocalDesktopWindowsUx.current", body)

    def test_every_imported_name_is_declared_in_its_package(self):
        for text in (self.LAYOUT, self.POLICY):
            for imported in re.findall(r"(?m)^import (\S+)$", text):
                package, simple = imported.rsplit(".", 1)
                if package.startswith(("androidx.compose", "kotlin.")):
                    continue
                self.assertIn(package, declaring_packages(simple), imported)

    def test_same_package_names_used_by_the_layout_file_are_declared_in_the_package(self):
        for name in ("LocalDesktopShellInsets", "desktopHomeDensityDelegation", "resolveDesktopHomeDensity", "desktopHomeFeedDesktopWidthDp",
                     "desktopHomeDensityCardLayout", "desktopHomeDensityCompactMetadata", "desktopHomeDensityCardWidthDp"):
            self.assertIn("com.bilipai.desktop.ui", declaring_packages(name), name)
        for name in ("DESKTOP_HOME_SIDE_PADDING_DP", "DESKTOP_HOME_GUTTER_DP", "DESKTOP_HOME_MAX_CARD_DP"):
            self.assertIn("com.bilipai.desktop.ui", declaring_packages(name), name)

    def test_the_vendored_layout_functions_are_reached_only_through_the_wrappers(self):
        # Inside the desktop sources only the wrapper file, the old grid host (a separate surface that already measures its own box) and
        # the card preference tests name them.
        users = []
        for path in sorted(source.K.rglob("*.kt")):
            code = source.mask_kotlin(source.read(path))
            if re.search(r"\b(resolveHomeFeedGridColumns|resolveHomeFeedCardLayout|resolveHomeFeedMaxContentWidth)\(", code):
                users.append(source.rel(path))
        self.assertEqual(["ui/DesktopHomeCardGrid.kt", "ui/DesktopHomeFeedLayout.kt"], users)

    def test_classic_runs_the_vendored_functions_themselves(self):
        code = source.mask_kotlin(self.LAYOUT)
        self.assertTrue(has(code, "DesktopWindowsUxHomeLayout.CLASSIC -> minOf(windowWidth, resolveHomeFeedMaxContentWidth())"))
        self.assertTrue(has(code, "DesktopWindowsUxHomeLayout.CLASSIC -> original"))
        self.assertTrue(has(code, "if (desktopHomeDensityDelegation(layout, displayMode, fixedColumnCount) != null) { resolveHomeFeedGridColumns("))
        self.assertTrue(has(code, "contentWidthDp = contentWidthDp, displayMode = displayMode, fixedColumnCount = fixedColumnCount, "
                                  "cardWidthPreset = cardWidthPreset, widthSizeClass = widthSizeClass, )"))
        self.assertTrue(has(code, "resolveDesktopHomeDensity(contentWidthDp, cardWidthPreset.minCardWidthDp).columns"))

    def test_the_defaults_repeat_the_vendored_ones(self):
        code = source.mask_kotlin(self.LAYOUT)
        # resolveHomeFeedGridColumns(... widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Medium)
        self.assertTrue(has(code, "cardWidthPreset: HomeFeedCardWidthPreset, widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Medium, ): Int"))
        # resolveHomeFeedCardLayout(style, gridColumns: Int = 2, widthSizeClass = WindowWidthSizeClass.Compact)
        # (the wrapper adds the delegation after the vendored parameters, defaulted)
        self.assertTrue(has(code, "style: HomeFeedCardStyle, gridColumns: Int = 2, widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact, "
                                  "delegated: Boolean = false, ): HomeFeedCardLayout"))
        self.assertTrue(has(source.read(shell.GRID_POLICY), "widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Medium ): Int"))
        style = source.read(source.REPO / "app/src/main/java/com/android/purebilibili/feature/home/HomeFeedCardStylePolicy.kt")
        self.assertTrue(has(style, "gridColumns: Int = 2, widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact, ): HomeFeedCardLayout"))

    def test_the_flag_has_exactly_one_reader_and_the_settings_row_is_on(self):
        readers = []
        for path in sorted(source.K.rglob("*.kt")):
            if path == SETTINGS_KT or source.rel(path).startswith("appearance/DesktopWindowsUx"):
                continue
            if re.search(r"\.homeLayout\b", source.mask_kotlin(source.read(path))):
                readers.append(source.rel(path))
        self.assertEqual(["ui/DesktopHomeFeedLayout.kt"], readers)
        self.assertIn("private const val HOME_LAYOUT_HAS_CONSUMER = true\n", source.read(SETTINGS_KT))
        self.assertTrue(has(source.mask_kotlin(self.LAYOUT), "val settings by LocalDesktopWindowsUx.current.settings.collectAsState() return settings.homeLayout"))

    def test_the_insets_and_the_window_width_are_read_in_composable_scope(self):
        code = source.mask_kotlin(self.LAYOUT)
        self.assertTrue(has(code, "desktopHomeFeedMeasuredWidth(windowWidth, LocalDesktopShellInsets.current.leadingDp, desktopHomeFeedLayoutKey(), delegated)"))
        self.assertTrue(has(code, "LocalWindowSizeClass.current.widthDp, LocalDesktopShellInsets.current.leadingDp, layout, delegated, ).value.toInt()"))
        # The rail footprint is the shell's own local, not a second source: nothing else in the file names the rail.
        for forbidden in ("DesktopRailPresentation", "railMode", "desktopRailPresentation"):
            self.assertNotIn(forbidden, self.LAYOUT)

    def test_the_files_are_plain_lf_text_without_chinese_hooks_or_key_fallback(self):
        source.assert_lf_only(self, LAYOUT_KT, POLICY_KT, LAYER_PY, PRODUCER, BUILD, SETTINGS_KT)
        for path in (LAYOUT_KT, POLICY_KT):
            text = source.read(path)
            self.assertEqual([], source.cjk_positions(text), path.name)
            self.assertEqual([], source.hook_uses(text), path.name)
            self.assertEqual([], source.key_fallback_uses(text), path.name)
            self.assertEqual([], source.banned_imports(text), path.name)
            self.assertTrue(text.endswith("\n") and not text.endswith("\n\n"), path.name)
            self.assertTrue(text.startswith("package com.bilipai.desktop.ui\n"), path.name)
        self.assertEqual([], [position for position in source.cjk_positions(source.read(LAYER_PY))])

    def test_the_program_scope_arms_the_two_files(self):
        self.assertIn("ui/DesktopHomeFeedLayout.kt", source.PROGRAM_EXTRA_GLOBS)
        self.assertIn("ui/DesktopHomeFeedLayoutPolicy.kt", source.PROGRAM_EXTRA_GLOBS)

    def test_delimiters_of_the_new_kotlin_files_balance(self):
        for path in (LAYOUT_KT, POLICY_KT):
            code = source.mask_kotlin(source.read(path))
            for pair in ("()", "{}", "[]"):
                self.assertEqual(code.count(pair[0]), code.count(pair[1]), (path.name, pair))

    def test_the_top_level_names_of_the_new_files_are_new_in_the_source_tree(self):
        names = []
        for path in (LAYOUT_KT, POLICY_KT):
            names += re.findall(r"(?m)^(?:@Composable\n)?internal (?:fun|val|const val|class|object) (\w+)", source.read(path))
        self.assertEqual(11, len(names), names)
        self.assertEqual(len(names), len(set(names)))
        for path in sorted(source.K.rglob("*.kt")) + sorted(source.KT.rglob("*.kt")):
            if path in (LAYOUT_KT, POLICY_KT):
                continue
            code = source.mask_kotlin(source.read(path))
            for name in names:
                self.assertIsNone(re.search(r"(?m)^\s*(internal |public |private )?(fun|val|const val|class|object) %s\b" % name, code), (source.relative_posix(path), name))


class LayerModuleTest(unittest.TestCase):
    def test_the_module_is_plain_ascii_lf_python_that_raises_assertion_errors(self):
        text = source.read(LAYER_PY)
        text.encode("ascii")
        self.assertNotIn("\r", LAYER_PY.read_bytes().decode("utf-8"))
        self.assertNotIn("\nassert ", text)
        self.assertEqual(1, text.count("def apply_home_layout("))
        self.assertEqual(1, text.count("def reverse("))

    def test_every_anchor_is_a_whole_line_start_or_a_whole_call(self):
        for name, edits in layer.EDITS.items():
            for label, before, after in edits:
                self.assertTrue(before.endswith(("\n", ")")), label)
                self.assertTrue(after.endswith(("\n", ")")), label)

    def test_the_wrapper_names_the_layer_introduces_are_the_declared_ones(self):
        introduced = set(layer.INTRODUCED) - {"desktopHomeLayout", "desktopHomeDelegated"}
        self.assertEqual(set(WRAPPER_FILES), introduced)
        used = set()
        for edits in layer.EDITS.values():
            for _label, _before, after in edits:
                used.update(re.findall(r"com\.bilipai\.desktop\.ui\.(\w+)\(", after))
        self.assertEqual(set(WRAPPER_FILES), used)

    def test_reverse_refuses_a_text_that_is_not_layered(self):
        with self.assertRaises(AssertionError):
            layer.reverse(layer.CATEGORY, "nothing here")


if __name__ == "__main__":
    unittest.main()
