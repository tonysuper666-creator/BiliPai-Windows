from pathlib import Path
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO/'desktop/tools'))
import v046_favorite_editor as editor
import v046_history_in_place as history

MAIN = REPO/'desktop/src/main/kotlin/com/bilipai/desktop'
TEST = REPO/'desktop/src/test/kotlin'
VM = 'com/android/purebilibili/feature/list/DesktopOriginalListViewModels.kt'


def load(name):
    spec = importlib.util.spec_from_file_location('history_in_place_'+name.replace('-', '_'), REPO/'desktop/tools'/name)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in Path(root).rglob('*') if p.is_file()}


class HistoryInPlaceProgressSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-hip-'); cls.root = Path(cls.temp.name)
        cls.owner = load('extract-upstream-favorites.py')
        cls.rows = cls.owner.generate(REPO, cls.root/'on', True, cls.root/'on-tests')
        with mock.patch.object(history, 'advance_vm', lambda repo, before: (before, {})):
            cls.owner.generate(REPO, cls.root/'off', True, cls.root/'off-tests')
        cls.sources, cls.manifest = editor.fixed(REPO); cls.syntax = editor.parser(REPO)
        cls.on = (cls.root/'on'/VM).read_text(encoding='utf8')
        cls.off = (cls.root/'off'/VM).read_text(encoding='utf8')
        cls.row = next(row for row in cls.rows if any(x['path'].endswith('/DesktopOriginalListViewModels.kt') for x in row['outputs']))
        cls.proof = cls.row['v046HistoryInPlaceAdvance']

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def history_class(self, body):
        return editor.declaration(body, 'class', 'HistoryViewModel', self.syntax)

    def test_only_the_generated_history_vm_changes_and_its_predecessor_is_the_pinned_editor_output(self):
        self.assertEqual(history.PREDECESSOR_SHA256, history.sha(self.off))
        on, off = tree(self.root/'on'), tree(self.root/'off')
        self.assertEqual(set(on), set(off))
        self.assertEqual([VM], sorted(name for name in on if on[name] != off[name]))
        self.assertEqual(tree(self.root/'on-tests'), tree(self.root/'off-tests'))
        self.assertEqual(history.sha(self.on), self.proof['retainedToCurrent']['afterSHA256'])
        # Nothing outside the History class moved: the Liked and Favorite classes keep their exact bodies.
        first, last = editor.span(self.on, 'class', 'HistoryViewModel', self.syntax)
        ofirst, olast = editor.span(self.off, 'class', 'HistoryViewModel', self.syntax)
        self.assertEqual(self.on[:first], self.off[:ofirst]); self.assertEqual(self.on[last:], self.off[olast:])

    def test_actual_output_restores_the_exact_predecessor_and_every_original_run(self):
        self.assertEqual(self.off, history.restore(self.on, self.proof['retainedToCurrent']))
        self.assertEqual(self.sources['ListViewModel.kt'].count('class HistoryViewModel('), 1)
        original = self.sources['ListViewModel.kt']; cls = self.history_class(self.on)
        names = [run['name'] for run in self.proof['completeOriginalRuns']]
        self.assertEqual(['sessionFieldsEnterBrowsingSessionSetPageActive', 'updateCachedHistoryProgress',
                          'initAndObserveHistoryRefresh'], names)
        parts = history.original_members(REPO)
        for run, key in zip(self.proof['completeOriginalRuns'], ['session', 'update', 'refresh']):
            self.assertTrue(run['wholeOriginalRun']); self.assertEqual(history.sha(parts[key]), run['originalSHA256'])
            self.assertIn(parts[key], original)
            adapted = parts[key].replace(history.ORIGINAL_BUS, history.WINDOWS_BUS) if key == 'refresh' else parts[key]
            self.assertEqual(history.sha(adapted), run['adaptedSHA256']); self.assertIn(adapted, cls)
            self.assertEqual(parts[key], history.restore(adapted, run['platformBinding']))
        # Only the Windows bus port differs from the original run.
        self.assertEqual(1, len(self.proof['completeOriginalRuns'][2]['platformBinding']['hunks']))
        self.assertEqual([], self.proof['completeOriginalRuns'][0]['platformBinding']['hunks'])
        self.assertEqual([], self.proof['completeOriginalRuns'][1]['platformBinding']['hunks'])
        self.assertTrue(self.proof['reloadOnEveryHistorySignalRetired'] and self.proof['serverReadOncePerBrowsingSession'])
        self.assertEqual(editor.COMMIT, self.proof['upstreamCommit']); self.assertEqual(editor.MANIFEST_SHA256, self.proof['sourceManifestSHA256'])

    def test_generated_history_vm_has_one_session_one_in_place_update_and_no_reload_on_signals(self):
        cls = self.history_class(self.on); old = self.history_class(self.off)
        for token in ['fun enterBrowsingSession(sessionKey: Any? = null) {', 'fun setPageActive(active: Boolean) {',
                      'private fun updateCachedHistoryProgress() {', 'private fun observeHistoryRefresh() {',
                      'private var isPageActive = false', 'private var browsingSessionStarted = false',
                      'private var browsingSessionKey: Any? = null', 'private var startupLoadsActivated = false',
                      'if (browsingSessionStarted && browsingSessionKey === sessionKey) return',
                      'loadData(showLoading = _uiState.value.items.isEmpty())',
                      'if (isPageActive) updateCachedHistoryProgress()', history.WINDOWS_BUS]:
            self.assertEqual(1, cls.count(token), token)
            # Every token is new, except the collector declaration and bus port the predecessor already had.
            kept = token in (history.WINDOWS_BUS, 'private fun observeHistoryRefresh() {')
            self.assertEqual(1 if kept else 0, old.count(token), token)
        # The retired predecessor: a full server read on every refresh signal.
        self.assertIn('loadData(showLoading = false)', old); self.assertNotIn('loadData(showLoading = false)', cls)
        # The pause state is read at the first activation only, never also in the constructor.
        self.assertEqual(2, cls.count('loadHistoryPauseState()')); self.assertEqual(1, cls.count('fun loadHistoryPauseState()'))
        setter = editor.declaration(cls, 'fun', 'setPageActive', self.syntax)
        self.assertIn('loadHistoryPauseState()', setter); self.assertIn('if (active) updateCachedHistoryProgress()', setter)
        init = cls[cls.index('    init {\n        observeHistoryRefresh()'):]
        self.assertTrue(init.startswith('    init {\n        observeHistoryRefresh()\n    }\n'))
        self.assertNotIn('HistoryRefreshBus', self.on)
        update = editor.declaration(cls, 'fun', 'updateCachedHistoryProgress', self.syntax)
        self.assertIn('enrichHistoryProgress(_historyItemsByRenderKey.values.toList())', update)
        self.assertNotIn('loadData', update); self.assertNotIn('fetchItems', update)
        self.assertLess(cls.index('private fun enrichHistoryProgress('), cls.index('private fun updateCachedHistoryProgress()'))
        self.assertLess(cls.index('private fun updateCachedHistoryProgress()'), cls.index('override suspend fun fetchItems()'))
        self.assertEqual(1, self.on.count('class HistoryViewModel('))
        self.assertEqual(0, self.on.count('accountRequests'))

    def test_the_v039_and_v040_upstream_entries_are_one_pinned_blob_set(self):
        audit = history.AUDIT
        self.assertEqual(audit['v039']['blobs'], audit['v040']['blobs'])
        self.assertEqual(audit['carriedSessionMembers'], ['enterBrowsingSession', 'setPageActive', 'updateCachedHistoryProgress'])
        original = self.history_class(self.sources['ListViewModel.kt'])
        for name in audit['carriedSessionMembers']:
            self.assertEqual(1, original.count('fun '+name+'('))
        self.assertEqual(sorted(audit['v039']['blobs']), sorted(audit['v040']['blobs']))
        self.assertEqual(audit['v039']['commit'], self.proof['upstreamChanges'][0])
        self.assertEqual(audit['v040']['commit'], self.proof['upstreamChanges'][1])
        self.assertEqual('missing', audit['windowsStatusBeforeThisLayer']['v039'])
        self.assertEqual('missing', audit['windowsStatusBeforeThisLayer']['v040'])

    def test_generator_fails_closed_on_a_foreign_advanced_or_drifted_predecessor(self):
        for label, body in [('foreign', 'package x\n'), ('appended', self.off+'\n'), ('already advanced', self.on)]:
            with self.subTest(label=label), self.assertRaisesRegex(ValueError, 'predecessor changed'):
                history.advance_vm(REPO, body)
        drifts = [('header', 'class HistoryViewModel(environment: DesktopFavoriteEnvironment)', 'class HistoryViewModel(env: DesktopFavoriteEnvironment)', 'header changed'),
                  ('collector', history.WINDOWS_REFRESH, history.WINDOWS_REFRESH.replace('loadData(showLoading = false)', 'loadData()'), 'refresh collector changed'),
                  ('enrichment', 'private fun enrichHistoryProgress(', 'private fun enrichHistoryProgressX(', 'Ambiguous original declaration|anchor|Unbounded')]
        for label, old, new, message in drifts:
            body = self.off.replace(old, new, 1); self.assertNotEqual(self.off, body)
            with self.subTest(label=label), mock.patch.object(history, 'PREDECESSOR_SHA256', history.sha(body)):
                with self.assertRaisesRegex(ValueError, message): history.advance_vm(REPO, body)
        owned = self.off.replace('class HistoryViewModel(environment: DesktopFavoriteEnvironment) : BaseListViewModel(environment, "历史记录") {\n',
                                 'class HistoryViewModel(environment: DesktopFavoriteEnvironment) : BaseListViewModel(environment, "历史记录") {\n    fun setPageActive(active: Boolean) {}\n', 1)
        with mock.patch.object(history, 'PREDECESSOR_SHA256', history.sha(owned)):
            with self.assertRaisesRegex(ValueError, 'already owns'): history.advance_vm(REPO, owned)

    def test_original_members_come_only_from_the_pinned_editor_archive(self):
        for name in ['ListViewModel.kt', 'manifest.json', 'LICENSE', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-hip-') as temp:
                repo = Path(temp); root = repo/editor.ARCHIVE
                shutil.copytree(REPO/editor.ARCHIVE, root)
                file = root/name; file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(ValueError): history.original_members(repo)
        self.assertFalse((REPO/'desktop/upstream-slices/v046-history-in-place').exists())

    def test_real_producer_orders_the_layer_after_the_editor_and_before_the_final_audit_and_is_deterministic(self):
        source = (REPO/'desktop/tools/extract-upstream-favorites.py').read_text(encoding='utf8')
        editor_at = source.index("records[-1]['v046FavoriteEditorAdvance']=audit")
        layer_at = source.index("records[-1]['v046HistoryInPlaceAdvance']=audit")
        final_at = source.index("records[-1]['historyFinalGeneratedAdaptation']")
        self.assertLess(editor_at, layer_at); self.assertLess(layer_at, final_at)
        self.assertEqual(1, source.count("'v046FavoriteEditorOriginal','v046HistoryInPlaceAdvance']"))  # audit allow-list
        # The final History audit describes the actual output including this layer.
        final = self.row['historyFinalGeneratedAdaptation']
        self.assertEqual(history.sha(self.on), final['generatedSha256LfUtf8'])
        again = self.owner.generate(REPO, self.root/'again', True, self.root/'again-tests')
        self.assertEqual(tree(self.root/'on'), tree(self.root/'again'))
        self.assertEqual(tree(self.root/'on-tests'), tree(self.root/'again-tests'))
        repeated = next(row for row in again if any(x['path'].endswith('/DesktopOriginalListViewModels.kt') for x in row['outputs']))
        self.assertEqual(json.dumps(self.proof, sort_keys=True), json.dumps(repeated['v046HistoryInPlaceAdvance'], sort_keys=True))

    def test_gradle_tracks_the_new_generator_in_the_one_producer_that_runs_it(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        self.assertEqual(1, build.count('inputs.file("tools/v046_history_in_place.py")'))
        at = build.index('val extractOriginalFavorites by tasks.registering(')
        block = build[at:build.index('\n}', at)]
        self.assertIn('inputs.file("tools/v046_history_in_place.py")', block)
        self.assertIn('inputs.dir("upstream-slices/v046-favorite-editor")', block)
        # No new archive, source root or canonical exclusion is introduced by this layer.
        self.assertEqual(0, build.count('upstream-slices/v046-history-in-place'))

    def test_windows_host_swaps_the_per_activation_reload_for_the_browsing_session(self):
        host = (MAIN/'ui/DesktopOriginalPersonalListHost.kt').read_text(encoding='utf8')
        self.assertNotIn('.loadData(', host)
        for token in ['val historyModel = model as? HistoryViewModel', 'LocalDesktopHistoryBrowsingSessionKey.current',
                      'DisposableEffect(historyModel, isCurrentPage) {',
                      'historyModel?.bindDesktopPageActivity(entry.owns(), isCurrentPage)',
                      'onDispose { historyModel?.bindDesktopPageActivity(entryOwned = false, active = false) }',
                      'LaunchedEffect(historyModel, isCurrentPage, historySessionKey) {',
                      'historyModel?.enterDesktopBrowsingSession(entry.owns(), isCurrentPage, historySessionKey)',
                      # Contracts other tests already pin on this host stay intact.
                      'LocalDesktopPersonalRecapBindings provides recap',
                      '{ query, _ -> if (entry.owns()) onOpenHistorySearch(query) }']:
            self.assertEqual(1, host.count(token), token)
        binding = (MAIN/'ui/DesktopHistoryBrowsingSession.kt').read_text(encoding='utf8')
        for token in ['internal class DesktopHistoryBrowsingVisit', 'visit.takeIf { pagerHosted }',
                      'staticCompositionLocalOf<Any?> { null }', 'setPageActive(active && entryOwned)',
                      'catch (_: CancellationException)', 'if (active && entryOwned) enterBrowsingSession(sessionKey)']:
            self.assertEqual(1, binding.count(token), token)
        stack = (MAIN/'ui/DesktopOriginalRootStack.kt').read_text(encoding='utf8')
        self.assertEqual(1, stack.count('val historyVisit = remember(currentItem) { DesktopHistoryBrowsingVisit() }'))
        self.assertEqual(1, stack.count('LocalDesktopHistoryBrowsingSessionKey provides desktopHistoryBrowsingSessionKey(historyVisit, pagerHosted)'))
        self.assertLess(stack.index('val currentItem = resolveBottomPagerItemForPage('), stack.index('val historyVisit ='))
        self.assertLess(stack.index('val historyVisit ='), stack.index('val renderPage: @Composable'))

    def test_shell_no_longer_invalidates_a_retained_history_page_and_keeps_only_the_original_suppression(self):
        refresh = (MAIN/'ui/DesktopHistoryRefresh.kt').read_text(encoding='utf8')
        self.assertIn('fun DesktopHistoryRefreshEffects(videoDetailsVisible: Boolean) {', refresh)
        self.assertNotIn('invalidateCloudHistory', refresh); self.assertNotIn('HistoryRefreshBus', refresh)
        self.assertEqual(1, refresh.count('HistoryRefreshSuppression.suppress()'))
        self.assertEqual(1, refresh.count('HistoryRefreshSuppression.resume()'))
        shell = (MAIN/'DesktopShell.kt').read_text(encoding='utf8')
        self.assertEqual(1, shell.count('DesktopHistoryRefreshEffects(showVideo && playing.details != null)'))
        self.assertEqual(0, shell.count('DesktopHistoryRefreshEffects(browseMemory'))
        # The retained cloud-history page that the old subscriber invalidated has no live caller.
        callers = [p for p in (REPO/'desktop/src/main').rglob('*.kt')
                   if p.name != 'DesktopBrowseMemory.kt' and 'invalidateCloudHistory' in p.read_text(encoding='utf8')]
        self.assertEqual([], callers)
        screens = [p.name for p in (REPO/'desktop/src/main').rglob('*.kt')
                   if p.name != 'PersonalScreens.kt' and 'PersonalContentScreen(' in p.read_text(encoding='utf8')]
        self.assertEqual([], screens)

    def test_new_top_level_names_have_one_owner_in_the_compile_universe(self):
        names = ['DesktopHistoryBrowsingVisit', 'desktopHistoryBrowsingSessionKey', 'LocalDesktopHistoryBrowsingSessionKey',
                 'bindDesktopPageActivity', 'enterDesktopBrowsingSession']
        pattern = {name: re.compile(r'(?m)^\s*(?:internal |private |public )*(?:class|fun|val)\s+(?:[\w.<>?]+\.)?'+name+r'\b') for name in names}
        found = {name: [] for name in names}
        for base in [REPO/'desktop/src/main', self.root/'on']:
            for file in base.rglob('*.kt'):
                text = file.read_text(encoding='utf8')
                for name in names:
                    found[name] += [file.name]*len(pattern[name].findall(text))
        self.assertEqual({name: ['DesktopHistoryBrowsingSession.kt'] for name in names}, found)

    def test_changed_kotlin_keeps_balanced_delimiters_and_the_jvm_regression_counts(self):
        parser = load('sync-upstream.py')
        files = [MAIN/'ui/DesktopHistoryBrowsingSession.kt', MAIN/'ui/DesktopOriginalPersonalListHost.kt',
                 MAIN/'ui/DesktopOriginalRootStack.kt', MAIN/'ui/DesktopHistoryRefresh.kt',
                 TEST/'com/android/purebilibili/feature/list/DesktopHistoryInPlaceProgressTest.kt',
                 TEST/'com/bilipai/desktop/ui/DesktopHistoryRefreshTest.kt', self.root/'on'/VM]
        for file in files:
            stack = []
            for token, first, _ in parser.kotlin_tokens(file.read_text(encoding='utf8')):
                if token in ['(', '[', '{']: stack.append(token)
                elif token in [')', ']', '}']:
                    self.assertTrue(stack, (file.name, first))
                    self.assertEqual({')':'(', ']':'[', '}':'{'}[token], stack.pop(), (file.name, first))
            self.assertEqual([], stack, file.name)
        progress = files[4].read_text(encoding='utf8'); refresh = files[5].read_text(encoding='utf8')
        self.assertEqual(6, progress.count('@Test fun ')); self.assertEqual(7, refresh.count('@Test fun '))
        self.assertNotIn('persistentShellSubscriberInvalidatesHistory', refresh)
        # The in-place test proves both halves: no request for the order, and the progress change itself.
        for token in ['assertEquals(1, h.cursorRequests().size)', 'assertEquals(listOf("a", "b", "c"), h.order())',
                      'h.positions["a"] = 60_000L', 'unplanned']:
            self.assertIn(token, progress)
        self.assertEqual(0, progress.count('http://') + progress.count('127.0.0.1') + progress.count('localhost'))


if __name__ == '__main__':
    unittest.main()
