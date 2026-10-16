from pathlib import Path
import hashlib
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v043_search_up_author as source

class V043SearchUpAuthorSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.before = (REPO/'desktop/upstream-slices/v041-video-status-labels/SearchModels.kt').read_bytes()

    def temporary_repo(self):
        temporary = tempfile.TemporaryDirectory(prefix='bp-v043-up-search-')
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        shutil.copytree(REPO/source.ARCHIVE, root/source.ARCHIVE)
        return root

    def test_whole_original_model_matches_the_independent_upstream_pin(self):
        data, proof = source.advance_model(REPO, self.before)
        self.assertEqual('941c24c45ab415de3ae291209e1b945f7c714053386ebd838c6b02e181d8fb36', hashlib.sha256(data).hexdigest())
        self.assertEqual(21022, len(data))
        self.assertEqual('35e2fac8a3870a2227a323e19f29f1e93d7e591c', proof['upstreamCommit'])
        self.assertTrue(proof['singleReviewedMethodDelta'] and proof['completeOriginalInverse'])

    def test_inverse_restores_the_complete_old_model_including_unrelated_fields(self):
        data, _ = source.advance_model(REPO, self.before)
        self.assertEqual(self.before, data.replace(source.AFTER, source.BEFORE, 1))
        self.assertEqual(self.before.split(source.BEFORE), data.split(source.AFTER))

    def test_an_unreviewed_predecessor_change_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'predecessor'):
            source.advance_model(REPO, self.before + b'\n')

    def test_mutated_model_is_rejected_before_it_can_be_emitted(self):
        root = self.temporary_repo(); p = root/source.ARCHIVE/'SearchModels.kt'
        p.write_bytes(p.read_bytes().replace(b'val cleanedName', b'val changedName', 1))
        with self.assertRaisesRegex(ValueError, 'bytes changed'):
            source.advance_model(root, self.before)

    def test_mutated_original_test_is_rejected(self):
        root = self.temporary_repo(); p = root/source.ARCHIVE/'SearchUpItemTest.kt'; p.write_bytes(p.read_bytes()+b'\n')
        with self.assertRaisesRegex(ValueError, 'bytes changed'):
            source.emit_tests(root)

    def test_manifest_change_is_rejected(self):
        root = self.temporary_repo(); p = root/source.ARCHIVE/'manifest.json';p.write_bytes(p.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError, 'manifest pin'):
            source.advance_model(root, self.before)

    def test_unlisted_archive_leaf_is_rejected(self):
        root = self.temporary_repo(); (root/source.ARCHIVE/'extra.kt').write_bytes(b'')
        with self.assertRaisesRegex(ValueError, 'archive leaves'):
            source.advance_model(root, self.before)

    def test_both_original_jvm_test_bodies_are_preserved(self):
        files = source.emit_tests(REPO)
        self.assertEqual(['com/android/purebilibili/data/model/response/SearchUpItemTest.kt'], list(files))
        data, proof = next(iter(files.values())); restored = data
        for before, after in reversed(source.TEST_IMPORTS):restored = restored.replace(after,before,1)
        self.assertEqual((REPO/source.ARCHIVE/'SearchUpItemTest.kt').read_bytes(), restored)
        self.assertEqual(2, proof['cases']); self.assertTrue(proof['completeOriginalInverse'])
        self.assertEqual(2, data.count(b'    @Test\n')); self.assertNotIn(b'org.junit', data)

    def test_every_original_git_blob_matches_its_independent_manifest_identity(self):
        files = source.verified_raw(REPO)
        for row in source.PINS:
            raw = files[row['archiveFile']]
            self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest())

    def test_model_and_test_results_are_deterministic(self):
        self.assertEqual(source.advance_model(REPO,self.before), source.advance_model(REPO,self.before))
        self.assertEqual(source.emit_tests(REPO), source.emit_tests(REPO))

    def test_sole_producer_rejects_output_inside_the_new_original_archive(self):
        import v041_video_status_labels as producer
        root = self.temporary_repo()
        with self.assertRaisesRegex(ValueError, 'Output overlaps fixed source'):
            producer.generate(root, root/source.ARCHIVE/'generated', root/'tests-out')
        self.assertFalse((root/source.ARCHIVE/'generated').exists())

if __name__ == '__main__': unittest.main()
