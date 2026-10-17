from pathlib import Path
import importlib.util
import json
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v044_following as following

def undo(body, proof):
    lines = body.splitlines(keepends=True)
    for row in reversed(proof['hunks']):
        first, last = row['afterLines']
        assert ''.join(lines[first:last]) == row['after']
        lines[first:last] = row['before'].splitlines(keepends=True)
    original = ''.join(lines)
    assert following.sha(original) == proof['beforeSHA256']
    return original

class WholeOriginalFollowingSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        spec = importlib.util.spec_from_file_location('current_whole_following_owner', REPO/'desktop/tools/extract-upstream-following.py')
        cls.owner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.owner)
        cls.rows = cls.owner.generate(REPO, cls.root/'main', True, cls.root/'tests')
        cls.body = (cls.root/'main/com/android/purebilibili/feature/following/FollowingListScreen.kt').read_text(encoding='utf8')
        cls.proof = next(row for row in cls.rows if row['path'].endswith('/FollowingListScreen.kt'))['currentFollowingPlatformBinding']

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_complete_current_viewmodel_and_ui_have_an_exact_whole_original_inverse(self):
        original = following.fixed(REPO)['FollowingListScreen.kt']
        self.assertEqual(original, undo(self.body, self.proof['originalToWindows']))
        self.assertTrue(self.proof['completeOriginalFollowingVmAndUi'])
        row = next(row for row in self.rows if row['path'].endswith('/FollowingListScreen.kt'))
        self.assertEqual(following.COMMIT, row['upstreamCommit'])
        self.assertEqual(following.sha(original), row['sha256LfUtf8'])

    def test_first_page_does_not_start_the_removed_eager_full_directory_loop(self):
        self.assertNotIn('loadAllRemainingPages(', self.body)
        self.assertIn('fun loadAllForSearch()', self.body)
        self.assertIn('private suspend fun loadNextFollowingPage(generation: Long)', self.body)
        self.assertIn('while (generation == followingGeneration)', self.body)
        self.assertTrue(self.proof['firstPageOnlyAtStartup'])
        self.assertTrue(self.proof['explicitOriginalLoadAllForSearch'])

    def test_existing_root_generation_and_cache_write_admission_are_retained(self):
        self.assertIn('@Volatile private var followingGeneration = 0L', self.body)
        self.assertNotIn('var requestGeneration', self.body)
        self.assertIn('kotlinx.coroutines.currentCoroutineContext().ensureActive()', self.body)
        self.assertIn('environment.assertOwned()', self.body)
        self.assertIn('firstPageJob?.cancel()', self.body)
        self.assertIn('context = environment.cacheContext.forRequest { generation == followingGeneration && writeGeneration == cacheWriteGeneration }', self.body)
        self.assertIn('loadRemainingPagesJob === kotlinx.coroutines.currentCoroutineContext()[Job]', self.body)

    def test_original_aggregate_brand_feedback_uses_the_captured_actual_owner_once(self):
        self.assertEqual(1, self.body.count('val brandOrigin = environment.favorites.captureBrandFeedback()'))
        self.assertEqual(1, self.body.count('environment.favorites.confirmBrandFollow(brandOrigin,'))
        self.assertNotIn('BrandSuccessEvents.followChanged(', self.body)
        self.assertIn('environment.actions.followUser(mid, follow = false, emitBrandFeedback = false)', self.body)
        self.assertTrue(self.proof['windowsBrandSuccessAdaptation']['wholeInverseExact'])

    def test_current_user_interface_keeps_explicit_full_search_and_native_fans_callbacks(self):
        self.assertIn('搜索已加载 UP 主（全量请点加载全部）', self.body)
        self.assertIn('AppTextButton(onClick = { viewModel.loadAllForSearch() })', self.body)
        self.assertIn('onFansClick = { onFansClick(user.mid) }', self.body)
        host = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalFollowingHost.kt').read_text(encoding='utf8')
        self.assertIn('{mid->if(entry.owns())onFansClick(mid)},entry.viewModel)', host)

    def test_current_group_policy_and_22_original_pure_cases_are_emitted_verbatim(self):
        raw = following.fixed(REPO)
        self.assertEqual(raw['FollowingGroupPolicy.kt'], (self.root/'main/com/android/purebilibili/feature/following/FollowingGroupPolicy.kt').read_text(encoding='utf8'))
        self.assertIn('shouldContinueFollowingPagination', raw['FollowingGroupPolicy.kt'])
        for name, count in [('FollowingGroupPolicyTest.kt', 8), ('FollowingBatchSelectionPolicyTest.kt', 14)]:
            body = (self.root/'tests/com/android/purebilibili/feature/following'/name).read_text(encoding='utf8')
            self.assertEqual(raw[name], body)
            self.assertEqual(count, body.count('@Test'))

    def test_actual_gradle_tracks_the_sole_policy_producer_and_original_test_output(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/following/FollowingGroupPolicy.kt"))'))
        self.assertIn('"tools/v044_following.py"', build)
        self.assertIn('"--test-output", layout.buildDirectory.dir("generated/original-following-tests")', build)
        self.assertIn('kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-following-tests")) }', build)

    def test_current_following_inputs_are_no_longer_reference_only(self):
        catalog = json.loads((REPO/'desktop/tools/compatibility-source-inputs.json').read_bytes())
        for name in ['FollowingListScreen.kt', 'FollowingGroupPolicy.kt', 'FollowingGroupPolicyTest.kt', 'FollowingBatchSelectionPolicyTest.kt']:
            row = next(row for row in catalog['inputs'] if row['localPath']=='desktop/upstream-slices/v044-following-fans/'+name)
            self.assertFalse(row['referenceOnly'])
            self.assertFalse(row['partialReuse'])
            self.assertEqual(following.COMMIT, row['upstreamCommit'])

if __name__ == '__main__':
    unittest.main()
