"""Actual original seed/endpoint producer closure, tests and immutable inputs."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO/'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v042_seed_palette as palette
import v043_theme_chrome as chrome


def producer():
    spec = importlib.util.spec_from_file_location('actual_v042_seed_appearance', TOOLS/'extract-appearance-platform.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class OriginalSeedPaletteSourceTest(unittest.TestCase):
    def test_complete_original_policies_tests_and_licenses_are_emitted_by_the_existing_producer(self):
        fixed = palette.fixed_sources(REPO)
        selected = dict(fixed)
        current = chrome.fixed_sources(REPO)[0]
        for name in chrome.SEED_REPLACEMENTS + chrome.NEW_SEED_TESTS:
            selected[name] = current[name]
        with tempfile.TemporaryDirectory(prefix='v042-original-seed-closure-') as temp:
            out, tests, resources = Path(temp)/'main', Path(temp)/'tests', Path(temp)/'resources'
            actual = producer()
            actual.generate(REPO, out, resources, policy_only=True, test_output=tests)
            proof = json.loads((out/'v042-seed-palette-proof.json').read_bytes())
            for row in proof['writes']:
                raw = (out/row['path']).read_bytes()
                self.assertEqual(row['afterSHA256'], hashlib.sha256(raw).hexdigest())
                if row['mode'] == 'whole-original':
                    self.assertEqual(selected[row['source']], raw)
            self.assertEqual(46, proof['originalTestCaseCount'])
            self.assertEqual(10, len(proof['wholeOriginalTestProofs']))
            for row in proof['wholeOriginalTestProofs']:
                expected_imports = {
                    'SeedColorSchemeTest.kt': (
                        'import kotlin.test.assertEquals',
                        'import com.bilipai.desktop.appearance.assertDesktopPaletteEquals as assertEquals'),
                    'AppThemeEndpointPolicyTest.kt': (
                        'import com.android.purebilibili.core.store.AppThemeSettings',
                        'import com.bilipai.desktop.appearance.DesktopThemeSettings as AppThemeSettings'),
                }
                expected = expected_imports.get(row['filename'])
                self.assertEqual([] if expected is None else [expected],
                    [(edit['before'], edit['after']) for edit in row['edits']])
                targets = list(tests.rglob(row['filename']))
                self.assertEqual(1, len(targets))
                text = targets[0].read_text(encoding='utf-8')
                self.assertEqual(row['outputSHA256'], hashlib.sha256(text.encode()).hexdigest())
                for edit in reversed(row['edits']):
                    at = edit['offset']
                    self.assertEqual(edit['after'], text[at:at + len(edit['after'])])
                    text = text[:at] + edit['before'] + text[at + len(edit['after']):]
                self.assertEqual(selected[row['filename']], text.encode())
                self.assertTrue(row['wholeOriginalInverseRecovered'])
            for name in palette.LICENSES:
                self.assertEqual(fixed[name], (resources/'META-INF/licenses'/name).read_bytes())
            first = {p.relative_to(out).as_posix(): p.read_bytes() for p in out.rglob('*') if p.is_file()}
            actual.generate(REPO, out, resources, policy_only=True, test_output=tests)
            self.assertEqual(first, {p.relative_to(out).as_posix(): p.read_bytes() for p in out.rglob('*') if p.is_file()})

    def test_prior_owner_outputs_are_bound_and_new_selected_declarations_are_exact_originals(self):
        fixed = palette.fixed_sources(REPO)
        with tempfile.TemporaryDirectory(prefix='v042-seed-owner-boundary-') as temp:
            out = Path(temp)/'main'
            actual = producer()
            actual.generate(REPO, out, Path(temp)/'resources', policy_only=True)
            proof = json.loads((out/'v042-seed-palette-proof.json').read_bytes())
            self.assertEqual(len(proof['writes']), len({r['path'] for r in proof['writes']}))
            for row in proof['writes']:
                if row['before'] is not None:
                    self.assertEqual(row['beforeSHA256'], hashlib.sha256(row['before'].encode()).hexdigest())
            spec = importlib.util.spec_from_file_location('v042_seed_tokens', TOOLS/'sync-upstream.py')
            parser = importlib.util.module_from_spec(spec); spec.loader.exec_module(parser)
            selected = actual.declarations(parser, fixed['AppSegmentedControl.kt'].decode(), ['AppSegmentOption'])
            self.assertIn(selected, (out/'com/android/purebilibili/core/ui/components/AppSegmentedControl.kt').read_text(encoding='utf-8'))
            selected = actual.declarations(parser, fixed['PlaybackNotificationColorPolicy.kt'].decode(), ['resolvePlaybackNotificationAccentColor'])
            target = out/'com/android/purebilibili/feature/video/player/DesktopOriginalPlaybackNotificationAccent.kt'
            self.assertIn(selected, target.read_text(encoding='utf-8'))
            self.assertIn('DesktopThemeSettings as AppThemeSettings', target.read_text(encoding='utf-8'))
            self.assertFalse(proof['AndroidContextOrDataStoreStubAdded'])
            self.assertFalse(proof['systemWallpaperExtractionImplemented'])

    def test_changed_original_and_rewritten_manifest_are_rejected(self):
        for mode in ['original', 'repinned-manifest']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(prefix='v042-seed-raw-tamper-') as temp:
                root = Path(temp)
                target = root/palette.ARCHIVE
                shutil.copytree(REPO/palette.ARCHIVE, target)
                file = target/'SeedColorScheme.kt'
                file.write_bytes(file.read_bytes() + b'\n// changed\n')
                if mode == 'repinned-manifest':
                    manifest = json.loads((target/'manifest.json').read_bytes())
                    row = next(r for r in manifest['files'] if r['filename'] == file.name)
                    row['sha256Bytes'] = hashlib.sha256(file.read_bytes()).hexdigest()
                    (target/'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
                with self.assertRaises(ValueError):
                    palette.fixed_sources(root)


if __name__ == '__main__':
    unittest.main()
