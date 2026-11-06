"""Fixed whole upstream shader reuse; source tests, not GPU/pixel acceptance."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
LIVE = Path(os.environ.get('BILIPAI_LIVE_ROOT') or REPO)
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("liquid_lens_v027", TOOLS / "extract-upstream-shared-liquid-tabs.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
LENS_OUTPUT = "generated/com/android/purebilibili/feature/home/components/liquid/Lens.kt"


class DesktopOriginalLiquidGlassThemeBindingTest(unittest.TestCase):
    theme_paths = (tool.HOME + "FloatingDockChrome.kt", tool.HOME + "BottomBarMatchedLiquidChrome.kt")

    def fixed_original(self, path):
        original = tool._native_source(REPO, path, tool.read(tool._desktop_canonical_source(REPO, path)))
        pins = {'FloatingDockChrome.kt': '89bfe07642dca9c4424454edd782ffa0763a2802c2bf93b4f8c8046ae450cf36', 'BottomBarMatchedLiquidChrome.kt': 'eb3df1fe7470aee80838c2b44da94298d622d6608f8b14a8304679cb452a1c07'}
        self.assertEqual(pins[Path(path).name], tool.sha(original))
        return original

    def verify_theme_recipe(self, path):
        original = self.fixed_original(path)
        before = 'com.android.purebilibili.core.ui.isAppInDarkTheme()'
        after = 'com.bilipai.desktop.appearance.isDesktopInDarkTheme()'
        count = 2 if path.endswith('FloatingDockChrome.kt') else 1
        self.assertEqual([(before, after, count)], tool.desktop_glass_theme_transforms(path))
        self.assertEqual(count, original.count(before))
        self.assertEqual(0, original.count(after))
        adapted = original.replace(before, after)
        self.assertEqual(0, adapted.count('isAppInDarkTheme()'))
        self.assertEqual(count, adapted.count(after))
        self.assertEqual(original, adapted.replace(after, before))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            tool.generate(REPO, output)
            inventory = json.loads((output / 'source-inventory.json').read_text(encoding='utf-8'))
            record = next(row for row in inventory['sources'] if row['path'] == path)
            emitted = tool.read(output / record['output'])
            self.assertEqual(0, emitted.count('isAppInDarkTheme()'))
            self.assertEqual(count, emitted.count(after))

    def test_fixed_original_glass_sources_complete_inverse_preserves_algorithms(self):
        for path in self.theme_paths:
            with self.subTest(path=path):
                self.verify_theme_recipe(path)

    def test_selected_chrome_import_recipe_complete_inverse(self):
        path = tool.HOME + "BottomBarMatchedLiquidChrome.kt"
        self.verify_theme_recipe(path)

    def test_missing_or_duplicate_original_theme_import_is_rejected(self):
        for path in self.theme_paths:
            original = self.fixed_original(path)
            before, after, count = tool.desktop_glass_theme_transforms(path)[0]
            for altered in (original.replace(before, "", 1), original + before):
                with self.subTest(path=path, import_count=altered.count(before)):
                    select = tool._native_source
                    def changed(repo, selected_path, previous):
                        return altered if selected_path == path else select(repo, selected_path, previous)
                    with tempfile.TemporaryDirectory() as directory, patch.object(tool, '_native_source', side_effect=changed):
                        with self.assertRaisesRegex(AssertionError, 'Exact theme/platform symbol multiplicity'):
                            tool.generate(REPO, Path(directory))

    def test_theme_recipe_is_scoped_to_exact_original_glass_paths(self):
        for path in (tool.V027_LENS_PATH, tool.HOME + "FloatingBottomBar.kt",
            "other/FloatingDockChrome.kt", "other/BottomBarMatchedLiquidChrome.kt"):
            with self.subTest(path=path):
                self.assertEqual([], tool.desktop_glass_theme_transforms(path))


class FixedOriginalLiquidLensTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="original-liquid-lens-")
        cls.output = Path(cls.temporary.name) / "actual"
        tool.generate(REPO, cls.output)
        cls.inventory = json.loads((cls.output / "source-inventory.json").read_text(encoding="utf-8"))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_complete_generated_lens_inverse_restores_fixed_original_with_only_two_visibility_edits(self):
        import v040_native_chrome as native
        original = native.fixed_sources(REPO)[tool.V027_LENS_PATH].decode('utf-8')
        identity = native.identity_for(REPO, tool.V027_LENS_PATH)
        self.assertEqual('222ac588fe6ed2b0a168024a4233f4a1de0877db71ef0cf566677c0680f5f99a', tool.sha(original))
        generated = tool.read(self.output / LENS_OUTPUT)
        header = "// OriginalSource: " + tool.V027_LENS_PATH + "\n// OriginalSHA256: " + tool.sha(original) + "\n// OriginalCommit: " + native.COMMIT + "\n"
        self.assertTrue(generated.startswith(header))
        self.assertEqual(original, generated[len(header):])
        record = next(row for row in self.inventory["sources"] if row["path"] == tool.V027_LENS_PATH)
        self.assertEqual(identity, record["sourceIdentity"])
        self.assertEqual([], record["adaptations"])
        self.assertEqual(tool.COMMIT, self.inventory["originalCommit"])
        self.assertIn(identity, self.inventory["sourceOverrides"])
        patch_path = LIVE / 'desktop/upstream-core/patches/design-system/src/main/java/com/android/purebilibili/core/ui/effect/LiquidLens.kt.patch'
        lines = patch_path.read_text(encoding='utf-8').splitlines(keepends=True)
        removed = [line[1:] for line in lines if line.startswith('-') and not line.startswith('---')]
        added = [line[1:] for line in lines if line.startswith('+') and not line.startswith('+++')]
        self.assertEqual([f'private const val {name} = """\n' for name in tool.LENS_PREFLIGHT_CONSTANTS], removed)
        self.assertEqual(['import com.bilipai.desktop.ui.withDesktopWindowsGlassUniformCache\n', '        withDesktopWindowsGlassUniformCache {\n', '        }\n'] + [f'internal const val {name} = """\n' for name in tool.LENS_PREFLIGHT_CONSTANTS], added)
        source = (LIVE / 'design-system/src/main/java/com/android/purebilibili/core/ui/effect/LiquidLens.kt').read_text(encoding='utf-8').splitlines(keepends=True)
        cursor = 0; result = []
        for line in lines[2:]:
            if line.startswith('@@'):
                start = int(re.match(r'@@ -(\d+)', line).group(1)) - 1
                self.assertGreaterEqual(start, cursor)
                result.extend(source[cursor:start]); cursor = start
            elif line.startswith((' ', '-')):
                self.assertEqual(source[cursor], line[1:]); cursor += 1
                if line.startswith(' '): result.append(line[1:])
            elif line.startswith('+'):
                result.append(line[1:])
        result.extend(source[cursor:])
        self.assertEqual(2, sum('internal const val ' + name in ''.join(result) for name in tool.LENS_PREFLIGHT_CONSTANTS))

    def test_source_override_does_not_change_any_other_original_renderer_output(self):
        baseline = tool.read(tool._desktop_canonical_source(REPO, tool.V027_LENS_PATH))
        self.assertEqual("579ced36f6e1f15cb9d906b42c4cf64180c598a7f5083189c12cc30ceea00e8e", tool.sha(baseline))
        before = Path(self.temporary.name) / "v025-reference"
        select = tool._previous_native_source
        def changed(repo, path, previous):
            current = select(repo, path, previous)
            return current + '\n// override isolation probe\n' if path == tool.V027_LENS_PATH else current
        with patch.object(tool, "_previous_native_source", side_effect=changed):
            tool.generate(REPO, before)
        def outputs(directory):
            return {p.relative_to(tool.safe(directory)).as_posix(): p.read_bytes()
                    for p in tool.safe(directory).rglob("*.kt")}
        actual, old = outputs(self.output), outputs(before)
        self.assertEqual(old.keys(), actual.keys())
        self.assertNotEqual(old[LENS_OUTPUT], actual[LENS_OUTPUT])
        for name in actual.keys() - {LENS_OUTPUT}:
            self.assertEqual(old[name], actual[name], name)

    def test_runtime_render_and_capability_preflight_reference_same_generated_shader_constants(self):
        generated = tool.read(self.output / LENS_OUTPUT)
        material = tool.read(REPO / "desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsLiquidGlassMaterial.kt")
        original, _ = tool.fixed_v027_lens(REPO)
        for name in tool.LENS_PREFLIGHT_CONSTANTS:
            self.assertEqual(1, generated.count("internal const val " + name + " ="))
            self.assertIn("import com.android.purebilibili.feature.home.components.liquid." + name + "\n", material)
            self.assertEqual(1, material.count("RuntimeEffect.makeForShader(" + name + ").close()"))
            self.assertNotIn("const val " + name, material)
            # Visibility is the only change to each entire original constant.
            start = original.index("private const val " + name + " =")
            end = original.index('"""', original.index('"""', start) + 3) + 3
            constant = original[start:end]
            self.assertIn(constant.replace("private const val ", "internal const val ", 1), generated)
        self.assertNotIn("const val ROUNDED_RECT_SDF", material)
        self.assertNotIn("float2 safeNormalize", material)
        self.assertIn("safeNormalize(max(cornerCoord, 0.0))", generated)
        self.assertIn("return half4(r * gSample.a, gSample.g, b * gSample.a, gSample.a);", generated)

    def test_archive_manifest_raw_bytes_and_checkout_line_endings_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="liquid-lens-pins-") as temp:
            repo = Path(temp)
            archive = repo / tool.V027_LENS_ARCHIVE
            shutil.copytree(REPO / tool.V027_LENS_ARCHIVE, archive)
            manifest_path = archive / "manifest.json"
            manifest = json.loads(manifest_path.read_bytes())
            for field, value in [("schemaVersion", 2), ("fixedUpstreamCommit", "unknown"),
                ("originalPath", "another.kt"), ("archiveFile", "another.kt"),
                ("gitBlobOid", "0" * 40), ("sha256Bytes", "0" * 64),
                ("sha256LF", "0" * 64), ("bytes", 1)]:
                manifest_path.write_text(json.dumps(dict(manifest, **{field: value})), encoding="utf-8")
                with self.assertRaisesRegex(AssertionError, "manifest identity"):
                    tool.fixed_v027_lens(repo)
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            source = archive / "Lens.kt"
            raw = source.read_bytes()
            for altered in [raw + b"\n// altered\n", raw.replace(b"\n", b"\r\n"), b"\xef\xbb\xbf" + raw]:
                source.write_bytes(altered)
                with self.assertRaisesRegex(AssertionError, "Lens bytes"):
                    tool.fixed_v027_lens(repo)
            source.write_bytes(raw)
            self.assertEqual(tool.V027_LENS_COMMIT, tool.fixed_v027_lens(repo)[1]["pinnedCommit"])


if __name__ == "__main__":
    unittest.main()
