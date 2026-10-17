"""Real existing component/appearance/tab owners advance authenticated originals."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v043_theme_chrome as chrome
import v042_seed_palette as seed


def load(filename):
    spec = importlib.util.spec_from_file_location('theme_chrome_'+filename.replace('-', '_'),
        REPO/'desktop/tools'/filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def body(path):
    text = path.read_text(encoding='utf8')
    return '\n'.join(text.splitlines()[2:])+'\n' if text.startswith('// GENERATED from ') else text


class OriginalThemeChromeSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bilipai-v043-theme-chrome-')
        cls.root = Path(cls.temp.name)
        cls.raw, cls.manifest = chrome.fixed_sources(REPO)
        cls.components = load('extract-upstream-components.py')
        cls.appearance = load('extract-appearance-platform.py')
        cls.tabs = load('extract-upstream-dynamic-tabs.py')
        cls.components.generate(REPO, cls.root/'components', test_output=cls.root/'tests')
        cls.appearance.generate(REPO, cls.root/'appearance', cls.root/'resources', True, cls.root/'tests')
        cls.tabs.generate(REPO, cls.root/'tabs')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def leaf(self, name):
        files = [p for owner in ('components', 'appearance', 'tabs') for p in (self.root/owner).rglob(name)]
        self.assertEqual(1, len(files), name)
        return files[0]

    def test_complete_changed_controls_endpoint_and_renderer_have_one_existing_owner(self):
        for name in ('ButtonVisualPolicy.kt', 'AppPrimitiveComponents.kt', 'AppMiuixSwitch.kt',
                     'AppMaterial3SegmentedControl.kt', 'AppThemeEndpointPolicy.kt'):
            self.assertEqual(self.raw[name].decode(), body(self.leaf(name)), name)
        # The real MD3 caller passes measured labels to all four native rows.
        renderer = body(self.leaf('AppMaterial3SegmentedControl.kt'))
        self.assertEqual(4, renderer.count('labelContentWidths = labelContentWidths'))
        self.assertLess(renderer.index('if (indicatorPresentation == AppTabRowIndicatorPresentation.TONAL_PILL)'),
                        renderer.index('val textMeasurer = rememberTextMeasurer()'))

    def test_selected_native_miuix_theme_keeps_the_complete_original_declaration(self):
        parser = load('sync-upstream.py')
        actual = body(self.leaf('Theme.kt'))
        for name in ('resolveNativeMiuixColors', 'resolveMiuixColorsFromMaterialBridge'):
            selected = self.appearance.declarations(parser, self.raw['Theme.kt'].decode(), [name])
            self.assertIn(selected.rstrip(), actual)
        proof = json.loads((self.root/'appearance/v042-seed-palette-proof.json').read_bytes())
        current_names = set(chrome.SEED_REPLACEMENTS + chrome.NEW_SEED_TESTS)
        for row in proof['writes'] + proof['wholeOriginalTestProofs']:
            name = row.get('source', row.get('filename'))
            self.assertEqual(chrome.COMMIT if name in current_names else seed.COMMIT, row['upstreamCommit'])
            self.assertEqual(chrome.MANIFEST_SHA256 if name in current_names else seed.MANIFEST_SHA256,
                             row['sourceManifestSHA256'])

    def test_all_six_original_test_classes_keep_whole_bodies_and_only_existing_store_import_adapter(self):
        count = 0
        for row in self.manifest['files']:
            if row['cases'] is None:
                continue
            targets = list((self.root/'tests').rglob(row['filename']))
            self.assertEqual(1, len(targets), row['filename'])
            actual = targets[0].read_text(encoding='utf8')
            if row['filename'] == 'AppThemeEndpointPolicyTest.kt':
                old = 'import com.android.purebilibili.core.store.AppThemeSettings'
                new = 'import com.bilipai.desktop.appearance.DesktopThemeSettings as AppThemeSettings'
                self.assertEqual(1, actual.count(new))
                actual = actual.replace(new, old, 1)
            self.assertEqual(self.raw[row['filename']].decode(), actual)
            count += row['cases']
        self.assertEqual(33, count)

    def test_each_selected_predecessor_is_required_and_independently_reversible(self):
        import v040_chrome_actions as old_chrome
        import v040_liquid_value_controls as old_liquid
        import v042_search_dependencies as old_tabs
        before = seed.fixed_sources(REPO)
        host = self.components.helper(REPO)
        for path in chrome.COMPONENTS:
            text = old_chrome.selected_component_source(REPO, path,
                old_liquid.selected_component_source(REPO, path, host.read(REPO, path)))
            before[Path(path).name] = text.encode()
        before['AppMaterial3SegmentedControl.kt'] = old_tabs.fixed_sources(REPO)[0][chrome.NATIVE_RENDERER]
        for path, raw in old_chrome.fixed_sources(REPO).items():
            if Path(path).name in chrome.COMPONENT_TESTS:
                before[Path(path).name] = raw
        for name, reference in self.manifest['reviewedPredecessors'].items():
            original = before[name]
            self.assertEqual(reference['predecessorSHA256'], hashlib.sha256(original).hexdigest())
            self.assertEqual(self.raw[name], chrome.advance(REPO, name, original))
            # Reconstruct predecessor solely from authenticated diff ranges.
            restored = self.raw[name].splitlines(keepends=True)
            prior = original.splitlines(keepends=True)
            for _, i, j, k, l, _, _ in reversed(reference['hunks']):
                restored[k:l] = prior[i:j]
            self.assertEqual(original, b''.join(restored))
            with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                chrome.advance(REPO, name, original+b'\n')

    def test_tampered_raw_manifest_and_extra_leaf_are_rejected(self):
        for kind in ('raw', 'manifest', 'extra'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                repo = Path(temp)
                archive = repo/chrome.ARCHIVE
                shutil.copytree(REPO/chrome.ARCHIVE, archive)
                target = archive/('manifest.json' if kind == 'manifest' else
                    'extra.kt' if kind == 'extra' else 'AppPrimitiveComponents.kt')
                target.write_bytes((target.read_bytes() if target.exists() else b'')+b'\n')
                with self.assertRaises(ValueError):
                    chrome.fixed_sources(repo)

    def test_real_entrypoints_reject_archive_output_before_writing(self):
        before = {p.name:p.read_bytes() for p in (REPO/chrome.ARCHIVE).iterdir()}
        for owner in (self.components, self.appearance):
            for target in (REPO/chrome.ARCHIVE, REPO/chrome.ARCHIVE/'nested'):
                with self.subTest(owner=owner.__name__, target=target), self.assertRaisesRegex(ValueError, 'overlaps'):
                    owner.generate(REPO, target)
        self.assertEqual(before, {p.name:p.read_bytes() for p in (REPO/chrome.ARCHIVE).iterdir()})

    def test_existing_producers_are_deterministic_with_no_duplicate_original_tests(self):
        snapshot = {p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.components.generate(REPO, self.root/'components', test_output=self.root/'tests')
        self.appearance.generate(REPO, self.root/'appearance', self.root/'resources', True, self.root/'tests')
        self.tabs.generate(REPO, self.root/'tabs')
        self.assertEqual(snapshot,
            {p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_all_three_actual_owners_track_new_inputs_and_sync_tracks_every_raw_original(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        for task in ('extractUpstreamAppearance', 'extractUpstreamComponents', 'extractUpstreamDynamicTabs'):
            at = build.index('val '+task+' by tasks.registering(')
            block = build[at:build.index('\n}', at)]
            self.assertIn('inputs.file("tools/v043_theme_chrome.py")', block)
            self.assertIn('inputs.dir("upstream-slices/v043-theme-chrome")', block)
        sync = load('sync-upstream.py')
        entries = sync.adapted_compatibility_inputs(REPO)
        current = [row for row in entries if row['localPath'].startswith(chrome.ARCHIVE+'/')]
        self.assertEqual(12, len(current))
        for row in current:
            self.assertEqual(chrome.COMMIT, row['upstreamCommit'])
            self.assertFalse(row['referenceOnly'])
            self.assertEqual(row['sha256'], hashlib.sha256((REPO/row['localPath']).read_bytes()).hexdigest())


if __name__ == '__main__':
    unittest.main()
