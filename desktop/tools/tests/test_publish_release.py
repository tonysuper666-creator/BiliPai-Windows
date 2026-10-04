import ast
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("publish_release", Path(__file__).parents[1] / "publish-release.py")
publication = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publication)
SOURCE = "a" * 40
OTHER = "b" * 40
VERSION = "0.2.406.1"
TAG = "Windows-v" + VERSION
MANIFEST = {"upstreamRepository": "jay3-yy/BiliPai", "upstreamTag": "v0.2.3-alpha.9",
            "upstreamCommit": OTHER, "hashNormalization": "lf", "featureCoverage": {"playback": "native"}}
GATE = {"passed": True, "windowsSourceCommit": SOURCE, **{name: "passed" for name in publication.GATES}}
ARCHIVE, CHECKSUM, EVIDENCE = publication.asset_names(VERSION)


def zip_bytes(label):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("BiliPai Windows/BiliPai Windows.exe", label)
    return buffer.getvalue()


class FakeGitHub:
    repository = "owner/BiliPai-Windows"

    def __init__(self, *, tag=None, exists=False, draft=False, assets=None, fail_upload=False):
        self.tag, self.exists, self.is_draft = tag, exists, draft
        self.assets = assets or {}
        self.fail_upload = fail_upload
        self.operations, self.downloads = [], []

    def tag_head(self, tag):
        return self.tag

    def release(self, tag):
        if not self.exists:
            return None
        return {"draft": self.is_draft, "assets": [
            {"name": name, "digest": "sha256:" + hashlib.sha256(contents).hexdigest()}
            for name, contents in self.assets.items()]}

    def download(self, tag, name, destination):
        self.downloads.append(name)
        path = destination / name
        path.write_bytes(self.assets[name])
        return path

    def create_tag(self, tag, source_sha):
        self.operations.append("create_tag")
        self.tag = source_sha

    def create_draft(self, tag, version, notes):
        self.operations.append("create_draft")
        self.exists, self.is_draft = True, True

    def draft(self, tag, value):
        self.operations.append("draft" if value else "public")
        self.is_draft = value

    def upload(self, tag, paths):
        for path in paths:
            self.operations.append("upload:" + path.name)
            if self.fail_upload:
                raise publication.ReleaseError("simulated asset upload failure")
            if path.name in self.assets:
                raise AssertionError("An existing asset must never be overwritten")
            self.assets[path.name] = path.read_bytes()


class PublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.local_zip = zip_bytes("new build")
        (self.root / ARCHIVE).write_bytes(self.local_zip)
        self.local_digest = hashlib.sha256(self.local_zip).hexdigest()
        (self.root / CHECKSUM).write_text(f"{self.local_digest}  {ARCHIVE}\n", encoding="ascii")

    def tearDown(self):
        self.temp.cleanup()

    def complete_assets(self, contents=None):
        contents = contents or self.local_zip
        digest = hashlib.sha256(contents).hexdigest()
        return {ARCHIVE: contents, CHECKSUM: f"{digest}  {ARCHIVE}\n".encode("ascii"),
                EVIDENCE: json.dumps(publication.evidence(MANIFEST, VERSION, SOURCE, digest, GATE)).encode("utf-8")}

    def publish(self, github, gate=None):
        selected_gate = {**GATE, "windowsVersion": VERSION, "portableZipSha256": self.local_digest} if gate is None else gate
        return publication.publish(github, MANIFEST, VERSION, SOURCE, self.root, selected_gate)

    def test_no_release_requires_publication_even_without_upstream_source_changes(self):
        result = publication.publication_status(FakeGitHub(), MANIFEST, VERSION, SOURCE)
        self.assertTrue(result["publicationNeeded"])
        self.assertEqual(result["sourceSha"], SOURCE)
        self.assertEqual(result["missingAssets"], [ARCHIVE, CHECKSUM, EVIDENCE])

    def test_draft_release_is_found_when_by_tag_api_returns_404(self):
        github = publication.GitHub("owner/BiliPai-Windows", self.root)
        draft = {"tag_name": TAG, "draft": True, "assets": []}
        with patch.object(github, "api", return_value=None), \
             patch.object(github, "gh", return_value=json.dumps([[{"tag_name": "other"}], [draft]])) as gh:
            self.assertEqual(github.release(TAG), draft)
        self.assertIn("--paginate", gh.call_args.args)
        self.assertIn("--slurp", gh.call_args.args)

    def test_existing_tag_is_validated_even_when_release_does_not_exist(self):
        github = FakeGitHub(tag=OTHER)
        with self.assertRaisesRegex(publication.ReleaseError, "another source commit"):
            self.publish(github)
        self.assertEqual(github.operations, [])

    def test_existing_correct_tag_without_release_is_reused(self):
        github = FakeGitHub(tag=SOURCE)
        self.assertEqual(self.publish(github)["status"], "published")
        self.assertNotIn("create_tag", github.operations)
        self.assertEqual(github.operations[0], "create_draft")

    def test_fresh_release_is_draft_until_all_assets_are_verified(self):
        github = FakeGitHub()
        self.assertEqual(self.publish(github)["status"], "published")
        self.assertEqual(github.operations, ["create_tag", "create_draft", "upload:" + ARCHIVE,
                                           "upload:" + CHECKSUM, "upload:" + EVIDENCE, "public"])
        value = json.loads(github.assets[EVIDENCE])
        self.assertEqual(value["windowsSourceCommit"], SOURCE)
        self.assertEqual(value["zipSha256"], self.local_digest)

    def test_upload_failure_leaves_release_draft(self):
        github = FakeGitHub(fail_upload=True)
        with self.assertRaisesRegex(publication.ReleaseError, "upload failure"):
            self.publish(github)
        self.assertTrue(github.is_draft)
        self.assertNotIn("public", github.operations)

    def test_matching_remote_zip_missing_evidence_can_be_repaired_after_exact_local_gate(self):
        assets = self.complete_assets()
        del assets[EVIDENCE]
        github = FakeGitHub(tag=SOURCE, exists=True, assets=assets)
        self.assertEqual(self.publish(github)["status"], "published")
        self.assertEqual(github.assets[ARCHIVE], self.local_zip)
        self.assertEqual(github.operations, ["draft", "upload:" + EVIDENCE, "public"])

    def test_different_remote_zip_without_evidence_cannot_inherit_fresh_local_gate(self):
        old_zip = zip_bytes("previous valid package with different timestamps")
        assets = self.complete_assets(old_zip)
        del assets[EVIDENCE]
        github = FakeGitHub(tag=SOURCE, exists=True, assets=assets)
        with self.assertRaisesRegex(publication.ReleaseError, "lacks release evidence for its exact bytes"):
            self.publish(github)
        self.assertEqual(github.assets[ARCHIVE], old_zip)
        self.assertEqual(github.operations, [])
        self.assertNotIn(EVIDENCE, github.assets)

    def test_missing_checksum_is_repaired_from_preserved_remote_zip(self):
        old_zip = zip_bytes("old build")
        assets = self.complete_assets(old_zip)
        del assets[CHECKSUM]
        github = FakeGitHub(tag=SOURCE, exists=True, draft=True, assets=assets)
        self.publish(github)
        self.assertEqual(github.assets[ARCHIVE], old_zip)
        self.assertEqual(github.operations, ["upload:" + CHECKSUM, "public"])
        self.assertEqual(publication.checksum(github.assets[CHECKSUM], ARCHIVE), hashlib.sha256(old_zip).hexdigest())

    def test_complete_release_is_verified_and_has_no_mutations(self):
        github = FakeGitHub(tag=SOURCE, exists=True, assets=self.complete_assets())
        self.assertFalse(self.publish(github)["publicationNeeded"])
        self.assertEqual(github.operations, [])
        self.assertNotIn(ARCHIVE, github.downloads)

    def test_draft_with_all_assets_is_completed(self):
        github = FakeGitHub(tag=SOURCE, exists=True, draft=True, assets=self.complete_assets())
        self.publish(github)
        self.assertEqual(github.operations, ["public"])

    def test_bad_remote_checksum_stops_before_any_mutation(self):
        assets = self.complete_assets()
        assets[CHECKSUM] = f"{'f' * 64}  {ARCHIVE}\n".encode("ascii")
        github = FakeGitHub(tag=SOURCE, exists=True, assets=assets)
        with self.assertRaisesRegex(publication.ReleaseError, "disagree"):
            self.publish(github)
        self.assertEqual(github.operations, [])

    def test_checksum_naming_another_zip_is_rejected(self):
        with self.assertRaisesRegex(publication.ReleaseError, "exact Windows ZIP"):
            publication.checksum(f"{'a' * 64}  evil.zip\n".encode(), ARCHIVE)

    def test_existing_evidence_for_another_source_is_retained_and_rejected(self):
        assets = self.complete_assets()
        value = json.loads(assets[EVIDENCE])
        value["windowsSourceCommit"] = OTHER
        assets[EVIDENCE] = json.dumps(value).encode()
        github = FakeGitHub(tag=SOURCE, exists=True, assets=assets)
        with self.assertRaisesRegex(publication.ReleaseError, "conflicts"):
            self.publish(github)
        self.assertEqual(github.operations, [])

    def test_missing_zip_cannot_replace_an_old_declared_digest_with_new_bytes(self):
        assets = self.complete_assets(zip_bytes("old valid package"))
        del assets[ARCHIVE]
        github = FakeGitHub(tag=SOURCE, exists=True, assets=assets)
        with self.assertRaisesRegex(publication.ReleaseError, "different bytes"):
            self.publish(github)
        self.assertEqual(github.operations, [])

    def test_passed_boolean_cannot_replace_the_release_gates(self):
        github = FakeGitHub()
        with self.assertRaisesRegex(publication.ReleaseError, "must all pass"):
            self.publish(github, {"passed": True})
        self.assertEqual(github.operations, [])

    def test_old_three_gate_report_cannot_publish_without_updater_verification(self):
        github = FakeGitHub()
        gate = {**GATE, "windowsVersion": VERSION, "portableZipSha256": self.local_digest}
        del gate["packagedUpdaterSmoke"]
        with self.assertRaisesRegex(publication.ReleaseError, "must all pass"):
            self.publish(github, gate)
        self.assertEqual(github.operations, [])

    def test_report_without_generated_source_contracts_cannot_publish(self):
        github = FakeGitHub()
        gate = {**GATE, "windowsVersion": VERSION, "portableZipSha256": self.local_digest}
        del gate["pythonSourceContractTests"]
        with self.assertRaisesRegex(publication.ReleaseError, "must all pass"):
            self.publish(github, gate)
        self.assertEqual(github.operations, [])

    def test_native_download_mux_must_pass_before_any_publication_mutation(self):
        for outcome in (None, "failed", True):
            with self.subTest(outcome=outcome):
                github = FakeGitHub()
                gate = {**GATE, "windowsVersion": VERSION, "portableZipSha256": self.local_digest}
                if outcome is None:
                    gate.pop("packagedNativeDownloadMuxSmoke", None)
                else:
                    gate["packagedNativeDownloadMuxSmoke"] = outcome
                with self.assertRaisesRegex(publication.ReleaseError, "must all pass"):
                    self.publish(github, gate)
                self.assertEqual(github.operations, [])

    def test_release_evidence_records_and_requires_native_download_mux(self):
        assets = self.complete_assets()
        value = json.loads(assets[EVIDENCE])
        self.assertEqual(value["releaseGate"]["packagedNativeDownloadMuxSmoke"], "passed")
        del value["releaseGate"]["packagedNativeDownloadMuxSmoke"]
        assets[EVIDENCE] = json.dumps(value).encode("utf-8")
        github = FakeGitHub(tag=SOURCE, exists=True, assets=assets)
        with self.assertRaisesRegex(publication.ReleaseError, "must all pass"):
            self.publish(github)
        self.assertEqual(github.operations, [])

    def test_gate_for_another_package_cannot_publish_checked_local_zip(self):
        github = FakeGitHub()
        gate = {**GATE, "windowsVersion": VERSION, "portableZipSha256": "f" * 64}
        with self.assertRaisesRegex(publication.ReleaseError, "does not match the checked Windows package"):
            self.publish(github, gate)
        self.assertEqual(github.operations, [])

    def test_same_version_package_cannot_be_relabelled_with_another_source_commit(self):
        for commit in (None, OTHER):
            with self.subTest(commit=commit):
                github = FakeGitHub()
                gate = {**GATE, "windowsVersion": VERSION, "portableZipSha256": self.local_digest}
                if commit is None:
                    del gate["windowsSourceCommit"]
                else:
                    gate["windowsSourceCommit"] = commit
                with self.assertRaisesRegex(publication.ReleaseError, "exact Windows source commit"):
                    self.publish(github, gate)
                self.assertEqual(github.operations, [])

    def test_gate_for_another_version_cannot_publish_checked_local_zip(self):
        github = FakeGitHub()
        gate = {**GATE, "windowsVersion": "0.2.406.2", "portableZipSha256": self.local_digest}
        with self.assertRaisesRegex(publication.ReleaseError, "does not match the checked Windows package"):
            self.publish(github, gate)
        self.assertEqual(github.operations, [])

    def test_upstream_repository_and_invalid_source_cannot_be_published(self):
        for repository in ["jay3-yy/BiliPai", "JAY3-YY/BILIPAI", "../owner/repo", "https://github.com/owner/repo"]:
            with self.subTest(repository=repository), self.assertRaises(publication.ReleaseError):
                publication.valid_repository(repository)
        with self.assertRaises(publication.ReleaseError):
            publication.publication_status(FakeGitHub(), MANIFEST, VERSION, "main")

    def test_invalid_existing_zip_is_preserved_for_manual_review(self):
        github = FakeGitHub(tag=SOURCE, exists=True, assets={ARCHIVE: b"broken package"})
        with self.assertRaisesRegex(publication.ReleaseError, "Existing Windows ZIP is invalid"):
            self.publish(github)
        self.assertEqual(github.assets[ARCHIVE], b"broken package")
        self.assertEqual(github.operations, [])


