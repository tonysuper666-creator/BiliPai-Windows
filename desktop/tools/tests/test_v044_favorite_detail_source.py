"""Current full original detail flow through the real retained Windows producers."""
from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v044_favorite_detail as detail


def load(name):
    spec = importlib.util.spec_from_file_location('favorite_detail_'+name.replace('-', '_'), REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def undo(text, proof):
    if detail.sha(text) != proof['afterSHA256']:
        raise AssertionError('Actual generated output is not the claimed stage')
    lines = text.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        if ''.join(lines[first:last]) != hunk['after']:
            raise AssertionError('Actual inverse span differs')
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    restored = ''.join(lines)
    if detail.sha(restored) != proof['beforeSHA256']:
        raise AssertionError('Complete prior stage was not recovered')
    return restored


class CurrentFavoriteDetailSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bilipai-v044-favorite-detail-')
        cls.root = Path(cls.temp.name)
        cls.raw, cls.manifest = detail.fixed_sources(REPO)
        cls.space = load('extract-space-overview.py')
        cls.audio = load('extract-upstream-audio.py')
        cls.favorites = load('extract-upstream-favorites.py')
        cls.space.generate(REPO, cls.root/'space', True, cls.root/'tests')
        cls.audio.generate(REPO, cls.root/'audio')
        cls.inventory = cls.favorites.generate(REPO, cls.root/'favorites')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_actual_detail_vm_recovers_whole_current_original_and_keeps_root_ownership(self):
        name = 'SeasonSeriesDetailViewModel.kt'
        row = next(row for row in self.inventory if row['path'] == detail.ARCHIVE+'/'+name)
        actual = (self.root/'favorites'/detail.THEME/name).read_text(encoding='utf8')
        self.assertEqual(self.raw[name], undo(actual, row['v044FavoriteDetailPlatformBinding']['originalToPlatform']))
        self.assertIn('BaseListViewModel(environment, "")', actual)
        self.assertEqual(4, actual.count('viewModelScope.launch'))
        self.assertEqual(3, actual.count('environment.currentMid()'))
        self.assertIn('fun onAccountChanged()', actual)
        self.assertNotIn('AccountScopedRequests(', actual)
        self.assertNotIn('TokenManager.', actual)
        self.assertNotIn('android.widget.Toast', actual)
        self.assertIn('environment.showFeedback(', actual)
        self.assertNotIn('    init {\n        loadData()\n    }', actual)
        self.assertTrue(row['v044FavoriteDetailPlatformBinding']['rootRouteInitIsSoleInitialLoad'])
        self.assertTrue(row['v044FavoriteDetailPlatformBinding']['redundantConstructorLoadRemoved'])
        self.assertTrue(row['v044FavoriteDetailPlatformBinding']['invalidRouteIsTerminalWithoutTransport'])
        self.assertIn('isLoading = false, error = "收藏夹或合集参数无效", loadMoreError = null)', actual)

    def test_original_capabilities_pagination_and_seventeen_cases_are_emitted_whole(self):
        self.assertEqual(self.raw['SpacePlaybackPolicy.kt'],
            (self.root/'space'/detail.THEME/'SpacePlaybackPolicy.kt').read_text(encoding='utf8'))
        cases = self.root/'tests'/detail.THEME/'SpacePlaybackPolicyTest.kt'
        self.assertEqual(self.raw[cases.name], cases.read_text(encoding='utf8'))
        self.assertEqual(17, cases.read_text(encoding='utf8').count('@Test'))
        self.assertEqual([], list((self.root/'tests').rglob('SeasonSeriesDetailViewModelTest.kt')))
        self.assertEqual([], list((self.root/'space').rglob('*Test.kt')))

    def test_live_ui_advance_restores_entire_prior_final_owner_including_history_and_cover_gates(self):
        row = next(row for row in self.inventory if row['path'].endswith('/CommonListScreen.kt'))
        actual = (self.root/'favorites/com/android/purebilibili/feature/list/CommonListScreen.kt').read_text(encoding='utf8')
        proof = row['v044FavoriteDetailUiAdvance']
        detail_stage = actual
        if row.get('v044FavoriteSubscriptionUiAdvance'):
            detail_stage = undo(actual, row['v044FavoriteSubscriptionUiAdvance']['retainedToCurrent'])
        old = undo(detail_stage, proof['actualFinalOwnerToCurrent'])
        self.assertEqual('0ef1331c6c0f461129698628db6239ec7c07945b744de08f6f21172be0d45632', detail.sha(old))
        self.assertTrue(proof['allPriorOwnerSourceGatesRetained'])
        self.assertEqual(13, proof['originalSegments'])
        self.assertIn('onFirstReadAuthenticationRequired', old)
        self.assertEqual(old.count('onFirstReadAuthenticationRequired'), actual.count('onFirstReadAuthenticationRequired'))
        self.assertIn('import com.android.purebilibili.core.ui.AppContentColors\n', actual)
        self.assertIn('detailVm.retryLoadMore()', actual)
        self.assertIn('if (canManageFavoriteDetailResources)', actual)
        self.assertIn('onLongClick = if (!historyBatchMode && onHistoryLongDelete != null)', actual)
        self.assertIn('LaunchedEffect(shouldLoadMore.value, items.size, loadMoreError, isLoading, error)', actual)
        from v029_brand_history import selected_source
        baseline = selected_source('feature/list/CommonListScreen')[0]
        advanced, _ = detail.ui_source(REPO, baseline)
        self.assertEqual(self.manifest['reviewedUiAdvance']['selectedSHA256'], detail.sha(advanced))

    def test_single_playlist_model_keeps_whole_current_class_and_prior_queue_algorithms(self):
        text = (self.root/'audio/com/android/purebilibili/feature/video/player/DesktopPlaylistPolicies.kt').read_text(encoding='utf8')
        self.assertEqual(detail.playlist_item(self.raw['PlaylistManager.kt']), detail.playlist_item(text))
        old = self.audio.read_source(REPO, 'app/src/main/java/com/android/purebilibili/feature/video/player/PlaylistManager.kt')
        old_queue = self.audio.section(old, '/**\n * 播放列表项', '/**\n *  播放列表管理器')
        current, proof = detail.playlist_source(REPO, old_queue)
        self.assertEqual(old_queue, undo(current, proof['retainedQueueToCurrent']))
        self.assertTrue(text.endswith(current.rstrip()+'\n'))
        self.assertEqual(1, text.count('data class PlaylistItem('))

    def test_changed_inputs_and_unreviewed_predecessors_fail_closed(self):
        for name in ('manifest.json', 'SeasonSeriesDetailViewModel.kt', 'extra.kt'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                repo = Path(temp)
                target = repo/detail.ARCHIVE
                shutil.copytree(REPO/detail.ARCHIVE, target)
                file = target/name
                file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(ValueError):
                    detail.fixed_sources(repo)
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            detail.selected_source(REPO, 'feature/space/SeasonSeriesDetailViewModel', 'foreign source', {})
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            detail.ui_source_after_platform(REPO, 'foreign source')

    def test_real_entrypoints_reject_archive_outputs_and_repeat_deterministically(self):
        before = {p.name:p.read_bytes() for p in (REPO/detail.ARCHIVE).iterdir()}
        for owner in (self.space, self.audio, self.favorites):
            with self.subTest(owner=owner.__name__), self.assertRaisesRegex(ValueError, 'overlaps'):
                owner.generate(REPO, REPO/detail.ARCHIVE/'nested')
        self.assertEqual(before, {p.name:p.read_bytes() for p in (REPO/detail.ARCHIVE).iterdir()})
        snapshot = {p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.space.generate(REPO, self.root/'space', True, self.root/'tests')
        self.audio.generate(REPO, self.root/'audio')
        self.favorites.generate(REPO, self.root/'favorites')
        self.assertEqual(snapshot, {p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_all_actual_owners_track_input_closure_and_only_one_policy_owner_compiles(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        for task in ('extractOriginalFavorites', 'extractUpstreamSpaceOverview', 'extractUpstreamAudio',
                     'extractOriginalWatchLater', 'extractOriginalFollowing'):
            at = build.index('val '+task+' by tasks.registering(')
            block = build[at:build.index('\n}', at)]
            self.assertIn('inputs.file("tools/v044_favorite_detail.py")', block)
            self.assertIn('inputs.dir("upstream-slices/v044-favorite-detail")', block)
        path = 'app/src/main/java/com/android/purebilibili/feature/space/SpacePlaybackPolicy.kt'
        self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("'+path+'"))'))
        sync = load('sync-upstream.py')
        entries = [row for row in sync.adapted_compatibility_inputs(REPO) if row['localPath'].startswith(detail.ARCHIVE+'/')]
        self.assertEqual(8, len(entries))
        self.assertEqual(3, sum(row['referenceOnly'] for row in entries))


if __name__ == '__main__':
    unittest.main()
