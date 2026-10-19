"""The subtitle-menu scroll of the generated bottom bar comes from the whole v0.4.6 file. No Gradle.

The one-hunk interim of v042_player_readability (plr-subtitle-menu-hotfix) is retired: v046-player-overlay carries the
complete v0.4.6 BottomControlBar, which contains the same upstream line. This test keeps the upstream evidence for the
line, proves the emitted bar has it exactly once without any hunk layer, and proves the retired layer is gone while the
title and subtitle fixes of the same module still apply.
"""
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
import v046_player_overlay as overlay

REL = 'feature/video/ui/overlay/BottomControlBar.kt'
BOTTOM = 'app/src/main/java/com/android/purebilibili/' + REL
BOTTOM_UPSTREAM_BLOB = 'd2d0723213900bf15d6cf769eaf68496ceb9905f'
BOTTOM_UPSTREAM_COMMITS = (readability.COMMIT, 'd6bf8b1655ae910999860bf13e65c8fcea5b345b')
BOTTOM_UPSTREAM_HUNK = (
    '                Column(\n'
    '                    modifier = Modifier\n'
    '                        .widthIn(min = 140.dp, max = 220.dp)\n'
    '                        .verticalScroll(rememberScrollState())\n'
    '                        .padding(horizontal = 10.dp, vertical = 8.dp),\n'
)
BOTTOM_UPSTREAM_HUNK_SHA256 = 'e8b9cd88a7b12ed404003bedaef0a89ad819b7b68b3de0b7ce9fd0c6bc0dfb80'


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
        digest = hashlib.sha256(BOTTOM_UPSTREAM_HUNK.encode()).hexdigest()
        self.assertEqual(BOTTOM_UPSTREAM_HUNK_SHA256, digest)

    @unittest.skipUnless(
        all(commit_present(commit) for commit in BOTTOM_UPSTREAM_COMMITS),
        'local git is missing v0.4.2 5c34a82418b33bf88acea01784bb3c6b179d6491 '
        'or v0.4.6 d6bf8b1655ae910999860bf13e65c8fcea5b345b; '
        'git show of BottomControlBar.kt was not checked')
    def test_upstream_commits_contain_the_hunk_once_at_the_pinned_blob(self):
        for commit in BOTTOM_UPSTREAM_COMMITS:
            shown = subprocess.check_output(
                ['git', '-C', str(REPO), 'show', commit + ':' + BOTTOM], text=True)
            self.assertEqual(1, shown.count(BOTTOM_UPSTREAM_HUNK))
            blob = subprocess.check_output(
                ['git', '-C', str(REPO), 'rev-parse', commit + ':' + BOTTOM], text=True).strip()
            self.assertEqual(BOTTOM_UPSTREAM_BLOB, blob)

    def test_the_carried_whole_file_is_that_blob_and_holds_the_hunk_once(self):
        sources, _raws, manifest = overlay.fixed(REPO)
        row = next(r for r in manifest['files'] if r['filename'] == 'BottomControlBar.kt')
        self.assertEqual(BOTTOM_UPSTREAM_BLOB, row['gitBlob'])
        self.assertEqual(BOTTOM, row['path'])
        self.assertEqual(1, sources['BottomControlBar.kt'].count(BOTTOM_UPSTREAM_HUNK))

    def test_the_retired_hunk_layer_is_gone_from_the_readability_module(self):
        for name in ('BOTTOM', 'BOTTOM_UPSTREAM_BLOB', 'BOTTOM_UPSTREAM_COMMITS', 'BOTTOM_UPSTREAM_HUNK',
                     'BOTTOM_UPSTREAM_HUNK_SHA256', 'BOTTOM_MENU_WIDTH', 'apply_subtitle_menu_scroll'):
            self.assertFalse(hasattr(readability, name), name)
        text = (TOOLS / 'v042_player_readability.py').read_text(encoding='utf-8')
        self.assertIn('plr-overlay-and-controlbar-repin', text)
        self.assertNotIn('wholeFileNotPinned', text)
        with tempfile.TemporaryDirectory(prefix='v042-retired-hunk-') as temp:
            for source in ('anything\n', BOTTOM_UPSTREAM_HUNK):
                self.assertEqual(source, readability.apply_and_record(REPO, REL, source, Path(temp)))
            self.assertEqual([], list(Path(temp).iterdir()))
        producer_text = (TOOLS / 'extract-upstream-video-player-full-controls.py').read_text(encoding='utf-8')
        bar = producer_text[producer_text.index("rel='feature/video/ui/overlay/BottomControlBar.kt'"):
                            producer_text.index("rel='feature/video/ui/overlay/TopControlBar.kt'")]
        self.assertNotIn('apply_player_readability', bar)
        self.assertEqual(1, producer_text.count('apply_player_readability(REPO,rel,t,OUTPUT)'))

    def test_producer_emits_the_scroll_line_once_from_the_whole_file_and_keeps_the_other_v042_fixes(self):
        with tempfile.TemporaryDirectory(prefix='v042-subtitle-menu-scroll-') as temp:
            out = Path(temp)
            producer().generate(REPO, out, True)
            generated = (out / 'com/android/purebilibili' / REL).read_text(encoding='utf-8')
            self.assertEqual(1, generated.count(BOTTOM_UPSTREAM_HUNK))
            proofs = out / 'v042-player-readability-proofs'
            self.assertFalse((proofs / 'BottomControlBar.kt.json').exists())
            title = json.loads((proofs / 'TopControlBar.kt.json').read_text(encoding='utf-8'))
            self.assertTrue(title['fullInputInverseRecovered'])
            self.assertNotIn('wholeFileNotPinned', title)
            bar = json.loads((out / 'v037-source-proofs' / 'BottomControlBar.kt.json').read_text(encoding='utf-8'))
            self.assertEqual(overlay.COMMIT, bar['upstreamCommit'])
            self.assertTrue(bar['exactCompleteInverse'])


if __name__ == '__main__':
    unittest.main()
