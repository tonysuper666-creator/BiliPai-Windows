import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

ROOT = next(path for path in Path(__file__).resolve().parents if (path / "app/src/main").is_dir())
SCRIPT = next(path for path in (Path(__file__).with_name("extract-upstream-js.py"),
    Path(__file__).resolve().parent.parent / "extract-upstream-js.py",
    Path(__file__).resolve().parent.parent / "tools/extract-upstream-js.py") if path.is_file())
spec = importlib.util.spec_from_file_location("js_extractor", SCRIPT)
EXTRACTOR = importlib.util.module_from_spec(spec)
spec.loader.exec_module(EXTRACTOR)


class JsSourceParityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.files = EXTRACTOR.generate(ROOT, Path(cls.temporary.name))

    @classmethod
    def tearDownClass(cls): cls.temporary.cleanup()

    def file(self, name):
        return next(path.read_text(encoding="utf-8") for path in self.files if path.stem == name)

    def test_pure_schema_and_launch_store_stay_direct_and_are_not_duplicated(self):
        rows = EXTRACTOR.inventory(ROOT)
        expected = {
            EXTRACTOR.BASE + "core/plugin/js/BiliPaiJsPluginModels.kt": "direct",
            EXTRACTOR.BASE + "core/plugin/js/ExternalMediaLaunchStore.kt": "direct",
            EXTRACTOR.BASE + "feature/plugin/js/ExternalMediaDanmaku.kt": "direct",
            EXTRACTOR.BASE + "core/plugin/js/BiliPaiJsPluginInstallStore.kt": "extracted",
            EXTRACTOR.BASE + "core/plugin/feed/FeedSourceCatalog.kt": "extracted",
            EXTRACTOR.BASE + "core/plugin/js/BiliPaiJsModuleResultCache.kt": "extracted",
            EXTRACTOR.BASE + "feature/plugin/js/BiliPaiJsLayoutPresetStore.kt": "extracted",
            EXTRACTOR.BASE + "core/plugin/js/BiliPaiJsRuntime.kt": "policy-extract",
            EXTRACTOR.BASE + "feature/plugin/js/BiliPaiJsPluginContentScreen.kt": "policy-extract",
            EXTRACTOR.BASE + "feature/settings/screen/PluginsScreen.kt": "policy-extract",
        }
        self.assertEqual({row["path"]: row["mode"] for row in rows}, expected)
        self.assertEqual(len(rows), len(expected))
        self.assertEqual({file.name for file in self.files}, {
            "BiliPaiJsPluginInstallStore.kt", "FeedSourceCatalog.kt", "BiliPaiJsModuleResultCache.kt",
            "DesktopBiliPaiJsScriptPolicy.kt", "DesktopBiliPaiJsStorageBridge.kt",
            "DesktopBiliPaiJsContentPolicy.kt", "DesktopJsRemoteImportPolicy.kt",
            "OriginalBiliPaiJsPluginContentScreen.kt", "BiliPaiJsLayoutPresetStore.kt",
        })
        host = EXTRACTOR.helper(ROOT)
        for row in rows:
            self.assertEqual(row["sha256"], hashlib.sha256(host.read(ROOT, row["path"]).encode()).hexdigest())
        for path in EXTRACTOR.DIRECT:
            self.assertNotIn(Path(path).name, {file.name for file in self.files})

    def test_original_script_expressions_are_kept_exact_and_no_fake_vm_is_generated(self):
        host = EXTRACTOR.helper(ROOT)
        original = host.read(ROOT, EXTRACTOR.POLICIES[0])
        output = self.file("DesktopBiliPaiJsScriptPolicy")
        for name in EXTRACTOR.SCRIPT_METHODS:
            self.assertIn(host.media_extractor(ROOT).function(original, name, host.parser_for(ROOT)), output)
        for forbidden in ("class BiliPaiJsRuntime", "WebView", "loadModuleItems", "ScriptEngine"):
            self.assertNotIn(forbidden, output)
        for required in ("Promise.resolve(value)", "BiliPaiHttpNative.get", "BiliPaiStorageNative.set"):
            self.assertIn(required, output)

    def test_installed_scripts_and_metadata_use_real_atomic_platform_writes(self):
        host = EXTRACTOR.helper(ROOT)
        expected = host.platform_context(host.read(ROOT, EXTRACTOR.EXTRACTED[0]))
        expected = host.substitute(expected, "scriptFile.writeText(script, Charsets.UTF_8)",
            "com.bilipai.desktop.plugins.writeDesktopPluginDocument(scriptFile, script)")
        expected = host.substitute(expected, 'metadataFile(installed.manifest.id)\n            .writeText(json.encodeToString(InstalledBiliPaiJsPlugin.serializer(), installed), Charsets.UTF_8)',
            'com.bilipai.desktop.plugins.writeDesktopPluginDocument(metadataFile(installed.manifest.id),\n            json.encodeToString(InstalledBiliPaiJsPlugin.serializer(), installed))')
        output = self.file("BiliPaiJsPluginInstallStore")
        self.assertTrue(output.endswith(expected.strip() + "\n"))
        self.assertIn("enabled = false", output)
        self.assertIn("grantedCapabilities intersect manifest.permissions", output)

    def test_params_keys_defaults_and_flattening_keep_the_original_body(self):
        host = EXTRACTOR.helper(ROOT)
        original = host.read(ROOT, EXTRACTOR.POLICIES[1])
        output = self.file("DesktopBiliPaiJsContentPolicy")
        for name in EXTRACTOR.CONTENT_METHODS:
            method = host.media_extractor(ROOT).function(original, name, host.parser_for(ROOT))
            self.assertIn(method.replace("private fun", "internal fun", 1).replace("android.content.Context", "Context"), output)
        self.assertIn('"bilipai_js_plugin_params"', output)

    def test_feed_catalog_stays_original_and_checks_real_grants_before_host_fetch(self):
        host = EXTRACTOR.helper(ROOT)
        expected = host.platform_context(host.read(ROOT, EXTRACTOR.EXTRACTED[1]))
        output = self.file("FeedSourceCatalog")
        self.assertTrue(output.endswith(expected.strip() + "\n"))
        self.assertIn("PluginCapability.FEED_SOURCE in installed.grantedCapabilities", output)
        self.assertIn("PluginCapability.NETWORK in installed.grantedCapabilities", output)
        self.assertIn('it.kind.equals("feed", ignoreCase = true)', output)
        self.assertNotIn("loadModuleItems", output)

    def test_v025_module_cache_and_layout_preset_store_keep_complete_original_bodies(self):
        host = EXTRACTOR.helper(ROOT)
        for source_path, output_name in (
            (EXTRACTOR.BASE + "core/plugin/js/BiliPaiJsModuleResultCache.kt", "BiliPaiJsModuleResultCache"),
            (EXTRACTOR.BASE + "feature/plugin/js/BiliPaiJsLayoutPresetStore.kt", "BiliPaiJsLayoutPresetStore"),
        ):
            original = host.read(ROOT, source_path)
            expected = host.platform_context(original)
            if output_name == "BiliPaiJsModuleResultCache":
                android_cache = 'File(context.cacheDir, "bilipai_js_plugin_module_cache")'
                windows_cache = 'File(File(context.filesDir, "cache"), "bilipai_js_plugin_module_cache")'
                self.assertEqual(expected.count(android_cache), 1)
                expected = expected.replace(android_cache, windows_cache, 1)
            self.assertEqual(self.file(output_name).split("\n", 2)[2], expected.strip() + "\n")

    def test_complete_original_content_screen_inverts_every_recorded_platform_edit(self):
        host = EXTRACTOR.helper(ROOT)
        recipe = json.loads(SCRIPT.with_name("upstream-js-content-adaptations.json").read_text(encoding="utf-8"))
        original = host.read(ROOT, recipe["source"])
        self.assertEqual(hashlib.sha256(original.encode()).hexdigest(), recipe["sha256LF"])
        expected = original
        for edit in recipe["changes"]:
            self.assertEqual(expected.count(edit["before"]), edit["count"])
            expected = expected.replace(edit["before"], edit["after"], edit["count"])
        emitted = self.file("OriginalBiliPaiJsPluginContentScreen").split("\n", 2)[2]
        self.assertEqual(emitted, expected.strip() + "\n")
        # host.write strips boundary whitespace after the edits. Restore the
        # adapted boundary, which may differ from the original after deleting
        # Android-only trailing helpers, before checking every inverse position.
        leading = len(expected) - len(expected.lstrip())
        trailing = expected[len(expected.rstrip()):]
        reconstructed = expected[:leading] + emitted[:-1] + trailing
        for edit in reversed(recipe["changes"]):
            before, after = edit["before"], edit["after"]
            self.assertEqual(len(edit["beforePositions"]), edit["count"])
            for index in range(len(edit["beforePositions"]) - 1, -1, -1):
                position = edit["beforePositions"][index] + index * (len(after) - len(before))
                self.assertEqual(reconstructed[position:position + len(after)], after)
                reconstructed = reconstructed[:position] + before + reconstructed[position + len(after):]
        self.assertEqual(reconstructed, original)

    def test_example_snapshots_are_real_upstream_source_files(self):
        for path in EXTRACTOR.EXAMPLES:
            source = EXTRACTOR.helper(ROOT).read(ROOT, path)
            self.assertIn("BiliPaiPlugin", source)
            self.assertIn("BiliPai.http.get", source)

    def test_storage_bridge_keeps_original_keys_map_and_filename_policy(self):
        import re
        host = EXTRACTOR.helper(ROOT)
        original = host.read(ROOT, EXTRACTOR.POLICIES[0])
        bridge = EXTRACTOR.original_storage_bridge(original, host.parser_for(ROOT))
        bridge = bridge.replace("private class StorageBridge", "internal class DesktopBiliPaiJsStorageBridge")
        bridge = re.sub(r"(?m)^[ \t]*@JavascriptInterface\n", "", bridge)
        bridge = bridge.replace('File(storageDir, key.safeStorageName()).writeText(value, Charsets.UTF_8)',
            'com.bilipai.desktop.plugins.writeDesktopPluginDocument(File(storageDir, key.safeStorageName()), value)')
        output = self.file("DesktopBiliPaiJsStorageBridge")
        self.assertIn(bridge, output)
        self.assertIn(host.media_extractor(ROOT).function(original, "safeStorageName", host.parser_for(ROOT))
            .replace("private fun", "internal fun", 1), output)
        self.assertNotIn("JavascriptInterface", output)

    def test_remote_download_and_validation_keep_upstream_bodies_with_only_platform_imports(self):
        host = EXTRACTOR.helper(ROOT)
        selector, parser = host.media_extractor(ROOT), host.parser_for(ROOT)
        original = host.read(ROOT, EXTRACTOR.REMOTE_SOURCE)
        output = self.file("DesktopJsRemoteImportPolicy")
        self.assertIn(selector.function(original, "downloadJsRemotePlugin", parser), output)
        validate = selector.function(original, "validateImportUrlOrError", parser)
        self.assertIn(validate.replace("fun validateImportUrlOrError", "internal fun validateDesktopJsImportUrlOrError", 1), output)
        self.assertIn("DesktopJsRemoteNetwork as NetworkModule", output)
        self.assertIn("DesktopPluginUrl as Uri", output)
        self.assertNotIn("android.net.Uri", output)
        self.assertNotIn("core.network.NetworkModule", output)


if __name__ == "__main__": unittest.main()
