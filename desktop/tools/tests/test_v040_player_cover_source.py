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


def module(name, relative):
    spec = importlib.util.spec_from_file_location(name, REPO / 'desktop/tools' / relative)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class V040PlayerCoverSourceTest(unittest.TestCase):
    def test_actual_sole_producer_reuses_whole_original_policies_caller_and_tests(self):
        producer = module('actual_current_cover_producer', 'extract-upstream-video-player-section-full.py')
        with tempfile.TemporaryDirectory(prefix='bp-cover-producer-') as directory:
            root = Path(directory)
            actual, baseline, tests = root / 'actual', root / 'baseline', root / 'tests'
            producer.generate(REPO, actual, standalone=True, test_output=tests)
            protocol = producer.protocol
            inputs = dict(producer.SOURCES)
            rows = list(producer.OUTPUTS)
            with patch.object(cover, 'project_members', side_effect=lambda repo, members, sources, protocol: (members, {})), \
                 patch.object(cover, 'project_caller', side_effect=lambda repo, text, sources, protocol: (text, {})):
                producer.generate(REPO, baseline, standalone=True)
            self.assertEqual({row['path'] for row in rows}, {row['path'] for row in producer.OUTPUTS})
            for row in rows:
                relative = row['path']
                emitted = (actual / relative).read_text(encoding='utf-8')
                previous = (baseline / relative).read_text(encoding='utf-8')
                restored = emitted
                if relative.endswith('/DesktopOriginalVideoPlayerSectionPolicy.kt'):
                    proof = json.loads((actual / 'v037-player-section-policy-platform-proof.json').read_bytes())
                    self.assertIsNone(proof['fixedCommit'])
                    self.assertEqual(cover.BASE_COMMIT, proof['baseFixedCommit'])
                    for change in proof['coverProjection']['declarations']:
                        self.assertEqual(cover.COMMIT, proof['fixedCommitByDeclaration'][change['name']])
                        self.assertEqual(change['after'], cover.declaration(emitted, change['name'], protocol))
                        self.assertEqual(1, restored.count(change['after']))
                        restored = restored.replace(change['after'], change['before'], 1)
                elif relative.endswith('/VideoPlayerSection.kt'):
                    current = cover.fixed_sources(REPO)['VideoPlayerSection.kt'].decode('utf-8')
                    after = cover.caller_block(current, protocol)
                    self.assertEqual(after, cover.caller_block(emitted, protocol))
                    proof = json.loads((actual / 'v040-player-cover-caller-proof.json').read_bytes())
                    change = proof['indexedEdits'][0]
                    self.assertEqual(1, restored.count(after))
                    restored = restored.replace(after, change['before'], 1)
                    self.assertEqual(2, after.count('revealMotionSpec.surfaceRevealDurationMillis'))
                    self.assertIn('platform.NativeViewport(', emitted)
                    self.assertIn('RenderPlayerForeground {', emitted)
                    self.assertTrue(proof['androidUnderlayCapabilityNotMapped'])
                self.assertEqual(previous, restored, relative)
            for leaf in ('VideoPlayerSectionPolicy.kt', 'VideoPlayerSection.kt'):
                self.assertEqual(cover.COMMIT, inputs[cover.ARCHIVE + '/' + leaf]['upstreamCommit'])
            original = cover.fixed_sources(REPO)['VideoPlayerSectionPolicyTest.kt'].decode('utf-8')
            selected = next(tests.rglob('DesktopV040OriginalPlayerCoverPolicyTest.kt')).read_text(encoding='utf-8')
            self.assertEqual(3, selected.count('@Test'))
            for name in cover.ORIGINAL_TEST_NAMES:
                self.assertEqual(cover.declaration(original, name, protocol), cover.declaration(selected, name, protocol))
            self.assertEqual(1, len(list(tests.rglob('DesktopV040OriginalPlayerCoverPolicyTest.kt'))))
            build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
            task = build.split('val extractOriginalVideoPlayerSectionFull by tasks.registering(Exec::class) {', 1)[1].split('\n}\n', 1)[0]
            self.assertIn('inputs.file("tools/v040_player_cover.py")', task)
            self.assertIn('inputs.dir("upstream-slices/v040-player-cover")', task)

    def test_tampered_manifest_or_any_fixed_source_is_rejected(self):
        for leaf in ('manifest.json', *cover.fixed_sources(REPO)):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-cover-tamper-') as directory:
                root = Path(directory)
                shutil.copytree(REPO / cover.ARCHIVE, root / cover.ARCHIVE)
                target = root / cover.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    cover.fixed_sources(root)

    def test_missing_or_changed_old_member_cannot_silently_select_another_baseline(self):
        protocol = module('cover_member_brackets', 'extract-upstream-dynamic-reply-protocol.py')
        old = (REPO / 'desktop/upstream-slices/v037-player-section-policy/VideoPlayerSectionPolicy.kt').read_text(encoding='utf-8')
        members = {name: cover.declaration(old, name, protocol) for name in cover.NAMES}
        for name in cover.NAMES:
            missing = {key: value for key, value in members.items() if key != name}
            with self.assertRaisesRegex(ValueError, 'Missing existing'):
                cover.project_members(REPO, missing, {}, protocol)
            changed = dict(members)
            changed[name] = changed[name].replace('Boolean', 'Boolean /* changed */', 1)
            with self.assertRaisesRegex(ValueError, 'declaration drift'):
                cover.project_members(REPO, changed, {}, protocol)

    def test_changed_or_duplicate_actual_caller_is_rejected(self):
        protocol = module('cover_caller_brackets', 'extract-upstream-dynamic-reply-protocol.py')
        old = (REPO / 'desktop/upstream-slices/v037-player-owner-state/VideoPlayerSection.kt').read_text(encoding='utf-8')
        block = cover.caller_block(old, protocol)
        for changed in (old.replace(block, block.replace('remember(', 'remember( /* drift */', 1), 1), old + block):
            with self.assertRaises(ValueError):
                cover.project_caller(REPO, changed, {}, protocol)


if __name__ == '__main__':
    unittest.main()
