import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "desktop/tools"))
import v038_danmaku_cadence as cadence

spec = importlib.util.spec_from_file_location("list_menu_cadence_owner", REPO / "desktop/tools/extract-upstream-danmaku-list-menu.py")
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)


class CadenceSourceTest(unittest.TestCase):
    def test_full_original_policy_body_is_recovered_from_the_read_only_deadline_port(self):
        sources, _ = cadence.fixed_sources(REPO)
        result, proof = cadence.cadence_source(REPO)
        for edit in reversed(proof["edits"]):
            result = result.replace(edit["after"], edit["before"])
        self.assertEqual(sources["RenderFrameCadence.kt"], result)
        self.assertTrue(proof["exactFullSelectionInverse"])

    def test_original_request_cancel_callbacks_and_visibility_have_complete_inverse_evidence(self):
        result, proof = cadence.frame_request_source(REPO, producer.function)
        self.assertEqual(6, len(proof["selections"]))
        self.assertTrue(all(row["exactFullSelectionInverse"] for row in proof["selections"]))
        self.assertNotIn("Choreographer", result)
        self.assertNotIn("Looper", result)
        self.assertIn("platform.requestFrameCallback(renderFrame, cadence.nextFrameNs)", result)
        original, _ = cadence.fixed_sources(REPO)
        tests, _ = cadence.original_policy_test(REPO)
        self.assertEqual(original["RenderFrameCadenceTest.kt"], tests)

    def test_changed_upstream_source_is_rejected_even_when_it_is_valid_kotlin(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            target = repo / cadence.ARCHIVE
            shutil.copytree(REPO / cadence.ARCHIVE, target)
            path = target / "RenderFrameCadence.kt"
            path.write_bytes(path.read_bytes().replace(b"/ 60L", b"/ 30L"))
            with self.assertRaisesRegex(ValueError, "source bytes changed"):
                cadence.cadence_source(repo)

    def test_relabeling_manifest_cannot_admit_a_different_source_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp)
            target = repo / cadence.ARCHIVE
            shutil.copytree(REPO / cadence.ARCHIVE, target)
            path = target / "manifest.json"
            manifest = json.loads(path.read_text(encoding="utf-8"))
            manifest["fixedUpstreamCommit"] = "0" * 40
            path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "manifest changed"):
                cadence.fixed_sources(repo)


if __name__ == "__main__":
    unittest.main()
