"""Real sole pager producer and the complete latest original UP preview."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location('owner_follow_pager', TOOLS / 'extract-upstream-video-fullscreen-pager.py')
pager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pager)
import v042_owner_follow as follow

SHEET = 'com/android/purebilibili/feature/video/ui/components/UpPreviewSheet.kt'
PAGER = 'com/android/purebilibili/feature/video/ui/pager/PortraitVideoPager.kt'


def inverse(text, edits):
    for edit in reversed(edits):
        at = edit['offset']
        if text[at:at + len(edit['after'])] != edit['after']:
            raise AssertionError('Changed original projection boundary: ' + edit['label'])
        text = text[:at] + edit['before'] + text[at + len(edit['after']):]
    return text


class OriginalOwnerFollowUiTest(unittest.TestCase):
    def test_entire_original_sheet_recovered_and_unknown_status_keeps_click_action(self):
        with tempfile.TemporaryDirectory(prefix='owner-follow-original-sheet-') as temp:
            out = Path(temp)
            pager.generate(REPO, out, standalone=True)
            actual = (out / SHEET).read_text(encoding='utf-8')
            proof = json.loads((out / 'UpPreviewSheet.kt.v042-owner-follow-ui-proof.json').read_bytes())
            raw = follow.fixed_sources(REPO)['UpPreviewSheet.kt']
            self.assertEqual(raw, inverse(actual, proof['adaptations']))
            self.assertEqual(proof['originalSHA256'], hashlib.sha256(raw.encode()).hexdigest())
            self.assertEqual(proof['outputSHA256'], hashlib.sha256(actual.encode()).hexdigest())
            self.assertIn('onClickLabel = if (followStatusKnown) null else "重新确认状态并关注"', actual)
            self.assertIn('text = if (!followStatusKnown) "待确认"', actual)
            self.assertNotIn('enabled = followStatusKnown', actual)
            self.assertNotIn('NetworkModule.', actual)
            self.assertEqual(3, actual.count('platform.requests.spaceApi.'))
            self.assertEqual(1, actual.count('platform.requests.api.'))

    def test_confirmed_owner_status_and_guarded_callback_preserve_existing_whole_pager(self):
        with tempfile.TemporaryDirectory(prefix='owner-follow-pager-caller-') as temp:
            out, before = Path(temp) / 'after', Path(temp) / 'before'
            pager.generate(REPO, out, standalone=True)
            with patch.object(follow, 'apply_ui', side_effect=lambda repo, path, text, output, spec: text):
                pager.generate(REPO, before, standalone=True)
            actual = (out / PAGER).read_text(encoding='utf-8')
            proof = json.loads((out / 'PortraitVideoPager.kt.v042-owner-follow-ui-proof.json').read_bytes())
            self.assertEqual((before / PAGER).read_text(encoding='utf-8'), inverse(actual, proof['edits']))
            self.assertFalse(proof['fullOriginalPagerParityClaimed'])
            self.assertIn('it.info.bvid == bvid && it.info.aid == activeAid && it.info.owner.mid == authorMid', actual)
            self.assertIn('val followStatusKnown = interactionSuccess?.followStatusKnown == true', actual)
            self.assertIn('val isFollowing = followStatusKnown && interactionSuccess?.isFollowing == true', actual)
            self.assertIn('if (canHandlePortraitInteraction && authorMid > 0L) onToggleFollow(authorMid, isFollowing)', actual)
            self.assertEqual(proof['outputSHA256'], hashlib.sha256(actual.encode()).hexdigest())


if __name__ == '__main__':
    unittest.main()
