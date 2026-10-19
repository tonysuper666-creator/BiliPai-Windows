"""Source hygiene of the desktop UX program (slice X0). No JVM.

Run: PYTHONUTF8=1 PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=desktop/tools python -X utf8 -B -m unittest discover -s desktop/tools/ux -p test_ux_source_hygiene.py

Rules (spec 5.5, 10.1, P10), armed by file name in ux_source.py so a later slice cannot forget them:
  banned-import   no material3 or Material 2 (androidx.compose.material) FilterChip, Switch, DropdownMenu, AlertDialog,
                  TextButton, ModalBottomSheet or TabRow in ui/kit, DesktopPlayer*.kt, the rail and the re-skinned
                  settings files
  no-chinese      no Chinese text outside a *Strings.kt object in any file the program creates (PROGRAM_GLOBS)
  global-hook     no global AWT or OS hook in any file the program creates; slice V0 must use
                  window.addMouseWheelListener or add an owned allowlist entry (rule global-hook, owner V0)
  key-fallback    no use of the window key fallback in any file the program creates (one leaf slot and one root slot exist)
A violation is fixed or listed in ux-hygiene-allowlist.json with an owner; a stale entry fails.
"""
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ux_source as source  # noqa: E402

SHELL = source.K / "DesktopShell.kt"


def findings_by_rule():
    """{rule: {relative path: [finding, ...]}} and {rule: set of relative paths in scope}."""
    findings = {"banned-import": {}, "no-chinese": {}, "global-hook": {}, "key-fallback": {}}
    scope = {rule: set() for rule in findings}
    for path in source.kotlin_files(source.BANNED_IMPORT_GLOBS):
        scope["banned-import"].add(source.rel(path))
        findings["banned-import"][source.rel(path)] = source.banned_imports(source.read(path))
    for path in source.kotlin_files(source.PROGRAM_GLOBS):
        # Spec 10.1: no hard-coded Chinese outside the strings objects, in every file the program creates.
        if not source.is_strings_file(path):
            scope["no-chinese"].add(source.rel(path))
            findings["no-chinese"][source.rel(path)] = source.cjk_positions(source.read(path))
        scope["global-hook"].add(source.rel(path))
        scope["key-fallback"].add(source.rel(path))
        findings["global-hook"][source.rel(path)] = source.hook_uses(source.read(path))
        findings["key-fallback"][source.rel(path)] = source.key_fallback_uses(source.read(path))
    return findings, scope


