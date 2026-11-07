import hashlib
import importlib.util
import json
from pathlib import Path
import re
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

    def test_real_desktop_chat_consumer_applies_ime_once_to_shared_list_and_composer_parent(self):
        with tempfile.TemporaryDirectory(prefix='bp-chat-ime-') as temporary:
            out = Path(temporary)
            original = source.read(REPO, 'ChatScreen.kt')
            def function(text, name):
                match = re.search(r'(?m)^private fun ' + re.escape(name) + r'\(\): Dp \{\n.*?^\}', text, re.S)
                if match is None:
                    raise AssertionError(f'missing producer function: {name}')
                return match.group(0)
            screen = source.adapt_screen(original, function)
            source.record_adaptation(REPO, out, 'ChatScreen.kt', screen)
            proof = json.loads((out / 'ChatScreen.kt.v029-adaptation.json').read_text('utf8'))
            self.assertEqual(hashlib.sha256(screen.encode()).hexdigest(), proof['adaptedSha256LF'])
            restored = screen.splitlines(True)
            for row in reversed(proof['edits']):
                self.assertEqual(''.join(restored[row['adaptedStart']:row['adaptedEnd']]), row['after'])
                restored[row['adaptedStart']:row['adaptedEnd']] = row['before'].splitlines(True)
            self.assertEqual(original, ''.join(restored))

            self.assertEqual(screen.count('.desktopChatImePadding()'), 1)
            self.assertIn('import com.bilipai.desktop.ui.desktopChatImePadding', screen)
            self.assertNotIn('.imePadding()', screen)
            self.assertNotIn('.desktopChatImePadding()', screen[screen.index('fun ChatInputBar('):])

    # The desktop copy of the three original ChatTimelinePolicyTest cases was retired in 1c5558950 (M4d): the
    # upstream app/src/test feature/message/ChatTimelinePolicyTest.kt superset runs in the desktop test set.
    def test_policy_and_error_ui_remain_exact(self):
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
