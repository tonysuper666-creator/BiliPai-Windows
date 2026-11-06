from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(REPO/'desktop/tools'))
import v043_message_link_host as source
import v043_navigation as navigation


class V043MessageLinkHostSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.before=(REPO/'app/src/main/java/com/android/purebilibili/navigation/MessageLinkNavigationPolicy.kt').read_bytes().replace(b'\r\n',b'\n')
        spec=importlib.util.spec_from_file_location('message_host_existing_space_producer',REPO/'desktop/tools/extract-space-platform.py')
        cls.producer=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.producer)

    def temporary_repo(self):
        temporary=tempfile.TemporaryDirectory(prefix='bp-message-host-input-')
        self.addCleanup(temporary.cleanup)
        root=Path(temporary.name)
        shutil.copytree(REPO/source.ARCHIVE,root/source.ARCHIVE)
        return root

    def test_retained_policy_matches_the_complete_existing_original(self):
        files=source.verified_raw(REPO)
        self.assertEqual(self.before,files['MessageLinkNavigationPolicy.kt'])
        self.assertEqual('3208a12aa98ecc40dfd560dc444bbfadab982cc3c33f6eb8cc18adec3467e2b1',hashlib.sha256(self.before).hexdigest())
        self.assertEqual('9aa4a140f2f8ef04daa366edb5559397e39a832c3cbf58fb026244b1e0277301',source.MANIFEST_SHA256)

    def test_only_the_h5_host_condition_and_its_import_are_changed(self):
        files,proof=source.original_policy(REPO,self.before)
        self.assertEqual(1,len(files))
        data=next(iter(files.values()))
        restored=data.replace(source.HOST_AFTER,source.HOST_BEFORE,1).replace(source.IMPORT_AFTER,source.IMPORT_BEFORE,1)
        self.assertEqual(self.before,restored)
        self.assertEqual(1,data.count(source.HOST_AFTER))
        self.assertNotIn(source.HOST_BEFORE,data)
        self.assertTrue(proof['wholeOriginal'] and proof['completeOriginalInverse'] and proof['originalRecursionUnchanged'])

    def test_all_seventeen_original_message_tests_are_unchanged(self):
        tests=source.original_tests(REPO)
        self.assertEqual(['com/android/purebilibili/navigation/MessageLinkNavigationPolicyTest.kt'],list(tests))
        raw=next(iter(tests.values()))
        self.assertEqual((REPO/source.ARCHIVE/'MessageLinkNavigationPolicyTest.kt').read_bytes(),raw)
        self.assertEqual(17,raw.count(b'    @Test\n'))

    def test_an_unreviewed_predecessor_change_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'predecessor'):
            source.original_policy(REPO,self.before+b'\n')

    def test_changed_original_policy_is_rejected(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'MessageLinkNavigationPolicy.kt'
        p.write_bytes(p.read_bytes().replace(b'host.contains',b'host.endsWith',1))
        with self.assertRaisesRegex(ValueError,'original bytes changed'):
            source.original_policy(root,self.before)

    def test_changed_original_test_is_rejected(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'MessageLinkNavigationPolicyTest.kt'
        p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError,'original bytes changed'):
            source.original_tests(root)

    def test_manifest_tampering_is_rejected(self):
        root=self.temporary_repo();p=root/source.ARCHIVE/'manifest.json';p.write_bytes(p.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'manifest pin'):
            source.original_policy(root,self.before)

    def test_unlisted_archive_files_are_rejected(self):
        root=self.temporary_repo();(root/source.ARCHIVE/'extra.kt').write_bytes(b'')
        with self.assertRaisesRegex(ValueError,'archive leaves'):
            source.original_policy(root,self.before)

    def test_existing_producer_emits_both_whole_parser_and_message_graphs(self):
        with tempfile.TemporaryDirectory(prefix='bp-message-host-output-')as temporary:
            out=Path(temporary)/'main';tests=Path(temporary)/'tests'
            self.producer.generate(REPO,out,True,tests)
            files,proof=source.original_policy(REPO,self.before)
            for path,raw in files.items():self.assertEqual(raw,(out/path).read_bytes())
            original_tests={**navigation.original_tests(REPO),**source.original_tests(REPO)}
            self.assertEqual(48,sum(raw.count(b'    @Test\n')for raw in original_tests.values()))
            for path,raw in original_tests.items():self.assertEqual(raw,(tests/path).read_bytes())
            self.assertEqual(proof,json.loads((out/'v043-message-link-host-proof.json').read_bytes()))

    def test_producer_cannot_overwrite_the_new_original_archive(self):
        root=self.temporary_repo();out=root/source.ARCHIVE/'generated'
        with self.assertRaisesRegex(ValueError,'Output overlaps original source'):
            self.producer.generate(root,out,True)
        self.assertFalse(out.exists())

    def test_gradle_uses_the_same_sole_producer_and_declares_its_inputs(self):
        gradle=(REPO/'desktop/build.gradle.kts').read_text(encoding='utf-8')
        self.assertEqual(1,gradle.count('exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/navigation/MessageLinkNavigationPolicy.kt"))'))
        block=gradle.split('val extractUpstreamSpace by tasks.registering',1)[1].split('val extractUpstreamSpaceOverview',1)[0]
        self.assertIn('"tools/v043_message_link_host.py"',block)
        self.assertIn('inputs.dir("upstream-slices/v043-message-link-host")',block)
        self.assertIn('inputs.file(canonicalOriginalSource("app/src/main/java/com/android/purebilibili/navigation/MessageLinkNavigationPolicy.kt"))',block)

    def test_both_retained_git_blob_identities_are_exact(self):
        files=source.verified_raw(REPO)
        for pin in source.PINS:
            raw=files[pin['archiveFile']]
            self.assertEqual(pin['gitBlob'],hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest())


if __name__=='__main__':
    unittest.main()
