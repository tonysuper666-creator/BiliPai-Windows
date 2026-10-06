"""Actual sole media generator, complete original source pins/inverse; no Kotlin or HTTP."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(os.environ.get('BILIPAI_TEST_SOURCE_REPO', str(Path(__file__).resolve().parents[3])))
TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO/'desktop/tools'))
sys.path.insert(0, str(TOOLS))
import v030_live_stream as live
import v030_live_recovery as recovery

class V030LiveRecoverySourceTests(unittest.TestCase):
    def media(self):
        spec=importlib.util.spec_from_file_location('actual_live_recovery_media',TOOLS/'extract-upstream-media.py')
        value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

    def test_raw_complete_blobs_and_physical_tamper_rejection(self):
        self.assertEqual(3,len(recovery.sources()))
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            for path in ['manifest.json',*recovery.PINS]:
                target=root/path;target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(live.safe(recovery.ROOT/path).read_bytes())
            path=recovery.BASE+'components/LiveStreamSourceSheet.kt';target=root/path
            original=target.read_bytes();target.write_bytes(original+b'\n')
            with patch.object(recovery,'ROOT',root):
                with self.assertRaisesRegex(ValueError,'raw bytes changed'):recovery.sources()
            target.write_bytes(original.replace(b'\r\n',b'\n') if b'\r\n' in original else original.replace(b'\n',b'\r\n'))
            with patch.object(recovery,'ROOT',root):
                with self.assertRaisesRegex(ValueError,'raw bytes changed'):recovery.sources()

    def test_manifest_cannot_repin_or_drop_original_screen(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);manifest=json.loads(live.safe(recovery.ROOT/'manifest.json').read_bytes())
            manifest['files'][0]['gitBlob']='0'*40
            (root/'manifest.json').write_text(json.dumps(manifest),encoding='utf8')
            with patch.object(recovery,'ROOT',root):
                with self.assertRaisesRegex(ValueError,'identity changed'):recovery.sources()
            manifest['files'].pop()
            (root/'manifest.json').write_text(json.dumps(manifest),encoding='utf8')
            with patch.object(recovery,'ROOT',root):
                with self.assertRaisesRegex(ValueError,'Incomplete'):recovery.sources()

    def test_sole_media_cli_output_has_complete_sheet_inverse_and_original_budget(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)/'main';tests=Path(temporary)/'tests'
            files=self.media().generate(REPO,output,tests)
            # The sole media output is 16 main files plus three complete socket
            # tests; the existing stream test is emitted separately as before.
            main_names = {
                'com/android/purebilibili/data/repository/DesktopMediaPgcPolicies.kt',
                'com/android/purebilibili/data/repository/DesktopDownloadDanmakuRepository.kt',
                'com/android/purebilibili/danmaku/parser/DesktopDanmakuMetadataParser.kt',
                'com/android/purebilibili/feature/download/DesktopOfflinePositionPolicy.kt',
                'com/android/purebilibili/data/repository/DesktopLiveHosts.kt',
                'com/android/purebilibili/core/network/socket/LiveDanmakuClient.kt',
                'com/android/purebilibili/core/network/socket/LiveDanmakuConnectionHealthPolicy.kt',
                'com/android/purebilibili/core/network/socket/DanmakuProtocol.kt',
                'com/android/purebilibili/data/repository/DesktopLivePolicies.kt',
                'com/android/purebilibili/feature/live/DesktopLiveDanmakuItem.kt',
                'com/android/purebilibili/feature/bangumi/DesktopFollowPolicies.kt',
                'com/android/purebilibili/data/repository/DesktopOriginalLiveStreamRequest.kt',
                'com/android/purebilibili/feature/live/DesktopOriginalLiveStreamPolicy.kt',
                'com/android/purebilibili/feature/live/components/LiveStreamSourceSheet.kt',
                'com/android/purebilibili/feature/live/DesktopLiveReloadBudget.kt',
                'com/android/purebilibili/feature/live/components/DesktopOriginalLiveChatImage.kt',
            }
            socket_test_names = {
                'com/android/purebilibili/core/network/socket/LiveDanmakuClientTest.kt',
                'com/android/purebilibili/core/network/socket/LiveDanmakuConnectionHealthPolicyTest.kt',
                'com/android/purebilibili/core/network/socket/DanmakuProtocolLimitsTest.kt',
            }
            # emit_media uses extended Windows paths; compare their exact same
            # physical identities without changing any generated source bytes.
            normal = lambda path: str(path).removeprefix("\\\\?\\").replace("\\", "/")
            expected = {normal(output / path) for path in main_names} | {normal(tests / path) for path in socket_test_names}
            actual = [normal(path) for path in files]
            self.assertEqual(16, len(main_names)); self.assertEqual(3, len(socket_test_names))
            self.assertEqual(expected, set(actual))
            self.assertEqual(len(expected), len(actual))
            sheet=list(output.rglob('LiveStreamSourceSheet.kt'));self.assertEqual(1,len(sheet))
            generated=sheet[0].read_text(encoding='utf8')
            proof=json.loads((output/'v030-live-recovery-source-proof.json').read_bytes())
            raw=recovery.sources()[recovery.BASE+'components/LiveStreamSourceSheet.kt']
            edits=proof['sheetCompleteInverse']['indexedEdits']
            self.assertEqual(raw,live.replay(generated,edits,True))
            self.assertEqual(generated,live.replay(raw,edits,False))
            self.assertEqual(1,len(proof['countedSheetAdaptations']))
            self.assertEqual(1,proof['countedSheetAdaptations'][0]['count'])
            self.assertIn('DesktopWindowsLiveSourceSheetBody as AppModalBottomSheet',generated)
            budget=(output/'com/android/purebilibili/feature/live/DesktopLiveReloadBudget.kt').read_text(encoding='utf8')
            raw_vm=recovery.sources()[recovery.BASE+'LivePlayerViewModel.kt']
            self.assertEqual(raw_vm,live.replay(budget,proof['budgetCompleteInverse']['indexedEdits'],True))
            self.assertIn('internal const val MAX_PLAYBACK_RELOAD_ATTEMPTS = 1',budget)
            self.assertEqual(live.selected_function(REPO,raw_vm,'tryNextUrl'),proof['originalTryNextUrl'])
            self.assertEqual(live.selected_function(REPO,raw_vm,'switchPlaybackCandidate'),proof['originalManualSwitch'])

    def test_actual_error_policy_complete_body_uses_typed_categories_and_original_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)/'main';tests=Path(temporary)/'tests'
            self.media().generate(REPO,output,tests)
            generated=(output/'com/android/purebilibili/feature/live/DesktopOriginalLiveStreamPolicy.kt').read_text(encoding='utf8')
            raw=live.fixed_sources()[live.POLICY]
            before=live.selected_function(REPO,raw,'resolveLivePlaybackErrorRecovery')
            after=live.selected_function(REPO,generated,'resolveLivePlaybackErrorRecovery')
            self.assertEqual(before.replace('errorCode: Int','errorCode: PlaybackException'),after)
            self.assertNotIn('shouldRecoverUnexpectedLiveEnd',generated)
            actual=(tests/'com/android/purebilibili/feature/live/LivePlaybackPolicyTest.kt').read_text(encoding='utf8')
            self.assertEqual(12,actual.count('@Test'))
            self.assertIn('behind live window should seek to current live edge',actual)
            self.assertIn('audio and video decoder failures should try next source',actual)

if __name__=='__main__':unittest.main()
