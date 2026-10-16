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

    def test_archived_sources_are_exact_upstream_git_objects(self):
        for row in self.manifest['files']:
            raw = subprocess.check_output(['git', 'show', source.COMMIT + ':' + row['path']], cwd=REPO)
            self.assertEqual(raw, (REPO / source.ARCHIVE / row['filename']).read_bytes())

    def test_actual_whole_repository_and_view_model_restore_every_original_byte(self):
        for path in (source.REPOSITORY, source.VIEW_MODEL):
            row = next(row for row in self.rows if row['source'] == path)
            audit = row['comprehensiveSearchAdaptation']
            self.assertTrue(audit['wholeOriginalSelected'])
            emitted = Path(row['target']).read_text(encoding='utf-8')
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
        audit = row['comprehensiveSearchAdaptation']
        # Verify the independently regenerated platform stage before brand remapping.
        item = next(item for item in load('extract-upstream-search-pages.py').SPEC if item['path'] == source.SCREEN)
        generated, regenerated = source.adapt_page(REPO, item, source.baseline(REPO, source.SCREEN))
        self.assertEqual(generated, body)
        self.assertEqual(audit, regenerated)
        selected = inverse(generated, audit['originalToPlatform'])
        self.assertEqual(source.baseline(REPO, source.SCREEN), inverse(selected, audit['canonicalToSelected']))
        self.assertIn('SearchType.ALL -> {', generated)
        self.assertIn('SearchAllRelatedVideoCard(video = video)', generated)
        self.assertIn('up.res.orEmpty().take(6)', generated)
        self.assertIn('current.searchResults.size + current.upResults.size + current.bangumiResults.size', generated)

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

    def test_all_hides_only_the_grid_toggle_and_preserves_the_complete_filter_body(self):
        sheet = next(row for row in self.rows if row['source'] == source.FILTER_SHEET)
        body = Path(sheet['target']).read_text(encoding='utf-8')
        body = inverse(body, sheet['windowsTabAbiAdaptation'])
        audit = sheet['comprehensiveSearchAdaptation']
        selected = inverse(body, audit['originalToPlatform'])
        self.assertFalse(audit['wholeOriginalSelected'])
        self.assertEqual(source.baseline(REPO, source.FILTER_SHEET), inverse(selected, audit['canonicalToSelected']))
        self.assertIn('showLayoutToggle: Boolean = true,', selected)
        self.assertIn('if (showLayoutToggle) {\n            BottomBarMatchedReusableLiquidDock(', selected)
        self.assertEqual(1, selected.count('onClick = onLayoutToggle,'))
        screen = Path(next(row['target'] for row in self.rows if row['source'] == source.SCREEN)).read_text(encoding='utf-8')
        self.assertIn('showLayoutToggle = state.searchType == SearchType.VIDEO,', screen)
        for name in ('onDurationSelect', 'onVideoTidChange', 'onPubTimeTypeChange', 'onCustomPubTimeRange'):
            self.assertIn(name, selected)


if __name__ == '__main__':
    unittest.main()
