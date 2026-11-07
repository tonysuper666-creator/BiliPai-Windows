"""Gate A judges actual JUnit XML against a same-upstream baseline (desktop/tools/gate_a.py)."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from xml.sax.saxutils import quoteattr

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("gate_a", TOOLS / "gate_a.py")
gate_a = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate_a)


def write_suite(directory: Path, cls: str, cases: dict[str, str]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    body = []
    for name, status in cases.items():
        inner = {"failed": "<failure message='boom'/>", "skipped": "<skipped/>", "passed": ""}[status]
        body.append(f"<testcase name={quoteattr(name)} classname={quoteattr(cls)}>{inner}</testcase>")
    (directory / f"TEST-{cls}.xml").write_text(
        f"<testsuite name={quoteattr(cls)} tests='{len(cases)}'>" + "".join(body) + "</testsuite>", encoding="utf-8")


class GateATest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        (self.repo / "app").mkdir(parents=True)
        (self.repo / "app/Source.kt").write_text("upstream\n", encoding="utf-8")
        self.git("init", "-q")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "upstream")
        tree = self.git("rev-parse", "HEAD:app").strip()
        self.baseline = self.root / "baseline.json"
        self.baseline.write_text(json.dumps({
            "schema": 1, "gate": "A", "baselineSha": "a" * 40,
            "upstream": {"tag": "v0", "commit": "b" * 40, "trees": {"app": tree}},
            "failing": {"desktop:pkg.Upstream": ["red"], "upstream-core:pkg.Core": ["red"]},
            "skipped": {},
            "identities": {"desktop:pkg.Upstream": ["green", "red"], "desktop:pkg.Desktop": ["works"],
                           "upstream-core:pkg.Core": ["green", "red"]},
        }), encoding="utf-8")
        self.desktop = self.root / "desktop/build/test-results"
        self.core = self.root / "desktop/upstream-core/build/test-results"

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-C",
                               str(self.repo), *args], check=True, capture_output=True, text=True).stdout

    def results(self, upstream=None, desktop=None, core=None, extra=None):
        write_suite(self.desktop / "test", "pkg.Upstream", upstream or {"green": "passed", "red": "failed"})
        write_suite(self.desktop / "test", "pkg.Desktop", desktop or {"works": "passed"})
        write_suite(self.core / "test", "pkg.Core", core or {"green": "passed", "red": "failed"})
        for cls, cases in (extra or {}).items():
            write_suite(self.desktop / "test", cls, cases)

    def check(self, targeted=False):
        report = self.root / "report.json"
        report.unlink(missing_ok=True)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = gate_a.main(["--repo", str(self.repo), "check", "--baseline", str(self.baseline),
                                "--results", str(self.desktop), "--results", str(self.core), "--report", str(report)]
                               + (["--targeted"] if targeted else []))
        return code, (json.loads(report.read_text(encoding="utf-8")) if report.exists() else None)

    def targeted_results(self, suites, root=None):
        """Only the given classes ran, as in `:test --tests ...` plus its finalizers."""
        for path in list(self.desktop.glob("*/TEST-*.xml")) + list(self.core.glob("*/TEST-*.xml")):
            path.unlink()
        for cls, cases in suites.items():
            write_suite((root or self.desktop) / "test", cls, cases)

    def with_baseline_skip(self):
        document = json.loads(self.baseline.read_text(encoding="utf-8"))
        document["identities"]["desktop:pkg.Skipped"] = ["optional"]
        document["skipped"]["desktop:pkg.Skipped"] = ["optional"]
        self.baseline.write_text(json.dumps(document), encoding="utf-8")

    def test_baseline_failures_are_tolerated_and_fixes_are_reported(self):
        self.results()
        self.assertEqual(self.check()[0], 0)
        self.results(upstream={"green": "passed", "red": "passed"})
        code, report = self.check()
        self.assertEqual(code, 0)
        self.assertEqual(report["counts"]["fixedSinceBaseline"], 1)

    def test_a_new_failure_fails_the_gate(self):
        self.results(desktop={"works": "failed"})
        code, report = self.check()
        self.assertEqual(code, 1)
        self.assertEqual(report["newFailures"], [{"scope": "desktop", "class": "pkg.Desktop", "name": "works"}])

    def test_a_missing_or_newly_skipped_baseline_test_fails_the_gate(self):
        for desktop in ({"other": "passed"}, {"works": "skipped"}):
            with self.subTest(desktop=desktop):
                for path in self.desktop.glob("test/*.xml"):
                    path.unlink()
                self.results(desktop=desktop)
                code, report = self.check()
                self.assertEqual(code, 1)
                self.assertIn({"scope": "desktop", "class": "pkg.Desktop", "name": "works"}, report["missingOrSkipped"])

    def test_a_new_test_must_pass(self):
        self.results(extra={"pkg.Added": {"added": "passed"}})
        self.assertEqual(self.check()[0], 0)
        self.results(extra={"pkg.Added": {"added": "failed"}})
        self.assertEqual(self.check()[0], 1)

    def test_new_skipped_tests_fail_in_either_scope(self):
        self.results()
        for root, scope in ((self.desktop, "desktop"), (self.core, "upstream-core")):
            with self.subTest(scope=scope), contextlib.ExitStack() as cleanup:
                path = root / "test/TEST-pkg.Added.xml"
                write_suite(path.parent, "pkg.Added", {"added": "skipped"})
                cleanup.callback(path.unlink)
                code, report = self.check()
                self.assertEqual(code, 1)
                self.assertFalse(report["passed"])
                self.assertEqual(report["counts"]["addedSkipped"], 1)
                self.assertEqual(report["addedSkipped"], [
                    {"scope": scope, "class": "pkg.Added", "name": "added"}])
                self.assertEqual(report["newFailures"], [])
                self.assertEqual(report["missingOrSkipped"], [])

    def test_new_pass_and_failure_are_judged_in_either_scope(self):
        self.results()
        for root, scope in ((self.desktop, "desktop"), (self.core, "upstream-core")):
            for status, expected in (("passed", 0), ("failed", 1)):
                with self.subTest(scope=scope, status=status), contextlib.ExitStack() as cleanup:
                    path = root / "test/TEST-pkg.Added.xml"
                    write_suite(path.parent, "pkg.Added", {"added": status})
                    cleanup.callback(path.unlink)
                    code, report = self.check()
                    self.assertEqual(code, expected)
                    self.assertEqual(report["counts"]["addedSinceBaseline"], 1)
                    self.assertEqual(report["counts"]["newFailures"], int(status == "failed"))
                    self.assertEqual(report["addedSkipped"], [])

    def test_duplicate_new_identity_uses_worst_status_in_either_order(self):
        self.results()
        for root, scope in ((self.desktop, "desktop"), (self.core, "upstream-core")):
            for statuses, worst in ((("passed", "skipped"), "skipped"),
                                    (("skipped", "passed"), "skipped"),
                                    (("passed", "failed"), "failed"),
                                    (("failed", "passed"), "failed"),
                                    (("skipped", "failed"), "failed"),
                                    (("failed", "skipped"), "failed")):
                with self.subTest(scope=scope, statuses=statuses), contextlib.ExitStack() as cleanup:
                    for task, status in zip(("test", "other-task"), statuses):
                        write_suite(root / task, "pkg.Added", {"added": status})
                        cleanup.callback((root / task / "TEST-pkg.Added.xml").unlink)
                    code, report = self.check()
                    self.assertEqual(code, 1)
                    self.assertEqual(report["counts"]["addedSinceBaseline"], 1)
                    self.assertEqual(report["counts"]["newFailures"], int(worst == "failed"))
                    self.assertEqual(report["counts"]["addedSkipped"], int(worst == "skipped"))

    def test_duplicate_baseline_identity_cannot_hide_a_failure_or_skip(self):
        self.results()
        for statuses in (("passed", "failed"), ("failed", "passed"),
                         ("passed", "skipped"), ("skipped", "passed")):
            with self.subTest(statuses=statuses):
                for task, status in zip(("test", "other-task"), statuses):
                    write_suite(self.desktop / task, "pkg.Desktop", {"works": status})
                code, report = self.check()
                self.assertEqual(code, 1)
                key = "newFailures" if "failed" in statuses else "missingOrSkipped"
                self.assertIn({"scope": "desktop", "class": "pkg.Desktop", "name": "works"}, report[key])

    def test_scope_change_cannot_replace_a_baseline_identity(self):
        self.results()
        (self.desktop / "test/TEST-pkg.Desktop.xml").unlink()
        write_suite(self.core / "test", "pkg.Desktop", {"works": "passed"})
        code, report = self.check()
        self.assertEqual(code, 1)
        self.assertIn({"scope": "desktop", "class": "pkg.Desktop", "name": "works"}, report["missingOrSkipped"])

    def test_new_class_cannot_replace_a_baseline_identity(self):
        self.results()
        (self.desktop / "test/TEST-pkg.Desktop.xml").unlink()
        write_suite(self.desktop / "test", "pkg.Renamed", {"works": "passed"})
        self.assertEqual(self.check()[0], 1)

    def test_baseline_skip_remains_allowed_including_when_absent(self):
        document = json.loads(self.baseline.read_text(encoding="utf-8"))
        document["identities"]["desktop:pkg.Skipped"] = ["optional"]
        document["skipped"]["desktop:pkg.Skipped"] = ["optional"]
        self.baseline.write_text(json.dumps(document), encoding="utf-8")
        self.results()
        self.assertEqual(self.check()[0], 0)
        for status, expected in (("skipped", 0), ("passed", 0), ("failed", 1)):
            with self.subTest(status=status):
                write_suite(self.desktop / "test", "pkg.Skipped", {"optional": status})
                self.assertEqual(self.check()[0], expected)

    def test_skip_permission_does_not_cross_scopes(self):
        document = json.loads(self.baseline.read_text(encoding="utf-8"))
        document["identities"]["desktop:pkg.Skipped"] = ["optional"]
        document["skipped"]["desktop:pkg.Skipped"] = ["optional"]
        self.baseline.write_text(json.dumps(document), encoding="utf-8")
        self.results()
        write_suite(self.core / "test", "pkg.Skipped", {"optional": "skipped"})
        code, report = self.check()
        self.assertEqual(code, 1)
        self.assertEqual(report["addedSkipped"], [
            {"scope": "upstream-core", "class": "pkg.Skipped", "name": "optional"}])

    def test_no_added_identities_preserves_empty_added_sets(self):
        self.results()
        code, report = self.check()
        self.assertEqual(code, 0)
        self.assertEqual(report["counts"]["addedSinceBaseline"], 0)
        self.assertEqual(report["counts"]["addedSkipped"], 0)
        self.assertEqual(report["addedSkipped"], [])

    def test_empty_suite_is_an_input_error(self):
        write_suite(self.desktop / "test", "pkg.Empty", {})
        self.assertEqual(self.check()[0], 2)

    def test_empty_baseline_is_an_input_error(self):
        document = json.loads(self.baseline.read_text(encoding="utf-8"))
        document.update(identities={}, failing={}, skipped={})
        self.baseline.write_text(json.dumps(document), encoding="utf-8")
        self.results()
        self.assertEqual(self.check()[0], 2)

    def test_upstream_core_and_desktop_copies_are_judged_separately(self):
        # pkg.Core > red is a baseline failure only in the upstream-core project.
        self.results(extra={"pkg.Core": {"red": "failed"}})
        code, report = self.check()
        self.assertEqual(code, 1)
        self.assertEqual(report["newFailures"], [{"scope": "desktop", "class": "pkg.Core", "name": "red"}])

    def test_a_changed_upstream_tree_needs_a_new_baseline(self):
        self.results()
        (self.repo / "app/Source.kt").write_text("next upstream\n", encoding="utf-8")
        self.git("commit", "-q", "-am", "next upstream")
        code, report = self.check()
        self.assertEqual(code, 1)
        self.assertEqual(report["upstream"]["changedTrees"], ["app"])

    def test_no_results_is_an_input_error(self):
        self.assertEqual(self.check()[0], 2)

    def test_full_report_is_marked_full(self):
        self.results()
        code, report = self.check()
        self.assertEqual(code, 0)
        self.assertEqual((report["mode"], report["fullGateEvidence"]), ("full", True))
        self.assertEqual(report["counts"]["absentBaselineNotJudged"], 0)

    # --targeted (TON-1340): only the absence of a baseline identity is waived.

    def test_targeted_waives_only_absent_baseline_identities_and_is_not_full_evidence(self):
        self.targeted_results({"pkg.Upstream": {"green": "passed", "red": "failed"}})
        self.assertEqual(self.check()[0], 1)
        code, report = self.check(targeted=True)
        self.assertEqual(code, 0)
        self.assertTrue(report["passed"])
        self.assertEqual((report["mode"], report["fullGateEvidence"]), ("targeted", False))
        self.assertEqual(report["counts"]["absentBaselineNotJudged"], 3)
        self.assertEqual(report["missingOrSkipped"], [])
        self.assertEqual(report["stillFailingFromBaseline"], [{"scope": "desktop", "class": "pkg.Upstream", "name": "red"}])

    def test_targeted_names_only_the_failure_outside_the_baseline(self):
        self.targeted_results({"pkg.Upstream": {"green": "passed", "red": "failed"},
                               "pkg.Sponsor": {"initializationError": "failed"}})
        code, report = self.check(targeted=True)
        self.assertEqual(code, 1)
        self.assertFalse(report["fullGateEvidence"])
        self.assertEqual(report["newFailures"], [{"scope": "desktop", "class": "pkg.Sponsor", "name": "initializationError"}])
        self.assertEqual(report["counts"]["stillFailingFromBaseline"], 1)
        self.targeted_results({"pkg.Upstream": {"green": "passed", "red": "failed"}})
        self.assertEqual(self.check(targeted=True)[0], 0)

    def test_targeted_rejects_a_baseline_pass_that_now_fails(self):
        self.targeted_results({"pkg.Upstream": {"green": "failed", "red": "failed"}})
        code, report = self.check(targeted=True)
        self.assertEqual(code, 1)
        self.assertEqual(report["newFailures"], [{"scope": "desktop", "class": "pkg.Upstream", "name": "green"}])

    def test_targeted_rejects_added_failures_and_skips_in_either_scope(self):
        for root, scope in ((self.desktop, "desktop"), (self.core, "upstream-core")):
            for status, key in (("failed", "newFailures"), ("skipped", "addedSkipped")):
                with self.subTest(scope=scope, status=status):
                    self.targeted_results({"pkg.Added": {"added": status}}, root=root)
                    code, report = self.check(targeted=True)
                    self.assertEqual(code, 1)
                    self.assertEqual(report[key], [{"scope": scope, "class": "pkg.Added", "name": "added"}])
            with self.subTest(scope=scope, status="passed"):
                self.targeted_results({"pkg.Added": {"added": "passed"}}, root=root)
                self.assertEqual(self.check(targeted=True)[0], 0)

    def test_targeted_rejects_a_present_baseline_identity_that_is_skipped(self):
        for cls, name in (("pkg.Desktop", "works"), ("pkg.Upstream", "red")):
            with self.subTest(cls=cls):
                self.targeted_results({cls: {name: "skipped"}})
                code, report = self.check(targeted=True)
                self.assertEqual(code, 1)
                self.assertEqual(report["missingOrSkipped"], [{"scope": "desktop", "class": cls, "name": name}])

    def test_targeted_keeps_baseline_skips(self):
        self.with_baseline_skip()
        for status, expected in (("skipped", 0), ("passed", 0), ("failed", 1)):
            with self.subTest(status=status):
                self.targeted_results({"pkg.Skipped": {"optional": status}})
                self.assertEqual(self.check(targeted=True)[0], expected)
        self.targeted_results({"pkg.Skipped": {"optional": "skipped"}}, root=self.core)
        code, report = self.check(targeted=True)
        self.assertEqual(code, 1)
        self.assertEqual(report["addedSkipped"], [{"scope": "upstream-core", "class": "pkg.Skipped", "name": "optional"}])

    def test_targeted_rejects_an_identity_moved_to_another_scope(self):
        # Passing under the other scope cannot stand in for the baseline scope's run.
        self.targeted_results({"pkg.Desktop": {"works": "passed"}}, root=self.core)
        code, report = self.check(targeted=True)
        self.assertEqual(code, 1)
        self.assertEqual(report["scopeChanged"], [{"scope": "upstream-core", "class": "pkg.Desktop", "name": "works"}])
        # Nor does a baseline failure travel with its class to the other scope.
        self.targeted_results({"pkg.Core": {"red": "failed"}})
        code, report = self.check(targeted=True)
        self.assertEqual(code, 1)
        self.assertEqual(report["newFailures"], [{"scope": "desktop", "class": "pkg.Core", "name": "red"}])
        # Both copies run: each is judged in its own scope, nothing moved.
        self.targeted_results({"pkg.Core": {"green": "passed", "red": "failed"}}, root=self.core)
        write_suite(self.desktop / "test", "pkg.Core", {"green": "passed"})
        code, report = self.check(targeted=True)
        self.assertEqual(code, 0)
        self.assertEqual(report["scopeChanged"], [])

    def test_full_run_reports_a_moved_identity_as_missing_and_moved(self):
        self.results()
        (self.desktop / "test/TEST-pkg.Desktop.xml").unlink()
        write_suite(self.core / "test", "pkg.Desktop", {"works": "passed"})
        code, report = self.check()
        self.assertEqual(code, 1)
        self.assertIn({"scope": "desktop", "class": "pkg.Desktop", "name": "works"}, report["missingOrSkipped"])
        self.assertEqual(report["scopeChanged"], [{"scope": "upstream-core", "class": "pkg.Desktop", "name": "works"}])

    def test_targeted_duplicates_use_the_worst_status_in_either_order(self):
        for cls, name in (("pkg.Desktop", "works"), ("pkg.Added", "added")):
            for statuses in (("passed", "failed"), ("failed", "passed"), ("passed", "skipped"), ("skipped", "passed")):
                with self.subTest(cls=cls, statuses=statuses):
                    self.targeted_results({})
                    for task, status in zip(("test", "other-task"), statuses):
                        write_suite(self.desktop / task, cls, {name: status})
                    code, report = self.check(targeted=True)
                    self.assertEqual(code, 1)
                    key = ("newFailures" if "failed" in statuses else
                           "missingOrSkipped" if cls == "pkg.Desktop" else "addedSkipped")
                    self.assertEqual(report[key], [{"scope": "desktop", "class": cls, "name": name}])

    def test_targeted_still_needs_the_same_upstream_trees(self):
        self.targeted_results({"pkg.Upstream": {"green": "passed", "red": "failed"}})
        (self.repo / "app/Source.kt").write_text("next upstream\n", encoding="utf-8")
        self.git("commit", "-q", "-am", "next upstream")
        code, report = self.check(targeted=True)
        self.assertEqual(code, 1)
        self.assertEqual(report["upstream"]["changedTrees"], ["app"])

    def test_targeted_refuses_empty_results(self):
        self.assertEqual(self.check(targeted=True)[0], 2)
        write_suite(self.desktop / "test", "pkg.Empty", {})
        self.assertEqual(self.check(targeted=True)[0], 2)

    def test_targeted_cannot_see_a_renamed_class_so_only_full_mode_is_gate_evidence(self):
        # A renamed or removed test is indistinguishable from one not run: why a targeted pass is never gate A.
        self.results()
        (self.desktop / "test/TEST-pkg.Desktop.xml").unlink()
        write_suite(self.desktop / "test", "pkg.Renamed", {"works": "passed"})
        code, report = self.check(targeted=True)
        self.assertEqual((code, report["fullGateEvidence"]), (0, False))
        self.assertEqual(self.check()[0], 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
