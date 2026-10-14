import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/"desktop/tools"))
import v038_player_interaction_retry as retry


class V038InteractionRetrySourceTest(unittest.TestCase):
    def test_complete_original_retry_is_emitted_without_policy_changes(self):
        originals = retry.fixed_sources(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)/"original-video-full-owner"
            proof = retry.emit(REPO, output)
            path = output/"com/android/purebilibili/feature/video/viewmodel/VideoInteractionStatusRetry.kt"
            self.assertEqual(originals["VideoInteractionStatusRetry.kt"], path.read_text(encoding="utf-8"))
            self.assertTrue(proof["completeOriginalRetryCopied"])
            self.assertEqual(5, proof["originalCases"])

    def test_five_original_tests_have_complete_framework_inverse_and_stay_outside_main(self):
        originals = retry.fixed_sources(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)/"original-video-full-owner"
            proof = retry.emit(REPO, output)
            tests = Path(tmp)/"original-video-interaction-retry-tests/com/android/purebilibili/feature/video/viewmodel/VideoInteractionStatusRetryTest.kt"
            body = tests.read_text(encoding="utf-8")
            for edit in reversed(proof["testAdaptations"]): body = body.replace(edit["after"], edit["before"], 1)
            self.assertEqual(originals["VideoInteractionStatusRetryTest.kt"], body)
            self.assertFalse(any(output.rglob("*Test.kt")))

    def test_changed_timeout_or_retry_policy_cannot_replace_the_fixed_original(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)/retry.ARCHIVE
            shutil.copytree(REPO/retry.ARCHIVE, root)
            path = root/"VideoInteractionStatusRetry.kt"
            path.write_bytes(path.read_bytes().replace(b"8_000L", b"80_000L"))
            with self.assertRaisesRegex(ValueError, "source bytes changed"): retry.fixed_sources(Path(tmp))

    def test_original_full_vm_is_also_bound_by_its_raw_git_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)/retry.ARCHIVE
            shutil.copytree(REPO/retry.ARCHIVE, root)
            path = root/"VideoPlaybackViewModel.kt"
            path.write_bytes(path.read_bytes()+b"\n// changed source\n")
            with self.assertRaisesRegex(ValueError, "source bytes changed"): retry.fixed_sources(Path(tmp))


if __name__ == "__main__": unittest.main()
