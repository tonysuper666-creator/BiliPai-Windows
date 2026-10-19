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
def restore_indexed(body, edits, key='afterOffset'):
    for row in reversed(edits):
        index = row[key]
        assert body[index:index + len(row['after'])] == row['after']
        body = body[:index] + row['before'] + body[index + len(row['after']):]
    return body


class BrandSuccessMountContractTest(unittest.TestCase):
    def unwind_v037_interaction_root(self, directory, body):
        # 75522724c extend_same_producer rewrites the fragment after the brand proof.
        proof_path = directory / 'v037-original-interaction-root-operations-proof.json'
        self.assertTrue(proof_path.is_file())
        interaction = json.loads(read(proof_path))
        self.assertTrue(interaction['exactCompleteInverse'])
        self.assertEqual(sha(body), interaction['afterSha256LF'])
        body = restore_indexed(body, interaction['indexedEdits'])
        self.assertEqual(interaction['beforeSha256LF'], sha(body))
        return body

    def assert_final_outputs_keep_mapped_brand_follow(self, generated, on_disk_fragment):
        # v037_player_interaction.py:62-63 maps each selected
        # BrandSuccessEvents.followChanged(follow) onto confirmBrandFollow(follow).
        # Gradle compiles the protocol under the producer output and reads the fragment beside it.
        self.assertEqual(0, on_disk_fragment.count('confirmBrandFollow(change.isFollowing)'))
        self.assertNotIn('BrandSuccessEvents.followChanged', on_disk_fragment)
        relative = 'com/android/purebilibili/data/repository/DesktopOriginalVideoEngagementProtocol.kt'
        protocol = read(generated / relative)
        readback = json.loads(read(generated / 'v037-detail-complete-output-readback.json'))
        row = next(item for item in readback['outputs'] if item['path'] == relative)
        self.assertEqual(row['sha256LF'], sha(protocol))
        protocol_proof = json.loads(read(generated / 'v037-original-interaction-protocol-proof.json'))
        from v037_player_interaction import fixed_sources
        from v042_follow_confirmation import apply as apply_follow_confirmation
        raw = fixed_sources()['ActionRepository.kt']
        projected, _follow_proof = apply_follow_confirmation(REPO, raw, producer.selected)
        start = projected.index('object ActionRepository {') + len('object ActionRepository {')
        interior = projected[start:projected.rfind('}')]
        original_methods = producer.selected(interior, protocol_proof['originalDeclarations'])
        self.assertEqual(protocol_proof['beforeSha256LF'], sha(original_methods))
        needle = 'com.android.purebilibili.core.events.BrandSuccessEvents.followChanged(follow)'
        mapped = 'DesktopOriginalVideoEngagementPresentation.confirmBrandFollow(follow)'
        expected = original_methods.count(needle)
        self.assertGreater(expected, 0)
        methods = original_methods
        for edit in reversed(protocol_proof['indexedEdits']):
            at = edit['beforeOffset']
            self.assertEqual(edit['before'], methods[at:at + len(edit['before'])])
            methods = methods[:at] + edit['after'] + methods[at + len(edit['before']):]
        self.assertEqual(protocol_proof['afterSha256LF'], sha(methods))
        self.assertEqual(expected, methods.count(mapped))
        self.assertNotIn('BrandSuccessEvents.followChanged', methods)
        self.assertEqual(1, protocol.count(methods))
        self.assertEqual(expected, protocol.count(mapped))
        for path in generated.rglob('*.kt'):
            self.assertNotIn('BrandSuccessEvents.followChanged', read(path))

    def test_actual_members_pass_the_existing_unmodified_exact_member_gate(self):
        with tempfile.TemporaryDirectory() as temp:
            generated = Path(temp) / 'generated'
            producer.generate(REPO, generated, True)
            verifier = load('brand_success_existing_member_gate', REPO / 'desktop/tools/verify-upstream-video-detail-full-units.py')
            verifier.verify(REPO, generated, Path(temp) / 'report.json')
            proof = json.loads(read(generated / 'brand-success-operations-proof.json'))
            on_disk = read(generated / 'video-operations-members.fragment')
            final = self.unwind_v037_interaction_root(generated, on_disk)
            self.assertTrue(proof['fullInverseExact'])
            self.assertEqual(sha(final), proof['afterSha256LF'])
            self.assertEqual(1, final.count('DesktopOriginalVideoEngagementPresentation.confirmBrandFollow(change.isFollowing)'))
            self.assertIn('repository.followStateEvents.confirm(checkNotNull(owner),change)', final)
            self.assert_final_outputs_keep_mapped_brand_follow(generated, on_disk)

    def test_business_bridge_has_one_inverse_and_changes_no_original_vm_or_protocol(self):
        with tempfile.TemporaryDirectory() as temp:
            final_dir, baseline_dir = Path(temp) / 'final', Path(temp) / 'before-brand-owner-bridge'
            producer.generate(REPO, final_dir, True)
            proof = json.loads(read(final_dir / 'brand-success-operations-proof.json'))
            self.assertEqual(1, len(proof['edits']))
            on_disk = read(final_dir / 'video-operations-members.fragment')
            self.assert_final_outputs_keep_mapped_brand_follow(final_dir, on_disk)
            inverse = self.unwind_v037_interaction_root(final_dir, on_disk)
            for edit in reversed(proof['edits']):
                self.assertEqual(1, inverse.count(edit['after']))
                inverse = inverse.replace(edit['after'], edit['before'], 1)
            original_adapt = producer.adapt
            def without_brand(text, before, after, label):
                return text if label == LABEL else original_adapt(text, before, after, label)
            with patch.object(producer, 'adapt', side_effect=without_brand):
                producer.generate(REPO, baseline_dir, True)
            baseline = self.unwind_v037_interaction_root(baseline_dir, read(baseline_dir / 'video-operations-members.fragment'))
            self.assertEqual(baseline, inverse)
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
