"""Final engagement output must retain an executable inverse to its input."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[3]
TOOLS=REPO/'desktop/tools'
sys.path.insert(0,str(TOOLS))
spec=importlib.util.spec_from_file_location('engagement_receipt_producer',TOOLS/'extract-upstream-video-detail-full-units.py')
producer=importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)


class EngagementReceiptCompletionTest(unittest.TestCase):
    def test_real_final_vm_replays_and_reverses_the_complete_previous_input(self):
        with tempfile.TemporaryDirectory(prefix='engagement-receipt-') as directory:
            output=Path(directory)
            producer.generate(REPO,output,True)
            proof=json.loads((output/'v042-owner-follow-projection-proof.json').read_bytes())
            final=(output/'com/android/purebilibili/feature/video/viewmodel/VideoEngagementViewModel.kt').read_text(encoding='utf8')
            sha=lambda text:hashlib.sha256(text.encode()).hexdigest()
            self.assertEqual(sha(final),proof['outputSHA256'])
            before=final
            for edit in reversed(proof['edits']):
                at=edit['offset']
                self.assertEqual(edit['after'],before[at:at+len(edit['after'])])
                before=before[:at]+edit['before']+before[at+len(edit['after']):]
            self.assertEqual(sha(before),proof['inputSHA256'])
            replay=before
            for index,edit in enumerate(proof['edits']):
                at=edit['offset']
                self.assertEqual(edit['before'],replay[at:at+len(edit['before'])])
                replay=replay[:at]+edit['after']+replay[at+len(edit['before']):]
                if index+1==len(proof['ownerFollowStage']['edits']):
                    self.assertEqual(sha(replay),proof['ownerFollowStage']['outputSHA256'])
            self.assertEqual(final,replay)

    def test_corrupt_stage_is_rejected_before_final_receipt_is_created(self):
        proof=dict(inputSHA256=producer.sha('old'),outputSHA256=producer.sha('new'),edits=[])
        with self.assertRaisesRegex(ValueError,'stage input changed'):
            producer.finish_owner_follow_receipt(proof,'new','newer')


if __name__=='__main__':unittest.main()
