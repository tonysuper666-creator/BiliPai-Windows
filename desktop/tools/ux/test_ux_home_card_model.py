"""Slice H2 number and rule checks: the Home card interaction policy against the shared contract. No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_home_card_model.py

What is proved. ux_home_card_model.py holds line-by-line mirrors of the functions of ui/DesktopCardInteractionPolicy.kt and the parsers of its
token block.

  TokenTest           the three token constants of the Kotlin file are the numbers of the contract; the menu row is the flyout row of the contract
                      and the overflow target is the kit icon hit (32); the menu is narrower than the kit popover and holds no more entries than
                      the contract's card menu cap
  ContractShapeTest   the home_card section has exactly the planned keys, the labels are the three of the spec in the order of the menu, the
                      press and follow tables cover every combination once, and the rule says the menu opens on the press and that the press
                      and the release are consumed
  KotlinMirrorPinTest the mirrored Kotlin lines are pinned as needles, so editing a function without its mirror fails here; the mirrors give the
                      tables of the contract (written by hand, not derived from the mirrors) and the whole gesture replays as the rule says
  LabelTest           the contract labels are the vendored labels of the canonical card (an emoji, a space and the plain label), and the later
                      upstream card already has the plain labels the layer introduces
  JvmTestFileTest     DesktopCardInteractionPolicyTest exists with its test count, reads the contract section, and uses no window, scene or network

What is not proved. That the Kotlin compiles or runs: DesktopCardInteractionPolicyTest is the proof of the policy, on the Linux compile later.
The composables are judged by test_ux_home_card_adaptations.py (source rules); the generator edits and the Gradle wiring are there too.

Location. The UX suites live in desktop/tools/ux, following the desktop/tools/nvidia precedent, not in desktop/tools/tests as spec 8.2 says;
Root adds each suite to the validation plan as an explicit `-s desktop/tools/ux -p <file>` line.
"""
import itertools
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_home_card_model as cm  # noqa: E402
import ux_kit_model as kit  # noqa: E402
import ux_model as model  # noqa: E402
import ux_shell_model as shell  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()
CARD = CONTRACT["home_card"]
POLICY_TEXT = source.read(cm.POLICY_KT)
JVM_TEST_COUNT = 11
VENDORED_CARD = source.REPO / "app/src/main/java/com/android/purebilibili/feature/home/components/cards/VideoCard.kt"
LATER_CARD = source.REPO / "desktop/upstream-slices/v043-home-native-cards/VideoCard.kt"


def has(text, needle):
    return shell.squash(needle) in shell.squash(text)


class TokenTest(unittest.TestCase):
    def test_the_kotlin_tokens_are_the_contract_numbers(self):
        tokens = cm.policy_tokens(POLICY_TEXT)
        self.assertEqual(set(cm.token_map()), set(tokens))
        for name, value in tokens.items():
            self.assertEqual(cm.contract_token(name), value, name)

    def test_the_menu_row_is_the_flyout_row_and_the_overflow_target_is_the_kit_icon_hit(self):
        self.assertEqual(CONTRACT["flyout"]["row_height"], CARD["menu"]["row_height_dp"])
        kit_tokens = kit.token_constants(source.read(kit.TOKENS_KT))
        icon_hit = CONTRACT["tokens"]["icon_hit"]
        self.assertEqual(32, icon_hit[0])
        self.assertEqual(icon_hit[0], CARD["overflow_target_dp"])
        self.assertEqual(kit_tokens["DESKTOP_KIT_ICON_HIT_DP"], CARD["overflow_target_dp"])

    def test_the_overflow_target_is_above_the_hit_floor_and_below_the_vendored_size(self):
        self.assertGreaterEqual(CARD["overflow_target_dp"], CONTRACT["tokens"]["hit_floor"])
        self.assertLess(CARD["overflow_target_dp"], 48)  # AppChromeSizeTokens.MinimumTouchTarget, pinned by DesktopDesignSystemLeavesATest

    def test_the_menu_is_compact(self):
        self.assertLess(CARD["menu"]["width_dp"], CONTRACT["kit"]["menu"]["popover_width"])
        self.assertGreaterEqual(CARD["menu"]["width_dp"], 2 * 6 + 2 * 10 + 4 * 14)  # padding, row padding, and four 14 sp characters
        self.assertGreaterEqual(CARD["menu"]["row_height_dp"], CONTRACT["tokens"]["hit_floor"])

    def test_the_menu_holds_no_more_entries_than_the_contract_cap(self):
        self.assertLessEqual(len(CARD["labels"]), CONTRACT["flyout"]["card_menu_max_items"])


