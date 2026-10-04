from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[3]
TOOL = REPO / "desktop/tools/extract-upstream-dynamic-reply.py"
LEGACY_AICU = Path("com/android/purebilibili/feature/aicu/DesktopOriginalReplyAicuNavigation.kt")


class DynamicReplyFreshGenerationTest(unittest.TestCase):
    def generate(self, output):
        return subprocess.run(
            [sys.executable, "-B", str(TOOL), "--repo", str(REPO), "--output", str(output)],
            cwd=output.parent,
            capture_output=True,
            text=True,
            encoding="utf-8",
            timeout=90,
        )

    def test_fresh_output_generates_dissolve_without_legacy_generated_cache(self):
        with tempfile.TemporaryDirectory(prefix="bp-reply-fresh-") as name:
            output = Path(name) / "never-generated"
            self.assertFalse(output.exists())
            result = self.generate(output)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse((output / LEGACY_AICU).exists())
            dissolve = output / "com/bilipai/desktop/ui/DesktopOriginalReplyFailedCaptureDissolve.kt"
            text = dissolve.read_text(encoding="utf-8")
            self.assertIn("object DissolveAnimationManager", text)
            self.assertIn("fun DissolvableVideoCard(", text)
            self.assertIn("internal fun DesktopReplyDissolvableContainer(", text)
            self.assertIn("hasCompletedCurrentDissolve", text)
            self.assertIn("finishEffect()", text)
            # Reuse also follows the same no-legacy branch and is byte deterministic.
            first = {path.relative_to(output): path.read_bytes() for path in output.rglob("*.kt")}
            result = self.generate(output)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(first, {path.relative_to(output): path.read_bytes() for path in output.rglob("*.kt")})

    def test_unknown_legacy_output_is_rejected_and_preserved(self):
        with tempfile.TemporaryDirectory(prefix="bp-reply-unknown-legacy-") as name:
            output = Path(name) / "output"
            legacy = output / LEGACY_AICU
            legacy.parent.mkdir(parents=True)
            unexpected = b"unknown legacy data must not be removed\n"
            legacy.write_bytes(unexpected)
            result = self.generate(output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("AssertionError", result.stderr)
            self.assertEqual(legacy.read_bytes(), unexpected)


if __name__ == "__main__":
    unittest.main()
