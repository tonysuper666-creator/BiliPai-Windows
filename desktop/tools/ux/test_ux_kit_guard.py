"""Source guard of the desktop component kit, ui/kit (slices X2a and X2b, spec 5.1 and 5.5). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_kit_guard.py

Rules, all over the files of K/ui/kit:
  banned-import   no material3 or Material 2 FilterChip, Switch, DropdownMenu, AlertDialog, TextButton, ModalBottomSheet or
                  TabRow (spec 5.5), and no Material Slider either: the kit uses the vendored AppSwitch and AppSlider. The
                  hygiene allowlist (ux-hygiene-allowlist.json, owners X3 and X4) is for unmigrated screens only: no entry
                  may name a kit file, so the kit can never be allowlisted
  host-agnostic   no SwingPanel, MpvPlayer, native. or assembly. call, no ComposeDialog, ComposeWindow, command popup or
                  hit region, no java.awt, javax.swing or JNA import, and no import from com.bilipai.desktop outside the kit:
                  the kit is Compose, the vendored AppSwitch and AppSlider and the Miuix tooltip, nothing else
  no-chinese      no Chinese text outside DesktopKitStrings.kt (spec 10.1)
  one action      one clickable in the icon button, no long-click action anywhere, and the tooltip wrapper adds no action
                  (enableUserInput = false); the action sites of the whole controls file are counted, so a new one fails
  pixel snapping  no Modifier.border and no literal 1.dp: borders and dividers go through the hairline helpers (1f / density)
  typography      text sizes come from MaterialTheme.typography, never a fixed sp
  motion          reduced motion arrives through LocalDesktopKitReducedMotion, read once by the host: no per-control polling
No global AWT or OS hook and no key fallback use are checked here too (the hygiene suite arms the same rules for ui/kit).

Slice X2b adds rules for its files (DesktopKitMenus.kt, DesktopKitScrollbar.kt, DesktopPlayerDarkTheme.kt in the kit and
ui/DesktopWindowsToast.kt next to the shell):
  menus           a menu row is one 32 dp button, the context menu and the popover are one Popup on AppPopupSurface(MENU) with
                  no dim, the position comes from the pure desktopKitPlaceMenu, Up and Down move focus (modified keys pass on)
  scrollbar       the kit style is the 12 dp strip with the 8 dp thumb shape and the two alphas, on the v2 adapter, and it
                  composes nothing while the content fits
  dark theme      a nested MaterialTheme with a fixed darkColorScheme built from the marked palette constants only
  toast           one toast at a time on the pure state, a glass pill, a live region, one action button, a no-op default
                  LocalDesktopToaster, and no Snackbar

Scanner self tests show each pattern on samples. Location: desktop/tools/ux, following the desktop/tools/nvidia precedent,
not desktop/tools/tests as spec 8.2 says; Root adds the suite to the validation plan as an explicit
`-s desktop/tools/ux -p <file>` line. Python proves nothing about Kotlin compilation.
"""
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_kit_model as kit  # noqa: E402
import ux_source as source  # noqa: E402

# Every file of the kit folder is scanned, so the files that slice X2b adds are covered the moment they appear.
FILES = {path.name: path for path in sorted(kit.KIT.glob("*.kt"))}
TEXT = {name: kit.read(path) for name, path in FILES.items()}
CODE = {name: source.mask_kotlin(text) for name, text in TEXT.items()}
CONTROLS = CODE["DesktopKitControls.kt"]
MENUS = CODE["DesktopKitMenus.kt"]
SCROLLBAR = CODE["DesktopKitScrollbar.kt"]
DARK = CODE["DesktopPlayerDarkTheme.kt"]
TOAST_TEXT = kit.read(kit.TOAST_KT)
TOAST = source.mask_kotlin(TOAST_TEXT)

HOST_PATTERN = re.compile(
    r"\bSwingPanel\b|\bMpvPlayer\b|\bnative\.|\bassembly\.|\bComposeDialog\b|\bComposeWindow\b|\bdesktopCommandHitRegion\b|"
    r"\bDesktopCommandPopup\w*|\bSwingUtilities\b|\bToolkit\b")
ALLOWED_IMPORTS = (
    "androidx.compose.", "kotlin.", "kotlinx.coroutines.", "top.yukonga.miuix.kmp.", "com.android.purebilibili.core.ui.",
)
SLIDER_IMPORT = re.compile(r"(?m)^[ \t]*import[ \t]+androidx\.compose\.material3?\.(?:Range)?Slider\b")
FIXED_SP = re.compile(r"(?<![\w.])\d+(?:\.\d+)?f?\.sp\b|\bfontSize\s*=")
ONE_DP = re.compile(r"(?<![\w.])1(?:\.0)?f?\.dp\b")
BORDER_CALL = re.compile(r"\.border\(|\bborder\(")
LONG_CLICK = re.compile(r"\bonLongClick\b|\bcombinedClickable\b")
REDUCED_MOTION_POLL = re.compile(r"rememberSystemReduceMotion|rememberDesktopDynamicReduceMotion|desktopReduceMotion")


def host_uses(text):
    """Host, player and window names used in code (comments and string contents are ignored)."""
    return sorted({match.group().strip() for match in HOST_PATTERN.finditer(source.mask_kotlin(text))})


def foreign_imports(text):
    """Imports that are not Compose, Kotlin, coroutines, Miuix or the vendored design-system primitives."""
    found = []
    for name in re.findall(r"(?m)^import[ \t]+([\w.]+)", text):
        if not any(name.startswith(prefix) for prefix in ALLOWED_IMPORTS):
            found.append(name)
    return found


def function_block(code, name):
    """Masked source of the top-level function `name`: from its signature to the closing brace at column 0."""
    match = re.search(r"(?m)^(?:internal |private )?fun (?:<[^>]+> )?(?:(?:Modifier|BoxScope)\.)?%s\(" % re.escape(name), code)
    if match is None:
        raise AssertionError("no function " + name)
    end = code.find("\n}\n", match.end())
    if end < 0:
        raise AssertionError("no closing brace for " + name)
    return code[match.start():end + 2]