class ContractShapeTest(unittest.TestCase):
    def test_the_section_has_exactly_the_planned_keys(self):
        self.assertEqual(["about", "flag", "menu", "overflow_target_dp", "labels", "secondary", "not_covered"], list(CARD))
        self.assertEqual("card_interactions", CARD["flag"])
        self.assertEqual(["row_height_dp", "width_dp"], list(CARD["menu"]))
        self.assertEqual(["opens_on", "consumes", "press_table", "follow_table"], list(CARD["secondary"]))

    def test_the_flag_is_a_flag_of_the_store_and_the_layout_group_lists_it(self):
        self.assertIn(CARD["flag"], [row["key"] for row in CONTRACT["flags"]["fields"]])
        self.assertEqual("ON", [row["default"] for row in CONTRACT["flags"]["fields"] if row["key"] == CARD["flag"]][0])
        self.assertEqual(["ON", "OFF"], [value for row in CONTRACT["settings"]["ux_group"]["rows"] if row["flag"] == CARD["flag"]
                                          for value in row["values"]])

    def test_the_labels_are_the_three_of_the_spec_in_menu_order(self):
        self.assertEqual(["watch_later", "unfavorite", "dismiss"], [row["id"] for row in CARD["labels"]])
        # The contract is ASCII and holds no Chinese text (test_ux_control_bar.py and test_ux_settings_model.py pin that), so it names
        # each label by id and gives the code point of its vendored emoji; the plain text is the generator's constant of the id.
        self.assertEqual(["稍后再看", "取消收藏", "不感兴趣"], [cm.plain_label(row) for row in CARD["labels"]])
        self.assertEqual(["1F550", "1F494", "1F6AB"], [row["emoji_code_point"] for row in CARD["labels"]])
        self.assertTrue(model.CONTRACT_PATH.read_bytes().isascii())
        for row in CARD["labels"]:
            self.assertEqual(["id", "emoji_code_point"], list(row))
            # plain means plain: Chinese characters only, no emoji, no space
            self.assertNotRegex(cm.plain_label(row), r"[^一-鿿]")
            self.assertGreater(int(row["emoji_code_point"], 16), 0xFFFF)  # a supplementary-plane emoji: a surrogate pair in Kotlin

    def test_the_press_table_covers_every_button_combination_once(self):
        rows = CARD["secondary"]["press_table"]
        self.assertEqual(sorted(itertools.product([False, True], repeat=2)), sorted((r["primary"], r["secondary"]) for r in rows))
        self.assertEqual(["owns", "primary", "secondary"], sorted(rows[0]))
        # By hand: the secondary button alone is the card's, nothing else is.
        self.assertEqual([(False, True)], [(r["primary"], r["secondary"]) for r in rows if r["owns"]])

    def test_the_follow_table_covers_every_pointer_state_once(self):
        rows = CARD["secondary"]["follow_table"]
        self.assertEqual(sorted(itertools.product([False, True], repeat=2)), sorted((r["present"], r["pressed"]) for r in rows))
        self.assertEqual({"WAIT", "CONSUME_AND_END", "END"}, {r["step"] for r in rows})

    def test_the_rule_opens_on_the_press_and_consumes_the_press_and_the_release(self):
        self.assertEqual("PRESS", CARD["secondary"]["opens_on"])
        self.assertEqual(["PRESS", "RELEASE"], CARD["secondary"]["consumes"])

    def test_the_cards_not_covered_are_the_ones_the_spec_names(self):
        self.assertEqual(["HomeStyleSingleColumnVideoCard", "StoryVideoCard", "DynamicCard"], CARD["not_covered"])


