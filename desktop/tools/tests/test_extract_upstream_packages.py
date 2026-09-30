import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = Path(__file__).with_name("extract-upstream-packages.py")
if not SCRIPT.exists():
    SCRIPT = ROOT / "desktop/tools/extract-upstream-packages.py"
spec = importlib.util.spec_from_file_location("packages", SCRIPT)
EXTRACTOR = importlib.util.module_from_spec(spec)
spec.loader.exec_module(EXTRACTOR)


class PackageSourceParityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.files = EXTRACTOR.generate(ROOT, Path(cls.temporary.name))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def file(self, name):
        return next(path.read_text(encoding="utf-8") for path in self.files if path.name == name + ".kt")

    def test_inventory_deduplicates_direct_and_extracted_sources(self):
        inventory = EXTRACTOR.inventory(ROOT)
        self.assertEqual(13, len(inventory))
        self.assertEqual(len(inventory), len({row["path"] for row in inventory}))
        self.assertEqual(sum(row["mode"] != "direct" for row in inventory) + 1, len(self.files))
        generated = {path.name for path in self.files}
        for path in EXTRACTOR.DIRECT:
            self.assertNotIn(Path(path).name, generated)

    def test_archive_and_signature_protocols_stay_direct(self):
        paths = {row["path"]: row["mode"] for row in EXTRACTOR.inventory(ROOT)}
        for name in ("ExternalKotlinPluginPackageReader", "UiSkinPackageReader", "UiSkinImportPackageResolver", "UiSkinModels"):
            self.assertEqual("direct", next(mode for path, mode in paths.items() if path.endswith("/" + name + ".kt")))

    def test_original_store_only_receives_atomic_document_platform_binding(self):
        helper = EXTRACTOR.helper(ROOT)
        for path in EXTRACTOR.EXTRACTED:
            if not path.endswith("InstallStore.kt"):
                continue
            source = helper.platform_context(helper.read(ROOT, path))
            expected = helper.substitute(source, "file.writeText(json.encodeToString(value))",
                "com.bilipai.desktop.plugins.writeDesktopPluginDocument(file, json.encodeToString(value))")
            self.assertTrue(self.file(Path(path).stem).endswith(expected.strip() + "\n"))
        self.assertIn("enabled = false", self.file("ExternalKotlinPluginInstallStore"))
        self.assertNotIn("ClassLoader", self.file("ExternalKotlinPluginInstallStore"))

    def test_selection_observes_actual_preferences_and_reuses_original_read_set(self):
        host = EXTRACTOR.helper(ROOT)
        selector = host.media_extractor(ROOT)
        parser = host.parser_for(ROOT)
        path = EXTRACTOR.BASE + "core/plugin/skin/UiSkinSettingsStore.kt"
        original = host.read(ROOT, path)
        output = self.file("UiSkinSettingsStore")
        for name in ("readState", "setSelection"):
            self.assertEqual(selector.function(original, name, parser), selector.function(output, name, parser))
        self.assertIn('context.store.snapshot("ui_skin_settings").map { readState(context.applicationContext) }', output)
        self.assertNotIn("callbackFlow", output)
        self.assertNotIn("android.content.SharedPreferences", output)

    def test_activation_and_surface_selection_keep_original_decisions(self):
        host = EXTRACTOR.helper(ROOT)
        selector = host.media_extractor(ROOT)
        parser = host.parser_for(ROOT)
        for name, path, file in (("resolveUiSkinState", "UiSkinActivationPolicy", "UiSkinActivationPolicy"),
                ("assetPath", "UiSkinComposition", "DesktopUiSkinCompositionPolicy")):
            original = host.read(ROOT, EXTRACTOR.BASE + "core/plugin/skin/" + path + ".kt")
            self.assertIn(selector.function(original, name, parser), self.file(file))

    def test_catalog_classpath_hash_uses_original_lf_normalized_snapshot(self):
        import hashlib
        host = EXTRACTOR.helper(ROOT)
        digest = hashlib.sha256(host.read(ROOT, EXTRACTOR.ASSET).encode()).hexdigest()
        self.assertIn(digest, self.file("DesktopSkinAssetHash"))
        self.assertIn("DesktopSkinCatalogResource.open(ASSET_NAME)", self.file("SkinCatalog"))

    def test_profile_video_repeat_rule_is_original_and_platform_values_are_bound(self):
        host = EXTRACTOR.helper(ROOT)
        original = host.read(ROOT, EXTRACTOR.BASE + "feature/profile/ProfileScreen.kt")
        method = host.media_extractor(ROOT).function(original, "resolveProfileSkinVideoRepeatMode", host.parser_for(ROOT))
        output = self.file("DesktopProfileSkinVideoPolicy")
        self.assertIn(method, output)
        self.assertIn("DesktopSkinVideoRepeat as Player", output)

    def test_stale_direct_generation_is_pruned_without_deleting_foreign_file(self):
        host = EXTRACTOR.helper(ROOT)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            path = EXTRACTOR.DIRECT[0]
            stale = host.write(output, path, host.read(ROOT, path), host.read(ROOT, path))
            foreign = host.output_target(output, EXTRACTOR.DIRECT[1], host.read(ROOT, EXTRACTOR.DIRECT[1]))
            foreign.parent.mkdir(parents=True, exist_ok=True)
            foreign.write_text("// Independent user source\n", encoding="utf-8")
            EXTRACTOR.generate(ROOT, output)
            self.assertFalse(stale.exists())
            self.assertEqual("// Independent user source\n", foreign.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
