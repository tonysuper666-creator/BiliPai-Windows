from pathlib import Path
import hashlib
import importlib.util
import re
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
TOOL = REPO / "desktop/tools/extract-upstream-components.py"
if not TOOL.is_file(): TOOL = HERE / "extract-components-platform.py"
spec = importlib.util.spec_from_file_location("components", TOOL)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
host = module.helper(REPO)

def body(path):
    return path.read_text(encoding="utf-8").split("\n", 2)[2]

def signatures(source):
    tokens = host.parser_for(REPO).kotlin_tokens(source)
    result = []
    for i in range(len(tokens) - 2):
        if tokens[i][0] != "fun" or not tokens[i+1][0].startswith("App") or tokens[i+2][0] != "(": continue
        depth = 1; end = i + 2
        while depth:
            end += 1; depth += (tokens[end][0] == "(") - (tokens[end][0] == ")")
        result.append(source[tokens[i][1]:tokens[end][2]])
    return result

# Direct paths a retained slice layer advances to its whole pinned file before the producer writes them: v040 liquid
# (de22734e7), v040 chrome actions (59599b6a7), v042 optical (e3a868515), v043 theme chrome (326467691) and v0.4.6
# design-system leaves A (ad89fd764) and B (97ee63f5c). Each maps to the archive of the last layer that advances it.
ADVANCED = {
    "AppThemeConfig.kt": "v040-liquid-value-controls",
    "AppSurfaceTokens.kt": "v042-optical-dependencies",
    "AppChromeSizeTokens.kt": "v046-design-system-leaves-a",
    "AppPopupSurface.kt": "v046-design-system-leaves-a",
    "ButtonVisualPolicy.kt": "v043-theme-chrome",
    "components/AppPrimitiveComponents.kt": "v043-theme-chrome",
    "components/AppPrimitiveRendererPolicy.kt": "v040-chrome-actions",
    "components/AppDesktopInteraction.kt": "v046-design-system-leaves-b",
    "components/AppPrimaryButton.kt": "v040-chrome-actions",
    "components/AppCard.kt": "v046-design-system-leaves-b",
    "components/AppSwitch.kt": "v040-liquid-value-controls",
    "components/AppIconButton.kt": "v040-chrome-actions",
    "renderer/material3/AppMaterial3Switch.kt": "v040-liquid-value-controls",
    "renderer/material3/AppMaterial3IconButton.kt": "v040-chrome-actions",
    "renderer/miuix/AppMiuixActionPrimitives.kt": "v040-chrome-actions",
    "renderer/miuix/AppMiuixSwitch.kt": "v043-theme-chrome",
    "renderer/miuix/AppMiuixSlider.kt": "v040-liquid-value-controls",
    "renderer/miuix/AppMiuixIconButton.kt": "v040-chrome-actions",
}
PACKAGE_ROOT = "com/android/purebilibili/"
# Direct paths a layer selects for product output: every advanced path except AppThemeConfig.kt (the liquid layer
# replaces its bytes but does not list it for output) plus AppIcon.kt, which the chrome-action layer (59599b6a7)
# selects unchanged because its v0.4.0 bytes equal the original.
PRODUCT_DIRECT = (set(ADVANCED) - {"AppThemeConfig.kt"}) | {"components/AppIcon.kt"}
# Non-direct outputs the chained emitters add in every mode: chrome actions (59599b6a7), native chrome (894b8c2ff),
# leaves A (ad89fd764), the liquid value controls and their desktop color resources (de22734e7).
LAYER_OUTPUTS = {PACKAGE_ROOT + "core/ui/" + relative for relative in (
    "AppEntryIconPalette.kt", "AppSemanticVisualPolicy.kt", "ButtonSlotColor.kt", "MatchedLiquidIndicatorGeometry.kt",
    "MediaContrastPalette.kt", "animation/LiquidIndicatorMotion.kt", "animation/LiquidIndicatorVelocity.kt",
    "components/AppActionColors.kt", "components/AppChromeOverflow.kt", "components/AppElasticIndication.kt",
    "components/AppLiquidValueControls.kt", "components/AppTextSearchHighlight.kt",
    "components/BiliPaiTopBarIconDecorator.kt", "components/DesktopLiquidValueColorResources.kt",
    "effect/LiquidLens.kt", "motion/AppTabIndicatorMotion.kt", "wallpaper/GlobalWallpaperBackdrop.kt")}