class KotlinMirrorPinTest(unittest.TestCase):
    def test_the_mirrored_kotlin_lines_are_pinned(self):
        for needle in (
            "internal fun desktopCardMenuLabelFor(interactionsOn: Boolean, original: String, plain: String): String = if (interactionsOn) plain else original",
            "internal fun desktopCardOverflowTargetDpFor(interactionsOn: Boolean, originalDp: Float): Float = "
            "if (interactionsOn) DESKTOP_CARD_OVERFLOW_TARGET_DP.toFloat() else originalDp",
            "internal fun desktopCardSecondaryClickActive(hasMenu: Boolean, interactionsOn: Boolean): Boolean = hasMenu && interactionsOn",
            "internal fun desktopCardOwnsSecondaryPress(primaryPressed: Boolean, secondaryPressed: Boolean): Boolean = secondaryPressed && !primaryPressed",
            "internal fun desktopCardSecondaryFollow(pointerPresent: Boolean, stillPressed: Boolean): DesktopCardSecondaryFollow = when { "
            "!pointerPresent -> DesktopCardSecondaryFollow.END stillPressed -> DesktopCardSecondaryFollow.WAIT "
            "else -> DesktopCardSecondaryFollow.CONSUME_AND_END }",
            "internal enum class DesktopCardSecondaryFollow { WAIT, CONSUME_AND_END, END }",
        ):
            self.assertTrue(has(POLICY_TEXT, needle), needle)

    def test_the_file_is_pure(self):
        code = source.mask_kotlin(POLICY_TEXT)
        self.assertEqual([], re.findall(r"(?m)^import .*$", code))
        for forbidden in ("androidx", "java.awt", "javax.swing", "@Composable", "com.sun"):
            self.assertNotIn(forbidden, code)

    def test_the_mirrors_give_the_tables_of_the_contract(self):
        for row in CARD["secondary"]["press_table"]:
            self.assertEqual(row["owns"], cm.owns_secondary_press(row["primary"], row["secondary"]), row)
        for row in CARD["secondary"]["follow_table"]:
            self.assertEqual(row["step"], cm.secondary_follow(row["present"], row["pressed"]), row)

    def test_the_label_the_overflow_target_and_the_mount_rule_follow_the_flag(self):
        for entry in CARD["labels"]:
            original = cm.original_label(entry)
            plain = cm.plain_label(entry)
            self.assertEqual(plain, cm.menu_label(True, original, plain))
            self.assertEqual(original, cm.menu_label(False, original, plain))
        target = CARD["overflow_target_dp"]
        self.assertEqual(32.0, cm.overflow_target_dp(True, 48.0, target))
        self.assertEqual(32.0, cm.overflow_target_dp(True, 24.0, target))
        self.assertEqual(48.0, cm.overflow_target_dp(False, 48.0, target))
        self.assertEqual(24.0, cm.overflow_target_dp(False, 24.0, target))
        self.assertEqual([True, False, False, False],
                         [cm.secondary_click_active(m, o) for m, o in ((True, True), (True, False), (False, True), (False, False))])

    def test_the_whole_gesture_replays_as_the_rule_says(self):
        press_release = dict(taken=True, opened=True, consumed=["PRESS", "RELEASE"], finished=True)
        self.assertEqual(press_release, cm.replay(False, True, [(True, True), (True, True), (True, False)]))
        self.assertEqual(press_release, cm.replay(False, True, [(True, False)]))
        self.assertEqual(dict(taken=True, opened=True, consumed=["PRESS"], finished=True), cm.replay(False, True, [(True, True), (False, True)]))
        self.assertEqual(dict(taken=True, opened=True, consumed=["PRESS"], finished=False), cm.replay(False, True, []))
        self.assertEqual(dict(taken=True, opened=True, consumed=["PRESS"], finished=False), cm.replay(False, True, [(True, True)]))
        nothing = dict(taken=False, opened=False, consumed=[], finished=True)
        for primary, secondary in ((True, True), (True, False), (False, False)):
            self.assertEqual(nothing, cm.replay(primary, secondary, [(True, False)]), (primary, secondary))

    def test_the_menu_opens_exactly_once_per_gesture(self):
        # The open callback is called once, at the press, before the loop; the loop never calls it (a pinned needle of the Kotlin file).
        text = source.read(cm.POINTER_KT)
        code = source.mask_kotlin(text)
        self.assertEqual(1, len(re.findall(r"\blatest\(", code)))
        self.assertLess(code.index("latest(down.position)"), code.index("while (true)"))


