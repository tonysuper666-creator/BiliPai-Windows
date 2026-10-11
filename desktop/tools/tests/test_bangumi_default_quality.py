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
CURRENT_PRE_QUALITY_PRODUCER_SHA256_LF = 'd152efc0f88fe4060364e3831e4447e92cacd2801c6659953f87b8cb18110fa8'
HISTORICAL_VM_ADAPTED_SHA256_LF = '9a9cd34aa68fcdea34da3c7d6b953894862e12428bb322703c94ff40a3d82f34'
HISTORICAL_QUALITY_LAUNCH = '        environment.launch {\n            environment.native.beginEpisode(currentState.seasonDetail, currentState.currentEpisode)\n'
VM = "com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiPlayerViewModel.kt"

class BangumiDefaultQualityGeneratorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(dir=os.environ.get("BILIPAI_PGC_TEST_OUTPUT"))
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        # Reconstruct both the current pre-quality stage and the fixed historical
        # whole producer; the later current-quality recipe is a separate change.
        script = AFTER.read_text(encoding="utf8")
        start = script.index("\nPGC_DEFAULT_QUALITY_EDITS = ")
        end = script.index("\nfor recipe in RECIPES:", start)
        baseline = script[:start] + script[end:]
        baseline = baseline.replace(" body=bangumi_default_quality_delta(recipe['output'],body)\n", "")
        baseline = baseline.replace(" body=bangumi_initial_detail_login_delta(recipe['output'],body)\n", "")
        baseline = baseline.replace(" body=bangumi_network_recovery_delta(recipe['output'],body)\n", "")
        baseline = baseline.replace(" body=bangumi_v034_quality_delta(recipe['output'],body)\n", "")
        baseline = baseline.replace(" body=bangumi_v034_authorization_delta(recipe['output'],body)\n", "")
        baseline = baseline.replace(" body=bangumi_v034_course_download_delta(recipe['output'],body)\n", "")
        baseline = baseline.replace(" body=bangumi_premium_audio_delta(recipe['output'],body)\n", "")
        baseline = baseline.replace(" body=bangumi_codec_recovery_delta(recipe['output'],body)\n", "")
        proof_line = next(line for line in baseline.splitlines(keepends=True)
            if line.startswith("(out/'pgc-default-quality-source-inventory.json')"))
        baseline = baseline.replace(proof_line, "")
        current_pre_quality = baseline
        if hashlib.sha256(current_pre_quality.encode()).hexdigest() != CURRENT_PRE_QUALITY_PRODUCER_SHA256_LF:
            raise AssertionError("Current sole PGC pre-quality stage no longer reconstructs")
        recipe_node = next(node for node in ast.parse(baseline).body
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "RECIPES" for t in node.targets))
        recipes = json.loads(ast.literal_eval(recipe_node.value.args[0]))
        recipe = next(row for row in recipes if row["output"] == VM)
        if len(recipe["edits"]) != 77:
            raise AssertionError("Fixed historical PGC recipe shape changed")
        recipe["adaptedSha256LF"] = HISTORICAL_VM_ADAPTED_SHA256_LF
        recipe["edits"][53]["after"] = HISTORICAL_QUALITY_LAUNCH
        historical_line = "RECIPES=json.loads(" + repr(json.dumps(recipes, separators=(",", ":"), ensure_ascii=False)) + ")"
        baseline = baseline.replace(ast.get_source_segment(baseline, recipe_node), historical_line, 1)
        if hashlib.sha256(baseline.encode()).hexdigest() != BASELINE_PRODUCER_SHA256_LF:
            raise AssertionError("Fixed original sole PGC producer no longer reconstructs")
        cls.baseline_producer = cls.root / "current-pre-quality-producer.py"
        cls.baseline_producer.write_text(current_pre_quality, encoding="utf8", newline="\n")
        cls.login_edits = ast.literal_eval(next(node.value for node in ast.parse(script).body
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "PGC_INITIAL_DETAIL_LOGIN_EDITS" for t in node.targets)))
        cls.network_edits = ast.literal_eval(next(node.value for node in ast.parse(script).body
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "PGC_NETWORK_RECOVERY_EDITS" for t in node.targets)))
        beta2_node = next(node.value for node in ast.parse(script).body
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "PGC_V034_QUALITY_EDITS" for t in node.targets))
        cls.beta2_edits = json.loads(ast.literal_eval(beta2_node.args[0]))
        auth_node = next(node.value for node in ast.parse(script).body
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "PGC_V034_AUTHORIZATION_EDITS" for t in node.targets))
        cls.auth_edits = json.loads(ast.literal_eval(auth_node.args[0]))
        course_node = next(node.value for node in ast.parse(script).body
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "PGC_V034_COURSE_DOWNLOAD_EDITS" for t in node.targets))
        cls.course_edits = json.loads(ast.literal_eval(course_node.args[0]))
        premium_node = next(node.value for node in ast.parse(script).body
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "PGC_PREMIUM_AUDIO_EDITS" for t in node.targets))
        cls.premium_edits = json.loads(ast.literal_eval(premium_node.args[0]))
        codec_node = next(node.value for node in ast.parse(script).body
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "PGC_CODEC_RECOVERY_EDITS" for t in node.targets))
        cls.codec_edits = json.loads(ast.literal_eval(codec_node.args[0]))
        if BEFORE_OVERRIDE:
            if Path(BEFORE_OVERRIDE).read_text(encoding="utf8") != baseline:
                raise AssertionError("Explicit baseline is not the fixed reconstructed historical producer")
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
        beta2_proof = self.proof["beta2QualityBodies"]
        self.assertEqual(1, len(beta2_proof))
        self.assertTrue(beta2_proof[0]["completeInverse"])
        auth_proof = self.proof["beta2AuthorizationBodies"]
        self.assertEqual(1, len(auth_proof))
        self.assertTrue(auth_proof[0]["completeInverse"])
        course_proof = self.proof["beta2CourseDownloadBodies"]
        self.assertEqual(1, len(course_proof))
        self.assertTrue(course_proof[0]["completeInverse"])
        premium_proof = self.proof["premiumAudioBodies"]
        self.assertEqual(1, len(premium_proof))
        self.assertTrue(premium_proof[0]["completeInverse"])
        codec_proof = self.proof["codecRecoveryBodies"]
        self.assertEqual(1, len(codec_proof))
        self.assertTrue(codec_proof[0]["completeInverse"])
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), codec_proof[0]["afterSha256LF"])
        for edit in reversed(self.codec_edits):
            self.assertEqual(edit["count"], body.count(edit["after"]))
            body = body.replace(edit["after"], edit["before"], edit["count"])
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), codec_proof[0]["beforeSha256LF"])
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), premium_proof[0]["afterSha256LF"])
        for edit in reversed(self.premium_edits):
            self.assertEqual(edit["count"], body.count(edit["after"]))
            body = body.replace(edit["after"], edit["before"], edit["count"])
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), premium_proof[0]["beforeSha256LF"])
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), course_proof[0]["afterSha256LF"])
        for edit in reversed(self.course_edits):
            self.assertEqual(1, body.count(edit["after"]))
            body = body.replace(edit["after"], edit["before"], 1)
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), course_proof[0]["beforeSha256LF"])
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), auth_proof[0]["afterSha256LF"])
        for edit in reversed(self.auth_edits):
            self.assertEqual(1, body.count(edit["after"]))
            body = body.replace(edit["after"], edit["before"], 1)
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), auth_proof[0]["beforeSha256LF"])
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), beta2_proof[0]["afterSha256LF"])
        for edit in reversed(self.beta2_edits):
            self.assertEqual(1, body.count(edit["after"]))
            body = body.replace(edit["after"], edit["before"], 1)
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), beta2_proof[0]["beforeSha256LF"])
        for before, after in reversed(self.network_edits):
            self.assertEqual(1, body.count(after))
            body = body.replace(after, before, 1)
        for before, after in reversed(self.login_edits):
            self.assertEqual(1, body.count(after))
            body = body.replace(after, before, 1)
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
        updated = self.updated
        for edit in reversed(self.codec_edits):
            updated = updated.replace(edit["after"], edit["before"], edit["count"])
        for edit in reversed(self.premium_edits):
            updated = updated.replace(edit["after"], edit["before"], edit["count"])
        for edit in reversed(self.course_edits):
            updated = updated.replace(edit["after"], edit["before"], 1)
        for edit in reversed(self.auth_edits):
            updated = updated.replace(edit["after"], edit["before"], 1)
        for edit in reversed(self.beta2_edits):
            updated = updated.replace(edit["after"], edit["before"], 1)
        for before, after in reversed(self.network_edits):
            updated = updated.replace(after, before, 1)
        start = "    fun changeQuality(qualityId: Int) {"
        end = "    fun changeAudioQuality(audioQuality: Int) {"
        self.assertEqual(self.original.split(start, 1)[1].split(end, 1)[0],
            updated.split(start, 1)[1].split(end, 1)[0])
        start = "        if (playUrlResult.getOrNull()?.isDrm == true && requestedQn > 80) {"
        end = "        playUrlResult.onSuccess { playData ->"
        self.assertEqual(self.original.split(start, 1)[1].split(end, 1)[0],
            updated.split(start, 1)[1].split(end, 1)[0])
        self.assertIn("val targetQuality = if (requestedQn >= 127)", self.updated)
        self.assertIn("isHevcSupported = environment.hevcSupported()", self.updated)
        self.assertIn("storedQuality = storedQuality", self.updated)
        self.assertEqual("b2091b1de636e499df80af7e700796b4f52b7f92", self.proof["beta2QualitySource"]["upstreamCommit"])
        self.assertIn("quality = selectedVideoQuality ?: playData.quality", self.updated)
        self.assertEqual({"rawVerified": True, "gitBlobVerified": True,
            "completeInitialMethodVerified": True, "completeDashSelectorVerified": True},
            self.proof["beta2QualitySourceProof"])
        pin = self.proof["beta2QualitySource"]
        raw = (BASE / pin["originalRawPath"]).read_bytes()
        self.assertEqual(pin["bytesRaw"], len(raw))
        self.assertEqual(pin["sha256Raw"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(pin["gitBlob"], hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest())

    def test_beta2_course_method_reuses_original_task_and_io_capture_boundary(self):
        pin = self.proof["beta2CourseDownloadSource"]
        raw = (BASE / pin["originalRawPath"]).read_bytes()
        self.assertEqual(pin["bytesRaw"], len(raw))
        self.assertEqual(pin["sha256Raw"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(pin["gitBlob"], hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest())
        original = raw.decode("utf8").replace("\r\n", "\n")
        start = original.index(pin["originalMethodStart"])
        method = original[start:original.index(pin["originalMethodEnd"], start)]
        self.assertEqual(pin["originalMethodBytesLF"], len(method.encode("utf8")))
        self.assertEqual(pin["originalMethodSha256LF"], hashlib.sha256(method.encode("utf8")).hexdigest())
        self.assertEqual({"rawVerified": True, "gitBlobVerified": True, "completeCourseDownloadMethodVerified": True},
            self.proof["beta2CourseDownloadSourceProof"])
        before_task = method.split("        val task = DownloadTask(", 1)[1].split("        viewModelScope.launch {", 1)[0]
        after_task = self.updated.split("    fun downloadCurrentEpisode(context: Context) {", 1)[1].split("        val task = DownloadTask(", 1)[1].split("        val enqueue =", 1)[0]
        self.assertEqual(before_task, after_task)
        self.assertIn("val enqueue = try { environment.captureDownloadTask(task) }", self.updated)
        self.assertIn("val added = try { enqueue() }", self.updated)
        self.assertNotIn("environment.addDownloadTask(task)", self.updated)

if __name__ == "__main__":
    unittest.main()