class ScannerSelfTest(unittest.TestCase):
    def test_host_patterns(self):
        self.assertEqual(["SwingPanel"], host_uses("fun f() { SwingPanel(factory = { x }) }\n"))
        self.assertEqual(["MpvPlayer"], host_uses("val p = MpvPlayer(config)\n"))
        self.assertEqual(["assembly."], host_uses("fun f() = assembly.current()\n"))
        self.assertEqual(["native."], host_uses("fun f() = native.release()\n"))
        self.assertEqual(["ComposeDialog"], host_uses("val d = ComposeDialog(owner)\n"))
        self.assertEqual([], host_uses("// SwingPanel and MpvPlayer are not allowed\nval s = \"native.x assembly.y\"\n"))
        self.assertEqual([], host_uses("val nativeSize = 3\nval assemblyLine = 4\n"))

    def test_foreign_imports(self):
        text = ("import androidx.compose.ui.Modifier\nimport kotlin.math.round\nimport kotlinx.coroutines.delay\n"
                "import top.yukonga.miuix.kmp.basic.TooltipBox as MiuixTooltipBox\n"
                "import com.android.purebilibili.core.ui.components.AppSwitch\n")
        self.assertEqual([], foreign_imports(text))
        self.assertEqual([], foreign_imports("import com.android.purebilibili.core.ui.AppPopupSurface\n"))
        self.assertEqual(["com.bilipai.desktop.ui.DesktopFlyout"], foreign_imports("import com.bilipai.desktop.ui.DesktopFlyout\n"))
        self.assertEqual(["java.awt.Cursor"], foreign_imports("import java.awt.Cursor\n"))
        self.assertEqual(["com.android.purebilibili.feature.home.HomeViewModel"],
                         foreign_imports("import com.android.purebilibili.feature.home.HomeViewModel\n"))

    def test_the_slider_and_the_pixel_and_typography_patterns(self):
        self.assertTrue(SLIDER_IMPORT.search("import androidx.compose.material3.Slider\n"))
        self.assertTrue(SLIDER_IMPORT.search("import androidx.compose.material.RangeSlider\n"))
        self.assertFalse(SLIDER_IMPORT.search("import androidx.compose.material3.SliderDefaults\n"))
        self.assertTrue(FIXED_SP.search("Text(\"a\", fontSize = 13.sp)"))
        self.assertTrue(FIXED_SP.search("val h = 14.sp"))
        self.assertFalse(FIXED_SP.search("style = MaterialTheme.typography.bodySmall"))
        self.assertTrue(ONE_DP.search("Modifier.height(1.dp)"))
        self.assertTrue(ONE_DP.search("Modifier.height(1f.dp)"))
        self.assertFalse(ONE_DP.search("Modifier.height(10.dp).width(21.dp).padding(1.5.dp)"))
        self.assertFalse(ONE_DP.search("desktopKitHairlineDp(density).dp"))
        self.assertTrue(BORDER_CALL.search("Modifier.border(1.dp, c, s)"))
        self.assertFalse(BORDER_CALL.search("Modifier.desktopKitHairlineBorder(c, r)"))
        self.assertTrue(LONG_CLICK.search("Modifier.combinedClickable(onClick = {})"))
        self.assertTrue(LONG_CLICK.search("onLongClick(label = \"x\") { true }"))
        self.assertTrue(REDUCED_MOTION_POLL.search("val r = rememberSystemReduceMotion()"))

    def test_function_block_reads_one_composable(self):
        text = ("@Composable\ninternal fun A(x: Int) {\n    Box {\n    }\n}\n\n@Composable\ninternal fun <T> B(y: T) {\n    Row {\n    }\n}\n"
                "internal fun Modifier.c(): Modifier = this\n")
        block_a, block_b = function_block(text, "A"), function_block(text, "B")
        self.assertIn("Box", block_a)
        self.assertNotIn("Row", block_a)
        self.assertIn("Row", block_b)
        self.assertNotIn("Box", block_b)
        with self.assertRaises(AssertionError):
            function_block(text, "Missing")


class KitFilesTest(unittest.TestCase):
    def test_the_kit_has_its_six_files_in_the_kit_package(self):
        self.assertTrue({"DesktopKitTokens.kt", "DesktopKitModel.kt", "DesktopKitFocus.kt", "DesktopKitTooltip.kt",
                         "DesktopKitControls.kt", "DesktopKitStrings.kt"} <= set(FILES))
        for name, text in TEXT.items():
            self.assertEqual(1, len(re.findall(r"(?m)^package com\.bilipai\.desktop\.ui\.kit$", text)), name)

    def test_every_kit_file_is_armed_by_the_hygiene_scopes(self):
        armed = {source.rel(path) for path in source.kotlin_files(source.BANNED_IMPORT_GLOBS)}
        strict = {source.rel(path) for path in source.kotlin_files(source.STRICT_TEXT_GLOBS)}
        for name in FILES:
            self.assertIn("ui/kit/" + name, armed, name)
            self.assertIn("ui/kit/" + name, strict, name)


class BannedImportTest(unittest.TestCase):
    def test_no_banned_material_name_in_any_kit_file(self):
        for name, text in TEXT.items():
            self.assertEqual([], source.banned_imports(text), name)

    def test_the_kit_uses_the_vendored_controls_and_not_a_material_slider(self):
        for name, text in TEXT.items():
            self.assertIsNone(SLIDER_IMPORT.search(text), name)
        self.assertIn("import com.android.purebilibili.core.ui.components.AppSwitch", TEXT["DesktopKitControls.kt"])
        self.assertIn("import com.android.purebilibili.core.ui.components.AppSlider", TEXT["DesktopKitControls.kt"])
        self.assertEqual(1, len(re.findall(r"\bAppSwitch\(", CONTROLS)))
        self.assertEqual(1, len(re.findall(r"\bAppSlider\(", CONTROLS)))

    def test_the_hygiene_allowlist_names_no_kit_file(self):
        for entry in source.load_allowlist():
            self.assertFalse(entry["path"].startswith("ui/kit/"), "the kit is never allowlisted: %s" % (entry,))

    def test_the_hygiene_rules_pass_for_the_kit(self):
        for name, path in FILES.items():
            self.assertEqual([], source.hook_uses(TEXT[name]), name)
            self.assertEqual([], source.key_fallback_uses(TEXT[name]), name)


