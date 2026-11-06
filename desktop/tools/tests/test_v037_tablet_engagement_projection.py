from pathlib import Path
import contextlib
import io
import json
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v037_player_known_presentation as known


class TabletEngagementProjectionTest(unittest.TestCase):
    def test_actual_producer_preserves_whole_body_and_forwards_required_flags(self):
        captured = {}
        projected = {}
        original_apply = known.apply

        def observe(path, body, repo, output):
            result = original_apply(path, body, repo, output)
            if Path(path).name in {'TabletVideoLayout.kt', 'TabletCinemaLayout.kt'}:
                captured[Path(path).name] = body
                projected[Path(path).name] = result
            return result

        with tempfile.TemporaryDirectory(prefix='bp-tablet-flags-') as directory:
            output = Path(directory)
            arguments = ['tablet', '--repo', str(REPO), '--output', str(output)]
            with patch.object(sys, 'argv', arguments), patch.object(known, 'apply', side_effect=observe), contextlib.redirect_stdout(io.StringIO()):
                runpy.run_path(str(REPO / 'desktop/tools/extract-upstream-video-tablet-full.py'), run_name='__main__')
            self.assertEqual(set(captured), {'TabletVideoLayout.kt', 'TabletCinemaLayout.kt'})
            self.assertEqual(set(projected), set(captured))
            for name, before in captured.items():
                target = next(output.rglob(name))
                body = target.read_text(encoding='utf-8')
                proof = json.loads(next(output.rglob(name + '.v037-known-presentation-proof.json')).read_bytes())
                # The receipt describes this layer's own output: later layers of the same producer (v046 comment list in
                # aad198489, part selector cover in 545928dc1, holder collapse in 3bdfc9862, refresh lists in 591685d9e) carry
                # their own receipts and tests, so the edits are undone from what the actual producer run got from this layer.
                restored = projected[name]
                for edit in reversed(proof['indexedEdits']):
                    start = edit['afterOffset']
                    self.assertEqual(restored[start:start + len(edit['after'])], edit['after'])
                    restored = restored[:start] + edit['before'] + restored[start + len(edit['after']):]
                self.assertEqual(restored, before)
                self.assertIn('followStatusKnown: Boolean,', body)
                self.assertIn('followStatusKnown = followStatusKnown,', body)
                self.assertNotIn('followStatusKnown = true', body)
                self.assertNotIn('likeStatusKnown = true', body)
                if name == 'TabletVideoLayout.kt':
                    self.assertIn('likeStatusKnown: Boolean,', body)
                    self.assertIn('likeStatusKnown = engagementState.likeStatusKnown,', body)
                    self.assertIn('likeStatusKnown = likeStatusKnown,', body)
                else:
                    self.assertEqual(body.count('followStatusKnown = engagement.followStatusKnown,'), 2)
                    self.assertIn('likeStatusKnown = engagement.likeStatusKnown,', body)
                with self.assertRaises(AssertionError):
                    known.apply(name, before.replace('isFollowing = isFollowing,', 'removedFollowing = isFollowing,'), REPO, output / 'drift')


if __name__ == '__main__':
    unittest.main()
