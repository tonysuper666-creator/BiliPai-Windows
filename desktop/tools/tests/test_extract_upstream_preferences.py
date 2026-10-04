from pathlib import Path
import hashlib
import importlib.util
import re
import tempfile
import unittest

HERE = Path(__file__).resolve().parent; REPO = HERE.parents[2]
TOOL = REPO / "desktop/tools/extract-upstream-preferences.py"
if not TOOL.is_file(): TOOL = HERE / "extract-preferences-platform.py"
spec = importlib.util.spec_from_file_location("preferences", TOOL)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
host = module.helper(REPO)

def body(path): return path.read_text(encoding="utf-8").split("\n", 2)[2]

def signatures(source):
    tokens = host.parser_for(REPO).kotlin_tokens(source); result = []
    for i, token in enumerate(tokens[:-2]):
        if token[0] != "fun" or source[source.rfind("\n", 0, token[1]) + 1:token[1]] not in ("", "internal ", "private "): continue
        start = i + 1
        while start < len(tokens) and tokens[start][0] != "(": start += 1
        if start >= len(tokens): continue
        if not any(t[0].startswith("App") for t in tokens[i+1:start]): continue
        end = start; depth = 1
        while depth:
            end += 1; depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
        result.append(source[token[1]:tokens[end][2]])
    return result

