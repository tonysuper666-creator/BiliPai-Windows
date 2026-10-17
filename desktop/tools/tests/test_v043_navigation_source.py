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
import v043_navigation as source


class V043NavigationSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = REPO / 'app/src/main/java/com/android/purebilibili/core/util'
        cls.old_navigation = (root / 'BilibiliNavigationTargetParser.kt').read_bytes().replace(b'\r\n', b'\n')
        cls.old_url = (root / 'BilibiliUrlParser.kt').read_bytes().replace(b'\r\n', b'\n')
        spec = importlib.util.spec_from_file_location('original_space_producer', REPO / 'desktop/tools/extract-space-platform.py')
        cls.producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.producer)

    def temporary_repo(self):
        temporary = tempfile.TemporaryDirectory(prefix='bp-v043-navigation-inputs-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        shutil.copytree(REPO / source.ARCHIVE, root / source.ARCHIVE)
        return root

    def original_sources(self, repo=REPO):
        return source.original_sources(repo, self.old_navigation, self.old_url)

    def test_all_three_whole_original_sources_are_emitted(self):
        files, proof = self.original_sources()
        self.assertEqual(3, len(files))
        self.assertEqual('35e2fac8a3870a2227a323e19f29f1e93d7e591c', proof['upstreamCommit'])
        self.assertEqual('877b90467279252644e663b959282addc366d2d6c61a3a8b075d219542653f1e', proof['manifestSHA256'])
        for name in ['BilibiliHostPolicy.kt', 'BilibiliNavigationTargetParser.kt']:
            self.assertEqual((REPO/source.ARCHIVE/name).read_bytes(), files['com/android/purebilibili/core/util/'+name])
        self.assertTrue(all(row['wholeOriginal'] and row['completeOriginalInverse'] for row in proof['sources']))

    def test_only_android_uri_and_logging_imports_are_adapted(self):
        files, proof = self.original_sources()
        data = files['com/android/purebilibili/core/util/BilibiliUrlParser.kt']
        self.assertEqual((REPO/source.ARCHIVE/'BilibiliUrlParser.kt').read_bytes(), data.replace(source.URI_AFTER, source.URI_BEFORE, 1))
        self.assertNotIn(b'import android.', data)
        self.assertTrue(proof['onlyPlatformImportAdaptation'])

    def test_all_thirty_one_original_test_bodies_and_imports_are_unchanged(self):
        tests = source.original_tests(REPO)
        self.assertEqual(2, len(tests))
        self.assertEqual(31, sum(raw.count(b'    @Test\n') for raw in tests.values()))
        for path, raw in tests.items():
            self.assertEqual((REPO/source.ARCHIVE/Path(path).name).read_bytes(), raw)
            self.assertIn(b'import kotlin.test.Test', raw)

    def test_changed_navigation_predecessor_cannot_silently_discard_local_changes(self):
        with self.assertRaisesRegex(ValueError, 'predecessor'):
            source.original_sources(REPO, self.old_navigation+b'\n', self.old_url)

    def test_changed_url_predecessor_cannot_silently_discard_local_changes(self):
        with self.assertRaisesRegex(ValueError, 'predecessor'):
            source.original_sources(REPO, self.old_navigation, self.old_url+b'\n')

    def test_mutated_original_sources_are_rejected(self):
        for pin in source.PINS[:3]:
            with self.subTest(path=pin['path']):
                root = self.temporary_repo()
                path = root/source.ARCHIVE/pin['archiveFile']
                path.write_bytes(path.read_bytes()+b'\n')
                with self.assertRaisesRegex(ValueError, 'bytes changed'):
                    self.original_sources(root)

    def test_mutated_original_tests_are_rejected(self):
        root = self.temporary_repo()
        path = root/source.ARCHIVE/'BilibiliNavigationTargetParserTest.kt'
        path.write_bytes(path.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError, 'bytes changed'):
            source.original_tests(root)

    def test_mutated_manifest_is_rejected(self):
        root = self.temporary_repo()
        path = root/source.ARCHIVE/'manifest.json'
        path.write_bytes(path.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError, 'manifest pin'):
            self.original_sources(root)

    def test_unlisted_archive_leaf_is_rejected(self):
        root = self.temporary_repo()
        (root/source.ARCHIVE/'extra.kt').write_bytes(b'')
        with self.assertRaisesRegex(ValueError, 'archive leaves'):
            self.original_sources(root)

    def test_each_exact_git_blob_matches_its_retained_original(self):
        files = source.verified_raw(REPO)
        for pin in source.PINS:
            raw = files[pin['archiveFile']]
            self.assertEqual(pin['gitBlob'], hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest())

    def test_existing_sole_producer_emits_the_actual_new_main_and_test_sources(self):
        with tempfile.TemporaryDirectory(prefix='bp-v043-navigation-output-') as temporary:
            out = Path(temporary)/'main'
            tests = Path(temporary)/'tests'
            self.producer.generate(REPO, out, True, tests)
            expected, _ = self.original_sources()
            for path, raw in expected.items():
                self.assertEqual(raw, (out/path).read_bytes())
            for path, raw in source.original_tests(REPO).items():
                self.assertEqual(raw, (tests/path).read_bytes())
                self.assertFalse((out/path).exists())
            proof = json.loads((out/'v043-navigation-proof.json').read_bytes())
            self.assertEqual(31, proof['originalTestCases'])
            self.assertFalse(proof['mainCompiled'] or proof['jvmTestsExecuted'])

    def test_producer_rejects_archive_output_before_creating_files(self):
        root = self.temporary_repo()
        output = root/source.ARCHIVE/'generated'
        with self.assertRaisesRegex(ValueError, 'Output overlaps original source'):
            self.producer.generate(root, output, True)
        self.assertFalse(output.exists())

    def test_main_and_test_outputs_cannot_overlap(self):
        with tempfile.TemporaryDirectory(prefix='bp-v043-navigation-overlap-') as temporary:
            out = Path(temporary)/'output'
            for tests in [out, out/'tests']:
                with self.subTest(tests=str(tests)), self.assertRaisesRegex(ValueError, 'main/test outputs overlap'):
                    self.producer.generate(REPO, out, True, tests)
            self.assertFalse(out.exists())

    def test_gradle_has_one_navigation_owner_and_compiles_the_original_tests(self):
        gradle = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf-8')
        self.assertEqual(1, gradle.count('exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/core/util/BilibiliNavigationTargetParser.kt"))'))
        block = gradle.split('val extractUpstreamSpace by tasks.registering', 1)[1].split('val extractUpstreamSpaceImagePreviews', 1)[0]
        self.assertIn('"tools/v043_navigation.py"', block)
        self.assertIn('inputs.dir("upstream-slices/v043-navigation")', block)
        self.assertIn('"--test-output"', block)
        self.assertIn('kotlin.sourceSets.named("test")', block)
        self.assertIn('tasks.named("compileTestKotlin") { dependsOn(extractUpstreamSpace) }', block)


if __name__ == '__main__':
    unittest.main()
