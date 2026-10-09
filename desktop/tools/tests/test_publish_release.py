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


def evaluate_workflow_guard(test, expression, values):
    """Evaluate only the boolean subset used by the actual checked-in jobs."""
    expression = expression.replace("always()", "True")
    # Preserve quoted outputs like 'true'; only convert unquoted boolean literals.
    expression = re.sub(r"(?<![A-Za-z0-9_'\".])(?:true|false)(?![A-Za-z0-9_'\".])",
                        lambda m: "True" if m.group() == "true" else "False", expression)
    expression = re.sub(r"\b(?:needs|vars|github|inputs)\.[A-Za-z0-9_.]+",
                        lambda m: repr(values[m.group()]), expression)
    expression = " ".join(expression.replace("&&", " and ").replace("||", " or ").split())
    tree = ast.parse(expression, mode="eval")
    allowed = (ast.Expression, ast.BoolOp, ast.And, ast.Or, ast.Compare, ast.Eq, ast.NotEq, ast.Constant)
    test.assertTrue(all(isinstance(node, allowed) for node in ast.walk(tree)), expression)
    return eval(compile(tree, "checked-in-workflow-expression", "eval"), {"__builtins__": {}})


class PublicationRecoveryWorkflowTests(unittest.TestCase):
    """Evaluate the checked-in job guards/ref, without dispatching any workflow."""
    @classmethod
    def setUpClass(cls):
        workflow = Path(__file__).resolve().parents[3] / ".github/workflows/windows-upstream-sync.yml"
        cls.job = workflow.read_text(encoding="utf-8").split("\n  recover_publication:\n", 1)[1]
        cls.condition = re.search(r"\n    if: >-\n(.*?)\n    runs-on:", cls.job, re.S).group(1)
        cls.checkout = re.search(r"\n          ref: \$\{\{ (.*?) \}\}", cls.job).group(1)

    def evaluate(self, expression, *, candidate="failure", candidate_sha=OTHER, publication="true",
                 detect="success", auto_publish="true", repository="tonysuper666-creator/BiliPai-Windows", private=False):
        values = {"github.repository": repository, "github.event.repository.private": private, "needs.detect.result": detect, "needs.candidate.result": candidate,
                  "vars.BILIPAI_WINDOWS_AUTO_PUBLISH": auto_publish,
                  "needs.detect.outputs.publication_needed": publication,
                  "needs.detect.outputs.source_sha": SOURCE,
                  "needs.candidate.outputs.source_sha": candidate_sha}
        return evaluate_workflow_guard(self, expression, values)

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