class PublicationRecoveryWorkflowTests(unittest.TestCase):
    """Evaluate the checked-in job guards/ref, without dispatching any workflow."""
    @classmethod
    def setUpClass(cls):
        workflow = Path(__file__).resolve().parents[3] / ".github/workflows/windows-upstream-sync.yml"
        cls.job = workflow.read_text(encoding="utf-8").split("\n  recover_publication:\n", 1)[1]
        cls.condition = re.search(r"\n    if: >-\n(.*?)\n    runs-on:", cls.job, re.S).group(1)
        cls.checkout = re.search(r"\n          ref: \$\{\{ (.*?) \}\}", cls.job).group(1)

    def evaluate(self, expression, *, candidate="failure", candidate_sha=OTHER, publication="true",
                 detect="success", auto_publish="true"):
        values = {"needs.detect.result": detect, "needs.candidate.result": candidate,
                  "vars.BILIPAI_WINDOWS_AUTO_PUBLISH": auto_publish,
                  "needs.detect.outputs.publication_needed": publication,
                  "needs.detect.outputs.source_sha": SOURCE,
                  "needs.candidate.outputs.source_sha": candidate_sha}
        expression = expression.replace("always()", "True")
        expression = re.sub(r"\b(?:needs|vars)\.[A-Za-z0-9_.]+", lambda m: repr(values[m.group()]), expression)
        expression = " ".join(expression.replace("&&", " and ").replace("||", " or ").split())
        tree = ast.parse(expression, mode="eval")
        allowed = (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.Compare, ast.Eq, ast.NotEq, ast.Constant)
        self.assertTrue(all(isinstance(node, allowed) for node in ast.walk(tree)), expression)
        return eval(compile(tree, "checked-in-recovery-expression", "eval"), {"__builtins__": {}})

    def test_failed_candidate_does_not_block_existing_source_publication_recovery(self):
        self.assertTrue(self.evaluate(self.condition))
        # Even a leftover failed-candidate output cannot replace detect's fixed SHA.
        self.assertEqual(self.evaluate(self.checkout), SOURCE)

    def test_failed_candidate_without_existing_publication_cannot_trigger_release(self):
        self.assertFalse(self.evaluate(self.condition, publication="false"))
        self.assertEqual(self.evaluate(self.checkout, publication="false"), SOURCE)

    def test_successful_validated_candidate_is_used_and_can_trigger_publication(self):
        self.assertTrue(self.evaluate(self.condition, candidate="success", publication="false"))
        self.assertEqual(self.evaluate(self.checkout, candidate="success"), OTHER)

    def test_skipped_or_cancelled_candidate_recovers_only_the_detect_source(self):
        for result in ("skipped", "cancelled"):
            with self.subTest(result=result):
                self.assertTrue(self.evaluate(self.condition, candidate=result))
                self.assertEqual(self.evaluate(self.checkout, candidate=result), SOURCE)

    def test_failed_detect_or_disabled_publication_cannot_dispatch_recovery(self):
        self.assertFalse(self.evaluate(self.condition, detect="failure"))
        self.assertFalse(self.evaluate(self.condition, auto_publish="false"))


if __name__ == "__main__":
    unittest.main()
