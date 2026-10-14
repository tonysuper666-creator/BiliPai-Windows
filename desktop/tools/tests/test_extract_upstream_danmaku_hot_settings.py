from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "desktop/tools"))
spec = importlib.util.spec_from_file_location("danmaku_hot_settings", REPO / "desktop/tools/extract-upstream-danmaku-settings.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class HotDanmakuSettingsExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bp-hot-settings-generator-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_fresh_full_ui_inverse_and_selected_v027_source_identities(self):
        output = self.root / "fresh-output"
        self.assertFalse(output.exists())
        tool.generate(REPO, output)
        proof = json.loads((output / "source-inventory.json").read_text(encoding="utf-8"))
        self.assertEqual(proof["pinnedCommit"], tool.COMMIT)
        self.assertEqual(proof["hotSettingsSlice"]["pinnedCommit"], tool.HOT_SETTINGS_COMMIT)
        self.assertEqual(len(proof["hotSettingsSlice"]["uiClauses"]), 2)
        row = next(row for row in proof["outputs"] if row["path"].endswith("/DanmakuSettingsPanel.kt"))
        text = (output / row["path"]).read_text(encoding="utf-8")
        self.assertEqual(hashlib.sha256(text.encode()).hexdigest(), row["sha256LF"])
        for change in reversed(row["adaptations"]):
            self.assertEqual(text.count(change["after"]), 1)
            text = text.replace(change["after"], change["before"])
        original, identity = tool.latest_panel_original(REPO)
        self.assertEqual(text, original)
        self.assertEqual(proof["panelSlice"], identity)
        self.assertIn("onSliderPreview: ((DanmakuSliderParameter, Float) -> Unit)?", text)
        self.assertIn("onValueChangeFinished", text)
        self.assertTrue(row["reverseNormalizedOriginalByteEqual"])
        panel = (output / row["path"]).read_text(encoding="utf-8")
        self.assertIn("valueRange = 1000f..15000f", panel)
        self.assertIn("duplicateMergeWindowMs: Int = 15_000", panel)
        self.assertNotIn("expandedHotDanmaku", panel)
        self.assertIn("LocalDesktopDanmakuHotSettingsBindings.current", panel)
        self.assertNotIn("SettingsManager.", panel)
        # Verify every real panel color call against the emitted native methods,
        # and preserve each complete original function rather than a color shim.
        import re
        from v038_danmaku_merge import fixed_sources
        originals, _ = fixed_sources(REPO)
        colors = (output / 'com/android/purebilibili/core/ui/AppContentColors.kt').read_text(encoding='utf-8')
        required = set(re.findall(r'AppContentColors\.(\w+)\(', panel))
        self.assertEqual({'accent', 'error', 'onAccent'}, required)
        self.assertEqual(required, set(re.findall(r'fun (\w+)\(\): Color', colors)))
        for name in required:
            start = originals['AppContentColors.kt'].index('    @Composable\n    @ReadOnlyComposable\n    fun '+name+'(): Color')
            end = originals['AppContentColors.kt'].index('\n\n', start)
            self.assertIn(originals['AppContentColors.kt'][start:end], colors)

    def test_complete_original_hot_setter_bodies_use_only_same_owned_store(self):
        output = self.root / "fresh-preferences"
        tool.generate(REPO, output)
        text = (output / "com/bilipai/desktop/settings/DesktopOriginalDanmakuPreferences.kt").read_text(encoding="utf-8")
        originals, _ = tool.hot_settings_originals(REPO)
        manager = originals[tool.BASE + "core/store/SettingsManager.kt"]
        for name in ["getDanmakuHotBarEnabled", "setDanmakuHotBarEnabled", "getHotDanmakuExpandedMode", "setHotDanmakuExpandedMode"]:
            raw, _ = tool.decl(manager, name, "    ")
            expected = raw.replace("context: Context, ", "").replace("context: Context", "").replace("context.settingsDataStore.data", 'store.snapshot("settings")').replace("context.settingsDataStore.edit", "writeOriginal")
            self.assertIn(expected, text)
        self.assertIn('KEY_DANMAKU_HOT_BAR_ENABLED=booleanPreferencesKey', text.replace(" ", "").replace("\n", ""))
        self.assertIn('"danmaku_hot_bar_enabled"', text)
        self.assertIn('"hot_danmaku_expanded_mode"', text)
        self.assertNotIn("DesktopPluginStore(", text)
        self.assertNotIn("object SettingsManager", text)

    def copy_archive(self):
        repo = self.root / "repo"
        shutil.copytree(REPO / tool.HOT_SETTINGS_ARCHIVE, repo / tool.HOT_SETTINGS_ARCHIVE)
        return repo

    def test_changed_fixed_original_bytes_are_rejected_before_output(self):
        repo = self.copy_archive()
        file = repo / tool.HOT_SETTINGS_ARCHIVE / "SettingsManager.kt"
        file.write_bytes(file.read_bytes() + b"\n// drift\n")
        output = self.root / "must-not-exist"
        with self.assertRaisesRegex(ValueError, "bytes changed"):
            tool.generate(repo, output)
        self.assertFalse(output.exists())

    def test_unknown_commit_or_missing_original_input_is_rejected(self):
        repo = self.copy_archive()
        path = repo / tool.HOT_SETTINGS_ARCHIVE / "manifest.json"
        manifest = json.loads(path.read_bytes())
        original = dict(manifest)
        manifest["fixedUpstreamCommit"] = "unknown"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Unknown fixed"):
            tool.hot_settings_originals(repo)
        original["files"] = original["files"][:1]
        path.write_text(json.dumps(original), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "input set changed"):
            tool.hot_settings_originals(repo)

    def test_changed_latest_panel_is_rejected_before_generating_output(self):
        repo = self.root / "latest-repo"
        archive = Path("desktop/upstream-slices/v038-danmaku-merge")
        shutil.copytree(REPO / archive, repo / archive)
        original, _ = tool.latest_panel_original(repo)
        self.assertIn("fun applyDanmakuSliderPreview", original)
        path = repo / archive / "DanmakuSettingsPanel.kt"
        path.write_bytes(path.read_bytes() + b"\n// changed\n")
        with self.assertRaisesRegex(ValueError, "source bytes changed"):
            tool.latest_panel_original(repo)


if __name__ == "__main__":
    unittest.main()
