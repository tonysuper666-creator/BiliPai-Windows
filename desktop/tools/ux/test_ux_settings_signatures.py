"""Signatures, call sites and contracts of the settings chrome (slice X3, spec 10.1 "settings signature test"). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_settings_signatures.py

What is proved. The four public composables of ui/DesktopWindowsSettingsControls.kt (Pane, Group, Switch, Choice) keep
their parameter lists prefix-compatible: the old parameters, in order, with the old defaults, and anything new before
the trailing lambda with a default. Every call line of the six files that called them is still there (68 lines now: 67 when the controls were swapped and one more for the next-watch switch, the
spec says 66; the extra line is the one the spec counted differently), every named argument they pass is a parameter,
and the one RH10 edit in the Root-reserved DesktopWindowsPlaybackSettings.kt is the only use of the select row. The
catalog and its focus anchors, the six default quality choices, the NVIDIA call lines and the pinned navigator test are
untouched. The settings header keeps one accessible Back action left of the page title and the search action on the
right, which the Windows fixtures click by name. The controls are the component kit: no Material chip, switch, text
button or card in the re-skinned files, and the hygiene allowlist names no settings file any more.

What is not proved. That the Kotlin compiles or runs, and how the controls look; the four settings fixtures and the
NVIDIA fixture are Windows runs.
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_api_probe as probe  # noqa: E402
import ux_model as model  # noqa: E402
import ux_settings_model as sm  # noqa: E402
import ux_source as source  # noqa: E402

K = source.K
KT = source.KT
CONTROLS = sm.read(sm.CONTROLS_KT)
TREE = sm.read(sm.TREE_KT)
TREE_CODE = source.mask_kotlin(TREE)
CONTROLS_CODE = source.mask_kotlin(CONTROLS)
NAVIGATOR = sm.read(sm.NAVIGATOR_KT)
PLAYBACK = sm.read(sm.PLAYBACK_SETTINGS_KT)
CALL = re.compile(r"DesktopWindowsSettings(Pane|Group|Switch|Choice)\(")

# The parameter lists before slice X3: (name, has a default) in order.
OLD_SIGNATURES = {
    "DesktopWindowsSettingsPane": [("title", False), ("onBack", False), ("content", False)],
    "DesktopWindowsSettingsGroup": [("title", False), ("modifier", True), ("content", False)],
    "DesktopWindowsSettingsSwitch": [("title", False), ("value", False), ("enabled", True), ("description", True), ("onChange", False)],
    "DesktopWindowsSettingsChoice": [("title", False), ("value", False), ("choices", False), ("enabled", True),
                                     ("allowUnselected", True), ("onChange", False)],
}
OLD_TREE_PARAMETERS = [
    ("navigator", False), ("search", False), ("historyWritesScope", False), ("discovery", False), ("privacy", False),
    ("onFailure", False), ("onDetailBack", True), ("appearanceContent", False), ("pluginsContent", False),
    ("playbackContent", False), ("backupContent", False), ("blockedListContent", False), ("donateContent", False),
    ("commentFraudHistoryContent", False), ("homeContent", False), ("systemContent", False), ("onCategoryOpen", True),
    ("onOpenSearch", True), ("onSearchResult", True), ("onPageBack", True), ("storageContent", False),
    ("imageSavePathContent", True),
]
# The call lines of the files that called the controls before slice X3, per file (relative to K).
OLD_CALL_LINES = {
    "settings/DesktopPrivacySection.kt": 5,
    "ui/DesktopOriginalSystemAboutRootHost.kt": 4,
    "ui/DesktopVideoEnhancementControls.kt": 3,
    "ui/DesktopWindowsHomeSettings.kt": 23,
    "ui/DesktopWindowsPlaybackSettings.kt": 29,  # 28 when the controls were swapped, +1: the next-watch card switch (vid-next-watch-and-toggle)
    "ui/DesktopWindowsTipsSettings.kt": 4,
}
# Call lines that slice X3 itself adds, in the files it creates or rewrites.
NEW_CALL_LINES = {
    "ui/DesktopWindowsUxSettings.kt": 4,
    "ui/DesktopWindowsTipsShortcuts.kt": 3,
    "settings/DesktopSettingsTree.kt": 1,
}


def call_lines(path):
    code = source.mask_kotlin(sm.read(path))
    return sum(1 for line in code.splitlines() if CALL.search(line))


def kotlin_files_with_calls():
    found = {}
    for path in sorted(K.rglob("*.kt")):
        if path == sm.CONTROLS_KT:
            continue
        count = call_lines(path)
        if count:
            found[source.rel(path)] = count
    return found


class SignatureTest(unittest.TestCase):
    def signature(self, name):
        found = probe.function_signatures(CONTROLS, name)
        self.assertEqual(1, len(found), name)
        return found[0]

    def test_each_old_parameter_list_is_a_prefix_of_the_new_one_up_to_the_trailing_lambda(self):
        for name, old in OLD_SIGNATURES.items():
            new = self.signature(name)
            self.assertEqual(old[-1], new[-1], name + ": the trailing lambda stays last, so every call keeps its lambda syntax")
            self.assertEqual(old[:-1], new[:len(old) - 1], name + ": the old parameters keep their order and defaults")
            for parameter, has_default in new[len(old) - 1:-1]:
                self.assertTrue(has_default, "%s: new parameter %s needs a default" % (name, parameter))

    def test_only_the_choice_gains_a_parameter_and_it_is_the_presentation(self):
        for name, old in OLD_SIGNATURES.items():
            added = [parameter for parameter, _ in self.signature(name)[len(old) - 1:-1]]
            self.assertEqual(["presentation"] if name == "DesktopWindowsSettingsChoice" else [], added, name)

    def test_the_presentation_defaults_to_auto_and_the_choice_stays_generic(self):
        self.assertIn("presentation: DesktopWindowsSettingsChoicePresentation = DesktopWindowsSettingsChoicePresentation.AUTO,",
                      sm.squash(CONTROLS))
        self.assertIn("internal fun <T> DesktopWindowsSettingsChoice(title: String, value: T?, choices: List<Pair<T, String>>,", sm.squash(CONTROLS))

    def test_the_loading_helper_keeps_its_signature(self):
        self.assertIn("internal fun <T> desktopWindowsSettingsValue(flow: Flow<T>): T? = flow.collectAsState(initial = null).value", CONTROLS)

    def test_the_settings_tree_gains_one_trailing_defaulted_parameter(self):
        found = probe.function_signatures(TREE, "DesktopSettingsTree")
        self.assertEqual(1, len(found))
        parameters = found[0]
        self.assertEqual(OLD_TREE_PARAMETERS, parameters[:len(OLD_TREE_PARAMETERS)])
        self.assertEqual([("onCategorySelect", True)], parameters[len(OLD_TREE_PARAMETERS):])


class CallSiteTest(unittest.TestCase):
    def test_the_files_that_called_the_controls_keep_every_call_line(self):
        found = kotlin_files_with_calls()
        for name, count in OLD_CALL_LINES.items():
            self.assertEqual(count, found.get(name), name)
        self.assertEqual(68, sum(OLD_CALL_LINES.values()))

    def test_no_other_file_calls_them_except_the_files_of_this_slice(self):
        found = kotlin_files_with_calls()
        expected = dict(OLD_CALL_LINES)
        expected.update(NEW_CALL_LINES)
        self.assertEqual(expected, found)

    def test_every_named_argument_of_every_call_is_a_parameter(self):
        allowed = {name: {parameter for parameter, _ in signature} for name, signature in OLD_SIGNATURES.items()}
        allowed["DesktopWindowsSettingsChoice"].add("presentation")
        for path in sorted(K.rglob("*.kt")):
            if path == sm.CONTROLS_KT:
                continue
            text = sm.read(path)
            for name in OLD_SIGNATURES:
                for named, _positional, _trailing in probe.calls(text, name):
                    self.assertTrue(set(named) <= allowed[name], "%s calls %s with %s" % (source.rel(path), name, sorted(set(named) - allowed[name])))

    def test_a_call_passes_at_most_the_positional_arguments_before_the_old_optional_ones(self):
        limits = {"DesktopWindowsSettingsPane": 3, "DesktopWindowsSettingsGroup": 2, "DesktopWindowsSettingsSwitch": 3,
                  "DesktopWindowsSettingsChoice": 3}
        for path in sorted(K.rglob("*.kt")):
            if path == sm.CONTROLS_KT:
                continue
            text = sm.read(path)
            for name, limit in limits.items():
                for named, positional, _trailing in probe.calls(text, name):
                    self.assertLessEqual(positional, limit, "%s calls %s" % (source.rel(path), name))

    def test_the_select_row_is_used_once_by_the_one_line_rh10_edit_of_the_reserved_playback_file(self):
        uses = []
        for path in sorted(K.rglob("*.kt")):
            code = source.mask_kotlin(sm.read(path))
            uses += [source.rel(path)] * len(re.findall(r"presentation\s*=\s*DesktopWindowsSettingsChoicePresentation\.DROPDOWN", code))
        self.assertEqual(["ui/DesktopWindowsPlaybackSettings.kt"], uses)
        call = PLAYBACK[PLAYBACK.index('DesktopWindowsSettingsChoice("默认画质"'):]
        call = call[:call.index("{ value ->")]
        self.assertIn("desktopWindowsDefaultQualityChoices", call)
        self.assertIn("enabled = highest == false && qualityReady, allowUnselected = qualityReady, presentation = DesktopWindowsSettingsChoicePresentation.DROPDOWN)", call)
        self.assertEqual(1, PLAYBACK.count("DesktopWindowsSettingsChoicePresentation.DROPDOWN"))

    def test_the_default_quality_call_is_the_only_edit_of_the_reserved_playback_file(self):
        # Everything the quality label test and the focus anchors pin is still there, the other choices untouched.
        self.assertIn('internal val desktopWindowsDefaultQualityChoices =\n    listOf(16 to "360P", 32 to "480P", 64 to "720P", 80 to "1080P", 112 to "1080P+", 120 to "4K")', PLAYBACK)
        self.assertIn("internal fun desktopWindowsOffListQualityCaption(quality: Int?): String? {", PLAYBACK)
        self.assertEqual(1, len(re.findall(r"allowUnselected = qualityReady", PLAYBACK)))
        for anchor in ("windows_playback_quality", "windows_playback_speed_subtitle", "windows_playback_behavior",
                       "windows_playback_comments", "windows_audio_output", "windows_video_enhancement"):
            self.assertEqual(1, PLAYBACK.count('"%s"' % anchor), anchor)


class CatalogAndNvidiaTest(unittest.TestCase):
    def test_the_catalog_keeps_its_eight_focus_anchors(self):
        text = sm.read(K / "settings/DesktopWindowsSettingsCatalog.kt")
        focus = re.findall(r'DesktopWindowsSettingsSearchEntry\(SettingsSearchTarget\.(\w+),.*?"(windows_[a-z_]+)"\),\n', text)
        self.assertEqual(8, len(focus))
        self.assertEqual({("PLAYBACK", "windows_playback_quality"), ("PLAYBACK", "windows_playback_speed_subtitle"),
                          ("PLAYBACK", "windows_playback_behavior"), ("PLAYBACK", "windows_playback_comments"),
                          ("PLAYBACK", "windows_audio_output"), ("APPEARANCE", "windows_display_scale"),
                          ("PLAYBACK", "windows_video_enhancement")}, set(focus))

    def test_the_display_scale_card_keeps_its_anchor_and_calls_the_layout_group_with_one_line(self):
        text = sm.read(sm.DISPLAY_SCALE_KT)
        self.assertEqual(1, text.count('desktopSettingsSearchFocusAnchor(SettingsSearchTarget.APPEARANCE, "windows_display_scale")'))
        self.assertEqual(1, text.count("com.bilipai.desktop.ui.DesktopWindowsUxLayoutGroup()"))
        # The definition is in the group file; the display scale card is the only caller anywhere in the program.
        mentions = [path for path in sorted(K.rglob("*.kt")) if "DesktopWindowsUxLayoutGroup" in source.mask_kotlin(sm.read(path))]
        self.assertEqual(sorted([sm.DISPLAY_SCALE_KT, sm.UX_GROUP_KT]), mentions)
        self.assertEqual(0, source.mask_kotlin(sm.read(sm.UX_GROUP_KT)).count("DesktopWindowsUxLayoutGroup()") - 1)
        self.assertIn("Card(Modifier.fillMaxWidth().desktopSettingsSearchFocusAnchor(", text)

    def test_the_nvidia_popover_keeps_its_three_call_lines_and_is_not_edited(self):
        text = sm.read(K / "ui/DesktopVideoEnhancementControls.kt")
        compact = sm.squash(text)
        self.assertIn('DesktopWindowsSettingsChoice("增强内容", preferences.content,', compact)
        self.assertIn('DesktopWindowsSettingsChoice("效果强度", preferences.intensity,', compact)
        self.assertIn('if (showProcessingQuality) DesktopWindowsSettingsChoice("质量上限", preferences.quality,', compact)
        self.assertNotIn("DesktopWindowsSettingsChoicePresentation", text)
        self.assertIn('title = "NVIDIA 自动增强"', text)
        self.assertTrue((source.HERE / ".." / "nvidia" / "test_nvidia_ui_strings.py").resolve().is_file())

    def test_the_nvidia_choices_lose_the_material_chips_through_the_new_choice(self):
        # Content and strength reach DesktopSegmentedControl or the radio rows; the chips are gone from the controls file.
        self.assertIn("DesktopSegmentedControl(", CONTROLS_CODE)
        self.assertIn("DesktopSettingsRadioRow(", CONTROLS_CODE)
        for banned in ("FilterChip", "FlowRow", "ExperimentalLayoutApi"):
            self.assertNotIn(banned, CONTROLS_CODE)

    def test_the_default_quality_choices_and_the_pinned_tests_are_in_place(self):
        names = re.findall(r"@Test fun `?([^`(]+)`?\(?", sm.read(KT / "ui/DesktopWindowsPlaybackSettingsQualityLabelTest.kt"))
        self.assertEqual(3, len(names))
        navigator_test = sm.read(KT / "settings/DesktopSettingsNavigatorTest.kt")
        self.assertEqual(["categoryActionsDoNotProjectToAnUnrelatedDetail", "detailBackKeepsSearchEntryWhileANewSearchGetsANewToken",
                          "originalCategoriesDirectlyOpenOnlyTheirOriginalDestinations",
                          "leavingTheSettingsSubtreeRetiresSearchAndPendingFocus",
                          "deniedBackupEntryDoesNotPushOrChangeExistingSearchFocus"],
                         re.findall(r"@Test fun (\w+)\(", navigator_test))


class HeaderContractTest(unittest.TestCase):
    """Four fixtures (Home, Icon, Playback, TypedPlayback) click one accessible Back action by its name, left of the title."""

    FIXTURES = ("OriginalHomeSettingsRootUiFixture.kt", "OriginalIconSettingsRootUiFixture.kt",
                "OriginalPlaybackSettingsRootUiFixture.kt", "OriginalTypedPlaybackSettingsRootUiFixture.kt")

    def test_the_fixtures_still_look_for_the_name_with_exactly_one_action(self):
        for name in self.FIXTURES:
            text = sm.read(KT / "ui" / name)
            self.assertIn('"返回"', text, name)
            self.assertIn("accessibleActionCount ?: 0) == 1", text, name)
        home = sm.read(KT / "ui/OriginalHomeSettingsRootUiFixture.kt")
        self.assertIn("bounds.maxX <= header.minX", home)
        # The NVIDIA fixture finds the one Back of the typed playback page by its row next to the title and left of it.
        video = sm.read(KT / "ui/WindowsVideoActualRootUiFixture.kt")
        self.assertIn('it.accessibleName == "播放与音频" &&', video)
        self.assertIn("kotlin.math.abs(position.y + control.size.height / 2 - center) <= 12 && position.x < point.x", video)

    def test_the_back_name_is_the_one_the_fixtures_click(self):
        strings = sm.read(sm.STRINGS_KT)
        self.assertIn('const val BACK = "返回"', strings)
        self.assertIn('const val SEARCH = "搜索设置"', strings)
        self.assertIn('const val TREE_TITLE = "Windows 设置"', strings)

    def test_the_header_row_is_back_then_title_then_search_in_one_row(self):
        start = TREE_CODE.index("private fun DesktopSettingsTreeHeader(")
        body = TREE_CODE[start:TREE_CODE.index("\n}\n", start)]
        self.assertEqual(1, len(re.findall(r"(?<![\w])Row\(", body)))
        back, title, search = body.index("DesktopSettingsBackButton(onBack)"), body.index("Text("), body.index("DesktopMenuRow(")
        self.assertLess(back, title)
        self.assertLess(title, search)
        self.assertIn("if (showBack) DesktopSettingsBackButton(onBack)", sm.squash(TREE))
        self.assertIn("verticalAlignment = Alignment.CenterVertically", body)
        self.assertEqual(1, TREE_CODE.count("DesktopSettingsBackButton("))

    def test_the_back_action_is_shown_whenever_a_page_is_open_as_it_was(self):
        self.assertIn("showBack = page != DesktopSettingsPage.Root,", sm.squash(TREE))

    def test_the_back_action_is_one_icon_button_named_back_and_the_search_action_one_menu_row(self):
        start = CONTROLS_CODE.index("internal fun DesktopSettingsBackButton(")
        body = CONTROLS_CODE[start:CONTROLS_CODE.index("\n}\n", start)]
        self.assertEqual(1, body.count("DesktopIconButton("))
        self.assertIn("label = DesktopWindowsSettingsStrings.BACK", sm.squash(body))
        self.assertIn("DesktopMenuRow(DesktopWindowsSettingsStrings.SEARCH, onSearch,", sm.squash(TREE))

    def test_a_pane_in_the_tree_hands_its_title_and_back_to_the_header_and_draws_no_row_of_its_own(self):
        start = CONTROLS_CODE.index("internal fun DesktopWindowsSettingsPane(")
        body = CONTROLS_CODE[start:CONTROLS_CODE.index("\n}\n", start)]
        squashed = sm.squash(body)
        self.assertIn("val header = LocalDesktopSettingsHeader.current", squashed)
        self.assertIn("header.title = title", squashed)
        self.assertIn("header.onBack = { latestBack() }", squashed)
        self.assertIn("if (header == null) DesktopSettingsPaneHeader(title, onBack)", squashed)
        self.assertNotIn("TextButton", body)
        # The tree uses the Pane's own Back when a Pane is mounted (it knows how its route is left), else the page Back.
        self.assertIn("onBack = { (header.onBack ?: onPageBack)() },", sm.squash(TREE))

    def test_the_content_is_at_most_960_dp_wide_and_left_aligned_in_the_pane_and_in_the_tree_pages(self):
        needle = "Modifier.widthIn(max = DESKTOP_SETTINGS_CONTENT_MAX_WIDTH_DP.dp).fillMaxWidth()"
        self.assertEqual(1, sm.squash(CONTROLS).count(needle))
        self.assertEqual(1, sm.squash(TREE).count(needle))
        self.assertNotIn("horizontalAlignment", CONTROLS_CODE.replace("horizontalArrangement", ""))
        self.assertNotIn("Alignment.CenterHorizontally", TREE_CODE + CONTROLS_CODE)


class KitControlsTest(unittest.TestCase):
    def test_the_controls_are_the_kit_and_not_the_material_look(self):
        for needle in ("DesktopToggleRow(", "DesktopSegmentedControl(", "DesktopSelectRow(", "DesktopPopover(", "DesktopMenuRow(",
                       "desktopKitHairlineBorder(", "DesktopKitVerticalScrollbar(scroll)"):
            self.assertIn(needle, CONTROLS_CODE)
        for banned in ("Card", "TextButton", "FilterChip", "Switch", "HorizontalDivider", "OutlinedButton", "AlertDialog", "DropdownMenu"):
            self.assertIsNone(re.search(r"(?<![\w])%s\b" % banned, CONTROLS_CODE), banned)
            self.assertIsNone(re.search(r"(?<![\w])%s\b" % banned, TREE_CODE), banned)

    def test_the_group_is_one_container_with_one_pixel_dividers_between_its_children(self):
        squashed = sm.squash(CONTROLS)
        self.assertEqual(1, CONTROLS_CODE.count("fun DesktopSettingsGroupLayout("))
        self.assertIn("size = Size(size.width, 1f)", squashed)
        self.assertIn("if (index > 0) { lines.add(y) y += 1 }", squashed)
        self.assertIn(".clip(shape).background(colors.surfaceContainerLow, shape)", squashed)
        self.assertIn(".desktopKitHairlineBorder(colors.outlineVariant, radius)", squashed)
        self.assertIn("val radius = DESKTOP_KIT_RADIUS_PANEL_DP.dp", squashed)
        # One container per group: the group draws one background; no nested card.
        self.assertEqual(1, CONTROLS_CODE.count(".background(colors.surfaceContainerLow"))

    def test_a_row_is_measured_exactly_and_every_other_child_keeps_the_width_it_asks_for(self):
        # A button (the update and help groups, the background group) must stay wrap-content and start aligned; only a
        # settings row spans the container. The ImageComposeScene test DesktopWindowsSettingsGroupUiTest runs it.
        squashed = sm.squash(CONTROLS)
        self.assertIn("val limits = if (isRow) Constraints(minWidth = inner, maxWidth = inner, minHeight = 0, maxHeight = Constraints.Infinity) "
                      "else Constraints(minWidth = 0, maxWidth = inner, minHeight = 0, maxHeight = Constraints.Infinity)", squashed)
        self.assertIn("val placeable = measurable.measure(limits)", squashed)
        self.assertEqual(1, CONTROLS_CODE.count("minWidth = inner"))
        self.assertEqual(2, CONTROLS_CODE.count("maxWidth = inner"))
        self.assertEqual(2, CONTROLS_CODE.count("minWidth = "))
        self.assertIn("val side = if (isRow) 0 else inset", squashed)
        self.assertIn("val padding = if (isRow) 0 else vertical", squashed)
        self.assertIn("placed.add(Triple(placeable, side, y))", squashed)

    def test_only_the_kit_toggle_row_and_the_select_row_are_rows_and_the_choice_pads_nothing_at_the_sides(self):
        self.assertEqual(2, CONTROLS_CODE.count("layoutId(DESKTOP_SETTINGS_ROW_ID)"))
        start = CONTROLS_CODE.index("internal fun <T> DesktopWindowsSettingsChoice(")
        body = CONTROLS_CODE[start:CONTROLS_CODE.index("\n}\n", start)]
        squashed = sm.squash(body)
        self.assertNotIn("layoutId", body)
        self.assertNotIn("horizontal =", body)
        self.assertNotIn("side", body)
        # The group is read only for the vertical gap: a choice nested in a wrapper of the group adds no second inset.
        self.assertEqual(1, body.count("LocalDesktopSettingsInGroup.current"))
        self.assertIn("val vertical = if (LocalDesktopSettingsInGroup.current) 0 else DESKTOP_SETTINGS_ROW_PADDING_VERTICAL_DP", squashed)
        self.assertIn("Column(Modifier.fillMaxWidth().padding(vertical = vertical.dp)) {", squashed)
        self.assertEqual(1, CONTROLS_CODE.count("LocalDesktopSettingsInGroup.current"))

    def test_a_radio_row_grows_with_a_two_line_label_instead_of_clipping_it(self):
        start = CONTROLS_CODE.index("private fun DesktopSettingsRadioRow(")
        body = CONTROLS_CODE[start:CONTROLS_CODE.index("\n}\n", start)]
        squashed = sm.squash(body)
        self.assertIn(".heightIn(min = DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP.dp)", squashed)
        self.assertNotIn(".height(", body)
        self.assertIn(".padding(horizontal = 8.dp, vertical = 4.dp),", squashed)
        self.assertIn("maxLines = 2", squashed)

    def test_the_switch_is_the_kit_toggle_row_with_the_loading_row_of_the_kit(self):
        start = CONTROLS_CODE.index("internal fun DesktopWindowsSettingsSwitch(")
        body = sm.squash(CONTROLS_CODE[start:CONTROLS_CODE.index("\n}\n", start)])
        self.assertIn("DesktopToggleRow(title = title, checked = value, onCheckedChange = onChange,", body)
        kit = sm.read(K / "ui/kit/DesktopKitStrings.kt")
        self.assertIn('const val LOADING_SETTING = "读取设置…"', kit)

    def test_the_choice_keeps_what_the_chips_selected(self):
        squashed = sm.squash(CONTROLS)
        self.assertIn("val pick: (T) -> Unit = { candidate -> if (value != candidate) onChange(candidate) }", squashed)
        self.assertIn("val interactive = enabled && (value != null || allowUnselected)", squashed)
        self.assertIn("enabled = enabled && (value != null || allowUnselected),", squashed)
        self.assertIn("DesktopSettingsRadioRow(label, value == candidate, interactive) { pick(candidate) }", squashed)

    def test_the_form_comes_from_the_pure_rule_and_the_dropdown_only_from_the_caller(self):
        squashed = sm.squash(CONTROLS)
        self.assertIn("desktopSettingsChoiceLayout(presentation, choices.map { it.second }, maxWidth.value.toInt())", squashed)
        self.assertIn("if (presentation == DesktopWindowsSettingsChoicePresentation.DROPDOWN) {", squashed)
        self.assertEqual(1, CONTROLS_CODE.count("DesktopSettingsChoiceForm.SEGMENTED_INLINE ->"))
        self.assertEqual(1, CONTROLS_CODE.count("DesktopSettingsChoiceForm.SEGMENTED_STACKED ->"))
        # A radio row is one selectable node with the radio role; the group is one selectable group.
        self.assertIn("role = Role.RadioButton", squashed)
        self.assertIn("Modifier.selectableGroup()", squashed)

    def test_the_select_row_shows_a_placeholder_for_a_stored_value_that_no_choice_has(self):
        squashed = sm.squash(CONTROLS)
        self.assertIn("val valueLabel = selectedLabel ?: if (value != null || allowUnselected) DesktopWindowsSettingsStrings.UNSELECTED else null", squashed)

    def test_the_wide_labels_of_a_row_are_not_rendered_with_a_popup_outside_the_select_row(self):
        # Spec 3.7: AUTO never opens a popup, so it is safe inside the fixed-size native popovers.
        before_dropdown = CONTROLS_CODE[:CONTROLS_CODE.index("private fun <T> DesktopSettingsDropdownChoice(")]
        self.assertNotIn("DesktopPopover(", before_dropdown)
        after = CONTROLS_CODE[CONTROLS_CODE.index("internal fun <T> DesktopWindowsSettingsChoice("):]
        self.assertNotIn("DesktopPopover(", after)

    def test_the_tree_rail_is_one_kit_nav_row_per_category_with_a_selected_state(self):
        squashed = sm.squash(TREE)
        self.assertEqual(1, TREE_CODE.count("DesktopNavRow("))
        self.assertIn("DesktopNavRow(label = title, selected = category == selected, onClick = { onSelect(category) })", squashed)
        self.assertIn("DESKTOP_SETTINGS_RAIL_CATEGORIES.forEach { category ->", squashed)
        self.assertIn("val selected = desktopSettingsPageCategory(page)", squashed)
        for removed in ("OutlinedButton", "TextButton", "AlertDialog", "VerticalDivider", "HorizontalDivider"):
            self.assertNotIn(removed, TREE)

    def test_the_root_page_has_no_duplicate_category_buttons_and_lands_on_the_last_category(self):
        squashed = sm.squash(TREE)
        self.assertNotIn("groups.forEach", TREE_CODE)
        self.assertNotIn("适合鼠标、键盘与桌面窗口的设置", TREE)
        self.assertIn("DesktopSettingsPage.Root -> DesktopSettingsRootHint(navigation.landingPending)", squashed)
        self.assertIn("if (!landingPending) Text(DesktopWindowsSettingsStrings.EMPTY_HINT,", squashed)
        self.assertIn("LaunchedEffect(page, navigation.landingPending) {", squashed)
        self.assertIn("if (page === DesktopSettingsPage.Root && navigation.landingPending) navigator.landOnLastCategory()", squashed)
        self.assertIn("else if (page is DesktopSettingsPage.Category && desktopSettingsDirectDetail(page.category) != null) navigator.replaceCategory(page.category)", squashed)

    def test_a_rail_click_replaces_the_page_and_the_system_category_keeps_its_route(self):
        squashed = sm.squash(TREE)
        self.assertIn("if (canonicalSettingsRootCategory(category) == SettingsRootCategory.SYSTEM_ABOUT) onCategoryOpen(category) else navigator.replaceCategory(category)", squashed)
        self.assertIn("if (!desktopSettingsRailClickIsRedundant(page, category)) onCategorySelect(category)", squashed)
        # The old guard swallowed a click on the selected entry from a page under it (the 消息通知 detail under 隐私与屏蔽).
        self.assertNotIn("category != selected", squashed)
        # Search results and the shell still open categories through onCategoryOpen as before.
        self.assertIn("onCategoryClick = onCategoryOpen,", squashed)

    def test_the_content_branches_of_the_tree_are_the_old_ones(self):
        squashed = sm.squash(TREE)
        for needle in ("is DesktopSettingsPage.Search -> DesktopSettingsSearchScreen(search, onBack = onPageBack,",
                       "is DesktopSettingsPage.CommentFraudHistory -> commentFraudHistoryContent(page) { navigator.pop() }",
                       "SettingsSearchTarget.PLAYBACK -> playbackContent(page) { onDetailBack(page.target) }",
                       "SettingsSearchTarget.APPEARANCE -> appearanceContent()",
                       "SettingsSearchTarget.PLUGINS -> pluginsContent()",
                       "SettingsSearchTarget.MESSAGE_NOTIFICATION -> DesktopMessageNotificationSettingsContent(onFailure)",
                       "SettingsSearchTarget.BLOCKED_LIST -> blockedListContent()",
                       "SettingsSearchTarget.TIPS -> com.bilipai.desktop.ui.DesktopWindowsTipsSettings(onBack = { onDetailBack(page.target) })",
                       "SettingsSearchTarget.OPEN_SOURCE_LICENSES -> OpenSourceLicensesScreen(onBack = { onDetailBack(page.target) })",
                       "SettingsSearchTarget.WEBDAV_BACKUP, SettingsSearchTarget.SETTINGS_SHARE -> backupContent(page.target) { navigator.pop() }",
                       "SettingsSearchTarget.IMAGE_SAVE_PATH -> DesktopSettingsTreePage { imageSavePathContent(true) }",
                       "SettingsRootCategory.STORAGE_BACKUP -> storageContent(null)",
                       "SettingsRootCategory.SYSTEM_ABOUT -> systemContent(page, requestDonate)",
                       "onCommentFraudHistoryClick = navigator::openCommentFraudHistory)",
                       "donateContent(entry) { if (donateEntry === entry) donateEntry = null }"):
            self.assertIn(needle, squashed)

    def test_the_home_page_carries_the_recommendation_fields_of_the_category_page_it_replaces(self):
        squashed = sm.squash(TREE)
        self.assertIn("SettingsSearchTarget.HOME_FEED -> CompositionLocalProvider(LocalDesktopSettingsPanePrelude provides homePrelude) {", squashed)
        self.assertIn("DesktopWindowsSettingsGroup(DesktopWindowsSettingsStrings.HOME_RECOMMENDATION_GROUP) { DesktopHomeRecommendationSettings(discovery, onFailure) }", squashed)
        self.assertEqual(1, TREE_CODE.count("DesktopHomeRecommendationSettings("))
        self.assertIn("prelude?.invoke(this)", sm.squash(CONTROLS))


class LayoutGroupTest(unittest.TestCase):
    """The 界面布局 group: rows for the four flags and the reset button, wired to the flags store."""

    TEXT = sm.read(sm.UX_GROUP_KT)
    STRINGS = sm.read(sm.UX_GROUP_STRINGS_KT)

    def test_the_spec_labels(self):
        expected = {
            "TITLE": "界面布局", "RAIL": "导航栏", "RAIL_AUTO": "自动", "RAIL_EXPANDED": "展开", "RAIL_COLLAPSED": "折叠",
            "RAIL_CLASSIC_TEXT": "经典文字", "PLAYER": "播放器布局", "PLAYER_DESKTOP": "桌面", "PLAYER_LEGACY": "旧版底栏",
            "HOME": "首页布局", "HOME_DESKTOP": "桌面", "HOME_CLASSIC": "经典", "CARDS": "卡片右键菜单与悬停", "CARDS_ON": "开启",
            "CARDS_OFF": "关闭", "RESET": "恢复默认界面布局",
        }
        found = dict(re.findall(r'const val ([A-Z_]+) = "([^"]+)"', self.STRINGS))
        for name, text in expected.items():
            self.assertEqual(text, found[name], name)

    def test_one_choice_per_flag_in_the_contract_order_and_a_reset(self):
        rows = model.load()["settings"]["ux_group"]["rows"]
        self.assertEqual(["rail_mode", "player_layout", "home_layout", "card_interactions"], [row["flag"] for row in rows])
        enums = {name: re.findall(r"\w+", body) for name, body in re.findall(
            r"internal enum class DesktopWindowsUx(\w+) \{ ([^}]*) \}", sm.read(K / "appearance/DesktopWindowsUxModel.kt"))}
        order = {"rail_mode": "RailMode", "player_layout": "PlayerLayout", "home_layout": "HomeLayout", "card_interactions": "CardInteractions"}
        for row in rows:
            self.assertEqual(set(row["values"]), set(enums[order[row["flag"]]]), row["flag"])
        squashed = sm.squash(self.TEXT)
        commands = {"rail_mode": "SetRailMode", "player_layout": "SetPlayerLayout", "home_layout": "SetHomeLayout",
                    "card_interactions": "SetCardInteractions"}
        for row in rows:
            self.assertEqual(1, squashed.count("DesktopWindowsUxCommand.%s(it)" % commands[row["flag"]]), row["flag"])
            values = re.findall(r"DesktopWindowsUx%s\.(\w+) to text\." % order[row["flag"]], squashed)
            self.assertEqual(row["values"], values, row["flag"])
        self.assertEqual(4, squashed.count("DesktopWindowsSettingsChoice(text."))
        self.assertIn("DesktopMenuRow(label = text.RESET, onClick = { ux.submit(DesktopWindowsUxCommand.ResetLayout) },", squashed)
        self.assertEqual("ResetLayout", model.load()["settings"]["ux_group"]["reset_command"])

    def test_the_group_reads_the_flags_store_and_changes_no_default(self):
        squashed = sm.squash(self.TEXT)
        self.assertIn("val ux = LocalDesktopWindowsUx.current", squashed)
        self.assertIn("val settings by ux.settings.collectAsState()", squashed)
        for forbidden in ("windows_display", "WINDOWS_UX_NAMESPACE", "DesktopPluginStore", "setPercent"):
            self.assertNotIn(forbidden, self.TEXT)
        # The group shows what the store holds: the first choice of every row is the stored value, never a constant.
        for flag in ("railMode", "playerLayout", "homeLayout", "cardInteractions"):
            self.assertIn("settings.%s," % flag, squashed)

    def test_the_composable_has_no_text_of_its_own(self):
        self.assertEqual([], source.cjk_positions(self.TEXT))
        self.assertTrue(source.cjk_positions(self.STRINGS))

    def test_the_group_is_inside_the_display_scale_card_and_is_called_by_one_line(self):
        text = sm.read(sm.DISPLAY_SCALE_KT)
        self.assertIn("Card(Modifier.fillMaxWidth().desktopSettingsSearchFocusAnchor(SettingsSearchTarget.APPEARANCE, \"windows_display_scale\")) {", text)
        lines = [line for line in text.splitlines() if "DesktopWindowsUxLayoutGroup" in line]
        self.assertEqual(["            com.bilipai.desktop.ui.DesktopWindowsUxLayoutGroup()"], lines)
        # The call is the last child of the card's column, after the scale controls and their error line.
        last = text.rindex("com.bilipai.desktop.ui.DesktopWindowsUxLayoutGroup()")
        self.assertGreater(last, text.index("(error ?: writeError ?: settings.error)"))
        self.assertIn("\n        }\n    }\n}", text[last:])

    FLAG_ROWS = (("railMode", "RAIL_MODE", "RAIL"), ("playerLayout", "PLAYER_LAYOUT", "PLAYER"),
                 ("homeLayout", "HOME_LAYOUT", "HOME"), ("cardInteractions", "CARD_INTERACTIONS", "CARDS"))

    def flag_readers(self):
        """The Kotlin files that read one of the four flags of the store, other than the store itself and the layout group."""
        readers = {}
        for path in sorted(K.rglob("*.kt")):
            if path == sm.UX_GROUP_KT or source.rel(path).startswith("appearance/DesktopWindowsUx"):
                continue
            code = source.mask_kotlin(sm.read(path))
            for flag, _constant, _row in self.FLAG_ROWS:
                if re.search(r"\.%s\b" % flag, code):
                    readers.setdefault(flag, []).append(source.rel(path))
        return readers

    # Slice V2 changed this pin, with exact values. The ordinary-video leaf now reads player_layout, but it shows DESKTOP only
    # when an overlay exists to show it and none does yet (desktopPlayerEffectiveLayout, DESKTOP_PLAYER_OVERLAY_AVAILABLE = false),
    # so that reader cannot honour the value and the row of the flag stays off. Slice S1 changed it again, with exact values: the
    # shell frame reads rail_mode and honours every value of it (the pure rail presentation rule, the classic block inline), so the
    # rail row is on. The readers are pinned exactly: any other reader of any of the four flags still fails here until its slice
    # turns its row on.
    EXPECTED_READERS = {"playerLayout": ["ui/DesktopWindowsVideoPhysicalLeaf.kt"], "railMode": ["ui/DesktopWindowsShellFrame.kt"]}
    SELECTION_KT = K / "ui/DesktopPlayerLayoutSelection.kt"

    def honoured_flags(self, readers):
        """The flags that a reader honours: every flag read, except player_layout while no overlay is available."""
        overlay = re.search(r"\ninternal const val DESKTOP_PLAYER_OVERLAY_AVAILABLE = (true|false)\n", sm.read(self.SELECTION_KT))
        self.assertIsNotNone(overlay)
        return {flag for flag in readers if flag != "playerLayout" or overlay.group(1) == "true"}

    def test_a_row_is_enabled_only_while_something_reads_its_flag(self):
        # Only the rail flag has a reader that honours it (slice S1); home_layout, card_interactions and player_layout have none,
        # so no row of theirs may be offered: a stored explicit DESKTOP player layout would switch the unaccepted overlay on the
        # day the player slice lands.
        squashed = sm.squash(self.TEXT)
        readers = self.flag_readers()
        honoured = self.honoured_flags(readers)
        for flag, constant, row in self.FLAG_ROWS:
            self.assertEqual(1, len(re.findall(r"private const val %s_HAS_CONSUMER = (true|false)\n" % constant, self.TEXT)), constant)
            wired = "private const val %s_HAS_CONSUMER = true" % constant in self.TEXT
            self.assertEqual(flag in honoured, wired, "%s: the row is on exactly when a reader that honours the flag exists %s" % (flag, readers.get(flag)))
            self.assertEqual(1, squashed.count("enabled = %s_HAS_CONSUMER)" % constant), constant)
        self.assertEqual(self.EXPECTED_READERS, readers)
        self.assertEqual({"railMode"}, honoured)
        self.assertEqual(4, squashed.count("enabled = "))
        # The reset button is the escape from a stored flag and stays enabled.
        self.assertIn("DesktopMenuRow(label = text.RESET, onClick = { ux.submit(DesktopWindowsUxCommand.ResetLayout) },", squashed)

    def test_the_note_says_that_grey_options_are_not_available_yet(self):
        note = re.search(r'const val NOTE = "([^"]+)"', self.STRINGS).group(1)
        self.assertIn("灰色", note)
        self.assertIn("尚不可用", note)

    def test_the_option_note_says_the_values_take_effect_later_and_the_defaults_are_unchanged(self):
        # Honest state after slice S1: only the shell frame reads rail_mode, nothing reads home_layout or card_interactions yet,
        # and the only reader of player_layout is the player leaf, which resolves DESKTOP to LEGACY while no overlay exists.
        self.assertIn("const val NOTE", self.STRINGS)
        defaults = sm.read(K / "appearance/DesktopWindowsUxModel.kt")
        self.assertIn("val railMode: DesktopWindowsUxRailMode = DesktopWindowsUxRailMode.AUTO,", defaults)
        self.assertIn("val playerLayout: DesktopWindowsUxPlayerLayout = DesktopWindowsUxPlayerLayout.LEGACY,", defaults)


class TipsTest(unittest.TestCase):
    def test_the_tips_page_calls_the_shortcut_groups_with_one_line_and_keeps_its_four_groups(self):
        text = sm.read(sm.TIPS_KT)
        self.assertEqual(1, text.count("DesktopWindowsTipsShortcuts()"))
        self.assertEqual(3, len(re.findall(r"DesktopWindowsSettingsGroup\(", text)))
        self.assertEqual(1, len(re.findall(r"DesktopWindowsSettingsPane\(", text)))
        self.assertTrue(text.index("DesktopWindowsTipsShortcuts()") > text.rindex("DesktopWindowsSettingsGroup("))

    def test_the_lists_are_built_from_the_two_tables_and_show_the_right_click_hint(self):
        text = sm.squash(sm.read(sm.TIPS_SHORTCUTS_KT))
        self.assertIn("desktopShellShortcutListRows().forEach { DesktopTipsShortcutRow(it) }", text)
        self.assertIn("desktopPlayerShortcutListRows().forEach { DesktopTipsShortcutRow(it) }", text)
        self.assertIn("Text(DesktopShellStrings.CTRL_B_NOTE,", text)
        self.assertIn("Text(DesktopWindowsTipsStrings.RIGHT_CLICK_HINT)", text)
        list_text = sm.squash(sm.read(sm.SHORTCUT_LIST_KT))
        self.assertIn("table.map { it.chord to DesktopShellStrings.actionLabel(it.action) }", list_text)
        self.assertIn("table.filter { it.status != DesktopPlayerShortcutStatus.OMITTED_UNTIL_MPV_API }", list_text)
        self.assertIn("table: List<DesktopShellShortcut> = DESKTOP_SHELL_SHORTCUTS", list_text)
        self.assertIn("table: List<DesktopPlayerShortcut> = DESKTOP_PLAYER_SHORTCUTS", list_text)

    def test_the_planned_keys_note_is_shown_once_above_all_three_groups(self):
        # The lists hold keys and a right-click menu that later slices wire (V6, H2, V5), so the page says so once, first.
        text = sm.read(sm.TIPS_SHORTCUTS_KT)
        code = source.mask_kotlin(text)
        self.assertEqual(1, code.count("DesktopWindowsTipsStrings.NOTE"))
        self.assertLess(code.index("DesktopWindowsTipsStrings.NOTE"), code.index("DesktopWindowsSettingsGroup("))
        body = code[code.index("internal fun DesktopWindowsTipsShortcuts()"):]
        self.assertEqual(3, len(re.findall(r"DesktopWindowsSettingsGroup\(", body)))
        self.assertEqual(1, body.count("Text(DesktopWindowsTipsStrings.NOTE,"))
        # The right-click hint is conditional on the new interface, and the note names the keys that work today.
        strings = sm.read(sm.TIPS_STRINGS_KT)
        self.assertIn('const val RIGHT_CLICK_HINT = "新界面启用后，', strings)
        note = re.search(r'const val NOTE = "([^"]+)"', strings).group(1)
        for shipped in ("空格", "Esc", "F11"):
            self.assertIn(shipped, note)
        self.assertIn("并非每一项都可用", note)

    def test_every_player_action_kind_has_a_label_and_no_branch_is_a_fallback(self):
        kinds = re.findall(r"\b([A-Z][A-Z_]+)\b", re.search(r"enum class DesktopPlayerActionKind \{(.*?)\}", sm.read(sm.PLAYER_SHORTCUTS_KT), re.S).group(1))
        text = sm.read(sm.TIPS_STRINGS_KT)
        body = text[text.index("fun playerActionLabel("):]
        labelled = re.findall(r"DesktopPlayerActionKind\.([A-Z_]+) ->", body)
        self.assertEqual(kinds, labelled)
        self.assertNotIn("else ->", body)

    def test_every_shell_action_kind_has_a_label_in_the_shell_strings(self):
        shell_text = sm.read(sm.STRINGS_KT.parent / "DesktopShellStrings.kt")
        kinds = re.findall(r"\b([A-Z][A-Z_]+)\b", re.search(r"enum class DesktopShellActionKind \{(.*?)\}", sm.read(K / "ui/DesktopShellShortcuts.kt"), re.S).group(1))
        self.assertEqual(kinds, sm.shell.action_label_kinds(shell_text))

    def test_the_lists_say_that_some_keys_come_with_the_new_interface(self):
        strings = sm.read(sm.TIPS_STRINGS_KT)
        self.assertIn("const val NOTE", strings)
        self.assertIn("const val RIGHT_CLICK_HINT", strings)
        self.assertTrue(source.cjk_positions(strings))
        self.assertEqual([], source.cjk_positions(sm.read(sm.SHORTCUT_LIST_KT)))
        self.assertEqual([], source.cjk_positions(sm.read(sm.TIPS_SHORTCUTS_KT)))

    def test_the_list_row_builders_are_pure(self):
        code = source.mask_kotlin(sm.read(sm.SHORTCUT_LIST_KT))
        for needle in ("androidx.", "java.awt", "@Composable"):
            self.assertNotIn(needle, code)


class GroupSceneTestSourceTest(unittest.TestCase):
    """The ImageComposeScene test of the group layout: it exists, it is marked as a scene test, and it covers the four rules."""

    TEXT = sm.read(KT / "ui/DesktopWindowsSettingsGroupUiTest.kt")

    def test_it_is_a_marked_headless_scene_test_without_a_window_gpu_or_network(self):
        self.assertIn("class DesktopWindowsSettingsGroupUiTest {", self.TEXT)
        self.assertIn("ImageComposeScene test (CPU scene, headless JVM; no native window, no GPU, no network)", self.TEXT)
        self.assertIn("ImageComposeScene(WIDTH, HEIGHT, Density(density))", self.TEXT)
        self.assertIn("check(GraphicsEnvironment.isHeadless())", self.TEXT)
        for banned in ("java.net", "Window(", "ComposeWindow", "Robot"):
            self.assertNotIn(banned, self.TEXT)

    def test_it_covers_the_wrap_content_child_the_dividers_the_nested_choice_and_the_radio_row(self):
        names = re.findall(r"@Test fun (\w+)\(", self.TEXT)
        self.assertEqual(["aChildThatIsNotARowKeepsItsOwnWidthStartAlignedAtTheGroupInsetWhileARowSpansTheContainer",
                          "theDividersAreExactlyOnePixelLinesBetweenTheChildrenAtEveryDensity",
                          "aChoiceNestedInAWrapperOfTheGroupAddsNoInsetOfItsOwn",
                          "aRadioRowGrowsWithATwoLineLabelInsteadOfClippingIt"], names)
        self.assertIn("assertTrue(wrap.width < group.width,", self.TEXT)
        self.assertIn("val lines = scanned.filter { pixels[it * WIDTH + x] != background }", self.TEXT)
        self.assertIn("val DENSITIES = listOf(1f, 1.25f, 1.5f)", self.TEXT)


class HygieneTest(unittest.TestCase):
    SETTINGS_FILES = ("ui/DesktopWindowsSettingsControls.kt", "ui/DesktopWindowsUxSettings.kt", "ui/DesktopWindowsTipsSettings.kt",
                      "ui/DesktopWindowsTipsShortcuts.kt", "settings/DesktopSettingsTree.kt",
                      "appearance/DesktopWindowsDisplayScaleSettings.kt")

    def test_the_hygiene_allowlist_names_no_settings_file_and_is_empty(self):
        entries = source.load_allowlist()
        self.assertEqual([], entries)
        for entry in entries:
            self.assertNotIn(entry["path"], self.SETTINGS_FILES)

    def test_the_six_settings_files_are_in_the_banned_import_scope_and_clean(self):
        scoped = {source.rel(path) for path in source.kotlin_files(source.BANNED_IMPORT_GLOBS)}
        for name in self.SETTINGS_FILES:
            self.assertIn(name, scoped, name)
            self.assertEqual([], source.banned_imports(sm.read(K / name)), name)

    def test_the_files_of_the_slice_hold_no_banned_material_name(self):
        for path in (sm.MODEL_KT, sm.TREE_MODEL_KT, sm.STRINGS_KT, sm.UX_GROUP_STRINGS_KT, sm.TIPS_STRINGS_KT, sm.SHORTCUT_LIST_KT,
                     sm.TIPS_SHORTCUTS_KT, sm.NAVIGATOR_KT):
            self.assertEqual([], source.banned_imports(sm.read(path)), source.rel(path))

    def test_the_layout_group_file_is_in_the_program_scope_and_has_no_hook_or_key_fallback(self):
        text = sm.read(sm.UX_GROUP_KT)
        self.assertEqual([], source.cjk_positions(text))
        self.assertEqual([], source.hook_uses(text))
        self.assertEqual([], source.key_fallback_uses(text))

    def test_the_files_of_the_slice_are_lf_only_without_control_bytes(self):
        here = Path(__file__).resolve().parent
        paths = [sm.CONTROLS_KT, sm.TREE_KT, sm.MODEL_KT, sm.TREE_MODEL_KT, sm.NAVIGATOR_KT, sm.STRINGS_KT, sm.UX_GROUP_KT,
                 sm.UX_GROUP_STRINGS_KT, sm.TIPS_KT, sm.TIPS_SHORTCUTS_KT, sm.TIPS_STRINGS_KT, sm.SHORTCUT_LIST_KT, sm.DISPLAY_SCALE_KT,
                 sm.PLAYBACK_SETTINGS_KT, source.ALLOWLIST, here / "test_ux_settings_signatures.py",
                 source.KT / "ui/DesktopWindowsSettingsGroupUiTest.kt"]
        source.assert_lf_only(self, *paths)


if __name__ == "__main__":
    unittest.main()
