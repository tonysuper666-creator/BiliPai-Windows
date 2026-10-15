from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v041_feed_images as source
from v025_source_paths import canonical_source


class V041FeedImagesSourceTest(unittest.TestCase):
    def producer(self):
        spec = importlib.util.spec_from_file_location('v041_actual_feed_producer',
            REPO / 'desktop/tools/extract-upstream-plugins.py')
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        return producer

    def test_actual_main_and_test_producers_keep_exact_originals_without_duplicate_sources(self):
        producer = self.producer()
        fixed = source.verified_sources(REPO)
        manifest = json.loads((REPO / 'desktop/upstream-sources.json').read_bytes())
        declared = {row['path']: row['mode'] for row in manifest['sources']}
        with tempfile.TemporaryDirectory(prefix='bilipai-v041-feed-source-') as tmp:
            root = Path(tmp); main = root / 'main'; tests = root / 'tests'
            files = producer.generate(REPO, main)
            self.assertEqual(len(files), len(set(files)))
            self.assertEqual(sorted(files), sorted(producer.generate(REPO, main)))
            for path in source.MARKERS:
                with self.subTest(path=path):
                    baseline = producer.read(REPO, path)
                    selected, proof = source.compose_feed_source(REPO, path, baseline)
                    matches = list(main.rglob(Path(path).name))
                    self.assertEqual(1, len(matches))
                    header, sha_header, emitted = matches[0].read_text(encoding='utf-8').split('\n', 2)
                    self.assertIn(path, header)
                    self.assertIn(hashlib.sha256(baseline.encode()).hexdigest(), sha_header)
                    self.assertEqual(selected.strip() + '\n', emitted)
                    self.assertEqual(fixed[path].strip() + '\n', emitted)
                    self.assertTrue(proof['baselineRemainderUnchanged'])
                    self.assertTrue(proof['wholeFunctionRetained'])
                    self.assertNotIn(path, producer.DIRECT)
                    self.assertEqual('extracted', declared[path])
            test_files = producer.generate_feed_tests(REPO, tests)
            self.assertEqual(test_files, producer.generate_feed_tests(REPO, tests))
            self.assertEqual(1, len(test_files))
            from v041_feed_store import complete_original_feed_regression_source
            selected_tests, _ = complete_original_feed_regression_source(REPO)
            self.assertEqual(selected_tests.encode('utf-8'), test_files[0].read_bytes())
            self.assertEqual(19, test_files[0].read_text(encoding='utf-8').count('@Test'))
            self.assertEqual([], list(main.rglob('*Test.kt')))
            self.assertEqual([], list(tests.rglob('DynamicAuthorSearchPolicyTest.kt')))

    def test_manifest_each_original_leaf_and_both_canonical_function_changes_fail_closed(self):
        baselines = {path: canonical_source(REPO, path).read_text(encoding='utf-8') for path in source.MARKERS}
        for path, marker in source.MARKERS.items():
            baseline = baselines[path]
            before = source.whole_function(baseline, marker)
            changed = baseline.replace(before, before.replace('{', '{ /* changed baseline */', 1), 1)
            with self.assertRaisesRegex(ValueError, 'Canonical feed function changed'):
                source.compose_feed_source(REPO, path, changed)
        leaves = ['manifest.json'] + [row['archiveFile'] for row in source.FILES]
        for leaf in leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory() as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / source.ARCHIVE, repo / source.ARCHIVE)
                file = repo / source.ARCHIVE / leaf
                file.write_bytes(file.read_bytes() + b'\n')
                with self.assertRaises(ValueError): source.verified_sources(repo)

    def test_seventeen_complete_cases_restore_all_nineteen_original_cases_exactly(self):
        body, proof = source.feed_image_regression_source(REPO)
        path = 'app/src/test/java/com/android/purebilibili/core/plugin/feed/FeedDocumentParserTest.kt'
        original = source.original_regression_sources(REPO)[path]
        self.assertEqual(17, body.count('@Test'))
        self.assertEqual(19, original.count('@Test'))
        self.assertEqual(1, body.count('class FeedDocumentParserTest'))
        for edit in reversed(proof['edits']):
            self.assertEqual('', edit['after'])
            self.assertEqual(1, edit['before'].count('@Test'))
            self.assertIn('fun `' + edit['originalCase'] + '`()', edit['before'])
            self.assertTrue(edit['before'].rstrip().endswith('}'))
            at = edit['offset']; body = body[:at] + edit['before'] + body[at:]
        self.assertEqual(original, body)
        self.assertTrue(proof['completeOriginalInverse'])
        self.assertTrue(proof['remainingOriginalBytesUnchanged'])
        self.assertEqual(2, proof['deferredOriginalTests'])
        self.assertFalse(proof['jvmTestsExecuted'])
        self.assertFalse(proof['runtimeExecuted'])


if __name__ == '__main__': unittest.main()
