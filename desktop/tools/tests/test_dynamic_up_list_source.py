"""Exercise the real producer CLI, its actual receipt, and fail-closed counterexamples."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOL = Path(__file__).resolve().parents[1] / 'dynamic_up_list.py'
REPO = TOOL.parents[2]
sys.path.insert(0, str(TOOL.parent))
spec = importlib.util.spec_from_file_location('up_list_source_test', TOOL)
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)


class DynamicUpListSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dynamic-up-list-')
        self.output = Path(self.temp.name) / 'output'
        self.command = [sys.executable, str(TOOL), '--repo', str(REPO), '--output', str(self.output)]
        self.run_cli()

    def tearDown(self):
        self.temp.cleanup()

    def run_cli(self, *flags, expected=0):
        result = subprocess.run(self.command + list(flags), text=True, capture_output=True)
        self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
        return result

    def rewrite_receipt_hash(self, relative):
        receipt = self.output / 'dynamic-up-list-proof.json'
        body = json.loads(receipt.read_text())
        body['outputs'][relative] = hashlib.sha256((self.output / relative).read_bytes()).hexdigest()
        receipt.write_text(json.dumps(body))

    def test_actual_cli_output_receipt_inverse_and_determinism(self):
        before = {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()}
        result = json.loads(self.run_cli('--verify').stdout)
        self.assertEqual(2, len(result['outputs']))
        receipt = json.loads((self.output / 'dynamic-up-list-proof.json').read_text())
        self.assertEqual(source.sha(receipt['originalControl']), result['controlInverseSha256'])
        self.assertEqual(source.sha(receipt['storeSelection']), result['storeSelectionSha256'])
        self.run_cli()
        self.assertEqual(before, {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()})

    def test_corrupted_actual_output_rejected(self):
        file = self.output / source.STORE_OUTPUT
        file.write_text(file.read_text().replace('?: true', '?: false'))
        self.assertIn('actual output changed', self.run_cli('--verify', expected=1).stderr)

    def test_store_mutation_with_matching_receipt_is_still_rejected(self):
        file = self.output / source.STORE_OUTPUT
        file.write_text(file.read_text().replace('?: true', '?: false'))
        self.rewrite_receipt_hash(source.STORE_OUTPUT)
        self.assertIn('wrapper or body changed', self.run_cli('--verify', expected=1).stderr)

    def test_unregistered_control_or_import_mutation_rejected(self):
        file = self.output / source.FIELD_OUTPUT
        file.write_text(file.read_text().replace('checked = visible', 'checked = true'))
        self.rewrite_receipt_hash(source.FIELD_OUTPUT)
        self.assertIn('wrapper or body changed', self.run_cli('--verify', expected=1).stderr)

    def test_pinned_original_and_seam_receipt_are_fail_closed(self):
        archive = Path(self.temp.name) / 'archive'
        shutil.copytree(source.ARCHIVE, archive)
        file = archive / 'DynamicSettingsStore.kt'
        file.write_text(file.read_text().replace('?: true', '?: false'))
        with patch.object(source, 'ARCHIVE', archive), self.assertRaisesRegex(ValueError, 'original changed'):
            source.verify(REPO, self.output)
        receipt = self.output / 'dynamic-up-list-proof.json'
        body = json.loads(receipt.read_text())
        body['controlEdits'][0]['after'] = 'unregistered'
        receipt.write_text(json.dumps(body))
        self.assertIn('seam receipt changed', self.run_cli('--verify', expected=1).stderr)


if __name__ == '__main__':
    unittest.main()