class PreferenceExtractionTest(unittest.TestCase):
    def test_all_direct_bodies_are_original_verbatim(self):
        with tempfile.TemporaryDirectory() as name:
            output = Path(name); emitted = module.generate(REPO, output, standalone=True)
            for path in module.DIRECT:
                original = host.read(REPO, path)
                self.assertEqual(body(host.output_target(output, path, original)), original.strip() + "\n", path)
            self.assertEqual(len(emitted), 28)

    def test_product_outputs_only_adapters_and_selected_host_local(self):
        with tempfile.TemporaryDirectory() as name:
            emitted = module.generate(REPO, Path(name))
            self.assertEqual({p.name for p in emitted}, {"AdaptivePreferenceComponents.kt", "AdaptiveContentCardComponents.kt", "AppSelectionPreferenceComponents.kt", "AppWallpaperBackdropLocal.kt", "AppNavigationComponents.kt"})
            for path in emitted: self.assertNotRegex(body(path), r"(?m)^import (?:android\.|androidx\.activity\.)")

    def test_all_original_signatures_including_generic_selection_remain_exact(self):
        for path in module.ADAPTED:
            original = host.read(REPO, path)
            self.assertEqual(signatures(original), signatures(module.adapt(path, original, host)), path)
        selection = host.read(REPO, module.BASE + "components/AppSelectionPreferenceComponents.kt")
        self.assertTrue(any("<T> AppSingleChoicePreference" in signature for signature in signatures(selection)))

    def test_preference_renderer_preserves_body_except_duplicate_import_and_current_query_binding(self):
        path = module.BASE + "components/AdaptivePreferenceComponents.kt"; original = host.read(REPO, path)
        duplicate = "import com.android.purebilibili.core.ui.LocalAppThemeConfig\n"
        self.assertEqual(original.count(duplicate), 2)
        expected = original.replace(duplicate, "", 1)
        expected = expected.replace("import androidx.compose.runtime.remember\n", "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberUpdatedState\n")
        expected = expected.replace("val textFieldState = rememberTextFieldState(initialText = query)\n        LaunchedEffect(textFieldState)",
            "val textFieldState = rememberTextFieldState(initialText = query)\n        val currentQuery by rememberUpdatedState(query)\n        val currentOnQueryChange by rememberUpdatedState(onQueryChange)\n        LaunchedEffect(textFieldState)")
        expected = expected.replace("if (updated != query) {\n                        onQueryChange(updated)", "if (updated != currentQuery) {\n                        currentOnQueryChange(updated)")
        self.assertEqual(module.adapt(path, original, host), expected)

    def test_query_collector_drift_fails_closed(self):
        path = module.BASE + "components/AdaptivePreferenceComponents.kt"; original = host.read(REPO, path)
        for anchor in ("import androidx.compose.runtime.remember\n",
                       "val textFieldState = rememberTextFieldState(initialText = query)\n        LaunchedEffect(textFieldState)",
                       "if (updated != query) {\n                        onQueryChange(updated)"):
            with self.assertRaises(ValueError):
                module.adapt(path, original.replace(anchor, "// original collector changed\n", 1), host)

    def test_window_adapter_has_only_current_window_binding_import(self):
        path = module.BASE + "components/AppSelectionPreferenceComponents.kt"; original = host.read(REPO, path)
        expected = original.replace("import androidx.compose.ui.platform.LocalConfiguration",
            "import com.bilipai.desktop.appearance.DesktopWindowConfiguration as LocalConfiguration")
        self.assertEqual(module.adapt(path, original, host), expected)
        self.assertIn("val maxDialogHeight = (configuration.screenHeightDp * 0.8f).dp", expected)
        binding = REPO / "desktop/src/main/kotlin/com/bilipai/desktop/appearance/DesktopWindowConfiguration.kt"
        if not binding.exists(): binding = REPO / "desktop/.local/preference-parity/src/DesktopWindowConfiguration.kt"
        source = binding.read_text(encoding="utf-8")
        self.assertIn("LocalWindowInfo.current.containerSize.height", source)
        self.assertNotIn("Toolkit", source)

    def test_tag_card_font_padding_adapter_changes_only_android_text_flag(self):
        path = module.BASE + "components/AdaptiveContentCardComponents.kt"; original = host.read(REPO, path)
        expected = original.replace("import androidx.compose.ui.text.PlatformTextStyle\n", "").replace("        platformStyle = PlatformTextStyle(includeFontPadding = false),\n", "")
        self.assertEqual(module.adapt(path, original, host), expected)
        self.assertIn("val metrics = resolveAppTagChipMetrics(size)", expected)
        self.assertIn("fontSize = baseLabelStyle.fontSize * metrics.fontScale", expected)

    def test_wallpaper_default_is_original_composition_local_not_fake_provider(self):
        with tempfile.TemporaryDirectory() as name:
            emitted = module.generate(REPO, Path(name))
            local = next(p for p in emitted if p.name == "AppWallpaperBackdropLocal.kt")
            declaration = re.search(r"(?m)^val LocalGlobalWallpaperBackdropVisible[^\n]+", body(local))[0]
            self.assertIn(declaration, host.read(REPO, module.POLICIES[0]))

    def test_navigation_facade_has_one_explicit_producer_and_no_body_changes(self):
        path = module.BASE + "components/AppNavigationComponents.kt"
        original = host.read(REPO, path)
        self.assertIn(path, module.ADAPTED)
        self.assertNotIn(path, module.DIRECT)
        self.assertEqual(module.adapt(path, original, host), original)
        with tempfile.TemporaryDirectory() as name:
            generated = next(p for p in module.generate(REPO, Path(name)) if p.name == "AppNavigationComponents.kt")
            self.assertEqual(body(generated), original.strip() + "\n")
        inventory = {entry["path"]: entry for entry in module.inventory(REPO)}
        self.assertEqual(inventory[path]["mode"], "platform-adapter-reference")

    def test_inventory_lf_hashes_and_direct_adapter_boundary(self):
        inventory = module.inventory(REPO)
        self.assertEqual(len(inventory), 28); self.assertEqual(len({e["path"] for e in inventory}), 28)
        self.assertEqual(sum(e["mode"] == "direct" for e in inventory), 23)
        for entry in inventory:
            original = host.read(REPO, entry["path"])
            self.assertNotIn("\r", original)
            self.assertEqual(entry["sha256"], hashlib.sha256(original.encode()).hexdigest())

    def test_stale_cleanup_cannot_remove_user_managed_direct_source(self):
        with tempfile.TemporaryDirectory() as name:
            output = Path(name); first, second = module.DIRECT[:2]
            old = host.write(output, first, host.read(REPO, first), host.read(REPO, first))
            manual = host.output_target(output, second, host.read(REPO, second))
            manual.parent.mkdir(parents=True, exist_ok=True); manual.write_text("// Preserved user file\n", encoding="utf-8")
            module.generate(REPO, output)
            self.assertFalse(old.exists()); self.assertEqual(manual.read_text(), "// Preserved user file\n")

    def test_original_header_drift_fails_closed(self):
        path = module.BASE + "components/AdaptivePreferenceComponents.kt"; original = host.read(REPO, path)
        drift = original.replace("import com.android.purebilibili.core.ui.LocalAppThemeConfig\n", "", 1)
        with self.assertRaisesRegex(ValueError, "duplicate preference import changed"):
            module.adapt(path, drift, host)

if __name__ == "__main__": unittest.main()
