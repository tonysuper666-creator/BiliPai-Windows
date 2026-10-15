from pathlib import Path
import hashlib
import importlib.util
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v041_feed_store as source
from v025_source_paths import canonical_source


class V041FeedStoreSourceTest(unittest.TestCase):
    def module(self, name, leaf):
        spec = importlib.util.spec_from_file_location(name, REPO / 'desktop/tools' / leaf)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_both_real_producers_emit_each_complete_original_once_and_inverse_every_edit(self):
        plugin = self.module('v041_complete_actual_plugin', 'extract-upstream-plugins.py')
        js = self.module('v041_complete_actual_js', 'extract-upstream-js.py')
        fixed = source.verified_sources(REPO)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); main = root / 'main'
            plugin_files = plugin.generate(REPO, main)
            js_files = js.generate(REPO, main)
            self.assertEqual(plugin_files, plugin.generate(REPO, main))
            self.assertEqual(js_files, js.generate(REPO, main))
            self.assertEqual(len(plugin_files + js_files), len(set(plugin_files + js_files)))
            for path, original in fixed.items():
                with self.subTest(path=path):
                    baseline = canonical_source(REPO, path).read_text(encoding='utf-8')
                    emitted, proof = source.compose_source(REPO, path, baseline)
                    restored = emitted
                    for edit in reversed(proof['adaptations']):
                        at = edit['offset']; self.assertEqual(edit['after'], restored[at:at + len(edit['after'])])
                        restored = restored[:at] + edit['before'] + restored[at + len(edit['after']):]
                    self.assertEqual(original, restored)
                    name = 'DesktopSavedSubscriptionFeed.kt' if path.endswith('/SubscriptionFeedStore.kt') else Path(path).name
                    matches = list(main.rglob(name)); self.assertEqual(1, len(matches))
                    actual = matches[0].read_text(encoding='utf-8').split('\n', 2)[2]
                    self.assertEqual(emitted.strip() + '\n', actual)
                    self.assertTrue(proof['allRemainingOriginalBytesUnchanged'])

    def test_all_five_canonical_source_changes_fail_closed(self):
        for path in source.verified_sources(REPO):
            with self.subTest(path=path):
                baseline = canonical_source(REPO, path).read_text(encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'Canonical v025 feed source changed'):
                    source.compose_source(REPO, path, baseline + '\n// changed canonical source\n')

    def test_manifest_and_all_five_original_archive_leaves_reject_changed_or_missing_bytes(self):
        for leaf in ['manifest.json', *source.BASE_SHA256_LF]:
            for changed in [True, False]:
                with self.subTest(leaf=leaf, changed=changed), tempfile.TemporaryDirectory() as tmp:
                    repo = Path(tmp)
                    shutil.copytree(REPO / source.ARCHIVE, repo / source.ARCHIVE)
                    path = repo / source.ARCHIVE / leaf
                    if changed: path.write_bytes(path.read_bytes() + b'\n')
                    else: path.unlink()
                    with self.assertRaises((ValueError, FileNotFoundError)):
                        source.verified_sources(repo)

    def test_all_nineteen_original_cases_restore_exactly_with_one_folder_expectation_correction(self):
        from v041_feed_images import original_regression_sources
        body, proof = source.complete_original_feed_regression_source(REPO)
        original = original_regression_sources(REPO)[proof['source']]
        self.assertEqual(19, body.count('@Test'))
        self.assertEqual(0, proof['deferredOriginalTests'])
        self.assertEqual(1, len(proof['edits']))
        edit = proof['edits'][0]; at = edit['offset']
        self.assertEqual(edit['after'], body[at:at + len(edit['after'])])
        self.assertEqual(original, body[:at] + edit['before'] + body[at + len(edit['after']):])
        self.assertTrue(proof['originalRawArchivePreserved'])
        self.assertFalse(proof['jvmTestsExecuted'])


if __name__ == '__main__': unittest.main()
