from pathlib import Path
import importlib.util
import sys
import unittest


REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "desktop/tools"))
GENERATED = REPO / "desktop/build/generated/diagnostics/sources"
spec = importlib.util.spec_from_file_location("diagnostic_product_extractor", REPO / "desktop/tools/extract-upstream-diagnostics.py")
extractor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extractor)
host = extractor.load(REPO / "desktop/tools/extract-upstream-plugins.py", "diagnostic_product_parser_host")
helper = host.media_extractor(REPO)
parser = helper.parser_for(REPO)


def generated(name):
    return next(GENERATED.rglob(name)).read_text(encoding="utf-8")


class DiagnosticSources(unittest.TestCase):
    def test_entire_original_pure_region_reaches_product_with_documented_adapters(self):
        original = extractor.read(REPO, extractor.LOGGER)
        expected = original[original.index("private const val LOG_DIRECTORY_NAME"):original.index("/**\n *  统一日志工具类")]
        expected = expected.replace("LogCollector.", "DesktopDiagnosticCollector.").replace(
            'appendLine("Android版本: $androidRelease (API $apiLevel)")',
            'appendLine("系统版本: $androidRelease；运行时: $apiLevel")',
        )
        self.assertTrue(generated("DesktopDiagnosticPolicy.kt").rstrip().endswith(expected.rstrip()))

    def test_original_collector_retains_sanitizer_ring_and_dedupe(self):
        original = extractor.read(REPO, extractor.LOGGER)
        collector = original[original.index("object LogCollector {"):]
        product = generated("DesktopDiagnosticCollector.kt")
        for name in ("getEntries", "getCount", "clear"):
            self.assertEqual(helper.function(collector, name, parser), helper.function(product, name, parser), name)
        # v0.4.6 shares the immutable redaction patterns: the sanitizer is the complete v0.4.6 original, whose
        # inlined patterns equal the v0.2.5 sanitizer (proved in test_v046_mfx_small_protocol_fixes_source).
        from v046_mfx_small_protocol_fixes import fixed
        archived = fixed(REPO)[0]["Logger.kt"]
        newest = archived[archived.index("object LogCollector {"):]
        self.assertEqual(helper.function(newest, "sanitizeMessage", parser), helper.function(product, "sanitizeMessage", parser))
        self.assertNotEqual(helper.function(collector, "sanitizeMessage", parser), helper.function(product, "sanitizeMessage", parser))
        actual = helper.function(product, "add", parser).replace("clock()", "System.currentTimeMillis()").replace(
            "persist(it, basicDiagnostic)", "appendEntryToRuntimeFile(it, basicDiagnostic)"
        )
        self.assertEqual(helper.function(collector, "add", parser), actual)

    def test_original_consent_dialog_body_reaches_product_unchanged(self):
        original = helper.function(extractor.read(REPO, extractor.SECTIONS), "DiagnosticsSection", parser)
        consent = original[original.index("    if (showEnhancedDiagnosticConsent)"):].rstrip()[:-1].rstrip()
        self.assertTrue(generated("SettingsDiagnosticFields.kt").rstrip().endswith(consent + "\n}"))

    def test_original_local_crash_policy_reaches_product_unchanged(self):
        original = helper.function(extractor.read(REPO, extractor.CRASH), "shouldPersistLocalCrashSnapshot", parser)
        self.assertEqual(original, helper.function(generated("DesktopLocalCrashPolicy.kt"), "shouldPersistLocalCrashSnapshot", parser))


if __name__ == "__main__":
    unittest.main()
