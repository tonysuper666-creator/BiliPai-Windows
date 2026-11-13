from pathlib import Path
import os
import subprocess
import tempfile
import unittest

import check_slice_symbol_closure as gate
from compiled_source_fixture import relative_inside, scratch_directory


class WindowsFixturePathTests(unittest.TestCase):
    def test_mirror_budget_and_original_temp(self):
        self.assertEqual((259, 181, 8, 69), (gate.WINDOWS_MAX_PATH, gate.LONGEST_PRODUCER_PATH,
                                            gate.PRODUCER_PATH_MARGIN, gate.MAX_SCRATCH_ROOT))
        with scratch_directory(Path('.legacy-root')) as scratch:
            self.assertEqual(Path(tempfile.gettempdir()).resolve(), scratch.parent.resolve())
            gate.check_scratch_root(gate.mirror_root('.legacy-root', scratch))

    def test_69_allowed_and_70_rejected(self):
        parent = os.path.abspath(os.path.join(tempfile.gettempdir(), ''))
        self.assertLess(len(parent) + 1, 69)
        root = parent + os.sep + 'x' * (69 - len(parent) - 1)
        gate.check_scratch_root(root, windows=True)
        with self.assertRaises(gate.ToolError):
            gate.check_scratch_root(root + 'x', windows=True)

    def test_full_identity_and_escapes(self):
        with tempfile.TemporaryDirectory(prefix='bp-') as temporary:
            base = Path(temporary)
            root, outside = base / 'root', base / 'outside'
            root.mkdir()
            outside.mkdir()
            expected, different = root / 'file.kt', outside / 'file.kt'
            expected.write_text('expected')
            different.write_text('different')
            self.assertEqual(Path('file.kt'), relative_inside(gate._long(expected), root))
            self.assertFalse(expected.samefile(different))
            with self.assertRaises(ValueError):
                relative_inside(different, root)
            link = root / 'escape'
            if os.name == 'nt':
                subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(outside)],
                               check=True, capture_output=True)
                self.addCleanup(lambda: link.rmdir() if link.exists() else None)
            else:
                link.symlink_to(outside, target_is_directory=True)
            with self.assertRaises(ValueError):
                relative_inside(link / 'file.kt', root)
            if os.name == 'nt':
                link.rmdir()
