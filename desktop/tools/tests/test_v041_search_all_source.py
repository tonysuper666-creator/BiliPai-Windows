from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v041_search_all as source
import v042_search_layout as current
import v046_search_state as state_layer

# Independently read from the fixed upstream Git commit; verification stays offline.
UPSTREAM_BLOBS = {'core-data/src/main/java/com/android/purebilibili/data/repository/SearchRepository.kt': {'blob': '4d9ab28aaae5d6b344841f5969f602cccf333183', 'sha256': '9a669b0f7ccd36a5d9a0e878f8c8330e3b46138ab49192035d16ffd306c8fe10', 'bytes': 33438}, 'app/src/main/java/com/android/purebilibili/feature/search/SearchViewModel.kt': {'blob': 'f0ba713c6087b00e287f637456e23c6ff275b4fc', 'sha256': 'dd8e048d666c547cf26be83c0502fb109d369853b2d85c814b6dfd78a5806ef8', 'bytes': 77392}, 'app/src/main/java/com/android/purebilibili/feature/search/SearchScreen.kt': {'blob': 'aaf2b036d6b1eadc21bc387e0b90fa18e73348c9', 'sha256': 'c742e3cbc6b6902e056c17b27874a5240b0aa9ca3d535c60f53369016ebb5a0d', 'bytes': 251347}, 'core-data/src/main/java/com/android/purebilibili/data/repository/SearchLoadPolicy.kt': {'blob': '0d24d3abe2c905ebce16dc15050573c97d1304c9', 'sha256': '680976b2c41780defcebef4b1c1078e0e626f2fd6071c874e837508324e50119', 'bytes': 4111}, 'app/src/main/java/com/android/purebilibili/feature/search/SearchEmptyStatePolicy.kt': {'blob': '72fb8aa3935310c48bcf9d7b8f283864de388a1a', 'sha256': '3f42b89a3c3d1512b483ff980d3e29edbb6b2bf7c1a39e4261ae2518a2d53a0b', 'bytes': 1562}, 'core-data/src/main/java/com/android/purebilibili/core/network/ApiClient.kt': {'blob': '3e901a8fbd3991e376047d67a22decbd3a174099', 'sha256': '4beacddf7ee3b2394ce61e6e75de882df76ad5b12019ec0e27f4c9413c8543c4', 'bytes': 154196}, 'app/src/main/java/com/android/purebilibili/feature/search/SearchVideoFilterSheet.kt': {'blob': 'a53ec028ebe0ce48cd1824ec10203046175203ea', 'sha256': '490338fa9c133f7ce197a9ba3edb2f20b99ccf8cec18c92e0efe7096050f4b1e', 'bytes': 19558}}


