import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
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
            for name, folder in (('ChatTimelinePolicy.kt', 'message'), ('ListLoadError.kt', 'common')):
                self.assertEqual(source.read(REPO, name), (out / 'com/android/purebilibili/feature' / folder / name).read_text('utf8'))
        test = REPO / 'desktop/src/test/kotlin/com/android/purebilibili/feature/message/ChatTimelinePolicyTest.kt'
        self.assertEqual(source.read(REPO, test.name), test.read_text('utf8'))
        self.assertEqual(3, test.read_text('utf8').count('@Test'))

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
