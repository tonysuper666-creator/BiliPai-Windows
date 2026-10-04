from pathlib import Path
import hashlib
import importlib.util
import json
import os
import shutil
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("bas_filter", REPO / "desktop/tools/extract-upstream-bas-filter.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)
ARCHIVE = Path("desktop/upstream-slices/v029-bas-filter")


def physical(path):
    return Path("\\\\?\\" + str(path.resolve())) if os.name == "nt" else path


class BasFilterExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bilipai-bas-filter-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def copy_archive(self):
        repo = self.root / "repo"
        shutil.copytree(REPO / ARCHIVE, repo / ARCHIVE)
        return repo

    def test_whole_original_filter_and_four_original_tests_are_preserved(self):
        output = self.root / "generated"
        proof = tool.generate(REPO, output)
        self.assertTrue(proof["rawFilterWhole"])
        self.assertTrue(proof["rawTestsWhole"])
        self.assertFalse(proof["runtimeVisible"])
        for source, emitted in [
            ("BasDanmakuFilterPolicy.kt", proof["outputs"][0]),
            ("BasDanmakuFilterPolicyTest.kt", proof["outputs"][2]),
        ]:
            original = tool.read(REPO, source)
            generated = (output / emitted).read_text(encoding="utf-8")
            self.assertEqual(original, generated.split("\n", 2)[2])
        self.assertEqual(4, tool.read(REPO, "BasDanmakuFilterPolicyTest.kt").count("@Test"))

    def test_entire_selected_original_pipeline_roundtrips_all_counted_changes(self):
        original, adapted, edits = tool.adapt_pipeline(tool.read(REPO, "DanmakuManager.kt"))
        self.assertEqual(10, len(edits))
        self.assertTrue(all(count == 1 for _, _, count in edits))
        self.assertEqual(original, tool.inverse(adapted, edits))
        self.assertIn("type = 9,", adapted)
        self.assertIn("content = item.source,", adapted)
        self.assertLess(adapted.index(".filter(sourceItem)"), adapted.index("parseDesktopBasPluginProgram"))
        self.assertLess(adapted.index("parseDesktopBasPluginProgram"), adapted.index(".style(filtered)"))
        self.assertIn("copy(\n                        source = filtered.content,", adapted)
        for forbidden in ("take(2000)", "coerceIn(1, 6)", "catch (error: Exception)", "runCatching"):
            self.assertNotIn(forbidden, adapted)

    def test_producer_reproduces_actual_checked_in_main_and_original_test_consumers(self):
        output = self.root / "generated"
        proof = tool.generate(REPO, output)
        for emitted in proof["outputs"]:
            kind, relative = emitted.split("/", 1)
            checked_in = REPO / "desktop/src" / kind / "kotlin" / relative
            self.assertEqual((output / emitted).read_bytes(), physical(checked_in).read_bytes(), str(checked_in))
        generated = (output / proof["outputs"][1]).read_text(encoding="utf-8")
        self.assertEqual(1, generated.count("budget: DesktopBasDocumentBudget, onRejected:"))
        self.assertNotIn("DesktopBasDocumentBudget()", generated)
        self.assertIn("if (!budget.reserve(estimate))", generated)
        self.assertIn("if (!budget.retain(item))", generated)
        self.assertIn("return if (admitted.size == items.size) items else admitted", generated)

    def test_raw_repin_and_raw_newline_tampering_are_rejected(self):
        repo = self.copy_archive()
        source = repo / ARCHIVE / "BasDanmakuFilterPolicy.kt"
        source.write_bytes(source.read_bytes().replace(b"\n", b"\r\n"))
        with self.assertRaises(AssertionError):
            tool.read(repo, "BasDanmakuFilterPolicy.kt")
        manifest_path = repo / ARCHIVE / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["files"][0]["sha256Raw"] = hashlib.sha256(source.read_bytes()).hexdigest()
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaises(AssertionError):
            tool.read(repo, "BasDanmakuFilterPolicy.kt")

    def test_manifest_commit_path_escape_and_missing_input_are_rejected(self):
        for mutation in ("commit", "path", "missing"):
            repo = self.root / mutation
            shutil.copytree(REPO / ARCHIVE, repo / ARCHIVE)
            path = repo / ARCHIVE / "manifest.json"
            manifest = json.loads(path.read_text(encoding="utf-8"))
            if mutation == "commit":
                manifest["commit"] = "0" * 40
            elif mutation == "path":
                manifest["files"][0]["archiveFile"] = "../BasDanmakuFilterPolicy.kt"
            else:
                manifest["files"].pop()
            path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(AssertionError):
                tool.read(repo, "BasDanmakuFilterPolicy.kt")

    def test_selected_method_or_inverse_ambiguity_cannot_silently_change_original(self):
        source = tool.read(REPO, "DanmakuManager.kt")
        with self.assertRaises(AssertionError):
            tool.adapt_pipeline(source.replace("    private fun filterBasDanmakuForRender", "    private fun changedBasFilter", 1))
        original, adapted, edits = tool.adapt_pipeline(source)
        with self.assertRaises(AssertionError):
            tool.inverse(adapted + edits[-1][1], edits)
        self.assertEqual(original, tool.inverse(adapted, edits))


if __name__ == "__main__":
    unittest.main()