class HostAgnosticTest(unittest.TestCase):
    def test_no_player_native_window_or_popup_dependency(self):
        for name, text in TEXT.items():
            self.assertEqual([], host_uses(text), name)

    def test_imports_are_compose_kotlin_miuix_tooltip_and_the_two_vendored_controls(self):
        for name, text in TEXT.items():
            self.assertEqual([], foreign_imports(text), name)

    def test_no_import_from_the_desktop_shell(self):
        for name, text in TEXT.items():
            self.assertNotIn("import com.bilipai.desktop", text, name)


class TextTest(unittest.TestCase):
    def test_no_chinese_outside_the_strings_object(self):
        for name, text in TEXT.items():
            if name != "DesktopKitStrings.kt":
                self.assertEqual([], source.cjk_positions(text), name)

    def test_the_strings_object_really_holds_the_text(self):
        self.assertTrue(source.cjk_positions(TEXT["DesktopKitStrings.kt"]))
        self.assertTrue(source.is_strings_file(FILES["DesktopKitStrings.kt"]))
        self.assertIn("internal object DesktopKitStrings", TEXT["DesktopKitStrings.kt"])
        self.assertEqual(["LOADING_SETTING"], re.findall(r"const val (\w+) =", TEXT["DesktopKitStrings.kt"]))

    def test_the_loading_text_is_the_settings_wording_of_the_spec(self):
        # Spec 3.7: the loading row says "read settings" with a one-character ellipsis (U+8BFB U+53D6 U+8BBE U+7F6E U+2026).
        wording = chr(0x8BFB) + chr(0x53D6) + chr(0x8BBE) + chr(0x7F6E) + chr(0x2026)
        self.assertIn('const val LOADING_SETTING = "%s"' % wording, TEXT["DesktopKitStrings.kt"])

    def test_text_sizes_come_from_the_theme_typography(self):
        for name, code in CODE.items():
            self.assertIsNone(FIXED_SP.search(code), name)
        for slot in ("bodyMedium", "bodySmall", "labelLarge", "labelSmall"):
            self.assertIn("MaterialTheme.typography." + slot, CONTROLS + CODE["DesktopKitTooltip.kt"], slot)


class OneActionTest(unittest.TestCase):
    def test_the_icon_button_is_one_clickable_with_the_button_role_and_a_name(self):
        block = function_block(CONTROLS, "DesktopIconButton")
        self.assertEqual(1, len(re.findall(r"\bclickable\b", block)))
        self.assertEqual(1, block.count("Role.Button"))
        self.assertIn("role = Role.Button", block)
        self.assertIn("contentDescription = label", block)
        self.assertIn("DesktopTooltipBox(", block)
        for forbidden in ("pointerInput", "combinedClickable", "onLongClick", "selectable", "toggleable"):
            self.assertNotIn(forbidden, block, forbidden)

    def test_the_action_sites_of_the_controls_file_are_counted(self):
        # A call is a modifier on its own line (the import lines also hold the name after a dot).
        self.assertEqual(2, len(re.findall(r"(?m)^\s+\.clickable\b", CONTROLS)), "icon button and select row")
        self.assertEqual(2, len(re.findall(r"(?m)^\s+\.selectable\b", CONTROLS)), "nav row and one per segment")
        self.assertEqual(1, len(re.findall(r"(?m)^\s+\.toggleable\b", CONTROLS)), "toggle row")
        self.assertEqual(0, len(re.findall(r"(?m)^\s+\.(?:triStateToggleable|combinedClickable|pointerInput)\b", CONTROLS)))
        for name, code in CODE.items():
            self.assertIsNone(LONG_CLICK.search(code), name)

    def test_the_tooltip_wrapper_adds_no_action(self):
        code = CODE["DesktopKitTooltip.kt"]
        text = TEXT["DesktopKitTooltip.kt"]
        self.assertIn("enableUserInput = false", code)
        self.assertIn("focusable = false", code)
        self.assertIn("rememberMiuixTooltipState(isPersistent = true)", text)
        for forbidden in ("clickable", "selectable", "toggleable", "semantics", "onLongClick", "combinedClickable"):
            self.assertNotIn(forbidden, code, forbidden)
        self.assertEqual(1, code.count("MiuixTooltipBox("))

    def test_the_tooltip_shows_on_hover_and_focus_after_the_kit_delay_and_hides_on_a_press(self):
        text = TEXT["DesktopKitTooltip.kt"]
        for needle in (".hoverable(hoverSource)", ".onFocusChanged { focused = it.hasFocus }",
                       ".desktopKitOnPointerPress { dismissed = true }", "delay(delayMillis.toLong())",
                       "delayMillis: Int = DESKTOP_KIT_TOOLTIP_DELAY_MS", "(hovered || focused)", "tooltipState.show()",
                       "tooltipState.dismiss()"):
            self.assertIn(needle, text, needle)

    def test_the_tooltip_wrapper_keeps_one_structure_whatever_its_arguments(self):
        code = CODE["DesktopKitTooltip.kt"]
        block = function_block(code, "DesktopTooltipBox")
        # No early return and no branch around the Miuix box: the content is never remounted when a tooltip appears.
        self.assertNotIn("return", block)
        self.assertEqual(1, block.count("MiuixTooltipBox("))
        self.assertNotRegex(block, r"if \(\w+\) \{\s*MiuixTooltipBox")


