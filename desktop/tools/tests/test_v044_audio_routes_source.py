from pathlib import Path
import importlib.util
import json
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v044_audio_routes as audio
import v044_favorite_search as search

def undo(body, proof):
    lines = body.splitlines(keepends=True)
    for row in reversed(proof['hunks']):
        first, last = row['afterLines']
        assert ''.join(lines[first:last]) == row['after']
        lines[first:last] = row['before'].splitlines(keepends=True)
    result = ''.join(lines)
    assert search.sha(result) == proof['beforeSHA256']
    return result

class OriginalAudioRouteSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        spec = importlib.util.spec_from_file_location('audio_route_real_owner', REPO/'desktop/tools/extract-upstream-listen-video-root.py')
        producer = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(producer)
        producer.produce(REPO, cls.root/'navigation', cls.root/'tests')
        cls.mapping = (cls.root/'navigation/com/android/purebilibili/navigation3/BiliPaiNavKeyMappingPolicy.kt').read_text(encoding='utf8')
        cls.proof = json.loads((cls.root/'navigation/v044-favorite-search-mapping-proof.json').read_bytes())

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_both_audio_branches_are_complete_current_originals_at_the_actual_producer(self):
        proof = self.proof['originalAudioCollectionMapping']
        original = search.fixed(REPO)['BiliPaiNavKeyMappingPolicy.kt']
        self.assertEqual(2, len(proof['completeOriginalAudioBranches']))
        for row in proof['completeOriginalAudioBranches']:
            self.assertEqual(row['originalText'], original[row['originalOffset']:row['originalOffset']+len(row['originalText'])])
            self.assertEqual(row['originalSHA256'], search.sha(row['originalText']))
            self.assertIn(row['originalText'], self.mapping)

    def test_audio_advance_preserves_the_complete_pending_favorite_mapping_and_other_routes(self):
        audio_mapping = undo(self.mapping, self.proof['originalNativeFansMapping']['retainedToCurrent'])
        favorite = undo(audio_mapping, self.proof['originalAudioCollectionMapping']['retainedToCurrent'])
        self.assertEqual(audio.BEFORE_MAPPING_SHA256, search.sha(favorite))
        baseline = search.canonical_source(REPO, search.NAV+'BiliPaiNavKeyMappingPolicy.kt').read_text(encoding='utf8')
        self.assertEqual(baseline, undo(self.mapping, self.proof['retainedToCurrent']))
        self.assertIn('&subscribed=$subscribed', self.mapping)
        self.assertIn('subscribed = query["subscribed"].toBoolean()', self.mapping)

    def test_unreviewed_predecessor_and_second_application_are_rejected(self):
        audio_mapping = undo(self.mapping, self.proof['originalNativeFansMapping']['retainedToCurrent'])
        before = undo(audio_mapping, self.proof['originalAudioCollectionMapping']['retainedToCurrent'])
        for value in [before+'\n', self.mapping]:
            with self.assertRaises(ValueError):
                audio.advance_mapping(REPO, value)

    def test_whole_original_audio_regression_is_emitted_into_the_existing_generated_test_directory(self):
        original = search.fixed(REPO)['BiliPaiNavKeyMappingPolicyTest.kt']
        body = (self.root/'tests/com/android/purebilibili/navigation3/V044AudioCollectionRouteOriginalTest.kt').read_text(encoding='utf8')
        marker = '    @Test\n    fun audioCollectionKeysPreserveSourceAndTitleAcrossRouteRestoration() {'
        first = original.index(marker)
        method = original[first:original.index('\n    }', first)+len('\n    }')]
        self.assertIn(method, body)
        self.assertEqual(1, body.count('@Test'))
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        self.assertIn('"tools/v044_audio_routes.py"', build)
        self.assertIn('kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-listen-video-policy-tests")) }', build)

    def test_original_archives_cannot_be_overwritten_by_audio_test_emission(self):
        before = {p.name: p.read_bytes() for p in (REPO/search.ARCHIVE).iterdir()}
        with self.assertRaises(ValueError):
            audio.emit_original_test(REPO, REPO/search.ARCHIVE)
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO/search.ARCHIVE).iterdir()})

if __name__ == '__main__':
    unittest.main()
