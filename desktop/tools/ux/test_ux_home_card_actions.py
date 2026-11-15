"""Slice K3i generator checks: the Home card action edits (ACTION_EDITS of desktop/tools/ux_home_card.py) on the generated VideoCard
text. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_card_actions.py

What is proved. The real Home card producer (extract-upstream-home-full-card.py) is run with the layer and with the layer patched out:

  ActionEditsTest    the two edits are the ones written out below, last of the layer, after the H2, K3, K3b, K3d, K3e and K3f
                     edits; each anchor occurs exactly once in the text the earlier edits leave; those written-out edits applied to
                     that text give the produced card byte for byte; undoing only them gives the earlier text back byte for byte, and
                     the whole layer reverses to the vendored card; the audit rows carry their hashes; the menu has two rows more
  ActionRefusalTest  a mutated or doubled K3i anchor and a taken K3i name fail closed with AssertionError, naming the edit or the
                     name, before the audit row of that edit is written
  ActionWiringTest   the menu order is 稍后再看 / 取消收藏 / 收藏 / 小窗播放 / 复制链接 / separator / 不感兴趣, at most seven entries; each
                     new row is drawn only when its action exists, closes the menu once and then calls only its own action; both
                     actions read the one Root port through the flag's ON branch (null while OFF); the separator guard stays exact
                     because the actions exist only when the copy link does

What is not proved. What the rows do: DesktopHomeCardActionsTest (ImageComposeScene) opens the real generated card menu with and
without the port, DesktopHomeCardActionsRootTest and DesktopHomeCardMiniIntentTest drive the favourite drawer and the mini request.
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

LABELS = ["ux-card-home-actions-state", "ux-card-home-actions-menu-rows"]
STATE = ("    val homeCardActionsPort = com.bilipai.desktop.ui.LocalDesktopHomeCardActions.current\n"
         "    val homeCardActions = if (homeCardThreeLine) homeCardActionsPort else null\n"
         "    val favoriteHomeCard: (() -> Unit)? = com.bilipai.desktop.ui.desktopHomeCardFavorite(homeCardActions, video)\n"
         "    val miniHomeCard: (() -> Unit)? = com.bilipai.desktop.ui.desktopHomeCardMini(homeCardActions, video)\n")
COPY_ACTION_END = ("        { com.bilipai.desktop.ui.desktopHomeCopyVideoLink(homeCardClipboard, homeCardCopyLink) }\n"
                   "    }\n")
COPY_COMMENT = "                // 复制链接 (Ctrl C)\n"
FAVORITE_COMMENT = "                // 收藏 (原云端收藏夹抽屉, K3i)\n"
MINI_COMMENT = "                // 小窗播放 (现有播放器小窗, K3i)\n"


def row(comment, guard, label, action):
    return ("\n" + comment
            + "                if (%s != null) {\n" % guard
            + "                    AppDropdownMenuItem(\n"
              "                        text = {\n"
              "                            AppText(\n"
              '                                "%s",\n' % label
            + "                                color = MaterialTheme.colorScheme.onSurface\n"
              "                            )\n"
              "                        },\n"
              "                        onClick = {\n"
              "                            showDismissMenu = false\n"
              "                            %s?.invoke()\n" % action
            + "                        }\n"
              "                    )\n"
              "                }\n")


# The two edits, written out here and not read from the layer.
EXPECTED = (
    ("ux-card-home-actions-state", COPY_ACTION_END, COPY_ACTION_END + STATE),
    ("ux-card-home-actions-menu-rows",
     "\n" + COPY_COMMENT,
     row(FAVORITE_COMMENT, "favoriteHomeCard", "收藏", "favoriteHomeCard")
     + row(MINI_COMMENT, "miniHomeCard", "小窗播放", "miniHomeCard")
     + "\n" + COPY_COMMENT),
)
NAMES = ("LocalDesktopHomeCardActions", "desktopHomeCardFavorite", "desktopHomeCardMini", "homeCardActions", "favoriteHomeCard",
         "miniHomeCard")
WATCH_LATER = "                // 稍后再看\n"
UNFAVORITE = "// 取消收藏 (仅在收藏页显示)"
SEPARATOR_COMMENT = "                // 分隔线：把常用操作和「不感兴趣」分开 (K3f)\n"
DISMISS_COMMENT = "                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n"
SEPARATOR_GUARD = ("                if (onDismiss != null && (onWatchLater != null || onUnfavorite != null "
                   "|| copyHomeCardLink != null)) {\n")


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


def only_the_action_edits():
    """The layer with only its K3i edits, for a text the earlier edits have already changed: the refusal of a K3i anchor is then the
    refusal of that edit and not of an earlier one whose anchor overlaps it (the copy row of K3e ends at the same lines)."""
    return mock.patch.multiple(layer, ALL_EDITS=layer.ACTION_EDITS, INTRODUCED=(), COVER_INTRODUCED=(), LAYOUT_INTRODUCED=(),
                               HOVER_INTRODUCED=(), COPY_INTRODUCED=(), SEPARATOR_INTRODUCED=(), GONE=())


class _Generated(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-uxk3i-")
        root = Path(cls.temp.name)
        producer = h2.load_producer(h2.PRODUCER)
        h2.generate_quietly(producer, root / "a")
        with mock.patch.object(layer, "apply_home_card", lambda repo, name, text, audits: text):
            h2.generate_quietly(producer, root / "p")
        cls.layered_tree, cls.plain_tree = h2.tree(root / "a"), h2.tree(root / "p")
        cls.layered_bytes, cls.plain_bytes = cls.layered_tree[h2.CARD_PATH], cls.plain_tree[h2.CARD_PATH]
        cls.layered, cls.plain = cls.layered_bytes.decode("utf-8"), cls.plain_bytes.decode("utf-8")
        # The text the K3i edits receive: the H2, K3, K3b, K3d, K3e and K3f edits applied, nothing else.
        cls.received = apply_edits(cls.plain, layer.EDITS + layer.COVER_EDITS + layer.LAYOUT_EDITS + layer.HOVER_EDITS
                                   + layer.COPY_EDITS + layer.SEPARATOR_EDITS)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def menu(self, text):
        start = text.index("            AppDropdownMenu(\n")
        return text[start:text.index(DISMISS_COMMENT, start)]


class ActionEditsTest(_Generated):
    def test_the_edits_are_the_last_ones_of_the_layer(self):
        self.assertEqual(LABELS, [label for label, _b, _a in layer.ACTION_EDITS])
        self.assertEqual(EXPECTED, layer.ACTION_EDITS)
        self.assertEqual(layer.EDITS + layer.COVER_EDITS + layer.LAYOUT_EDITS + layer.HOVER_EDITS + layer.COPY_EDITS
                         + layer.SEPARATOR_EDITS + layer.ACTION_EDITS, layer.ALL_EDITS)
        self.assertEqual(LABELS, [label for label, _b, _a in layer.ALL_EDITS[-2:]])
        self.assertEqual(NAMES, layer.ACTION_INTRODUCED)
        for label, before, after in EXPECTED:
            self.assertNotEqual(before, after, label)
            self.assertTrue(after.startswith(before) or after.endswith(before), label)
            self.assertTrue(before.endswith("\n") and after.endswith("\n"), label)

    def test_every_anchor_occurs_exactly_once_in_the_text_the_edits_receive(self):
        text = self.received
        for label, before, after in EXPECTED:
            self.assertEqual(1, text.count(before), label)
            self.assertEqual(0, text.count(after), label)
            text = text.replace(before, after, 1)
            self.assertEqual(1, self.layered.count(after), label)

    def test_forward_is_the_written_out_edits_byte_for_byte(self):
        self.assertEqual(self.layered_bytes, apply_edits(self.received, EXPECTED).encode("utf-8"))
        audits = []
        produced = layer.apply_home_card(REPO, layer.CARD, self.plain, audits)
        self.assertEqual(self.layered_bytes, produced.encode("utf-8"))

    def test_inverse_gives_back_the_earlier_text_and_the_vendored_card_byte_for_byte(self):
        # Undoing only the K3i edits gives back the text of H2, K3, K3b, K3d, K3e and K3f exactly; the whole layer reverses to the
        # vendored card.
        self.assertEqual(self.received.encode("utf-8"), undo_edits(self.layered, EXPECTED).encode("utf-8"))
        self.assertEqual(self.plain_bytes, layer.reverse(layer.CARD, self.layered).encode("utf-8"))

    def test_the_layer_changes_only_the_card_and_the_inventory(self):
        self.assertEqual(set(self.plain_tree), set(self.layered_tree))
        changed = {name for name in self.layered_tree if self.layered_tree[name] != self.plain_tree[name]}
        self.assertEqual({h2.CARD_PATH, h2.INVENTORY_PATH}, changed)

    def test_the_last_audit_rows_are_the_action_edits(self):
        audits = []
        layer.apply_home_card(REPO, layer.CARD, self.plain, audits)
        self.assertEqual(LABELS, [entry["id"] for entry in audits[-2:]])
        for (label, before, after), entry in zip(EXPECTED, audits[-2:]):
            self.assertEqual(hashlib.sha256(before.encode("utf-8")).hexdigest(), entry["beforeSha256"], label)
            self.assertEqual(hashlib.sha256(after.encode("utf-8")).hexdigest(), entry["afterSha256"], label)
            self.assertIs(True, entry["inverseRestoresInput"], label)

    def test_the_menu_gets_two_rows_and_keeps_the_others(self):
        self.assertEqual(self.received.count("AppDropdownMenuItem(") + 2, self.layered.count("AppDropdownMenuItem("))
        for kept in (WATCH_LATER, UNFAVORITE, COPY_COMMENT, SEPARATOR_COMMENT, DISMISS_COMMENT):
            self.assertEqual(1, self.received.count(kept), kept)
            self.assertEqual(1, self.layered.count(kept), kept)

    def test_the_brackets_balance_as_before(self):
        for pair in ("()", "{}", "[]"):
            counts = []
            for text in (self.received, self.layered):
                code = h2.source.mask_kotlin(text)
                counts.append(code.count(pair[0]) - code.count(pair[1]))
            self.assertEqual(counts[0], counts[1], pair)


class ActionRefusalTest(_Generated):
    def test_the_action_only_layer_reproduces_the_card(self):
        # The control of the refusals below: with only the K3i edits, the layer turns the received text into the produced card.
        audits = []
        with only_the_action_edits():
            self.assertEqual(self.layered, layer.apply_home_card(REPO, layer.CARD, self.received, audits))
        self.assertEqual(LABELS, [entry["id"] for entry in audits])

    def test_a_mutated_anchor_fails_closed(self):
        for label, before, _after in EXPECTED:
            # Insert a character after the first one that is not a space or a line break: the anchor then no longer occurs.
            start = len(before) - len(before.lstrip(" \n")) + 1
            mutated = before[:start] + "X" + before[start:]
            self.assertEqual(0, self.received.count(mutated), label)
            audits = []
            with only_the_action_edits(), self.assertRaisesRegex(AssertionError,
                                                                 "anchor count changed: %s \\(0\\)" % re.escape(label)):
                layer.apply_home_card(REPO, layer.CARD, self.received.replace(before, mutated, 1), audits)
            self.assertNotIn(label, [entry["id"] for entry in audits], label)
            # The full layer refuses the mutated vendored card as well.
            if self.plain.count(before) == 1:
                with self.assertRaises(AssertionError, msg=label):
                    layer.apply_home_card(REPO, layer.CARD, self.plain.replace(before, mutated, 1), [])

    def test_a_doubled_anchor_fails_closed(self):
        for label, before, _after in EXPECTED:
            audits = []
            with only_the_action_edits(), self.assertRaisesRegex(AssertionError,
                                                                 "anchor count changed: %s \\(2\\)" % re.escape(label)):
                layer.apply_home_card(REPO, layer.CARD, self.received + "\n" + before, audits)
            self.assertNotIn(label, [entry["id"] for entry in audits], label)
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


class ActionWiringTest(_Generated):
    def test_the_menu_order_is_the_one_of_the_card(self):
        menu = self.menu(self.layered)
        order = [WATCH_LATER, UNFAVORITE, FAVORITE_COMMENT, MINI_COMMENT, COPY_COMMENT, SEPARATOR_COMMENT]
        positions = [menu.index(marker) for marker in order]
        self.assertEqual(sorted(positions), positions)
        # Five rows and the line above 不感兴趣, then 不感兴趣 itself: seven entries at most.
        self.assertEqual(5, menu.count("AppDropdownMenuItem("))
        self.assertEqual(1, menu.count("DesktopCardMenuSeparator()"))
        after = self.layered[self.layered.index(DISMISS_COMMENT):]
        self.assertEqual(1, after[:after.index("\n            }\n")].count("AppDropdownMenuItem("))

    def test_each_row_closes_the_menu_once_and_calls_only_its_own_action(self):
        for comment, label, action, other in ((FAVORITE_COMMENT, "收藏", "favoriteHomeCard", "miniHomeCard"),
                                              (MINI_COMMENT, "小窗播放", "miniHomeCard", "favoriteHomeCard")):
            start = self.layered.index(comment)
            block = self.layered[start:self.layered.index("\n\n", start)]
            self.assertTrue(block.startswith(comment + "                if (%s != null) {\n" % action), label)
            self.assertEqual(1, block.count('"%s",\n' % label), label)
            self.assertEqual(1, block.count("showDismissMenu = false\n"), label)
            self.assertEqual(1, block.count("%s?.invoke()\n" % action), label)
            self.assertLess(block.index("showDismissMenu = false\n"), block.index("%s?.invoke()\n" % action), label)
            for callback in (other, "onDismiss", "onWatchLater", "onUnfavorite", "triggerCardClick", "onClick = { triggerCardClick()",
                             "copyHomeCardLink"):
                self.assertNotIn(callback, block, (label, callback))

    def test_the_actions_read_the_one_port_through_the_flag(self):
        self.assertEqual(1, self.layered.count(STATE))
        self.assertEqual(1, self.layered.count("LocalDesktopHomeCardActions.current"))
        # The flag is still read once (slice K3b): the port is read through the same local, and null while it is off.
        self.assertEqual(1, self.layered.count("desktopCardInteractionsOn()"))
        self.assertEqual(1, self.layered.count("    val homeCardActions = if (homeCardThreeLine) homeCardActionsPort else null\n"))
        self.assertEqual(1, self.layered.count("desktopHomeCardFavorite("))
        self.assertEqual(1, self.layered.count("desktopHomeCardMini("))
        # The state comes right after the copy action of K3e, which the K3f separator guard names.
        at = self.layered.index(STATE)
        self.assertEqual(at, self.layered.index(COPY_ACTION_END) + len(COPY_ACTION_END))
        self.assertLess(self.layered.index("    val homeCardThreeLine"), at)

    def test_the_separator_guard_stays_exact(self):
        # Both actions exist only when the card is a three line card with a video link, which is when copyHomeCardLink exists, so
        # the guard of K3f still names every row that can be above the line and is not widened.
        self.assertEqual(1, self.layered.count(SEPARATOR_GUARD))
        self.assertEqual(self.received.count(SEPARATOR_GUARD), self.layered.count(SEPARATOR_GUARD))
        self.assertIn("    val homeCardCopyLink = if (homeCardThreeLine) {\n        com.bilipai.desktop.ui.desktopHomeVideoLink(video.bvid)\n",
                      self.layered)

    def test_the_new_names_occur_only_in_the_card_of_the_generated_tree(self):
        for relative, data in self.layered_tree.items():
            if not relative.endswith(".kt") or relative == h2.CARD_PATH:
                continue
            text = data.decode("utf-8")
            for name in NAMES:
                self.assertNotIn(name, text, (relative, name))


if __name__ == "__main__":
    unittest.main()