class ControlShapeTest(unittest.TestCase):
    def test_the_segmented_control_is_a_selectable_group_with_left_right_home_end(self):
        block = function_block(CONTROLS, "DesktopSegmentedControl")
        for needle in (".selectableGroup()", "Role.RadioButton", "Key.DirectionLeft ->", "Key.DirectionRight ->",
                       "Key.MoveHome ->", "Key.MoveEnd ->", "canFocus = false", ".focusRequester(focusRequester)",
                       "hasVisualOverflow", "DesktopTooltipBox(", "enabled = overflowed", "TextOverflow.Ellipsis"):
            self.assertIn(needle, block, needle)
        # Unmodified keys only, so Alt+Left (back) and Ctrl shortcuts reach the shell.
        for modifier in ("isCtrlPressed", "isAltPressed", "isMetaPressed", "isShiftPressed"):
            self.assertIn("event." + modifier, block, modifier)
        self.assertIn("event.type != KeyEventType.KeyDown", block)
        self.assertEqual(1, block.count(".onKeyEvent {"))
        self.assertNotIn("onPreviewKeyEvent", block)

    def test_the_segmented_control_is_32_dp_with_the_control_radius(self):
        block = function_block(CONTROLS, "DesktopSegmentedControl")
        self.assertIn(".height(DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)", block)
        self.assertIn("val radius = DESKTOP_KIT_RADIUS_CONTROL_DP.dp", block)
        self.assertIn(".desktopKitHairlineBorder(colors.outlineVariant, radius)", block)

    def test_the_ring_is_outside_the_border_so_it_draws_on_top(self):
        block = function_block(CONTROLS, "DesktopSegmentedControl")
        self.assertLess(block.index(".desktopKitFocusRing("), block.index(".desktopKitHairlineBorder("))

    def test_the_toggle_row_is_one_switch_action_over_a_display_only_vendored_switch(self):
        block = function_block(CONTROLS, "DesktopToggleRow")
        self.assertIn("role = Role.Switch", block)
        self.assertIn("AppSwitch(checked = checked == true, onCheckedChange = null, enabled = interactive)", " ".join(block.split()))
        self.assertIn("val interactive = enabled && checked != null", block)
        self.assertIn("if (checked == null)", block)
        self.assertIn("DesktopKitStrings.LOADING_SETTING", TEXT["DesktopKitControls.kt"])

    def test_the_select_row_is_a_dropdown_button_that_anchors_the_callers_menu(self):
        block = function_block(CONTROLS, "DesktopSelectRow")
        self.assertIn("role = Role.DropdownList", block)
        self.assertIn("menu?.invoke()", block)
        self.assertIn("val interactive = enabled && valueLabel != null", block)

    def test_the_slider_row_wraps_the_vendored_slider_with_a_name(self):
        block = function_block(CONTROLS, "DesktopSliderRow")
        self.assertIn("AppSlider(", block)
        self.assertIn("contentDescription = title", block)

    def test_the_nav_row_is_one_tab_with_the_pill_and_the_collapsed_name(self):
        block = function_block(CONTROLS, "DesktopNavRow")
        for needle in ("role = Role.Tab", "DESKTOP_KIT_NAV_PILL_WIDTH_DP", "DESKTOP_KIT_NAV_PILL_HEIGHT_DP",
                       "if (collapsed) contentDescription = label", "DESKTOP_KIT_ROW_NAV_DP.dp", "TooltipAnchorPosition.Right"):
            self.assertIn(needle, block, needle)
        self.assertEqual(1, block.count(".selectable("))

    def test_the_badge_is_decorative(self):
        block = function_block(CONTROLS, "DesktopBadge")
        self.assertEqual(2, block.count("clearAndSetSemantics { }"))
        self.assertIn("DESKTOP_KIT_BADGE_DOT_DP.dp", block)
        self.assertIn("DESKTOP_KIT_BADGE_DP", block)

    def test_every_public_control_of_the_spec_exists(self):
        for name in ("DesktopIconButton", "DesktopNavRow", "DesktopSegmentedControl", "DesktopToggleRow", "DesktopSelectRow",
                     "DesktopSliderRow", "DesktopBadge", "DesktopKitDivider"):
            function_block(CONTROLS, name)
        function_block(CODE["DesktopKitTooltip.kt"], "DesktopTooltipBox")
        for name in ("desktopKitHoverWash", "desktopKitFocusRing", "desktopKitInteraction", "desktopKitOnPointerPress"):
            function_block(CODE["DesktopKitFocus.kt"], name)
        self.assertIn("internal fun Modifier.desktopKitHairlineBorder(", TEXT["DesktopKitFocus.kt"])


class PixelSnapTest(unittest.TestCase):
    def test_no_modifier_border_and_no_one_dp_literal(self):
        for name, code in CODE.items():
            self.assertIsNone(BORDER_CALL.search(code), name)
            self.assertIsNone(ONE_DP.search(code), name)

    def test_borders_rings_and_dividers_use_the_snapping_helpers(self):
        focus = TEXT["DesktopKitFocus.kt"]
        self.assertIn("style = Stroke(width = 1f)", focus)
        self.assertIn("topLeft = Offset(0.5f, 0.5f)", focus)
        self.assertIn("desktopKitSnapStrokePx(DESKTOP_KIT_FOCUS_RING_OUTER_DP * density)", focus)
        self.assertIn("desktopKitSnapStrokePx(DESKTOP_KIT_FOCUS_RING_INNER_DP * density)", focus)
        divider = function_block(CONTROLS, "DesktopKitDivider")
        self.assertIn("desktopKitHairlineDp(density).dp", divider)

    def test_the_ring_shows_for_keyboard_focus_only(self):
        block = function_block(CODE["DesktopKitFocus.kt"], "desktopKitFocusRing")
        self.assertIn("focus.ringVisible", block)
        self.assertIn("DesktopKitFocusState()", TEXT["DesktopKitFocus.kt"])
        self.assertIn("event.type == KeyEventType.KeyDown && event.key in DESKTOP_KIT_NAVIGATION_KEYS", TEXT["DesktopKitFocus.kt"])
        # Initial pass observer: a press is seen before the control takes focus and is never consumed.
        pointer = function_block(CODE["DesktopKitFocus.kt"], "desktopKitOnPointerPress")
        self.assertIn("PointerEventPass.Initial", pointer)
        self.assertNotIn("consume", pointer)

    def test_the_hand_cursor_and_hover_come_only_when_enabled(self):
        block = function_block(CODE["DesktopKitFocus.kt"], "desktopKitHoverWash")
        self.assertIn("if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier", block)
        self.assertIn(".hoverable(interactionSource, enabled)", block)


