from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
SOURCE_REPO = REPO
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v030_up_danmaku as selected

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, REPO / 'desktop/tools' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

pool_tool = load('up_danmaku_pool', 'extract-upstream-danmaku-list-menu.py')
owner_tool = load('up_danmaku_owner', 'extract-upstream-video-full-owner.py')

class CompleteUpDanmakuSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bilipai-up-source-')
        cls.output = Path(cls.temp.name)
        cls.originals, cls.pins = selected.fixed_v030_up_files()
        cls.inventory = pool_tool.generate(REPO, cls.output / 'pool')
        cls.owner_inventory = owner_tool.generate(REPO, cls.output / 'owner')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def read_pool(self, relative):
        return (self.output / 'pool' / relative).read_text(encoding='utf8')

    def test_six_fixed_blobs_and_manifest_match_exact_immutable_identity(self):
        self.assertEqual(6, len(self.pins))
        self.assertTrue(all(row['commit'] == selected.COMMIT for row in self.pins))
        manifest = Path(selected.__file__).resolve().parent.parent / 'upstream-slices/v030-up-danmaku/manifest.json'
        self.assertEqual(selected.MANIFEST_SHA256, hashlib.sha256(manifest.read_bytes()).hexdigest())
        self.assertEqual('79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40', self.inventory['upstreamCommit'])
        self.assertEqual(selected.COMMIT, self.inventory['ordinaryPoolUpstreamCommit'])

    def test_each_raw_and_manifest_byte_tamper_is_rejected(self):
        with tempfile.TemporaryDirectory(prefix='bilipai-up-pin-') as temp:
            root = Path(temp) / 'slice'
            shutil.copytree(Path(selected.__file__).resolve().parent.parent / 'upstream-slices/v030-up-danmaku', root)
            for name in ['manifest.json'] + [row['file'] for row in self.pins]:
                file = root / name
                original = file.read_bytes()
                file.write_bytes(original + b' ')
                with self.assertRaisesRegex(ValueError, 'Changed fixed-v030'):
                    selected.fixed_v030_up_files(root)
                file.write_bytes(original)
            self.assertEqual(self.originals, selected.fixed_v030_up_files(root)[0])

    def test_fresh_complete_pool_inverse_preserves_original_confirmation_and_up_tag(self):
        row = next(row for row in self.inventory['emitted'] if row['path'].endswith('/DanmakuPoolSheet.kt'))
        body = self.read_pool(row['path'])
        self.assertEqual(row['sha256LF'], hashlib.sha256(body.encode()).hexdigest())
        for edit in reversed(row['adaptations']):
            self.assertEqual(1, body.count(edit['after']))
            body = body.replace(edit['after'], edit['before'])
        self.assertEqual(self.originals['DanmakuPoolSheet.kt'], body)
        self.assertIn('DanmakuSameSendConfirmation', body)
        self.assertIn('upOwnerUserHash', body)
        self.assertIn('remember(videoCid)', body)
        # Existing HotBar owns this byte-identical complete confirmation component.
        self.assertFalse(any(row['path'].endswith('/DanmakuSameSendConfirmation.kt') for row in self.inventory['emitted']))
        old = REPO / 'desktop/upstream-slices/v027-hot-danmaku/DanmakuSameSendConfirmation.kt'
        self.assertEqual(next(row['sha256'] for row in self.pins if row['file'] == old.name), hashlib.sha256(old.read_bytes()).hexdigest())

    def test_complete_neutral_model_inverse_keeps_author_field_and_copy(self):
        row = next(row for row in self.inventory['emitted'] if row['path'].endswith('/DanmakuItem.kt'))
        body = self.read_pool(row['path'])
        self.assertIn('target.isUpOwner = isUpOwner', body)
        for edit in reversed(row['adaptations']):
            self.assertEqual(1, body.count(edit['after']))
            body = body.replace(edit['after'], edit['before'])
        original = self.originals['DanmakuModels.kt']
        self.assertEqual(original[:original.index('\ndata class DanmakuWindow(')], body)

    def test_both_whole_badge_functions_and_constants_restore_fixed_original(self):
        receipt = json.loads((self.output / 'pool/v030-up-badge-adaptation.json').read_bytes())
        self.assertTrue(receipt['completeFunctionInverse'])
        self.assertEqual(2, len(receipt['functions']))
        rendered = self.read_pool('com/bilipai/desktop/danmaku/DesktopOriginalUpDanmakuBadge.kt')
        original = self.originals['TextDrawItem.kt']
        for proof in receipt['functions']:
            self.assertIn(proof['adapted'], rendered)
            restored = proof['adapted']
            for edit in reversed(proof['adaptations']):
                self.assertEqual(1, restored.count(edit['after']))
                restored = restored.replace(edit['after'], edit['before'])
            self.assertEqual(pool_tool.function(original, proof['member'])[0], restored)
        constants = original[original.index('    private companion object {'):original.index('\n    private fun getFontHeight')].rstrip()
        self.assertIn(constants, rendered)

    def test_actual_owner_last_stage_inverse_equals_full_generated_original_before_guard(self):
        row = next(row for row in self.owner_inventory if row['path'].endswith('/VideoPlaybackViewModel.kt'))
        body = (self.output / 'owner' / row['path']).read_text(encoding='utf8')
        self.assertEqual(row['sha256LF'], hashlib.sha256(body.encode()).hexdigest())
        for edit in reversed(row['postSameSendInverseEdits']):
            i = edit['offset']
            self.assertEqual(edit['after'], body[i:i+len(edit['after'])])
            body = body[:i] + edit['before'] + body[i+len(edit['after']):]
        self.assertEqual(row['postSameSendBeforeSha256LF'], hashlib.sha256(body.encode()).hexdigest())
        self.assertEqual(5, len(row['sameSendExpectedSourceInverseEdits']))
        for edit in reversed(row['sameSendExpectedSourceInverseEdits']):
            i = edit['offset']
            self.assertEqual(edit['after'], body[i:i+len(edit['after'])])
            body = body[:i] + edit['before'] + body[i+len(edit['after']):]
        self.assertEqual(row['sameSendExpectedSourceBeforeSha256LF'], hashlib.sha256(body.encode()).hexdigest())
        with tempfile.TemporaryDirectory(prefix='bilipai-up-owner-baseline-') as temp:
            with patch.object(selected, 'same_send_expected_source_delta', side_effect=lambda path, body, edits: body), \
                 patch.object(owner_tool, 'paused_original_cdn_resume_delta', side_effect=lambda path, body, audit=None: body):
                owner_tool.generate(REPO, Path(temp))
            self.assertEqual(body, (Path(temp) / row['path']).read_text(encoding='utf8'))

    def test_changed_guard_anchor_and_double_application_fail_closed(self):
        row = next(row for row in self.owner_inventory if row['path'].endswith('/VideoPlaybackViewModel.kt'))
        final = (self.output / 'owner' / row['path']).read_text(encoding='utf8')
        with self.assertRaisesRegex(ValueError, 'guard anchor'):
            selected.same_send_expected_source_delta(row['path'], final, [])
        original = final
        for field in ('postSameSendInverseEdits', 'sameSendExpectedSourceInverseEdits'):
            for edit in reversed(row[field]):
                i = edit['offset']
                self.assertEqual(edit['after'], original[i:i+len(edit['after'])])
                original = original[:i] + edit['before'] + original[i+len(edit['after']):]
            checkpoint = 'postSameSendBeforeSha256LF' if field == 'postSameSendInverseEdits' else 'sameSendExpectedSourceBeforeSha256LF'
            self.assertEqual(row[checkpoint], hashlib.sha256(original.encode()).hexdigest())
        with self.assertRaisesRegex(ValueError, 'guard anchor'):
            selected.same_send_expected_source_delta(row['path'], original.replace('attentionCommand: Boolean = false', 'attentionCommand: Boolean = true', 1), [])

    def test_real_root_pool_caller_supplies_current_cid_busy_and_expected_source_dispatch(self):
        host = (SOURCE_REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDanmakuHost.kt').read_text(encoding='utf8')
        root = (SOURCE_REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalDanmakuRoot.kt').read_text(encoding='utf8')
        shell = (SOURCE_REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf8')
        self.assertIn('videoCid=environment.cid', host)
        self.assertIn('isSending=isSending', host)
        self.assertIn('onSendSame=', host)
        self.assertRegex(root, r'onSendSame\s*:\s*\(String\)\s*->\s*Unit')
        self.assertIn('danmakuSource.nativeSource,::ownsDanmakuAuthorSource,::ownsDanmakuSource', shell)
        self.assertIn('playback.isSendingDanmaku.collectAsState()', shell)

    def test_actual_hot_same_send_uses_identical_expected_source_dispatch_outside_gate(self):
        host = (SOURCE_REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopWindowsHotDanmakuHost.kt').read_text(encoding='utf8')
        shell = (SOURCE_REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf8')
        self.assertEqual(1, host.count('onSendSame={text->'))
        action = host[host.index('onSendSame={text->'):host.index('isSending=sending', host.index('onSendSame={text->'))]
        self.assertIn('dispatchDesktopOriginalDanmakuSameSend(text,assembly.playback,lease.nativeSource,', action)
        self.assertIn('sourceCurrent,::owned,latestAdmission)', action)
        self.assertNotIn('playback.sendDanmaku(', host)
        self.assertRegex(host, r'stillOwned:\(\)->Boolean,\s+sourceCurrent:\(\)->Boolean,\s+admit:')
        self.assertIn('danmakuAssembly,::ownsDanmakuSource,::ownsDanmakuAuthorSource,', shell)

if __name__ == '__main__':
    unittest.main()
