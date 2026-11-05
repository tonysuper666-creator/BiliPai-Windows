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

    def check(self):
        report = self.root / "report.json"
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = gate_a.main(["--repo", str(self.repo), "check", "--baseline", str(self.baseline),
                                "--results", str(self.desktop), "--results", str(self.core), "--report", str(report)])
        return code, (json.loads(report.read_text(encoding="utf-8")) if report.exists() else None)

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
