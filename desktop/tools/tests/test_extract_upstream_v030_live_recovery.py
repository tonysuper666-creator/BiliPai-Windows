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
            # The sole media output is 23 main files plus three complete socket
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
                'com/android/purebilibili/data/repository/DesktopLiveVoteSnapshot.kt',
                'com/android/purebilibili/feature/live/components/LiveVotePanel.kt',
                'com/android/purebilibili/data/repository/LiveInteractionModels.kt',
                'com/android/purebilibili/feature/live/LiveRealtimeMessagePolicy.kt',
                'com/android/purebilibili/feature/live/LiveDanmakuFilterPolicy.kt',
                'com/android/purebilibili/data/repository/DesktopLiveModerationRequests.kt',
                'com/android/purebilibili/feature/live/components/DesktopOriginalLiveModerationUi.kt',
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
            self.assertEqual(23, len(main_names)); self.assertEqual(3, len(socket_test_names))
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

            # The complete vote panel and six moderation composables are named,
            # pinned source units, with the actual generated bodies inverted.
            import v034_live_moderation_ui as moderation_ui
            panel_path = 'app/src/main/java/com/android/purebilibili/feature/live/components/LiveVotePanel.kt'
            panel_raw = live.safe(REPO / 'desktop/upstream-slices/v034-live-vote' / panel_path).read_bytes()
            self.assertEqual('720707b0f2d25c6b927fa5647bf312768cd3e6b9673944dbd889b94164913e1b', live.sha(panel_raw))
            panel = (output / 'com/android/purebilibili/feature/live/components/LiveVotePanel.kt').read_text(encoding='utf8')
            self.assertEqual('ac7070225154d4f606fcdb0b95a9fa2a1693141a7f1ee130d7311c3f019e9b0b', live.sha(panel.encode()))
            old_import = 'import com.android.purebilibili.core.ui.components.AppLazyColumn as LazyColumn\n'
            new_import = 'import androidx.compose.foundation.lazy.LazyColumn\n'
            self.assertEqual(1, panel.count(new_import))
            self.assertEqual(panel_raw.decode('utf8'), panel[panel.index('package '):].replace(new_import, old_import, 1))
            ui = (output / 'com/android/purebilibili/feature/live/components/DesktopOriginalLiveModerationUi.kt').read_text(encoding='utf8')
            self.assertEqual('8fb2bcd6422108e2e833325628a5313d8a36849d33db391a160a34464747a919', live.sha(ui.encode()))
            proof = json.loads((output / 'v034-live-moderation-ui-source-proof.json').read_bytes())
            self.assertEqual('b2091b1de636e499df80af7e700796b4f52b7f92', proof['fixedCommit'])
            self.assertEqual(['LiveReportDialog', 'LiveDmBlockSheet', 'LiveRuleSection', 'LiveKeywordSection',
                              'LiveShieldUserSection', 'LiveDanmakuUserMenu'], [row['name'] for row in proof['selections']])
            expected_pins = ['f3e4db4ebabd16ad9b79d7d6d2cd38e7c77384bc783c78dbe4fb6c25a6c853b4',
                             'b7d6803bc68725a00a487535dad0fd699d32c4ffcb3012f3110fadc62c6a7b82']
            self.assertEqual(2, len(proof['completeOriginalSources']))
            original = []
            for pin, digest in zip(proof['completeOriginalSources'], expected_pins):
                raw = live.safe(REPO / pin['archive']).read_bytes()
                self.assertEqual(digest, live.sha(raw))
                original.append(raw.decode('utf8').replace('\r\n', '\n'))
            projections = list(original); inverses = []; parts = []
            for row in proof['selections']:
                source = original[row['source']][row['start']:row['end']]
                self.assertEqual(row['originalSha256LF'], live.sha(source.encode()))
                adapted = source
                for old, new in row['mappings']:
                    self.assertEqual(1, adapted.count(old)); adapted = adapted.replace(old, new, 1)
                self.assertEqual(row['adaptedSha256LF'], live.sha(adapted.encode()))
                self.assertEqual(1, ui.count(adapted))
                inverse = adapted
                for old, new in reversed(row['mappings']):
                    self.assertEqual(1, inverse.count(new)); inverse = inverse.replace(new, old, 1)
                self.assertEqual(source, inverse)
                index = row['source']; self.assertEqual(1, projections[index].count(source))
                projections[index] = projections[index].replace(source, adapted, 1)
                inverses.append((index, source, adapted)); parts.append(adapted)
            self.assertEqual(moderation_ui.IMPORTS + '\n\n'.join(parts) + '\n', ui[ui.index('package '):])
            for index, source, adapted in reversed(inverses):
                self.assertEqual(1, projections[index].count(adapted))
                projections[index] = projections[index].replace(adapted, source, 1)
            self.assertEqual(original, projections)

    def test_actual_error_policy_complete_body_uses_typed_categories_and_original_tests(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)/'main';tests=Path(temporary)/'tests'
            self.media().generate(REPO,output,tests)
            generated=(output/'com/android/purebilibili/feature/live/DesktopOriginalLiveStreamPolicy.kt').read_text(encoding='utf8')
            raw=live.fixed_sources()[live.POLICY]
            before=live.selected_function(REPO,raw,'resolveLivePlaybackErrorRecovery')
            after=live.selected_function(REPO,generated,'resolveLivePlaybackErrorRecovery')
            self.assertEqual(before.replace('errorCode: Int','errorCode: PlaybackException'),after)
            self.assertEqual(live.selected_function(REPO,raw,'shouldRecoverUnexpectedLiveEnd'),
                             live.selected_function(REPO,generated,'shouldRecoverUnexpectedLiveEnd'))
            actual=(tests/'com/android/purebilibili/feature/live/LivePlaybackPolicyTest.kt').read_text(encoding='utf8')
            self.assertEqual(12,actual.count('@Test'))
            self.assertIn('behind live window should seek to current live edge',actual)
            self.assertIn('audio and video decoder failures should try next source',actual)

if __name__=='__main__':unittest.main()
