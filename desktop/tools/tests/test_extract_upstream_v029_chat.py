import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
LIVE = Path(os.environ.get('BILIPAI_LIVE_ROOT') or REPO)
spec = importlib.util.spec_from_file_location('message_pages_v029', REPO / 'desktop/tools/extract-upstream-message-pages.py')
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)
source = producer.v029_chat_sources


class V029ChatSourceTest(unittest.TestCase):
    def test_actual_generator_retains_complete_original_vm_screen_with_full_inverse(self):
        with tempfile.TemporaryDirectory(prefix='bp-chat-source-') as temporary:
            out = Path(temporary)
            producer.generate(REPO, out)
            for name in ('ChatViewModel.kt', 'ChatScreen.kt'):
                generated = (out / 'com/android/purebilibili/feature/message' / name).read_text('utf8')
                proof = json.loads((out / (name + '.v029-adaptation.json')).read_text('utf8'))
                self.assertEqual(hashlib.sha256(generated.encode()).hexdigest(), proof['adaptedSha256LF'])
                restored = generated.splitlines(True)
                for row in reversed(proof['edits']):
                    self.assertEqual(''.join(restored[row['adaptedStart']:row['adaptedEnd']]), row['after'])
                    restored[row['adaptedStart']:row['adaptedEnd']] = row['before'].splitlines(True)
                self.assertEqual(source.read(REPO, name), ''.join(restored))
                self.assertNotIn('import android.', generated)
                self.assertNotIn('TokenManager.', generated)
                self.assertNotIn('LocalLifecycleOwner', generated)
            vm = (out / 'com/android/purebilibili/feature/message/ChatViewModel.kt').read_text('utf8')
            self.assertIn('messages = mergeChatMessages(current.messages, incoming)', vm)
            self.assertIn('messages = mergeChatMessages(older, it.messages)', vm)
            self.assertIn('owner.awaitRead("chat-refresh")', vm)
            self.assertIn('owner.launchMutation("chat-send") {\n            _uiState.update', vm)
            screen = (out / 'com/android/purebilibili/feature/message/ChatScreen.kt').read_text('utf8')
            self.assertIn('key = ::chatMessageKey', screen)
            self.assertIn('if (pageVisible && windowFocused)', screen)
            self.assertIn('if (inputText == sentText) inputText = ""', screen)
            self.assertIn('reverseLayout = true', screen)
            self.assertIn('uiState.loadMoreError', screen)

    def test_policy_error_ui_and_all_three_original_tests_remain_exact(self):
        with tempfile.TemporaryDirectory(prefix='bp-chat-policy-') as temporary:
            out = Path(temporary); producer.generate(REPO, out)
            self.assertEqual(source.read(REPO, 'ChatTimelinePolicy.kt'),
                (out / 'com/android/purebilibili/feature/message/ChatTimelinePolicy.kt').read_text('utf8'))
            generated = (out / 'com/android/purebilibili/feature/common/ListLoadError.kt').read_text('utf8')
            proof = json.loads((out / 'ListLoadError.kt.v029-adaptation.json').read_text('utf8'))
            self.assertEqual(hashlib.sha256(generated.encode()).hexdigest(), proof['adaptedSha256LF'])
            restored = generated.splitlines(True)
            for row in reversed(proof['edits']):
                self.assertEqual(''.join(restored[row['adaptedStart']:row['adaptedEnd']]), row['after'])
                restored[row['adaptedStart']:row['adaptedEnd']] = row['before'].splitlines(True)
            self.assertEqual(source.read(REPO, 'ListLoadError.kt'), ''.join(restored))
        relative = 'com/android/purebilibili/feature/message/ChatTimelinePolicyTest.kt'
        test = LIVE / 'app/src/test/java' / relative
        original = source.read(REPO, test.name)
        actual = test.read_text('utf8')
        pattern = r'(?ms)^    @Test\n    fun (\w+)\(\) \{.*?^    \}'
        originals = re.findall(pattern, original)
        self.assertEqual(len(originals), 3)
        self.assertEqual(len(set(originals)), 3)
        for name in originals:
            expression = r'(?ms)^    @Test\n    fun ' + re.escape(name) + r'\(\) \{.*?^    \}'
            before = re.findall(expression, original)
            after = re.findall(expression, actual)
            self.assertEqual(len(before), 1, name)
            self.assertEqual(len(after), 1, name)
            self.assertEqual(before[0], after[0], name)
        gradle = (LIVE / 'desktop/build.gradle.kts').read_text('utf8')
        areas = re.search(r'val migratedAppM4dAreas = listOf\((.*?)\)', gradle, re.S)
        self.assertIsNotNone(areas)
        self.assertIn('"feature/message/"', areas.group(1))
        self.assertIn('migratedAppM4dAreas.asSequence().flatMap { appTestRoot.resolve', gradle)
        self.assertIn('core !in appM4dTestsNotRun', gradle)
        self.assertIn('kotlin.srcDir(appTestRoot)', gradle)
        self.assertNotRegex(actual, r'(?m)^import (org\.junit\.(?!jupiter)|org\.robolectric|io\.mockk|androidx\.test)')
        for table in ('tests-not-run.tsv', 'later-steps.tsv'):
            entries = (LIVE / 'desktop/upstream-core/m4d' / table).read_text('utf8').splitlines()
            self.assertNotIn('feature/message/ChatTimelinePolicyTest.kt', [line.split('\t', 1)[0] for line in entries if not line.startswith('#')])
            self.assertNotIn('feature/message/ChatTimelinePolicy.kt', [line.split('\t', 1)[0] for line in entries if not line.startswith('#')])

    def test_raw_source_or_manifest_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='bp-chat-pin-') as temporary:
            repo = Path(temporary)
            archive = repo / 'desktop/upstream-slices/v029-chat-timeline'
            shutil.copytree(REPO / 'desktop/upstream-slices/v029-chat-timeline', archive)
            path = archive / 'ChatTimelinePolicy.kt'; path.write_bytes(path.read_bytes() + b'\n')
            with self.assertRaises(AssertionError): source.read(repo, path.name)
            manifest = archive / 'manifest.json'; data = json.loads(manifest.read_text()); data['commit'] = '0' * 40
            manifest.write_text(json.dumps(data), encoding='utf8')
            with self.assertRaises(AssertionError): source.read(repo, 'ChatViewModel.kt')


if __name__ == '__main__': unittest.main()
