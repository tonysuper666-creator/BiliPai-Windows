import base64
import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("package_qualification", Path(__file__).parents[1] / "verify-package-source-qualification.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class PackageSourceQualificationTest(unittest.TestCase):
    def setUp(self):
        self.sha = "a" * 40
        self.plan = dict(baseHead="b" * 40, windowsVersion="0.2.427.79", expectedTests=2,
                         classes={"sample.ActualTest": 2}, sources=[dict(path="desktop/source.kt", sha256="c" * 64)])
        self.run = dict(id=7, repository=dict(full_name=gate.REPOSITORY, private=False), head_sha=self.sha,
                        head_branch="desktop/source-validation-fixture", status="completed", conclusion="success",
                        path=".github/workflows/windows-source-validation.yml")
        self.jobs = {"jobs": [dict(id=9, run_id=7, head_sha=self.sha, name="source-validation",
                                  status="completed", conclusion="success", labels=["windows-latest"])]}
        self.payload = dict(receipt=dict(passed=True, exitCode=0, sourceSha=self.sha, baseHead=self.plan["baseHead"],
            windowsVersion=self.plan["windowsVersion"], sourceUnchanged=True, sourceBefore={"desktop/source.kt": "c" * 64},
            sourceAfter={"desktop/source.kt": "c" * 64}, expectedTests=2,
            suites=[dict(name="sample.ActualTest", fresh=True, backgroundError=False)]),
            rawXml={"sample.ActualTest": '<testsuite name="sample.ActualTest" tests="2" failures="0" errors="0" skipped="0"><testcase name="first"/><testcase name="second"/></testsuite>'})

    def log(self):
        raw = json.dumps(self.payload).encode()
        encoded = base64.b64encode(gzip.compress(raw)).decode()
        return "BILIPAI_SOURCE_RECEIPT_BEGIN " + hashlib.sha256(raw).hexdigest() + "\n" + encoded + "\nBILIPAI_SOURCE_RECEIPT_END\n"

    def verify(self):
        return gate.verify(self.run, self.jobs, self.log(), self.plan, self.sha)

    def test_successful_fresh_source_checks_do_not_claim_package_launch_or_hardware(self):
        proof = self.verify()
        self.assertEqual(2, proof["actualTests"])
        self.assertTrue(proof["sourceQualified"])
        for field in ["packageLaunchVerified", "hardwareVerified", "releaseQualified", "desktopReplaced"]:
            self.assertFalse(proof[field])

    def test_wrong_repository_private_repository_unfinished_run_and_wrong_source_are_rejected(self):
        original = copy.deepcopy(self.run)
        for change in [dict(repository=dict(full_name="jay3-yy/BiliPai", private=False)),
                       dict(repository=dict(full_name=gate.REPOSITORY, private=True)),
                       dict(status="in_progress"), dict(head_sha="d" * 40), dict(conclusion="failure")]:
            self.run = dict(original, **change)
            with self.subTest(change=change), self.assertRaises(ValueError): self.verify()

    def test_different_job_or_nonstandard_runner_cannot_qualify_a_package(self):
        original = copy.deepcopy(self.jobs["jobs"][0])
        for change in [dict(run_id=8), dict(head_sha="d" * 40), dict(labels=["gpu-runner"]), dict(conclusion="failure")]:
            self.jobs["jobs"][0] = dict(original, **change)
            with self.subTest(change=change), self.assertRaises(ValueError): self.verify()

    def test_stale_xml_missing_cases_and_changed_source_are_rejected(self):
        original = copy.deepcopy(self.payload)
        variants = []
        x = copy.deepcopy(original); x["receipt"]["suites"][0]["fresh"] = False; variants.append(x)
        x = copy.deepcopy(original); x["receipt"]["sourceAfter"] = {}; variants.append(x)
        x = copy.deepcopy(original); x["rawXml"]["sample.ActualTest"] = x["rawXml"]["sample.ActualTest"].replace('skipped="0"', 'skipped="1"'); variants.append(x)
        x = copy.deepcopy(original); x["rawXml"]["sample.ActualTest"] = x["rawXml"]["sample.ActualTest"].replace('<testcase name="second"/>', ''); variants.append(x)
        for x in variants:
            self.payload = x
            with self.assertRaises(ValueError): self.verify()

    def test_ambiguous_and_tampered_receipt_transports_are_rejected(self):
        log = self.log()
        for invalid in [log + log, log.replace("BILIPAI_SOURCE_RECEIPT_END", "removed"),
                        log.replace(hashlib.sha256(json.dumps(self.payload).encode()).hexdigest(), "0" * 64, 1)]:
            with self.assertRaises(ValueError): gate.receipt_from_log(invalid)

    def harness_export(self):
        self.plan["exportPackagedUpdaterHarness"] = True
        self.payload["receipt"]["updaterHarnessExport"] = dict(schema=1, sourceSha=self.sha,
            manifestSha256="e" * 64, payloadFiles=6, payloadBytes=12345,
            applicationExecuted=False, updaterE2EExecuted=False)

    def test_opted_export_is_bound_without_claiming_actual_updater_acceptance(self):
        self.harness_export(); proof = self.verify()
        self.assertEqual("e" * 64, proof["updaterHarnessExport"]["manifestSha256"])
        self.assertFalse(proof["updaterHarnessExport"]["updaterE2EExecuted"])
        self.assertFalse(proof["releaseQualified"])

    def test_opted_export_cannot_be_missing_from_the_sealed_receipt(self):
        self.plan["exportPackagedUpdaterHarness"] = True
        with self.assertRaises(ValueError): self.verify()

    def test_export_source_digest_bounds_and_claims_are_validated(self):
        self.harness_export(); original = copy.deepcopy(self.payload["receipt"]["updaterHarnessExport"])
        for change in [dict(sourceSha="f" * 40), dict(manifestSha256="bad"), dict(payloadFiles=True),
                       dict(payloadFiles=1), dict(payloadBytes=65 * 1024 * 1024),
                       dict(applicationExecuted=True), dict(updaterE2EExecuted=True)]:
            self.payload["receipt"]["updaterHarnessExport"] = dict(original, **change)
            with self.subTest(change=change), self.assertRaises(ValueError): self.verify()

    def test_an_export_cannot_qualify_a_failed_source_run(self):
        self.harness_export(); self.run["conclusion"] = "failure"
        with self.assertRaises(ValueError): self.verify()

    def test_colored_console_output_outside_receipt_does_not_change_sealed_evidence(self):
        log = "2026-10-14T11:00:00Z \x1b[36mcompile output\x1b[0m\n" + self.log()
        proof = gate.verify(self.run, self.jobs, log, self.plan, self.sha)
        self.assertEqual(2, proof["actualTests"])
        self.assertTrue(proof["sourceQualified"])


if __name__ == "__main__":
    unittest.main()
