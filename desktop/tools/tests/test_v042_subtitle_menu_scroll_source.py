"""Pinned upstream subtitle-menu scroll on the generated bottom bar. No Gradle."""
from pathlib import Path
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v042_player_readability as readability

REL = 'feature/video/ui/overlay/BottomControlBar.kt'


def producer():
    spec = importlib.util.spec_from_file_location(
        'subtitle_menu_controls', TOOLS / 'extract-upstream-video-player-full-controls.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def commit_present(commit):
    done = subprocess.run(
        ['git', '-C', str(REPO), 'cat-file', '-e', commit + '^{commit}'], capture_output=True)
    return done.returncode == 0


class SubtitleMenuScrollSourceTest(unittest.TestCase):
    def test_hunk_sha256_matches_constant(self):
        digest = hashlib.sha256(readability.BOTTOM_UPSTREAM_HUNK.encode()).hexdigest()
        self.assertEqual(readability.BOTTOM_UPSTREAM_HUNK_SHA256, digest)

    @unittest.skipUnless(
        all(commit_present(commit) for commit in readability.BOTTOM_UPSTREAM_COMMITS),
        'local git is missing v0.4.2 5c34a82418b33bf88acea01784bb3c6b179d6491 '
        'or v0.4.6 d6bf8b1655ae910999860bf13e65c8fcea5b345b; '
        'git show of BottomControlBar.kt was not checked')
    def test_upstream_commits_contain_the_hunk_once_at_the_pinned_blob(self):
        for commit in readability.BOTTOM_UPSTREAM_COMMITS:
            shown = subprocess.check_output(
                ['git', '-C', str(REPO), 'show', commit + ':' + readability.BOTTOM], text=True)
            self.assertEqual(1, shown.count(readability.BOTTOM_UPSTREAM_HUNK))
            blob = subprocess.check_output(
                ['git', '-C', str(REPO), 'rev-parse', commit + ':' + readability.BOTTOM], text=True).strip()
            self.assertEqual(readability.BOTTOM_UPSTREAM_BLOB, blob)

    def test_producer_emits_hunk_once_and_rejects_a_second_or_moved_anchor(self):
        with tempfile.TemporaryDirectory(prefix='v042-subtitle-menu-scroll-') as temp:
            out = Path(temp)
            producer().generate(REPO, out, True)
            generated_path = out / 'com/android/purebilibili' / REL
            generated = generated_path.read_text(encoding='utf-8')
            self.assertEqual(1, generated.count(readability.BOTTOM_UPSTREAM_HUNK))
            proof = json.loads((out / 'v042-player-readability-proofs' / 'BottomControlBar.kt.json').read_text(encoding='utf-8'))
            self.assertTrue(proof['fullInputInverseRecovered'])
            self.assertEqual(1, len(proof['edits']))
            self.assertEqual(readability.BOTTOM_UPSTREAM_HUNK_SHA256, proof['sourceSHA256'])
            self.assertEqual(readability.BOTTOM_UPSTREAM_BLOB, proof['upstreamBlob'])
            self.assertEqual(list(readability.BOTTOM_UPSTREAM_COMMITS), proof['upstreamCommits'])
            self.assertTrue(proof['wholeFileNotPinned'])
            self.assertEqual('plr-overlay-and-controlbar-repin', proof['supersededBy'])
            restored = generated
            for edit in reversed(proof['edits']):
                at = edit['offset']
                self.assertEqual(edit['after'], restored[at:at + len(edit['after'])])
                restored = restored[:at] + edit['before'] + restored[at + len(edit['after']):]
            self.assertEqual(proof['inputSHA256'], hashlib.sha256(restored.encode()).hexdigest())
            with self.assertRaises(ValueError):
                readability.apply_and_record(REPO, REL, generated, out / 'second')
            anchor = readability.BOTTOM_MENU_WIDTH
            self.assertEqual(1, restored.count(anchor))
            mutated = restored.replace(anchor, anchor.replace('140.dp', '141.dp'), 1)
            with self.assertRaises(ValueError):
                readability.apply_and_record(REPO, REL, mutated, out / 'mutated')


if __name__ == '__main__':
    unittest.main()