class MotionTest(unittest.TestCase):
    def test_reduced_motion_is_a_local_the_host_provides_and_no_control_polls_the_system(self):
        focus = TEXT["DesktopKitFocus.kt"]
        self.assertIn("internal val LocalDesktopKitReducedMotion = staticCompositionLocalOf { false }", focus)
        for name, code in CODE.items():
            self.assertIsNone(REDUCED_MOTION_POLL.search(code), name)
        self.assertGreaterEqual(len(re.findall(r"LocalDesktopKitReducedMotion\.current", " ".join(CODE.values()))), 1)
        self.assertIn("desktopKitMotionMs(reduced, DESKTOP_KIT_MOTION_FAST_MS)", focus)


class PartTwoFilesTest(unittest.TestCase):
    def test_the_part_two_files_exist_in_the_kit_package_and_are_scanned_by_every_rule_above(self):
        for name in ("DesktopKitMenus.kt", "DesktopKitScrollbar.kt", "DesktopPlayerDarkTheme.kt"):
            self.assertIn(name, FILES)
            self.assertEqual(1, len(re.findall(r"(?m)^package com\.bilipai\.desktop\.ui\.kit$", TEXT[name])), name)

    def test_the_toast_file_is_armed_by_the_hygiene_scopes_and_follows_them(self):
        armed = {source.rel(path) for path in source.kotlin_files(source.BANNED_IMPORT_GLOBS)}
        strict = {source.rel(path) for path in source.kotlin_files(source.STRICT_TEXT_GLOBS)}
        self.assertIn("ui/DesktopWindowsToast.kt", armed)
        self.assertIn("ui/DesktopWindowsToast.kt", strict)
        self.assertEqual([], source.banned_imports(TOAST_TEXT))
        self.assertEqual([], source.cjk_positions(TOAST_TEXT))
        self.assertEqual([], source.hook_uses(TOAST_TEXT))
        self.assertEqual([], source.key_fallback_uses(TOAST_TEXT))
        self.assertEqual([], host_uses(TOAST_TEXT))

    def test_the_part_two_files_ask_nothing_of_the_kit_strings(self):
        # Menus, scrollbar, dark theme and toast own no text: every label comes from the caller.
        self.assertEqual(["LOADING_SETTING"], re.findall(r"const val (\w+) =", TEXT["DesktopKitStrings.kt"]))
        for name in ("DesktopKitMenus.kt", "DesktopKitScrollbar.kt", "DesktopPlayerDarkTheme.kt"):
            self.assertNotIn("DesktopKitStrings", TEXT[name], name)