def load(name):
    spec = importlib.util.spec_from_file_location('comprehensive_' + name.replace('-', '_'), REPO / 'desktop/tools' / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def inverse(text, proof):
    assert hashlib.sha256(text.encode()).hexdigest() == proof['afterSha256LF']
    for change in reversed(proof['indexedEdits']):
        pos, old = change['afterOffset'], change['after']
        assert text[pos:pos + len(old)] == old
        text = text[:pos] + change['before'] + text[pos + len(old):]
    assert hashlib.sha256(text.encode()).hexdigest() == proof['beforeSha256LF']
    return text


class ComprehensiveSearchSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bilipai-comprehensive-search-source-')
        cls.output = Path(cls.temp.name)
        cls.raw, cls.manifest = source.fixed_sources(REPO)
        cls.rows = load('extract-upstream-search-pages.py').generate(REPO, cls.output / 'pages')
        load('extract-search-platform.py').generate(REPO, cls.output / 'platform')
        cls.api = load('extract-upstream-api.py').generate(REPO, cls.output / 'api')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_archived_sources_match_fixed_upstream_git_blob_identities(self):
        self.assertEqual(set(UPSTREAM_BLOBS), set(source.PATHS))
        for row in self.manifest['files']:
            raw = (REPO / source.ARCHIVE / row['filename']).read_bytes()
            pinned = UPSTREAM_BLOBS[row['path']]
            actual_blob = subprocess.check_output(['git', 'hash-object', '--stdin'], input=raw, cwd=REPO).decode().strip()
            self.assertEqual(pinned['blob'], actual_blob)
            self.assertEqual(pinned['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual(pinned['bytes'], len(raw))

    def test_actual_whole_repository_and_view_model_restore_every_original_byte(self):
        for path in (source.REPOSITORY, source.VIEW_MODEL):
            row = next(row for row in self.rows if row['source'] == path)
            audit = row['comprehensiveSearchAdaptation']
            self.assertTrue(audit['wholeOriginalSelected'])
            emitted = Path(row['target']).read_text(encoding='utf-8')
            if row['v046SearchStateAdvance'] is not None:
                # The v0.4.6 search state layer sits on top of this v0.4.1 stage: peel it first.
                emitted = state_layer.restore(emitted, row['v046SearchStateAdvance']['retainedToCurrent'])
            original = inverse(emitted, audit['originalToPlatform'])
            self.assertEqual(self.raw[path], original)
            self.assertEqual(source.baseline(REPO, path), inverse(original, audit['canonicalToSelected']))

    def test_actual_screen_restores_all_adoption_and_platform_stages(self):
        row = next(row for row in self.rows if row['source'] == source.SCREEN)
        body = Path(row['target']).read_text(encoding='utf-8')
        body = inverse(body, row['windowsTabAbiAdaptation'])
        # Brand adaptation already has its own indexed whole-body source proof.
        brand = row['windowsBrandConsumerAdaptation']
        self.assertIsInstance(brand, dict)
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), brand['afterSha256LfUtf8'])
        for edit in reversed(brand['edits']):
            pos = edit['index']
            self.assertEqual(edit['after'], body[pos:pos + len(edit['after'])])
            body = body[:pos] + edit['before'] + body[pos + len(edit['after']):]
        self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), brand['beforeSha256LfUtf8'])
        # The v0.4.6 search state layer sits between the v0.4.2 platform stage and the brand stage: peel it.
        body = state_layer.restore(body, row['v046SearchStateAdvance']['retainedToCurrent'])
        audit = row['comprehensiveSearchAdaptation']
        # Verify the independently regenerated platform stage before brand remapping.
        item = next(item for item in load('extract-upstream-search-pages.py').SPEC if item['path'] == source.SCREEN)
        generated, regenerated = current.adapt_page(REPO, item, source.baseline(REPO, source.SCREEN))
        self.assertEqual(generated, body)
        self.assertEqual(audit, regenerated)
        selected = inverse(generated, audit['originalToPlatform'])
        self.assertEqual(source.baseline(REPO, source.SCREEN), inverse(selected, audit['canonicalToSelected']))
        self.assertTrue(audit['wholeOriginalSelected'])
        self.assertEqual(current.fixed_sources(REPO)[0][source.SCREEN], selected)
        self.assertIn('SearchType.ALL, SearchType.VIDEO -> {', generated)
        self.assertIn('span = { _, _ -> GridItemSpan(maxLineSpan) }', generated)
        self.assertIn('up.res.orEmpty().take(6)', generated)
        self.assertIn('val itemCount = current.searchResults.size + if (targetSearchType == SearchType.ALL)', generated)
        self.assertIn('pageResultState.totalCount == 0', generated)

    def test_actual_api_has_one_exact_complete_search_interface(self):
        text = self.api.read_text(encoding='utf-8')
        emitted = source.declaration(REPO, text, 'interface', 'SearchApi')
        self.assertEqual(source.declaration(REPO, self.raw[source.API], 'interface', 'SearchApi'), emitted)
        self.assertEqual(1, text.count('interface SearchApi {'))
        self.assertIn('SearchAllResponse', emitted)

    def test_actual_policies_are_byte_exact_with_one_existing_producer(self):
        for path in (source.LOAD_POLICY, source.EMPTY_POLICY):
            leaf = next((self.output / 'pages').rglob(Path(path).name))
            self.assertEqual(self.raw[path].encode(), leaf.read_bytes())
        build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        for path in (source.LOAD_POLICY, source.EMPTY_POLICY):
            self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("' + path + '"))'))
        for task in ('extractUpstreamApi', 'extractUpstreamSearch', 'extractOriginalSearchPages'):
            start = build.index('val ' + task + ' by tasks.registering(')
            block = build[start:build.index('\n}', start)]
            self.assertIn('inputs.file("tools/v041_search_all.py")', block)
            self.assertIn('inputs.dir("upstream-slices/v041-search-all")', block)

    def test_archive_corruption_and_canonical_changes_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix='bilipai-search-source-tamper-') as folder:
            root = Path(folder)
            shutil.copytree(REPO / source.ARCHIVE, root / source.ARCHIVE)
            leaf = root / source.ARCHIVE / 'ApiClient.kt'
            leaf.write_bytes(leaf.read_bytes() + b'\n// tampered\n')
            with self.assertRaisesRegex(ValueError, 'raw source changed'):
                source.fixed_sources(root)
        item = next(item for item in load('extract-upstream-search-pages.py').SPEC if item['path'] == source.VIEW_MODEL)
        with self.assertRaisesRegex(ValueError, 'canonical producer mismatch'):
            source.adapt_page(REPO, item, source.baseline(REPO, source.VIEW_MODEL) + '\n')

    def test_request_ownership_guards_cover_new_aggregate_success_and_filters(self):
        row = next(row for row in self.rows if row['source'] == source.REPOSITORY)
        body = Path(row['target']).read_text(encoding='utf-8')
        self.assertIn('val response = api.searchAll(params)\n            checked()', body)
        self.assertIn('run { checked(); Result.success(SearchAllResults(', body)
        self.assertIn('if (!hasVideoFilters) return searchAll(keyword, page)', body)
        vm = Path(next(row['target'] for row in self.rows if row['source'] == source.VIEW_MODEL)).read_text(encoding='utf-8')
        self.assertEqual(2, vm.count('application.assertCurrent()\n                            val nativeFiltered = aggregate.videos'))
        self.assertIn('SearchType.ALL -> searchResults.size + upResults.size + bangumiResults.size', vm)

    def test_all_grid_uses_the_complete_current_original_filter_body(self):
        sheet = next(row for row in self.rows if row['source'] == source.FILTER_SHEET)
        body = Path(sheet['target']).read_text(encoding='utf-8')
        body = inverse(body, sheet['windowsTabAbiAdaptation'])
        body = state_layer.restore(body, sheet['v046SearchStateAdvance']['retainedToCurrent'])
        audit = sheet['comprehensiveSearchAdaptation']
        selected = inverse(body, audit['originalToPlatform'])
        self.assertTrue(audit['wholeOriginalSelected'])
        self.assertEqual(current.fixed_sources(REPO)[0][source.FILTER_SHEET], selected)
        self.assertEqual(source.baseline(REPO, source.FILTER_SHEET), inverse(selected, audit['canonicalToSelected']))
        self.assertIn('showLayoutToggle: Boolean = true,', selected)
        self.assertIn('if (showLayoutToggle) {', selected)
        self.assertEqual(1, selected.count('onClick = onLayoutToggle,'))
        screen = Path(next(row['target'] for row in self.rows if row['source'] == source.SCREEN)).read_text(encoding='utf-8')
        self.assertNotIn('showLayoutToggle = state.searchType == SearchType.VIDEO,', screen)
        for name in ('onDurationSelect', 'onVideoTidChange', 'onPubTimeTypeChange', 'onCustomPubTimeRange'):
            self.assertIn(name, selected)


if __name__ == '__main__':
    unittest.main()
