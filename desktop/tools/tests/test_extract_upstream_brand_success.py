"""Persistent original-source and actual sole-producer business-feedback contracts.

No private packet paths, JVM, network, GUI, account or user profile is required.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))
import v029_brand_success as business


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha(data):
    return hashlib.sha256(data).hexdigest()


class BrandSuccessProducerTest(unittest.TestCase):
    def archive_copy(self, destination):
        originals = business.checked()
        root = Path(destination)
        for path, data in {"manifest.json": business.wide(business.ROOT / "manifest.json").read_bytes(), **originals}.items():
            target = business.wide(root / path)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        return root

    def test_all_six_originals_have_fixed_raw_commit_and_blob_identity(self):
        originals = business.checked()
        self.assertEqual(6, len(originals))
        manifest = json.loads(business.wide(business.ROOT / "manifest.json").read_bytes())
        self.assertEqual("a4b77f894d0a2dd26c0b9fc144b8adb88ac05480", manifest["fixedUpstreamCommit"])
        self.assertEqual("raw", manifest["hashNormalization"])
        for row in manifest["files"]:
            data = originals[row["path"]]
            self.assertEqual(row["bytes"], len(data))
            self.assertEqual(row["rawSha256"], sha(data))
            self.assertEqual(row["gitBlob"], hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest())

    def test_manifest_tamper_cannot_repin_changed_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.archive_copy(temp)
            manifest = business.wide(root / "manifest.json")
            data = json.loads(manifest.read_bytes())
            data["files"][0]["rawSha256"] = "0" * 64
            manifest.write_text(json.dumps(data), encoding="utf-8")
            with patch.object(business, "ROOT", root):
                with self.assertRaisesRegex(ValueError, "manifest mismatch"):
                    business.runtime_outputs()

    def test_raw_tamper_is_rejected_before_runtime_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = self.archive_copy(temp)
            path = business.wide(root / (business.BASE + "core/events/BrandSuccessEvents.kt"))
            path.write_bytes(path.read_bytes() + b"\n")
            with patch.object(business, "ROOT", root):
                with self.assertRaisesRegex(ValueError, "original bytes mismatch"):
                    business.runtime_outputs()

    def test_complete_original_events_and_host_inverse_and_final_hash(self):
        originals = business.checked()
        outputs = business.runtime_outputs()
        proof = json.loads(outputs["brand-success-source-proof.json"])
        self.assertEqual(2, len(proof["proofs"]))
        for row in proof["proofs"]:
            name = row["originalPath"].removeprefix(business.BASE)
            final = outputs["kotlin/com/android/purebilibili/" + name]
            self.assertTrue(row["wholeInverseExact"])
            self.assertEqual(sha(final), row["afterSha256"])
            original = business.reverse(final.decode(), row["edits"]).encode()
            self.assertEqual(originals[row["originalPath"]], original)
            self.assertEqual(sha(original), row["beforeSha256"])
        events = outputs["kotlin/com/android/purebilibili/core/events/BrandSuccessEvents.kt"].decode()
        self.assertIn("extraBufferCapacity = 4", events)
        self.assertIn('completedDownloads.add("$taskId:$createdAt")', events)
        self.assertIn("completedDownloads.size > 256", events)
        self.assertIn("if (isCurrent(event)) emit(event)", events)
        self.assertNotIn("object BrandSuccessEvents", events)

    def test_actual_favorites_consumer_applies_one_stage_with_full_inverse(self):
        producer = load("brand_success_favorites_contract", TOOLS / "extract-upstream-favorites.py")
        relative = "com/android/purebilibili/data/repository/DesktopOriginalFavoriteActions.kt"
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "final"
            rows = producer.generate(REPO, output, True)
            row = next(row for row in rows if row.get("windowsBrandSuccessAdaptation"))
            audit = row["windowsBrandSuccessAdaptation"]
            final = producer.safe(output / relative).read_bytes()
            self.assertEqual(sha(final), audit["afterSha256"])
            self.assertEqual(4, len(audit["edits"]))
            baseline = Path(temp) / "before-brand-stage"
            with patch.object(business, "favorites_delta", lambda text: (text, {})):
                producer.generate(REPO, baseline, True)
            original = business.reverse(final.decode(), audit["edits"]).encode()
            self.assertEqual(producer.safe(baseline / relative).read_bytes(), original)
            self.assertEqual(sha(original), audit["beforeSha256"])
            physical = next(item for item in row["outputs"] if item["path"] == relative)
            self.assertEqual(sha(final), physical["sha256LfUtf8"])

    def test_actual_following_vm_aggregate_consumes_one_stage_and_keeps_protocol(self):
        producer = load("brand_success_following_contract", TOOLS / "extract-upstream-following.py")
        relative = "com/android/purebilibili/feature/following/FollowingListScreen.kt"
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "final"
            rows = producer.generate(REPO, output, True)
            row = next(row for row in rows if row.get("windowsBrandSuccessAdaptation"))
            audit = row["windowsBrandSuccessAdaptation"]
            final = producer.safe(output / relative).read_bytes()
            self.assertEqual(sha(final), audit["afterSha256"])
            self.assertEqual(sha(final), row["output"]["sha256LfUtf8"])
            self.assertEqual(3, len(audit["edits"]))
            baseline = Path(temp) / "before-brand-stage"
            with patch.object(business, "following_delta", lambda text: (text, {})):
                producer.generate(REPO, baseline, True)
            original = business.reverse(final.decode(), audit["edits"]).encode()
            self.assertEqual(producer.safe(baseline / relative).read_bytes(), original)
            self.assertEqual(sha(original), audit["beforeSha256"])
            self.assertIn("applyRemovedUsers(successMids)", final.decode())
            self.assertIn("emitBrandFeedback = false", final.decode())

    def test_sole_brand_producer_emits_complete_runtime_and_keeps_welcome_excluded(self):
        producer = load("brand_success_motion_contract", TOOLS / "extract-upstream-brand-motion.py")
        expected = business.runtime_outputs()
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "generated"
            report = producer.generate(REPO, output)
            self.assertEqual(33, report["generatedFiles"])
            for relative, data in expected.items():
                self.assertEqual(data, producer.wide(output / relative).read_bytes())
            self.assertEqual("15291a833f8e9d90731bdb5c1f8707a63c9958f395fb6938f6b5b49b1910a6c9", report["catalogSha256Bytes"])
            self.assertEqual(13, report["runtimeUniquePairs"])
            self.assertFalse(report["welcomeRuntimeAllowed"])
            self.assertEqual(26, len(producer.files_under(output / "resources/brand-motion")))

    def test_following_cli_preserves_unicode_report_on_cp1252_pipe(self):
        producer = load("brand_success_following_cli_contract", TOOLS / "extract-upstream-following.py")
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "cli"
            environment = os.environ.copy()
            environment.update(PYTHONIOENCODING="cp1252:strict", PYTHONUTF8="0")
            completed = subprocess.run(
                [sys.executable, str(TOOLS / "extract-upstream-following.py"),
                 "--repo", str(REPO), "--output", str(output), "--standalone"],
                cwd=REPO, env=environment, capture_output=True,
            )
            self.assertEqual(0, completed.returncode, completed.stderr.decode("cp1252", "replace"))
            report = json.loads(completed.stdout.decode("ascii"))
            expected = producer.generate(REPO, Path(temp) / "reference", True)
            self.assertEqual(expected, report)
            # Escaping stdout must retain the original Chinese adaptation text.
            self.assertTrue(any(ord(character) > 127 for character in json.dumps(report, ensure_ascii=False)))
            for row in report:
                emitted = row["output"]
                self.assertEqual(emitted["sha256LfUtf8"], sha(producer.safe(output / emitted["path"]).read_bytes()))


if __name__ == "__main__":
    unittest.main()
