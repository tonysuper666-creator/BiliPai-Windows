from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v044_favorite_subscription as subscription


def load(name):
    spec = importlib.util.spec_from_file_location('subscription_test_'+name.replace('-', '_'),
        REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def undo(value, proof):
    lines = value.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        assert ''.join(lines[first:last]) == hunk['after']
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    original = ''.join(lines)
    assert subscription.sha(original) == proof['beforeSHA256']
    return original


class OriginalSubscriptionSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        cls.api_owner = load('extract-upstream-api.py')
        cls.favorite_owner = load('extract-upstream-favorites.py')
        cls.api_owner.generate(REPO, cls.root/'api')
        cls.inventory = cls.favorite_owner.generate(REPO, cls.root/'favorites', True, cls.root/'policy-tests')
        cls.favorite_owner.generate(REPO, cls.root/'favorites-production', test_output=cls.root/'policy-tests-production')
        cls.raw, cls.manifest = subscription.fixed(REPO)
        cls.favorite_root = cls.root/'favorites/com/android/purebilibili'
        cls.syntax = subscription.parser(REPO)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def actual(self, relative):
        return (self.favorite_root/relative).read_text(encoding='utf8')

    def row(self, suffix):
        return next(row for row in self.inventory if row['outputs'] and
            any(item['path'].endswith(suffix) for item in row['outputs']))

    def test_complete_original_inputs_are_fixed_and_tampering_fails(self):
        self.assertEqual(10, len(self.raw))
        for name in ['manifest.json', 'ListViewModel.kt', 'FavoriteRepository.kt', 'extra.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                repo = Path(temporary)
                target = repo/subscription.ARCHIVE
                shutil.copytree(REPO/subscription.ARCHIVE, target)
                file = target/name
                file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(ValueError):
                    subscription.fixed(repo)

    def test_vm_has_complete_original_methods_and_keeps_root_cover_and_history(self):
        actual = self.actual('feature/list/DesktopOriginalListViewModels.kt')
        proof = self.row('DesktopOriginalListViewModels.kt')['v044FavoriteSubscriptionAdvance']
        before = undo(actual, proof['retainedToCurrent'])
        self.assertEqual(self.manifest['beforeFinalOutputs']['feature/list/DesktopOriginalListViewModels.kt']['sha256'],
            subscription.sha(before))
        current_vm = subscription.declaration(actual, 'class', 'FavoriteViewModel', self.syntax)
        previous_vm = subscription.declaration(before, 'class', 'FavoriteViewModel', self.syntax)
        self.assertEqual(10, len(proof['completeOriginalMethods']))
        self.assertTrue(proof['originalPageActivationOwnsStartup'])
        self.assertNotIn('    init {\n        loadData()\n    }', current_vm)
        action = subscription.declaration(current_vm, 'fun', 'toggleSubscribedFolderSubscription', self.syntax)
        self.assertIn('environment.favorite\n                        .setFolderSubscription', action)
        self.assertNotIn('com.android.purebilibili.data.repository.FavoriteRepository', action)
        for method in proof['completeOriginalMethods']:
            adapted = subscription.declaration(current_vm, 'fun', method['name'], self.syntax)
            original = undo(adapted, method['platformBinding'])
            self.assertEqual(method['originalSHA256'], subscription.sha(original))
            self.assertIn(original, self.raw['ListViewModel.kt'])
        self.assertTrue(proof['preservedRootCoverMethods'])
        for name in proof['preservedRootCoverMethods']:
            self.assertEqual(subscription.declaration(previous_vm, 'fun', name, self.syntax),
                subscription.declaration(current_vm, 'fun', name, self.syntax))
        self.assertEqual(subscription.declaration(before, 'class', 'HistoryViewModel', self.syntax),
            subscription.declaration(actual, 'class', 'HistoryViewModel', self.syntax))

    def test_whole_policy_uses_type_and_fid_and_keeps_single_risk_control_function(self):
        actual = self.actual('feature/list/FavoriteCollectionPolicy.kt')
        expected = self.favorite_owner.rmfun(self.raw['FavoriteCollectionPolicy.kt'], 'isFavoriteRiskControlError')
        self.assertEqual(expected, actual)
        self.assertIn('"${folder.type}:${folder.id}_${folder.fid}"', actual)
        self.assertEqual(21, self.raw['FavoriteFolderAggregationPolicyTest.kt'].count('@Test'))
        self.assertEqual([], list((self.root/'favorites').rglob('*Test.kt')))
        relative = 'com/android/purebilibili/feature/list/FavoriteFolderAggregationPolicyTest.kt'
        for output in ['policy-tests', 'policy-tests-production']:
            self.assertEqual(self.raw['FavoriteFolderAggregationPolicyTest.kt'],
                             (self.root/output/relative).read_text(encoding='utf8'))
            self.assertEqual([], list((self.root/output).rglob('FavoriteSubscribedFoldersViewModelTest.kt')))

    def test_real_repository_and_api_advance_whole_original_subscription_operations(self):
        actual = self.actual('data/repository/DesktopOriginalFavoriteRepository.kt')
        proof = self.row('DesktopOriginalFavoriteRepository.kt')['v044FavoriteSubscriptionAdvance']
        before = undo(actual, proof['retainedToCurrent'])
        self.assertEqual(self.manifest['beforeFinalOutputs']['data/repository/DesktopOriginalFavoriteRepository.kt']['sha256'],
            subscription.sha(before))
        method = subscription.declaration(actual, 'fun', 'setFolderSubscription', self.syntax)
        original = undo(method, proof['platformBinding'])
        self.assertIn(original, self.raw['FavoriteRepository.kt'])
        self.assertIn('throw e', method)
        self.assertIn('api.favoriteFolder(mediaId = mediaId, csrf = csrf)', method)
        self.assertIn('api.unfavoriteFolder(mediaId = mediaId, csrf = csrf)', method)
        api = (self.root/'api/com/android/purebilibili/core/network/DesktopUpstreamApi.kt').read_text(encoding='utf8')
        body = api.split('import okhttp3.ResponseBody\n\n', 1)[1].removesuffix('\n')
        api_proof = json.loads((self.root/'api/v044-favorite-subscription-api-proof.json').read_bytes())
        old = undo(body, api_proof['retainedToCurrent'])
        for name in ['favoriteFolder', 'unfavoriteFolder']:
            self.assertNotIn('suspend fun '+name+'(', old)
            self.assertEqual(1, body.count('suspend fun '+name+'('))
        self.assertEqual(2, api_proof['completeOriginalRetrofitMethods'])

    def test_bus_is_verbatim_and_identity_declaration_has_one_existing_owner(self):
        bus = self.actual('core/refresh/FavoriteSubscriptionBus.kt')
        self.assertEqual(self.raw['FavoriteSubscriptionBus.kt'], bus)
        identity = self.actual('core/store/DesktopFavoriteAccountIdentity.kt')
        original = subscription.declaration(self.raw['TokenManager.kt'], 'class',
            'AccountSessionIdentity', self.syntax, True)
        self.assertIn(original, identity)
        self.assertEqual(1, identity.count('data class AccountSessionIdentity('))
        self.assertNotIn('object TokenManager', identity)
        self.assertNotIn('MutableStateFlow', identity)
        production = self.root/'favorites-production/com/android/purebilibili'
        self.assertEqual(bus, (production/'core/refresh/FavoriteSubscriptionBus.kt').read_text(encoding='utf8'))
        self.assertEqual(identity, (production/'core/store/DesktopFavoriteAccountIdentity.kt').read_text(encoding='utf8'))

    def test_actual_subscription_ui_keeps_whole_original_composables_and_live_actions(self):
        actual = self.actual('feature/list/CommonListScreen.kt')
        proof = self.row('CommonListScreen.kt')['v044FavoriteSubscriptionUiAdvance']
        from v044_list_foreground import restore_predecessor
        current = self.row('CommonListScreen.kt').get('v044ListForegroundAdvance')
        if current: actual = restore_predecessor(actual, current)
        previous = undo(actual, proof['retainedToCurrent'])
        self.assertEqual(self.manifest['beforeFinalOutputs']['feature/list/CommonListScreen.kt']['sha256'],
            subscription.sha(previous))
        self.assertEqual(3, len(proof['completeOriginalComposables']))
        for item in proof['completeOriginalComposables']:
            first, last = subscription.composable_span(actual, item['name'], self.syntax)
            body = actual[first:last]
            self.assertEqual(item['originalSHA256'], subscription.sha(body))
            self.assertIn(body, self.raw['CommonListScreen.kt'])
        self.assertIn('actions = subscribedFolderActions', actual)
        self.assertIn('error = subscribedFolderProgressState.error', actual)
        self.assertIn('onRetry = { favoriteVm.retrySubscribedFolders() }', actual)
        self.assertIn('onToggleSubscription = { favoriteVm.toggleSubscribedFolderSubscription(it) }', actual)
        self.assertIn('allowAutomaticLoadMore = isCurrentPage', actual)
        self.assertIn('isSearchDestination &&\n                    !isSubscribedBrowse &&', actual)
        self.assertTrue(proof['accountEffectUsesRootRetiredViewModelIdentity'])

    def test_material_is_whole_original_with_only_actual_windows_capability_binding(self):
        actual = self.actual('core/ui/components/WallpaperMediaCardSurface.kt')
        expected, proof = subscription.material_source(REPO)
        self.assertEqual(expected, actual)
        self.assertEqual(self.raw['WallpaperMediaCardSurface.kt'], undo(actual, proof['originalToWindows']))
        self.assertIn('24.dp.toPx()', actual)
        self.assertIn('0.44f else 0.36f', actual)
        self.assertIn('desktopFavoriteWallpaperEffectsSupported()', actual)
        self.assertNotIn('android.os.Build', actual)
        self.assertIn('!isLowBlurBudgetForced()', actual)

    def test_real_entrypoints_protect_archives_and_repeat_deterministically(self):
        fixed = {p.name:p.read_bytes() for p in (REPO/subscription.ARCHIVE).iterdir()}
        for owner in [self.api_owner, self.favorite_owner]:
            with self.subTest(owner=owner.__name__), self.assertRaisesRegex(ValueError, 'overlaps'):
                owner.generate(REPO, REPO/subscription.ARCHIVE/'nested')
        self.assertEqual(fixed, {p.name:p.read_bytes() for p in (REPO/subscription.ARCHIVE).iterdir()})
        before = {p.relative_to(self.root).as_posix():p.read_bytes()
            for p in self.root.rglob('*') if p.is_file()}
        self.api_owner.generate(REPO, self.root/'api')
        self.favorite_owner.generate(REPO, self.root/'favorites', True, self.root/'policy-tests')
        self.favorite_owner.generate(REPO, self.root/'favorites-production', test_output=self.root/'policy-tests-production')
        self.assertEqual(before, {p.relative_to(self.root).as_posix():p.read_bytes()
            for p in self.root.rglob('*') if p.is_file()})
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.favorite_owner.generate(REPO, self.root/'never-created', test_output=REPO/subscription.ARCHIVE)

    def test_unreviewed_predecessors_and_duplicate_api_methods_fail_closed(self):
        for operation in [lambda: subscription.advance_vm(REPO, 'foreign body', self.favorite_owner.drop_logs),
                          lambda: subscription.advance_repository(REPO, 'foreign body', self.favorite_owner.drop_logs),
                          lambda: subscription.advance_policy(REPO, 'foreign body', self.favorite_owner.rmfun)]:
            with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                operation()
        api = (self.root/'api/com/android/purebilibili/core/network/DesktopUpstreamApi.kt').read_text(encoding='utf8')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            subscription.advance_api(REPO, api)

    def test_production_owners_track_complete_new_input_closure_and_pure_original_tests(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        for owner in ['extractUpstreamApi', 'extractOriginalFavorites', 'extractOriginalWatchLater',
                      'extractOriginalFollowing', 'extractUpstreamSpaceOverview']:
            first = build.index('val '+owner+' by tasks.registering(')
            body = build[first:build.index('\n}', first)]
            self.assertIn('inputs.file("tools/v044_favorite_subscription.py")', body)
            self.assertIn('inputs.file("tools/v044_favorite_detail.py")', body)
            self.assertIn('"tools/sync-upstream.py"', body)
            self.assertIn('inputs.dir("upstream-slices/v044-favorite-subscription")', body)
        self.assertIn('--test-output", layout.buildDirectory.dir("generated/original-favorite-policy-tests")', build)
        sync = load('sync-upstream.py')
        rows = [row for row in sync.adapted_compatibility_inputs(REPO)
                if row['localPath'].startswith(subscription.ARCHIVE+'/')]
        self.assertEqual(10, len(rows))
        self.assertEqual(['FavoriteSubscribedFoldersViewModelTest.kt'],
                         [Path(row['localPath']).name for row in rows if row['referenceOnly']])


if __name__ == '__main__':
    unittest.main()
