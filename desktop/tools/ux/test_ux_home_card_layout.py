"""Slice K3b generator checks: the three-line Home card layout edits (LAYOUT_EDITS of desktop/tools/ux_home_card.py) on the generated
VideoCard text. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_card_layout.py

What is proved. The real Home card producer (extract-upstream-home-full-card.py) is run with the layer and with the layer patched out:

  LayoutProducerTest   the fourteen edits, in order, each a whole-line, newline-ended replacement; each anchor occurs exactly once in the
                       text the H2 and K3 edits leave; applying the layer to the received text gives the produced card byte for byte, and
                       reversing it gives the received text byte for byte; a mutated or doubled K3b anchor and a taken K3b name fail
                       closed with AssertionError; the flag is read once and every ON branch keeps the vendored expression as its OFF
                       branch; the ON branches carry the spec values (16:9 cover, 12 dp corner, 14 / 20 Medium title, time inline in the
                       owner row, no separate publish row)
  LayoutContractTest   the contract's 32 dp overflow target is untouched by the layout edits: no K3b edit names the overflow box, its
                       target function or a touch target size, so the 24 dp glyph of the spec stays a glyph inside the 32 dp target

What is not proved. How the card renders: DesktopHomeCardContentLayoutTest (ImageComposeScene) measures the cover ratio, the corner, the
title lines, the owner row and the clicks of the real generated card with the flag ON and OFF.
"""
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import tempfile  # noqa: E402

import test_ux_home_card_adaptations as h2  # noqa: E402
import ux_home_card_model as cm  # noqa: E402

layer = h2.layer
REPO = h2.REPO

LABELS = ["ux-card-layout-flag-and-corner", "ux-card-layout-duration-on-cover", "ux-card-layout-duration-outside",
          "ux-card-layout-stats-on-cover", "ux-card-layout-publish-time", "ux-card-layout-shell-shape", "ux-card-layout-cover-16-9",
          "ux-card-layout-title-style", "ux-card-layout-owner-time-parameter", "ux-card-layout-owner-leading-condition",
          "ux-card-layout-owner-no-reason-badge", "ux-card-layout-owner-time-inline", "ux-card-layout-owner-time-call",
          "ux-card-layout-no-publish-row"]


def apply_edits(text, edits):
    for label, before, after in edits:
        if text.count(before) != 1:
            raise AssertionError("%s occurs %d times" % (label, text.count(before)))
        text = text.replace(before, after, 1)
    return text


def undo_edits(text, edits):
    """Undo the edits last first, each of whose replacement must occur exactly once."""
    for label, before, after in reversed(edits):
        if text.count(after) != 1:
            raise AssertionError("%s replacement occurs %d times" % (label, text.count(after)))
        text = text.replace(after, before, 1)
    return text


class LayoutProducerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-uxk3b-")
        root = Path(cls.temp.name)
        producer = h2.load_producer(h2.PRODUCER)
        h2.generate_quietly(producer, root / "a")
        with mock.patch.object(layer, "apply_home_card", lambda repo, name, text, audits: text):
            h2.generate_quietly(producer, root / "p")
        cls.layered_bytes = h2.tree(root / "a")[h2.CARD_PATH]
        cls.plain_bytes = h2.tree(root / "p")[h2.CARD_PATH]
        cls.layered, cls.plain = cls.layered_bytes.decode("utf-8"), cls.plain_bytes.decode("utf-8")
        # The text the K3b edits receive: the H2 and K3 edits applied, nothing else.
        cls.received = apply_edits(cls.plain, layer.EDITS + layer.COVER_EDITS)
        # The text the K3b edits leave: the produced card with the K3e copy link edits and then the K3d hover edits
        # (test_ux_home_card_hover.py) undone, last first.
        cls.k3b = undo_edits(undo_edits(cls.layered, layer.COPY_EDITS), layer.HOVER_EDITS)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_the_edits_are_the_fourteen_of_the_slice_in_order(self):
        self.assertEqual(LABELS, [label for label, _b, _a in layer.LAYOUT_EDITS])
        self.assertEqual(layer.EDITS + layer.COVER_EDITS + layer.LAYOUT_EDITS + layer.HOVER_EDITS + layer.COPY_EDITS,
                         layer.ALL_EDITS)
        for label, before, after in layer.LAYOUT_EDITS:
            self.assertNotEqual(before, after, label)
            self.assertTrue(before.endswith("\n") and after.endswith("\n"), label)
            self.assertTrue(before.startswith(" ") or before[0].isalpha(), label)

    def test_every_anchor_occurs_exactly_once_in_the_text_the_edits_receive(self):
        text = self.received
        for label, before, after in layer.LAYOUT_EDITS:
            self.assertEqual(1, text.count(before), label)
            self.assertEqual(0, text.count(after), label)
            text = text.replace(before, after, 1)
            self.assertEqual(1, self.layered.count(after), label)
        self.assertEqual(self.k3b, text)

    def test_forward_and_inverse_are_byte_identical(self):
        audits = []
        produced = layer.apply_home_card(REPO, layer.CARD, self.plain, audits)
        self.assertEqual(self.layered_bytes, produced.encode("utf-8"))
        self.assertEqual(self.plain_bytes, layer.reverse(layer.CARD, produced).encode("utf-8"))
        self.assertEqual([label for label, _b, _a in layer.ALL_EDITS], [row["id"] for row in audits])
        # Undoing the K3e copy link edits, then the K3d hover edits and then the K3b edits, last first, gives back the
        # text the H2 and K3 edits alone produce, byte for byte.
        partial = undo_edits(undo_edits(produced, layer.COPY_EDITS), layer.HOVER_EDITS)
        for label, before, after in reversed(layer.LAYOUT_EDITS):
            self.assertEqual(1, partial.count(after), label)
            partial = partial.replace(after, before, 1)
        self.assertEqual(self.received.encode("utf-8"), partial.encode("utf-8"))

    def test_a_mutated_or_doubled_anchor_fails_closed(self):
        for label, before, _after in layer.LAYOUT_EDITS:
            self.assertEqual(1, self.plain.count(before), label + ": the anchor is vendored text, not text of an earlier edit")
            start = len(before) - len(before.lstrip(" ")) + 1
            mutated = before[:start] + "X" + before[start:]
            with self.assertRaises(AssertionError, msg=label + " missing"):
                layer.apply_home_card(REPO, layer.CARD, self.plain.replace(before, mutated, 1), [])
            with self.assertRaises(AssertionError, msg=label + " doubled"):
                layer.apply_home_card(REPO, layer.CARD, self.plain + "\n" + before, [])

    def test_a_taken_name_is_refused_before_any_edit(self):
        self.assertEqual(("desktopCardInteractionsOn", "homeCardThreeLine", "threeLineTime", "home_video_owner_time"), layer.LAYOUT_INTRODUCED)
        for introduced in layer.LAYOUT_INTRODUCED:
            self.assertNotIn(introduced, self.plain, introduced)
            audits = []
            with self.assertRaises(AssertionError, msg=introduced):
                layer.apply_home_card(REPO, layer.CARD, self.plain + "\nval %s = 1\n" % introduced, audits)
            self.assertEqual([], audits, introduced)

    def test_the_flag_is_read_once_into_one_local(self):
        self.assertEqual(1, self.layered.count("desktopCardInteractionsOn()"))
        self.assertEqual(1, self.layered.count("val homeCardThreeLine = com.bilipai.desktop.ui.desktopCardInteractionsOn()\n"))
        for label, _before, after in layer.LAYOUT_EDITS:
            if label == "ux-card-layout-owner-time-parameter":
                # The parameter edit only declares threeLineTime; its default null is the vendored row.
                self.assertIn("threeLineTime: String? = null", after, label)
                continue
            self.assertTrue("homeCardThreeLine" in after or "threeLineTime" in after, label)

    def test_every_on_branch_keeps_the_vendored_expression_as_its_off_branch(self):
        for needle in (
            "val cardCornerRadius = if (homeCardThreeLine) 12.dp else AppShapes.containerCornerDp(ContainerLevel.Card)\n",
            "val showDurationOutside = !homeCardThreeLine && homeDurationStyle == HomeDurationStyle.OUTSIDE_COVER\n",
            "compactStatsOnCover = compactStatsOnCover || homeCardThreeLine\n",
            ".aspectRatio(if (homeCardThreeLine) 16f / 9f else coverAspectRatio)\n",
            "publishTimeText = if (homeCardThreeLine) \"\" else publishTimeRowText,\n",
            "threeLineTime = publishTimeRowText.takeIf { homeCardThreeLine }\n",
            "(threeLineTime == null && video.recommendationReason.isNotBlank())",
            "if (homeCardThreeLine) {\n                    title.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)\n",
        ):
            self.assertEqual(1, self.layered.count(needle), needle)

    def test_the_owner_row_carries_the_time_and_the_publish_row_is_empty_when_on(self):
        self.assertEqual(1, self.layered.count('Modifier.testTag("home_video_owner_time")'))
        self.assertEqual(1, self.layered.count('.removePrefix("发布于 ")'))
        self.assertEqual(1, self.layered.count("inlineTrailingContent"))

    def test_the_new_names_occur_only_in_the_card(self):
        for path, data in h2.tree(Path(self.temp.name) / "a").items():
            if path == h2.CARD_PATH or not path.endswith(".kt"):
                continue
            text = data.decode("utf-8")
            for name in ("homeCardThreeLine", "threeLineTime", "home_video_owner_time"):
                self.assertNotIn(name, text, (path, name))


class LayoutContractTest(unittest.TestCase):
    def test_the_overflow_target_stays_the_contract_32_dp(self):
        self.assertEqual(32, cm.section()["overflow_target_dp"])
        for label, before, after in layer.LAYOUT_EDITS:
            for needle in ("desktopCardOverflowTarget", "MinimumTouchTarget", "更多操作", "MoreVert"):
                self.assertNotIn(needle, before, (label, needle))
                self.assertNotIn(needle, after, (label, needle))

    def test_the_only_new_size_is_the_12_dp_corner(self):
        for label, before, after in layer.LAYOUT_EDITS:
            added = [token for token in ("12.dp", "16.dp", "24.dp", "32.dp", ".size(") if after.count(token) > before.count(token)]
            self.assertEqual(["12.dp"] if label == "ux-card-layout-flag-and-corner" else [], added, label)


if __name__ == "__main__":
    unittest.main()
