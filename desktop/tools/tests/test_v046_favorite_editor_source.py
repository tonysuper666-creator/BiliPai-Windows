from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v046_favorite_editor as editor
import v046_danmaku_like as like


def load(name):
    spec = importlib.util.spec_from_file_location('favorite_editor_'+name.replace('-', '_'), REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


class OriginalFavoriteEditorSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(); cls.root = Path(cls.temp.name)
        cls.api_owner = load('extract-upstream-api.py'); cls.favorite_owner = load('extract-upstream-favorites.py')
        cls.api_owner.generate(REPO, cls.root/'api')
        cls.rows = cls.favorite_owner.generate(REPO, cls.root/'favorites', True, cls.root/'tests')
        cls.favorite_owner.generate(REPO, cls.root/'production', test_output=cls.root/'production-tests')
        cls.sources, cls.manifest = editor.fixed(REPO); cls.syntax = editor.parser(REPO)
        cls.main = cls.root/'favorites/com/android/purebilibili'
        cls.api_proof = json.loads((cls.root/'api/v046-favorite-editor-api-proof.json').read_bytes())

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def actual(self, relative):
        return (self.main/relative).read_text(encoding='utf8')

    def row(self, name):
        return next(row for row in self.rows if any(x['path'].endswith('/'+name) for x in row['outputs']))

    def test_original_sources_license_and_commit_reject_tampered_extra_or_missing_leaves(self):
        self.assertEqual(8, len(self.sources)); self.assertEqual(editor.COMMIT, self.manifest['commit'])
        for name in [*editor.EXPECTED, 'manifest.json', 'LICENSE', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                repo = Path(temp); root = repo/editor.ARCHIVE
                shutil.copytree(REPO/editor.ARCHIVE, root)
                file = root/name; file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(ValueError): editor.fixed(repo)

    def test_actual_api_and_response_reuse_complete_original_declarations_with_single_model_owner(self):
        body = (self.root/'api/com/android/purebilibili/core/network/DesktopUpstreamApi.kt').read_text(encoding='utf8')
        current = body.split('import okhttp3.ResponseBody\n\n', 1)[1].removesuffix('\n')
        like_proof = json.loads((self.root/'api/v046-danmaku-like-api-proof.json').read_bytes())
        editor_stage = like.restore(current, like_proof['retainedToCurrent'])
        before = editor.restore(editor_stage, self.api_proof['retainedToCurrent'])
        self.assertNotIn('getFavFolderInfo(', before); self.assertEqual(1, current.count('getFavFolderInfo('))
        self.assertIn(self.api_proof['originalMethod'], self.sources['ApiClient.kt'])
        self.assertIn('@GET("x/v3/fav/folder/info")', current)
        self.assertIn('@Query("media_id") mediaId: Long', current)
        response = (self.root/'api/com/android/purebilibili/data/model/response/FavoriteInfoResponse.kt').read_text(encoding='utf8')
        self.assertEqual(1, response.count('data class FavoriteInfoResponse('))
        self.assertTrue(self.api_proof['responseDeclaration']['installedModelsUnchanged'])
        self.assertTrue(self.api_proof['responseDeclaration']['wholeOriginalModelAfterSingleDeclarationRemoval'])
        self.assertEqual([], list((self.root/'favorites').rglob('FavoriteModels.kt')))

    def test_actual_repository_and_vm_restore_whole_prior_outputs_and_original_members(self):
        for relative in ['data/repository/DesktopOriginalFavoriteRepository.kt', 'feature/list/DesktopOriginalListViewModels.kt']:
            current = self.actual(relative); proof = self.row(Path(relative).name)['v046FavoriteEditorAdvance']
            before = editor.restore(current, proof['retainedToCurrent'])
            self.assertEqual(self.manifest['beforeFinalOutputs'][relative]['sha256'], editor.sha(before))
            for row in proof['completeOriginalMethods']:
                actual = editor.declaration(current, 'fun', row['name'], self.syntax)
                original = editor.restore(actual, row['platformBinding'])
                self.assertEqual(row['originalSHA256'], editor.sha(original))
                source = self.sources['ListViewModel.kt' if 'ViewModels' in relative else 'FavoriteRepository.kt']
                self.assertIn(original, source)
        vm = self.actual('feature/list/DesktopOriginalListViewModels.kt')
        self.assertNotIn('editSelectedFavoriteFolder(', vm)
        loader = editor.declaration(vm, 'fun', 'loadFavoriteFolderForEditing', self.syntax)
        self.assertIn('environment.favoriteAccountIdentity()', loader)
        self.assertIn('environment.isFavoriteAccountCurrent(identity)', loader)
        self.assertEqual(2, loader.count('!isPageActive'))
        writer = editor.declaration(vm, 'fun', 'editFavoriteFolder', self.syntax)
        self.assertIn('mediaId = mediaId', writer); self.assertIn('cover = cover', writer)

    def test_actual_editor_keeps_the_complete_original_dialog_and_guards_late_result_and_callbacks(self):
        current = self.actual('feature/list/CommonListScreen.kt')
        proof = self.row('CommonListScreen.kt')['v046FavoriteEditorAdvance']
        before = editor.restore(current, proof['retainedToCurrent'])
        self.assertEqual(self.manifest['beforeFinalOutputs']['feature/list/CommonListScreen.kt']['sha256'], editor.sha(before))
        start = current.index('    favoriteFolderEditorMode?.takeIf')
        end = current.index('\n    if (showFavoriteFolderDeleteConfirm', start)
        original = editor.restore(current[start:end], proof['originalToWindows'])
        self.assertEqual(proof['originalDialogSHA256'], editor.sha(original))
        self.assertIn(original, self.sources['CommonListScreen.kt'])
        dialog = current[start:end]
        self.assertLess(dialog.index('currentCoroutineContext().ensureActive()'), dialog.index('favoriteFolderEditorTitle = info.title'))
        for token in ['favoriteFolderEditorStamp === editorStamp', 'favoriteEditorOwner === editorViewModel',
                      'favoriteFolderEditorMediaId != expectedMediaId', 'favoriteFolderEditorRetry != expectedRetry',
                      'loadFavoriteFolderForEditing(expectedMediaId)', 'cover = favoriteFolderEditorCover',
                      'mediaId = favoriteFolderEditorMediaId', 'if (!editorCurrent() || !favoriteFolderEditorLoaded || isFavoriteManaging)']:
            self.assertIn(token, dialog)
        self.assertIn('正在读取收藏夹信息…', dialog); self.assertIn('重试', dialog)
        self.assertNotIn('accountIdentity', dialog)

    def test_explicit_browse_selection_and_low_privacy_bit_are_used_by_actual_menu_actions(self):
        ui = self.actual('feature/list/CommonListScreen.kt')
        self.assertNotIn('LaunchedEffect(foldersState.size, subscribedFoldersState.size)', ui)
        self.assertEqual(2, ui.count('selectedFavoriteFolder?.let { !isFavoriteFolderPrivate(it.attr) }'))
        self.assertNotIn('favoriteFolderEditorPrivate = folder.attr != 0', ui)
        policy = self.actual('feature/list/FavoriteManagementPolicy.kt')
        self.assertEqual(self.sources['FavoriteManagementPolicy.kt'], policy)
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/list/FavoriteManagementPolicy.kt"))'))

    def test_both_entire_original_suites_are_emitted_with_only_junit_import_binding(self):
        for name, package, count in [('FavoriteManagementPolicyTest.kt', 'feature/list', 6),
                                     ('FavoriteModelsMappingTest.kt', 'data/model/response', 7)]:
            original = self.sources[name]; expected = original
            for a, b in [('import org.junit.Assert.assertEquals', 'import kotlin.test.assertEquals'),
                         ('import org.junit.Assert.assertTrue', 'import kotlin.test.assertTrue'),
                         ('import org.junit.Test', 'import kotlin.test.Test')]: expected = expected.replace(a, b)
            self.assertEqual(count, original.count('@Test'))
            for output in ['tests', 'production-tests']:
                actual = (self.root/output/'com/android/purebilibili'/package/name).read_text(encoding='utf8')
                self.assertEqual(expected, actual)

    def test_real_producers_reject_archive_overlap_and_repeat_deterministically(self):
        before = {p.name:p.read_bytes() for p in (REPO/editor.ARCHIVE).iterdir()}
        for owner in [self.api_owner, self.favorite_owner]:
            with self.assertRaisesRegex(ValueError, 'overlaps'): owner.generate(REPO, REPO/editor.ARCHIVE/'child')
        self.assertEqual(before, {p.name:p.read_bytes() for p in (REPO/editor.ARCHIVE).iterdir()})
        snapshot = {p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.api_owner.generate(REPO, self.root/'api')
        self.favorite_owner.generate(REPO, self.root/'favorites', True, self.root/'tests')
        self.favorite_owner.generate(REPO, self.root/'production', test_output=self.root/'production-tests')
        self.assertEqual(snapshot, {p.relative_to(self.root).as_posix():p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_input_catalog_and_existing_two_generation_tasks_track_the_entire_new_archive(self):
        sync = load('sync-upstream.py')
        rows = [row for row in sync.adapted_compatibility_inputs(REPO) if row['localPath'].startswith(editor.ARCHIVE+'/')]
        self.assertEqual(8, len(rows)); self.assertTrue(all(not row['referenceOnly'] for row in rows))
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        for name in ['extractUpstreamApi', 'extractOriginalFavorites']:
            at = build.index('val '+name+' by tasks.registering(')
            block = build[at:build.index('\n}', at)]
            self.assertIn('inputs.file("tools/v046_favorite_editor.py")', block)
            self.assertIn('inputs.dir("upstream-slices/v046-favorite-editor")', block)

    def test_all_generated_changed_kotlin_and_actual_root_fixture_keep_balanced_delimiters(self):
        parser = load('sync-upstream.py')
        files = [self.main/'feature/list/CommonListScreen.kt', self.main/'feature/list/DesktopOriginalListViewModels.kt',
                 self.main/'data/repository/DesktopOriginalFavoriteRepository.kt',
                 self.main/'feature/list/FavoriteManagementPolicy.kt',
                 self.root/'api/com/android/purebilibili/data/model/response/FavoriteInfoResponse.kt',
                 REPO/'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopFavoriteEditorRootRegressionTest.kt',
                 REPO/'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopFavoriteCoverPrefetchRegressionTest.kt']
        for file in files:
            stack = []
            for token, first, _ in parser.kotlin_tokens(file.read_text(encoding='utf8')):
                if token in ['(', '[', '{']: stack.append(token)
                elif token in [')', ']', '}']:
                    self.assertTrue(stack, (file.name, first))
                    self.assertEqual({')':'(', ']':'[', '}':'{'}[token], stack.pop(), (file.name, first))
            self.assertEqual([], stack, file.name)


if __name__ == '__main__':
    unittest.main()
