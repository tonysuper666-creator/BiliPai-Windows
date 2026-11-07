"""Exercise the real download producer and its exact-anchor failure policy."""
import ast
import importlib.util
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


if __name__ == '__main__':
    unittest.main()
