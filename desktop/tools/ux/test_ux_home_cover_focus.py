"""Slice K3 generator and source checks: the keyboard focus of the Home card cover on the generated VideoCard text and the
Kotlin it calls (DesktopHomeCardFrame.kt). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_cover_focus.py

What is proved. desktop/tools/ux_home_card.py runs through the real Home card producer (extract-upstream-home-full-card.py),
once with the layer and once with the layer patched out:

  CoverEditsTest   the four K3 edits come after the seven H2 edits; each anchor occurs exactly once in the text K3 receives
                   (the H2 output), each replacement once in the produced card; reverse() gives back the received text byte for
                   byte; a mutated or doubled anchor and a taken K3 name fail closed with AssertionError
  CoverWiringTest  the focus target sits in the modifier chain of the cover Box (home_video_cover), after the test tag and before
                   the clip; Enter calls triggerCardClick, the same call as the cover tap; the menu keys call openDismissMenu with
                   the cover anchor only when hasOverflowMenu (the condition of the right click); the ring is the last modifier
                   of the card root Box, which does not clip; the shared state is declared once, before the root Box, after the
                   names the two calls use, and nothing else in the card uses it
  FrameSourceTest  the Kotlin declares the three entry points once with the parameter names the card uses; both modifiers return
                   the receiver while card_interactions is OFF; the ring uses the kit widths (outer 2 dp primary, inner 1 dp
                   background) after a 2 dp gap, so the primary ring is offset 3 dp (spec 2), and snaps them to pixels; the menu
                   key is the AWT VK_CONTEXT_MENU code; the file is LF text without Chinese, hooks or key fallback

What is not proved here. That the Kotlin compiles and that focus, Enter, the menu keys and the ring behave in a real Compose scene:
that is DesktopHomeCardFrameTest (a JVM test on the real generated card in an ImageComposeScene).
"""
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_source as source  # noqa: E402
from test_ux_home_card_adaptations import CARD_PATH, PRODUCER, REPO, generate_quietly, load_producer, matching_paren, tree  # noqa: E402

sys.path.insert(0, str(REPO / "desktop/tools"))
import ux_home_card as layer  # noqa: E402

FRAME_KT = source.K / "ui/DesktopHomeCardFrame.kt"
KIT_TOKENS_KT = source.K / "ui/kit/DesktopKitTokens.kt"
KIT_MODEL_KT = source.K / "ui/kit/DesktopKitModel.kt"
KIT_FOCUS_KT = source.K / "ui/kit/DesktopKitFocus.kt"
COVER_IDS = ["ux-card-cover-focus-imports", "ux-card-cover-focus-state", "ux-card-cover-focus-target", "ux-card-cover-focus-ring"]
TARGET_CALL = (".desktopHomeCoverFocusTarget(\n"
               "                    state = homeCoverFocus,\n"
               "                    cornerRadius = cardCornerRadius,\n"
               "                    onOpen = { triggerCardClick() },\n"
               "                    onMenu = if (hasOverflowMenu) {\n"
               "                        { openDismissMenu(coverCoordsRef.value, null) }\n"
               "                    } else {\n"
               "                        null\n"
               "                    },\n"
               "                )\n")


def h2_only(text):
    """The text K3 receives: the plain card with the seven H2 edits applied."""
    for label, before, after in layer.EDITS:
        text = layer._once(text, before, after, label)
    return text


