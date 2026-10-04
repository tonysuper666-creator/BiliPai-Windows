import importlib.util
import hashlib
from pathlib import Path
import tempfile
import unittest
import re


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
        inventory = {row["path"]: row for row in EXTRACTOR.inventory(ROOT)}
        for path in EXTRACTOR.DIRECT:
            source = EXTRACTOR.read(ROOT, path)
            package = EXTRACTOR.re.search(r"(?m)^package ([\w.]+)", source)[1]
            self.assertEqual("direct", inventory[path]["mode"])
            self.assertEqual(hashlib.sha256(source.encode()).hexdigest(), inventory[path]["sha256"])
            self.assertNotRegex(source, r"(?m)^import android\.")
            self.assertFalse((self.output / package.replace(".", "/") / Path(path).name).exists())

    def test_android_store_is_real_windows_binding_without_algorithm_changes(self):
        body = self.output_for("com.android.purebilibili.core.plugin", "PluginStore")
        for key in ["plugin_enabled_$pluginId", "plugin_config_$pluginId", "plugin_data_${pluginId}_$name", "effect_match_hints_enabled"]:
            self.assertIn(key, body)
        self.assertIn("resolvePluginDefaultEnabled(pluginId)", body)
        self.assertNotIn("androidx.datastore", body)
        self.assertIn("DesktopPluginContext as Context", body)
        self.assertIn("prefs[key] = configJson", body)

    def test_fresh_subscription_output_uses_the_fixed_allocator_without_renumbering(self):
        body = self.output_for("com.android.purebilibili.core.plugin.feed", "DesktopSavedSubscriptionFeed")
        original = EXTRACTOR.read(ROOT, EXTRACTOR.BASE + "core/plugin/feed/SubscriptionFeedStore.kt")
        expected = EXTRACTOR.platform_context(EXTRACTOR.subscription_feed_identity_fix(original))
        self.assertEqual(expected.strip() + "\n", body.split("\n", 2)[2])
        self.assertEqual(EXTRACTOR.SUBSCRIPTION_ID_FIX_HELPER_SHA256,
            hashlib.sha256(EXTRACTOR.SUBSCRIPTION_ID_FIX_HELPER.encode()).hexdigest())
        self.assertEqual(1, body.count(EXTRACTOR.SUBSCRIPTION_ID_FIX_HELPER))
        self.assertIn("val usedIds = current.map { it.id }.toMutableSet()", body)
        self.assertIn("usedIds += feed.id\n            current += feed", body)
        self.assertIn("existing?.id ?: uniqueFeedId(trimmedUrl, current.map { it.id }.toSet())", body)
        self.assertIn("204b5891d005c0cac525ca6fdd328b4534c4f3a6a3966917610eee528d3b2785", body.splitlines()[1])
        selector, parser = EXTRACTOR.media_extractor(ROOT), EXTRACTOR.parser_for(ROOT)
        for name in ("list", "remove", "removeAll", "setEnabled", "write", "file"):
            self.assertEqual(selector.function(original, name, parser), selector.function(body, name, parser))

    def test_subscription_identity_fix_rejects_changed_canonical_inputs(self):
        original = EXTRACTOR.read(ROOT, EXTRACTOR.BASE + "core/plugin/feed/SubscriptionFeedStore.kt")
        with self.assertRaisesRegex(ValueError, "Canonical subscription Store changed"):
            EXTRACTOR.subscription_feed_identity_fix(original.replace("var added = 0", "var added = 1"))
        with self.assertRaisesRegex(ValueError, "Canonical subscription Store changed"):
            EXTRACTOR.subscription_feed_identity_fix(EXTRACTOR.subscription_feed_identity_fix(original))

    def test_json_rule_engine_has_one_reviewed_color_binding(self):
        source = EXTRACTOR.read(ROOT, EXTRACTOR.BASE + "core/plugin/json/RuleEngine.kt")
        body = self.output_for("com.android.purebilibili.core.plugin.json", "RuleEngine")
        expected = source.replace("android.graphics.Color.parseColor(it)", "com.bilipai.desktop.plugins.DesktopPluginColor.parseColor(it)")
        self.assertEqual(body.split("\n", 2)[2], expected.strip() + "\n")

    def test_media_owner_guard_keeps_the_original_manager_transaction(self):
        original = EXTRACTOR.read(ROOT, EXTRACTOR.BASE + "core/plugin/PluginManager.kt")
        body = self.output_for("com.android.purebilibili.core.plugin", "PluginManager")
        selector, parser = EXTRACTOR.media_extractor(ROOT), EXTRACTOR.parser_for(ROOT)
        actual = selector.function(body, "setEnabled", parser)
        actual = EXTRACTOR.substitute(actual,
            'enabled: Boolean, stillOwned: () -> Boolean = { true }', 'enabled: Boolean')
        actual = EXTRACTOR.substitute(actual,
            '        if (!stillOwned()) return@withLock\n', '')
        actual = EXTRACTOR.substitute(actual,
            '                if (!stillOwned()) {\n                    plugin.onDisable()\n                    return@withLock\n                }\n', '')
        self.assertEqual(selector.function(original, "setEnabled", parser), actual)

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

    def test_lifecycle_and_statistics_have_actual_windows_bindings(self):
        eye = self.output_for("com.android.purebilibili.feature.plugin", "EyeProtectionPlugin")
        self.assertIn("val appForeground = com.bilipai.desktop.plugins.DesktopPluginLifecycle.isAppVisible()", eye)
        self.assertIn("if (!appForeground) return false", eye)
        self.assertNotIn("ProcessLifecycleOwner", eye)
        sponsor = self.output_for("com.android.purebilibili.feature.plugin", "SponsorBlockPlugin")
        self.assertIn("DesktopPluginAnalytics.logSponsorBlockSkip", sponsor)
        self.assertNotIn("com.android.purebilibili.core.util.AnalyticsHelper", sponsor)
        validators = self.output_for("com.android.purebilibili.core.plugin.feed", "DesktopFeedConditionalValidators")
        self.assertIn("@Serializable\ndata class FeedConditionalValidators", validators)

    def test_binding_drift_fails_closed(self):
        with self.assertRaises(ValueError):
            EXTRACTOR.substitute("new color parser", "android.graphics.Color.parseColor(it)", "bound parser")
        with self.assertRaises(ValueError):
            EXTRACTOR.substitute("x x", "x", "y")

    def test_stale_direct_copy_is_removed_but_foreign_file_is_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            first, second = EXTRACTOR.DIRECT[:2]
            generated = EXTRACTOR.write(output, first, EXTRACTOR.read(ROOT, first), EXTRACTOR.read(ROOT, first))
            foreign = EXTRACTOR.output_target(output, second, EXTRACTOR.read(ROOT, second))
            foreign.parent.mkdir(parents=True, exist_ok=True)
            foreign.write_text("// Not generated by this extractor\npackage retained\n", encoding="utf-8")
            prior = foreign.read_bytes()
            files = EXTRACTOR.generate(ROOT, output)
            self.assertFalse(generated.exists())
            self.assertEqual(prior, foreign.read_bytes())
            self.assertEqual(len(files), len(set(files)))
            self.assertTrue(all(path.is_file() and path.resolve().is_relative_to(output.resolve()) for path in files))

    def test_canonical_target_cannot_escape_output_on_windows(self):
        with tempfile.TemporaryDirectory() as temp:
            parent = Path(temp)
            output = parent / "generated"
            output.mkdir()
            victim = parent / "outside.kt"
            victim.write_text("do not remove", encoding="utf-8")
            with self.assertRaises(ValueError):
                EXTRACTOR.checked_output(output, output / ".." / "outside.kt")
            self.assertEqual("do not remove", victim.read_text(encoding="utf-8"))
            with self.assertRaises(ValueError):
                EXTRACTOR.output_target(output, "bad.kt", "package ....\n")

    def test_text_import_reuses_original_validation_and_install_body(self):
        body = next(path.read_text(encoding="utf-8") for path in self.files if path.name == "JsonPluginManager.kt")
        original = EXTRACTOR.read(ROOT, EXTRACTOR.BASE + "core/plugin/json/JsonPluginManager.kt")
        parser = EXTRACTOR.parser_for(ROOT)
        selector = EXTRACTOR.media_extractor(ROOT)
        url = selector.function(original, "importFromUrl", parser)
        text = selector.function(body, "importFromText", parser)
        start = "    val existing = _plugins.value.find { it.plugin.id == plugin.id }"
        end = "    } catch (e: java.net.SocketTimeoutException) {"
        # Install logic, enabled-state preservation and invalidation must remain verbatim.
        original_install = url[url.index(start):url.index(end)]
        text_install = text[text.index(start):text.index(end)]
        self.assertEqual(original_install.replace("sourceUrl = normalizedUrl", "sourceUrl = null"), text_install)
        parse = selector.function(body, "parsePluginText", parser)
        self.assertIn("validatePlugin(plugin)?.let", parse)
        self.assertIn("json.decodeFromString<JsonRulePlugin>(content)", parse)
        self.assertNotIn("httpClient", text)

    def test_inventory_has_one_unique_source_and_lf_hash(self):
        inventory = EXTRACTOR.inventory(ROOT)
        self.assertEqual(len(inventory), len({row["path"] for row in inventory}))
        # A source can yield its original provider plus UI/aliases, and renderer references
        # yield no Kotlin file. Validate exact provenance instead of one-file-per-source counts.
        registered = {row["path"]: row["sha256"] for row in inventory + EXTRACTOR.asset_inventory(ROOT)}
        origins = set()
        for path in self.files:
            lines = path.read_text(encoding="utf-8").splitlines()
            match = re.fullmatch(r"// GENERATED from (.+); do not edit\.", lines[0])
            self.assertIsNotNone(match)
            origin = match[1]
            self.assertIn(origin, registered)
            self.assertEqual("// LF-normalized SHA-256: " + registered[origin], lines[1])
            origins.add(origin)
        required = {row["path"] for row in inventory if row["mode"] not in ("direct", "reference-only")}
        self.assertTrue(required.issubset(origins), required - origins)
        self.assertTrue(all(len(row["sha256"]) == 64 for row in inventory))
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            path = repo / "sample.kt"
            path.write_bytes(b"package sample\r\nfun ok() = true\r\n")
            self.assertEqual("package sample\nfun ok() = true\n", EXTRACTOR.read(repo, "sample.kt"))

    def test_shader_and_catalog_resource_digests_follow_original_sources(self):
        assets = EXTRACTOR.asset_inventory(ROOT)
        self.assertEqual(11, len(assets))
        self.assertEqual(len(assets), len({row["path"] for row in assets}))
        body = next(path.read_text(encoding="utf-8") for path in self.files if path.name == "DesktopPluginAssetHashes.kt")
        for row in assets:
            self.assertEqual("asset", row["mode"])
            self.assertIn(row["path"] + '\" to \"' + row["sha256"], body)

    def test_original_background_jobs_are_owned_and_awaitable(self):
        for path in self.files:
            source = path.read_text(encoding="utf-8")
            self.assertNotIn("CoroutineScope(SupervisorJob() + Dispatchers.", source)
        manager = self.output_for("com.android.purebilibili.core.plugin", "PluginManager")
        self.assertIn("DesktopPluginScopeRegistry.create", manager)
        today = self.output_for("com.android.purebilibili.feature.plugin", "TodayWatchPlugin")
        self.assertIn("DesktopPluginScopeRegistry.create", today)
        self.assertIn("Dispatchers.IO)", today)

    def test_today_result_conversion_preserves_original_scores_reasons_and_creator_groups(self):
        original = EXTRACTOR.read(ROOT, EXTRACTOR.BASE + "feature/home/HomeViewModel.kt")
        body = self.output_for("com.android.purebilibili.feature.home", "DesktopTodayWatchPlanConversion")
        parser = EXTRACTOR.parser_for(ROOT)
        selector = EXTRACTOR.media_extractor(ROOT)
        conversion = selector.function(original, "toTodayWatchPlan", parser)
        vm_spec = importlib.util.spec_from_file_location("original_home_vm", ROOT / "desktop/tools/extract-upstream-home-viewmodel.py")
        vm_generator = importlib.util.module_from_spec(vm_spec)
        vm_spec.loader.exec_module(vm_generator)
        with tempfile.TemporaryDirectory() as directory:
            vm_generator.generate(ROOT, Path(directory))
            vm_body = (Path(directory) / "com/android/purebilibili/feature/home/DesktopOriginalHomeViewModel.kt").read_text(encoding="utf-8")
        self.assertIn(conversion.replace("private fun", "internal fun", 1), vm_body)
        self.assertNotIn("fun RecommendationResult.toTodayWatchPlan", body)
        start = original.index("private fun RecommendationGroup?.toTodayUpRanks")
        end = original.index("\nprivate data class TodayWatchRuntimeConfig", start)
        self.assertIn(original[start:end].strip(), body)


if __name__ == "__main__":
    unittest.main()
