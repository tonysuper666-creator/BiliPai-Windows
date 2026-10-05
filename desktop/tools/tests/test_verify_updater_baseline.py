"""Byte admission before the existing real previous-EXE forwarding test."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("updater_baseline", TOOLS / "verify-updater-baseline.py")
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)


class UpdaterBaselineTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.package = self.root / "previous.zip"
        self.data = b"Synthetic verifier bytes only; never executed."
        self.package.write_bytes(self.data)
        self.pin = dict(baseline.BASELINE, filename=self.package.name, bytes=len(self.data),
                        sha256=hashlib.sha256(self.data).hexdigest())

    def test_exact_bytes_admitted_without_extraction_or_execution(self):
        result = baseline.verify(self.package, self.pin)
        self.assertTrue(result["verified"])
        self.assertFalse(result["applicationLaunched"])
        self.assertEqual(list(self.root.iterdir()), [self.package])

    def test_wrong_bytes_of_same_length_are_rejected(self):
        self.package.write_bytes(b"x" * len(self.data))
        with self.assertRaisesRegex(ValueError, "hash differs"):
            baseline.verify(self.package, self.pin)

    def test_truncated_zip_is_rejected(self):
        self.package.write_bytes(self.data[:-1])
        with self.assertRaisesRegex(ValueError, "size differs"):
            baseline.verify(self.package, self.pin)

    def test_wrong_name_cannot_impersonate_the_expected_package(self):
        other = self.root / "another.zip"
        other.write_bytes(self.data)
        with self.assertRaisesRegex(ValueError, "expected regular ZIP"):
            baseline.verify(other, self.pin)

    def test_missing_file_is_rejected(self):
        with self.assertRaises(FileNotFoundError):
            baseline.verify(self.root / "missing.zip", self.pin)

    def test_directory_cannot_be_used_as_package(self):
        directory = self.root / "directory.zip"
        directory.mkdir()
        with self.assertRaisesRegex(ValueError, "expected regular ZIP"):
            baseline.verify(directory, dict(self.pin, filename=directory.name))

    def test_workflow_fetches_exact_owned_artifact_and_verifies_before_build(self):
        workflow = (TOOLS.parents[1] / ".github/workflows/windows-desktop.yml").read_text(encoding="utf-8")
        windows = workflow.split("\n  windows:\n", 1)[1].split("\n  publish:", 1)[0]
        self.assertIn("artifact-ids: " + str(baseline.BASELINE["artifactId"]), windows)
        self.assertIn("run-id: " + str(baseline.BASELINE["runId"]), windows)
        self.assertIn("repository: " + baseline.BASELINE["repository"], windows)
        self.assertIn(baseline.BASELINE["filename"], windows)
        self.assertLess(windows.index("verify-updater-baseline.py"), windows.index("Build tested portable Windows package"))
        self.assertIn("$parameters.PreviousUpdateTestPackage = $env:BILIPAI_PREVIOUS_UPDATE_TEST_PACKAGE", windows)
        self.assertIn("if (-not $env:BILIPAI_PREVIOUS_UPDATE_TEST_PACKAGE)", windows)


if __name__ == "__main__":
    unittest.main()
