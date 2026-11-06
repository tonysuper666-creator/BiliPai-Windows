"""A moved original retains a pinned feature lineage, never a synthetic row."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from v025_source_paths import registered_source_row

REPO = Path(__file__).resolve().parents[3]
OLD = 'app/src/main/java/com/android/purebilibili/feature/audio/screen/MusicTopControlDragPolicy.kt'
NEW = 'app/src/main/java/com/android/purebilibili/feature/audio/screen/AudioNowPlayingBar.kt'


class HistoricalSourceRegistrationTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        self.manifest['sources'] = [row for row in self.manifest['sources'] if row['path'] != OLD]
        self.successor = next(row for row in self.manifest['sources'] if row['path'] == NEW)

    def test_pinned_historical_row_retains_original_feature_and_digest(self):
        # The real read point has Git history, unlike an arbitrary temporary fixture.
        row = registered_source_row(REPO, self.manifest, OLD)
        self.assertEqual(OLD, row['path'])
        self.assertEqual('8eb95f92c79b22446cdedc05a67566c328e3a01df750c58abbd144816e366d07', row['sha256'])
        self.assertEqual(['stable-music-player-original'], row['features'])

    def test_missing_unregistered_or_tampered_replacement_fails_closed(self):
        for mode in ('missing', 'lineage', 'digest', 'features', 'duplicate'):
            manifest = copy.deepcopy(self.manifest)
            successor = next(row for row in manifest['sources'] if row['path'] == NEW)
            if mode == 'missing':
                manifest['sources'].remove(successor)
            elif mode == 'lineage':
                successor['replacesPaths'] = []
            elif mode == 'digest':
                successor['sha256'] = '0' * 64
            elif mode == 'features':
                successor['features'] = []
            else:
                manifest['sources'].append(copy.deepcopy(successor))
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                registered_source_row(REPO, manifest, OLD)

    def test_unpinned_archive_and_live_tree_cannot_supply_historical_rows(self):
        with mock.patch('v025_source_paths.subprocess.check_output', return_value=b'{}'):
            with self.assertRaisesRegex(ValueError, 'inventory digest'):
                registered_source_row(REPO, self.manifest, OLD)
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(ValueError, 'Missing original source registration'):
                registered_source_row(Path(temporary), self.manifest, OLD)


if __name__ == '__main__':
    unittest.main()
