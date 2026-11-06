from pathlib import Path
import hashlib
import importlib.util
import os
import re
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
LIVE = Path(os.environ.get('BILIPAI_LIVE_ROOT') or REPO)
TOOL = REPO / "desktop/tools/extract-upstream-components.py"
if not TOOL.is_file(): TOOL = HERE / "extract-components-platform.py"
spec = importlib.util.spec_from_file_location("components", TOOL)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
host = module.helper(REPO)

def body(path):
    return path.read_text(encoding="utf-8").split("\n", 2)[2]

def signatures(source):
    tokens = host.parser_for(REPO).kotlin_tokens(source)
    result = []
    for i in range(len(tokens) - 2):
        if tokens[i][0] != "fun" or not tokens[i+1][0].startswith("App") or tokens[i+2][0] != "(": continue
        depth = 1; end = i + 2
        while depth:
            end += 1; depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
        result.append(source[tokens[i][1]:tokens[end][2]])
    return result

class ComponentsExtractionTest(unittest.TestCase):
    def test_direct_sources_are_exact_and_already_reused_renderers_are_not_duplicated(self):
        with tempfile.TemporaryDirectory() as name:
            output = Path(name)
            emitted = module.generate(REPO, output, standalone=True)
            for path in set(module.DIRECT + module.ADAPTED + module.POLICIES + module.REUSED):
                identity = path.split('/src/main/java/', 1)[1]
                matches = [LIVE / name / 'src/main/java' / identity for name in ('design-tokens', 'design-system', 'plugin-sdk')]
                self.assertEqual(1, sum(p.is_file() for p in matches), path)
            for path in module.DIRECT:
                original = module._leaf_b_source(REPO, path, module._leaf_source(REPO, path, module._theme_chrome_source(REPO, path, module._optical_source(REPO, path, module._chrome_source(REPO, path, module._liquid_source(REPO, path, host.read(REPO, path)))))))
                target = host.output_target(output, path, original)
                if path in module.REUSED: self.assertFalse(target.exists()); continue
                self.assertEqual(body(target), original.strip() + "\n", path)
                self.assertIn(hashlib.sha256(original.encode()).hexdigest(), target.read_text(encoding="utf-8").split("\n")[1])
            self.assertEqual(len(emitted), len(set(emitted)))

    def test_product_generation_emits_only_platform_bindings_and_selected_policy(self):
        with tempfile.TemporaryDirectory() as name:
            emitted = module.generate(REPO, Path(name))
            self.assertTrue({"AppText.kt", "AppSlider.kt", "AppContentDialogLayoutPolicy.kt", "AppScrollableUnderlinePolicy.kt", "AdaptiveDialogComponents.kt"} <= {p.name for p in emitted})
            gradle = (LIVE / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
            exclusions = gradle.split('val upstreamOwnedGeneratedCopies = setOf(', 1)[1].split('\n)', 1)[0]
            self.assertIn('migratedUpstreamRelativePaths', gradle)
            self.assertIn('val migratedWholeModules = listOf("design-tokens/", "design-system/", "plugin-sdk/")', gradle)
            self.assertIn('appJavaRoot.resolve("com/android/purebilibili/core").walkTopDown()', gradle)
            self.assertIn('upstreamIdentity(element.file) in migratedUpstreamRelativePaths', gradle)
            app_exceptions = ''.join(gradle.split('val ' + key, 1)[1].split('\n)', 1)[0]
                                     for key in ('appCoreDesktopOwned:', 'appCoreLaterSteps:'))
            app_exception_paths = re.findall(r'"(core/[^"\n]+)"\s+to\s+', app_exceptions)
            for path in emitted:
                relative = path.relative_to(Path(name)).as_posix()
                whole_modules = [LIVE / part / 'src/main/java' / relative for part in ('design-tokens', 'design-system', 'plugin-sdk')]
                app_core = relative.removeprefix('com/android/purebilibili/')
                migrated_app = (app_core.startswith('core/') and (LIVE / 'app/src/main/java' / relative).is_file()
                                and not any(app_core.startswith(p) for p in app_exception_paths))
                self.assertTrue(path.name == 'DesktopLiquidValueColorResources.kt' or any(p.is_file() for p in whole_modules) or migrated_app or f'components/{relative}' in exclusions, relative)
            for path in emitted: self.assertNotRegex(body(path), r"(?m)^import android\.")

    def test_all_adapter_public_component_parameters_defaults_and_overloads_are_original(self):
        for path in module.ADAPTED:
            original = host.read(REPO, path)
            self.assertEqual(signatures(original), signatures(module.adapt(path, original, host)), path)
        self.assertEqual(len(signatures(host.read(REPO, module.BASE + "components/AppText.kt"))), 4)

    def test_dialog_adapter_preserves_original_policy_and_properties_with_desktop_width_constraints(self):
        path = module.BASE + "AppContentDialogLayoutPolicy.kt"
        original = host.read(REPO, path)
        expected = original.replace("        securePolicy = base.securePolicy,\n", "").replace("        decorFitsSystemWindows = base.decorFitsSystemWindows,\n", "")
        expected = expected.replace("import androidx.compose.foundation.layout.wrapContentHeight\n",
            "import androidx.compose.foundation.layout.wrapContentHeight\nimport androidx.compose.foundation.layout.wrapContentWidth\n")
        expected = expected.replace("        .padding(horizontal = policy.horizontalPaddingDp.dp)\n        .widthIn(",
            "        .padding(horizontal = policy.horizontalPaddingDp.dp)\n        .wrapContentWidth()\n        .widthIn(")
        self.assertEqual(module.adapt(path, original, host), expected)
        self.assertIn("dismissOnBackPress = base.dismissOnBackPress", expected)
        self.assertIn("dismissOnClickOutside = base.dismissOnClickOutside", expected)

    def test_slider_has_only_monotonic_platform_clock_import_substitution(self):
        path = module.BASE + "components/AppSlider.kt"
        original = host.read(REPO, path)
        expected = original.replace("import android.os.SystemClock", "import com.bilipai.desktop.appearance.DesktopMonotonicClock as SystemClock")
        self.assertEqual(module.adapt(path, original, host), expected)
        self.assertIn("nowMs - lastHapticTimeMs >= 55L", expected)

    def test_adaptive_dialog_is_whole_original_except_two_android_properties(self):
        path = module.BASE + "AdaptiveDialogComponents.kt"
        original = host.read(REPO, path)
        expected = original
        for android_property in (
            "                securePolicy = properties.securePolicy,\n",
            "                decorFitsSystemWindows = false,\n",
        ):
            self.assertEqual(expected.count(android_property), 1)
            expected = expected.replace(android_property, "", 1)
        self.assertEqual(module.adapt(path, original, host), expected)
        inventory = {entry["path"]: entry for entry in module.inventory(REPO)}
        self.assertEqual(inventory[path]["mode"], "policy-extract")
        self.assertNotIn(path, module.DIRECT)
        with tempfile.TemporaryDirectory() as name:
            generated = next(p for p in module.generate(REPO, Path(name)) if p.name == "AdaptiveDialogComponents.kt")
            self.assertEqual(body(generated), expected.strip() + "\n")

    def test_clipboard_preserves_gesture_and_copy_success_gate_without_reading_user_clipboard(self):
        original = (LIVE / module.BASE / 'components/AppTextTapCopy.kt').read_text(encoding='utf-8')
        patch = (LIVE / 'desktop/upstream-core/patches/design-system/src/main/java/com/android/purebilibili/core/ui/components/AppTextTapCopy.kt.patch').read_text(encoding='utf-8')
        self.assertIn('+import com.bilipai.desktop.appearance.LocalDesktopTextClipboard', patch)
        self.assertIn('+        if (!currentValueOf(LocalDesktopTextClipboard).copyText(text.trim())) return', patch)
        self.assertIn('-import android.widget.Toast', patch)
        gesture_start = original[:original.index('awaitEachGesture {')].count('\n') + 1
        gesture_end = original[:original.index('shouldCopyGlobalTextTap', original.index('awaitEachGesture {'))].count('\n') + 1
        for start, count in re.findall(r'(?m)^@@ -(\d+)(?:,(\d+))? \+', patch):
            start, count = int(start), int(count or 1)
            self.assertTrue(start + count - 1 < gesture_start or start > gesture_end)
        platform = LIVE / "desktop/src/main/kotlin/com/bilipai/desktop/appearance/DesktopTextClipboard.kt"
        binding = platform.read_text(encoding="utf-8")
        self.assertIn('ReportingDesktopTextClipboard(AwtDesktopShare())', binding)
        share = LIVE / 'desktop/src/main/kotlin/com/bilipai/desktop/platform/share/AwtDesktopShare.kt'
        awt = share.read_text(encoding='utf-8')
        self.assertIn('Toolkit.getDefaultToolkit().systemClipboard', awt)
        self.assertIn('clipboard().setContents(StringSelection(link), null)', awt)
        for source in (platform, share, share.with_name('DesktopShare.kt')):
            self.assertNotRegex(source.read_text(encoding='utf-8'), r'getContents\(|getData\(')

    def test_scrollable_default_is_extracted_from_original_not_reimplemented(self):
        with tempfile.TemporaryDirectory() as name:
            emitted = module.generate(REPO, Path(name))
            policy = next(p for p in emitted if p.name == "AppScrollableUnderlinePolicy.kt")
            declaration = re.search(r"(?m)^internal fun resolvePiliPlusScrollableUnderlineMinWidth[^\n]+", body(policy))[0]
            self.assertIn(declaration, host.read(REPO, module.POLICIES[0]))

    def test_source_hashes_are_lf_normalized_and_unique(self):
        inventory = module.inventory(REPO)
        self.assertEqual(len(inventory), 53)
        self.assertEqual(len({entry["path"] for entry in inventory}), len(inventory))
        for entry in inventory:
            source = host.read(REPO, entry["path"])
            self.assertNotIn("\r", source)
            self.assertEqual(entry["sha256"], hashlib.sha256(source.encode()).hexdigest())

    def test_old_direct_outputs_are_removed_only_if_owned_by_same_source(self):
        with tempfile.TemporaryDirectory() as name:
            output = Path(name)
            first, second = module.DIRECT[:2]
            old = host.write(output, first, host.read(REPO, first), host.read(REPO, first))
            manual = host.output_target(output, second, host.read(REPO, second))
            manual.parent.mkdir(parents=True, exist_ok=True); manual.write_text("// User-managed file\n", encoding="utf-8")
            module.generate(REPO, output)
            self.assertFalse(old.exists())
            self.assertEqual(manual.read_text(encoding="utf-8"), "// User-managed file\n")

if __name__ == "__main__": unittest.main()
