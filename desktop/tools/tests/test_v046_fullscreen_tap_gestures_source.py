"""Pinned v0.4.6 fullscreen tap swap on the generated overlay. No Gradle."""
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
import v046_fullscreen_tap_gestures as gestures

REL = gestures.OUTPUT_RELATIVE


def producer():
    spec = importlib.util.spec_from_file_location(
        'fullscreen_tap_pager', TOOLS / 'extract-upstream-video-fullscreen-pager.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def commit_present(commit):
    done = subprocess.run(
        ['git', '-C', str(REPO), 'cat-file', '-e', commit + '^{commit}'], capture_output=True)
    return done.returncode == 0


class FullscreenTapGesturesSourceTest(unittest.TestCase):
    def test_upstream_pins_match_their_sha256(self):
        self.assertEqual(
            gestures.UPSTREAM_IMPORT_LINE_SHA256,
            hashlib.sha256(gestures.UPSTREAM_IMPORT_LINE.encode()).hexdigest())
        self.assertEqual(
            gestures.UPSTREAM_CALL_CONTEXT_SHA256,
            hashlib.sha256(gestures.UPSTREAM_CALL_CONTEXT.encode()).hexdigest())
        self.assertEqual('d6bf8b1655ae910999860bf13e65c8fcea5b345b', gestures.UPSTREAM_COMMIT)
        self.assertEqual(
            'app/src/main/java/com/android/purebilibili/feature/video/ui/overlay/FullscreenPlayerOverlay.kt',
            gestures.UPSTREAM_PATH)
        self.assertEqual('80fc2e9a492463fc4c7dc3ec9f04e0da3f0c9b39', gestures.UPSTREAM_BLOB)
        self.assertEqual(
            gestures.UPSTREAM_CALL_CONTEXT,
            gestures.CALL_BEFORE.replace('detectTapGestures(', 'detectPlayerTapGestures(', 1))
        gestures.assert_pins()

    @unittest.skipUnless(
        commit_present(gestures.UPSTREAM_COMMIT),
        'local git is missing v0.4.6 d6bf8b1655ae910999860bf13e65c8fcea5b345b; '
        'git show of FullscreenPlayerOverlay.kt was not checked')
    def test_upstream_commit_contains_the_pinned_lines_at_the_pinned_blob(self):
        shown = subprocess.check_output(
            ['git', '-C', str(REPO), 'show', gestures.UPSTREAM_COMMIT + ':' + gestures.UPSTREAM_PATH],
            text=True)
        self.assertEqual(1, shown.count(gestures.UPSTREAM_IMPORT_LINE))
        self.assertEqual(1, shown.count(gestures.UPSTREAM_CALL_CONTEXT))
        self.assertEqual(1, shown.count('import androidx.compose.foundation.gestures.detectTapGestures\n'))
        self.assertEqual(1, shown.count('detectTapGestures { showRatioMenu = false }'))
        names = gestures.call_parameter_names(shown, shown.index(gestures.UPSTREAM_CALL_CONTEXT))
        self.assertEqual(['onTap', 'onDoubleTap'], names)
        blob = subprocess.check_output(
            ['git', '-C', str(REPO), 'rev-parse', gestures.UPSTREAM_COMMIT + ':' + gestures.UPSTREAM_PATH],
            text=True).strip()
        self.assertEqual(gestures.UPSTREAM_BLOB, blob)

    def test_producer_swaps_the_main_call_once_and_rejects_a_second_or_moved_anchor(self):
        with tempfile.TemporaryDirectory(prefix='v046-fullscreen-tap-') as temp:
            out = Path(temp)
            producer().generate(REPO, out, True)
            generated_path = out / REL
            generated = generated_path.read_text(encoding='utf-8')
            self.assertEqual(1, generated.count('detectPlayerTapGestures('))
            self.assertEqual(1, generated.count('detectTapGestures { showRatioMenu = false }'))
            self.assertEqual(1, generated.count(gestures.UPSTREAM_IMPORT_LINE))
            self.assertEqual(1, generated.count(gestures.IMPORT_BEFORE))
            self.assertEqual(0, generated.count(gestures.CALL_BEFORE))
            proof = json.loads(
                (out / 'v046-fullscreen-tap-gestures-proofs' / 'FullscreenPlayerOverlay.kt.json').read_text(encoding='utf-8'))
            self.assertTrue(proof['fullInputInverseRecovered'])
            self.assertEqual(gestures.UPSTREAM_COMMIT, proof['upstreamCommit'])
            self.assertEqual(gestures.UPSTREAM_PATH, proof['sourcePath'])
            self.assertEqual(gestures.UPSTREAM_BLOB, proof['upstreamBlob'])
            self.assertEqual(gestures.UPSTREAM_IMPORT_LINE_SHA256, proof['upstreamImportLineSHA256'])
            self.assertEqual(gestures.UPSTREAM_CALL_CONTEXT_SHA256, proof['upstreamCallContextSHA256'])
            self.assertEqual(2, len(proof['edits']))
            self.assertEqual([1, 1], [edit['count'] for edit in proof['edits']])
            self.assertEqual(proof['outputSHA256'], hashlib.sha256(generated.encode()).hexdigest())
            restored = generated
            for edit in reversed(proof['edits']):
                at = edit['offset']
                self.assertEqual(edit['after'], restored[at:at + len(edit['after'])])
                restored = restored[:at] + edit['before'] + restored[at + len(edit['after']):]
            self.assertEqual(proof['inputSHA256'], hashlib.sha256(restored.encode()).hexdigest())
            with self.assertRaises(ValueError):
                gestures.apply_and_record(REPO, REL, generated, out / 'second')
            anchor = gestures.CALL_BEFORE
            self.assertEqual(1, restored.count(anchor))
            mutated = restored.replace(anchor, anchor.replace('onTap = {', 'onTapMoved = {'), 1)
            with self.assertRaises(ValueError):
                gestures.apply_and_record(REPO, REL, mutated, out / 'mutated')


if __name__ == '__main__':
    unittest.main()
