"""Replay the real producer against its unchanged, pinned Gradle legacy read point."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
from desktop_refresh_adaptation import bind_refresh_launch


def digest(body):
    return hashlib.sha256(body.encode()).hexdigest()


def inverse(body, audit):
    if digest(body) != audit['afterSha256LF']:
        raise ValueError('Output identity mismatch')
    for edit in reversed(audit['edits']):
        offset = edit['offset']
        if body[offset:offset + len(edit['after'])] != edit['after']:
            raise ValueError('Inverse anchor mismatch')
        body = body[:offset] + edit['before'] + body[offset + len(edit['after']):]
    if digest(body) != audit['beforeSha256LF']:
        raise ValueError('Input identity mismatch')
    return body


class HomeFollowTodayProducerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('follow_today_producer', TOOLS / 'extract-upstream-home-viewmodel.py')
        cls.producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.producer)
        cls.root = Path(os.environ.get('BILIPAI_LEGACY_ROOT', REPO / '.legacy-root'))
        cls.temp = tempfile.TemporaryDirectory(prefix='home-follow-today-')
        cls.output = Path(cls.temp.name)
        cls.producer.generate(cls.root, cls.output / 'first')
        cls.producer.generate(cls.root, cls.output / 'second')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_actual_producer_replays_byte_for_byte(self):
        first = self.output / 'first'
        files = sorted(p.relative_to(first) for p in first.rglob('*') if p.is_file())
        self.assertTrue(files)
        for relative in files:
            self.assertEqual((first / relative).read_bytes(), (self.output / 'second' / relative).read_bytes(), str(relative))

    def own_stages(self):
        root = self.output / 'first'
        body = (root / 'com/android/purebilibili/feature/home/DesktopOriginalHomeViewModel.kt').read_text()
        original_launch = 'import kotlinx.coroutines.launch\n'
        refresh_launch = 'import com.bilipai.desktop.ui.desktopRefreshLaunch as launch\n'
        self.assertEqual(body.count(refresh_launch), 1)
        stage = body.replace(refresh_launch, original_launch)
        self.assertEqual(bind_refresh_launch(stage), body)
        for audit in reversed(json.loads((root / 'home-load-v029-adaptations.json').read_text())['audits']):
            stage = inverse(stage, audit)
        audits = json.loads((root / 'home-follow-today-adaptations.json').read_text())['audits']
        self.assertEqual(len(audits), 2)
        stages = []
        for audit in reversed(audits):
            stage = inverse(stage, audit)
            stages.append((stage, audit))
        return list(reversed(stages))

    def test_sequential_inverse_returns_the_exact_preexisting_prepared_vm(self):
        stages = self.own_stages()
        producer = self.producer
        spec = json.loads((TOOLS / 'extract-upstream-home-viewmodel-adaptations.json').read_text())
        source = producer.read(producer._desktop_canonical_source(self.root, spec['source']))
        self.assertEqual(digest(source), spec['sha256LF'])
        lines = source.splitlines(keepends=True)
        for edit in reversed(spec['operations']):
            start, end = edit['originalStartLine'] - 1, edit['originalEndLineExclusive'] - 1
            self.assertEqual(''.join(lines[start:end]), edit['expected'])
            lines[start:end] = edit['replacement'].splitlines(keepends=True)
        prepared = ''.join(lines)
        self.assertEqual(digest(prepared), spec['desiredSha256LF'])
        prepared = prepared.replace('private fun RecommendationResult.toTodayWatchPlan', 'internal fun RecommendationResult.toTodayWatchPlan')
        self.assertEqual(stages[0][0], producer.owned_today_watch_feedback_delta(prepared))
        for (body, audit), apply in zip(stages, (producer.owned_follow_rebase_delta, producer.owned_today_watch_build_dispatch_delta)):
            replay = []
            self.assertEqual(digest(apply(body, replay)), audit['afterSha256LF'])
            self.assertEqual(replay, [audit])

    def test_each_anchor_rejects_missing_and_duplicate_input(self):
        for (body, audit), apply in zip(self.own_stages(), (self.producer.owned_follow_rebase_delta, self.producer.owned_today_watch_build_dispatch_delta)):
            # Sequential edit anchors may be introduced by an earlier edit. The
            # original-input anchors are the producer boundary we can corrupt.
            anchors = {edit['before'] for edit in audit['edits'] if edit['before'] in body}
            self.assertGreater(len(anchors), 5)
            for anchor in anchors:
                for malformed in (body.replace(anchor, '/* missing home anchor */', 1), body + '\n' + anchor):
                    with self.subTest(anchor=anchor[:90]), self.assertRaises((ValueError, AssertionError)):
                        apply(malformed)

    def test_inverse_rejects_changed_result_or_receipt(self):
        for (body, audit), apply in zip(self.own_stages(), (self.producer.owned_follow_rebase_delta, self.producer.owned_today_watch_build_dispatch_delta)):
            result = apply(body)
            with self.assertRaises(ValueError):
                inverse(result + '\n', audit)
            changed = dict(audit, beforeSha256LF='0' * 64)
            with self.assertRaises(ValueError):
                inverse(result, changed)

    def test_actual_sdk_transition_applies_and_reverses_the_new_producer(self):
        relative = Path('home-viewmodel/com/android/purebilibili/feature/home/DesktopOriginalHomeViewModel.kt')
        patch = REPO / 'desktop/upstream-core/transition/patches' / (str(relative) + '.patch')
        source = (self.output / 'first' / Path(*relative.parts[1:])).read_bytes()
        with tempfile.TemporaryDirectory(prefix='home-sdk-transition-') as folder:
            target = Path(folder) / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(source)
            for flags in (['--check'], [], ['--reverse']):
                subprocess.run(['git', 'apply', '--no-index', *flags, str(patch)], cwd=folder, check=True, capture_output=True)
            self.assertEqual(target.read_bytes(), source)


if __name__ == '__main__':
    unittest.main()
