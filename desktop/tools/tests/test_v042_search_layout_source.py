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
import v042_search_layout as current
import v046_search_state as state_layer


def load(filename):
    spec = importlib.util.spec_from_file_location('current_search_' + filename.replace('-', '_'), REPO / 'desktop/tools' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def inverse(text, proof):
    assert hashlib.sha256(text.encode()).hexdigest() == proof['afterSha256LF']
    for edit in reversed(proof['indexedEdits']):
        at = edit['afterOffset']
        assert text[at:at + len(edit['after'])] == edit['after']
        text = text[:at] + edit['before'] + text[at + len(edit['after']):]
    assert hashlib.sha256(text.encode()).hexdigest() == proof['beforeSha256LF']
    return text


class CurrentSearchLayoutSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bilipai-current-search-layout-')
        cls.output = Path(cls.temp.name)
        cls.originals, cls.manifest = current.fixed_sources(REPO)
        cls.pages = load('extract-upstream-search-pages.py').generate(REPO, cls.output / 'main', cls.output / 'tests')
        load('extract-upstream-shared-liquid-tabs.py').generate(REPO, cls.output / 'tabs')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_complete_current_screen_and_filter_restore_every_upstream_byte(self):
        for path in (current.SCREEN, current.FILTER_SHEET):
            row = next(row for row in self.pages if row['source'] == path)
            body = Path(row['target']).read_text(encoding='utf-8')
            body = inverse(body, row['windowsTabAbiAdaptation'])
            if path == current.SCREEN:
                brand = row['windowsBrandConsumerAdaptation']
                self.assertTrue(brand['alreadyPresentInWholeOriginal'])
                self.assertEqual([], brand['edits'])
                self.assertEqual(hashlib.sha256(body.encode()).hexdigest(), brand['beforeSha256LfUtf8'])
            # The v0.4.6 search state layer follows the v0.4.2 platform stage: peel it to reach the v0.4.2 body.
            body = state_layer.restore(body, row['v046SearchStateAdvance']['retainedToCurrent'])
            proof = row['comprehensiveSearchAdaptation']
            self.assertTrue(proof['wholeOriginalSelected'])
            self.assertEqual(current.COMMIT, proof['upstreamCommit'])
            self.assertEqual(self.originals[path], inverse(body, proof['originalToPlatform']))

    def test_both_actual_shared_controls_restore_complete_current_material_and_geometry(self):
        inventory = json.loads((self.output / 'tabs/source-inventory.json').read_bytes())
        for path in current.CONTROLS:
            row = next(row for row in inventory['sources'] if row['path'] == path)
            self.assertEqual(current.COMMIT, row['sourceIdentity']['pinnedCommit'])
            body = (self.output / 'tabs' / row['output']).read_text(encoding='utf-8')
            body = body[body.index('package '):]
            self.assertEqual(self.originals[path], inverse(body, row['wholeOriginalToPlatform']))

    def test_complete_shared_hosts_use_platform_boundaries_and_original_tests_are_exact(self):
        rows = json.loads((self.output / 'main/v042-search-foundation-source-proof.json').read_bytes())
        self.assertEqual({current.SHARED_HOST, current.SEARCH_HOST}, {row['path'] for row in rows})
        for row in rows:
            body = Path(row['output']).read_text(encoding='utf-8')
            self.assertEqual(self.originals[row['path']], inverse(body, row['originalToPlatform']))
        names = {Path(path).name for path in current.PATHS if '/test/java/' in path}
        tests = sorted(leaf for leaf in (self.output / 'tests').rglob('*.kt') if leaf.name in names)
        self.assertEqual(2, len(tests))
        for leaf in tests:
            path = next(path for path in current.PATHS if path.endswith('/' + leaf.name))
            self.assertEqual(self.originals[path].encode('utf-8'), leaf.read_bytes())
        self.assertEqual(6, sum(leaf.read_text(encoding='utf-8').count('@Test') for leaf in tests))

    def test_manifest_and_raw_source_tampering_fail_before_generation(self):
        with tempfile.TemporaryDirectory(prefix='bilipai-search-layout-tamper-') as folder:
            root = Path(folder)
            shutil.copytree(REPO / current.ARCHIVE, root / current.ARCHIVE)
            leaf = root / current.ARCHIVE / 'SearchScreen.kt'
            leaf.write_bytes(leaf.read_bytes() + b'\n// changed\n')
            with self.assertRaisesRegex(ValueError, 'Modified complete v042 Search source'):
                current.fixed_sources(root)

    def test_single_existing_producers_declare_inputs_and_keep_test_sources_separate(self):
        for name in ('SearchScreen.kt', 'SearchVideoFilterSheet.kt', 'SharedCardContainerHost.kt', 'SearchResultContainerTransition.kt'):
            self.assertEqual(1, len(list((self.output / 'main').rglob(name))))
        self.assertEqual([], list((self.output / 'main').rglob('*Test.kt')))
        build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        for task in ('extractOriginalSearchPages', 'extractSharedLiquidTabs'):
            start = build.index('val ' + task + ' by tasks.registering(')
            block = build[start:build.index('\n}', start)]
            self.assertIn('inputs.file("tools/v042_search_layout.py")', block)
            self.assertIn('inputs.dir("upstream-slices/v042-search-layout")', block)


if __name__ == '__main__':
    unittest.main()
