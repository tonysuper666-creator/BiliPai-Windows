"""Slice H2 generator and source checks: the Home card interaction layer on the generated VideoCard text, its hook, its Gradle input and
the Kotlin it calls. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_card_adaptations.py

What is proved. desktop/tools/ux_home_card.py is run through the real Home card producer (extract-upstream-home-full-card.py), three times
into temporary directories (with the layer, again with the layer, and with the layer patched out):

  GeneratorOutputTest  every anchor occurs exactly once in the text the layer receives; the layer changes exactly the generated VideoCard and
                       the audit rows of its inventory entry, nothing else; the output is deterministic; reverse-applying the edits gives
                       back the received text byte for byte; a mutated or doubled anchor, an already layered text and a layer with its names
                       taken all fail closed with AssertionError; a file the layer does not own passes through as the same object; the audit
                       rows have the v043 shape and the right hashes; the vendored menu call shapes (names, count, trailing lambda) are
                       unchanged; the right click and hand cursor sit after the hover effect and before the enter animation, in the modifier
                       chain of the card box, after the declarations they use; only the overflow box takes the 32 dp target (the unfavorite box
                       keeps the vendored size); the three labels keep their vendored text inside the wrapper call; no caller passes its own
                       dismissMenuText; the brackets balance as before
  WiringTest           the hook is one line, in the right place, calling the module through the producer's own import path; the module is an
                       input of the full-card Gradle task (hunk RH3b) and of nothing else; the Home page producer does not emit the card in
                       production
  KotlinSourceTest     the menu, the row, the label function, the target function and the two modifiers are declared once, with the call shape
                       and the parameter names the generated card uses and a subset of the vendored menu parameters, the imports resolve to
                       real declarations, the files are plain LF text without Chinese, hooks or key fallback, OFF reaches the vendored code, and
                       the settings row of the flag is on

What is not proved. That the Kotlin compiles or runs, and that the generated card compiles with the new calls in it: both belong to the Linux
compile later (DesktopCardInteractionPolicyTest is the JVM proof of the pure logic). That a right click really reaches the Initial-pass handler
and is not read as a tap is a behaviour of the real Compose scene; no ImageComposeScene test is part of this slice (the spec lists none for the
card), so that claim rests on the source rules here and on a look at the real window.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in desktop/tools/tests as spec 8.2 says;
Root adds each suite to the validation plan as an explicit `-s desktop/tools/ux -p <file>` line.

Slice notes (H2), kept here with the pins they explain.
  * The default of card_interactions is ON (the contract's first value), so the merge of H2 changes the Home cards for everyone who has chosen
    nothing: a right click opens the card menu, the whole card shows the hand cursor, the menu labels lose their emoji, and the overflow button is
    32 dp instead of 48 dp. OFF (the 卡片右键菜单与悬停 row of 界面布局, turned on by this slice) runs the vendored menu, labels and button
    size. Root is asked to confirm the default for R2, or to hold it back behind the settings default until the L4 look check.
  * The spec names the swap of the two imports, two modifiers, the 32 dp box and three labels; the layer has seven edits: the two imports of the
    modifiers are separate edits from the import swap of the menu, and the three labels are three edits, all anchored on single lines except
    the overflow box, which needs five lines (the token MinimumTouchTarget is used twice in the card).
  * The labels are gated by the flag as the spec asks (OFF delegates to the original), so the original text stays in the call as the argument
    original and the plain text is the argument plain of desktopCardMenuLabel; the emoji therefore still occur in the generated text, once
    each, as the OFF text.
  * The right click opens the menu on the press (spec 3.6, pressOffset), not on the release. The rail menu of slice S1 opens on the press as
    well. The press position is in the coordinates of the card box, where the hover lift (a 2.5 percent scale) may shift the menu by a few
    pixels near the card edges.
  * The affected earlier pin: test_ux_api_probe_source.py asserted the two vendored import lines in the generated card; it now asserts the two
    alias lines (exact values). The XP trio (UxApiProbeTest.kt, ux_api_probe.py, test_ux_api_probe_source.py) is still on disk: the report of
    batch B asks Root to delete it before H2, and only Root may.
  * Single-column mode, StoryVideoCard and DynamicCard are not covered (spec 3.6); the exit criterion of the spec ("no action reachable only by
    long press") is met for the pointer half on the grid card only.
"""
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_home_card_model as cm  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

REPO = source.REPO
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import ux_home_card as layer  # noqa: E402

PRODUCER = TOOLS / "extract-upstream-home-full-card.py"
HOME_PRODUCER = TOOLS / "extract-upstream-home-page.py"
BUILD = REPO / "desktop/build.gradle.kts"
LAYER_PY = TOOLS / "ux_home_card.py"
SETTINGS_KT = source.K / "ui/DesktopWindowsUxSettings.kt"
CARDS_DIR = "generated/com/android/purebilibili/feature/home/components/cards/"
CARD_PATH = CARDS_DIR + layer.CARD
INVENTORY_PATH = "source-inventory.json"
CARD_SOURCE = "app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCard.kt"

MENU_FUNCTIONS = {"DesktopCardContextMenu": cm.MENU_KT, "DesktopCardContextMenuItem": cm.MENU_KT, "desktopCardInteractionsOn": cm.MENU_KT,
                  "desktopCardMenuLabel": cm.MENU_KT, "desktopCardOverflowTarget": cm.MENU_KT,
                  "desktopCardSecondaryClick": cm.POINTER_KT, "desktopCardHandCursor": cm.POINTER_KT}
POLICY_FUNCTIONS = ("desktopCardMenuLabelFor", "desktopCardOverflowTargetDpFor", "desktopCardSecondaryClickActive",
                    "desktopCardOwnsSecondaryPress", "desktopCardSecondaryFollow")
VENDORED_MENUS = (REPO / "design-system/src/main/java/com/android/purebilibili/core/ui/components/AppPrimitiveComponents.kt",
                  REPO / "desktop/upstream-slices/v043-theme-chrome/AppPrimitiveComponents.kt")


