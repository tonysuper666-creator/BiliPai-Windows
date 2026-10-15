from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v040_player_cover as cover
import v040_player_tap as tap


def module(name, relative):
    spec = importlib.util.spec_from_file_location(name, REPO / 'desktop/tools' / relative)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def retained_caller(protocol):
    original = (REPO / 'desktop/upstream-slices/v037-player-owner-state/VideoPlayerSection.kt').read_text(encoding='utf-8')
    return cover.project_caller(REPO, original, {}, protocol)[0]


class V040PlayerTapSourceTest(unittest.TestCase):
    def test_the_existing_sole_producer_connects_the_whole_current_original_detector(self):
        producer = module('actual_current_tap_producer', 'extract-upstream-video-player-section-full.py')
        with tempfile.TemporaryDirectory(prefix='bp-original-tap-producer-') as directory:
            root = Path(directory)
            actual, baseline = root / 'actual', root / 'baseline'
            producer.generate(REPO, actual, standalone=True)
            rows, identities, protocol = list(producer.OUTPUTS), dict(producer.SOURCES), producer.protocol
            raw, identity = tap.fixed_original(REPO)
            method = tap.whole_detector(raw, protocol)
            selected = (actual / tap.RELATIVE_OUTPUT).read_text(encoding='utf-8')
            self.assertTrue(selected.endswith(method))
            self.assertNotIn('import android.', selected)
            self.assertNotIn('AudioManager', selected)
            self.assertNotIn('Settings.System', selected)
            self.assertEqual(1, sum(row['path'] == tap.RELATIVE_OUTPUT for row in rows))
            self.assertEqual(tap.COMMIT, identities[identity['localPath']]['upstreamCommit'])
            with patch.object(tap, 'project_caller', side_effect=lambda repo, text, sources, protocol: (text, {})), \
                 patch.object(tap, 'emit'):
                producer.generate(REPO, baseline, standalone=True)
            baseline_paths = {row['path'] for row in producer.OUTPUTS}
            self.assertEqual(baseline_paths | {tap.RELATIVE_OUTPUT}, {row['path'] for row in rows})
            section = 'com/android/purebilibili/feature/video/ui/section/VideoPlayerSection.kt'
            for relative in baseline_paths - {section}:
                self.assertEqual((baseline / relative).read_bytes(), (actual / relative).read_bytes(), relative)
            caller = (actual / section).read_text(encoding='utf-8')
            self.assertEqual(1, caller.count('detectPlayerTapGestures('))
            self.assertNotIn('longPressSpeedEndedAtMs', caller)
            self.assertNotIn('detectTapGestures', caller)
            for token in ('platform.NativeViewport(', 'RenderPlayerForeground {', 'finishLongPressSpeedGesture('):
                self.assertEqual((baseline / section).read_text(encoding='utf-8').count(token), caller.count(token), token)
            previous = retained_caller(protocol)
            projected, proof = tap.project_caller(REPO, previous, {}, protocol)
            self.assertEqual(previous, tap.inverse(projected, proof['edits']))
            self.assertEqual(8, len(proof['edits']))
            self.assertTrue(proof['windowsForegroundAndLongPressOwnerUnchanged'])
            build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
            task = build.split('val extractOriginalVideoPlayerSectionFull by tasks.registering(Exec::class) {', 1)[1].split('\n}\n', 1)[0]
            self.assertIn('inputs.file("tools/v040_player_tap.py")', task)
            self.assertIn('inputs.dir("upstream-slices/v040-player-tap")', task)

    def test_modified_fixed_handler_and_manifest_are_rejected(self):
        for leaf in ('manifest.json', 'PlayerGestureHandler.kt'):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-original-tap-tamper-') as directory:
                root = Path(directory)
                shutil.copytree(REPO / tap.ARCHIVE, root / tap.ARCHIVE)
                path = root / tap.ARCHIVE / leaf
                path.write_bytes(path.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    tap.fixed_original(root)

    def test_missing_changed_or_duplicate_caller_boundaries_fail_closed(self):
        protocol = module('tap_caller_boundaries', 'extract-upstream-dynamic-reply-protocol.py')
        caller = retained_caller(protocol)
        for before in {row['before'] for row in tap.CALLER_CHANGES}:
            with self.subTest(before=before[:80]):
                changed = caller.replace(before, '/* changed caller boundary */', 1)
                with self.assertRaisesRegex(ValueError, 'caller'):
                    tap.project_caller(REPO, changed, {}, protocol)
                with self.assertRaisesRegex(ValueError, 'caller'):
                    tap.project_caller(REPO, caller + before, {}, protocol)

    def test_changed_or_ambiguous_original_detector_cannot_be_selected(self):
        protocol = module('tap_declaration_boundaries', 'extract-upstream-dynamic-reply-protocol.py')
        original, _ = tap.fixed_original(REPO)
        selected = tap.whole_detector(original, protocol)
        for changed in (original + selected, original.replace('val doubleTapSlopSquared =', 'val alteredDoubleTapSlopSquared =', 1)):
            with self.assertRaises(ValueError):
                tap.whole_detector(changed, protocol)


if __name__ == '__main__':
    unittest.main()
