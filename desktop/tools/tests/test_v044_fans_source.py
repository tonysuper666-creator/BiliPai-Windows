from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v044_fans as fans
import v044_favorite_search as navigation

def load(name):
    spec = importlib.util.spec_from_file_location('native_fans_'+name.replace('-', '_'), REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def undo(body, proof):
    lines = body.splitlines(keepends=True)
    for row in reversed(proof['hunks']):
        first, last = row['afterLines']
        assert ''.join(lines[first:last]) == row['after']
        lines[first:last] = row['before'].splitlines(keepends=True)
    result = ''.join(lines)
    assert fans.sha(result) == proof['beforeSHA256']
    return result

class OriginalNativeFansSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.api = load('extract-upstream-api.py')
        cls.api_file = cls.api.generate(REPO, cls.root/'api')
        cls.following = load('extract-upstream-following.py')
        cls.inventory = cls.following.generate(REPO, cls.root/'following')
        cls.listen = load('extract-upstream-listen-video-root.py')
        cls.listen.produce(REPO, cls.root/'navigation', cls.root/'tests')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_exact_original_archive_rejects_any_tampered_or_extra_input(self):
        for name in [*fans.EXPECTED, 'manifest.json', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO/fans.ARCHIVE, repo/fans.ARCHIVE)
                target = repo/fans.ARCHIVE/name
                target.write_bytes((target.read_bytes() if target.exists() else b'')+b'\n')
                with self.assertRaises(ValueError):
                    fans.fixed(repo)

    def test_actual_following_producer_emits_the_whole_original_fans_state_vm_and_ui(self):
        body = (self.root/'following/com/android/purebilibili/feature/following/FansListScreen.kt').read_text(encoding='utf8')
        row = next(row for row in self.inventory if row['path'].endswith('/FansListScreen.kt'))
        proof = row['nativeFansPlatformBinding']
        self.assertEqual(fans.fixed(REPO)['FansListScreen.kt'], undo(body, proof['originalToWindows']))
        self.assertTrue(proof['wholeOriginalStateViewModelAndUiRetained'])
        self.assertTrue(proof['rootEntryOwnsViewModelAndScope'])
        self.assertFalse(proof['androidViewModelStoreCreated'])
        self.assertFalse(proof['followingCacheReusedForFans'])
        self.assertIn('environment.getFollowers(vmid = mid, pn = requestPage)', body)
        self.assertIn('LaunchedEffect(model, active) { if (active) model.load() }', body)
        self.assertNotIn('viewModelFactory', body)

    def test_actual_api_emits_the_complete_original_fans_method_without_a_second_service(self):
        body = self.api_file.read_text(encoding='utf8')
        proof = json.loads((self.root/'api/v044-favorite-subscription-api-proof.json').read_bytes())['originalFollowersApi']
        self.assertEqual(1, body.count('suspend fun getFollowers('))
        self.assertIn(proof['originalMethod'], body)
        self.assertEqual(proof['originalMethodSHA256'], fans.sha(proof['originalMethod']))
        self.assertIn('@GET("x/relation/fans")', proof['originalMethod'])
        self.assertIn('@Query("ps") ps: Int = 20', proof['originalMethod'])
        self.assertIn('@Query("order_type") orderType: String = "attention"', proof['originalMethod'])
        declarations = body.split('import okhttp3.ResponseBody\n\n', 1)[1].removesuffix('\n')
        before = undo(declarations, proof['retainedToCurrent'])
        self.assertNotIn('suspend fun getFollowers(', before)
        self.assertIn('suspend fun favoriteFolder(', before)
        self.assertIn('suspend fun unfavoriteFolder(', before)

    def test_both_pure_navigation_files_equal_the_complete_current_original(self):
        for name in ['BiliPaiNavKey.kt', 'BiliPaiNavKeyMappingPolicy.kt']:
            self.assertEqual((REPO/navigation.ARCHIVE/name).read_bytes(),
                (self.root/'navigation/com/android/purebilibili/navigation3'/name).read_bytes())
        body = (self.root/'navigation/com/android/purebilibili/navigation3/BiliPaiNavKeyMappingPolicy.kt').read_text(encoding='utf8')
        proof = json.loads((self.root/'navigation/v044-favorite-search-mapping-proof.json').read_bytes())
        self.assertTrue(proof['originalNativeFansMapping']['fullCurrentOriginalMappingUnadapted'])
        before = undo(body, proof['originalNativeFansMapping']['retainedToCurrent'])
        self.assertEqual('cf046ea4a22bf9bd7c3055eda106414e85035162257dc441d023339603c86c43', fans.sha(before))
        self.assertIn('&subscribed=$subscribed', before)
        self.assertIn('collectionMediaId = query["mediaId"]', before)

    def test_real_root_space_entry_and_host_mount_native_fans_with_the_existing_owner(self):
        source = lambda name: (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui'/name).read_text(encoding='utf8')
        space = source('DesktopOriginalSpacePagesRoot.kt')
        self.assertIn('onFansClick={mid->navigate {routes.push(BiliPaiNavKey.Following(mid,fans=true))}}', space)
        self.assertNotIn('https://space.bilibili.com/$mid/fans/fans', space)
        host = source('DesktopOriginalFollowingHost.kt')
        self.assertIn('if(key.fans) fansViewModel=FansListViewModel(key.mid,environment)', host)
        self.assertIn('else viewModel=FollowingListViewModel(environment)', host)
        self.assertIn('if(entry.key.fans) FansListScreen(', host)
        self.assertIn('entry.fansViewModel,foreground)', host)
        self.assertIn('if(closed.compareAndSet(false,true))job.cancel()', host)
        shell = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf8')
        self.assertIn('onFansClick={mid->commands.push(BiliPaiNavKey.Following(mid,fans=true))}', shell)

    def test_production_generators_reject_output_overlap_before_touching_originals(self):
        target = REPO/fans.ARCHIVE
        before = {p.name: p.read_bytes() for p in target.iterdir()}
        for call in [lambda: self.api.generate(REPO, target),
                     lambda: self.following.generate(REPO, target),
                     lambda: self.listen.produce(REPO, target)]:
            with self.assertRaises(ValueError):
                call()
            self.assertEqual(before, {p.name: p.read_bytes() for p in target.iterdir()})

    def test_transitive_inputs_and_current_following_sources_are_explicit(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        self.assertEqual(15, build.count('inputs.dir("upstream-slices/v044-following-fans")'))
        self.assertIn('"tools/v044_fans.py"', build)
        catalog = json.loads((REPO/'desktop/tools/compatibility-source-inputs.json').read_bytes())
        rows = [row for row in catalog['inputs'] if row['localPath'].startswith(fans.ARCHIVE+'/')]
        self.assertEqual(5, len(rows))
        self.assertFalse(next(row for row in rows if row['localPath'].endswith('/FansListScreen.kt'))['referenceOnly'])
        for name in ['FollowingListScreen.kt', 'FollowingGroupPolicy.kt']:
            self.assertFalse(next(row for row in rows if row['localPath'].endswith('/'+name))['referenceOnly'])
        for name in ['BiliPaiNavKey.kt', 'BiliPaiNavKeyMappingPolicy.kt']:
            row = next(row for row in catalog['inputs'] if row['localPath'] == navigation.ARCHIVE+'/'+name)
            self.assertFalse(row['partialReuse'])

    def test_native_fans_generation_is_deterministic(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp)
            self.following.generate(REPO, output)
            first = {p.relative_to(output).as_posix(): p.read_bytes() for p in output.rglob('*') if p.is_file()}
            self.following.generate(REPO, output)
            self.assertEqual(first, {p.relative_to(output).as_posix(): p.read_bytes() for p in output.rglob('*') if p.is_file()})

if __name__ == '__main__':
    unittest.main()
