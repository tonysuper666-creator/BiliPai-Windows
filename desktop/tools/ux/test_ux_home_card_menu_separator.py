"""Slice K3f generator checks: the menu separator edit (SEPARATOR_EDITS of desktop/tools/ux_home_card.py) on the generated VideoCard text.
No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_card_menu_separator.py

What is proved. The real Home card producer (extract-upstream-home-full-card.py) is run with the layer and with the layer patched out:

  SeparatorEditsTest    the one edit is the one written out below, after the H2, K3, K3b, K3d and K3e edits; its anchor occurs exactly
                        once in the text the earlier edits leave; that written-out edit applied to that text gives the produced card
                        byte for byte; undoing only it gives the H2 + K3 + K3b + K3d + K3e text back byte for byte, and the whole layer
                        reverses to the vendored card; the audit row carries its hashes; no row of the menu is added or removed
  SeparatorRefusalTest  a mutated or doubled K3f anchor and a taken K3f name fail closed with AssertionError, naming the edit or the
                        name, before any audit row is written
  SeparatorWiringTest   the line sits after the copy row and before 不感兴趣, inside the existing menu; it is one call of the kit
                        separator under a guard that names the three rows above it, so a menu without that row, or without a
                        predecessor, draws no line; the line is not a menu row (no click, no callback, no menu state); the vendored
                        row order is untouched

What is not proved. How the card renders and reacts: DesktopHomeCardMenuSeparatorTest (ImageComposeScene) opens the real generated
card menu, reads the one outlineVariant pixel row, walks the rows with the arrow keys and checks the OFF case.
"""
import hashlib
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import test_ux_home_card_adaptations as h2  # noqa: E402

layer = h2.layer
REPO = h2.REPO

LABELS = ["ux-card-menu-separator-row"]
# The one edit, written out here and not read from the layer.
EXPECTED = (
    ("ux-card-menu-separator-row",
     "\n                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n",
     "\n                // 分隔线：把常用操作和「不感兴趣」分开 (K3f)\n"
     "                if (onDismiss != null && (onWatchLater != null || onUnfavorite != null || copyHomeCardLink != null)) {\n"
     "                    com.bilipai.desktop.ui.DesktopCardMenuSeparator()\n"
     "                }\n"
     "\n                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n"),
)
NAMES = ("DesktopCardMenuSeparator",)
GUARD = ("                if (onDismiss != null && (onWatchLater != null || onUnfavorite != null "
         "|| copyHomeCardLink != null)) {\n")
CALL = "                    com.bilipai.desktop.ui.DesktopCardMenuSeparator()\n"
COMMENT = "                // 分隔线：把常用操作和「不感兴趣」分开 (K3f)\n"
DISMISS_COMMENT = "                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n"
COPY_VALUE = '                                "复制链接（Ctrl C）",\n'


def apply_edits(text, edits):
    for label, before, after in edits:
        if text.count(before) != 1:
            raise AssertionError("%s occurs %d times" % (label, text.count(before)))
        text = text.replace(before, after, 1)
    return text


def undo_edits(text, edits):
    for label, before, after in reversed(edits):
        if text.count(after) != 1:
            raise AssertionError("%s replacement occurs %d times" % (label, text.count(after)))
        text = text.replace(after, before, 1)
    return text


def only_the_separator_edits():
    """The layer with only its K3f edit, for a text the earlier edits have already changed: the refusal of the K3f anchor is then the
    refusal of that edit and not of an earlier one whose anchor overlaps it (the copy row of K3e uses the same line)."""
    return mock.patch.multiple(layer, ALL_EDITS=layer.SEPARATOR_EDITS, INTRODUCED=(), COVER_INTRODUCED=(),
                               LAYOUT_INTRODUCED=(), HOVER_INTRODUCED=(), COPY_INTRODUCED=(), GONE=())


