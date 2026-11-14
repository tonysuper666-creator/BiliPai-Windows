"""Later stages must restore the exact pinned input and reject stale receipts."""
import json
from pathlib import Path
import tempfile
import unittest

from generation_stage_proof import capture_ui_stage, receipt_path, record_stage, restore_stage


class GenerationStageProofTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.output = Path(temp.name)
        self.stage = 'later-stage'
        self.path = 'com/example/Player.kt'
        self.before = 'same\n旧状态\nsame\nfun old() {}\n'
        self.after = 'same\n新状态\nsame\nfun current() {}\nextra\n'
        record_stage(self.output, self.stage, self.path, self.before, self.after)
        self.receipt = receipt_path(self.output, self.stage, self.path)

    def test_repeated_lines_unicode_and_multiple_edits_restore_the_complete_input(self):
        self.assertEqual(self.before, restore_stage(self.output, self.stage, self.path, self.after))
        first = self.receipt.read_bytes()
        record_stage(self.output, self.stage, self.path, self.before, self.after)
        self.assertEqual(first, self.receipt.read_bytes())

    def test_changed_output_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'output hash'):
            restore_stage(self.output, self.stage, self.path, self.after + '\n')

    def test_another_path_with_the_same_filename_cannot_reuse_the_receipt(self):
        with self.assertRaisesRegex(ValueError, 'identity'):
            restore_stage(self.output, self.stage, 'other/Player.kt', self.after)

    def test_tampered_identity_inverse_offsets_and_input_hash_are_rejected(self):
        original = json.loads(self.receipt.read_text(encoding='utf8'))
        for field in ['stage', 'path', 'exactCompleteInverse', 'indexedEdits', 'beforeSha256LF']:
            with self.subTest(field=field):
                proof = json.loads(json.dumps(original))
                if field == 'exactCompleteInverse':
                    proof[field] = False
                elif field == 'indexedEdits':
                    proof[field][0]['afterOffset'] += 1
                else:
                    proof[field] = 'wrong'
                self.receipt.write_text(json.dumps(proof), encoding='utf8')
                with self.assertRaises(ValueError):
                    restore_stage(self.output, self.stage, self.path, self.after)

    def test_unchanged_outputs_have_no_receipt_and_ui_wrapper_preserves_results(self):
        record_stage(self.output, self.stage, 'Unchanged.kt', self.before, self.before)
        self.assertFalse(receipt_path(self.output, self.stage, 'Unchanged.kt').exists())
        edits = [{'before': self.before, 'after': self.after}]
        def transform(body, path):
            self.assertEqual((self.before, self.path), (body, path))
            return self.after, edits
        wrapped = capture_ui_stage(self.output, self.stage, transform)
        result, actual_edits = wrapped(self.before, self.path)
        self.assertEqual(self.after, result)
        self.assertIs(edits, actual_edits)
        self.assertEqual(self.before, restore_stage(self.output, self.stage, self.path, result))