class MenusTest(unittest.TestCase):
    SQUASHED = kit.squash(MENUS)

    def test_the_menu_composables_exist(self):
        for name in ("DesktopMenuRow", "DesktopContextMenu", "DesktopPopover", "DesktopKitPopupPanel", "desktopKitMenuKey"):
            function_block(MENUS, name)
        self.assertIn("private class DesktopKitMenuPositionProvider(", MENUS)

    def test_a_menu_row_is_one_32_dp_button_with_a_decorative_shortcut(self):
        block = function_block(MENUS, "DesktopMenuRow")
        self.assertEqual(1, len(re.findall(r"\bclickable\b", block)))
        for needle in ("role = Role.Button", ".height(DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)", ".alpha(desktopKitDisabledAlpha(enabled))",
                       ".desktopKitInteraction(source, DESKTOP_KIT_RADIUS_CONTROL_DP.dp, enabled)", "enabled = enabled",
                       "Modifier.clearAndSetSemantics { }", "TextOverflow.Ellipsis", "MaterialTheme.typography.bodyMedium",
                       "MaterialTheme.typography.bodySmall"):
            self.assertIn(needle, block, needle)
        # The accessible name of a row is its label alone: no description, no second action.
        for forbidden in ("pointerInput", "combinedClickable", "onLongClick", "selectable", "toggleable", "contentDescription"):
            self.assertNotIn(forbidden, block, forbidden)

    def test_a_disabled_row_stays_visible_and_dimmed_and_is_not_focusable(self):
        # Dimmed through the shared alpha, inert and out of the focus order through clickable(enabled = false).
        self.assertIn("internal fun desktopKitDisabledAlpha(enabled: Boolean): Float", TEXT["DesktopKitControls.kt"])
        block = function_block(MENUS, "DesktopMenuRow")
        self.assertIn("enabled: Boolean = true", block)
        self.assertLess(block.index(".alpha(desktopKitDisabledAlpha(enabled))"), block.index(".desktopKitInteraction("))
        self.assertLess(block.index(".desktopKitInteraction("), block.index(".clickable("))

    def test_the_menus_file_has_exactly_one_action_site(self):
        self.assertEqual(1, len(re.findall(r"(?m)^\s+\.clickable\b", MENUS)))
        self.assertEqual(0, len(re.findall(
            r"(?m)^\s+\.(?:selectable|toggleable|triStateToggleable|combinedClickable|pointerInput)\b", MENUS)))

    def test_the_context_menu_and_the_popover_are_one_popup_on_the_menu_surface_without_a_dim(self):
        self.assertEqual(1, len(re.findall(r"\bPopup\(", MENUS)))
        self.assertEqual(1, len(re.findall(r"\bAppPopupSurface\(", MENUS)))
        for needle in ("popupPositionProvider = provider", "onDismissRequest = onDismissRequest", "properties = PopupProperties(focusable = true)",
                       "type = AppPopupSurfaceType.MENU", "shape = RoundedCornerShape(radius)", "containerColor = colors.surface"):
            self.assertIn(needle, self.SQUASHED, needle)
        self.assertIn("import com.android.purebilibili.core.ui.AppPopupSurface\n", TEXT["DesktopKitMenus.kt"])
        self.assertIn("import com.android.purebilibili.core.ui.AppPopupSurfaceType\n", TEXT["DesktopKitMenus.kt"])
        for pattern in (r"\bDialog\w*", r"DropdownMenu\w*", r"\w*[Ss]crim\w*", r"\bdim\w*", r"\bblur\w*", r"ModalBottomSheet\w*"):
            self.assertIsNone(re.search(pattern, MENUS), pattern)

    def test_the_popup_panel_is_shared_and_sized_from_the_tokens(self):
        self.assertEqual(3, len(re.findall(r"\bDesktopKitPopupPanel\(", MENUS)), "the definition and the two public wrappers")
        for needle in ("DESKTOP_KIT_MENU_WIDTH_DP.dp", "DESKTOP_KIT_POPOVER_WIDTH_DP.dp", "DESKTOP_KIT_MENU_PADDING_DP.dp",
                       "DESKTOP_KIT_MENU_GAP_DP.dp", "DESKTOP_KIT_RADIUS_MENU_DP.dp", "DesktopKitMenuPlacement.AT_POINT",
                       "placement: DesktopKitMenuPlacement = DesktopKitMenuPlacement.BELOW_END", "IntOffset.Zero"):
            self.assertIn(needle, self.SQUASHED, needle)
        context = function_block(MENUS, "DesktopContextMenu")
        self.assertIn("pointer: IntOffset", context)
        popover = function_block(MENUS, "DesktopPopover")
        self.assertNotIn("pointer:", popover)

    def test_a_collapsed_menu_composes_nothing_and_the_popup_comes_after_the_guard(self):
        block = function_block(MENUS, "DesktopKitPopupPanel")
        self.assertIn("if (!expanded) return", block)
        self.assertLess(block.index("if (!expanded) return"), block.index("Popup("))

    def test_the_position_is_the_pure_flip_and_clamp_added_to_the_anchor_corner(self):
        for needle in ("private class DesktopKitMenuPositionProvider(", ") : PopupPositionProvider {", "override fun calculatePosition(",
                       "val atPoint = placement == DesktopKitMenuPlacement.AT_POINT",
                       "val left = if (atPoint) anchorBounds.left + pointer.x else anchorBounds.left",
                       "val top = if (atPoint) anchorBounds.top + pointer.y else anchorBounds.top",
                       "val position = desktopKitPlaceMenu(", "return IntOffset(position.x, position.y)",
                       "remember(placement, pointer, gapPx) { DesktopKitMenuPositionProvider(placement, pointer, gapPx) }"):
            self.assertIn(needle, self.SQUASHED, needle)

    def test_up_and_down_move_the_focus_of_the_popup_layer_and_modified_keys_pass_on(self):
        block = function_block(MENUS, "desktopKitMenuKey")
        for needle in ("focus == null || event.type != KeyEventType.KeyDown", "Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)",
                       "Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)", "else -> false"):
            self.assertIn(needle, block, needle)
        for modifier in ("isCtrlPressed", "isAltPressed", "isMetaPressed", "isShiftPressed"):
            self.assertIn("event." + modifier, block, modifier)
        # Escape is the popup's own dismissal (dismissOnBackPress); the menu never takes Enter, Space, Tab or the arrows sideways.
        for key in ("Key.Escape", "Key.Enter", "Key.Spacebar", "Key.Tab", "Key.DirectionLeft", "Key.DirectionRight"):
            self.assertNotIn(key, block, key)
        self.assertIn("onPreviewKeyEvent = { event -> desktopKitMenuKey(event, layerFocus) }", self.SQUASHED)
        # The popup layer owns its focus: the manager is read inside the popup content, after the Popup call.
        popup_at = re.search(r"\bPopup\(", MENUS).start()
        self.assertGreater(MENUS.index("LocalFocusManager.current"), popup_at)
        self.assertEqual(1, MENUS.count("LocalFocusManager.current"))
        self.assertIn("SideEffect { layerFocus = focusManager }", self.SQUASHED)

    def test_the_menu_scrolls_when_taller_than_the_window_and_uses_the_kit_scrollbar(self):
        for needle in (".heightIn(max = with(density) { maxHeightPx.toDp() })", ".verticalScroll(scroll)",
                       "DesktopKitVerticalScrollbar(scroll)", ".desktopKitHairlineBorder(colors.outlineVariant, radius)",
                       "val endPadding = if (scroll.maxValue > 0) DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP.dp else contentPadding",
                       "desktopKitMenuMaxHeightPx(LocalWindowInfo.current.containerSize.height, gapPx)"):
            self.assertIn(needle, self.SQUASHED, needle)

    def test_the_menu_label_is_the_callers_and_the_surface_names_itself_only_when_given_one(self):
        self.assertIn(".semantics { if (label != null) contentDescription = label }", self.SQUASHED)
        for name in ("DesktopContextMenu", "DesktopPopover"):
            self.assertIn("label: String? = null", function_block(MENUS, name), name)


