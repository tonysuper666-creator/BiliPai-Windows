"""Real sole member producer and exact full-source favorite mount contracts."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parents[1]
UI = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui'
SHELL = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt'
sys.path.insert(0, str(TOOLS))
LABEL = 'Confirmed original protocol callback submits only its originating Windows brand receipt'
def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module
producer = load('brand_success_actual_video_members', TOOLS / 'extract-upstream-video-detail-full-units.py')
def read(path): return producer.wide(path).read_bytes().decode().replace('\r\n', '\n')
def sha(text): return hashlib.sha256(text.encode()).hexdigest()


class BrandSuccessMountContractTest(unittest.TestCase):
    def test_actual_members_pass_the_existing_unmodified_exact_member_gate(self):
        with tempfile.TemporaryDirectory() as temp:
            generated = Path(temp) / 'generated'
            producer.generate(REPO, generated, True)
            verifier = load('brand_success_existing_member_gate', REPO / 'desktop/tools/verify-upstream-video-detail-full-units.py')
            verifier.verify(REPO, generated, Path(temp) / 'report.json')
            proof = json.loads(read(generated / 'brand-success-operations-proof.json'))
            final = read(generated / 'video-operations-members.fragment')
            self.assertTrue(proof['fullInverseExact'])
            self.assertEqual(sha(final), proof['afterSha256LF'])
            self.assertEqual(1, final.count('DesktopOriginalVideoEngagementPresentation.confirmBrandFollow(change.isFollowing)'))
            self.assertIn('repository.followStateEvents.confirm(checkNotNull(owner),change)', final)

    def test_business_bridge_has_one_inverse_and_changes_no_original_vm_or_protocol(self):
        with tempfile.TemporaryDirectory() as temp:
            final_dir, baseline_dir = Path(temp) / 'final', Path(temp) / 'before-brand-owner-bridge'
            producer.generate(REPO, final_dir, True)
            proof = json.loads(read(final_dir / 'brand-success-operations-proof.json'))
            self.assertEqual(1, len(proof['edits']))
            inverse = read(final_dir / 'video-operations-members.fragment')
            for edit in reversed(proof['edits']):
                self.assertEqual(1, inverse.count(edit['after']))
                inverse = inverse.replace(edit['after'], edit['before'], 1)
            original_adapt = producer.adapt
            def without_brand(text, before, after, label):
                return text if label == LABEL else original_adapt(text, before, after, label)
            with patch.object(producer, 'adapt', side_effect=without_brand):
                producer.generate(REPO, baseline_dir, True)
            self.assertEqual(read(baseline_dir / 'video-operations-members.fragment'), inverse)
            self.assertEqual(proof['beforeSha256LF'], sha(inverse))
            for relative in ('com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt',
                             'com/android/purebilibili/data/repository/DesktopOriginalVideoEngagementProtocol.kt'):
                self.assertEqual(producer.wide(baseline_dir / relative).read_bytes(), producer.wide(final_dir / relative).read_bytes())

    def test_favorite_actual_scope_keys_the_full_captured_publication(self):
        root, shell = read(UI / 'DesktopVideoFavoriteRoot.kt'), read(SHELL)
        self.assertEqual(1, root.count('sourceOwner: DesktopOriginalVideoAcceptedPublication?,'))
        self.assertEqual(1, root.count('key(aid, epoch, sourceOwner)'))
        self.assertNotIn('key(aid, epoch) {', root)
        self.assertGreater(root.index('val latestOwned by rememberUpdatedState(stillOwned)'), root.index('key(aid, epoch, sourceOwner)'))
        self.assertIn('alive.set(false); session.close(); scope.cancel()', root)
        begin = shell.index('favorite = { owner, success, current ->')
        end = shell.index('overlay = {', begin)
        self.assertIn('val expected = owner.native.current()', shell[begin:end])
        self.assertEqual(1, shell[begin:end].count('sourceOwner = expected'))
        self.assertIn('owner.native.isCurrent(expected)', shell[begin:end])


if __name__ == '__main__': unittest.main()
