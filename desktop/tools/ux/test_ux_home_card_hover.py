"""Slice K3d generator checks: the hover and quick action edits (HOVER_EDITS of desktop/tools/ux_home_card.py) on the generated VideoCard
text. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_card_hover.py

What is proved. The real Home card producer (extract-upstream-home-full-card.py) is run with the layer and with the layer patched out:

  HoverEditsTest     the four edits are the ones written out below, in order, after the H2, K3 and K3b edits; each anchor occurs exactly
                     once in the text the earlier edits leave; those four written-out edits applied to that text give the produced card
                     byte for byte; undoing only them gives the H2 + K3 + K3b text back byte for byte, and the whole layer reverses to
                     the vendored card; the audit rows carry their hashes
  HoverRefusalTest   a mutated or doubled K3d anchor and a taken K3d name fail closed with AssertionError, naming the edit or the name,
                     before any audit row is written
  HoverWiringTest    the flag switch keeps the vendored hover expression as its OFF branch; the shared state is declared once and read
                     by the surface and the quick actions; the quick actions sit in the cover box, call only declared parameters, reuse
                     the card's watch later callback and open the card menu only where the card has one; the labels are the contract's
  HoverFrameTest     the three entry points in DesktopHomeCardFrame.kt, each returning at once while the flag is off

What is not proved. How the card renders and reacts: DesktopHomeCardHoverTest (ImageComposeScene) moves the pointer in and out of the real
generated card, samples the wash and its 8 dp outset, measures the two 32 dp targets, clicks them and walks them with the keyboard.
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
import ux_home_card_model as cm  # noqa: E402
import ux_source as source  # noqa: E402

layer = h2.layer
REPO = h2.REPO
FRAME_KT = source.K / "ui/DesktopHomeCardFrame.kt"

LABELS = ["ux-card-hover-imports", "ux-card-hover-state", "ux-card-hover-surface", "ux-card-hover-quick-actions"]
# The four edits, written out here and not read from the layer.
EXPECTED = (
    ("ux-card-hover-imports",
     "import com.bilipai.desktop.ui.desktopHomeCoverFocusTarget\n",
     "import com.bilipai.desktop.ui.desktopHomeCoverFocusTarget\n"
     "import com.bilipai.desktop.ui.desktopHomeCardHoverSurface\n"),
    ("ux-card-hover-state",
     "    val homeCoverFocus = com.bilipai.desktop.ui.rememberDesktopHomeCoverFocus()\n",
     "    val homeCoverFocus = com.bilipai.desktop.ui.rememberDesktopHomeCoverFocus()\n"
     "    val homeCardHover = com.bilipai.desktop.ui.rememberDesktopHomeCardHover()\n"),
    ("ux-card-hover-surface",
     "            .adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n",
     "            .then(\n"
     "                if (homeCardThreeLine) {\n"
     "                    Modifier.desktopHomeCardHoverSurface(homeCardHover, cornerRadius = cardCornerRadius)\n"
     "                } else {\n"
     "                    Modifier.adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n"
     "                }\n"
     "            )\n"),
    ("ux-card-hover-quick-actions",
     "            }\n"
     "            }\n"
     "\n"
     "        }\n"
     "        \n"
     "        val infoSurfaceShape = remember(cardCornerRadius) {\n",
     "            }\n"
     "            }\n"
     "            com.bilipai.desktop.ui.DesktopHomeCardQuickActions(\n"
     "                state = homeCardHover,\n"
     "                coverFocus = homeCoverFocus,\n"
     "                onWatchLater = onWatchLater,\n"
     "                onMore = if (hasOverflowMenu) {\n"
     "                    { anchor -> openDismissMenu(anchor, null) }\n"
     "                } else {\n"
     "                    null\n"
     "                },\n"
     "                watchLaterLabel = \"稍后再看\",\n"
     "                moreLabel = \"更多\",\n"
     "                modifier = Modifier.align(Alignment.TopEnd),\n"
     "            )\n"
     "\n"
     "        }\n"
     "        \n"
     "        val infoSurfaceShape = remember(cardCornerRadius) {\n"),
)
NAMES = ("desktopHomeCardHoverSurface", "rememberDesktopHomeCardHover", "homeCardHover", "DesktopHomeCardQuickActions")


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


def only_the_hover_edits():
    """The layer with only its K3d edits, for a text the earlier edits have already changed: the refusal of a K3d anchor is then
    the refusal of that edit and not of an earlier one whose anchor overlaps it."""
    return mock.patch.multiple(layer, ALL_EDITS=layer.HOVER_EDITS, INTRODUCED=(), COVER_INTRODUCED=(), LAYOUT_INTRODUCED=(), GONE=())


class _Generated(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-uxk3d-")
        root = Path(cls.temp.name)
        producer = h2.load_producer(h2.PRODUCER)
        h2.generate_quietly(producer, root / "a")
        with mock.patch.object(layer, "apply_home_card", lambda repo, name, text, audits: text):
            h2.generate_quietly(producer, root / "p")
        cls.layered_tree, cls.plain_tree = h2.tree(root / "a"), h2.tree(root / "p")
        cls.layered_bytes, cls.plain_bytes = cls.layered_tree[h2.CARD_PATH], cls.plain_tree[h2.CARD_PATH]
        cls.layered, cls.plain = cls.layered_bytes.decode("utf-8"), cls.plain_bytes.decode("utf-8")
        # The text the K3d edits receive: the H2, K3 and K3b edits applied, nothing else.
        cls.received = apply_edits(cls.plain, layer.EDITS + layer.COVER_EDITS + layer.LAYOUT_EDITS)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()


class HoverEditsTest(_Generated):
    def test_the_edits_are_the_four_of_the_slice_after_the_earlier_ones(self):
        self.assertEqual(LABELS, [label for label, _b, _a in layer.HOVER_EDITS])
        self.assertEqual(EXPECTED, layer.HOVER_EDITS)
        # K3e appends its own copy link edits after these four, so the chain is checked by composition here and
        # the hover group is pinned to the four rows that directly follow the K3b layout group.
        self.assertEqual(layer.EDITS + layer.COVER_EDITS + layer.LAYOUT_EDITS + layer.HOVER_EDITS + layer.COPY_EDITS,
                         layer.ALL_EDITS)
        self.assertEqual(LABELS, [label for label, _b, _a in layer.ALL_EDITS[-7:-3]])
        self.assertEqual(NAMES, layer.HOVER_INTRODUCED)
        for label, before, after in EXPECTED:
            self.assertNotEqual(before, after, label)
            self.assertTrue(before.endswith("\n") and after.endswith("\n"), label)

    def test_every_anchor_occurs_exactly_once_in_the_text_the_edits_receive(self):
        text = self.received
        for label, before, after in EXPECTED:
            self.assertEqual(1, text.count(before), label)
            self.assertEqual(0, text.count(after), label)
            text = text.replace(before, after, 1)
            self.assertEqual(1, self.layered.count(after), label)

    def test_forward_is_the_written_out_edits_byte_for_byte(self):
        # The text the K3d edits leave: the produced card with the K3e copy link edits undone, nothing else.
        hover_only = undo_edits(self.layered, layer.COPY_EDITS)
        self.assertEqual(hover_only.encode("utf-8"), apply_edits(self.received, EXPECTED).encode("utf-8"))
        audits = []
        produced = layer.apply_home_card(REPO, layer.CARD, self.plain, audits)
        self.assertEqual(self.layered_bytes, produced.encode("utf-8"))

    def test_inverse_gives_back_the_earlier_text_and_the_vendored_card_byte_for_byte(self):
        # Undoing the K3e edits and then only the K3d edits, last first, gives back the text of H2, K3 and K3b
        # exactly; the whole layer reverses to the vendored card.
        self.assertEqual(self.received.encode("utf-8"),
                         undo_edits(undo_edits(self.layered, layer.COPY_EDITS), EXPECTED).encode("utf-8"))
        self.assertEqual(self.plain_bytes, layer.reverse(layer.CARD, self.layered).encode("utf-8"))

    def test_the_layer_changes_only_the_card_and_the_inventory(self):
        self.assertEqual(set(self.plain_tree), set(self.layered_tree))
        changed = {name for name in self.layered_tree if self.layered_tree[name] != self.plain_tree[name]}
        self.assertEqual({h2.CARD_PATH, h2.INVENTORY_PATH}, changed)

    def test_the_four_audit_rows_of_the_hover_edits_follow_the_layout_ones(self):
        audits = []
        layer.apply_home_card(REPO, layer.CARD, self.plain, audits)
        # K3e appends three rows after these four, so the hover rows are the four before the last three.
        self.assertEqual(LABELS, [row["id"] for row in audits[-7:-3]])
        for (label, before, after), row in zip(EXPECTED, audits[-7:-3]):
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


class HoverRefusalTest(_Generated):
    def test_the_hover_only_layer_reproduces_the_card(self):
        # The control of the refusals below: with only the K3d edits, the layer turns the received text into the
        # card the K3d edits leave (the produced card has the K3e copy link edits on top, so they are undone here).
        hover_only = undo_edits(self.layered, layer.COPY_EDITS)
        audits = []
        with only_the_hover_edits():
            self.assertEqual(hover_only, layer.apply_home_card(REPO, layer.CARD, self.received, audits))
        self.assertEqual(LABELS, [row["id"] for row in audits])

    def test_a_mutated_anchor_fails_closed(self):
        for label, before, _after in EXPECTED:
            # Insert a character after the first one that is not a space: the anchor then no longer occurs in the received text.
            start = len(before) - len(before.lstrip(" ")) + 1
            mutated = before[:start] + "X" + before[start:]
            self.assertEqual(0, self.received.count(mutated), label)
            audits = []
            with only_the_hover_edits(), self.assertRaisesRegex(AssertionError, "anchor count changed: %s \\(0\\)" % re.escape(label)):
                layer.apply_home_card(REPO, layer.CARD, self.received.replace(before, mutated, 1), audits)
            self.assertNotIn(label, [row["id"] for row in audits], label)
            # The full layer refuses the mutated vendored card as well, where the anchor is vendored text.
            if self.plain.count(before) == 1:
                with self.assertRaises(AssertionError, msg=label):
                    layer.apply_home_card(REPO, layer.CARD, self.plain.replace(before, mutated, 1), [])

    def test_a_doubled_anchor_fails_closed(self):
        for label, before, _after in EXPECTED:
            audits = []
            with only_the_hover_edits(), self.assertRaisesRegex(AssertionError, "anchor count changed: %s \\(2\\)" % re.escape(label)):
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


class HoverWiringTest(_Generated):
    def test_the_flag_switch_keeps_the_vendored_hover_as_its_off_branch(self):
        # The vendored line is gone from the chain and occurs only once, as the OFF branch; the ON branch is the K3d surface.
        self.assertEqual(1, self.plain.count("            .adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n"))
        self.assertEqual(0, self.layered.count("            .adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n"))
        self.assertEqual(1, self.layered.count("adaptiveCardHoverEffect("))
        self.assertEqual(1, self.layered.count(".desktopHomeCardHoverSurface("))
        switch = ("            .fillMaxWidth()\n            .then(\n                if (homeCardThreeLine) {\n"
                  "                    Modifier.desktopHomeCardHoverSurface(homeCardHover, cornerRadius = cardCornerRadius)\n"
                  "                } else {\n                    Modifier.adaptiveCardHoverEffect(shape = adaptiveHoverShape)\n"
                  "                }\n            )\n            .desktopCardSecondaryClick(")
        self.assertEqual(1, self.layered.count(switch))
        # The flag is still read once (slice K3b): the switch reads the same local.
        self.assertEqual(1, self.layered.count("desktopCardInteractionsOn()"))
        self.assertEqual(1, self.layered.count("import com.bilipai.desktop.ui.desktopHomeCardHoverSurface\n"))
        self.assertEqual(1, len(re.findall(r"(?m)^import .*\bdesktopHomeCardHoverSurface$", self.layered)))

    def test_the_shared_state_is_declared_once_and_read_by_the_surface_and_the_actions(self):
        declaration = "    val homeCardHover = com.bilipai.desktop.ui.rememberDesktopHomeCardHover()\n"
        self.assertEqual(1, self.layered.count(declaration))
        self.assertEqual(3, len(re.findall(r"\bhomeCardHover\b", self.layered)))  # the declaration, the surface, the actions
        at = self.layered.index(declaration)
        self.assertLess(self.layered.index("    val homeCoverFocus = "), at)
        self.assertLess(at, self.layered.index(".desktopHomeCardHoverSurface("))
        self.assertLess(at, self.layered.index("DesktopHomeCardQuickActions("))
        for name in ("    val hasOverflowMenu = ", "    val openDismissMenu: ", "    val cardCornerRadius = "):
            self.assertLess(self.layered.index(name), at, name)

    def test_the_quick_actions_sit_last_in_the_cover_box_with_declared_parameters(self):
        code = source.mask_kotlin(self.layered)
        calls = list(re.finditer(r"com\.bilipai\.desktop\.ui\.DesktopHomeCardQuickActions\(", code))
        self.assertEqual(1, len(calls))
        arguments = h2.top_level_arguments(code, calls[0].end() - 1)
        named = [argument.split("=", 1)[0].strip() for argument in arguments]
        self.assertEqual(["state", "coverFocus", "onWatchLater", "onMore", "watchLaterLabel", "moreLabel", "modifier"], named)
        declared = h2.declared_parameters(source.read(FRAME_KT), "DesktopHomeCardQuickActions")
        self.assertEqual(named, [parameter for parameter, _default in declared])
        # The call is in the cover box: after the cover tag and the focus target, before the info surface of the card.
        at = self.layered.index("DesktopHomeCardQuickActions(")
        self.assertLess(self.layered.index('.testTag("home_video_cover")'), at)
        self.assertLess(self.layered.index(".desktopHomeCoverFocusTarget("), at)
        self.assertLess(at, self.layered.index("        val infoSurfaceShape = remember(cardCornerRadius) {\n"))

    def test_the_actions_reuse_the_card_callbacks_and_the_menu_only_where_there_is_one(self):
        self.assertEqual(1, self.layered.count("                onWatchLater = onWatchLater,\n"))
        self.assertEqual(1, self.layered.count("                onMore = if (hasOverflowMenu) {\n                    { anchor -> openDismissMenu(anchor, null) }\n"
                                               "                } else {\n                    null\n                },\n"))
        # The only new call of the card menu is the quick "more" action; the earlier slices' calls are unchanged.
        self.assertEqual(self.received.count("openDismissMenu(") + 1, self.layered.count("openDismissMenu("))
        # The callback is passed on as it is: "onWatchLater = onWatchLater" is the only new mention, and it names it twice.
        self.assertEqual(self.received.count("onWatchLater") + 2, self.layered.count("onWatchLater"))

    def test_the_labels_are_the_contract_watch_later_and_a_more_distinct_from_the_overflow_name(self):
        labels = {entry["id"]: entry for entry in cm.section()["labels"]}
        self.assertEqual(cm.plain_label(labels["watch_later"]), "稍后再看")
        self.assertEqual(1, self.layered.count('watchLaterLabel = "%s",\n' % cm.plain_label(labels["watch_later"])))
        self.assertEqual(1, self.layered.count('moreLabel = "更多",\n'))
        # The overflow button under the title keeps its own accessible name.
        self.assertEqual(self.received.count('contentDescription = "更多操作"'), self.layered.count('contentDescription = "更多操作"'))

    def test_the_new_names_occur_only_in_the_card_of_the_generated_tree(self):
        for relative, data in self.layered_tree.items():
            if not relative.endswith(".kt") or relative == h2.CARD_PATH:
                continue
            text = data.decode("utf-8")
            for name in NAMES:
                self.assertNotIn(name, text, (relative, name))


class HoverFrameTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.code = source.mask_kotlin(source.read(FRAME_KT))

    def test_the_three_entry_points_are_declared_once(self):
        for pattern in (r"internal fun rememberDesktopHomeCardHover\(\): DesktopHomeCardHover",
                        r"internal fun Modifier\.desktopHomeCardHoverSurface\(state: DesktopHomeCardHover, cornerRadius: Dp\): Modifier",
                        r"internal fun DesktopHomeCardQuickActions\("):
            self.assertEqual(1, len(re.findall(pattern, self.code)), pattern)

    def test_the_surface_and_the_actions_return_at_once_while_the_flag_is_off(self):
        start = self.code.index("fun Modifier.desktopHomeCardHoverSurface(")
        body = self.code[self.code.index("{", self.code.index("): Modifier", start)):]
        self.assertEqual("if (!desktopCardInteractionsOn()) return this", body[1:].strip().splitlines()[0])
        start = self.code.index("fun DesktopHomeCardQuickActions(")
        body = self.code[self.code.index(") {", start) + 2:]
        self.assertEqual("if (!desktopCardInteractionsOn() || (onWatchLater == null && onMore == null)) return", body[1:].strip().splitlines()[0])

    def test_the_outset_and_the_quick_geometry_are_the_spec_values(self):
        for declaration in ("internal const val DESKTOP_HOME_CARD_HOVER_OUTSET_DP = 8",
                            "internal const val DESKTOP_HOME_CARD_QUICK_INSET_DP = 8",
                            "internal const val DESKTOP_HOME_CARD_QUICK_GAP_DP = 4"):
            self.assertEqual(1, self.code.count(declaration), declaration)


if __name__ == "__main__":
    unittest.main()