class ScannerSelfTest(unittest.TestCase):
    """The scanners find what they must and ignore what they must, shown on small samples."""

    def names(self, text):
        return sorted(name for _line, name in source.banned_imports(text))

    def test_explicit_banned_imports_are_found_including_aliases_and_helpers(self):
        self.assertEqual(["TextButton"], self.names("import androidx.compose.material3.TextButton\n"))
        self.assertEqual(["Switch"], self.names("import androidx.compose.material3.Switch as M3Switch\n"))
        self.assertEqual(["rememberModalBottomSheetState"],
                         self.names("import androidx.compose.material3.rememberModalBottomSheetState\n"))
        self.assertEqual(["PrimaryTabRow"], self.names("import androidx.compose.material3.PrimaryTabRow\n"))
        self.assertEqual(["DropdownMenuItem"], self.names("import androidx.compose.material3.DropdownMenuItem\n"))
        self.assertEqual(["FilterChip"], self.names("import androidx.compose.material3.FilterChip\n"))
        self.assertEqual(["AlertDialog"], self.names("import androidx.compose.material3.AlertDialog\n"))

    def test_harmless_material3_imports_pass(self):
        text = ("import androidx.compose.material3.Text\nimport androidx.compose.material3.MaterialTheme\n"
                "import androidx.compose.material3.windowsizeclass.WindowSizeClass\n"
                "import androidx.compose.material3.windowsizeclass.*\n")
        self.assertEqual([], self.names(text))

    def test_a_star_import_is_a_violation_only_where_a_banned_name_is_used(self):
        star = "import androidx.compose.material3.*\n"
        self.assertEqual([], self.names(star + "fun f() { Text(\"x\"); Card { } ; AppSwitch(true) }\n"))
        self.assertEqual(["TextButton"], self.names(star + "fun f() { TextButton(onClick = {}) { } }\n"))
        self.assertEqual(["Switch"], self.names(star + "fun f() { Switch(checked = true, onCheckedChange = {}) }\n"))

    def test_material2_imports_and_uses_are_found_like_material3(self):
        # The pinned classpath also carries Material 2 (material-desktop), which must not bypass the guard.
        self.assertEqual(["TextButton"], self.names("import androidx.compose.material.TextButton\n"))
        self.assertEqual(["Switch"], self.names("import androidx.compose.material.Switch as M2Switch\n"))
        self.assertEqual(["DropdownMenuItem"], self.names("import androidx.compose.material.DropdownMenuItem\n"))
        self.assertEqual(["ModalBottomSheetLayout"],
                         self.names("import androidx.compose.material.ModalBottomSheetLayout\n"))
        self.assertEqual(["ScrollableTabRow"], self.names("import androidx.compose.material.ScrollableTabRow\n"))
        self.assertEqual(["FilterChip"], self.names("import androidx.compose.material.FilterChip\n"))
        self.assertEqual(["AlertDialog"],
                         self.names("fun f() { androidx.compose.material.AlertDialog(onDismissRequest = {}) }\n"))
        star = "import androidx.compose.material.*\n"
        self.assertEqual([], self.names(star + "fun f() { Text(\"x\"); Surface { } ; AppSwitch(true) }\n"))
        self.assertEqual(["TextButton"], self.names(star + "fun f() { TextButton(onClick = {}) { } }\n"))
        both = "import androidx.compose.material3.Text\nimport androidx.compose.material.TextButton\n"
        self.assertEqual(["TextButton"], self.names(both))

    def test_harmless_material2_imports_pass(self):
        text = ("import androidx.compose.material.Text\nimport androidx.compose.material.icons.Icons\n"
                "import androidx.compose.material.icons.filled.*\nimport androidx.compose.material.ripple.rememberRipple\n"
                "import androidx.compose.material.MaterialTheme\n")
        self.assertEqual([], self.names(text))
        self.assertEqual([], self.names("// import androidx.compose.material.TextButton\n"
                                        "val s = \"androidx.compose.material.Switch\"\n"))

    def test_comments_and_strings_do_not_count(self):
        text = ("// import androidx.compose.material3.TextButton\n/* androidx.compose.material3.Switch */\n"
                "val s = \"androidx.compose.material3.AlertDialog\"\n")
        self.assertEqual([], self.names(text))

    def test_a_qualified_use_counts_once(self):
        self.assertEqual(["AlertDialog"], self.names("fun f() { androidx.compose.material3.AlertDialog(onDismissRequest = {}) }\n"))
        self.assertEqual(["AlertDialog"], self.names(
            "import androidx.compose.material3.AlertDialog\nfun f() { AlertDialog(onDismissRequest = {}) }\n"))

    def test_chinese_is_found_in_literals_punctuation_and_escapes(self):
        self.assertEqual(2, len(source.cjk_positions("val a = \"\u8fd4\u56de\"\n")))
        self.assertTrue(source.cjk_positions("val a = \"ok\uff0c\"\n"))
        self.assertTrue(source.cjk_positions("val a = \"\\u8FD4\"\n"))
        self.assertTrue(source.cjk_positions("// \u8fd4\u56de\n"))
        self.assertEqual([], source.cjk_positions("val a = \"back \\u00e9 \\u0041\"\n"))

    def test_hooks_are_found_in_code_only(self):
        code = "val k = KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(d)\n"
        self.assertTrue(source.hook_uses(code))
        self.assertTrue(source.hook_uses("Toolkit.getDefaultToolkit().addAWTEventListener(l, mask)\n"))
        self.assertTrue(source.hook_uses("fun f() = SetWindowsHookExW(0, p, h, 0)\n"))
        self.assertEqual([], source.hook_uses("// addAWTEventListener is not allowed\nval s = \"RegisterHotKey\"\n"))
        self.assertEqual([], source.hook_uses("window.addMouseWheelListener(listener)\ncomponent.addKeyListener(k)\n"))

    def test_key_fallback_use_is_found_in_code_only(self):
        self.assertTrue(source.key_fallback_uses("val f = LocalDesktopWindowKeyFallback.current\n"))
        self.assertEqual([], source.key_fallback_uses("// the key fallback has two slots\nval s = \"KeyFallback\"\n"))

    def test_allowlist_mechanism(self):
        scope = {"banned-import": {"a.kt", "b.kt", "c.kt"}}
        findings = {"banned-import": {"a.kt": [(1, "Switch")], "b.kt": [], "c.kt": [(2, "TextButton")]}}
        good = [{"rule": "banned-import", "path": "a.kt", "owner": "X3", "reason": "r"},
                {"rule": "banned-import", "path": "c.kt", "owner": "X4", "reason": "r"}]
        self.assertEqual([], source.allowlist_problems(good, findings, scope))
        # a violation that is not listed
        self.assertTrue(any("not allowlisted" in p for p in source.allowlist_problems(good[:1], findings, scope)))
        # a stale entry (b.kt no longer violates)
        stale = good + [{"rule": "banned-import", "path": "b.kt", "owner": "X3", "reason": "r"}]
        self.assertTrue(any("no longer violates" in p for p in source.allowlist_problems(stale, findings, scope)))
        # a missing file, a missing owner, a missing reason, a duplicate, an unknown rule
        self.assertTrue(source.allowlist_problems(good + [{"rule": "banned-import", "path": "zz.kt", "owner": "X3", "reason": "r"}], findings, scope))
        self.assertTrue(source.allowlist_problems([dict(good[0], owner="")] + good[1:], findings, scope))
        self.assertTrue(source.allowlist_problems([dict(good[0], reason=" ")] + good[1:], findings, scope))
        self.assertTrue(source.allowlist_problems(good + good[:1], findings, scope))
        self.assertTrue(source.allowlist_problems(good + [{"rule": "bogus", "path": "a.kt", "owner": "X", "reason": "r"}], findings, scope))


