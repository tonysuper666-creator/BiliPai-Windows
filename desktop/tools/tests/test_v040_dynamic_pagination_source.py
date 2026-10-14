from pathlib import Path
import importlib.util
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v040_dynamic_pagination as source
from v025_source_paths import canonical_source


class V040DynamicPaginationSourceTest(unittest.TestCase):
    def baseline(self):
        return canonical_source(REPO, source.ORIGIN).read_text(encoding='utf-8')

    def test_whole_fixed_decision_is_retained_and_the_other_existing_policies_are_unchanged(self):
        baseline = self.baseline()
        original = (REPO / source.ARCHIVE / 'DynamicScreenStatePolicy.kt').read_text(encoding='utf-8')
        body, proof = source.compose_policy(REPO, baseline)
        old = source.complete_function(baseline)
        latest = source.complete_function(original)
        self.assertEqual(latest, source.complete_function(body))
        self.assertEqual(baseline.replace(old, '', 1), body.replace(latest, '', 1))
        self.assertTrue(proof['wholeFunctionRetained'])
        self.assertFalse(proof['actualWindowsCallerVerified'])

    def test_the_actual_sole_tabs_producer_emits_the_fixed_decision(self):
        spec = importlib.util.spec_from_file_location('actual_dynamic_tabs_v040',
            REPO / 'desktop/tools/extract-upstream-dynamic-tabs.py')
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        with tempfile.TemporaryDirectory(prefix='bilipai-v040-tabs-source-') as tmp:
            output = Path(tmp)
            producer.generate(REPO, output, standalone=True)
            emitted = output / 'com/android/purebilibili/feature/dynamic/DesktopOriginalDynamicUserStatePolicy.kt'
            original = (REPO / source.ARCHIVE / 'DynamicScreenStatePolicy.kt').read_text(encoding='utf-8')
            self.assertEqual(source.complete_function(original), source.complete_function(emitted.read_text(encoding='utf-8')))
            self.assertTrue((output / 'v040-original-dynamic-pagination-proof.json').is_file())

    def test_changed_source_manifest_or_existing_decision_is_rejected(self):
        baseline = self.baseline()
        old = source.complete_function(baseline)
        changed = baseline.replace(old, old.replace('allowAutomaticLoadMore', 'changedAdmission'), 1)
        with self.assertRaises(ValueError): source.compose_policy(REPO, changed)
        for leaf in ('manifest.json', 'DynamicScreenStatePolicy.kt'):
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                shutil.copytree(REPO / source.ARCHIVE, root / source.ARCHIVE)
                path = root / source.ARCHIVE / leaf
                path.write_bytes(path.read_bytes() + b'\n')
                with self.assertRaises(ValueError): source.compose_policy(root, baseline)


if __name__ == '__main__': unittest.main()
