from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v038_listen_screen as listen
import v044_favorite_search as search

def load(name):
    spec = importlib.util.spec_from_file_location('favorite_search_'+name.replace('-', '_'), REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def undo(body, proof):
    lines = body.splitlines(keepends=True)
    for hunk in reversed(proof['hunks']):
        first, last = hunk['afterLines']
        assert ''.join(lines[first:last]) == hunk['after']
        lines[first:last] = hunk['before'].splitlines(keepends=True)
    result = ''.join(lines)
    assert search.sha(result) == proof['beforeSHA256']
    return result

class OriginalFavoriteSearchSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temp.name)
        cls.originals = search.fixed(REPO)
        cls.producer = load('extract-upstream-listen-video-root.py')
        cls.producer.produce(REPO, cls.root/'navigation', cls.root/'tests')
        cls.favorite = load('extract-upstream-favorites.py')
        cls.inventory = cls.favorite.generate(REPO, cls.root/'favorites')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_exact_archived_originals_reject_tampered_missing_or_extra_leaves(self):
        for name in [*search.EXPECTED, 'manifest.json', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO/search.ARCHIVE, repo/search.ARCHIVE)
                file = repo/search.ARCHIVE/name
                file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(ValueError):
                    search.fixed(repo)

    def test_real_navigation_producer_reuses_whole_current_search_declaration_and_keeps_audio(self):
        file = self.root/'navigation/com/android/purebilibili/navigation3/BiliPaiNavKey.kt'
        body = file.read_text(encoding='utf8')
        self.assertEqual(search.favorite_declaration(self.originals['BiliPaiNavKey.kt']), search.favorite_declaration(body))
        self.assertEqual(listen.audio_declaration(listen.fixed_sources(REPO)['BiliPaiNavKey.kt']), listen.audio_declaration(body))
        proof = json.loads((self.root/'navigation/v038-listen-screen-source-proof.json').read_bytes())
        row = next(row for row in proof['sources'] if row['output'].endswith('/BiliPaiNavKey.kt'))
        for edit in reversed(row['edits']):
            at = edit['offset']
            self.assertEqual(edit['after'], body[at:at+len(edit['after'])])
            body = body[:at]+edit['before']+body[at+len(edit['after']):]
        baseline = search.canonical_source(REPO, search.NAV+'BiliPaiNavKey.kt').read_text(encoding='utf8')
        self.assertEqual(baseline, body)

    def test_only_whole_original_search_mapping_branches_advance(self):
        body = (self.root/'navigation/com/android/purebilibili/navigation3/BiliPaiNavKeyMappingPolicy.kt').read_text(encoding='utf8')
        proof = json.loads((self.root/'navigation/v044-favorite-search-mapping-proof.json').read_bytes())
        original = self.originals['BiliPaiNavKeyMappingPolicy.kt']
        self.assertEqual(2, len(proof['completeOriginalBranches']))
        for row in proof['completeOriginalBranches']:
            self.assertTrue(row['completeOriginalBranch'])
        self.assertIn(search.between(original, '        is BiliPaiNavKey.FavoriteSearch ->',
            '        is BiliPaiNavKey.LikedVideos ->'), body)
        baseline = search.canonical_source(REPO, search.NAV+'BiliPaiNavKeyMappingPolicy.kt').read_text(encoding='utf8')
        self.assertEqual(baseline, undo(body, proof['retainedToCurrent']))

    def test_real_list_callback_and_root_restore_and_history_consumer_share_current_contract(self):
        body = (self.root/'favorites/com/android/purebilibili/feature/list/CommonListScreen.kt').read_text(encoding='utf8')
        row = next(row for row in self.inventory if any(q['path'].endswith('/CommonListScreen.kt') for q in row['outputs']))
        proof = row['v044FavoriteSubscriptionUiAdvance']['originalSearchNavigation']
        from v044_list_foreground import restore_predecessor
        from v046_favorite_editor import restore as restore_editor_predecessor
        if row.get('v046FavoriteEditorAdvance'):
            body = restore_editor_predecessor(body, row['v046FavoriteEditorAdvance']['retainedToCurrent'])
        if row.get('v044ListForegroundAdvance'): body = restore_predecessor(body, row['v044ListForegroundAdvance'])
        self.assertEqual(search.BASE_UI_SHA256, search.sha(undo(body, proof['retainedToCurrent'])))
        for segment in proof['originalCallbackSegments']:
            self.assertIn(segment['originalText'], body)
        effect = search.between(body, '    LaunchedEffect(\n        searchQuery,\n', '    // [New] 动态顶栏高度测量')
        original_effect = undo(effect, proof['originalSearchEffectPlatformBinding'])
        self.assertEqual(proof['originalSearchEffectSHA256'], search.sha(original_effect))
        self.assertTrue(proof['completeOriginalSearchEffect'])
        self.assertIn('if (!isCurrentPage) return@LaunchedEffect', effect)
        self.assertIn('isSearchDestination && favoriteViewModel != null && !isSubscribedBrowse', effect)
        shell = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/DesktopShell.kt').read_text(encoding='utf8')
        self.assertIn('(entryKey as? BiliPaiNavKey.FavoriteSearch)?.subscribed == true', shell)
        self.assertIn('detail == null && entryKey !is BiliPaiNavKey.FavoriteSearch', shell)
        self.assertIn('{ value, subscribed -> commands.push(BiliPaiNavKey.FavoriteSearch(query = value, subscribed = subscribed)) }', shell)
        host = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalFavoritesHost.kt').read_text(encoding='utf8')
        self.assertIn('onOpenSearchDestination:((String,Boolean)->Unit)?=null', host)
        self.assertIn('onOpenSearchDestination=onOpenSearchDestination', host)
        history = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalPersonalListHost.kt').read_text(encoding='utf8')
        self.assertIn('{ query, _ -> if (entry.owns()) onOpenHistorySearch(query) }', history)

    def test_current_upstream_regression_method_is_emitted_without_rewriting_its_algorithm(self):
        body = (self.root/'tests/com/android/purebilibili/navigation3/V044FavoriteSearchOriginalPolicyTest.kt').read_text(encoding='utf8')
        marker = '    @Test\n    fun personalListSearchKeysRoundTripThroughLegacyRoutes() {'
        original = self.originals['BiliPaiNavKeyMappingPolicyTest.kt']
        first = original.index(marker)
        method = original[first:original.index('\n    }', first)+len('\n    }')]
        self.assertIn(method, body)
        self.assertEqual(1, body.count('@Test'))

    def test_actual_gradle_owners_track_transitive_search_input_closure_and_exclude_duplicate_mapping(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        # Check each retained consumer, rather than the old total including Space overview.
        for owner in (
            'extractUpstreamApi',
            'extractUpstreamAudio',
            'extractUpstreamPackages',
            'extractUpstreamJs',
            'extractUpstreamNetworkProxy',
            'extractUpstreamDiagnostics',
            'extractUpstreamDynamicFullCard',
            'extractImageSaveSettingsUi',
            'extractOriginalFavorites',
            'extractUpstreamDynamicFollow',
            'extractUpstreamStoryTopic',
            'extractOriginalWatchLater',
            'extractOriginalFollowing',
            'extractOriginalListenVideoPages',
        ):
            start = build.index("val "+owner+" by tasks.registering(")
            body = build[start:build.index("\n}", start)]
            self.assertIn('inputs.dir("upstream-slices/v044-favorite-search")', body, owner)
        self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("'+search.NAV+'BiliPaiNavKeyMappingPolicy.kt"))'))
        self.assertIn('inputs.file(canonicalOriginalSource("'+search.NAV+'BiliPaiNavKeyMappingPolicy.kt"))', build)
        self.assertIn('"--test-output", layout.buildDirectory.dir("generated/original-listen-video-policy-tests")', build)
        self.assertIn('kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/original-listen-video-policy-tests")) }', build)

    def test_emission_cannot_overwrite_archived_originals(self):
        before = {p.name: p.read_bytes() for p in (REPO/search.ARCHIVE).iterdir()}
        with self.assertRaises(ValueError):
            search.emit_mapping(REPO, REPO/search.ARCHIVE)
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO/search.ARCHIVE).iterdir()})

if __name__ == '__main__':
    unittest.main()