class _Generated(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bp-uxk3-")
        root = Path(cls.temp.name)
        producer = load_producer(PRODUCER)
        generate_quietly(producer, root / "a")
        with mock.patch.object(layer, "apply_home_card", lambda repo, name, text, audits: text):
            generate_quietly(producer, root / "p")
        cls.plain = tree(root / "p")[CARD_PATH].decode("utf-8")
        cls.produced = tree(root / "a")[CARD_PATH].decode("utf-8")
        cls.received = h2_only(cls.plain)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def chain_of(self, start_marker):
        """The modifier chain text of the Box whose chain contains start_marker: from the Box( to its matching )."""
        index = self.produced.index(start_marker)
        box = self.produced.rindex("Box(", 0, index)
        return self.produced[box:matching_paren(self.produced, box + 3) + 1]


class CoverEditsTest(_Generated):
    def test_the_four_cover_edits_follow_the_seven_card_edits(self):
        self.assertEqual(COVER_IDS, [label for label, _b, _a in layer.COVER_EDITS])
        self.assertEqual(layer.EDITS + layer.COVER_EDITS, layer.ALL_EDITS)
        for label, before, after in layer.COVER_EDITS:
            self.assertNotEqual(before, after, label)
            self.assertTrue(before.endswith("\n") and after.endswith("\n"), label)
            # Every K3 edit only adds lines: removing the added lines gives back the anchor.
            added = [line for line in after.splitlines(True) if line not in before.splitlines(True)]
            self.assertEqual(before, "".join(line for line in after.splitlines(True) if line not in added), label)

    def test_every_cover_anchor_occurs_exactly_once_in_the_text_it_receives(self):
        for label, before, after in layer.COVER_EDITS:
            self.assertEqual(1, self.received.count(before), label)
            self.assertEqual(0, self.received.count(after), label)
            self.assertEqual(1, self.produced.count(after), label)

    def test_the_produced_card_is_the_received_text_with_the_cover_edits(self):
        expected = self.received
        for label, before, after in layer.COVER_EDITS:
            expected = expected.replace(before, after, 1)
        self.assertEqual(expected, self.produced)

    def test_reverse_gives_back_the_plain_card_byte_for_byte(self):
        self.assertEqual(self.plain.encode("utf-8"), layer.reverse(layer.CARD, self.produced).encode("utf-8"))

    def test_a_mutated_or_doubled_cover_anchor_fails_closed(self):
        for label, before, _after in layer.COVER_EDITS:
            start = len(before) - len(before.lstrip(" ")) + 1
            mutated = before[:start] + "X" + before[start:]
            self.assertEqual(1, self.plain.count(before), label)
            with self.assertRaises(AssertionError, msg=label + " missing"):
                layer.apply_home_card(REPO, layer.CARD, self.plain.replace(before, mutated, 1), [])
            with self.assertRaises(AssertionError, msg=label + " doubled"):
                layer.apply_home_card(REPO, layer.CARD, self.plain + "\n" + before, [])

    def test_a_taken_cover_name_is_refused_before_any_edit(self):
        for introduced in layer.COVER_INTRODUCED:
            self.assertNotIn(introduced, self.plain, introduced)
            audits = []
            with self.assertRaises(AssertionError, msg=introduced):
                layer.apply_home_card(REPO, layer.CARD, self.plain + "\nval %s = 1\n" % introduced, audits)
            self.assertEqual([], audits, introduced)

    def test_the_cover_imports_are_the_only_binding_of_their_names(self):
        for name in ("desktopHomeCoverFocusRing", "desktopHomeCoverFocusTarget"):
            self.assertEqual(1, self.produced.count("import com.bilipai.desktop.ui.%s\n" % name), name)
            self.assertEqual(1, len(re.findall(r"(?m)^import .*\b%s$" % name, self.produced)), name)


class CoverWiringTest(_Generated):
    def test_the_focus_target_sits_on_the_cover_box_after_the_tag_and_before_the_clip(self):
        chain = self.chain_of('.testTag("home_video_cover")')
        self.assertEqual(1, self.produced.count(".desktopHomeCoverFocusTarget("))
        order = ['.testTag("home_video_cover")', TARGET_CALL, ".aspectRatio(coverAspectRatio)", ".clip(coverShape)",
                 "coverCoordsRef.value = coordinates", "onTap = {\n                            triggerCardClick()"]
        positions = [chain.index(item) for item in order]
        self.assertEqual(sorted(positions), positions)

    def test_enter_is_the_cover_tap_and_the_menu_keys_are_the_right_click_menu(self):
        # The cover tap calls triggerCardClick with no argument; Enter makes the same call.
        self.assertIn("onOpen = { triggerCardClick() }", self.produced)
        self.assertIn("    val triggerCardClick = {\n", self.produced)
        # openDismissMenu takes the anchor and an optional press offset; the menu keys pass the cover anchor and no offset,
        # gated by hasOverflowMenu, the same condition as the right click of slice H2.
        self.assertIn("    val openDismissMenu: (LayoutCoordinates?, Offset?) -> Unit = { anchorCoords, pressOffset ->\n", self.produced)
        self.assertIn("    val hasOverflowMenu = onDismiss != null || onWatchLater != null\n", self.produced)
        self.assertIn(".desktopCardSecondaryClick(enabled = hasOverflowMenu)", self.produced)
        self.assertEqual(1, self.produced.count("{ openDismissMenu(coverCoordsRef.value, null) }"))

    def test_the_ring_is_the_last_modifier_of_the_card_root_box(self):
        chain = self.chain_of(".adaptiveCardHoverEffect(shape = adaptiveHoverShape)")
        self.assertTrue(chain.endswith(".desktopHomeCoverFocusRing(homeCoverFocus)\n    )"), chain[-200:])
        self.assertEqual(1, self.produced.count(".desktopHomeCoverFocusRing("))
        # The root chain clips nothing: the cover shell clips the cover, so the ring must be drawn here, outside it.
        self.assertNotIn(".clip(", chain)
        self.assertLess(chain.index("cardCoordsRef.value = coordinates"), chain.index(".desktopHomeCoverFocusRing("))

    def test_the_shared_state_is_declared_once_before_the_root_box_after_what_the_calls_use(self):
        declaration = "    val homeCoverFocus = com.bilipai.desktop.ui.rememberDesktopHomeCoverFocus()\n"
        self.assertEqual(1, self.produced.count(declaration))
        self.assertEqual(3, len(re.findall(r"\bhomeCoverFocus\b", self.produced)))  # the declaration, the target, the ring
        at = self.produced.index(declaration)
        root = self.produced.index(".adaptiveCardHoverEffect(shape = adaptiveHoverShape)")
        self.assertLess(at, root)
        for name in ("    val cardCornerRadius = ", "    val coverCoordsRef = ", "    val openDismissMenu: ", "    val hasOverflowMenu = ",
                     "    val triggerCardClick = "):
            self.assertLess(self.produced.index(name), at, name)
        # All of it inside ElegantVideoCard, the grid card of the Home feed.
        function = self.produced.index("internal fun ElegantVideoCard(")
        following = self.produced.find("\nfun ", function + 1)
        following_internal = self.produced.find("\ninternal fun ", function + 1)
        ends = [value for value in (following, following_internal) if value > 0]
        self.assertTrue(function < at and (not ends or self.produced.index(TARGET_CALL) < min(ends)))


class FrameSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = source.read(FRAME_KT)
        cls.code = source.mask_kotlin(cls.text)

    def test_the_file_is_plain_lf_program_text(self):
        source.assert_lf_only(self, FRAME_KT)
        self.assertIn("ui/DesktopHomeCardFrame.kt", source.PROGRAM_EXTRA_GLOBS)
        self.assertEqual([], source.cjk_positions(self.text))
        self.assertEqual([], source.hook_uses(self.text))
        self.assertEqual([], source.key_fallback_uses(self.text))
        self.assertEqual([], source.banned_imports(self.text))
        self.assertTrue(self.text.startswith("package com.bilipai.desktop.ui\n"))

    def test_the_three_entry_points_are_declared_once_with_the_names_the_card_uses(self):
        for pattern in (r"internal fun rememberDesktopHomeCoverFocus\(\): DesktopHomeCoverFocus",
                        r"internal fun Modifier\.desktopHomeCoverFocusTarget\(\s*state: DesktopHomeCoverFocus,\s*cornerRadius: Dp,"
                        r"\s*onOpen: \(\) -> Unit,\s*onMenu: \(\(\) -> Unit\)\?,\s*\): Modifier",
                        r"internal fun Modifier\.desktopHomeCoverFocusRing\(state: DesktopHomeCoverFocus\): Modifier"):
            self.assertEqual(1, len(re.findall(pattern, self.code)), pattern)

    def test_both_modifiers_return_the_receiver_while_the_flag_is_off(self):
        for name in ("desktopHomeCoverFocusTarget", "desktopHomeCoverFocusRing"):
            start = self.code.index("fun Modifier.%s(" % name)
            body = self.code[self.code.index("{", self.code.index("): Modifier", start)):]
            first = body[1:].strip().splitlines()[0]
            self.assertEqual("if (!desktopCardInteractionsOn()) return this", first, name)

    def test_the_ring_geometry_is_the_spec_ring_offset_three_dp(self):
        tokens = source.read(KIT_TOKENS_KT)
        self.assertIn("internal const val DESKTOP_KIT_FOCUS_RING_OUTER_DP = 2\n", tokens)
        self.assertIn("internal const val DESKTOP_KIT_FOCUS_RING_INNER_DP = 1\n", tokens)
        self.assertIn("internal const val DESKTOP_HOME_COVER_RING_GAP_DP = 2\n", self.text)
        self.assertIn("internal fun desktopKitSnapStrokePx(px: Float): Float", source.read(KIT_MODEL_KT))
        # Outer primary, inner background, both snapped; the inner stroke centred at gap + inner / 2, the outer at
        # gap + inner + outer / 2, so the primary ring starts 3 dp outside the cover.
        self.assertIn("val outerColor = MaterialTheme.colorScheme.primary\n", self.text)
        self.assertIn("val innerColor = MaterialTheme.colorScheme.background\n", self.text)
        for line in ("val gap = desktopKitSnapStrokePx(DESKTOP_HOME_COVER_RING_GAP_DP * density)",
                     "val inner = desktopKitSnapStrokePx(DESKTOP_KIT_FOCUS_RING_INNER_DP * density)",
                     "val outer = desktopKitSnapStrokePx(DESKTOP_KIT_FOCUS_RING_OUTER_DP * density)",
                     "drawCoverRingStroke(cover, radiusPx, gap + inner / 2f, inner, innerColor)",
                     "drawCoverRingStroke(cover, radiusPx, gap + inner + outer / 2f, outer, outerColor)"):
            self.assertEqual(1, self.text.count(line), line)
        # The ring shows only while the kit heuristic says keyboard focus.
        self.assertIn("if (state.focus.ringVisible && cover.width > 0f && cover.height > 0f)", self.text)

    def test_the_kit_symbols_it_uses_are_internal_and_in_the_kit_package(self):
        focus = source.read(KIT_FOCUS_KT)
        self.assertIn("internal fun Modifier.desktopKitOnPointerPress(onPress: () -> Unit): Modifier", focus)
        self.assertIn("internal data class DesktopKitFocusState(", source.read(KIT_MODEL_KT))
        for path in (KIT_TOKENS_KT, KIT_MODEL_KT, KIT_FOCUS_KT):
            self.assertTrue(source.read(path).startswith("package com.bilipai.desktop.ui.kit\n"), path.name)

    def test_the_key_actions(self):
        self.assertIn("private val DESKTOP_HOME_CONTEXT_MENU_KEY = Key(525)\n", self.text)
        body = self.code[self.code.index("internal fun desktopHomeCoverKeyAction("):]
        body = body[:body.index("\n}\n")]
        self.assertIn("event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed", body)
        self.assertIn("!event.isShiftPressed && (key == Key.Enter || key == Key.NumPadEnter) -> DesktopHomeCoverKeyAction.OPEN", body)
        self.assertIn("event.isShiftPressed && key == Key.F10 -> DesktopHomeCoverKeyAction.MENU", body)
        self.assertIn("!event.isShiftPressed && (key == DESKTOP_HOME_CONTEXT_MENU_KEY || key == Key.Menu) -> DesktopHomeCoverKeyAction.MENU", body)
        # A card without a menu leaves the menu keys to its parents.
        self.assertIn("DesktopHomeCoverKeyAction.MENU -> menu?.let { it(); true } ?: false", self.text)


if __name__ == "__main__":
    unittest.main()
