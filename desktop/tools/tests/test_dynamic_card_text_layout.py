"""Fail closed when the upstream producer's layout anchors drift."""
import importlib.util
from pathlib import Path
import sys
import unittest

DESKTOP = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(DESKTOP / 'tools'))
spec = importlib.util.spec_from_file_location('dynamic_card_layout_producer', DESKTOP / 'tools/extract-upstream-dynamic-card.py')
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)
from v035_dynamic_preview import prepare_latest_card


class DynamicCardLayoutAnchors(unittest.TestCase):
    def test_originals_restore_exactly_and_missing_or_duplicate_anchors_fail(self):
        for filename, kind in [('DynamicCard.kt', 'card'), ('ForwardedContent.kt', 'forward')]:
            original = (DESKTOP / 'upstream-slices/v035-dynamic-link' / filename).read_text()
            if kind == 'card':
                original = prepare_latest_card(original)
                anchor = 'private const val DYNAMIC_FEED_TEXT_FOLD_THRESHOLD = 108\n'
            else:
                anchor = '                    collapseWebLinks = collapseWebLinks,\n                    desc = desc,'
            proof = {}
            producer.adapt_feed_text_layout(original, kind, proof)
            self.assertEqual(proof[kind]['inputSha256'], proof[kind]['inverseSha256'])
            with self.assertRaisesRegex(ValueError, 'anchor count 0'):
                producer.adapt_feed_text_layout(original.replace(anchor, '', 1), kind)
            with self.assertRaisesRegex(ValueError, 'anchor count 2'):
                producer.adapt_feed_text_layout(original + anchor, kind)


if __name__ == '__main__':
    unittest.main()
