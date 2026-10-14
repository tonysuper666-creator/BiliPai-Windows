from pathlib import Path
import hashlib
import importlib.util
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v040_theme_roles as source
from v025_source_paths import canonical_source


def producer():
    spec = importlib.util.spec_from_file_location('actual_theme_role_producer',
        REPO/'desktop/tools/extract-appearance-platform.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class V040ThemeRolesSourceTest(unittest.TestCase):
    def test_only_the_two_known_white_fixtures_are_adapted_and_unreviewed_fixtures_fail_closed(self):
        sources = source.fixed_sources(REPO)
        path = next(p for p in sources if p.endswith('/ThemeRoleOverridePolicyTest.kt'))
        raw = sources[path]
        selected, proof = source.selected_test_source(path, raw)
        self.assertEqual(9, raw.count(b'@Test'))
        self.assertEqual(9, selected.count(b'@Test'))
        expected = ['assertEquals(Color.White, result.onPrimary)', 'assertEquals(Color.White, light.onPrimary)']
        changes = [e for e in proof['edits'] if e['before'].startswith('assertEquals')]
        self.assertEqual(expected, [e['before'] for e in changes])
        self.assertEqual(4, len(proof['edits']))
        for before in expected:
            self.assertIn(before.encode(), raw)
            with self.assertRaises(ValueError):
                source.selected_test_source(path, raw.replace(before.encode(), b'assertEquals(Color.Black, light.onPrimary)'))
        for main in source.MAIN_PATHS:
            self.assertEqual(sources[main], source.selected_source(REPO, main))

    def test_complete_original_policies_and_tests_reach_the_existing_sole_producer(self):
        fixed = source.fixed_sources(REPO)
        before = {p: canonical_source(REPO,p).read_bytes() for p in source.MAIN_PATHS}
        with tempfile.TemporaryDirectory(prefix='bilipai-theme-role-original-') as tmp:
            output = Path(tmp)/'main'
            tests = Path(tmp)/'tests'
            producer().generate(REPO, output, resource_output=Path(tmp)/'resources',
                policy_only=True, test_output=tests)
            actual_cases = 0
            for path, raw in fixed.items():
                is_test = '/test/java/' in path
                relative = path.split('/test/java/' if is_test else '/main/java/', 1)[1]
                target = (tests if is_test else output)/relative
                selected, proof = source.selected_test_source(path, raw) if is_test else (raw, None)
                self.assertEqual(selected, target.read_bytes(), path)
                if proof is not None:
                    restored = selected.decode('utf-8')
                    for edit in reversed(proof['edits']):
                        at, after = edit['offset'], edit['after']
                        self.assertEqual(after, restored[at:at + len(after)])
                        restored = restored[:at] + edit['before'] + restored[at + len(after):]
                    self.assertEqual(raw.decode('utf-8'), restored)
                    self.assertFalse(proof['productSourceChanged'])
                self.assertEqual(1, len(list((tests if is_test else output).rglob(target.name))))
                if is_test:
                    actual_cases += raw.count(b'@Test')
            self.assertEqual(17, actual_cases)
            build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf-8')
            for path in source.MAIN_PATHS:
                self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("'+path+'"))'))
            self.assertIn('kotlin.srcDir(generatedThemeRoleTests)', build)
            self.assertIn('"--test-output", generatedThemeRoleTests.get().asFile.absolutePath', build)
        self.assertEqual(before, {p: canonical_source(REPO,p).read_bytes() for p in source.MAIN_PATHS})

    def test_other_actual_generated_appearance_units_remain_byte_identical(self):
        # Actual sole-producer outputs recorded before this overlay, at source107 d19a2ab.
        retained = {
            'com/android/purebilibili/core/store/SettingsManager.kt': 'd123106ccd9fd6815c91dc94bc174711104b7867410c0537dc252d7ec98b172e',
            'com/android/purebilibili/core/store/ThemeSelectionStore.kt': '7e2da3ba1380d33b9dc2c71334eb12d51e65693fa501492739241c9fc2767e4d',
            'com/android/purebilibili/core/theme/Theme.kt': '44e92c9598f88e643032a6f11c17f18f81dd76956cf5c79c89cf6696d97019e6',
            'com/android/purebilibili/core/ui/components/AppSegmentedControl.kt': '66b25d5a700818066af16dedf6b77dd5e298441e3d79eae43f8509587273fb91',
            'com/android/purebilibili/feature/settings/AppearanceThemeSegmentPolicy.kt': 'c72db21aeced2f89174c701624803d1a03feb3cde5ab3d86478fdb166697f475',
        }
        with tempfile.TemporaryDirectory(prefix='bilipai-theme-role-owner-') as tmp:
            output = Path(tmp)/'main'
            producer().generate(REPO,output,resource_output=Path(tmp)/'resources',policy_only=True)
            self.assertEqual(set(retained)|{p.split('/main/java/',1)[1] for p in source.MAIN_PATHS},
                {p.relative_to(output).as_posix() for p in output.rglob('*.kt')})
            for path,digest in retained.items():
                self.assertEqual(digest,hashlib.sha256((output/path).read_bytes()).hexdigest(),path)

    def test_changed_original_archives_and_canonical_input_are_rejected(self):
        for leaf in ('manifest.json',*(r['archiveFile'] for r in source.PINS)):
            with self.subTest(leaf=leaf),tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                shutil.copytree(REPO/source.ARCHIVE,root/source.ARCHIVE)
                path=root/source.ARCHIVE/leaf
                path.write_bytes(path.read_bytes()+b'\n')
                with self.assertRaises(ValueError): source.fixed_sources(root)
        path=source.MAIN_PATHS[0]
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            original=canonical_source(REPO,path)
            target=root/original.relative_to(REPO)
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(original.read_bytes()+b'\n')
            with self.assertRaisesRegex(ValueError,'Canonical original source digest mismatch'):
                source.selected_source(root,path)


if __name__ == '__main__': unittest.main()
