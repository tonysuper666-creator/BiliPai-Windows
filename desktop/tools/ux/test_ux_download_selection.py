"""Exercise the real download producer and its exact-anchor failure policy."""
import ast
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PRODUCER = ROOT / 'desktop/tools/extract-upstream-download-list.py'


class DownloadSelectionProducerTest(unittest.TestCase):
    def test_real_legacy_root_produces_verified_source_and_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [sys.executable, str(PRODUCER), '--repo', str(ROOT / '.legacy-root'), '--output', directory],
                cwd=ROOT, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('Generated 3 original download list sources', result.stdout)
            self.assertTrue((Path(directory) / 'source-receipt.json').is_file())

    def test_real_editor_rejects_missing_and_duplicate_anchors(self):
        tree = ast.parse(PRODUCER.read_text(encoding='utf-8'))
        generate = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'generate')
        edit = next(n for n in generate.body if isinstance(n, ast.FunctionDef) and n.name == 'edit')
        namespace = {}
        exec(compile(ast.fix_missing_locations(ast.Module(body=[edit], type_ignores=[])), str(PRODUCER), 'exec'), namespace)
        real_edit = namespace['edit']
        self.assertEqual(real_edit('one anchor here', 'anchor', 'replacement', []), 'one replacement here')
        for source in ('nothing here', 'anchor and anchor'):
            with self.subTest(source=source), self.assertRaises(AssertionError):
                real_edit(source, 'anchor', 'replacement', [])

    def test_complete_producer_rejects_missing_and_duplicate_anchors(self):
        # Inject a defect only after the authentic .legacy-root source and blob
        # checks, then run the full producer in a separate process.  This tests
        # its actual failure path without changing the pinned original files.
        text = PRODUCER.read_text(encoding='utf-8')
        check = '  assert s.count(before)==count,repr(before)'
        self.assertEqual(text.count(check), 1)
        anchor = 'import coil3.compose.LocalPlatformContext as LocalContext'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for mode in ('missing', 'duplicate'):
                with self.subTest(mode=mode):
                    replacement = "''" if mode == 'missing' else 'before + before'
                    injection = f'  if before == {anchor!r}: s = s.replace(before, {replacement}, 1)\n'
                    script = root / f'{mode}.py'
                    script.write_text(text.replace(check, injection + check), encoding='utf-8')
                    output = root / f'out-{mode}'
                    env = os.environ.copy()
                    env['PYTHONPATH'] = str(PRODUCER.parent) + os.pathsep + env.get('PYTHONPATH', '')
                    result = subprocess.run(
                        [sys.executable, str(script), '--repo', str(ROOT / '.legacy-root'), '--output', str(output)],
                        cwd=ROOT, env=env, capture_output=True, text=True,
                    )
                    self.assertNotEqual(result.returncode, 0, result.stdout)
                    self.assertIn('AssertionError: ' + repr(anchor), result.stderr)
                    self.assertFalse((output / 'source-receipt.json').exists())


if __name__ == '__main__':
    unittest.main()
