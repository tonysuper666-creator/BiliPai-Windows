"""Metrics, choice rule, rail rules and landing of the settings chrome (slice X3). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_settings_model.py

What is proved. The metric constants of ui/DesktopWindowsSettingsModel.kt (between the UX-SETTINGS-METRICS markers) and its
wide-glyph ranges are the numbers of the contract ("settings"). The Python mirror of the choice rule (UTF-16 label width,
segmented width, form) reproduces every golden row of the contract, and agrees with a differently shaped oracle over a
sweep. The rail list, the category of a page, the direct details and the stack rewrite of settings/DesktopSettingsTreeModel.kt
are the contract's, read through the vendored category policy that the Kotlin delegates to. The Navigator mirror replays
every scenario of "settings.tree.scenarios" to the recorded stack, last category and pending flag. The transliterated Kotlin
lines are pinned as source needles, so editing the Kotlin without the mirror, or the mirror without the Kotlin, fails here.
DesktopWindowsSettingsModelTest and DesktopSettingsTreeModelTest run the same tables on the real code.

What is not proved. That the Kotlin compiles or runs, and any composable behaviour: test_ux_settings_signatures.py reads the
composable source, the Windows fixtures run it.
"""
import itertools
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_model as model  # noqa: E402
import ux_settings_model as sm  # noqa: E402
import ux_source as source  # noqa: E402

CONTRACT = model.load()
SETTINGS = CONTRACT["settings"]
CHOICE = SETTINGS["choice"]
TREE_SECTION = SETTINGS["tree"]
MODEL_TEXT = sm.read(sm.MODEL_KT)
TREE_MODEL_TEXT = sm.read(sm.TREE_MODEL_KT)
NAVIGATOR_TEXT = sm.read(sm.NAVIGATOR_KT)
# The titles the tree always had (spec 3.7 keeps them): playback and audio, home and recommendation, appearance and display,
# cache and backup, privacy and blocking, plugins and extensions, update and diagnostics.
OLD_RAIL_TITLES = ("播放与音频", "首页与推荐", "外观与显示",
                   "缓存与备份", "隐私与屏蔽", "插件与扩展",
                   "更新与诊断")


def kotlin_choice():
    return sm.Choice.from_kotlin()


