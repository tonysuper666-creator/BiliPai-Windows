"""Slice K3c generator checks: the desktop HomeHeader edits of the Home layer on the real generated HomeHeader.kt. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_header.py

The real sole Home producer (extract-upstream-home-page.py) runs twice into temporary directories, with the layer and with the layer
patched out. The header edits (ux_home_layout.HEADER_EDITS) are checked on what the producer hands the layer and on what it writes:

  forward and inverse  the layer applied on its own to the received HomeHeader text is the written file byte for byte, and reverse()
                       of the written file is the received text byte for byte; every anchor occurs exactly once where it must
  fail closed          a missing or doubled anchor, an already layered text and a taken name all raise AssertionError
  desktop branch       every avatar and right action tap in HomeHeader (the folded tabs row and the search row) is inside an
                       if (!desktopHomeHeaderDeduped) block; the search entry is not, and the dock takes the centred 560 dp width
  CLASSIC branch       the vendored dock width chain stays word for word in the else branch, the vendored lines are all still there
                       in their order, and the flag is DESKTOP only through the existing desktopHomeFeedLayoutKey()
  wiring               the flag is read once, before its first use, through the existing wrapper and enum; HomeHeader is declared once
                       and called once, from the generated DesktopOriginalHomeScreen.kt
"""
import importlib.util
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_source as source  # noqa: E402

REPO = source.REPO
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
import ux_home_layout as layer  # noqa: E402

PRODUCER = TOOLS / "extract-upstream-home-page.py"
HOME_DIR = "com/android/purebilibili/feature/home/"
HEADER_PATH = HOME_DIR + "components/" + layer.HEADER
SCREEN_PATH = HOME_DIR + layer.SCREEN
LAYOUT_KT = source.K / "ui/DesktopHomeFeedLayout.kt"
MODEL_KT = source.K / "appearance/DesktopWindowsUxModel.kt"
OFF = "if (!desktopHomeHeaderDeduped) {"
VENDORED_DOCK_WIDTH = (".weight(1f)\n", ".widthIn(max = AppSpacingTokens.TripleExtraLarge * 13 + AppSpacingTokens.Large)\n")
# The taps that open the profile or the right action (settings or inbox) in HomeHeader, all of them.
TAPS = ("performHomeTopBarTap(haptic = haptic, onClick = onAvatarClick)", "onAvatarClick()",
        "performHomeTopBarTap(haptic = haptic, onClick = onTopRightActionClick)", "onTopRightActionClick()")


def load_producer():
    spec = importlib.util.spec_from_file_location("ux_k3c_home_page", PRODUCER)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def tree(root):
    root = Path(root)
    return {path.relative_to(root).as_posix(): path.read_bytes() for path in sorted(root.rglob("*")) if path.is_file()}


def matching(code, open_index, pair):
    depth = 0
    for index in range(open_index, len(code)):
        if code[index] == pair[0]:
            depth += 1
        elif code[index] == pair[1]:
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError("unbalanced " + pair)


def header_body(text):
    """(start, end) of the body of the single fun HomeHeader( in text."""
    code = source.mask_kotlin(text)
    found = [m.end() - 1 for m in re.finditer(r"(?m)^fun HomeHeader\(", code)]
    if len(found) != 1:
        raise AssertionError("HomeHeader is declared %d times" % len(found))
    open_brace = code.index("{", matching(code, found[0], "()"))
    return open_brace, matching(code, open_brace, "{}")


def off_blocks(text):
    """[(start, end)] of every if (!desktopHomeHeaderDeduped) { ... } block."""
    code = source.mask_kotlin(text)
    return [(i, matching(code, i + len(OFF) - 1, "{}")) for i in (m.start() for m in re.finditer(re.escape(OFF), code))]


class HomeHeaderLayerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-uxk3c-")
        root = Path(cls.temp.name)
        producer = load_producer()
        producer.generate(REPO, root / "a")
        with mock.patch.object(layer, "apply_home_layout", lambda repo, name, text, audits: text):
            producer.generate(REPO, root / "p")
        cls.layered, cls.plain = tree(root / "a"), tree(root / "p")
        cls.received = cls.plain[HEADER_PATH].decode("utf-8")
        cls.produced = cls.layered[HEADER_PATH].decode("utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    # forward and inverse

    def test_the_layer_alone_on_the_received_text_is_the_written_file_byte_for_byte(self):
        audits = []
        forward = layer.apply_home_layout(REPO, layer.HEADER, self.received, audits)
        self.assertEqual(self.layered[HEADER_PATH], forward.encode("utf-8"))
        self.assertEqual([label for label, _b, _a in layer.HEADER_EDITS], [row["id"] for row in audits])

    def test_reverse_of_the_written_file_is_the_received_text_byte_for_byte(self):
        self.assertEqual(self.plain[HEADER_PATH], layer.reverse(layer.HEADER, self.produced).encode("utf-8"))

    def test_every_header_anchor_occurs_exactly_once(self):
        self.assertTrue(layer.HEADER_EDITS)
        for label, before, after in layer.HEADER_EDITS:
            self.assertTrue(label.startswith("ux-home-header-"), label)
            self.assertNotEqual(before, after, label)
            self.assertEqual(1, self.received.count(before), label)
            self.assertEqual(0, self.received.count(after), label)
            self.assertEqual(1, self.produced.count(after), label)

    def test_the_header_edits_only_insert_text_around_vendored_lines(self):
        # Every vendored line of an anchor is still in its replacement, in the same order: nothing vendored is dropped or rewritten.
        for label, before, after in layer.HEADER_EDITS:
            position = 0
            for line in before.splitlines(keepends=True):
                found = after.find(line.strip(), position)
                self.assertGreaterEqual(found, 0, (label, line))
                position = found + len(line.strip())
        self.assertEqual(len(self.received) + sum(len(a) - len(b) for _l, b, a in layer.HEADER_EDITS), len(self.produced))

    def test_the_header_edits_are_a_table_of_their_own_next_to_the_eight_h1_edits(self):
        self.assertNotIn(layer.HEADER, layer.EDITS)
        self.assertEqual(8, sum(len(edits) for edits in layer.EDITS.values()))
        self.assertFalse({label for label, _b, _a in layer.HEADER_EDITS} & {label for edits in layer.EDITS.values() for label, _b, _a in edits})

    # fail closed

    def test_a_missing_or_doubled_anchor_fails_closed(self):
        for label, before, _after in layer.HEADER_EDITS:
            with self.assertRaises(AssertionError, msg=label + " missing"):
                layer.apply_home_layout(REPO, layer.HEADER, self.received.replace(before, before.replace("\n", " \n", 1), 1), [])
            with self.assertRaises(AssertionError, msg=label + " doubled"):
                layer.apply_home_layout(REPO, layer.HEADER, self.received + "\n" + before, [])

    def test_an_already_layered_text_is_refused(self):
        with self.assertRaises(AssertionError):
            layer.apply_home_layout(REPO, layer.HEADER, self.produced, [])

    def test_a_taken_name_is_refused_before_any_edit(self):
        for introduced in layer.HEADER_INTRODUCED:
            audits = []
            with self.assertRaises(AssertionError, msg=introduced):
                layer.apply_home_layout(REPO, layer.HEADER, self.received + "\nval %s = 1\n" % introduced, audits)
            self.assertEqual([], audits, introduced)

    def test_reverse_refuses_a_text_that_is_not_layered(self):
        with self.assertRaises(AssertionError):
            layer.reverse(layer.HEADER, self.received)

    # desktop branch

    def test_every_avatar_and_right_action_tap_is_inside_a_desktop_off_block(self):
        start, end = header_body(self.produced)
        blocks = off_blocks(self.produced)
        self.assertEqual(4, len(blocks))  # The folded row and the search row: one block for the avatar, one for the right action.
        self.assertTrue(all(start < a and b < end for a, b in blocks))
        code = source.mask_kotlin(self.produced)
        seen = 0
        for tap in TAPS:
            for match in re.finditer(re.escape(tap), code[start:end]):
                at = start + match.start()
                seen += 1
                self.assertTrue(any(a < at < b for a, b in blocks), (tap, self.produced.count("\n", 0, at) + 1))
        self.assertEqual(6, seen)
        received_start, received_end = header_body(self.received)
        received_code = source.mask_kotlin(self.received)[received_start:received_end]
        self.assertEqual(6, sum(received_code.count(tap) for tap in TAPS))
        # The described avatar of the folded row (the accessible name the desktop must not show twice) is inside a block too.
        profile = 'contentDescription = "\\u4e2a\\u4eba\\u4e2d\\u5fc3"'.encode("ascii").decode("unicode_escape")
        self.assertEqual(1, self.received.count(profile))
        self.assertEqual(1, self.produced.count(profile))
        at = self.produced.index(profile)
        self.assertTrue(any(a < at < b for a, b in blocks))

    def test_the_search_entry_stays_outside_every_off_block_and_takes_the_centred_desktop_width(self):
        blocks = off_blocks(self.produced)
        code = source.mask_kotlin(self.produced)
        searches = [m.start() for m in re.finditer(r"onSearchClick\(\)", code)]
        start, end = header_body(self.produced)
        searches = [at for at in searches if start < at < end]
        self.assertTrue(searches)
        for at in searches:
            self.assertFalse(any(a < at < b for a, b in blocks), self.produced.count("\n", 0, at) + 1)
        dock = self.produced.index("BottomBarMatchedReusableLiquidDock(\n                                shape = CircleShape,")
        branch = self.produced[dock:self.produced.index("} else {", dock)]
        self.assertIn("if (desktopHomeHeaderDeduped) {", branch)
        for needle in (".weight(1f)", ".wrapContentWidth(Alignment.CenterHorizontally)", ".widthIn(max = 560.dp)", ".fillMaxWidth()"):
            self.assertEqual(1, branch.count(needle), needle)
        self.assertLess(branch.index(".wrapContentWidth("), branch.index(".widthIn(max = 560.dp)"))
        self.assertLess(branch.index(".widthIn(max = 560.dp)"), branch.index(".fillMaxWidth()"))

    # CLASSIC branch

    def test_classic_keeps_the_vendored_dock_width_chain_word_for_word(self):
        dock = self.produced.index("BottomBarMatchedReusableLiquidDock(\n                                shape = CircleShape,")
        else_at = self.produced.index("} else {", dock)
        else_end = self.produced.index("}\n", else_at + len("} else {"))
        classic = self.produced[else_at:else_end]
        self.assertEqual(["Modifier", *[part.strip() for part in VENDORED_DOCK_WIDTH]],
                         [line.strip() for line in classic.splitlines()[1:] if line.strip()])
        # The vendored chain is the one the received text has after Modifier, moved into the else branch and nothing else.
        vendored = "".join(" " * 36 + part for part in VENDORED_DOCK_WIDTH)
        self.assertEqual(1, self.received.count("modifier = Modifier\n" + vendored))
        self.assertEqual(0, self.produced.count(vendored))
        self.assertEqual(1, self.produced.count("Modifier\n" + "".join(" " * 48 + part for part in VENDORED_DOCK_WIDTH)))

    def test_the_flag_is_desktop_only_through_the_existing_key_and_enum(self):
        start, _end = header_body(self.produced)
        declaration = ("    val desktopHomeHeaderDeduped = com.bilipai.desktop.ui.desktopHomeFeedLayoutKey() ==\n"
                       "        com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout.DESKTOP\n")
        self.assertEqual(1, self.produced.count(declaration))
        at = self.produced.index(declaration)
        self.assertEqual(start + 2, at)  # The first statement of HomeHeader, so no branch reads it before it is set.
        self.assertEqual(1, self.produced.count("val desktopHomeHeaderDeduped"))
        # Four if (!flag) openers, their four closing comments, the declaration and the dock branch: no other reader.
        self.assertEqual(4 + 4 + 1 + 1, self.produced.count("desktopHomeHeaderDeduped"))
        self.assertEqual(4, self.produced.count("} // desktopHomeHeaderDeduped\n"))
        layout = source.mask_kotlin(source.read(LAYOUT_KT))
        self.assertEqual(1, len(re.findall(r"@Composable\s+internal fun desktopHomeFeedLayoutKey\(\): DesktopWindowsUxHomeLayout", layout)))
        model = source.read(MODEL_KT)
        self.assertIn("package com.bilipai.desktop.appearance\n", model)
        self.assertIn("internal enum class DesktopWindowsUxHomeLayout { DESKTOP, CLASSIC }", model)

    # wiring

    def test_home_header_is_declared_once_and_called_once_from_the_generated_home_screen(self):
        declared, called = [], []
        for relative, data in self.layered.items():
            if not relative.endswith(".kt"):
                continue
            code = source.mask_kotlin(data.decode("utf-8"))
            if re.search(r"(?m)^fun HomeHeader\(", code):
                declared.append(relative)
            for match in re.finditer(r"(?<![A-Za-z0-9_.])HomeHeader\(", code):
                if not code[max(0, match.start() - 4):match.start()].endswith("fun "):
                    called.append(relative)
        self.assertEqual([HEADER_PATH], declared)
        self.assertEqual([SCREEN_PATH], called)

    def test_only_home_header_of_the_header_edits_changes_and_the_brackets_balance(self):
        for pair in ("()", "{}", "[]"):
            counts = []
            for text in (self.received, self.produced):
                code = source.mask_kotlin(text)
                counts.append(code.count(pair[0]) - code.count(pair[1]))
            self.assertEqual(counts[0], counts[1], pair)
        start, end = header_body(self.produced)
        for label, _before, after in layer.HEADER_EDITS:
            at = self.produced.index(after)
            self.assertTrue(start - 2 <= at < end, label)


if __name__ == "__main__":
    unittest.main()
