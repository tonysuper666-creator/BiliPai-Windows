import importlib.util
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("plugins_extractor", ROOT / "desktop/tools/extract-upstream-plugins.py")
EXTRACTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXTRACTOR)


class PluginExtractorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.output = Path(cls.temp.name)
        cls.files = EXTRACTOR.generate(ROOT, cls.output)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def output_for(self, package, name):
        return (self.output / package.replace(".", "/") / (name + ".kt")).read_text(encoding="utf-8")

    def test_direct_sdk_and_rules_are_verbatim(self):
        for path in EXTRACTOR.DIRECT:
            source = EXTRACTOR.read(ROOT, path)
            package = EXTRACTOR.re.search(r"(?m)^package ([\w.]+)", source)[1]
            result = self.output_for(package, Path(path).stem)
            self.assertEqual(result.split("\n", 2)[2], source.strip() + "\n")

    def test_android_store_is_real_windows_binding_without_algorithm_changes(self):
        body = self.output_for("com.android.purebilibili.core.plugin", "PluginStore")
        for key in ["plugin_enabled_$pluginId", "plugin_config_$pluginId", "plugin_data_${pluginId}_$name", "effect_match_hints_enabled"]:
            self.assertIn(key, body)
        self.assertIn("resolvePluginDefaultEnabled(pluginId)", body)
        self.assertNotIn("androidx.datastore", body)
        self.assertIn("DesktopPluginContext as Context", body)
        self.assertIn("prefs[key] = configJson", body)

    def test_json_rule_engine_has_one_reviewed_color_binding(self):
        source = EXTRACTOR.read(ROOT, EXTRACTOR.BASE + "core/plugin/json/RuleEngine.kt")
        body = self.output_for("com.android.purebilibili.core.plugin.json", "RuleEngine")
        expected = source.replace("android.graphics.Color.parseColor(it)", "com.bilipai.desktop.plugins.DesktopPluginColor.parseColor(it)")
        self.assertEqual(body.split("\n", 2)[2], expected.strip() + "\n")

    def test_live_rules_configs_and_json_validation_keep_original_code(self):
        body = self.output_for("com.android.purebilibili.feature.plugin", "DanmakuEnhancePlugin")
        for original in ["blockedKeywordsCache.any { danmaku.content.contains(it, ignoreCase = true) }", "normalized.startsWith(target)", "scale = 1.05f"]:
            self.assertIn(original, body)
        self.assertNotIn("LocalContext", body)
        self.assertNotIn("SettingsContent", body)
        json_body = self.output_for("com.android.purebilibili.core.plugin.json", "JsonPluginManager")
        self.assertIn('Regex("^[a-zA-Z0-9_.-]{1,64}$")', json_body)
        self.assertIn('plugin.type !in setOf("feed", "danmaku")', json_body)
        self.assertIn("validatePlugin(plugin)", json_body)
        self.assertIn("condition 或 field/op/value", json_body)

    def test_public_http_does_not_inherit_bilibili_login_jar(self):
        fetcher = self.output_for("com.android.purebilibili.core.plugin.feed", "FeedFetcher")
        sponsor = self.output_for("com.android.purebilibili.data.repository", "SponsorBlockRepository")
        self.assertIn("DesktopPluginNetwork.publicClient.newBuilder()", fetcher)
        self.assertIn("2 * 1024 * 1024", fetcher)
        self.assertIn("buildSponsorBlockHttpClient(com.bilipai.desktop.plugins.DesktopPluginNetwork.publicClient)", sponsor)
        self.assertNotIn("NetworkModule", fetcher + sponsor)

    def test_binding_drift_fails_closed(self):
        with self.assertRaises(ValueError):
            EXTRACTOR.substitute("new color parser", "android.graphics.Color.parseColor(it)", "bound parser")
        with self.assertRaises(ValueError):
            EXTRACTOR.substitute("x x", "x", "y")

    def test_inventory_has_one_unique_source_and_lf_hash(self):
        inventory = EXTRACTOR.inventory(ROOT)
        self.assertEqual(len(inventory), len({row["path"] for row in inventory}))
        self.assertEqual(len(inventory), len(self.files))
        self.assertTrue(all(len(row["sha256"]) == 64 for row in inventory))
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            path = repo / "sample.kt"
            path.write_bytes(b"package sample\r\nfun ok() = true\r\n")
            self.assertEqual("package sample\nfun ok() = true\n", EXTRACTOR.read(repo, "sample.kt"))


if __name__ == "__main__":
    unittest.main()