class ComponentsExtractionTest(unittest.TestCase):
    def test_direct_sources_are_exact_and_already_reused_renderers_are_not_duplicated(self):
        with tempfile.TemporaryDirectory() as name:
            output = Path(name)
            emitted = module.generate(REPO, output, standalone=True)
            for path in module.DIRECT:
                original = host.read(REPO, path)
                relative = path[len(module.BASE):]
                if relative in ADVANCED:
                    selected = (REPO / "desktop/upstream-slices" / ADVANCED[relative] / Path(path).name).read_text(encoding="utf-8")
                    self.assertNotEqual(selected, original, path)
                    original = selected
                target = host.output_target(output, path, original)
                if path in module.REUSED: self.assertFalse(target.exists()); continue
                self.assertEqual(body(target), original.strip() + "\n", path)
                self.assertIn(hashlib.sha256(original.encode()).hexdigest(), target.read_text(encoding="utf-8").split("\n")[1])
            self.assertEqual(len(emitted), len(module.DIRECT) - len(module.REUSED) + len(module.ADAPTED) + len(module.POLICIES) + len(LAYER_OUTPUTS))
            self.assertTrue({output / relative for relative in LAYER_OUTPUTS} <= set(emitted))

    def test_product_generation_emits_only_platform_bindings_selected_policy_and_retained_slice_outputs(self):
        with tempfile.TemporaryDirectory() as name:
            output = Path(name)
            emitted = module.generate(REPO, output)
            self.assertEqual(len(emitted), len(set(emitted)))
            bindings = {"core/ui/components/AppText.kt", "core/ui/components/AppSlider.kt", "core/ui/AppContentDialogLayoutPolicy.kt",
                        "core/ui/components/AppScrollableUnderlinePolicy.kt", "core/ui/AdaptiveDialogComponents.kt"}
            selected = {"core/ui/" + relative for relative in PRODUCT_DIRECT}
            self.assertEqual({p.relative_to(output).as_posix() for p in emitted},
                             {PACKAGE_ROOT + relative for relative in bindings | selected} | LAYER_OUTPUTS)
            for path in emitted: self.assertNotRegex(body(path), r"(?m)^import android\.")

    def test_all_adapter_public_component_parameters_defaults_and_overloads_are_original(self):
        for path in module.ADAPTED:
            original = host.read(REPO, path)
            self.assertEqual(signatures(original), signatures(module.adapt(path, original, host)), path)
        self.assertEqual(len(signatures(host.read(REPO, module.BASE + "components/AppText.kt"))), 4)

    def test_dialog_adapter_preserves_original_policy_and_properties_with_desktop_width_constraints(self):
        path = module.BASE + "AppContentDialogLayoutPolicy.kt"
        original = host.read(REPO, path)
        expected = original.replace("        securePolicy = base.securePolicy,\n", "").replace("        decorFitsSystemWindows = base.decorFitsSystemWindows,\n", "")
        expected = expected.replace("import androidx.compose.foundation.layout.wrapContentHeight\n",
            "import androidx.compose.foundation.layout.wrapContentHeight\nimport androidx.compose.foundation.layout.wrapContentWidth\n")
        expected = expected.replace("        .padding(horizontal = policy.horizontalPaddingDp.dp)\n        .widthIn(",
            "        .padding(horizontal = policy.horizontalPaddingDp.dp)\n        .wrapContentWidth()\n        .widthIn(")
        self.assertEqual(module.adapt(path, original, host), expected)
        self.assertIn("dismissOnBackPress = base.dismissOnBackPress", expected)
        self.assertIn("dismissOnClickOutside = base.dismissOnClickOutside", expected)

    def test_slider_has_only_monotonic_platform_clock_import_substitution(self):
        path = module.BASE + "components/AppSlider.kt"
        original = host.read(REPO, path)
        expected = original.replace("import android.os.SystemClock", "import com.bilipai.desktop.appearance.DesktopMonotonicClock as SystemClock")
        self.assertEqual(module.adapt(path, original, host), expected)
        self.assertIn("nowMs - lastHapticTimeMs >= 55L", expected)

    def test_adaptive_dialog_is_whole_original_except_two_android_properties(self):
        path = module.BASE + "AdaptiveDialogComponents.kt"
        original = host.read(REPO, path)
        expected = original
        for android_property in (
            "                securePolicy = properties.securePolicy,\n",
            "                decorFitsSystemWindows = false,\n",
        ):
            self.assertEqual(expected.count(android_property), 1)
            expected = expected.replace(android_property, "", 1)
        self.assertEqual(module.adapt(path, original, host), expected)
        inventory = {entry["path"]: entry for entry in module.inventory(REPO)}
        self.assertEqual(inventory[path]["mode"], "policy-extract")
        self.assertNotIn(path, module.DIRECT)
        with tempfile.TemporaryDirectory() as name:
            generated = next(p for p in module.generate(REPO, Path(name)) if p.name == "AdaptiveDialogComponents.kt")
            self.assertEqual(body(generated), expected.strip() + "\n")

    def test_clipboard_preserves_gesture_and_copy_success_gate_without_reading_user_clipboard(self):
        path = module.BASE + "components/AppText.kt"
        original = host.read(REPO, path)
        adapted = module.adapt(path, original, host)
        gesture = original[original.index("        awaitEachGesture {"):original.index("                val clipboard = context.getSystemService")]
        self.assertIn(gesture, adapted)
        self.assertIn("if (clipboard.copyText(text.trim()))", adapted)
        self.assertNotIn("Toast", adapted)
        platform = REPO / "desktop/src/main/kotlin/com/bilipai/desktop/appearance/DesktopTextClipboard.kt"
        if not platform.is_file(): platform = REPO / "desktop/.local/component-parity/src/DesktopTextClipboard.kt"
        binding = platform.read_text(encoding="utf-8")
        # 418d1831b: the binding writes through the desktop share link copier, which owns the AWT clipboard write.
        self.assertIn("    private val delegate = ReportingDesktopTextClipboard(AwtDesktopShare()) { failure.value = it }\n", binding)
        self.assertIn("        share.copyLink(value)\n        report(null)\n        true\n", binding)
        share = (REPO / "desktop/src/main/kotlin/com/bilipai/desktop/platform/share/AwtDesktopShare.kt").read_text(encoding="utf-8")
        self.assertIn("    private val clipboard: () -> Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },\n", share)
        self.assertIn("        fun copy() = clipboard().setContents(StringSelection(link), null)\n", share)
        for source in (binding, share): self.assertNotRegex(source, r"getContents\(|getData\(")

    def test_scrollable_default_is_extracted_from_original_not_reimplemented(self):
        with tempfile.TemporaryDirectory() as name:
            emitted = module.generate(REPO, Path(name))
            policy = next(p for p in emitted if p.name == "AppScrollableUnderlinePolicy.kt")
            declaration = re.search(r"(?m)^internal fun resolvePiliPlusScrollableUnderlineMinWidth[^\n]+", body(policy))[0]
            self.assertIn(declaration, host.read(REPO, module.POLICIES[0]))

    def test_source_hashes_are_lf_normalized_and_unique(self):
        inventory = module.inventory(REPO)
        self.assertEqual(len(inventory), 53)
        self.assertEqual(len({entry["path"] for entry in inventory}), len(inventory))
        for entry in inventory:
            source = host.read(REPO, entry["path"])
            self.assertNotIn("\r", source)
            self.assertEqual(entry["sha256"], hashlib.sha256(source.encode()).hexdigest())

    def test_old_direct_outputs_are_removed_only_if_owned_by_same_source(self):
        with tempfile.TemporaryDirectory() as name:
            output = Path(name)
            first, second = module.DIRECT[:2]
            old = host.write(output, first, host.read(REPO, first), host.read(REPO, first))
            manual = host.output_target(output, second, host.read(REPO, second))
            manual.parent.mkdir(parents=True, exist_ok=True); manual.write_text("// User-managed file\n", encoding="utf-8")
            module.generate(REPO, output)
            self.assertFalse(old.exists())
            self.assertEqual(manual.read_text(encoding="utf-8"), "// User-managed file\n")

if __name__ == "__main__": unittest.main()
