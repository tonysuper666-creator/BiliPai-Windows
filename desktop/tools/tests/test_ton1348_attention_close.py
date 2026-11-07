"""Actual producer receipts and fail-closed inverse; pointer behavior is tested in Compose."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v029_command_vote as vote
import v037_command_overlay as v037
import v046_danmaku_hot_command as command


class AttentionCloseProducerTest(unittest.TestCase):
    def candidate(self):
        old, _ = v037.fixed_original(REPO)
        predecessor = vote.adapt_overlay(old, [])
        body, proof = command.advance_command_overlay(REPO, predecessor)
        return predecessor, body, proof

    def test_complete_source_and_predecessor_round_trip_without_changing_vote_or_actions(self):
        predecessor, body, proof = self.candidate()
        original = command.fixed(REPO)[0]['CommandDanmakuOverlay.kt']
        self.assertEqual(original, command.restore(body, proof['originalToWindows']))
        self.assertEqual(predecessor, command.restore(body, proof['retainedToCurrent']))
        base = vote.adapt_overlay(original, [])
        for start, end in [('private fun AttentionCommandTripleButton(', 'private fun AttentionCommandFollowButton('),
                           ('private fun AttentionCommandFollowButton(', 'private fun AttentionCommandIconSlot('),
                           ('private fun VoteCommandCard(', 'private fun OutsideCommandDanmakuCloseButton(')]:
            self.assertEqual(base[base.index(start):base.index(end)], body[body.index(start):body.index(end)])

    def test_receipt_detects_output_tampering(self):
        _, body, proof = self.candidate()
        with self.assertRaisesRegex(ValueError, 'differs from its receipt'):
            command.restore(body.replace('key(state, item.id)', 'key(item.id)'), proof['originalToWindows'])

    def test_emitted_file_and_receipt_have_verified_input_and_output_hashes(self):
        _, body, proof = self.candidate()
        with tempfile.TemporaryDirectory() as directory:
            record = command.emit_command_overlay(REPO, directory, body, proof)
            generated = Path(directory, command.OVERLAY_RELATIVE).read_text()
            self.assertEqual(record['outputFileSHA256'], command.sha(generated))
            self.assertEqual(record, json.loads(Path(directory, command.OVERLAY_PROOF).read_text()))
            self.assertEqual(command.fixed(REPO)[1]['commit'], record['upstreamCommit'])
            self.assertEqual(command.fixed(REPO)[0]['CommandDanmakuOverlay.kt'],
                command.restore(generated.removeprefix(record['header']), record['originalToWindows']))

    def test_actual_producer_help_and_missing_arguments_are_explicit(self):
        cli = REPO / 'desktop/tools/extract-stable-video-votes.py'
        help_run = subprocess.run([sys.executable, str(cli), '--help'], capture_output=True, text=True)
        self.assertEqual(0, help_run.returncode, help_run.stderr)
        self.assertIn('repository output', help_run.stdout)
        invalid = subprocess.run([sys.executable, str(cli)], capture_output=True, text=True)
        self.assertEqual(2, invalid.returncode)
        self.assertNotIn('Traceback', invalid.stderr)


if __name__ == '__main__':
    unittest.main()