class LabelTest(unittest.TestCase):
    def test_the_contract_labels_are_the_vendored_labels_of_the_canonical_card(self):
        text = source.read(VENDORED_CARD)
        watch, unfavorite, dismiss = (cm.original_label(entry) for entry in CARD["labels"])
        self.assertEqual(1, text.count('"%s",\n' % watch))
        self.assertEqual(1, text.count('"%s",\n' % unfavorite))
        # The default of dismissMenuText writes the emoji as a Kotlin escape pair (a surrogate pair), not as the character.
        high, low = (0xD800 + ((0x1F6AB - 0x10000) >> 10), 0xDC00 + ((0x1F6AB - 0x10000) & 0x3FF))
        escape = "\\u%04X\\u%04X %s" % (high, low, cm.plain_label(CARD["labels"][2]))
        self.assertEqual("\\uD83D\\uDEAB 不感兴趣", escape)
        self.assertEqual(1, text.count('dismissMenuText: String = "%s",' % escape))
        self.assertEqual(chr(0x1F6AB) + " 不感兴趣", dismiss)

    def test_the_later_upstream_card_already_has_the_plain_labels(self):
        later = source.read(LATER_CARD)
        plain = [cm.plain_label(entry) for entry in CARD["labels"]]
        self.assertEqual(1, later.count('dismissMenuText: String = "%s",' % plain[2]))
        self.assertEqual(1, later.count('"%s",\n' % plain[0]))
        self.assertEqual(1, later.count('"%s",\n' % plain[1]))
        for entry in CARD["labels"]:
            self.assertNotIn(chr(int(entry["emoji_code_point"], 16)), later)


class JvmTestFileTest(unittest.TestCase):
    TEXT = source.read(cm.KOTLIN_TEST)

    def test_the_class_exists_with_its_tests(self):
        self.assertIn("package com.bilipai.desktop.ui\n", self.TEXT)
        self.assertIn("\nclass DesktopCardInteractionPolicyTest {", self.TEXT)
        self.assertEqual(JVM_TEST_COUNT, len(re.findall(r"@Test fun ", self.TEXT)))

    def test_it_reads_the_contract_section_and_the_policy_functions(self):
        self.assertIn('DesktopUxContract.section("home_card")', self.TEXT)
        for name in ("desktopCardMenuLabelFor", "desktopCardOverflowTargetDpFor", "desktopCardSecondaryClickActive",
                     "desktopCardOwnsSecondaryPress", "desktopCardSecondaryFollow", "DESKTOP_CARD_MENU_ROW_DP",
                     "DESKTOP_CARD_MENU_WIDTH_DP", "DESKTOP_CARD_OVERFLOW_TARGET_DP"):
            self.assertIn(name, self.TEXT)

    def test_it_needs_no_window_scene_gpu_or_network(self):
        code = source.mask_kotlin(self.TEXT)
        for forbidden in ("ImageComposeScene", "ComposeWindow", "ComposeDialog", "java.awt", "java.net", "HttpClient", "runComposeUiTest"):
            self.assertNotIn(forbidden, code)


class LineEndingTest(unittest.TestCase):
    def test_the_files_of_the_slice_are_plain_lf_text(self):
        source.assert_lf_only(self, *cm.CARD_FILES, cm.KOTLIN_TEST, cm.HERE / "ux_home_card_model.py", Path(__file__))


if __name__ == "__main__":
    unittest.main()
