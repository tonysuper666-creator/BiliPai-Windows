import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = Path(__file__).with_name("extract-upstream-js.py")
if not SCRIPT.exists(): SCRIPT = ROOT / "desktop/tools/extract-upstream-js.py"
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
        self.assertEqual(6, len(rows))
        self.assertEqual(6, len({row["path"] for row in rows}))
        self.assertEqual(5, len(self.files))
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


if __name__ == "__main__": unittest.main()
