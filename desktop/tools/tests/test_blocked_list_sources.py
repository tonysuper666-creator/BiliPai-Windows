from pathlib import Path
import hashlib
import importlib.util
import json
import unittest

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("blocked_list_product_extractor", REPO / "desktop/tools/extract-upstream-blocked-list-ui.py")
extractor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extractor)
GENERATED = REPO / "desktop/build/generated/blocked-list-ui"


class BlockedListSources(unittest.TestCase):
    def test_original_management_content_and_helpers_reach_the_product_unchanged(self):
        source = extractor.original(REPO, extractor.SOURCES[0])
        marker = "@Composable\nfun BlockedListContent("
        generated = (GENERATED / "com/android/purebilibili/feature/settings/DesktopUpstreamBlockedListContent.kt").read_text(encoding="utf-8")
        reconstructed = generated[generated.index(marker):]
        # Only the approved Windows dissolve caller identities differ. The
        # complete management UI and helpers must invert to the canonical body.
        for original, windows in (
            ("com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(",
             "com.bilipai.desktop.ui.DesktopReplyDissolvableContainer("),
            ("com.android.purebilibili.core.ui.animation.DissolveAnimationPreset.",
             "com.bilipai.desktop.ui.DissolveAnimationPreset."),
        ):
            self.assertEqual(source[source.index(marker):].count(original), 1)
            self.assertEqual(reconstructed.count(windows), 1)
            reconstructed = reconstructed.replace(windows, original, 1)
        self.assertEqual(source[source.index(marker):], reconstructed)
        self.assertIn("import com.bilipai.desktop.ui.jiggleOnDissolve\n", generated)
        self.assertNotIn("import com.android.purebilibili.core.ui.animation.jiggleOnDissolve\n", generated)

    def test_original_padding_and_network_pacing_reach_the_product(self):
        source = extractor.original(REPO, extractor.SOURCES[2])
        marker = "internal val LocalSettingsTopContentPadding"
        end = source.index("@Composable\ninternal fun SettingsBottomBarScrollEffect", source.index(marker))
        scaffold = (REPO / "desktop/build/generated/static-settings-pages/com/android/purebilibili/feature/settings/ui/SettingsPageScaffold.kt").read_text(encoding="utf-8")
        generated_end = scaffold.index("@Composable\ninternal fun SettingsBottomBarScrollEffect", scaffold.index(marker))
        self.assertEqual(source[source.index(marker):end], scaffold[scaffold.index(marker):generated_end])
        self.assertFalse((GENERATED / "com/android/purebilibili/feature/settings/ui/DesktopUpstreamBlockedListPadding.kt").exists())
        pacing = (GENERATED / "com/android/purebilibili/data/repository/DesktopUpstreamBlockedListPacing.kt").read_text(encoding="utf-8")
        for path in (extractor.SOURCES[3], extractor.SOURCES[4]):
            for line in extractor.original(REPO, path).splitlines():
                if line.startswith("private const val BLOCKED_") and any(part in line for part in ("DELAY_MS", "PAGE_SIZE", "MAX_PAGES")):
                    self.assertIn(line, pacing)

    def test_product_badges_keep_every_original_binary_byte_and_manifest_digest(self):
        resources = json.loads((REPO / "desktop/upstream-sources.json").read_bytes())["resources"]
        inventory = {row["path"]: row for row in resources}
        for name in ("lv0", "lv1", "lv2", "lv3", "lv4", "lv5", "lv6", "lv6_s"):
            relative = f"app/src/main/res/drawable-nodpi/{name}.png"
            original = (REPO / relative).read_bytes()
            packaged = (REPO / f"desktop/build/resources/main/blocked-up-badges/{name}.png").read_bytes()
            self.assertEqual(original, packaged)
            self.assertTrue(original.startswith(b"\x89PNG\r\n\x1a\n"))
            self.assertEqual("raw", inventory[relative]["hashNormalization"])
            self.assertEqual(hashlib.sha256(original).hexdigest(), inventory[relative]["sha256"])


if __name__ == "__main__":
    unittest.main()
