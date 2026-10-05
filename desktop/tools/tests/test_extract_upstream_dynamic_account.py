from pathlib import Path
import json
import importlib.util, shutil, tempfile, unittest, sys
sys.dont_write_bytecode = True
REPO = next(p for p in Path(__file__).resolve().parents if (p/'desktop/tools/extract-upstream-dynamic-account.py').is_file())
spec = importlib.util.spec_from_file_location('dynamic_account_fixed', REPO/'desktop/tools/extract-upstream-dynamic-account.py')
producer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(producer)

class DynamicAccountSourceTest(unittest.TestCase):
    def test_full_main_and_two_original_tests_have_exact_identity_inverse(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); receipt=producer.generate(REPO,root/'main',root/'test')
            for name,destination in [('DynamicAccountCachePolicy.kt','main'),('DynamicAccountCachePolicyTest.kt','test')]:
                self.assertEqual((REPO/producer.SLICE/name).read_bytes(),(root/destination/producer.PACKAGE/name).read_bytes())
            self.assertEqual(2,receipt['originalTests']); self.assertEqual([],receipt['platformEdits'])
    def test_mutated_original_is_rejected_before_any_generated_output(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); source=root/'repo'/producer.SLICE
            shutil.copytree(REPO/producer.SLICE,source)
            (source/'DynamicAccountCachePolicy.kt').write_bytes(b'changed')
            with self.assertRaises(ValueError): producer.generate(root/'repo',root/'main',root/'test')
            self.assertFalse((root/'main').exists())
    def test_reference_vm_is_also_pinned_and_never_emitted(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); producer.generate(REPO,root/'main',root/'test')
            self.assertEqual(['DynamicAccountCachePolicy.kt'],[p.name for p in (root/'main').rglob('*.kt')])
            self.assertEqual(['DynamicAccountCachePolicyTest.kt'],[p.name for p in (root/'test').rglob('*.kt')])
    def test_modified_generated_output_is_preserved_and_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); producer.generate(REPO,root/'main',root/'test')
            changed=root/'main'/producer.PACKAGE/'DynamicAccountCachePolicy.kt'; changed.write_bytes(b'changed')
            with self.assertRaises(ValueError): producer.generate(REPO,root/'main',root/'test')
            self.assertEqual(b'changed',changed.read_bytes())
    def test_outputs_cannot_overwrite_fixed_sources(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); source=root/'repo'/producer.SLICE
            shutil.copytree(REPO/producer.SLICE,source)
            with self.assertRaises(ValueError): producer.generate(root/'repo',source,root/'test')
    def test_manifest_identity_cannot_be_relabelled(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); source=root/'repo'/producer.SLICE
            shutil.copytree(REPO/producer.SLICE,source)
            manifest=json.loads((source/'manifest.json').read_text())
            manifest['sources'][0]['gitBlob']='0'*40
            (source/'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaises(ValueError): producer.generate(root/'repo',root/'main',root/'test')
            self.assertFalse((root/'main').exists())
    def test_modified_test_output_is_rejected_before_main_creation(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); target=root/'test'/producer.PACKAGE/'DynamicAccountCachePolicyTest.kt'
            target.parent.mkdir(parents=True); target.write_bytes(b'changed')
            with self.assertRaises(ValueError): producer.generate(REPO,root/'main',root/'test')
            self.assertFalse((root/'main').exists()); self.assertEqual(b'changed',target.read_bytes())
    def test_generated_main_test_roots_are_disjoint(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            with self.assertRaises(ValueError): producer.generate(REPO,root/'main',root/'main'/'tests')
            self.assertFalse((root/'main').exists())

if __name__ == '__main__': unittest.main()