def sample_name(glob):
    """A file name that the glob matches (wildcards replaced by a fixed word)."""
    return glob.replace("**", "Sample").replace("*", "Sample")


class RuleScopeSelfTest(unittest.TestCase):
    """The rules are armed over the right files, shown on scratch trees patched in as K (nothing is written to the repo)."""

    CHINESE = "val label = \"打开\"\n"

    def scratch(self, names):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name).resolve()
        for name in names:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(self.CHINESE.encode("utf-8"))
        return root

    def run_rules(self, names):
        root = self.scratch(names)
        with mock.patch.object(source, "K", root):
            return findings_by_rule()

    def test_program_scope_is_the_strict_scope_plus_the_extra_files(self):
        self.assertEqual(source.STRICT_TEXT_GLOBS + source.PROGRAM_EXTRA_GLOBS, source.PROGRAM_GLOBS)
        self.assertFalse(set(source.STRICT_TEXT_GLOBS) & set(source.PROGRAM_EXTRA_GLOBS))
        for name in ("ui/DesktopWindowsUxSettings.kt", "ui/DesktopCardContextMenu.kt", "ui/DesktopHomeCardMenu.kt",
                     "ui/DesktopHomeCardFrame.kt", "ui/DesktopCardPointer.kt", "ui/DesktopHomeFeedLayout.kt",
                     "ui/DesktopLazyScrollbars.kt", "ui/DesktopFeedScrollbar.kt", "ui/DesktopFeedGridHost.kt",
                     "ui/DesktopWindowsCaptionTheme.kt"):
            self.assertIn(name, source.PROGRAM_EXTRA_GLOBS)

    def test_chinese_in_a_program_only_file_is_flagged(self):
        names = [sample_name(glob) for glob in source.PROGRAM_EXTRA_GLOBS]
        findings, scope = self.run_rules(names)
        for name in names:
            self.assertIn(name, scope["no-chinese"], name)
            self.assertTrue(findings["no-chinese"][name], name + " holds Chinese text and must be flagged")

    def test_chinese_in_a_strict_file_is_still_flagged(self):
        names = [sample_name(glob) for glob in source.STRICT_TEXT_GLOBS]
        findings, scope = self.run_rules(names)
        for name in names:
            self.assertIn(name, scope["no-chinese"], name)
            self.assertTrue(findings["no-chinese"][name], name)

    def test_a_strings_object_is_exempt_wherever_it_sits(self):
        findings, scope = self.run_rules(["appearance/DesktopWindowsUxStrings.kt", "ui/DesktopPlayerStrings.kt",
                                          "ui/DesktopShellStrings.kt"])
        self.assertEqual({}, findings["no-chinese"])
        self.assertEqual(set(), scope["no-chinese"])

    def test_a_file_outside_the_program_scope_is_not_scanned(self):
        findings, scope = self.run_rules(["ui/SomeOtherScreen.kt"])
        self.assertEqual({}, findings["no-chinese"])
        self.assertEqual(set(), scope["no-chinese"])

    def test_a_program_only_violation_needs_an_allowlist_entry(self):
        findings, scope = self.run_rules(["ui/DesktopWindowsUxSettings.kt"])
        entry = {"rule": "no-chinese", "path": "ui/DesktopWindowsUxSettings.kt", "owner": "X3", "reason": "r"}
        self.assertEqual([], source.allowlist_problems([entry], findings, scope))
        self.assertTrue(any("not allowlisted" in p for p in source.allowlist_problems([], findings, scope)))

    def test_the_v0_hook_exception_is_written_down(self):
        self.assertIn("window.addMouseWheelListener", source.__doc__)
        self.assertIn("owner: V0", source.__doc__)
        self.assertIn("V0", source.load_allowlist_about())