def load_producer(path):
    spec = importlib.util.spec_from_file_location("ux_h2_" + path.stem.replace("-", "_"), path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def generate_quietly(producer, output):
    with redirect_stdout(StringIO()):
        producer.generate(REPO, output)


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
    """[(parameter name, has a default)] of the single top-level function called name; the receiver of an extension is not a parameter."""
    code = source.mask_kotlin(text)
    found = [match.end() - 1 for match in re.finditer(r"\bfun (?:[\w.]+\.)?%s\(" % re.escape(name), code)]
    if len(found) != 1:
        raise AssertionError("%s is declared %d times" % (name, len(found)))
    parameters = []
    for argument in top_level_arguments(code, found[0]):
        head = argument.split(":", 1)[0].strip()
        parameters.append((head, "=" in argument.split(":", 1)[1].split("->")[0] if ":" in argument else False))
    return parameters


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


class GeneratorOutputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-uxh2-")
        root = Path(cls.temp.name)
        producer = load_producer(PRODUCER)
        generate_quietly(producer, root / "a")
        generate_quietly(producer, root / "b")
        with mock.patch.object(layer, "apply_home_card", lambda repo, name, text, audits: text):
            generate_quietly(producer, root / "p")
        cls.layered, cls.again, cls.plain = tree(root / "a"), tree(root / "b"), tree(root / "p")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def card(self, files):
        return files[CARD_PATH].decode("utf-8")

    def rows(self, files):
        return json.loads(files[INVENTORY_PATH].decode("utf-8"))

    def card_row(self, files):
        found = [row for row in self.rows(files) if row["path"] == CARD_SOURCE]
        self.assertEqual(1, len(found))
        return found[0]

    def test_the_layer_changes_exactly_the_generated_card_and_the_inventory(self):
        self.assertEqual(set(self.plain), set(self.layered))
        changed = {name for name in self.layered if self.layered[name] != self.plain[name]}
        self.assertEqual({CARD_PATH, INVENTORY_PATH}, changed)
        # In the inventory only the audit rows of the card entry change, and only by the seven rows the layer appends.
        plain_rows, layered_rows = self.rows(self.plain), self.rows(self.layered)
        self.assertEqual(len(plain_rows), len(layered_rows))
        for before, after in zip(plain_rows, layered_rows):
            if before["path"] != CARD_SOURCE:
                self.assertEqual(before, after, before["path"])
        before, after = self.card_row(self.plain), self.card_row(self.layered)
        self.assertEqual({key: value for key, value in before.items() if key != "changes"}, {key: value for key, value in after.items() if key != "changes"})
        self.assertEqual(before["changes"], after["changes"][:len(before["changes"])])
        self.assertEqual([label for label, _b, _a in layer.EDITS], [row["id"] for row in after["changes"][len(before["changes"]):]])

    def test_the_output_is_deterministic(self):
        self.assertEqual(self.layered, self.again)

    def test_every_anchor_occurs_exactly_once_in_the_text_the_layer_receives(self):
        received, produced = self.card(self.plain), self.card(self.layered)
        for label, before, after in layer.EDITS:
            self.assertEqual(1, received.count(before), label)
            self.assertEqual(0, received.count(after), label)
            self.assertEqual(1, produced.count(after), label)
            if label not in ("ux-card-modifier-imports", "ux-card-secondary-click-and-hand-cursor"):
                # These two edits keep their anchor line and add lines after it; every other anchor is gone.
                self.assertEqual(0, produced.count(before), label)
            else:
                self.assertEqual(1, produced.count(before), label)

    def test_the_edits_are_the_seven_of_the_slice_in_one_file(self):
        self.assertEqual(["ux-card-menu-imports", "ux-card-modifier-imports", "ux-card-secondary-click-and-hand-cursor", "ux-card-overflow-target",
                          "ux-card-dismiss-label-default", "ux-card-watch-later-label", "ux-card-unfavorite-label"],
                         [label for label, _b, _a in layer.EDITS])
        self.assertEqual("VideoCard.kt", layer.CARD)
        for label, before, after in layer.EDITS:
            self.assertNotEqual(before, after, label)
            self.assertTrue(before.endswith("\n") and after.endswith("\n"), label)

    def test_reverse_applying_the_edits_gives_back_the_received_text(self):
        self.assertEqual(self.card(self.plain), layer.reverse(layer.CARD, self.card(self.layered)))

    def test_the_text_between_the_edits_is_untouched(self):
        expected = len(self.card(self.plain)) + sum(len(after) - len(before) for _label, before, after in layer.EDITS)
        self.assertEqual(expected, len(self.card(self.layered)))

    def test_a_mutated_or_doubled_anchor_fails_closed(self):
        received = self.card(self.plain)
        for label, before, _after in layer.EDITS:
            # Insert a character after the first one that is not a space: the anchor then no longer occurs in the received text.
            start = len(before) - len(before.lstrip(" ")) + 1
            mutated = before[:start] + "X" + before[start:]
            self.assertNotEqual(before, mutated, label)
            with self.assertRaises(AssertionError, msg=label + " missing"):
                layer.apply_home_card(REPO, layer.CARD, received.replace(before, mutated, 1), [])
            with self.assertRaises(AssertionError, msg=label + " doubled"):
                layer.apply_home_card(REPO, layer.CARD, received + "\n" + before, [])

    def test_an_already_layered_text_is_refused(self):
        with self.assertRaises(AssertionError):
            layer.apply_home_card(REPO, layer.CARD, self.card(self.layered), [])

    def test_a_taken_name_is_refused_before_any_edit(self):
        for introduced in layer.INTRODUCED:
            with self.assertRaises(AssertionError, msg=introduced):
                layer.apply_home_card(REPO, layer.CARD, self.card(self.plain) + "\nval %s = 1\n" % introduced, [])

    def test_a_file_the_layer_does_not_own_passes_through_as_the_same_object(self):
        audits = []
        count = 0
        for relative, data in self.plain.items():
            if not relative.endswith(".kt") or Path(relative).name == layer.CARD:
                continue
            body = data.decode("utf-8")
            self.assertIs(body, layer.apply_home_card(REPO, Path(relative).name, body, audits), relative)
            count += 1
        self.assertGreater(count, 10)
        self.assertEqual([], audits)
        self.assertIn(CARDS_DIR + "HomeStyleSingleColumnVideoCard.kt", self.plain)

    def test_the_audit_rows_have_the_v043_shape_and_the_right_hashes(self):
        plain_changes, rows = self.card_row(self.plain)["changes"], self.card_row(self.layered)["changes"]
        mine = rows[len(plain_changes):]
        self.assertEqual(len(layer.EDITS), len(mine))
        for (label, before, after), row in zip(layer.EDITS, mine):
            self.assertEqual({"id", "beforeSha256", "afterSha256", "inverseRestoresInput"}, set(row), label)
            self.assertEqual(label, row["id"])
            self.assertEqual(hashlib.sha256(before.encode("utf-8")).hexdigest(), row["beforeSha256"], label)
            self.assertEqual(hashlib.sha256(after.encode("utf-8")).hexdigest(), row["afterSha256"], label)
            self.assertIs(True, row["inverseRestoresInput"], label)

    def test_the_vendored_menu_imports_are_gone_and_the_aliases_are_the_only_import_of_the_family(self):
        produced = self.card(self.layered)
        self.assertEqual(0, produced.count("import com.android.purebilibili.core.ui.components.AppDropdownMenu"))
        self.assertEqual(1, produced.count("import com.bilipai.desktop.ui.DesktopCardContextMenu as AppDropdownMenu\n"))
        self.assertEqual(1, produced.count("import com.bilipai.desktop.ui.DesktopCardContextMenuItem as AppDropdownMenuItem\n"))
        self.assertEqual(1, produced.count("import com.android.purebilibili.core.ui.adaptive.adaptiveCardHoverEffect\n"))
        self.assertEqual(1, produced.count("import com.bilipai.desktop.ui.desktopCardHandCursor\n"))
        self.assertEqual(1, produced.count("import com.bilipai.desktop.ui.desktopCardSecondaryClick\n"))
        # Kotlin refuses two imports of one simple name, and an alias is a new simple name: nothing else binds these names.
        for name in ("AppDropdownMenu", "AppDropdownMenuItem", "desktopCardHandCursor", "desktopCardSecondaryClick"):
            self.assertEqual(1, len(re.findall(r"(?m)^import .*\b%s$" % name, produced)), name)

    def test_the_menu_call_shapes_are_the_vendored_ones(self):
        received, produced = source.mask_kotlin(self.card(self.plain)), source.mask_kotlin(self.card(self.layered))
        for name, expected in (("AppDropdownMenu", [["expanded", "onDismissRequest", "offset"]]),
                               ("AppDropdownMenuItem", [["text", "onClick"]] * 3)):
            for code in (received, produced):
                calls = []
                for match in re.finditer(r"(?<![\w.])%s\(" % name, code):
                    arguments = top_level_arguments(code, match.end() - 1)
                    calls.append([argument.split("=", 1)[0].strip() for argument in arguments if re.match(r"^\w+\s*=[^=]", argument)])
                self.assertEqual(expected, calls, name)
        self.assertIn("AppDropdownMenu(\n                expanded = showDismissMenu,\n                onDismissRequest = { showDismissMenu = false },\n"
                      "                offset = DpOffset.Zero\n            ) {", self.card(self.layered))

    def test_the_right_click_and_the_hand_cursor_sit_in_the_modifier_chain_of_the_card_box(self):
        produced = self.card(self.layered)
        chain_start = produced.index("    Box(\n        modifier = modifier\n            .fillMaxWidth()\n            .adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n")
        chain = produced[chain_start:produced.index("\n    ) {\n", chain_start)]
        order = [".fillMaxWidth()", ".adaptiveCardHoverEffect(shape = adaptiveHoverShape)", ".desktopCardSecondaryClick(enabled = hasOverflowMenu) { pressOffset ->",
                 "openDismissMenu(cardCoordsRef.value, pressOffset)", ".desktopCardHandCursor()", ".animateEnter(", ".onGloballyPositioned { coordinates ->"]
        positions = [chain.index(item) for item in order]
        self.assertEqual(sorted(positions), positions)
        self.assertEqual(1, produced.count(".desktopCardSecondaryClick("))
        self.assertEqual(1, produced.count(".desktopCardHandCursor()"))
        # The handler is called with the press position (px, in the card box) and the card's own coordinates, as the long press does with its anchor.
        self.assertTrue(has(chain, ".desktopCardSecondaryClick(enabled = hasOverflowMenu) { pressOffset -> openDismissMenu(cardCoordsRef.value, pressOffset) }"))
        # Everything the chain reads is declared before the chain, with the types the call needs.
        for declaration in ("val cardCoordsRef = remember { object { var value: LayoutCoordinates? = null } }",
                            "val openDismissMenu: (LayoutCoordinates?, Offset?) -> Unit = { anchorCoords, pressOffset ->",
                            "val hasOverflowMenu = onDismiss != null || onWatchLater != null"):
            self.assertEqual(1, produced.count(declaration), declaration)
            self.assertLess(produced.index(declaration), chain_start, declaration)
        # The long press sites keep calling the menu with their own anchors.
        self.assertEqual(self.card(self.plain).count("openDismissMenu(") + 1, produced.count("openDismissMenu("))
        self.assertEqual(1, produced.count("openDismissMenu(coverCoordsRef.value, pressOffset)"))
        self.assertEqual(1, produced.count("openDismissMenu(titleCoordsRef.value, pressOffset)"))
        self.assertEqual(1, produced.count("openDismissMenu(menuButtonCoordsRef.value, null)"))

    def test_only_the_overflow_box_takes_the_32_dp_target(self):
        received, produced = self.card(self.plain), self.card(self.layered)
        token = "AppChromeSizeTokens.MinimumTouchTarget"
        self.assertEqual(2, received.count(token))
        self.assertEqual(2, produced.count(token))
        first = produced.index(token)
        second = produced.index(token, first + 1)
        # The first use is the unfavorite box and it is untouched; the second sits in the wrapper call of the overflow box.
        self.assertEqual("                            .size(AppChromeSizeTokens.MinimumTouchTarget)\n", produced[produced.rindex("\n", 0, first) + 1:produced.index("\n", first) + 1])
        self.assertEqual("                            .size(com.bilipai.desktop.ui.desktopCardOverflowTarget(AppChromeSizeTokens.MinimumTouchTarget))\n",
                         produced[produced.rindex("\n", 0, second) + 1:produced.index("\n", second) + 1])
        self.assertLess(produced.index('contentDescription = "取消收藏",'), second)
        self.assertLess(second, produced.index("openDismissMenu(menuButtonCoordsRef.value, null)"))
        self.assertLess(produced.index("if (hasOverflowMenu) {\n                    Box("), second)
        # The five lines that follow are the vendored ones, and the single-column overflow icon is untouched.
        self.assertIn('.semantics { contentDescription = "更多操作" }\n                            .onGloballyPositioned { coordinates ->\n'
                      '                                menuButtonCoordsRef.value = coordinates\n                            }\n', produced[second:])
        self.assertEqual(2, produced.count('contentDescription = "更多操作"'))
        self.assertEqual(1, produced.count('AppIcon(Icons.Outlined.MoreVert, contentDescription = "更多操作")'))
        self.assertEqual(1, produced.count("desktopCardOverflowTarget("))

    def test_the_three_labels_keep_their_vendored_text_in_the_wrapper_call(self):
        produced = self.card(self.layered)
        self.assertEqual(3, produced.count("com.bilipai.desktop.ui.desktopCardMenuLabel("))
        for entry in cm.section()["labels"]:
            plain = cm.plain_label(entry)
            original = chr(int(entry["emoji_code_point"], 16)) + " " + plain
            if entry["id"] == "dismiss":
                original = "\\uD83D\\uDEAB " + plain
                call = 'dismissMenuText: String = com.bilipai.desktop.ui.desktopCardMenuLabel(original = "%s", plain = "%s"), //  [新增] 自定义长按菜单删除文案\n' % (original, plain)
            else:
                call = '                                com.bilipai.desktop.ui.desktopCardMenuLabel(original = "%s", plain = "%s"),\n' % (original, plain)
            self.assertEqual(1, produced.count(call), entry["id"])
            self.assertEqual(1, produced.count('original = "%s"' % original), entry["id"])
            self.assertEqual(1, produced.count('plain = "%s"' % plain), entry["id"])
        # The two literals sit in the AppText of their menu rows, inside the composable text lambda; the default is a parameter default.
        self.assertEqual(1, produced.count('AppText(\n                                com.bilipai.desktop.ui.desktopCardMenuLabel(original = "' + layer.WATCH_LATER_ORIGINAL + '"'))
        self.assertEqual(1, produced.count('AppText(\n                                com.bilipai.desktop.ui.desktopCardMenuLabel(original = "' + layer.UNFAVORITE_ORIGINAL + '"'))
        # The vendored uses of the default stay as they are: the menu row text and the single-column action label.
        self.assertEqual(1, produced.count("                                dismissMenuText,\n"))
        self.assertEqual(1, produced.count("AppWindowAction(label = dismissMenuText, onClick = it)"))
        # The labels of the single-column menu are plain already and are not touched.
        self.assertEqual(1, produced.count('AppWindowAction(label = "加入稍后再看", onClick = it)'))
        self.assertEqual(1, produced.count('AppWindowAction(label = "取消收藏", onClick = it)'))

    def test_the_label_constants_of_the_layer_are_the_contract_labels(self):
        # The contract names the labels by id and emoji code point; the layer's constants must agree with both.
        labels = cm.section()["labels"]
        self.assertEqual(["watch_later", "unfavorite", "dismiss"], [entry["id"] for entry in labels])
        self.assertEqual(cm.plain_label(labels[0]), layer.WATCH_LATER_PLAIN)
        self.assertEqual(cm.plain_label(labels[1]), layer.UNFAVORITE_PLAIN)
        self.assertEqual(cm.plain_label(labels[2]), layer.DISMISS_PLAIN)
        self.assertEqual(cm.original_label(labels[0]), layer.WATCH_LATER_ORIGINAL)
        self.assertEqual(cm.original_label(labels[1]), layer.UNFAVORITE_ORIGINAL)
        self.assertEqual("\\uD83D\\uDEAB " + cm.plain_label(labels[2]), layer.DISMISS_ORIGINAL_KOTLIN)
        self.assertEqual(chr(int(labels[2]["emoji_code_point"], 16)) + " " + layer.DISMISS_PLAIN, cm.original_label(labels[2]))

    def test_no_caller_passes_its_own_dismiss_menu_text(self):
        # A named argument dismissMenuText = ... in any Kotlin source or generator would bypass the changed default.
        result = subprocess.run(["git", "-c", "core.autocrlf=false", "grep", "--untracked", "-n", "-E", r"dismissMenuText[[:space:]]*=[^=]", "--",
                                 "app", "design-system", "core-data", "desktop/src", "desktop/tools"],
                                cwd=str(REPO), stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
        self.assertIn(result.returncode, (0, 1), result.stderr.decode("utf-8", "replace"))
        hits = [line for line in result.stdout.decode("utf-8", "replace").splitlines() if "/test_ux_home_card_adaptations.py:" not in line]
        self.assertEqual([], hits)
        for files in (self.plain, self.layered):
            for relative, data in files.items():
                if relative.endswith(".kt"):
                    self.assertIsNone(re.search(r"dismissMenuText\s*=[^=]", data.decode("utf-8")), relative)
        # The default is declared once, in the one function that has the parameter.
        self.assertEqual(1, len(re.findall(r"dismissMenuText: String =", self.card(self.layered))))
        self.assertEqual(1, self.card(self.layered).count("internal fun ElegantVideoCard("))

    def test_the_brackets_balance_as_before(self):
        for pair in ("()", "{}", "[]"):
            counts = []
            for files in (self.plain, self.layered):
                code = source.mask_kotlin(self.card(files))
                counts.append(code.count(pair[0]) - code.count(pair[1]))
            self.assertEqual(counts[0], counts[1], pair)

    def test_the_new_names_occur_only_in_the_card_of_the_generated_tree(self):
        for relative, data in self.layered.items():
            if not relative.endswith(".kt") or relative == CARD_PATH:
                continue
            text = data.decode("utf-8")
            for name in layer.INTRODUCED:
                self.assertNotIn(name, text, (relative, name))

    def test_every_call_of_a_new_function_names_only_declared_parameters_and_gives_every_required_one(self):
        code = source.mask_kotlin(self.card(self.layered))
        wrappers = {name: declared_parameters(source.read(path), name) for name, path in MENU_FUNCTIONS.items()
                    if name in ("desktopCardMenuLabel", "desktopCardOverflowTarget", "desktopCardSecondaryClick")}
        calls = 0
        for name, parameters in wrappers.items():
            for match in re.finditer(r"(?:com\.bilipai\.desktop\.ui\.|\.)%s\(" % re.escape(name), code):
                calls += 1
                arguments = top_level_arguments(code, match.end() - 1)
                named = [argument.split("=", 1)[0].strip() for argument in arguments if re.match(r"^\w+\s*=[^=]", argument)]
                positional = len(arguments) - len(named)
                declared = [parameter for parameter, _default in parameters]
                self.assertTrue(set(named) <= set(declared), (name, named, declared))
                given = set(named) | set(declared[:positional])
                # The right click handler is given its last parameter as a trailing lambda, after the closing parenthesis.
                trailing = {"desktopCardSecondaryClick": {"onSecondaryClick"}}.get(name, set())
                required = {parameter for parameter, default in parameters if not default}
                self.assertTrue(required <= given | trailing, (name, sorted(required - given - trailing)))
        # Three labels, one overflow target and one right click.
        self.assertEqual(5, calls)


class WiringTest(unittest.TestCase):
    HOOK = '  body=__import__("ux_home_card").apply_home_card(repo,Path(path).name,body,changes)'
    GRADLE_LINE = '    inputs.files("tools/ux_home_card.py")\n'

    def test_the_hook_is_one_line_after_the_recommendation_badge_layer_and_before_the_output(self):
        lines = source.read(PRODUCER).split("\n")
        hooks = [index for index, line in enumerate(lines) if "ux_home_card" in line]
        self.assertEqual(1, len(hooks))
        index = hooks[0]
        self.assertEqual(self.HOOK, lines[index])
        self.assertEqual("  body=apply_card(repo,Path(path).name,body,changes)", lines[index - 1])
        self.assertEqual("  out=HERE/'generated'/pkg.replace('.','/')/Path(path).name", lines[index + 1])
        # It sits inside the per-file loop, after the ADAPTED block, so it sees the file the producer is about to write.
        self.assertIn(" for path in DIRECT+list(EXTRACTED)+ADAPTED:", lines[:index])

    def test_the_hook_calls_the_module_attribute_at_call_time(self):
        # The tests patch ux_home_card.apply_home_card; the hook must look the attribute up each time (no from-import binding at load).
        text = source.read(PRODUCER)
        self.assertEqual(0, len(re.findall(r"(?m)^\s*(from|import) ux_home_card", text)))
        self.assertEqual(1, text.count('__import__("ux_home_card").apply_home_card('))

    def task_block(self, name):
        build = source.read(BUILD)
        start = build.index("val %s by tasks.registering(" % name)
        end = build.index("\n}\n", start)
        return build[start:end]

    def test_the_module_is_an_input_of_the_full_card_task_and_nothing_else(self):
        full = self.task_block("extractUpstreamHomeFullCard") + "\n"
        self.assertEqual(1, full.count(self.GRADLE_LINE))
        self.assertEqual(1, source.read(BUILD).count("tools/ux_home_card.py"))
        # Directly after the line that tracks the Home density module, before the source lists of the task.
        self.assertIn('    inputs.files("tools/ux_home_layout.py")\n' + self.GRADLE_LINE + "    inputs.files(sources.filter {", full)
        self.assertNotIn("ux_home_card", self.task_block("extractOriginalHomePage"))

    def test_the_task_runs_the_hooked_producer(self):
        block = self.task_block("extractUpstreamHomeFullCard")
        self.assertIn('"tools/extract-upstream-home-full-card.py"', block)

    def test_the_home_page_producer_does_not_write_the_grid_card_in_production(self):
        # The full-card producer is the one writer of VideoCard.kt, so the one hook is enough. The Home page producer writes the Story card
        # (which also uses the dropdown family and is outside the H2 row) and no VideoCard.kt.
        home = load_producer(HOME_PRODUCER)
        with tempfile.TemporaryDirectory(prefix="bp-uxh2-home-") as folder:
            generate_quietly(home, Path(folder))
            written = sorted(path.name for path in Path(folder).rglob("*.kt") if path.name.endswith("Card.kt"))
        self.assertNotIn(layer.CARD, written)
        self.assertNotIn("HomeStyleSingleColumnVideoCard.kt", written)
        self.assertIn("StoryVideoCard.kt", written)
        self.assertNotIn("ux_home_card", source.read(HOME_PRODUCER))

    def test_only_the_hook_and_the_tests_name_the_module(self):
        names = sorted(path.name for path in TOOLS.glob("*.py") if path.name != "ux_home_card.py" and "ux_home_card" in source.read(path))
        self.assertEqual(["extract-upstream-home-full-card.py"], names)

    def test_no_other_generator_writes_the_new_names_into_generated_text(self):
        for path in TOOLS.glob("*.py"):
            if path.name == "ux_home_card.py":
                continue
            text = source.read(path)
            for name in ("desktopCardSecondaryClick", "desktopCardHandCursor", "desktopCardMenuLabel", "desktopCardOverflowTarget", "DesktopCardContextMenu"):
                self.assertNotIn(name, text, (path.name, name))

    def test_the_layer_is_a_producer_of_the_card_only(self):
        self.assertEqual("VideoCard.kt", layer.CARD)
        self.assertEqual(1, source.read(LAYER_PY).count("def apply_home_card("))
        self.assertEqual(1, source.read(LAYER_PY).count("def reverse("))


class KotlinSourceTest(unittest.TestCase):
    MENU = source.read(cm.MENU_KT)
    POINTER = source.read(cm.POINTER_KT)
    POLICY = source.read(cm.POLICY_KT)

    def test_the_new_functions_are_declared_once_in_their_file_and_nowhere_else(self):
        for name, path in MENU_FUNCTIONS.items():
            code = source.mask_kotlin(source.read(path))
            self.assertEqual(1, len(re.findall(r"\bfun (?:[\w.]+\.)?%s\(" % name, code)), name)
        policy = source.mask_kotlin(self.POLICY)
        for name in POLICY_FUNCTIONS:
            self.assertEqual(1, len(re.findall(r"\binternal fun %s\(" % name, policy)), name)
        names = list(MENU_FUNCTIONS) + list(POLICY_FUNCTIONS)
        for path in sorted(source.K.rglob("*.kt")) + sorted(source.KT.rglob("*.kt")):
            if path in cm.CARD_FILES:
                continue
            code = source.mask_kotlin(source.read(path))
            for name in names:
                found = re.search(r"\bfun (?:[\w.]+\.)?%s\(" % re.escape(name), code)
                if found is None:
                    continue
                # The compile-only probe of slice XP declares stand-ins with these two names in its own package (com.bilipai.desktop.ux).
                self.assertEqual("ux/UxApiProbeTest.kt", source.relative_posix(path)[len("desktop/src/test/kotlin/com/bilipai/desktop/"):], (source.relative_posix(path), name))
                self.assertIn(name, ("DesktopCardContextMenu", "DesktopCardContextMenuItem"))
                self.assertIn("package com.bilipai.desktop.ux\n", source.read(path))

    def test_the_composables_are_composable_and_the_policy_is_not(self):
        menu = shell.squash(source.mask_kotlin(self.MENU))
        for name in ("desktopCardInteractionsOn", "desktopCardMenuLabel", "desktopCardOverflowTarget", "DesktopCardContextMenu", "DesktopCardContextMenuItem"):
            self.assertEqual(1, menu.count("@Composable internal fun %s(" % name), name)
        pointer = shell.squash(source.mask_kotlin(self.POINTER))
        self.assertEqual(1, pointer.count("@Composable internal fun Modifier.desktopCardSecondaryClick("))
        self.assertEqual(1, pointer.count("@Composable internal fun Modifier.desktopCardHandCursor()"))
        self.assertNotIn("@Composable", source.mask_kotlin(self.POLICY))
        self.assertIsNone(re.search(r"(?m)^\s*import ", source.mask_kotlin(self.POLICY)))

    def test_the_menu_and_the_row_keep_the_call_shape_of_the_generated_card_and_only_vendored_parameter_names(self):
        menu = declared_parameters(self.MENU, "DesktopCardContextMenu")
        item = declared_parameters(self.MENU, "DesktopCardContextMenuItem")
        self.assertEqual([("expanded", False), ("onDismissRequest", False), ("modifier", True), ("offset", True), ("content", False)], menu)
        self.assertEqual([("text", False), ("onClick", False), ("modifier", True), ("enabled", True)], item)
        used_menu, used_item = {"expanded", "onDismissRequest", "offset"}, {"text", "onClick"}
        self.assertTrue(used_menu <= {name for name, _ in menu})
        self.assertTrue(used_item <= {name for name, _ in item})
        self.assertTrue({name for name, default in menu if not default} <= used_menu | {"content"})
        self.assertTrue({name for name, default in item if not default} <= used_item)
        for path in VENDORED_MENUS:
            text = source.read(path)
            vendored_menu = [name for name, _ in declared_parameters(text, "AppDropdownMenu")]
            vendored_item = [name for name, _ in declared_parameters(text, "AppDropdownMenuItem")]
            self.assertTrue({name for name, _ in menu} <= set(vendored_menu), (path.name, vendored_menu))
            self.assertTrue({name for name, _ in item} <= set(vendored_item), (path.name, vendored_item))
            self.assertEqual("content", vendored_menu[-1])
            self.assertIn("content: @Composable ColumnScope.() -> Unit", shell.squash(text))
        self.assertIn("content: @Composable ColumnScope.() -> Unit,", self.MENU)
        self.assertIn("text: @Composable () -> Unit,", self.MENU)

    def test_off_reaches_the_vendored_code_and_on_reaches_the_kit_popup(self):
        code = source.mask_kotlin(self.MENU)
        self.assertTrue(has(code, "if (!desktopCardInteractionsOn()) { AppDropdownMenu( expanded = expanded, onDismissRequest = onDismissRequest, "
                                  "modifier = modifier, offset = offset, content = content, ) return }"))
        self.assertTrue(has(code, "if (!desktopCardInteractionsOn()) { AppDropdownMenuItem(text = text, onClick = onClick, modifier = modifier, enabled = enabled) return }"))
        self.assertTrue(has(code, "DesktopContextMenu( expanded = expanded, onDismissRequest = onDismissRequest, pointer = pointer, modifier = modifier, "
                                  "width = DESKTOP_CARD_MENU_WIDTH_DP.dp, content = content, )"))
        self.assertTrue(has(code, "val pointer = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }"))
        self.assertTrue(has(code, ".height(DESKTOP_CARD_MENU_ROW_DP.dp)"))
        self.assertTrue(has(code, ".desktopKitInteraction(source, DESKTOP_KIT_RADIUS_CONTROL_DP.dp, enabled)"))
        self.assertTrue(has(code, ".clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)"))
        self.assertTrue(has(code, "desktopCardMenuLabelFor(desktopCardInteractionsOn(), original, plain)"))
        self.assertTrue(has(code, "desktopCardOverflowTargetDpFor(desktopCardInteractionsOn(), original.value).dp"))
        self.assertTrue(has(code, "val settings by LocalDesktopWindowsUx.current.settings.collectAsState() "
                                  "return settings.cardInteractions == DesktopWindowsUxCardInteractions.ON"))

    def test_the_kit_popup_the_menu_calls_exists_with_these_parameters(self):
        kit_menus = source.read(source.K / "ui/kit/DesktopKitMenus.kt")
        parameters = [name for name, _ in declared_parameters(kit_menus, "DesktopContextMenu")]
        self.assertEqual(["expanded", "onDismissRequest", "pointer", "modifier", "width", "label", "content"], parameters)
        self.assertIn("pointer: IntOffset,", kit_menus)
        self.assertIn("width: Dp = DESKTOP_KIT_MENU_WIDTH_DP.dp,", kit_menus)
        # The wrapper gives the kit popup a narrower width than the kit default, from the contract.
        self.assertLess(cm.section()["menu"]["width_dp"], model_width())

    def test_the_right_click_handler_watches_the_initial_pass_and_consumes_the_press_and_the_release(self):
        code = source.mask_kotlin(self.POINTER)
        self.assertTrue(has(code, "val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)"))
        self.assertTrue(has(code, "val buttons = currentEvent.buttons"))
        self.assertTrue(has(code, "if (!desktopCardOwnsSecondaryPress(buttons.isPrimaryPressed, buttons.isSecondaryPressed)) return@awaitEachGesture"))
        self.assertTrue(has(code, "val event = awaitPointerEvent(PointerEventPass.Initial)"))
        self.assertTrue(has(code, "val change = event.changes.firstOrNull { it.id == down.id }"))
        self.assertTrue(has(code, "when (desktopCardSecondaryFollow(change != null, change?.pressed == true))"))
        for branch in ("DesktopCardSecondaryFollow.WAIT -> Unit", "DesktopCardSecondaryFollow.CONSUME_AND_END -> { change?.consume() return@awaitEachGesture }",
                       "DesktopCardSecondaryFollow.END -> return@awaitEachGesture"):
            self.assertTrue(has(code, branch), branch)
        # Order: a press that is not the card's returns before anything is consumed; the press is consumed before the menu opens; the menu
        # opens once, before the watch loop; the release is consumed inside the loop. Nothing else is consumed.
        self.assertLess(code.index("return@awaitEachGesture"), code.index("down.consume()"))
        self.assertLess(code.index("down.consume()"), code.index("latest(down.position)"))
        self.assertLess(code.index("latest(down.position)"), code.index("while (true)"))
        self.assertEqual(2, len(re.findall(r"\.consume\(\)", code)))
        self.assertEqual(1, len(re.findall(r"\bdown\.consume\(\)", code)))
        self.assertEqual(1, len(re.findall(r"\bchange\?\.consume\(\)", code)))
        # Both passes the handler awaits are the Initial pass; the Main pass is never awaited here.
        self.assertEqual(2, len(re.findall(r"PointerEventPass\.Initial", code)))
        self.assertNotIn("PointerEventPass.Main", code)
        self.assertNotIn("PointerEventPass.Final", code)

    def test_both_modifiers_return_the_modifier_they_were_called_on_while_the_flag_is_off(self):
        code = source.mask_kotlin(self.POINTER)
        self.assertTrue(has(code, "val latest by rememberUpdatedState(onSecondaryClick) "
                                  "if (!desktopCardSecondaryClickActive(enabled, desktopCardInteractionsOn())) return this return pointerInput(Unit) {"))
        self.assertTrue(has(code, "if (desktopCardInteractionsOn()) pointerHoverIcon(PointerIcon.Hand) else this"))
        # The remember sits before the early return, so the composition shape does not depend on the flag.
        self.assertLess(code.index("rememberUpdatedState(onSecondaryClick)"), code.index("return this"))

    def test_the_imports_resolve_and_are_used(self):
        for path in cm.CARD_FILES:
            text = source.read(path)
            imports = re.findall(r"(?m)^import (\S+)$", text)
            code = source.mask_kotlin(text)
            body = code[code.index("\n", code.rindex("import ")):] if imports else code
            self.assertEqual(len(imports), len(set(imports)), path.name)
            for imported in imports:
                package, simple = imported.rsplit(".", 1)
                if simple != "getValue":
                    self.assertGreaterEqual(len(re.findall(r"\b%s\b" % re.escape(simple), body)), 1, (path.name, imported))
                if package.startswith(("androidx.compose", "kotlin.")):
                    continue
                self.assertIn(package, declaring_packages(simple), (path.name, imported))

    def test_same_package_names_the_files_use_are_declared_in_the_package(self):
        for name in ("DESKTOP_CARD_MENU_ROW_DP", "DESKTOP_CARD_MENU_WIDTH_DP", "DESKTOP_CARD_OVERFLOW_TARGET_DP", "DesktopCardSecondaryFollow",
                     "desktopCardMenuLabelFor", "desktopCardOverflowTargetDpFor", "desktopCardSecondaryClickActive",
                     "desktopCardOwnsSecondaryPress", "desktopCardSecondaryFollow"):
            self.assertIn("com.bilipai.desktop.ui", declaring_packages(name), name)
        for name in ("DesktopContextMenu", "desktopKitInteraction", "desktopKitDisabledAlpha", "DESKTOP_KIT_RADIUS_CONTROL_DP"):
            self.assertIn("com.bilipai.desktop.ui.kit", declaring_packages(name), name)
        for name in ("DesktopWindowsUxCardInteractions", "LocalDesktopWindowsUx"):
            self.assertIn("com.bilipai.desktop.appearance", declaring_packages(name), name)

    def test_the_flag_has_exactly_one_reader_and_the_settings_row_is_on(self):
        readers = []
        for path in sorted(source.K.rglob("*.kt")):
            if path == SETTINGS_KT or source.rel(path).startswith("appearance/DesktopWindowsUx"):
                continue
            if re.search(r"\.cardInteractions\b", source.mask_kotlin(source.read(path))):
                readers.append(source.rel(path))
        self.assertEqual(["ui/DesktopCardContextMenu.kt"], readers)
        self.assertIn("private const val CARD_INTERACTIONS_HAS_CONSUMER = true\n", source.read(SETTINGS_KT))
        defaults = source.read(source.K / "appearance/DesktopWindowsUxModel.kt")
        self.assertIn("val cardInteractions: DesktopWindowsUxCardInteractions = DesktopWindowsUxCardInteractions.ON,", defaults)

    def test_the_files_are_plain_lf_text_without_chinese_hooks_or_key_fallback(self):
        source.assert_lf_only(self, *cm.CARD_FILES, LAYER_PY, PRODUCER, BUILD, SETTINGS_KT, cm.KOTLIN_TEST)
        for path in cm.CARD_FILES:
            text = source.read(path)
            self.assertEqual([], source.cjk_positions(text), path.name)
            self.assertEqual([], source.hook_uses(text), path.name)
            self.assertEqual([], source.key_fallback_uses(text), path.name)
            self.assertEqual([], source.banned_imports(text), path.name)
            self.assertTrue(text.endswith("\n") and not text.endswith("\n\n"), path.name)
            self.assertTrue(text.startswith("package com.bilipai.desktop.ui\n"), path.name)

    def test_the_program_scope_arms_the_three_files(self):
        for name in ("ui/DesktopCardContextMenu.kt", "ui/DesktopCardPointer.kt", "ui/DesktopCardInteractionPolicy.kt"):
            self.assertIn(name, source.PROGRAM_EXTRA_GLOBS)

    def test_delimiters_of_the_new_kotlin_files_balance(self):
        for path in cm.CARD_FILES:
            code = source.mask_kotlin(source.read(path))
            for pair in ("()", "{}", "[]"):
                self.assertEqual(code.count(pair[0]), code.count(pair[1]), (path.name, pair))

    def test_the_top_level_names_of_the_new_files_are_new_in_the_source_tree(self):
        names = []
        for path in cm.CARD_FILES:
            names += re.findall(r"(?m)^(?:@Composable\n)?internal (?:fun (?:Modifier\.)?|val |const val |class |enum class |object )(\w+)", source.read(path))
        # The menu file: the flag reader, the label, the target, the menu and the row (5). The pointer file: the two modifiers (2). The
        # policy: three tokens, the enum and five functions (9).
        self.assertEqual(16, len(names), names)
        self.assertEqual(len(names), len(set(names)))
        for path in sorted(source.K.rglob("*.kt")) + sorted(source.KT.rglob("*.kt")):
            if path in cm.CARD_FILES:
                continue
            code = source.mask_kotlin(source.read(path))
            for name in names:
                found = re.search(r"(?m)^\s*(internal |public |private )?(fun|val|const val|class|enum class|object) (?:Modifier\.)?%s\b" % re.escape(name), code)
                if found is not None:
                    # Only the stand-ins of the compile-only probe of slice XP, in their own package.
                    self.assertEqual("ux/UxApiProbeTest.kt", source.relative_posix(path)[len("desktop/src/test/kotlin/com/bilipai/desktop/"):], (source.relative_posix(path), name))


def model_width():
    """The kit default width of a context menu, from the contract (the card menu is narrower)."""
    return cm.model.load()["kit"]["menu"]["width"]


class LayerModuleTest(unittest.TestCase):
    def test_the_module_is_lf_python_that_raises_assertion_errors(self):
        text = source.read(LAYER_PY)
        self.assertNotIn("\r", LAYER_PY.read_bytes().decode("utf-8"))
        self.assertNotIn("\nassert ", text)
        self.assertEqual(1, text.count("def apply_home_card("))
        self.assertEqual(1, text.count("def reverse("))
        self.assertEqual([], [ch for ch in text if ord(ch) > 0x1F000], "no emoji character in the source: they are written as escapes")

    def test_every_anchor_is_a_whole_line_start(self):
        for label, before, after in layer.EDITS:
            self.assertTrue(before.endswith("\n"), label)
            self.assertTrue(after.endswith("\n"), label)
            self.assertTrue(before.startswith(("import ", "    ", " ", "\n")), label)

    def test_the_names_the_layer_introduces_are_the_declared_ones(self):
        self.assertEqual({"DesktopCardContextMenu", "DesktopCardContextMenuItem", "desktopCardSecondaryClick", "desktopCardHandCursor",
                          "desktopCardMenuLabel", "desktopCardOverflowTarget"}, set(layer.INTRODUCED))
        for name in layer.INTRODUCED:
            self.assertIn(name, MENU_FUNCTIONS)
        used = set()
        for _label, _before, after in layer.EDITS:
            used.update(re.findall(r"com\.bilipai\.desktop\.ui\.(\w+)", after))
        self.assertEqual(set(layer.INTRODUCED), used)

    def test_reverse_refuses_a_text_that_is_not_layered_and_a_file_it_does_not_own(self):
        with self.assertRaises(AssertionError):
            layer.reverse(layer.CARD, "nothing here")
        with self.assertRaises(AssertionError):
            layer.reverse("StoryVideoCard.kt", "nothing here")

    def test_the_vendored_card_is_the_one_the_anchors_were_read_from(self):
        # The canonical source (before the earlier layers) holds the three vendored labels and the two token uses.
        text = source.read(REPO / CARD_SOURCE)
        self.assertEqual(1, text.count('"%s",\n' % layer.WATCH_LATER_ORIGINAL))
        self.assertEqual(1, text.count('"%s",\n' % layer.UNFAVORITE_ORIGINAL))
        self.assertEqual(1, text.count('dismissMenuText: String = "%s",' % layer.DISMISS_ORIGINAL_KOTLIN))
        self.assertEqual(2, text.count("AppChromeSizeTokens.MinimumTouchTarget"))


if __name__ == "__main__":
    unittest.main()
