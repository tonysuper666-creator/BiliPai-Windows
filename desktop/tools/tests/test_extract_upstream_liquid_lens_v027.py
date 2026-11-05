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


class DesktopOriginalLiquidGlassThemeBindingTest(unittest.TestCase):
    # The fixed v037 glass sources read the app theme through one qualified call
    # (75522724c); the generator adapts the current pinned native chrome source.
    theme_paths = (tool.HOME + "FloatingDockChrome.kt", tool.HOME + "BottomBarMatchedLiquidChrome.kt")
    theme_read = "com.android.purebilibili.core.ui.isAppInDarkTheme()"
    desktop_read = "com.bilipai.desktop.appearance.isDesktopInDarkTheme()"
    theme_reads = {tool.HOME + "FloatingDockChrome.kt": 2, tool.HOME + "BottomBarMatchedLiquidChrome.kt": 1}

    def fixed_original(self, path):
        canonical = tool.read(tool._desktop_canonical_source(REPO, path))
        fixed = subprocess.check_output(["git", "show", tool.COMMIT + ":" + path], cwd=REPO).decode("utf-8").replace("\r\n", "\n")
        self.assertEqual(fixed, canonical)
        from v037_player_shared_material import selected_source
        original = tool._native_source(REPO, path, selected_source(path, canonical))
        identity = tool._native_identity(REPO, path)
        self.assertEqual(identity["sha256Bytes"], hashlib.sha256(original.encode("utf-8")).hexdigest())
        return original

    def test_fixed_original_glass_sources_complete_inverse_preserves_algorithms(self):
        for path in self.theme_paths:
            with self.subTest(path=path):
                original = self.fixed_original(path)
                count = self.theme_reads[path]
                transforms = tool.desktop_glass_theme_transforms(path)
                self.assertEqual([(self.theme_read, self.desktop_read, count)], transforms)
                self.assertEqual(count, original.count(self.theme_read))
                self.assertEqual(0, original.count(self.desktop_read))
                self.assertNotIn("isSystemInDarkTheme", original)
                adapted = original.replace(self.theme_read, self.desktop_read)
                self.assertNotIn(self.theme_read, adapted)
                self.assertEqual(count, adapted.count(self.desktop_read))
                restored = adapted.replace(self.desktop_read, self.theme_read)
                self.assertEqual(original, restored)
                self.assertEqual(tool.sha(original), tool.sha(restored))
                # Every original theme caller and complete render body remains intact.
                self.assertEqual(original.count("InDarkTheme()"), adapted.count("InDarkTheme()"))

    def test_selected_chrome_import_recipe_complete_inverse(self):
        path = tool.HOME + "BottomBarMatchedLiquidChrome.kt"
        original = self.fixed_original(path)
        with tempfile.TemporaryDirectory(prefix="liquid-glass-theme-") as temp:
            output = Path(temp) / "actual"
            tool.generate(REPO, output)
            inventory = json.loads((output / "source-inventory.json").read_text(encoding="utf-8"))
            record = next(row for row in inventory["sources"] if row["path"] == path)
            generated = tool.read(output / record["output"])
        self.assertEqual("selected", record["mode"])
        self.assertEqual([dict(original=self.theme_read, replacement=self.desktop_read, count=1)], record["adaptations"])
        header = "// OriginalSource: " + path + "\n// OriginalSHA256: " + tool.sha(original) + "\n// OriginalCommit: " + record["sourceIdentity"]["pinnedCommit"] + "\n"
        self.assertTrue(generated.startswith(header))
        adapted = generated[len(header):]
        self.assertNotIn(self.theme_read, adapted)
        self.assertEqual(1, adapted.count(self.desktop_read))
        restored = tool.one(adapted, self.desktop_read, self.theme_read)
        # emit copies the full original import list before the selected declarations.
        imports = [line for line in original.splitlines() if line.startswith("import ")]
        self.assertEqual(imports, [line for line in restored.splitlines() if line.startswith("import ")])
        self.assertEqual(record["wholeOriginalToPlatform"]["afterSha256LF"], tool.sha(adapted))

    def test_missing_or_duplicate_original_theme_import_is_rejected(self):
        native_source = tool._native_source
        for path in self.theme_paths:
            original = self.fixed_original(path)
            before, after, count = tool.desktop_glass_theme_transforms(path)[0]
            for altered in (original.replace(before, "", 1), original.replace(before, before + "\n    " + before, 1)):
                with self.subTest(path=path, theme_read_count=altered.count(before)):
                    self.assertNotEqual(count, altered.count(before))
                    def altered_source(repo, selected, previous, path=path, altered=altered):
                        return altered if selected == path else native_source(repo, selected, previous)
                    with tempfile.TemporaryDirectory(prefix="liquid-glass-theme-") as temp:
                        with patch.object(tool, "_native_source", altered_source):
                            with self.assertRaisesRegex(AssertionError, "Exact theme/platform symbol multiplicity"):
                                tool.generate(REPO, Path(temp) / "actual")

    def test_theme_recipe_is_scoped_to_exact_original_glass_paths(self):
        for path in (tool.V027_LENS_PATH, tool.HOME + "FloatingBottomBar.kt",
            "other/FloatingDockChrome.kt", "other/BottomBarMatchedLiquidChrome.kt"):
            with self.subTest(path=path):
                self.assertEqual([], tool.desktop_glass_theme_transforms(path))


class FixedOriginalLiquidLensTest(unittest.TestCase):
    # The generated Lens output is now the v040 native chrome delegate (894b8c2ff); its
    # shared shader programs are covered by test_v040_native_chrome_source and the
    # directly compiled design-system core/ui/effect sources. The v027 archive is still
    # read by the generator, so its fail-closed identity stays pinned here.
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