class ScrollbarTest(unittest.TestCase):
    SQUASHED = kit.squash(SCROLLBAR)

    def test_the_style_is_the_kit_style(self):
        block = function_block(SCROLLBAR, "desktopKitScrollbarStyle")
        for needle in ("defaultScrollbarStyle().copy(", "minimalHeight = DESKTOP_KIT_HIT_FLOOR_DP.dp",
                       "thickness = DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP.dp", "shape = DesktopKitScrollbarThumbShape",
                       "hoverDurationMillis = desktopKitMotionMs(reduced, DESKTOP_KIT_MOTION_SLOW_MS)",
                       "unhoverColor = colors.onSurface.copy(alpha = DESKTOP_KIT_SCROLLBAR_IDLE_ALPHA_PERCENT / 100f)",
                       "hoverColor = colors.onSurface.copy(alpha = DESKTOP_KIT_SCROLLBAR_HOVER_ALPHA_PERCENT / 100f)"):
            self.assertIn(needle, block, needle)
        self.assertEqual(1, block.count("LocalDesktopKitReducedMotion.current"))
        self.assertIn("@Composable\ninternal fun desktopKitScrollbarStyle(): ScrollbarStyle {", TEXT["DesktopKitScrollbar.kt"])

    def test_the_hit_strip_is_the_layout_box_and_only_the_centred_thumb_is_drawn(self):
        for needle in ("private object DesktopKitScrollbarThumbShape : Shape {",
                       "override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {",
                       "val thumb = desktopKitScrollbarThumbPx(density.density, size.width)",
                       "val inset = desktopKitScrollbarThumbInsetPx(size.width, thumb)",
                       "Outline.Rounded(RoundRect(rect = Rect(inset, 0f, inset + thumb, size.height), cornerRadius = CornerRadius(thumb / 2f)),)"):
            self.assertIn(needle, self.SQUASHED, needle)

    def test_it_is_an_overlay_aligned_to_the_end_and_composes_nothing_while_the_content_fits(self):
        self.assertEqual(3, len(re.findall(r"(?m)^internal fun BoxScope\.DesktopKitVerticalScrollbar\(", SCROLLBAR)))
        for needle in ("Modifier.align(Alignment.CenterEnd).fillMaxHeight().then(modifier)", "if (!needed) return",
                       "remember(adapter) { derivedStateOf { desktopKitScrollbarNeeded(adapter.contentSize, adapter.viewportSize) } }",
                       "style = desktopKitScrollbarStyle()",
                       "DesktopKitVerticalScrollbar(adapter = rememberScrollbarAdapter(scrollState), modifier = modifier)",
                       "DesktopKitVerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = modifier)"):
            self.assertIn(needle, self.SQUASHED, needle)
        self.assertEqual(1, len(re.findall(r"\bVerticalScrollbar\(", SCROLLBAR)))

    def test_it_uses_the_v2_adapter_and_never_the_deprecated_one(self):
        text = TEXT["DesktopKitScrollbar.kt"]
        self.assertIn("import androidx.compose.foundation.v2.ScrollbarAdapter\n", text)
        self.assertIsNone(re.search(r"import androidx\.compose\.foundation\.ScrollbarAdapter\b", text))
        for forbidden in ("rememberOldScrollbarAdapter", "HorizontalScrollbar", "Modifier.border", "VerticalScrollbar(adapter = adapter, modifier = modifier)"):
            self.assertNotIn(forbidden, SCROLLBAR, forbidden)

    def test_the_colours_come_from_the_theme_and_the_fade_from_the_reduced_motion_local(self):
        self.assertIn("val colors = MaterialTheme.colorScheme", SCROLLBAR)
        self.assertEqual(0, len(re.findall(r"Color\(0x", SCROLLBAR)))
        self.assertIsNone(REDUCED_MOTION_POLL.search(SCROLLBAR))


class DarkThemeTest(unittest.TestCase):
    SQUASHED = kit.squash(DARK)
    PALETTE = kit.palette_constants(TEXT["DesktopPlayerDarkTheme.kt"])
    SCHEME = DARK[DARK.index("darkColorScheme("):DARK.index("internal fun desktopPlayerDarkColorScheme")]
    SLOTS = {"primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "secondary", "onSecondary", "secondaryContainer",
             "onSecondaryContainer", "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
             "outline", "outlineVariant", "error", "onError", "errorContainer", "onErrorContainer", "surfaceContainerLowest",
             "surfaceContainerLow", "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest"}

    def test_the_wrapper_is_one_nested_material_theme_with_the_fixed_scheme(self):
        self.assertIn("internal fun DesktopPlayerDarkTheme(content: @Composable () -> Unit) {", self.SQUASHED)
        self.assertIn("MaterialTheme(colorScheme = desktopPlayerDarkScheme, content = content)", self.SQUASHED)
        self.assertEqual(1, len(re.findall(r"\bMaterialTheme\(", DARK)))
        self.assertEqual(1, len(re.findall(r"\bdarkColorScheme\(", DARK)))
        self.assertIn("private val desktopPlayerDarkScheme: ColorScheme = darkColorScheme(", self.SQUASHED)

    def test_the_palette_is_fixed_it_reads_nothing_from_the_app_theme(self):
        for forbidden in ("MaterialTheme.colorScheme", "isSystemInDarkTheme", "LocalDesktopDarkTheme", "LocalAppThemeConfig",
                          "dynamicColor", "LocalAppUiStyle"):
            self.assertNotIn(forbidden, DARK, forbidden)

    def test_every_palette_constant_feeds_the_scheme_and_no_colour_literal_is_outside_the_palette(self):
        self.assertEqual(22, len(self.PALETTE))
        for name in self.PALETTE:
            self.assertIn("Color(%s)" % name, self.SCHEME, name)
        self.assertEqual(0, len(re.findall(r"Color\(0x", DARK)))

    def test_the_scheme_defines_every_colour_slot_the_kit_reads(self):
        defined = set(re.findall(r"(\w+) = Color\(", self.SCHEME)) | {"surfaceTint"}
        self.assertTrue(self.SLOTS <= defined, sorted(self.SLOTS - defined))
        read = set()
        for name, code in CODE.items():
            if name != "DesktopPlayerDarkTheme.kt":
                read |= {slot for slot in re.findall(r"(?:colors|MaterialTheme\.colorScheme)\.(\w+)", code) if slot in self.SLOTS}
        self.assertTrue(read, "the kit reads colour slots")
        self.assertTrue(read <= defined, sorted(read - defined))