class RuleGuardTest(unittest.TestCase):
    def test_allowlist_matches_the_tree_exactly(self):
        findings, scope = findings_by_rule()
        self.assertEqual([], source.allowlist_problems(source.load_allowlist(), findings, scope))

    def test_every_banned_import_scope_file_is_clean_or_allowlisted(self):
        findings, _scope = findings_by_rule()
        listed = {entry["path"] for entry in source.load_allowlist() if entry["rule"] == "banned-import"}
        for path, items in findings["banned-import"].items():
            if items:
                self.assertIn(path, listed, "%s uses %s" % (path, items[:3]))

    def test_no_chinese_outside_the_strings_objects(self):
        findings, _scope = findings_by_rule()
        listed = {entry["path"] for entry in source.load_allowlist() if entry["rule"] == "no-chinese"}
        for path, items in findings["no-chinese"].items():
            if items:
                self.assertIn(path, listed, "%s holds Chinese text %s" % (path, items[:3]))

    def test_no_global_awt_or_os_hook(self):
        findings, _scope = findings_by_rule()
        listed = {entry["path"] for entry in source.load_allowlist() if entry["rule"] == "global-hook"}
        for path, items in findings["global-hook"].items():
            if items:
                self.assertIn(path, listed, "%s installs a global hook %s" % (path, items[:3]))

    def test_no_second_key_fallback_registration(self):
        findings, _scope = findings_by_rule()
        listed = {entry["path"] for entry in source.load_allowlist() if entry["rule"] == "key-fallback"}
        for path, items in findings["key-fallback"].items():
            if items:
                self.assertIn(path, listed, "%s uses the key fallback %s" % (path, items[:3]))

    def test_x0_files_are_inside_the_armed_scopes(self):
        names = {source.rel(path) for path in source.kotlin_files(source.STRICT_TEXT_GLOBS)}
        self.assertTrue({"appearance/DesktopWindowsUx.kt", "appearance/DesktopWindowsUxModel.kt",
                         "appearance/DesktopWindowsUxStrings.kt"} <= names)
        self.assertTrue(source.is_strings_file(source.K / "appearance/DesktopWindowsUxStrings.kt"))
        self.assertFalse(source.is_strings_file(source.K / "appearance/DesktopWindowsUx.kt"))

    def test_the_strings_object_really_holds_the_text(self):
        # The no-Chinese rule is not vacuous: the one strings file of X0 contains Chinese.
        self.assertTrue(source.cjk_positions(source.read(source.K / "appearance/DesktopWindowsUxStrings.kt")))


