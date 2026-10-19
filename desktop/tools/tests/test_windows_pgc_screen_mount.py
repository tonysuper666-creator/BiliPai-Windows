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
import v046_bangumi_player as bangumi_player


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

        # The like-status layer of bgm-player-viewmodel-and-features pins the real chain, which this
        # capture run deliberately is not: it is switched off here and peeled below for the real run.
        with patch.object(stage, 'apply_and_record', side_effect=capture), \
                patch.object(bangumi_player, 'apply_retry_ui', side_effect=lambda repo, relative, body, output: body):
            cls.run_producer(cls.baseline)
        cls.run_producer(cls.generated)
        cls.before = cls.captured['screen']
        cls.current = (cls.generated / stage.TARGET).read_text(encoding='utf-8')
        # The initial-load gate precedes the reviewed full v046 comment stage.
        # Check the unchanged old contract through that stage's exact inverse,
        # while also checking the connected current caller below.
        from v046_favorite_editor import restore
        cls.comment_proof = json.loads((cls.generated /
            'v046-DesktopOriginalBangumiPlayerScreen-comment-caller-proof.json').read_bytes())
        retry_proof = json.loads((cls.generated / 'v046-bangumi-player-proofs' /
            'ui-retry-DesktopOriginalBangumiPlayerScreen.json').read_bytes())
        cls.after = restore(restore(cls.current, retry_proof['retainedToCurrent']), cls.comment_proof['retainedToCurrent'])

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
        added = {Path('com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiCommentPanel.kt'),
                 Path('com/android/purebilibili/feature/video/ui/components/DesktopOriginalVideoCommentFloatingHeader.kt')}
        retained = {Path(row['output']) for row in self.producer.RECIPES} | {
            Path(self.producer.POLICY['output']),
            Path('com/android/purebilibili/core/store/DesktopOriginalBangumiPlayerUiSettings.kt'),
            Path('com/android/purebilibili/feature/bangumi/DesktopOriginalBangumiSponsorPolicy.kt')}
        self.assertEqual(len(retained), 8)
        self.assertEqual(originals - added, retained)
        self.assertEqual(originals & added, added)
        # The like-status layer of bgm-player-viewmodel-and-features threads one flag through three outputs
        # (the Screen is handled by the dedicated tests above); the other two are compared after peeling it.
        threaded = {Path(output) for output in bangumi_player.UI_RETRY_HUNKS}
        for name in originals - {Path(stage.TARGET)} - threaded:
            self.assertEqual((self.baseline / name).read_bytes(), (self.generated / name).read_bytes(), str(name))
        from v046_favorite_editor import restore
        for name in threaded - {Path(stage.TARGET)}:
            retry = json.loads((self.generated / 'v046-bangumi-player-proofs' / ('ui-retry-' + name.stem + '.json')).read_bytes())
            self.assertEqual((self.baseline / name).read_text(encoding='utf-8'),
                             restore((self.generated / name).read_text(encoding='utf-8'), retry['retainedToCurrent']), str(name))

    def test_record_binds_the_real_complete_producer_not_a_synthetic_fragment(self):
        proof = json.loads((self.generated / 'windows-pgc-screen-mount-proof.json').read_text(encoding='utf-8'))
        self.assertEqual(proof['originalInputSHA256LF'], stage.sha(self.before))
        self.assertEqual(proof['generatedOutputSHA256LF'], stage.sha(self.after))
        self.assertTrue(proof['inverseByteEqual'])
        self.assertFalse(proof['kotlinCompiled'])
        self.assertFalse(proof['applicationExecuted'])
        self.assertEqual(1, self.current.count('platform.runInitialScreenLoad(viewModel) {'))
        self.assertIn('isVideoPlaying = playbackDebugSnapshot.isPlaying,', self.current)
        self.assertIn('onTimestampClick = ::seekFromComment,', self.current)
        self.assertIn('if (!platform.owns() || !exoPlayer.isOwned()) return', self.current)
        self.assertTrue(self.comment_proof['actualNativeSeekAction'])
        self.assertTrue(self.comment_proof['noAdditionalPlayerOrListener'])

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