class OwnPublicWindowsWorkflowTests(unittest.TestCase):
    """Read every active server job and upload step, not a stand-in condition."""
    @classmethod
    def setUpClass(cls):
        folder = Path(__file__).resolve().parents[3] / ".github/workflows"
        cls.workflows = {path.name: path.read_text(encoding="utf-8") for path in folder.glob("*.yml")}
        cls.jobs = {}
        for name, source in cls.workflows.items():
            body = source.split("\njobs:\n", 1)[1]
            starts = list(re.finditer(r"(?m)^  ([A-Za-z0-9_-]+):$", body))
            for index, start in enumerate(starts):
                end = starts[index + 1].start() if index + 1 < len(starts) else len(body)
                cls.jobs[(name, start.group(1))] = body[start.start():end]

    def admitted(self, name, job, **changes):
        body = self.jobs[(name, job)]
        match = re.search(r"(?m)^    if: (.+)$", body)
        self.assertIsNotNone(match, "Every server job needs its own condition")
        condition = match.group(1)
        if condition == ">-":
            condition = re.match(r"(?:      [^\n]*(?:\n|$))+", body[match.end() + 1:]).group()
        values = {"github.repository": "tonysuper666-creator/BiliPai-Windows", "github.event.repository.private": False,
                  "github.event_name": "workflow_dispatch", "github.ref_type": "tag",
                  "inputs.render_diagnostic": job == "render-diagnostic",
                  "inputs.comment_search_ui": job == "comment-search-ui",
                  "inputs.acknowledge_source_build": True,
                  "vars.BILIPAI_WINDOWS_AUTO_SYNC": "true", "vars.BILIPAI_WINDOWS_AUTO_PUBLISH": "true",
                  "needs.windows.outputs.release": "true", "needs.detect.outputs.update_needed": "true",
                  "needs.detect.result": "success", "needs.candidate.result": "success",
                  "needs.detect.outputs.publication_needed": "true", "needs.candidate.outputs.source_sha": SOURCE}
        values.update(changes)
        return evaluate_workflow_guard(self, condition, values)

    def test_all_server_jobs_require_own_public_repository(self):
        self.assertEqual(set(self.workflows), {"windows-desktop.yml", "windows-upstream-sync.yml",
                                               "windows-mpv-native-manual.yml", "windows-mpv-rtx-core-manual.yml"})
        self.assertEqual(len(self.jobs), 9)
        for name, job in self.jobs:
            with self.subTest(name=name, job=job):
                self.assertTrue(self.admitted(name, job))
                for repository in ("jay3-yy/BiliPai", "someone/BiliPai-Windows", "tonysuper666-creator/another-repo"):
                    self.assertFalse(self.admitted(name, job, **{"github.repository": repository}))
                self.assertFalse(self.admitted(name, job, **{"github.event.repository.private": True}))

    def test_every_upload_has_one_day_retention_and_original_missing_file_policy(self):
        uploads = 0
        for (name, job), body in self.jobs.items():
            starts = list(re.finditer(r"(?m)^      - ", body))
            for index, start in enumerate(starts):
                end = starts[index + 1].start() if index + 1 < len(starts) else len(body)
                step = body[start.start():end]
                if "uses: actions/upload-artifact@" not in step:
                    continue
                uploads += 1
                with self.subTest(name=name, job=job):
                    self.assertEqual(re.findall(r"(?m)^          retention-days: (.+)$", step), ["1"])
                    self.assertRegex(step, r"(?m)^          if-no-files-found: (warn|error)$")
        self.assertEqual(uploads, 11)

    def test_only_standard_runner_labels_and_readonly_default_permissions(self):
        for (name, job), body in self.jobs.items():
            with self.subTest(name=name, job=job):
                runner = re.search(r"(?m)^    runs-on: (.+)$", body).group(1)
                if (name, job) in {("windows-mpv-native-manual.yml", "native-candidate"),
                                   ("windows-mpv-rtx-core-manual.yml", "native-candidate")}:
                    self.assertEqual(runner, "ubuntu-24.04")
                else:
                    self.assertIn(runner, ("windows-latest", "ubuntu-latest"))
        for source in self.workflows.values():
            self.assertRegex(source, r"(?m)^permissions:\n  contents: read$")

    def test_native_candidate_requires_manual_dispatch_and_acknowledgement(self):
        name = "windows-mpv-native-manual.yml"
        job = "native-candidate"
        self.assertTrue(self.admitted(name, job, **{"inputs.acknowledge_source_build": True}))
        self.assertFalse(self.admitted(name, job, **{"inputs.acknowledge_source_build": False}))
        for event in ("push", "pull_request"):
            with self.subTest(event=event):
                self.assertFalse(self.admitted(name, job, **{"github.event_name": event,
                                                            "inputs.acknowledge_source_build": True}))

    def test_rtx_core_candidate_is_manual_source_only_and_never_published(self):
        name = "windows-mpv-rtx-core-manual.yml"
        job = "native-candidate"
        source = self.workflows[name]
        body = self.jobs[(name, job)]
        self.assertRegex(source, r"(?m)^  workflow_dispatch:$")
        self.assertNotRegex(source, r"(?m)^  (push|pull_request|schedule|workflow_call):")
        self.assertRegex(source, r"(?m)^      acknowledge_source_build:\n(?:        [^\n]*\n)*        default: false$")
        self.assertTrue(self.admitted(name, job, **{"inputs.acknowledge_source_build": True}))
        self.assertFalse(self.admitted(name, job, **{"inputs.acknowledge_source_build": False}))
        for event in ("push", "pull_request", "schedule", "workflow_call"):
            with self.subTest(event=event):
                self.assertFalse(self.admitted(name, job, **{"github.event_name": event,
                                                            "inputs.acknowledge_source_build": True}))
        self.assertRegex(body, r"(?m)^    runs-on: ubuntu-24.04$")
        self.assertRegex(body, r"(?m)^    timeout-minutes: 360$")
        self.assertIn("ghcr.io/tonysuper666-creator/bilipai-windows-builder@sha256:c7dffe77b57d98b10e327dde12d3977faf4cb90aa7cb4f5eeac4e9d68d724239", body)
        self.assertIn("python3 desktop/tools/native/build-mpv-rtx-core-runtime.py", body)
        self.assertIn("python3 desktop/tools/native/upload-mpv-rtx-core-draft.py", body)
        self.assertRegex(body, r"(?m)^    permissions:\n      contents: write$")
        self.assertNotIn("actions/upload-artifact@", body)
        self.assertNotIn("actions/cache", body)
        self.assertIn("        if: success()", body)
        self.assertFalse(self.admitted(name, job, **{"github.ref_type": "branch"}))
        self.assertNotRegex(body, r"(?i)gh\s+release|create-release|upload-release|veyra-core.*cmake|nvngx.*(?:build|download)")
        draft_helper = (Path(__file__).parents[1] / "native/upload-mpv-rtx-core-draft.py").read_text(encoding="utf-8")
        self.assertIn("\'draft\': True", draft_helper)
        self.assertIn("\'make_latest\': \'false\'", draft_helper)
        self.assertIn("PART_BYTES = 1 << 30", draft_helper)
        self.assertIn("RELEASE_ASSET_LIMIT = 2 << 30", draft_helper)
        self.assertIn("require_tag(base, tag, commit, token)", draft_helper)
        self.assertNotIn("target_commitish", draft_helper)
        self.assertNotIn("api(\'PATCH\'", draft_helper)
        root = Path(__file__).resolve().parents[3]
        inputs = root / "desktop/third-party/libmpv/build/rtx-core-v1"
        fixed = json.loads((inputs / "fixed-inputs.json").read_text(encoding="utf-8"))
        self.assertEqual({row["kind"] for row in fixed["archives"]}, {"mpv", "ffmpeg", "recipes"})
        manifest = json.loads((inputs / "bilipai-rtx-source-manifest.json").read_text(encoding="utf-8"))
        self.assertIs(manifest["closedSdkOrRuntimeIncluded"], False)
        self.assertIs(manifest["vfgImplemented"], False)
        self.assertEqual(manifest["frameEffects"], ["SR", "HDR"])
        self.assertEqual({row["sourcePath"] for row in manifest["sourceFiles"]}, {
            "desktop/native/mpv-rtx-bridge/vf_bilipai_rtx.c",
            "desktop/native/mpv-rtx-bridge/bilipai_rtx_mpv_bridge.c",
            "desktop/native/mpv-rtx-bridge/bilipai_rtx_mpv_bridge.h",
            "desktop/native/veyra-core/bilipai_veyra_core_v1.h"})

    def test_diagnostic_dispatch_keeps_normal_build_and_publish_excluded(self):
        name = "windows-desktop.yml"
        self.assertTrue(self.admitted(name, "render-diagnostic"))
        self.assertFalse(self.admitted(name, "render-diagnostic", **{"github.event_name": "push"}))
        self.assertFalse(self.admitted(name, "windows", **{"inputs.render_diagnostic": True}))
        self.assertFalse(self.admitted(name, "publish", **{"needs.windows.outputs.release": ""}))

    def test_comment_search_is_manual_only_and_excludes_normal_jobs(self):
        name = "windows-desktop.yml"
        self.assertTrue(self.admitted(name, "comment-search-ui"))
        self.assertFalse(self.admitted(name, "comment-search-ui", **{"github.event_name": "push"}))
        self.assertFalse(self.admitted(name, "comment-search-ui", **{"inputs.comment_search_ui": False}))
        self.assertFalse(self.admitted(name, "windows", **{"inputs.comment_search_ui": True}))
        self.assertFalse(self.admitted(name, "render-diagnostic", **{"inputs.comment_search_ui": True}))
        self.assertFalse(self.admitted(name, "publish", **{"needs.windows.outputs.release": ""}))
        self.assertRegex(self.workflows[name], r"(?m)^      comment_search_ui:$")

    def test_job_environment_does_not_resolve_runner_context_before_allocation(self):
        for (name, job), body in self.jobs.items():
            with self.subTest(name=name, job=job):
                for block in re.findall(r"(?m)^    env:\n((?:      [^\n]*(?:\n|$))+)", body):
                    self.assertNotRegex(block, r"\$\{\{[^}]*\brunner\.")

    def test_manual_dispatch_remains_without_duplicate_github_schedule(self):
        name = "windows-upstream-sync.yml"
        self.assertRegex(self.workflows[name], r"(?m)^  workflow_dispatch:$")
        self.assertNotRegex(self.workflows[name], r"(?m)^  schedule:")
        self.assertTrue(self.admitted(name, "detect", **{"vars.BILIPAI_WINDOWS_AUTO_SYNC": "false"}))
        self.assertFalse(self.admitted(name, "detect", **{"github.event_name": "schedule", "vars.BILIPAI_WINDOWS_AUTO_SYNC": "false"}))
        self.assertTrue(self.admitted(name, "detect", **{"github.event_name": "schedule"}))

    def test_candidate_and_publish_retain_original_required_outputs(self):
        self.assertFalse(self.admitted("windows-upstream-sync.yml", "candidate", **{"needs.detect.outputs.update_needed": "false"}))
        self.assertFalse(self.admitted("windows-desktop.yml", "publish", **{"needs.windows.outputs.release": "false"}))
        self.assertFalse(self.admitted("windows-upstream-sync.yml", "recover_publication", **{"needs.detect.result": "failure"}))
        self.assertFalse(self.admitted("windows-upstream-sync.yml", "recover_publication", **{"vars.BILIPAI_WINDOWS_AUTO_PUBLISH": "false"}))


if __name__ == "__main__":
    unittest.main()
