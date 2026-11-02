"""Regression for the desktop default when the upstream contract is migrated."""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RELATIVE = Path('app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/VideoPlayerOverlayContracts.kt')
PATCH = ROOT / 'desktop/upstream-core/patches' / (str(RELATIVE) + '.patch')


def assert_viewport_default(test, source):
    match = re.search(r'val portraitDanmakuDisplayAreaMode\s*:\s*PortraitDanmakuDisplayAreaMode\s*=\s*PortraitDanmakuDisplayAreaMode\.(\w+)', source)
    test.assertIsNotNone(match, 'missing default in upstream contract')
    test.assertEqual(match.group(1), 'VIDEO_VIEWPORT')


class OverlayContractDesktopDefaultTest(unittest.TestCase):
    def test_actual_patch_preserves_desktop_default_and_upstream_is_counterexample(self):
        upstream = (ROOT / RELATIVE).read_text()
        with self.assertRaises(AssertionError):
            assert_viewport_default(self, upstream)
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / RELATIVE
            destination.parent.mkdir(parents=True)
            destination.write_text(upstream)
            subprocess.run(['patch', '--batch', '-p1', '-i', str(PATCH)], cwd=tmp, check=True, capture_output=True, text=True)
            result = destination.read_text()
        assert_viewport_default(self, result)


if __name__ == '__main__':
    unittest.main()
