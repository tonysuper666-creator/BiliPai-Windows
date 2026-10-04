"""Fixed whole upstream shader reuse; source tests, not GPU/pixel acceptance."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / "desktop/tools"
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("liquid_lens_v027", TOOLS / "extract-upstream-shared-liquid-tabs.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
LENS_OUTPUT = "generated/com/android/purebilibili/feature/home/components/liquid/Lens.kt"


class DesktopOriginalLiquidGlassThemeBindingTest(unittest.TestCase):
    # This class reads fixed sources and evaluates the pure recipe only. No generator/output.
    theme_paths = (tool.HOME + "FloatingDockChrome.kt", tool.HOME + "BottomBarMatchedLiquidChrome.kt")

    def fixed_original(self, path):
        original = tool.read(tool._desktop_canonical_source(REPO, path))
        fixed = subprocess.check_output(["git", "show", tool.COMMIT + ":" + path], cwd=REPO).decode("utf-8").replace("\r\n", "\n")
        self.assertEqual(fixed, original)
        return original

    def test_fixed_original_glass_sources_complete_inverse_preserves_algorithms(self):
        for path in self.theme_paths:
            with self.subTest(path=path):
                original = self.fixed_original(path)
                transforms = tool.desktop_glass_theme_transforms(path)
                self.assertEqual([("import androidx.compose.foundation.isSystemInDarkTheme\n",
                    "import com.bilipai.desktop.appearance.isDesktopInDarkTheme as isSystemInDarkTheme\n")], transforms)
                adapted = original
                for before, after in transforms:
                    adapted = tool.one(adapted, before, after)
                restored = adapted
                for before, after in reversed(transforms):
                    restored = tool.one(restored, after, before)
                self.assertEqual(original, restored)
                self.assertEqual(tool.sha(original), tool.sha(restored))
                # Every original theme caller and complete render body remains intact.
                self.assertEqual(original.count("isSystemInDarkTheme()"), adapted.count("isSystemInDarkTheme()"))

    def test_selected_chrome_import_recipe_complete_inverse(self):
        path = tool.HOME + "BottomBarMatchedLiquidChrome.kt"
        original = self.fixed_original(path)
        # emit copies the full original import list before adapting selected declarations.
        imports = "\n".join(line for line in original.splitlines() if line.startswith("import ")) + "\n"
        before, after = tool.desktop_glass_theme_transforms(path)[0]
        adapted = tool.one(imports, before, after)
        self.assertEqual(imports, tool.one(adapted, after, before))
        self.assertNotIn(before, adapted)
        self.assertEqual(1, adapted.count(after))

    def test_missing_or_duplicate_original_theme_import_is_rejected(self):
        for path in self.theme_paths:
            original = self.fixed_original(path)
            before, after = tool.desktop_glass_theme_transforms(path)[0]
            for altered in (original.replace(before, "", 1), original + before):
                with self.subTest(path=path, import_count=altered.count(before)):
                    with self.assertRaises(AssertionError):
                        tool.one(altered, before, after)

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
        original, identity = tool.fixed_v027_lens(REPO)
        generated = tool.read(self.output / LENS_OUTPUT)
        header = "// OriginalSource: " + tool.V027_LENS_PATH + "\n// OriginalSHA256: " + tool.V027_LENS_SHA256 + "\n// OriginalCommit: " + tool.V027_LENS_COMMIT + "\n"
        self.assertTrue(generated.startswith(header))
        adapted = generated[len(header):]
        transforms = tool.lens_preflight_visibility_transforms()
        self.assertEqual(2, len(transforms))
        restored = adapted
        for before, after in reversed(transforms):
            self.assertEqual(1, restored.count(after))
            restored = restored.replace(after, before, 1)
        self.assertEqual(original, restored)
        self.assertEqual(tool.V027_LENS_SHA256, hashlib.sha256(restored.encode("utf-8")).hexdigest())
        record = next(row for row in self.inventory["sources"] if row["path"] == tool.V027_LENS_PATH)
        self.assertEqual(identity, record["sourceIdentity"])
        self.assertEqual([dict(original=a, replacement=b) for a, b in transforms], record["adaptations"])
        self.assertEqual(tool.COMMIT, self.inventory["originalCommit"])
        self.assertEqual([identity], self.inventory["sourceOverrides"])

    def test_source_override_does_not_change_any_other_original_renderer_output(self):
        baseline = tool.read(tool._desktop_canonical_source(REPO, tool.V027_LENS_PATH))
        self.assertEqual("579ced36f6e1f15cb9d906b42c4cf64180c598a7f5083189c12cc30ceea00e8e", tool.sha(baseline))
        before = Path(self.temporary.name) / "v025-reference"
        with patch.object(tool, "fixed_v027_lens", return_value=(baseline, None)):
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
