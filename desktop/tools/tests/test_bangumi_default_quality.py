"""Fresh sole-PGC generation contracts; no Gradle, native, account or GUI."""
from pathlib import Path
import ast
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest

BASE = Path(os.environ.get("BILIPAI_PGC_TEST_REPO", Path(__file__).resolve().parents[3]))
AFTER = Path(os.environ.get("BILIPAI_PGC_TEST_PRODUCER", BASE / "desktop/tools/extract-upstream-bangumi-player.py"))
BEFORE_OVERRIDE = os.environ.get("BILIPAI_PGC_TEST_BEFORE")
BASELINE_PRODUCER_SHA256_LF = '0e3447f828839da5f5ccaa0b68d6f7950d8a7b628239ae691c230e664acba568'
VM = "com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerViewModel.kt"

class BangumiDefaultQualityGeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("BILIPAI_PGC_TEST_OUTPUT"))
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        # Reconstruct the exact fixed pre-adapter producer, not the currently
        # installed producer as its own supposed old baseline.
        script = AFTER.read_text(encoding="utf8")
        start = script.index("\nPGC_DEFAULT_QUALITY_EDITS = ")
        end = script.index("\nfor recipe in RECIPES:", start)
        baseline = script[:start] + script[end:]
        baseline = baseline.replace(" body=bangumi_default_quality_delta(recipe['output'],body)\n", "")
        proof_line = next(line for line in baseline.splitlines(keepends=True)
            if line.startswith("(out/'pgc-default-quality-source-inventory.json')"))
        baseline = baseline.replace(proof_line, "")
        if hashlib.sha256(baseline.encode()).hexdigest() != BASELINE_PRODUCER_SHA256_LF:
            raise AssertionError("Fixed original sole PGC producer no longer reconstructs")
        cls.baseline_producer = cls.root / "baseline-producer.py"
        cls.baseline_producer.write_text(baseline, encoding="utf8", newline="\n")
        if BEFORE_OVERRIDE:
            if Path(BEFORE_OVERRIDE).read_text(encoding="utf8") != baseline:
                raise AssertionError("Explicit baseline is not the fixed reconstructed producer")
        cls.before = cls.root / "before"
        cls.after = cls.root / "after"
        env = os.environ.copy()
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        env["PYTHONPATH"] = str(BASE / "desktop/tools")
        for producer, output in ((cls.baseline_producer, cls.before), (AFTER, cls.after)):
            result = subprocess.run([sys.executable, str(producer), "--repo", str(BASE), "--output", str(output)],
                env=env, capture_output=True, text=True, timeout=30)
            if result.returncode != 0:
                raise AssertionError(result.stdout + result.stderr)
        cls.original = (cls.before / VM).read_text(encoding="utf8")
        cls.updated = (cls.after / VM).read_text(encoding="utf8")
        cls.proof = json.loads((cls.after / "pgc-default-quality-source-inventory.json").read_text(encoding="utf8"))

    def test_new_whole_body_delta_has_counted_complete_inverse(self):
        body = self.updated.split("\n", 1)[1]
        original = self.original.split("\n", 1)[1]
        proof = self.proof["fullBodies"]
        self.assertEqual(1, len(proof))
        self.assertEqual(VM, proof[0]["path"])
        self.assertTrue(self.proof["originalPinsUnchanged"])
        self.assertTrue(self.proof["initialOnly"])
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), proof[0]["afterSha256LF"])
        self.assertEqual(6, len(proof[0]["edits"]))
        for edit in reversed(proof[0]["edits"]):
            self.assertEqual(edit["count"], body.count(edit["after"]), edit["name"])
            body = body.replace(edit["after"], edit["before"])
        self.assertEqual(original, body)
        self.assertEqual(hashlib.sha256(original.encode()).hexdigest(), proof[0]["beforeSha256LF"])

    def test_existing_recipe_and_fixed_source_pins_are_unchanged(self):
        def constant(script, name):
            for node in ast.parse(script.read_text(encoding="utf8")).body:
                if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == name for t in node.targets):
                    if isinstance(node.value, ast.Call):
                        return json.loads(ast.literal_eval(node.value.args[0]))
                    return ast.literal_eval(node.value)
            self.fail(name + " not found")
        self.assertEqual(constant(self.baseline_producer, "RECIPES"), constant(AFTER, "RECIPES"))
        self.assertEqual(constant(self.baseline_producer, "PROTOCOL"), constant(AFTER, "PROTOCOL"))

    def test_every_other_actual_pgc_output_is_byte_identical(self):
        paths = [path.relative_to(self.before) for path in self.before.rglob("*") if path.is_file()]
        self.assertGreater(len(paths), 1)
        for relative in paths:
            if relative == Path(VM):
                continue
            self.assertEqual((self.before / relative).read_bytes(), (self.after / relative).read_bytes(), str(relative))
        self.assertEqual(set(paths) | {Path("pgc-default-quality-source-inventory.json")},
            {path.relative_to(self.after) for path in self.after.rglob("*") if path.is_file()})

    def test_original_manual_quality_and_drm_fallback_remain_complete(self):
        start = "    fun changeQuality(qualityId: Int) {"
        end = "    fun changeAudioQuality(audioQuality: Int) {"
        self.assertEqual(self.original.split(start, 1)[1].split(end, 1)[0],
            self.updated.split(start, 1)[1].split(end, 1)[0])
        start = "        if (playUrlResult.getOrNull()?.isDrm == true && requestedQn > 80) {"
        end = "        playUrlResult.onSuccess { playData ->"
        self.assertEqual(self.original.split(start, 1)[1].split(end, 1)[0],
            self.updated.split(start, 1)[1].split(end, 1)[0])
        self.assertIn("if (autoHighestEnabled) requestedQn else playData.quality", self.updated)
        self.assertIn("quality = selectedVideoQuality ?: playData.quality", self.updated)

if __name__ == "__main__":
    unittest.main()
