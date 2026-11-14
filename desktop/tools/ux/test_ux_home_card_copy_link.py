"""Slice K3e generator checks: the copy link edits (COPY_EDITS of desktop/tools/ux_home_card.py) on the generated VideoCard text.
No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_card_copy_link.py

What is proved. The real Home card producer (extract-upstream-home-full-card.py) is run with the layer and with the layer patched out:

  CopyEditsTest     the three edits are the ones written out below, in order, after the H2, K3, K3b and K3d edits; each anchor
                    occurs exactly once in the text the earlier edits leave; those three written-out edits applied to that text give
                    the produced card byte for byte; undoing only them gives the H2 + K3 + K3b + K3d text back byte for byte, and the
                    whole layer reverses to the vendored card; the audit rows carry their hashes
  CopyRefusalTest   a mutated or doubled K3e anchor and a taken K3e name fail closed with AssertionError, naming the edit or the
                    name, before any audit row is written
  CopyWiringTest    the row sits in the existing menu after the vendored rows and before 不感兴趣, closes the menu and writes the link
                    through the one clipboard read of the card; the link is the flag's ON branch and null while OFF; the row is drawn
                    only when the card has a link; the copy action calls only declared parameters and neither plays nor dismisses
  CopyFrameTest     the four pieces of DesktopHomeCardFrame.kt: Ctrl+C is one more key action of the focused cover, read before the
                    unmodified Ctrl guard; the copy action is a parameter of the focus target; link and write are the two helpers below
                    with one clipboard.write call site

What is not proved. How the card renders and reacts: DesktopHomeCardCopyLinkTest (ImageComposeScene) clicks the row and presses
Ctrl+C on the real generated card and counts the writes of a recording clipboard boundary.
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
import ux_source as source  # noqa: E402

layer = h2.layer
REPO = h2.REPO
FRAME_KT = source.K / "ui/DesktopHomeCardFrame.kt"

LABELS = ["ux-card-copy-link-state", "ux-card-copy-link-key", "ux-card-copy-link-menu-row"]
# The three edits, written out here and not read from the layer.
EXPECTED = (
    ("ux-card-copy-link-state",
     "    val homeCardHover = com.bilipai.desktop.ui.rememberDesktopHomeCardHover()\n",
     "    val homeCardHover = com.bilipai.desktop.ui.rememberDesktopHomeCardHover()\n"
     "    val homeCardClipboard = com.bilipai.desktop.appearance.LocalDesktopTextClipboard.current\n"
     "    val homeCardCopyLink = if (homeCardThreeLine) {\n"
     "        com.bilipai.desktop.ui.desktopHomeVideoLink(video.bvid)\n"
     "    } else {\n"
     "        null\n"
     "    }\n"
     "    val copyHomeCardLink: (() -> Boolean)? = if (homeCardCopyLink == null) {\n"
     "        null\n"
     "    } else {\n"
     "        { com.bilipai.desktop.ui.desktopHomeCopyVideoLink(homeCardClipboard, homeCardCopyLink) }\n"
     "    }\n"),
    ("ux-card-copy-link-key",
     "                    onOpen = { triggerCardClick() },\n"
     "                    onMenu = if (hasOverflowMenu) {\n",
     "                    onOpen = { triggerCardClick() },\n"
     "                    onCopy = copyHomeCardLink,\n"
     "                    onMenu = if (hasOverflowMenu) {\n"),
    ("ux-card-copy-link-menu-row",
     "\n                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n",
     "\n                // 复制链接 (Ctrl C)\n"
     "                if (copyHomeCardLink != null) {\n"
     "                    AppDropdownMenuItem(\n"
     "                        text = {\n"
     "                            AppText(\n"
     "                                \"复制链接（Ctrl C）\",\n"
     "                                color = MaterialTheme.colorScheme.onSurface\n"
     "                            )\n"
     "                        },\n"
     "                        onClick = {\n"
     "                            showDismissMenu = false\n"
     "                            copyHomeCardLink?.invoke()\n"
     "                        }\n"
     "                    )\n"
     "                }\n"
     "\n                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n"),
)
NAMES = ("desktopHomeVideoLink", "desktopHomeCopyVideoLink", "homeCardClipboard", "homeCardCopyLink", "copyHomeCardLink")
LABEL = "复制链接（Ctrl C）"


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


def only_the_copy_edits():
    """The layer with only its K3e edits, for a text the earlier edits have already changed: the refusal of a K3e anchor is then
    the refusal of that edit and not of an earlier one whose anchor overlaps it."""
    return mock.patch.multiple(layer, ALL_EDITS=layer.COPY_EDITS, INTRODUCED=(), COVER_INTRODUCED=(), LAYOUT_INTRODUCED=(),
                               HOVER_INTRODUCED=(), SEPARATOR_INTRODUCED=(), GONE=())


class _Generated(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-uxk3e-")
        root = Path(cls.temp.name)
        producer = h2.load_producer(h2.PRODUCER)
        h2.generate_quietly(producer, root / "a")
        with mock.patch.object(layer, "apply_home_card", lambda repo, name, text, audits: text):
            h2.generate_quietly(producer, root / "p")
        cls.layered_tree, cls.plain_tree = h2.tree(root / "a"), h2.tree(root / "p")
        cls.layered_bytes, cls.plain_bytes = cls.layered_tree[h2.CARD_PATH], cls.plain_tree[h2.CARD_PATH]
        cls.layered, cls.plain = cls.layered_bytes.decode("utf-8"), cls.plain_bytes.decode("utf-8")
        # The text the K3e edits receive: the H2, K3, K3b and K3d edits applied, nothing else.
        cls.received = apply_edits(cls.plain, layer.EDITS + layer.COVER_EDITS + layer.LAYOUT_EDITS + layer.HOVER_EDITS)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()


class CopyEditsTest(_Generated):
    def test_the_edits_are_the_three_of_the_slice_after_the_earlier_ones(self):
        self.assertEqual(LABELS, [label for label, _b, _a in layer.COPY_EDITS])
        self.assertEqual(EXPECTED, layer.COPY_EDITS)
        self.assertEqual(layer.EDITS + layer.COVER_EDITS + layer.LAYOUT_EDITS + layer.HOVER_EDITS + layer.COPY_EDITS
                         + layer.SEPARATOR_EDITS,
                         layer.ALL_EDITS)
        # K3f appends its one separator edit after these three, so the copy rows are the three before the last one.
        self.assertEqual(LABELS, [label for label, _b, _a in layer.ALL_EDITS[-4:-1]])
        self.assertEqual(NAMES, layer.COPY_INTRODUCED)
        for label, before, after in EXPECTED:
            self.assertNotEqual(before, after, label)
            self.assertTrue(before.endswith("\n") and after.endswith("\n"), label)

    def test_every_anchor_occurs_exactly_once_in_the_text_the_edits_receive(self):
        # The produced card has the K3f separator edit on top, and that edit rewrites the line the last K3e edit ends with, so
        # the replacement is counted in the text the K3f edit leaves.
        settled = undo_edits(self.layered, layer.SEPARATOR_EDITS)
        text = self.received
        for label, before, after in EXPECTED:
            self.assertEqual(1, text.count(before), label)
            self.assertEqual(0, text.count(after), label)
            text = text.replace(before, after, 1)
            self.assertEqual(1, settled.count(after), label)

    def test_forward_is_the_written_out_edits_byte_for_byte(self):
        self.assertEqual(undo_edits(self.layered, layer.SEPARATOR_EDITS).encode("utf-8"),
                         apply_edits(self.received, EXPECTED).encode("utf-8"))
        audits = []
        produced = layer.apply_home_card(REPO, layer.CARD, self.plain, audits)
        self.assertEqual(self.layered_bytes, produced.encode("utf-8"))

    def test_inverse_gives_back_the_earlier_text_and_the_vendored_card_byte_for_byte(self):
        # Undoing the K3f separator edit and then only the K3e edits, last first, gives back the text of H2, K3, K3b and K3d
        # exactly; the whole layer reverses to the vendored card.
        self.assertEqual(self.received.encode("utf-8"),
                         undo_edits(undo_edits(self.layered, layer.SEPARATOR_EDITS), EXPECTED).encode("utf-8"))
        self.assertEqual(self.plain_bytes, layer.reverse(layer.CARD, self.layered).encode("utf-8"))

    def test_the_layer_changes_only_the_card_and_the_inventory(self):
        self.assertEqual(set(self.plain_tree), set(self.layered_tree))
        changed = {name for name in self.layered_tree if self.layered_tree[name] != self.plain_tree[name]}
        self.assertEqual({h2.CARD_PATH, h2.INVENTORY_PATH}, changed)

    def test_the_last_three_audit_rows_are_the_copy_edits(self):
        audits = []
        layer.apply_home_card(REPO, layer.CARD, self.plain, audits)
        # K3f appends one row after these three, so the copy rows are the three before the last one.
        self.assertEqual(LABELS, [row["id"] for row in audits[-4:-1]])
        for (label, before, after), row in zip(EXPECTED, audits[-4:-1]):
            self.assertEqual(hashlib.sha256(before.encode("utf-8")).hexdigest(), row["beforeSha256"], label)
            self.assertEqual(hashlib.sha256(after.encode("utf-8")).hexdigest(), row["afterSha256"], label)
            self.assertIs(True, row["inverseRestoresInput"], label)

    def test_the_brackets_balance_as_before(self):
        for pair in ("()", "{}", "[]"):
            counts = []
            for text in (self.received, self.layered):
                code = source.mask_kotlin(text)
                counts.append(code.count(pair[0]) - code.count(pair[1]))
            self.assertEqual(counts[0], counts[1], pair)


class CopyRefusalTest(_Generated):
    def test_the_copy_only_layer_reproduces_the_card(self):
        # The control of the refusals below: with only the K3e edits, the layer turns the received text into the produced card
        # without the K3f separator edit that sits on top of it.
        audits = []
        with only_the_copy_edits():
            self.assertEqual(undo_edits(self.layered, layer.SEPARATOR_EDITS),
                             layer.apply_home_card(REPO, layer.CARD, self.received, audits))
        self.assertEqual(LABELS, [row["id"] for row in audits])

    def test_a_mutated_anchor_fails_closed(self):
        for label, before, _after in EXPECTED:
            # Insert a character after the first one that is not a space: the anchor then no longer occurs in the received text.
            start = len(before) - len(before.lstrip(" ")) + 1
            mutated = before[:start] + "X" + before[start:]
            self.assertEqual(0, self.received.count(mutated), label)
            audits = []
            with only_the_copy_edits(), self.assertRaisesRegex(AssertionError,
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
            with only_the_copy_edits(), self.assertRaisesRegex(AssertionError,
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


class CopyWiringTest(_Generated):
    def test_the_row_is_the_last_vendored_row_of_the_existing_menu(self):
        # One new row, inside the menu the card already has: after the vendored rows and before 不感兴趣.
        comment = "                // 复制链接 (Ctrl C)\n"
        # K3f draws its separator between this row and 不感兴趣, so the row is read from the text the K3f edit leaves.
        settled = undo_edits(self.layered, layer.SEPARATOR_EDITS)
        self.assertEqual(0, self.received.count(comment))
        self.assertEqual(1, settled.count(comment))
        self.assertEqual(1, settled.count('                                "%s",\n' % LABEL))
        self.assertLess(settled.index("                // 稍后再看\n"), settled.index(comment))
        self.assertLess(settled.index("// 取消收藏 (仅在收藏页显示)"), settled.index(comment))
        self.assertLess(settled.index(comment), settled.index("                // 不感兴趣 (放第一位，方便操作) -> 改回下方\n"))
        # The row closes the menu first, as every other row does, and then copies: no other callback of the card is called.
        row = settled[settled.index(comment):settled.index("                // 不感兴趣")]
        self.assertEqual(1, row.count("showDismissMenu = false\n"))
        self.assertEqual(1, row.count("copyHomeCardLink?.invoke()\n"))
        self.assertLess(row.index("showDismissMenu = false\n"), row.index("copyHomeCardLink?.invoke()\n"))
        for callback in ("onDismiss", "onWatchLater", "onUnfavorite", "triggerCardClick", "onClick = { triggerCardClick()"):
            self.assertNotIn(callback, row, callback)
        # The row is drawn only when the card has a link.
        self.assertEqual(1, row.count("if (copyHomeCardLink != null) {\n"))

    def test_the_link_is_the_flag_on_branch_and_null_while_off(self):
        state = ("    val homeCardClipboard = com.bilipai.desktop.appearance.LocalDesktopTextClipboard.current\n"
                 "    val homeCardCopyLink = if (homeCardThreeLine) {\n"
                 "        com.bilipai.desktop.ui.desktopHomeVideoLink(video.bvid)\n"
                 "    } else {\n"
                 "        null\n"
                 "    }\n")
        self.assertEqual(1, self.layered.count(state))
        # One clipboard read for the whole card, and no second read anywhere in it.
        self.assertEqual(1, self.layered.count("com.bilipai.desktop.appearance.LocalDesktopTextClipboard.current"))
        self.assertEqual(1, self.layered.count("LocalDesktopTextClipboard"))
        self.assertEqual(1, self.layered.count("homeCardThreeLine) {\n        com.bilipai.desktop.ui.desktopHomeVideoLink(video.bvid)\n"))
        # The flag is still read once (slice K3b): the copy state reads the same local.
        self.assertEqual(1, self.layered.count("desktopCardInteractionsOn()"))
        # The state sits next to the hover state of K3d, after the card's own locals.
        at = self.layered.index(state)
        self.assertLess(self.layered.index("    val homeCardHover = "), at)
        for name in ("    val hasOverflowMenu = ", "    val openDismissMenu: ", "    val cardCornerRadius = ",
                     "    val homeCoverFocus = "):
            self.assertLess(self.layered.index(name), at, name)

    def test_the_copy_action_is_one_lambda_of_the_link_and_nothing_else(self):
        action = ("    val copyHomeCardLink: (() -> Boolean)? = if (homeCardCopyLink == null) {\n"
                  "        null\n"
                  "    } else {\n"
                  "        { com.bilipai.desktop.ui.desktopHomeCopyVideoLink(homeCardClipboard, homeCardCopyLink) }\n"
                  "    }\n")
        self.assertEqual(1, self.layered.count(action))
        self.assertEqual(0, self.received.count(action))
        # The single write site of the card is the call of the helper, and it is given the read clipboard and the link.
        self.assertEqual(1, self.layered.count("desktopHomeCopyVideoLink("))
        self.assertEqual(0, self.received.count("desktopHomeCopyVideoLink("))

    def test_the_focus_target_gets_the_copy_action_of_the_cover(self):
        call = re.search(r"\.desktopHomeCoverFocusTarget\(\n(?:.*\n)*?( *)\.aspectRatio\(", self.layered)
        self.assertIsNotNone(call)
        named = [line.strip().split("=", 1)[0].strip() for line in call.group(0).splitlines() if " = " in line]
        self.assertEqual(["state", "cornerRadius", "onOpen", "onCopy", "onMenu"], named)
        self.assertEqual(1, self.layered.count("                    onCopy = copyHomeCardLink,\n"))

    def test_the_new_names_occur_only_in_the_card_of_the_generated_tree(self):
        for relative, data in self.layered_tree.items():
            if not relative.endswith(".kt") or relative == h2.CARD_PATH:
                continue
            text = data.decode("utf-8")
            for name in NAMES:
                self.assertNotIn(name, text, (relative, name))


class CopyFrameTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(FRAME_KT)
        cls.code = source.mask_kotlin(cls.text)

    def test_ctrl_c_is_one_more_key_action_read_before_the_unmodified_ctrl_guard(self):
        self.assertEqual(1, self.code.count("internal enum class DesktopHomeCoverKeyAction { OPEN, MENU, COPY }"))
        copy = ("    if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&\n"
                "        !event.isShiftPressed && event.key == Key.C\n"
                "    ) return DesktopHomeCoverKeyAction.COPY\n")
        guard = ("    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) "
                 "return null\n")
        self.assertEqual(1, self.code.count(copy))
        self.assertEqual(1, self.code.count(guard))
        self.assertLess(self.code.index(copy), self.code.index(guard))
        # The action switches once, and the copy branch passes the press on when the card has no link.
        self.assertEqual(1, self.code.count("when (desktopHomeCoverKeyAction(event)) {"))
        self.assertEqual(1, self.code.count("DesktopHomeCoverKeyAction.COPY -> copy?.let { it(); true } ?: false\n"))

    def test_the_copy_action_is_a_parameter_of_the_focus_target(self):
        start = self.code.index("internal fun Modifier.desktopHomeCoverFocusTarget(")
        declaration = self.code[start:self.code.index("): Modifier {", start)]
        self.assertEqual(["state", "cornerRadius", "onOpen", "onMenu", "onCopy"],
                         [re.sub(r"^val\s+|^var\s+", "", line.strip().split(":", 1)[0]) for line in declaration.splitlines()[1:]
                          if ":" in line])
        self.assertEqual(1, self.code.count("    onCopy: (() -> Boolean)?,\n"))
        self.assertEqual(1, self.code.count("    val copy by rememberUpdatedState(onCopy)\n"))
        # The focus target itself is still mounted only while the flag is on. The same guard opens the
        # ring and the hover surface, so it is counted inside this one declaration instead of the file.
        body = self.code[start:self.code.index("internal fun Modifier.desktopHomeCoverFocusRing(", start)]
        self.assertEqual(1, body.count("    if (!desktopCardInteractionsOn()) return this\n"))

    def test_the_link_and_the_write_are_the_two_helpers_and_one_write_site(self):
        # The prefix and the id pattern carry string literals, and the mask that keeps the Kotlin
        # syntax readable drops literals, so those two are read from the raw frame instead.
        for declaration in ('internal const val DESKTOP_HOME_VIDEO_LINK_PREFIX = "https://www.bilibili.com/video/"',
                            'private val DESKTOP_HOME_BVID = Regex("^BV[0-9A-Za-z]{10}$")'):
            self.assertEqual(1, self.text.count(declaration), declaration)
        for declaration in ("internal fun desktopHomeVideoLink(bvid: String?): String? {",
                            "internal fun desktopHomeCopyVideoLink(clipboard: DesktopTextClipboard, link: String?): Boolean ="):
            self.assertEqual(1, self.code.count(declaration), declaration)
        self.assertEqual(1, self.code.count("    val id = bvid?.trim().orEmpty()\n"))
        self.assertEqual(1, self.code.count("    return if (DESKTOP_HOME_BVID.matches(id)) DESKTOP_HOME_VIDEO_LINK_PREFIX + id "
                                            "else null\n"))
        self.assertEqual(1, self.code.count("    link != null && clipboard.copyText(link)\n"))
        # One write, through the existing boundary; the frame calls nothing else of the clipboard.
        self.assertEqual(1, self.code.count("copyText("))
        self.assertEqual(0, self.code.count("已复制"))
        self.assertEqual(1, self.code.count("import com.bilipai.desktop.appearance.DesktopTextClipboard\n"))


if __name__ == "__main__":
    unittest.main()