class ToastSourceTest(unittest.TestCase):
    SQUASHED = kit.squash(TOAST)

    def test_the_default_toaster_does_nothing_so_a_fixture_needs_no_provider(self):
        for needle in ("internal interface DesktopToaster {",
                       "fun show(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null)",
                       "internal object DesktopNoopToaster : DesktopToaster {",
                       "override fun show(message: String, actionLabel: String?, onAction: (() -> Unit)?) = Unit",
                       "internal val LocalDesktopToaster = staticCompositionLocalOf<DesktopToaster> { DesktopNoopToaster }"):
            self.assertIn(needle, self.SQUASHED, needle)

    def test_one_toast_at_a_time_on_the_pure_state_with_a_timer_per_toast_id(self):
        for needle in ("var state: DesktopKitToastState by mutableStateOf(DesktopKitToastState()) private set",
                       "val next = state.show(message, actionLabel)", "if (next === state) return",
                       "val entry = controller.state.current ?: return", "LaunchedEffect(entry.id) {",
                       "delay(lifetimeMillis(entry))", "controller.dismiss(entry.id)",
                       "lifetimeMillis: (DesktopKitToastEntry) -> Long = { it.durationMs }",
                       "fun dismiss(id: Long) { state = state.dismiss(id) }"):
            self.assertIn(needle, self.SQUASHED, needle)
        self.assertEqual(1, len(re.findall(r"\bLaunchedEffect\(", TOAST)))

    def test_the_action_closes_the_toast_before_it_runs_its_callback_exactly_once(self):
        start = TOAST.index("fun invokeAction(id: Long) {")
        block = TOAST[start:TOAST.index("\n    }\n", start)]
        self.assertIn("if (state.current?.id != id) return", block)
        self.assertLess(block.index("pendingAction = null"), block.index("state = state.dismiss(id)"))
        self.assertLess(block.index("state = state.dismiss(id)"), block.index("action?.invoke()"))
        self.assertIn("pendingAction = if (next.current?.actionLabel != null) onAction else null", self.SQUASHED)

    def test_the_pill_is_glass_a_live_region_and_sits_above_other_bottom_chrome(self):
        for needle in ("internal fun BoxScope.DesktopToastHost(", "bottomInset: Dp = 24.dp", "DesktopWindowsGlassSurface(",
                       "shape = CircleShape,", ".align(Alignment.BottomCenter)", ".padding(start = 16.dp, end = 16.dp, bottom = bottomInset)",
                       ".semantics { liveRegion = LiveRegionMode.Polite }", "style = MaterialTheme.typography.bodyMedium",
                       "style = MaterialTheme.typography.labelLarge"):
            self.assertIn(needle, self.SQUASHED, needle)
        self.assertEqual(1, len(re.findall(r"\bDesktopWindowsGlassSurface\(", TOAST)))

    def test_the_action_is_one_32_dp_button(self):
        self.assertEqual(1, len(re.findall(r"(?m)^\s+\.clickable\b", TOAST)))
        self.assertEqual(0, len(re.findall(r"\b(?:combinedClickable|onLongClick|selectable|toggleable|pointerInput)\b", TOAST)))
        for needle in ("role = Role.Button", ".height(DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)",
                       ".desktopKitInteraction(source, DESKTOP_KIT_RADIUS_CONTROL_DP.dp)"):
            self.assertIn(needle, self.SQUASHED, needle)

    def test_it_is_not_a_snackbar_and_not_a_window(self):
        for pattern in (r"\bSnackbar\w*", r"\bPopup\w*", r"\bDialog\w*", r"\bComposeWindow\b", r"\bMpvPlayer\b"):
            self.assertIsNone(re.search(pattern, TOAST), pattern)

    def test_it_depends_on_the_kit_and_the_glass_surface_only(self):
        for name in re.findall(r"(?m)^import (com\.bilipai\.desktop\.[\w.]+)", TOAST_TEXT):
            self.assertTrue(name.startswith("com.bilipai.desktop.ui.kit."), name)
        self.assertEqual([], [n for n in re.findall(r"(?m)^import ([\w.]+)", TOAST_TEXT) if n.startswith(("java.", "javax."))])
        self.assertEqual(1, len(re.findall(r"(?m)^package com\.bilipai\.desktop\.ui$", TOAST_TEXT)))

    def test_the_rememberer_and_the_text_rule(self):
        self.assertIn("internal fun rememberDesktopToastController(): DesktopToastController = remember { DesktopToastController() }",
                      self.SQUASHED)
        self.assertEqual([], source.cjk_positions(TOAST_TEXT))


class SliceFilesTest(unittest.TestCase):
    def test_the_guard_files_are_lf_only(self):
        here = Path(__file__).resolve().parent
        source.assert_lf_only(self, here / "test_ux_kit_guard.py")

    def test_the_kotlin_tests_of_the_slice_exist(self):
        self.assertTrue(kit.KOTLIN_MODEL_TEST.is_file())
        self.assertTrue(kit.KOTLIN_UI_TEST.is_file())
        ui = kit.read(kit.KOTLIN_UI_TEST)
        self.assertIn("ImageComposeScene(", ui)
        self.assertIn("check(GraphicsEnvironment.isHeadless())", ui)
        self.assertIn("ImageComposeScene(480, 320, Density(1f))", ui)
        # Five tests of slice X2a and seven of slice X2b (menus, popover, scrollbar, toast, default toaster, dark theme).
        self.assertEqual(12, len(re.findall(r"@Test fun ", ui)))
        for needle in ("DesktopContextMenu(", "DesktopPopover(", "DesktopKitVerticalScrollbar(state)", "DesktopToastHost(controller",
                       "DesktopPlayerDarkTheme {", "focusedWindow = true"):
            self.assertIn(needle, ui, needle)


if __name__ == "__main__":
    unittest.main()
