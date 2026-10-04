from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("bas_core", REPO / "desktop/tools/extract-upstream-bas-core.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class BasCoreExtractionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="bilipai-bas-core-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def copy_archive(self):
        repo = self.root / "repository"
        shutil.copytree(REPO / tool.ARCHIVE, repo / tool.ARCHIVE)
        return repo

    def test_fresh_output_preserves_all_ten_originals_and_37_pure_tests(self):
        main, tests = self.root / "main", self.root / "tests"
        self.assertEqual(10, len(tool.generate(REPO, main, tests)))
        proof = json.loads((main / "source-proof.json").read_text(encoding="utf-8"))
        self.assertEqual(37, proof["originalTests"])
        originals = tool.checked_inputs(REPO)
        for row in proof["files"]:
            root = tests if row["testSource"] else main
            raw = (root / row["generatedPath"]).read_bytes()
            original = originals[Path(row["source"]).name][1]
            self.assertEqual(original, raw)
            self.assertEqual(row["originalRawSha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual([], row["changes"])
        self.assertEqual(37, sum(raw.count(b"@Test") for row, raw in originals.values()))
        self.assertFalse(proof["compiled"])
        self.assertFalse(proof["actualMainRendered"])
        self.assertFalse(proof["windowsBasPainterImplemented"])
        self.assertFalse(proof["overallCanonicalBaselineAdvanced"])

    def test_original_core_and_tests_have_no_android_compose_or_media3_dependencies(self):
        for row, raw in tool.checked_inputs(REPO).values():
            for forbidden in (b"import android.", b"import androidx.", b"import com.bytedance."):
                self.assertNotIn(forbidden, raw)

    def test_raw_tamper_fails_before_any_output(self):
        repo = self.copy_archive()
        path = repo / tool.ARCHIVE / "BasLexer.kt"
        path.write_bytes(path.read_bytes() + b"\n")
        with self.assertRaisesRegex(ValueError, "Pinned BAS original bytes changed"):
            tool.generate(repo, self.root / "main", self.root / "tests")
        self.assertFalse((self.root / "main").exists())

    def test_repin_or_path_escape_manifest_is_rejected(self):
        repo = self.copy_archive()
        path = repo / tool.ARCHIVE / "manifest.json"
        original = json.loads(path.read_text(encoding="utf-8"))
        original["files"][0]["archiveFile"] = "../BasEasing.kt"
        path.write_text(json.dumps(original), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Fixed BAS manifest bytes changed"):
            tool.checked_inputs(repo)

    def test_unknown_generated_kotlin_is_protected(self):
        main = self.root / "main"
        main.mkdir()
        unknown = main / "Unknown.kt"
        unknown.write_text("unknown", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "protected"):
            tool.generate(REPO, main, self.root / "tests")
        self.assertEqual("unknown", unknown.read_text(encoding="utf-8"))
        self.assertFalse((self.root / "tests").exists())

    def test_changed_known_output_is_protected_but_exact_regeneration_is_allowed(self):
        main, tests = self.root / "main", self.root / "tests"
        tool.generate(REPO, main, tests)
        self.assertEqual(10, len(tool.generate(REPO, main, tests)))
        path = main / tool.PACKAGE / "BasEasing.kt"
        path.write_bytes(b"unknown replacement")
        with self.assertRaisesRegex(ValueError, "protected"):
            tool.generate(REPO, main, tests)
        self.assertEqual(b"unknown replacement", path.read_bytes())

    def test_overlapping_outputs_or_original_archive_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "must be separate"):
            tool.generate(REPO, self.root / "main", self.root / "main/tests")
        with self.assertRaisesRegex(ValueError, "overlaps original"):
            tool.generate(REPO, REPO / tool.ARCHIVE, self.root / "tests")


if __name__ == "__main__":
    unittest.main()
