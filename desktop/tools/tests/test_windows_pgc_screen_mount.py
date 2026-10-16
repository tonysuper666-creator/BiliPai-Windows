"""Whole original producer integration; no Kotlin, GUI or copied playback model."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))
import windows_pgc_screen_mount as stage


class WindowsPgcScreenMountTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='pgc-complete-screen-producer-')
        cls.directory = Path(cls.temp.name)
        # Sparse local worktrees may point at the accepted canonical input tree.
        # The producer still comes from this test's exact source checkout.
        cls.source = Path(os.environ.get('BILIPAI_PGC_TEST_SOURCE_REPO', ROOT))
        spec = importlib.util.spec_from_file_location('pgc_mount_complete_producer', TOOLS / 'extract-upstream-bangumi-player-ui.py')
        cls.producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.producer)
        cls.baseline = cls.directory / 'baseline'
        cls.generated = cls.directory / 'generated'
        cls.captured = {}

        def capture(path, body, output, edits=None):
            if path == stage.TARGET:
                cls.captured['screen'] = body
            return body

        with patch.object(stage, 'apply_and_record', side_effect=capture):
            cls.run_producer(cls.baseline)
        cls.run_producer(cls.generated)
        cls.before = cls.captured['screen']
        cls.after = (cls.generated / stage.TARGET).read_text(encoding='utf-8')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    @classmethod
    def run_producer(cls, destination):
        with patch.object(sys, 'argv', ['extract-upstream-bangumi-player-ui.py', '--repo', str(cls.source), '--output', str(destination)]), contextlib.redirect_stdout(io.StringIO()):
            cls.producer.main()

    def effect(self, body, header):
        return body[body.index(header):body.index(stage.NEXT)]

    def test_actual_complete_input_and_output_are_reviewed_pins(self):
        self.assertEqual(stage.sha(self.before), stage.INPUT_SHA256_LF)
        self.assertEqual(stage.sha(self.after), stage.OUTPUT_SHA256_LF)
        self.assertGreater(len(self.after), 30_000)

    def test_only_initial_effect_changes_in_the_whole_original_screen(self):
        start = self.before.index(stage.HEADER)
        end = self.before.index(stage.NEXT)
        self.assertEqual(self.after[:start], self.before[:start])
        self.assertEqual(self.after[self.after.index(stage.NEXT):], self.before[end:])
        original = self.before[start:end]
        replacement = self.after[start:self.after.index(stage.NEXT)]
        self.assertEqual(self.after.replace(replacement, original, 1), self.before)

    def test_all_original_attach_load_statements_remain_in_order(self):
        original = self.effect(self.before, stage.HEADER)
        adapted = self.effect(self.after, '    LaunchedEffect(platform, viewModel, exoPlayer,')
        original_lines = [s.strip() for s in original.splitlines()[1:-2] if s.strip()]
        adapted_lines = [s.strip() for s in adapted.splitlines()[2:-3] if s.strip()]
        self.assertEqual(adapted_lines, original_lines)
        self.assertEqual(self.after.count('viewModel.attachPlayer(exoPlayer)'), 1)
        self.assertEqual(self.after.count('viewModel.loadBangumiPlay('), self.before.count('viewModel.loadBangumiPlay('))

    def test_gate_is_keyed_by_both_retained_platform_and_viewmodel(self):
        self.assertIn('LaunchedEffect(platform, viewModel, exoPlayer, seasonId, epId, resumePositionMs, isCourse, preferredAid)', self.after)
        self.assertEqual(self.after.count('platform.runInitialScreenLoad(viewModel) {'), 1)
        self.assertNotIn('lastBegun', self.after)
        self.assertNotIn('screenLaunch(', self.after)

    def test_all_other_complete_original_ui_and_policy_outputs_are_byte_equal(self):
        originals = {p.relative_to(self.baseline) for p in self.baseline.rglob('*.kt')}
        generated = {p.relative_to(self.generated) for p in self.generated.rglob('*.kt')}
        self.assertEqual(originals, generated)
        self.assertEqual(len(originals), 8)
        for name in originals - {Path(stage.TARGET)}:
            self.assertEqual((self.baseline / name).read_bytes(), (self.generated / name).read_bytes(), str(name))

    def test_record_binds_the_real_complete_producer_not_a_synthetic_fragment(self):
        proof = json.loads((self.generated / 'windows-pgc-screen-mount-proof.json').read_text(encoding='utf-8'))
        self.assertEqual(proof['originalInputSHA256LF'], stage.sha(self.before))
        self.assertEqual(proof['generatedOutputSHA256LF'], stage.sha(self.after))
        self.assertTrue(proof['inverseByteEqual'])
        self.assertFalse(proof['kotlinCompiled'])
        self.assertFalse(proof['applicationExecuted'])

    def test_non_screen_outputs_have_no_gate_or_proof_side_effect(self):
        directory = self.directory / 'non-screen'
        edits = []
        self.assertEqual(stage.apply_and_record('another.kt', 'arbitrary unchanged body', directory, edits), 'arbitrary unchanged body')
        self.assertFalse(directory.exists())
        self.assertEqual(edits, [])

    def test_source_drift_is_rejected_before_adaptation(self):
        with self.assertRaisesRegex(ValueError, 'input changed'):
            stage.adapt(stage.TARGET, self.before + '\n')

    def test_duplicate_initial_effect_is_rejected_even_if_reviewer_updates_input_pin(self):
        changed = self.before.replace(stage.HEADER, stage.HEADER + stage.HEADER, 1)
        with patch.object(stage, 'INPUT_SHA256_LF', stage.sha(changed)), self.assertRaisesRegex(ValueError, 'ambiguous'):
            stage.adapt(stage.TARGET, changed)

    def test_partial_attach_load_body_is_rejected(self):
        changed = self.before.replace('viewModel.attachPlayer(exoPlayer)', '/* removed attach */', 1)
        with patch.object(stage, 'INPUT_SHA256_LF', stage.sha(changed)), self.assertRaisesRegex(ValueError, 'attach/load body changed'):
            stage.adapt(stage.TARGET, changed)

    def test_already_adapted_screen_is_rejected_instead_of_double_wrapping(self):
        with self.assertRaisesRegex(ValueError, 'input changed'):
            stage.adapt(stage.TARGET, self.after)


if __name__ == '__main__':
    unittest.main()
