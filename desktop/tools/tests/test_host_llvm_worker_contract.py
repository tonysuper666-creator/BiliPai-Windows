from pathlib import Path
import copy
import hashlib
import importlib.util
import json
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('host_worker_importer_under_test',
    ROOT / 'desktop/tools/native/import-host-llvm-source-snapshot.py')
IMPORTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMPORTER)


class HostLlvmWorkerContractTests(unittest.TestCase):
    def records(self):
        return IMPORTER.load_catalog(ROOT)['records']

    def test_exact_one_worker_source_and_legacy_two_worker_source_stay_distinct(self):
        legacy, current = self.records()
        self.assertNotIn('workerMaximum', legacy)
        self.assertEqual(2, IMPORTER.source_worker_maximum(legacy))
        self.assertEqual(1, IMPORTER.source_worker_maximum(current))
        self.assertEqual('7f38cc4ce016c10ae3b087b5e343703bd4bec21d', current['sourceCommit'])
        self.assertEqual(38574814418, current['workflowRunId'])

    def test_original_identity_mutation_cannot_reuse_worker_admission(self):
        current = self.records()[1]
        changes = [('id', 'another-record'), ('sourceCommit', '0' * 40),
            ('sourceTag', 'rtx-source-another'), ('workflowRunId', 38574814419),
            ('workflowRunAttempt', 2), ('descriptor', '0' * 64)]
        for key, value in changes:
            with self.subTest(key=key):
                changed = copy.deepcopy(current)
                if key == 'descriptor': changed['assets'][IMPORTER.ASSETS[0]]['sha256'] = value
                else: changed[key] = value
                with self.assertRaisesRegex(RuntimeError, 'Original source worker contract differs'):
                    IMPORTER.source_worker_maximum(changed)

    def test_worker_override_wrong_type_missing_one_worker_or_wrong_count_is_rejected(self):
        legacy, current = self.records()
        for original, values in [(current, [None, True, False, 0, -1, 2, 3, '1']),
                                 (legacy, [None, True, False, 0, 1, 3, '2'])]:
            for value in values:
                with self.subTest(source=original['id'], value=value):
                    changed = copy.deepcopy(original); changed['workerMaximum'] = value
                    with self.assertRaisesRegex(RuntimeError, 'Original source worker contract differs'):
                        IMPORTER.source_worker_maximum(changed)
        changed = copy.deepcopy(current); del changed['workerMaximum']
        with self.assertRaisesRegex(RuntimeError, 'Original source worker contract differs'):
            IMPORTER.source_worker_maximum(changed)

    def test_one_worker_record_is_bound_to_completed_material_review_without_host_claim(self):
        current = self.records()[1]
        raw = (ROOT / 'desktop/third-party/libmpv/build/rtx-core-v1/host-llvm-material-review-run38574814418.json').read_bytes()
        self.assertEqual(current['independentReviewSha256'], hashlib.sha256(raw).hexdigest())
        review = json.loads(raw)
        self.assertTrue(review['passed'])
        self.assertEqual(current['sourceCommit'], review['sourceHead'])
        self.assertEqual(current['workflowRunId'], review['runId'])
        self.assertTrue(review['fullArchiveAndEmbeddedCanonicalBytesVerified'])
        self.assertTrue(review['fullUsedSourceMaterializationRelationshipsVerified'])
        self.assertEqual(169029, review['fullCanonicalGitBlobs'])
        self.assertFalse(review['usableImportQualified'])
        self.assertFalse(review['actualLlvmImportValidationTargetExecuted'])
        self.assertEqual('229a43dc8c7a282f9ed623203b4fd6b7fa60ad5f4c41ddbd9c99da9a54526fb7',
            review['originalCollectorSHA256Preserved'])


if __name__ == '__main__': unittest.main()
