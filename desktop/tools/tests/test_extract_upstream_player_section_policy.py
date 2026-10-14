from pathlib import Path
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v037_player_section_policy as policy


class FixedPlayerSectionPolicyTest(unittest.TestCase):
    def test_shared_gesture_declarations_have_one_latest_original_owner(self):
        def producer(name, filename):
            spec = importlib.util.spec_from_file_location(name, REPO / 'desktop/tools' / filename)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            return module

        detail = producer('shared_gesture_detail', 'extract-upstream-video-detail-full-units.py')
        section = producer('shared_gesture_section', 'extract-upstream-video-player-section-full.py')
        with tempfile.TemporaryDirectory(prefix='bp-gesture-owner-') as directory:
            root = Path(directory)
            detail.generate(REPO, root / 'detail')
            section.generate(REPO, root / 'section')
            relative = 'com/android/purebilibili/feature/video/ui/section/'
            motion = (root / 'detail' / relative / 'DesktopOriginalVideoGestureMotion.kt').read_text(encoding='utf-8')
            remaining = (root / 'section' / relative / 'DesktopOriginalVideoPlayerSectionPolicy.kt').read_text(encoding='utf-8')
            original, identity = policy.fixed_original(REPO, policy.RELATIVE_PATHS[0])
            expected = detail.selected(original, list(policy.GESTURE_MOTION_NAMES))
            self.assertIn(expected, motion)
            for name in policy.GESTURE_MOTION_NAMES:
                declaration = r'\b(?:class|fun)\s+' + name + r'\b'
                self.assertEqual(len(re.findall(declaration, motion)), 1, name)
                self.assertEqual(len(re.findall(declaration, remaining)), 0, name)
            record = json.loads((root / 'detail/source-bindings.json').read_bytes())
            source = next(row for row in record['sourceIdentities'] if row['path'] == identity['originalPath'])
            self.assertEqual(source['upstreamCommit'], policy.COMMIT)
            self.assertEqual(source['gitBlob'], identity['gitBlob'])

    def test_existing_producer_reuses_latest_complete_load_policy_and_motion_bodies(self):
        path = REPO / 'desktop/tools/extract-upstream-video-player-section-full.py'
        spec = importlib.util.spec_from_file_location('latest_player_policy_producer', path)
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        with tempfile.TemporaryDirectory(prefix='bp-player-policies-') as directory:
            output = Path(directory)
            producer.generate(REPO, output)
            load, identity = policy.fixed_original(REPO, policy.RELATIVE_PATHS[1])
            target = output / 'com/android/purebilibili/feature/video/ui/section/VideoPlayerDanmakuLoadPolicy.kt'
            self.assertEqual(target.read_text(encoding='utf-8'), load)
            self.assertIn('KeepCurrent', load)
            self.assertIn('shouldKeepVideoPlayerDanmakuHost', load)
            row = next(item for item in producer.OUTPUTS if item['path'].endswith('/VideoPlayerDanmakuLoadPolicy.kt'))
            self.assertTrue(row['generated'])
            self.assertEqual(row['source']['gitBlob'], identity['gitBlob'])
            manifest = json.loads((REPO / 'desktop/upstream-sources.json').read_bytes())
            old = next(item for item in manifest['sources'] if item['path'] == identity['originalPath'])
            self.assertEqual(old['mode'], 'selected')
            proof = json.loads((output / 'v037-player-section-policy-platform-proof.json').read_bytes())
            self.assertTrue(proof['exactCompleteInverse'])
            selected = output / 'com/android/purebilibili/feature/video/ui/section/DesktopOriginalVideoPlayerSectionPolicy.kt'
            emitted = selected.read_text(encoding='utf-8')
            self.assertEqual(emitted.count('enum class GestureLevelIconStyle {'), 1)
            self.assertEqual(emitted.count('private fun GestureLevelIconStyle.toOverlayStyle():'), 1)
            restored = emitted
            for change in reversed(proof['indexedEdits']):
                start = change['afterOffset']
                self.assertEqual(restored[start:start + len(change['after'])], change['after'])
                restored = restored[:start] + change['before'] + restored[start + len(change['after']):]
            self.assertIn('reducedMotion: Boolean = false', restored)
            self.assertIn('surfaceRevealDurationMillis = if (reducedMotion) 80', restored)
            self.assertTrue(set(producer.POLICY_NAMES).issubset(proof['originalDeclarationNames']))
            self.assertFalse(proof['sourceQualified'])

    def test_changed_original_policy_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='bp-player-policy-pins-') as directory:
            repo = Path(directory)
            shutil.copytree(REPO / policy.ARCHIVE, repo / policy.ARCHIVE)
            path = repo / policy.ARCHIVE / 'VideoPlayerDanmakuLoadPolicy.kt'
            path.write_bytes(path.read_bytes() + b'\n// changed\n')
            with self.assertRaisesRegex(AssertionError, 'Fixed player policy bytes changed'):
                policy.fixed_original(repo, policy.RELATIVE_PATHS[1])

    def test_unclaimed_source_does_not_fall_back_to_another_baseline(self):
        with self.assertRaises(AssertionError):
            policy.fixed_original(REPO, 'feature/video/ui/section/UnknownPolicy.kt')


if __name__ == '__main__':
    unittest.main()