class _Generated(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-uxk3f-")
        root = Path(cls.temp.name)
        producer = h2.load_producer(h2.PRODUCER)
        h2.generate_quietly(producer, root / "a")
        with mock.patch.object(layer, "apply_home_card", lambda repo, name, text, audits: text):
            h2.generate_quietly(producer, root / "p")
        cls.layered_tree, cls.plain_tree = h2.tree(root / "a"), h2.tree(root / "p")
        cls.layered_bytes, cls.plain_bytes = cls.layered_tree[h2.CARD_PATH], cls.plain_tree[h2.CARD_PATH]
        cls.layered, cls.plain = cls.layered_bytes.decode("utf-8"), cls.plain_bytes.decode("utf-8")
        # The text the K3f edit receives: the H2, K3, K3b, K3d and K3e edits applied, nothing else.
        cls.received = apply_edits(cls.plain, layer.EDITS + layer.COVER_EDITS + layer.LAYOUT_EDITS
                                   + layer.HOVER_EDITS + layer.COPY_EDITS)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()


class SeparatorEditsTest(_Generated):
    def test_the_edit_is_the_one_of_the_slice_after_the_earlier_ones(self):
        self.assertEqual(LABELS, [label for label, _b, _a in layer.SEPARATOR_EDITS])
        self.assertEqual(EXPECTED, layer.SEPARATOR_EDITS)
        self.assertEqual(layer.EDITS + layer.COVER_EDITS + layer.LAYOUT_EDITS + layer.HOVER_EDITS + layer.COPY_EDITS
                         + layer.SEPARATOR_EDITS, layer.ALL_EDITS)
        self.assertEqual(LABELS, [label for label, _b, _a in layer.ALL_EDITS[-1:]])
        self.assertEqual(NAMES, layer.SEPARATOR_INTRODUCED)
        for label, before, after in EXPECTED:
            self.assertNotEqual(before, after, label)
            self.assertTrue(before.endswith("\n") and after.endswith("\n"), label)

    def test_the_anchor_occurs_exactly_once_in_the_text_the_edit_receives(self):
        for label, before, after in EXPECTED:
            self.assertEqual(1, self.received.count(before), label)
            self.assertEqual(0, self.received.count(after), label)
            self.assertEqual(1, self.layered.count(after), label)

    def test_forward_is_the_written_out_edit_byte_for_byte(self):
        self.assertEqual(self.layered_bytes, apply_edits(self.received, EXPECTED).encode("utf-8"))
        audits = []
        produced = layer.apply_home_card(REPO, layer.CARD, self.plain, audits)
        self.assertEqual(self.layered_bytes, produced.encode("utf-8"))

    def test_inverse_gives_back_the_earlier_text_and_the_vendored_card_byte_for_byte(self):
        # Undoing only the K3f edit gives back the text of H2, K3, K3b, K3d and K3e exactly; the whole layer reverses to the
        # vendored card.
        self.assertEqual(self.received.encode("utf-8"), undo_edits(self.layered, EXPECTED).encode("utf-8"))
        self.assertEqual(self.plain_bytes, layer.reverse(layer.CARD, self.layered).encode("utf-8"))

    def test_the_layer_changes_only_the_card_and_the_inventory(self):
        self.assertEqual(set(self.plain_tree), set(self.layered_tree))
        changed = {name for name in self.layered_tree if self.layered_tree[name] != self.plain_tree[name]}
        self.assertEqual({h2.CARD_PATH, h2.INVENTORY_PATH}, changed)

    def test_the_last_audit_row_is_the_separator_edit(self):
        audits = []
        layer.apply_home_card(REPO, layer.CARD, self.plain, audits)
        self.assertEqual(LABELS, [row["id"] for row in audits[-1:]])
        for (label, before, after), row in zip(EXPECTED, audits[-1:]):
            self.assertEqual(hashlib.sha256(before.encode("utf-8")).hexdigest(), row["beforeSha256"], label)
            self.assertEqual(hashlib.sha256(after.encode("utf-8")).hexdigest(), row["afterSha256"], label)
            self.assertIs(True, row["inverseRestoresInput"], label)

    def test_no_menu_row_is_added_or_removed(self):
        # The line is not a row: the three vendored rows and the copy row of K3e stay the rows of the menu.
        self.assertEqual(self.received.count("AppDropdownMenuItem("), self.layered.count("AppDropdownMenuItem("))
        self.assertEqual(1, self.layered.count(COPY_VALUE))

    def test_the_brackets_balance_as_before(self):
        for pair in ("()", "{}", "[]"):
            counts = []
            for text in (self.received, self.layered):
                code = h2.source.mask_kotlin(text)
                counts.append(code.count(pair[0]) - code.count(pair[1]))
            self.assertEqual(counts[0], counts[1], pair)


class SeparatorRefusalTest(_Generated):
    def test_the_separator_only_layer_reproduces_the_card(self):
        # The control of the refusals below: with only the K3f edit, the layer turns the received text into the produced card.
        audits = []
        with only_the_separator_edits():
            self.assertEqual(self.layered, layer.apply_home_card(REPO, layer.CARD, self.received, audits))
        self.assertEqual(LABELS, [row["id"] for row in audits])

    def test_a_mutated_anchor_fails_closed(self):
        for label, before, _after in EXPECTED:
            # Insert a character after the first one that is not a space: the anchor then no longer occurs in the received text.
            start = len(before) - len(before.lstrip(" ")) + 1
            mutated = before[:start] + "X" + before[start:]
            self.assertEqual(0, self.received.count(mutated), label)
            audits = []
            with only_the_separator_edits(), self.assertRaisesRegex(AssertionError,
                                                                   "anchor count changed: %s \\(0\\)" % re.escape(label)):
                layer.apply_home_card(REPO, layer.CARD, self.received.replace(before, mutated, 1), audits)
            self.assertNotIn(label, [row["id"] for row in audits], label)
            # The full layer refuses the mutated vendored card as well, where the anchor is vendored text.
            if self.plain.count(before) == 1:
                with self.assertRaises(AssertionError, msg=label):
                    layer.apply_home_card(REPO, layer.CARD, self.plain.replace(before, mutated, 1), [])

    def test_a_doubled_anchor_fails_closed(self):
        for label, before, _after in EXPECTED:
            audits = []
            with only_the_separator_edits(), self.assertRaisesRegex(AssertionError,
                                                                   "anchor count changed: %s \\(2\\)" % re.escape(label)):
                layer.apply_home_card(REPO, layer.CARD, self.received + "\n" + before, audits)
            self.assertNotIn(label, [row["id"] for row in audits], label)
            with self.assertRaises(AssertionError, msg=label):
                layer.apply_home_card(REPO, layer.CARD, self.plain + "\n" + before, [])

    def test_a_taken_name_is_refused_before_any_edit(self):
        for name in NAMES:
            self.assertNotIn(name, self.plain, name)
            self.assertNotIn(name, self.received, name)
            audits = []
            with self.assertRaisesRegex(AssertionError, "name taken: %s " % re.escape(name)):
                layer.apply_home_card(REPO, layer.CARD, self.plain + "\nval %s = 1\n" % name, audits)
            self.assertEqual([], audits, name)

    def test_an_already_layered_card_is_refused(self):
        with self.assertRaises(AssertionError):
            layer.apply_home_card(REPO, layer.CARD, self.layered, [])


class SeparatorWiringTest(_Generated):
    def test_the_line_sits_after_the_copy_row_and_before_not_interested(self):
        self.assertEqual(0, self.received.count(COMMENT))
        self.assertEqual(1, self.layered.count(COMMENT))
        self.assertEqual(1, self.layered.count(CALL))
        # Inside the one menu, in the vendored row order, with the copy row of K3e last before the line.
        for earlier in (self.layered.index("                // 稍后再看\n"),
                        self.layered.index("// 取消收藏 (仅在收藏页显示)"),
                        self.layered.index(COPY_VALUE)):
            self.assertLess(earlier, self.layered.index(COMMENT))
        self.assertLess(self.layered.index(COMMENT), self.layered.index(DISMISS_COMMENT))
        # The line is drawn between the closing of the copy row and the opening of 不感兴趣.
        self.assertLess(self.layered.index("copyHomeCardLink?.invoke()"), self.layered.index(COMMENT))
        block = self.layered[self.layered.index(COMMENT):self.layered.index(DISMISS_COMMENT)]
        self.assertIn(GUARD, block)
        self.assertIn(CALL, block)
        self.assertLess(block.index(GUARD), block.index(CALL))

    def test_the_guard_names_the_three_rows_that_can_be_above_the_line(self):
        self.assertEqual(1, self.layered.count(GUARD))
        self.assertEqual(0, self.received.count(GUARD))
        # 不感兴趣 has to be drawn, and so has at least one of the three rows above it: 稍后再看, 取消收藏, 复制链接.
        for condition in ("onDismiss != null", "onWatchLater != null", "onUnfavorite != null", "copyHomeCardLink != null"):
            self.assertIn(condition, GUARD, condition)
        # No other callback can produce a row above the line, and none is named.
        for absent in ("onUpClick", "onLongClick", "triggerCardClick", "onClick"):
            self.assertNotIn(absent, GUARD, absent)

    def test_the_line_is_not_a_menu_row(self):
        block = self.layered[self.layered.index(COMMENT):self.layered.index(DISMISS_COMMENT)]
        for row_like in ("AppDropdownMenuItem(", "onClick", "showDismissMenu", "desktopCardMenuLabel", "Modifier."):
            self.assertNotIn(row_like, block, row_like)
        # One call, and it is the kit separator: no colour, size or line value of its own.
        self.assertEqual(1, block.count("DesktopCardMenuSeparator("))
        self.assertEqual(1, block.count("()\n"))
        self.assertNotIn("DesktopKitDivider", block)

    def test_the_new_name_occurs_only_in_the_card_of_the_generated_tree(self):
        for relative, data in self.layered_tree.items():
            if not relative.endswith(".kt") or relative == h2.CARD_PATH:
                continue
            text = data.decode("utf-8")
            for name in NAMES:
                self.assertNotIn(name, text, (relative, name))

    def test_the_card_side_declares_the_tag_the_render_test_reads(self):
        # The render test writes the tag out as a literal, so that the same file also runs against the card before this slice,
        # where the constant does not exist yet; the declaration and that literal must stay the same string.
        wrapper = h2.source.read(h2.cm.MENU_KT)
        self.assertEqual(1, wrapper.count('internal const val DESKTOP_CARD_MENU_SEPARATOR_TAG = "desktop_card_menu_separator"\n'))
        test = h2.source.read(h2.source.KT / "ui/DesktopHomeCardMenuSeparatorTest.kt")
        self.assertEqual(1, test.count('const val SEPARATOR_TAG = "desktop_card_menu_separator"\n'))


if __name__ == "__main__":
    unittest.main()