class SectionTest(unittest.TestCase):
    def test_the_contract_is_canonical_and_in_sync(self):
        self.assertEqual([], model.problems())

    def test_the_section_holds_no_chinese_text(self):
        self.assertEqual([], source.cjk_positions(model.CONTRACT_PATH.read_bytes().decode("utf-8")))

    def test_the_metrics_block_is_marked_once_and_names_exactly_the_mapped_constants(self):
        self.assertEqual(set(sm.metric_map(CONTRACT)), set(sm.metric_constants(MODEL_TEXT)))

    def test_every_metric_constant_is_its_contract_number(self):
        found = sm.metric_constants(MODEL_TEXT)
        for name, expected in sm.metric_map(CONTRACT).items():
            self.assertEqual(expected, found[name], name)

    def test_the_wide_glyph_ranges_are_the_contract_ranges(self):
        self.assertEqual([tuple(item) for item in CHOICE["wide_ranges"]], sm.kotlin_wide_ranges(MODEL_TEXT))

    def test_the_spec_numbers(self):
        # Spec 3.7: at most four segments; content at most 960 dp wide.
        self.assertEqual(4, CHOICE["segmented_max"])
        self.assertEqual(960, SETTINGS["metrics"]["content_max_width"])

    def test_the_segment_arithmetic_agrees_with_the_widths_of_the_kit_segmented_control(self):
        # DesktopSegmentedControl: 8 dp on each side of a label, 2 dp of track on each side, 2 dp between two segments.
        controls = source.read(source.K / "ui/kit/DesktopKitControls.kt")
        self.assertIn("Modifier.padding(horizontal = 8.dp)", controls)
        self.assertIn(".padding(2.dp)", controls)
        self.assertIn("horizontalArrangement = Arrangement.spacedBy(2.dp)", controls)
        self.assertEqual(2 * 8, CHOICE["segment_padding"])
        self.assertEqual(2 * 2, CHOICE["track_padding"])
        self.assertEqual(2, CHOICE["segment_gap"])

    def test_the_golden_rows_are_unique_and_cover_every_form(self):
        ids = [row["id"] for row in CHOICE["golden"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual({"SEGMENTED_INLINE", "SEGMENTED_STACKED", "RADIO_ROWS", "DROPDOWN"},
                         {row["expect"]["form"] for row in CHOICE["golden"]})


class ChoiceMirrorTest(unittest.TestCase):
    def test_every_golden_row_is_reproduced_by_the_mirror_of_the_kotlin_rule(self):
        choice = kotlin_choice()
        for row in CHOICE["golden"]:
            labels = [sm.make_label(item) for item in row["labels"]]
            form, control = choice.layout(row["presentation"], labels, row["available"])
            self.assertEqual((row["expect"]["form"], row["expect"]["control"]), (form, control), row["id"])

    def test_every_golden_row_is_reproduced_by_the_oracle(self):
        choice = kotlin_choice()
        for row in CHOICE["golden"]:
            widths = [choice.label_width(sm.make_label(item)) for item in row["labels"]]
            self.assertEqual(row["expect"]["form"], sm.oracle_form(CHOICE, row["presentation"], widths, row["available"]), row["id"])

    def test_the_glyph_cases_are_the_widths_of_one_utf16_unit(self):
        choice = kotlin_choice()
        for case in CHOICE["glyph_cases"]:
            self.assertEqual(case["width"], choice.glyph_width(case["code"]), hex(case["code"]))
            self.assertEqual(case["width"], choice.label_width(chr(case["code"])), hex(case["code"]))

    def test_a_character_outside_the_basic_plane_counts_as_two_narrow_units(self):
        choice = kotlin_choice()
        self.assertEqual(2 * CHOICE["glyph"]["narrow"], choice.label_width("\U0001F600"))

    def test_spec_rows_by_hand(self):
        # The fixed 320 dp native popover (284 dp for its content: DesktopWindowsPlayerMenu pads its scroll column with
        # end = 12 dp, and the NVIDIA content column pads 12 dp on each side): the content choice does not fit three
        # segments, the strength choice fits three and stacks. The codec choice of the playback page sits beside its title.
        choice = kotlin_choice()
        content = ["清晰度", "清晰度 + HDR 自动", "仅 HDR 自动"]
        strength = ["轻柔", "均衡", "完整"]
        codec = ["AVC / H.264", "HEVC / H.265", "AV1"]
        self.assertEqual(114, choice.label_width(content[1]))
        self.assertEqual(398, choice.segmented_width(content))
        self.assertEqual(("RADIO_ROWS", 284), choice.layout("AUTO", content, 284))
        self.assertEqual(176, choice.segmented_width(strength))
        self.assertEqual(("SEGMENTED_STACKED", 176), choice.layout("AUTO", strength, 284))
        self.assertEqual(320, choice.segmented_width(codec))
        self.assertEqual(("SEGMENTED_INLINE", 320), choice.layout("AUTO", codec, 936))

    def test_the_default_quality_list_is_six_and_only_the_dropdown_shows_it_whole(self):
        # Spec 3.7: DROPDOWN only for the six-value default-quality choice. The list is pinned by the quality label test.
        text = sm.read(sm.PLAYBACK_SETTINGS_KT)
        chips = re.search(r"desktopWindowsDefaultQualityChoices =\s*listOf\((.*?)\)\n", text, re.S).group(1)
        labels = re.findall(r'\d+ to "([^"]+)"', chips)
        self.assertEqual(["360P", "480P", "720P", "1080P", "1080P+", "4K"], labels)
        choice = kotlin_choice()
        self.assertEqual("DROPDOWN", choice.layout("DROPDOWN", labels, 936)[0])
        self.assertEqual("RADIO_ROWS", choice.layout("AUTO", labels, 936)[0])

    def test_mirror_and_oracle_agree_over_a_sweep(self):
        choice = kotlin_choice()
        label_sets = [[]]
        for count in range(1, 7):
            for glyphs in (1, 2, 5, 11, 30, 80):
                label_sets.append(["a" * glyphs] * count)
                label_sets.append(["中" * glyphs] * count)
        label_sets.append(["中", "a" * 40, " " * 9])
        label_sets.append(["AVC / H.264", "HEVC / H.265", "AV1"])
        checked = 0
        for labels, available, presentation in itertools.product(label_sets, range(-20, 1100, 9), ("AUTO", "DROPDOWN")):
            widths = [choice.label_width(label) for label in labels]
            form, control = choice.layout(presentation, labels, available)
            self.assertEqual(sm.oracle_form(CHOICE, presentation, widths, available), form, (labels, available, presentation))
            self.assertGreaterEqual(control, 0)
            if form.startswith("SEGMENTED"):
                self.assertEqual(choice.segmented_width(labels), control)
                self.assertLessEqual(control, available)
            checked += 1
        self.assertGreater(checked, 5000)

    def test_more_room_never_turns_a_segmented_control_into_radio_rows(self):
        choice = kotlin_choice()
        for labels in (["中中", "中中中", "ab"], ["AV1", "AVC / H.264"], ["a" * 30, "b" * 30, "c" * 5, "d"]):
            seen = False
            for available in range(0, 1500):
                form = choice.layout("AUTO", labels, available)[0]
                if form != "RADIO_ROWS":
                    seen = True
                else:
                    self.assertFalse(seen, (labels, available))
            self.assertTrue(seen)

    def test_the_inline_form_needs_the_title_room_and_the_control_cap(self):
        choice = kotlin_choice()
        labels = ["a" * 5, "b" * 5]
        needed = choice.segmented_width(labels)
        self.assertEqual(118, needed)
        self.assertEqual("SEGMENTED_STACKED", choice.layout("AUTO", labels, needed + CHOICE["inline_title_min"] - 1)[0])
        self.assertEqual("SEGMENTED_INLINE", choice.layout("AUTO", labels, needed + CHOICE["inline_title_min"])[0])


class KotlinChoiceSourceTest(unittest.TestCase):
    """The lines of the Kotlin rule that the mirror transliterates."""

    @classmethod
    def setUpClass(cls):
        cls.text = sm.squash(MODEL_TEXT)

    def test_the_label_width_is_the_sum_over_the_utf16_units(self):
        self.assertIn("internal fun desktopSettingsLabelWidthDp(label: String): Int = label.sumOf { desktopSettingsGlyphWidthDp(it) }", self.text)
        self.assertIn("glyph == ' ' -> DESKTOP_SETTINGS_GLYPH_SPACE_DP", self.text)
        self.assertIn("else -> DESKTOP_SETTINGS_GLYPH_NARROW_DP", self.text)

    def test_the_segmented_width(self):
        self.assertIn("if (labels.isEmpty()) return 0", self.text)
        self.assertIn("val widest = labels.maxOf { desktopSettingsLabelWidthDp(it) }", self.text)
        self.assertIn("val segment = maxOf(widest + DESKTOP_SETTINGS_SEGMENT_PADDING_DP, DESKTOP_SETTINGS_SEGMENT_MIN_DP)", self.text)
        self.assertIn("return labels.size * segment + DESKTOP_SETTINGS_TRACK_PADDING_DP + DESKTOP_SETTINGS_SEGMENT_GAP_DP * (labels.size - 1)", self.text)

    def test_the_form_rule(self):
        self.assertIn("val available = availableWidthDp.coerceAtLeast(0)", self.text)
        self.assertIn("if (presentation == DesktopWindowsSettingsChoicePresentation.DROPDOWN) { return DesktopSettingsChoiceLayout(DesktopSettingsChoiceForm.DROPDOWN, available) }", self.text)
        self.assertIn("if (labels.isEmpty() || labels.size > DESKTOP_SETTINGS_SEGMENTED_MAX) { return DesktopSettingsChoiceLayout(DesktopSettingsChoiceForm.RADIO_ROWS, available) }", self.text)
        self.assertIn("val needed = desktopSettingsSegmentedWidthDp(labels)", self.text)
        self.assertIn("if (needed > available) return DesktopSettingsChoiceLayout(DesktopSettingsChoiceForm.RADIO_ROWS, available)", self.text)
        self.assertIn("val inline = needed <= DESKTOP_SETTINGS_INLINE_CONTROL_MAX_DP && available - needed >= DESKTOP_SETTINGS_INLINE_TITLE_MIN_DP", self.text)
        self.assertIn("if (inline) DesktopSettingsChoiceForm.SEGMENTED_INLINE else DesktopSettingsChoiceForm.SEGMENTED_STACKED, needed,", self.text)

    def test_the_forms_and_presentations_are_the_spec_ones(self):
        self.assertIn("internal enum class DesktopWindowsSettingsChoicePresentation { AUTO, DROPDOWN }", self.text)
        self.assertIn("internal enum class DesktopSettingsChoiceForm { SEGMENTED_INLINE, SEGMENTED_STACKED, RADIO_ROWS, DROPDOWN }", self.text)

    def test_the_model_is_pure(self):
        code = source.mask_kotlin(MODEL_TEXT)
        self.assertNotIn("androidx.", code)
        self.assertNotIn("java.awt", code)
        self.assertNotIn("@Composable", code)


class TreeModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tree = sm.Tree()

    def test_the_rail_and_the_default_are_the_contract_ones(self):
        self.assertEqual(TREE_SECTION["rail"], self.tree.rail)
        self.assertEqual(TREE_SECTION["default_category"], self.tree.default)
        self.assertEqual(self.tree.rail[0], self.tree.default)

    def test_the_rail_titles_are_the_titles_the_tree_always_had_in_the_same_order(self):
        titles = sm.rail_titles(sm.read(sm.STRINGS_KT))
        self.assertEqual(list(OLD_RAIL_TITLES), [titles[category] for category in self.tree.rail])
        self.assertEqual(set(self.tree.rail), set(titles))

    def test_every_rail_category_is_a_real_category_and_canonical(self):
        canonical, owner = sm.vendored_category_policy()
        values = set(owner.values()) | set(canonical.values())
        for category in self.tree.rail:
            self.assertIn(category, values)
            self.assertNotIn(category, canonical)

    def test_direct_details_are_the_contract_ones_over_every_category(self):
        direct = TREE_SECTION["direct_detail"]
        canonical, owner = sm.vendored_category_policy()
        every = set(owner.values()) | set(canonical) | set(canonical.values())
        for category in sorted(every):
            expected = direct.get(self.tree.canonical(category))
            self.assertEqual(expected, self.tree.direct_detail(category), category)

    def test_the_original_direct_rule_is_appearance_and_plugins_only(self):
        self.assertEqual({"APPEARANCE_THEME": "APPEARANCE", "PLUGINS_EXTENSIONS": "PLUGINS"}, self.tree.original_direct)

    def test_a_rail_target_is_the_direct_detail_or_the_category_page(self):
        for category in self.tree.rail:
            detail = TREE_SECTION["direct_detail"].get(category)
            expected = ("Detail", detail) if detail else ("Category", category)
            self.assertEqual(expected, self.tree.rail_target(category))

    def test_the_category_of_a_page(self):
        tree = self.tree
        self.assertIsNone(tree.page_category(("Root",)))
        self.assertIsNone(tree.page_category(("Search",)))
        self.assertEqual("PLAYBACK_QUALITY", tree.page_category(("Category", "CONTENT_PLAYBACK")))
        self.assertEqual("PLAYBACK_QUALITY", tree.page_category(("Detail", "PLAYBACK")))
        self.assertEqual("HOME_RECOMMENDATION", tree.page_category(("Detail", "HOME_FEED")))
        self.assertEqual("PRIVACY_PERMISSION", tree.page_category(("Detail", "MESSAGE_NOTIFICATION")))
        self.assertEqual("PRIVACY_PERMISSION", tree.page_category(("CommentFraud",)))
        self.assertEqual("STORAGE_BACKUP", tree.page_category(("Detail", "WEBDAV_BACKUP")))
        self.assertEqual("SYSTEM_ABOUT", tree.page_category(("Detail", "TIPS")))
        self.assertIsNone(tree.page_category(("Detail", "ANIMATION")))
        self.assertIsNone(tree.page_category(("Category", "NAVIGATION_INTERACTION")))

    def test_the_system_category_is_the_only_one_that_is_not_landable(self):
        for category in self.tree.rail:
            landable = self.tree.landable(("Category", category))
            self.assertEqual(category not in TREE_SECTION["not_landable"], landable is not None, category)
        self.assertEqual(["SYSTEM_ABOUT"], TREE_SECTION["not_landable"])

    def test_replace_stack_cases(self):
        replace = sm.Tree.replace_stack
        root, search = ("Root",), ("Search",)
        category, detail, target = ("Category", "PRIVACY_PERMISSION"), ("Detail", "MESSAGE_NOTIFICATION"), ("Detail", "PLUGINS")
        self.assertEqual([root, target], replace([root], target))
        self.assertEqual([root, target], replace([root, category, detail], target))
        self.assertEqual([root, search, target], replace([root, search], target))
        self.assertEqual([root, search, target], replace([root, search, category], target))
        self.assertEqual([root, category, search, target], replace([root, category, search], target))

    def test_replace_stack_agrees_with_a_recursive_oracle_and_keeps_its_invariants(self):
        def oracle(stack, target):
            if len(stack) > 1 and stack[-1][0] in ("Category", "Detail"):
                return oracle(stack[:-1], target)
            return stack + [target]

        pages = [("Search",), ("Category", "PRIVACY_PERMISSION"), ("Detail", "PLAYBACK"), ("CommentFraud",)]
        target = ("Detail", "HOME_FEED")
        for length in range(0, 6):
            for tail in itertools.product(pages, repeat=length):
                stack = [("Root",)] + list(tail)
                result = sm.Tree.replace_stack(stack, target)
                self.assertEqual(oracle(stack, target), result)
                self.assertEqual(("Root",), result[0])
                self.assertEqual(target, result[-1])
                self.assertEqual(result, sm.Tree.replace_stack(result[:-1], target))

    def test_every_contract_scenario_replays_to_its_recorded_state(self):
        scenarios = TREE_SECTION["scenarios"]
        self.assertGreaterEqual(len(scenarios), 12)
        ids = [scenario["id"] for scenario in scenarios]
        self.assertEqual(len(ids), len(set(ids)))
        for scenario in scenarios:
            self.assertEqual(scenario["expect"], sm.Navigator(self.tree).run(scenario["steps"]).describe(), scenario["id"])

    def test_scenario_facts_by_hand(self):
        run = lambda steps: sm.Navigator(self.tree).run(steps).describe()  # noqa: E731
        # A fresh visit lands on the playback and audio page, and a click on another category replaces it.
        self.assertEqual(["Root", "Detail:PLAYBACK"], run(["root", "land"])["stack"])
        self.assertEqual(["Root", "Category:PRIVACY_PERMISSION"], run(["root", "land", "rail:PRIVACY_PERMISSION"])["stack"])
        # Back from a rail page is one pop to the root, whatever was visited before.
        walked = run(["root", "land", "rail:PRIVACY_PERMISSION", "rail:STORAGE_BACKUP", "rail:HOME_RECOMMENDATION", "pop"])
        self.assertEqual(["Root"], walked["stack"])
        # The landing is consumed once: a second look at the root does not land again by itself.
        self.assertFalse(walked["pending"])
        self.assertEqual(["Root"], run(["root", "land", "pop", "land"])["stack"])

    def test_a_rail_click_is_redundant_only_when_the_open_page_is_the_page_it_would_open(self):
        clicks = TREE_SECTION["rail_clicks"]
        self.assertGreaterEqual(len(clicks), 6)
        self.assertEqual(len(clicks), len({click["id"] for click in clicks}))
        for click in clicks:
            page = tuple(click["page"].split(":"))
            self.assertEqual(click["redundant"], self.tree.rail_click_redundant(page, click["category"]), click["id"])
        # A page under the clicked category is not that page (the click opens the category page), and the root never is.
        self.assertFalse(self.tree.rail_click_redundant(("Detail", "MESSAGE_NOTIFICATION"), "PRIVACY_PERMISSION"))
        self.assertFalse(self.tree.rail_click_redundant(("Root",), "PLAYBACK_QUALITY"))
        # Both outcomes occur, and the redundant ones are exactly the clicks whose replace would change nothing here.
        self.assertEqual({True, False}, {click["redundant"] for click in clicks})
        for click in clicks:
            page = tuple(click["page"].split(":"))
            if page == ("Root",):
                continue
            navigator = sm.Navigator(self.tree)
            navigator.stack = [("Root",), page]
            before = list(navigator.stack)
            navigator.replace_category(click["category"])
            self.assertEqual(click["redundant"], navigator.stack == before, click["id"])

    def test_a_refused_detail_changes_nothing(self):
        navigator = sm.Navigator(self.tree, can_open_detail=lambda target: target != "PLAYBACK")
        navigator.run(["root", "rail:PLAYBACK_QUALITY", "land"])
        self.assertEqual({"stack": ["Root"], "last": "PLAYBACK_QUALITY", "pending": True}, navigator.describe())


class KotlinTreeSourceTest(unittest.TestCase):
    """The lines of the Kotlin tree rules and navigator that the mirrors transliterate."""

    @classmethod
    def setUpClass(cls):
        cls.model = sm.squash(TREE_MODEL_TEXT)
        cls.navigator = sm.squash(NAVIGATOR_TEXT)

    def test_the_rail_block_is_marked_once(self):
        sm.marked_block(TREE_MODEL_TEXT, "SETTINGS-RAIL")

    def test_the_category_of_a_page(self):
        self.assertIn("is DesktopSettingsPage.Category -> canonicalSettingsRootCategory(page.category)", self.model)
        self.assertIn("is DesktopSettingsPage.Detail -> resolveSettingsRootCategoryForSearchTarget(page.target)?.let { canonicalSettingsRootCategory(it) }", self.model)
        self.assertIn("is DesktopSettingsPage.CommentFraudHistory -> SettingsRootCategory.PRIVACY_PERMISSION", self.model)
        self.assertIn("return category?.takeIf { it in DESKTOP_SETTINGS_RAIL_CATEGORIES }", self.model)

    def test_landable_direct_target_and_replace(self):
        self.assertIn("desktopSettingsPageCategory(page)?.takeIf { it != SettingsRootCategory.SYSTEM_ABOUT }", self.model)
        self.assertIn("return resolveDesktopSettingsCategoryDirectTarget(canonical) ?: when (canonical) {", self.model)
        self.assertIn("SettingsRootCategory.PLAYBACK_QUALITY -> SettingsSearchTarget.PLAYBACK", self.model)
        self.assertIn("SettingsRootCategory.HOME_RECOMMENDATION -> SettingsSearchTarget.HOME_FEED", self.model)
        self.assertIn("return if (detail != null) DesktopSettingsPage.Detail(detail, null) else DesktopSettingsPage.Category(canonical)", self.model)
        self.assertIn("while (end > 1 && (stack[end - 1] is DesktopSettingsPage.Category || stack[end - 1] is DesktopSettingsPage.Detail)) end -= 1", self.model)
        self.assertIn("return stack.subList(0, end) + target", self.model)

    def test_the_rail_click_check_is_the_open_page_against_the_rail_target(self):
        self.assertIn("internal fun desktopSettingsRailClickIsRedundant(page: DesktopSettingsPage, category: SettingsRootCategory): Boolean = "
                      "page == desktopSettingsRailTarget(category)", self.model)

    def test_the_navigator_replace_land_note_and_pending(self):
        text = self.navigator
        self.assertIn("mutableState.value = DesktopSettingsNavigationState(listOf(DesktopSettingsPage.Root), landingPending = true)", text)
        self.assertIn("val target = desktopSettingsRailTarget(category)", text)
        self.assertIn("if (target is DesktopSettingsPage.Detail && !canOpenDetail(target.target)) return", text)
        self.assertIn("val stack = desktopSettingsReplaceStack(current.stack, target)", text)
        self.assertIn("if (stack == current.stack) return", text)
        self.assertIn("if (!current.landingPending || current.current != DesktopSettingsPage.Root) return", text)
        self.assertIn("replaceCategory(lastCategory)", text)
        self.assertIn("desktopSettingsLandableCategory(page)?.let { lastCategory = it }", text)
        self.assertEqual(1, text.count("private fun noteCategory("))

    def test_every_other_transition_builds_a_state_with_nothing_pending(self):
        # Only openRoot sets landingPending; the default of the data class is false, so a push, a pop or an activation ends it.
        self.assertEqual(1, NAVIGATOR_TEXT.count("landingPending = true"))
        self.assertIn("val landingPending: Boolean = false", NAVIGATOR_TEXT)

    def test_the_pinned_original_rule_is_untouched(self):
        text = self.navigator
        self.assertIn("val canonical = canonicalSettingsRootCategory(category) val direct = resolveDesktopSettingsCategoryDirectTarget(canonical) "
                      "if (direct != null) openDetail(direct, null) else { SettingsSearchFocusController.clear() push(DesktopSettingsPage.Category(canonical)) }", text)
        self.assertIn("fun leave() = openRoot()", text)
        self.assertIn("if (stack.size <= 1) return false", text)

    def test_the_tree_model_is_pure(self):
        code = source.mask_kotlin(TREE_MODEL_TEXT)
        for needle in ("androidx.", "java.awt", "@Composable"):
            self.assertNotIn(needle, code)


class LfAndEncodingTest(unittest.TestCase):
    def test_the_files_of_the_model_are_lf_only_without_control_bytes(self):
        here = Path(__file__).resolve().parent
        source.assert_lf_only(self, sm.MODEL_KT, sm.TREE_MODEL_KT, sm.NAVIGATOR_KT, here / "ux_settings_model.py",
                              here / "test_ux_settings_model.py", model.CONTRACT_PATH, model.KOTLIN_COPY_PATH,
                              source.KT / "ui/DesktopWindowsSettingsModelTest.kt", source.KT / "settings/DesktopSettingsTreeModelTest.kt",
                              source.KT / "ui/DesktopWindowsShortcutListTest.kt")


if __name__ == "__main__":
    unittest.main()