class ShellProviderHunkTest(unittest.TestCase):
    """Hunk RH1 (provider only) in the Root-reserved DesktopShell.kt. The root key handler branch is slice S2."""

    @classmethod
    def setUpClass(cls):
        cls.text = source.read(SHELL)
        cls.code = source.mask_kotlin(cls.text)

    def test_one_controller_one_close_one_provider(self):
        code = self.code
        self.assertEqual(1, code.count("com.bilipai.desktop.appearance.DesktopWindowsUxController(pluginStore, scope)"))
        self.assertEqual(1, code.count("val windowsUx = remember(pluginStore, scope) {"))
        self.assertEqual(1, code.count("DisposableEffect(windowsUx) { onDispose { windowsUx.close() } }"))
        self.assertEqual(1, code.count("com.bilipai.desktop.appearance.LocalDesktopWindowsUx provides windowsUx,"))
        self.assertEqual(1, code.count("LocalDesktopWindowsUx"))
        self.assertEqual(4, len(re.findall(r"\bwindowsUx\b", code)))

    def test_controller_sits_next_to_the_display_scale_registration(self):
        code = self.code
        registration = code.index("windowsDisplayScale.registerWindowContext(storageSettingsContext)")
        controller = code.index("val windowsUx = remember(")
        next_block = code.index("val brandSuccessEvents = remember(repository)")
        self.assertLess(registration, controller)
        self.assertLess(controller, next_block)
        self.assertLess(code[registration:controller].count("\n"), 6)

    def test_provider_entry_follows_the_audio_output_controller_inside_the_root_provider(self):
        code = self.code
        audio = code.index("LocalDesktopWindowsAudioOutputController provides windowsAudioOutputController,")
        entry = code.index("LocalDesktopWindowsUx provides windowsUx,")
        self.assertLess(audio, entry)
        self.assertLess(code[audio:entry].count("\n"), 3)
        provider = code.rfind("CompositionLocalProvider(", 0, entry)
        self.assertGreater(provider, 0)
        self.assertFalse("\n    }\n" in code[provider:entry], "the entry left the root provider")

    def test_the_root_key_handler_is_untouched_by_the_flags(self):
        start = self.text.index("val rootKeyHandler: (KeyEvent) -> Boolean = { event ->")
        end = self.text.index("val latestRootKeyHandler by rememberUpdatedState(rootKeyHandler)")
        block = self.text[start:end]
        self.assertFalse("Ux" in block, "the root key handler mentions the flags")
        self.assertFalse("windowsUx" in block, "the root key handler mentions the flags")

    def test_the_window_zoom_registration_is_unchanged(self):
        code = self.code
        self.assertEqual(1, code.count("val windowsDisplayScale = com.bilipai.desktop.appearance.LocalDesktopWindowsDisplayScale.current"))
        self.assertEqual(1, code.count("DisposableEffect(windowsDisplayScale, storageSettingsContext) {"))

    def test_the_shell_does_not_write_the_flags_itself(self):
        for needle in ("WINDOWS_UX_NAMESPACE", "windows_ux", "DesktopWindowsUxCommand", "desktopWindowsUx"):
            self.assertFalse(needle in self.text, "the shell mentions " + needle)


if __name__ == "__main__":
    unittest.main()
