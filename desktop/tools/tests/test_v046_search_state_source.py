from pathlib import Path
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v041_search_all as v41
import v042_search_layout as v42
import v046_search_state as layer
from v025_source_paths import canonical_source
from v042_search_dependencies import SEARCH_MOTION, fixed_sources as v042_dependency_sources

DESKTOP = REPO / 'desktop/src'
PREFERENCES = DESKTOP / 'main/kotlin/com/bilipai/desktop/data/DesktopSearchPreferences.kt'
ENVIRONMENT = DESKTOP / 'main/kotlin/com/bilipai/desktop/ui/DesktopOriginalSearchEnvironment.kt'
CATALOG = DESKTOP / 'main/kotlin/com/bilipai/desktop/settings/DesktopSettingsShareWindowsCatalog.kt'
RECORDING_TEST = DESKTOP / 'test/kotlin/com/bilipai/desktop/data/DesktopSearchHistoryRecordingTest.kt'
STATE_TEST = DESKTOP / 'test/kotlin/com/bilipai/desktop/settings/DesktopSearchStateSettingsTest.kt'
SEARCH_DIR = 'com/android/purebilibili/feature/search/'


def load(filename):
    spec = importlib.util.spec_from_file_location('search_state_' + filename.replace('-', '_').replace('.', '_'),
                                                  REPO / 'desktop/tools' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def whole_inverse(text, proof):
    """Peel one v029-style indexed whole-body proof (the producer's tab ABI step)."""
    assert sha(text) == proof['afterSha256LF']
    for edit in reversed(proof['indexedEdits']):
        at = edit['afterOffset']
        assert text[at:at + len(edit['after'])] == edit['after']
        text = text[:at] + edit['before'] + text[at + len(edit['after']):]
    assert sha(text) == proof['beforeSha256LF']
    return text


def kotlin_files(root):
    return {path.relative_to(root).as_posix(): path.read_bytes() for path in Path(root).rglob('*.kt')}


def top_level_declarations(syntax, text):
    """(kind, receiver, name) of every top-level declaration, read from the same tokens the other pins use."""
    tokens = [token for token, _, _ in syntax.kotlin_tokens(text)]
    found, depth = [], 0
    for index, token in enumerate(tokens):
        if token in ('(', '[', '{'):
            depth += 1
        elif token in (')', ']', '}'):
            depth -= 1
        elif depth == 0 and token in ('fun', 'class', 'object', 'interface', 'typealias', 'val'):
            if token == 'fun':
                start = index + 1
                if tokens[start] == '<':
                    start = tokens.index('>', start) + 1
                path = [part for part in tokens[start:tokens.index('(', start)] if part != '.']
                found.append((token, path[-2] if len(path) > 1 else None, path[-1]))
            else:
                found.append((token, None, tokens[index + 1]))
    return found


def declaration_pattern(kind, receiver, name):
    if kind == 'fun':
        return r'\bfun\s+(?:<[^>]*>\s*)?' + (re.escape(receiver) + r'\.' if receiver else '') + re.escape(name) + r'\('
    return r'\b' + kind + r'\s+' + re.escape(name) + r'\b'


class SearchStateSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Short scratch root: the producer writes deep package paths (Windows MAX_PATH).
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-ss-')
        cls.root = Path(cls.temp.name)
        cls.producer = load('extract-upstream-search-pages.py')
        cls.sources, cls.manifest = layer.fixed(REPO)
        cls.rows = cls.producer.generate(REPO, cls.root / 'main', cls.root / 'tests')
        cls.main, cls.tests = cls.root / 'main', cls.root / 'tests'
        # The same producer with exactly this layer patched out: the previous stage, for before/after checks.
        with mock.patch.object(layer, 'advance_page', lambda repo, item, before: (before, None)), \
                mock.patch.object(layer, 'emit_policies', lambda repo, output, test_output: None):
            cls.base_rows = cls.producer.generate(REPO, cls.root / 'base-main', cls.root / 'base-tests')

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def row(self, path):
        return next(row for row in self.rows if row['source'] == path)

    def item(self, path):
        return next(item for item in self.producer.SPEC if item['path'] == path)

    def output(self, name):
        return (self.main / (SEARCH_DIR + name)).read_text(encoding='utf8')

    # -- archive ---------------------------------------------------------------------------------

    def test_archive_matches_the_local_upstream_objects_and_the_manifest(self):
        self.assertEqual(layer.COMMIT, self.manifest['commit'])
        self.assertEqual('v0.4.6', self.manifest['tag'])
        self.assertEqual(8, len(self.manifest['files']))
        self.assertEqual(layer.PATHS, tuple(row['path'] for row in self.manifest['files']))
        self.assertEqual(layer.MANIFEST_SHA256, sha((REPO / layer.ARCHIVE / 'manifest.json').read_bytes()))
        for row in self.manifest['files']:
            leaf = (REPO / layer.ARCHIVE / row['filename']).read_bytes()
            upstream = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob',
                                       layer.COMMIT + ':' + row['path']], capture_output=True, check=True).stdout
            self.assertEqual(upstream, leaf, row['path'])
            self.assertNotIn(b'\r', leaf)
            self.assertEqual(row['gitBlob'], hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest())
        licence = subprocess.run(['git', '-C', str(REPO), 'cat-file', 'blob', layer.COMMIT + ':LICENSE'],
                                 capture_output=True, check=True).stdout
        self.assertEqual(licence, (REPO / layer.ARCHIVE / 'LICENSE').read_bytes())

    def test_every_changed_added_or_missing_archive_leaf_fails_before_any_output(self):
        with tempfile.TemporaryDirectory(prefix='bp-ss-tamper-') as folder:
            for name in sorted(layer.EXPECTED | {'manifest.json', 'LICENSE'}):
                root = Path(folder) / name.replace('.', '-')
                shutil.copytree(REPO / layer.ARCHIVE, root / layer.ARCHIVE)
                leaf = root / layer.ARCHIVE / name
                leaf.write_bytes(leaf.read_bytes() + b'\n// changed\n')
                with self.assertRaises(ValueError, msg=name):
                    layer.fixed(root)
            root = Path(folder) / 'extra'
            shutil.copytree(REPO / layer.ARCHIVE, root / layer.ARCHIVE)
            (root / layer.ARCHIVE / 'Extra.kt').write_text('package x\n', encoding='utf8')
            with self.assertRaisesRegex(ValueError, 'leaves changed'):
                layer.fixed(root)
            root = Path(folder) / 'missing'
            shutil.copytree(REPO / layer.ARCHIVE, root / layer.ARCHIVE)
            (root / layer.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaisesRegex(ValueError, 'leaves changed'):
                layer.fixed(root)

    # -- predecessor pins ------------------------------------------------------------------------

    def test_manifest_predecessor_pins_are_the_real_previous_stage_bodies(self):
        before = self.manifest['beforeFinalOutputs']
        for path in (layer.SCREEN, layer.FILTER_SHEET):
            canonical = v41.baseline(REPO, path)
            body, _ = v42.adapt_page(REPO, self.item(path), canonical)
            self.assertEqual(before[path]['sha256'], sha(body), path)
        canonical = v41.baseline(REPO, layer.VIEW_MODEL)
        body, _ = v41.adapt_page(REPO, self.item(layer.VIEW_MODEL), canonical)
        self.assertEqual(before[layer.VIEW_MODEL]['sha256'], sha(body))
        # The landing page is the retained v0.2.5 canonical file plus its four counted same-window edits.
        item = self.item(layer.LANDING)
        body = canonical_source(REPO, item['path']).read_text(encoding='utf8').replace('\r\n', '\n')
        self.assertEqual(item['inputSha256LF'], sha(body))
        for op in item['operations']:
            body = body[:op['offset']] + op['after'] + body[op['offset'] + len(op['before']):]
        self.assertEqual(item['outputSha256LF'], sha(body))
        self.assertEqual(before[layer.LANDING]['sha256'], sha(body))
        originals, _ = v042_dependency_sources(REPO)
        self.assertEqual(before[layer.MOTION_POLICY]['sha256'], sha(originals[SEARCH_MOTION]))
        motion_test = next(path for path in originals if path.endswith('SearchMotionBudgetPolicyTest.kt'))
        self.assertEqual(before[layer.MOTION_TEST]['sha256'], sha(originals[motion_test]))

    def test_the_page_outputs_equal_their_pins_and_the_filter_sheet_stays_the_v041_body(self):
        after = self.manifest['afterFinalOutputs']
        self.assertEqual(set(layer.PAGES), set(after))
        self.assertEqual((layer.FILTER_SHEET,), layer.WITHHELD_PAGES)
        for path in layer.PAGES:
            audit = self.row(path)['v046SearchStateAdvance']
            self.assertEqual(self.manifest['beforeFinalOutputs'][path]['sha256'], audit['predecessorSHA256'], path)
            self.assertEqual(sha(self.sources[path]), audit['originalSHA256'], path)
            self.assertEqual(layer.COMMIT, audit['upstreamCommit'])
            self.assertEqual(layer.MANIFEST_SHA256, audit['sourceManifestSHA256'])
            self.assertFalse(audit['jvmTestsExecuted'])
            if path == layer.FILTER_SHEET:
                # The manifest's after pin of the sheet is the archived whole v0.4.6 file: the withheld candidate.
                self.assertEqual(after[path]['sha256'], audit['withheldOriginalSHA256'])
                self.assertEqual(sha(self.sources[path]), audit['withheldOriginalSHA256'])
                self.assertEqual(audit['predecessorSHA256'], audit['advancedSHA256'])
                self.assertTrue(audit['retainedPredecessor'])
                self.assertFalse(audit['wholeOriginalSelected'])
                self.assertEqual([], audit['retainedToCurrent']['hunks'])
            elif path == layer.SCREEN:
                # The manifest pins the plain whole-v0.4.6 replay; the generator pins the replay plus the toggle hunk.
                self.assertEqual(after[path]['sha256'], audit['recipeReplaySHA256'])
                self.assertEqual(layer.SCREEN_WITH_TOGGLE_SHA256, audit['advancedSHA256'])
                self.assertNotEqual(audit['recipeReplaySHA256'], audit['advancedSHA256'])
                self.assertTrue(audit['layoutToggleRetained'] and audit['wholeOriginalSelected'])
            else:
                self.assertEqual(after[path]['sha256'], audit['advancedSHA256'], path)
                self.assertTrue(audit['wholeOriginalSelected'], path)
        self.assertTrue(self.row(layer.SCREEN)['v046SearchStateAdvance']['completeCurrentOriginalScreen'])

    # -- inverse proofs --------------------------------------------------------------------------

    def final_layer_body(self, path):
        """The producer output with the later tab ABI step peeled (VM and landing have no later step)."""
        row = self.row(path)
        body = Path(row['target']).read_text(encoding='utf8')
        if row['windowsTabAbiAdaptation'] is not None:
            body = whole_inverse(body, row['windowsTabAbiAdaptation'])
        return row, body

    def test_every_page_restores_the_whole_v046_original_and_its_predecessor(self):
        for path in layer.PAGES:
            row, body = self.final_layer_body(path)
            audit = row['v046SearchStateAdvance']
            self.assertEqual(audit['advancedSHA256'], sha(body), path)
            self.assertEqual(self.sources[path], layer.restore(body, audit['originalToWindows']), path)
            previous = layer.restore(body, audit['retainedToCurrent'])
            self.assertEqual(self.manifest['beforeFinalOutputs'][path]['sha256'], sha(previous), path)
        # The edit set that turns a v0.4.2 screen into the v0.4.6 screen stays small and reviewable.
        hunks = self.row(layer.SCREEN)['v046SearchStateAdvance']['retainedToCurrent']['hunks']
        self.assertLessEqual(len(hunks), 16)
        self.assertLessEqual(sum(h['afterLines'][1] - h['afterLines'][0] for h in hunks), 80)

    def test_the_layer_changes_only_the_intended_generated_files(self):
        current, previous = kotlin_files(self.main), kotlin_files(self.root / 'base-main')
        changed = {name for name in current if name in previous and current[name] != previous[name]}
        added = set(current) - set(previous)
        self.assertEqual(set(), set(previous) - set(current))
        # The filter sheet is withheld: it is byte-identical to the previous stage and absent from the changed set.
        self.assertEqual({SEARCH_DIR + name for name in ('SearchViewModel.kt', 'SearchScreen.kt', 'SearchLandingUi.kt',
                                                        'SearchMotionBudgetPolicy.kt')}, changed)
        self.assertEqual(previous[SEARCH_DIR + 'SearchVideoFilterSheet.kt'], current[SEARCH_DIR + 'SearchVideoFilterSheet.kt'])
        self.assertEqual({SEARCH_DIR + 'SearchContentStatePolicy.kt'}, added)
        current_tests, previous_tests = kotlin_files(self.tests), kotlin_files(self.root / 'base-tests')
        self.assertEqual({SEARCH_DIR + 'SearchMotionBudgetPolicyTest.kt'},
                         {name for name in current_tests if name in previous_tests and current_tests[name] != previous_tests[name]})
        self.assertEqual({SEARCH_DIR + 'SearchContentStatePolicyTest.kt'}, set(current_tests) - set(previous_tests))
        self.assertEqual(set(), set(previous_tests) - set(current_tests))
        # Every untouched file, including the trending pages and the repository, is byte-identical.
        self.assertGreaterEqual(len(set(current) & set(previous)) - len(changed), 10)

    def test_policy_outputs_are_the_whole_originals_and_recover_the_v042_emission(self):
        proof = json.loads((self.main / layer.PROOF_NAME).read_bytes())
        self.assertEqual(layer.COMMIT, proof['upstreamCommit'])
        self.assertFalse(proof['jvmTestsExecuted'] or proof['mainCompiled'] or proof['pixelsValidated'])
        rows = {row['path']: row for row in proof['policies']}
        self.assertEqual({layer.CONTENT_POLICY, layer.MOTION_POLICY, layer.MOTION_TEST, layer.CONTENT_TEST}, set(rows))
        for path, name, root in ((layer.CONTENT_POLICY, 'SearchContentStatePolicy.kt', self.main),
                                 (layer.MOTION_POLICY, 'SearchMotionBudgetPolicy.kt', self.main),
                                 (layer.MOTION_TEST, 'SearchMotionBudgetPolicyTest.kt', self.tests),
                                 (layer.CONTENT_TEST, 'SearchContentStatePolicyTest.kt', self.tests)):
            self.assertEqual(self.sources[path].encode('utf8'), (root / (SEARCH_DIR + name)).read_bytes(), path)
        for path in (layer.MOTION_POLICY, layer.MOTION_TEST):
            old = (self.root / ('base-main' if path == layer.MOTION_POLICY else 'base-tests') /
                   (SEARCH_DIR + Path(path).name)).read_text(encoding='utf8')
            self.assertEqual(old, layer.restore(self.sources[path], rows[path]['retainedToCurrent']), path)
            self.assertEqual(self.manifest['beforeFinalOutputs'][path]['sha256'], rows[path]['predecessorSHA256'])
        self.assertEqual({'motion': 18, 'content': 16}, proof['originalTestCount'])

    def test_emitted_original_tests_are_exact_and_stay_outside_the_main_root(self):
        self.assertEqual([], [name for name in kotlin_files(self.main) if name.endswith('Test.kt')])
        for path, count in ((layer.MOTION_TEST, 18), (layer.CONTENT_TEST, 16)):
            self.assertEqual(count, self.sources[path].count('@Test'), path)
            self.assertNotIn('org.junit', self.sources[path])
        self.assertIn('withEditedSearchQuery', self.sources[layer.CONTENT_TEST])
        self.assertIn('shouldRecordSearchHistory', self.sources[layer.CONTENT_TEST])
        self.assertNotIn('shouldKeepResultsOnQueryChange', self.sources[layer.MOTION_TEST])
        self.assertIn('RETURN_TO_LANDING', self.sources[layer.MOTION_TEST])

    # -- fail-closed drift -----------------------------------------------------------------------

    def test_foreign_advanced_and_unknown_predecessors_fail_before_any_output(self):
        for path in layer.PAGES:
            item = self.item(path)
            row, advanced = self.final_layer_body(path)
            previous = layer.restore(advanced, row['v046SearchStateAdvance']['retainedToCurrent'])
            self.assertEqual(advanced, layer.advance_page(REPO, item, previous)[0], path)
            # A withheld page returns its predecessor unchanged, so the "already advanced" body is the valid predecessor.
            bodies = (previous + '\n', '') if path in layer.WITHHELD_PAGES else (previous + '\n', advanced, '')
            for bad in bodies:
                with self.assertRaisesRegex(ValueError, 'predecessor changed', msg=path):
                    layer.advance_page(REPO, item, bad)
        with self.assertRaisesRegex(ValueError, 'Unknown search state page'):
            layer.advance_page(REPO, dict(self.item(layer.VIEW_MODEL), path='app/x/SearchTrendingScreen.kt'), '')

    def test_recipe_and_anchor_drift_fail_closed(self):
        original = self.sources[layer.VIEW_MODEL]
        for name in ('isSearchHistoryRecordingEnabledSync', 'isPrivacyModeEnabledSync'):
            anchor = 'com.android.purebilibili.core.store.SettingsManager\n            .' + name + '(application)'
            self.assertEqual(1, original.count(anchor))
            with self.assertRaisesRegex(ValueError, 'initial settings read changed'):
                layer.adapt(REPO, self.item(layer.VIEW_MODEL), original.replace(anchor, 'x'))
        item = self.item(layer.LANDING)
        with self.assertRaisesRegex(ValueError, 'landing recipe changed'):
            layer.adapt(REPO, dict(item, operations=item['operations'][:3]), self.sources[layer.LANDING])
        with self.assertRaisesRegex(ValueError, 'landing anchor changed'):
            layer.adapt(REPO, item, self.sources[layer.LANDING].replace('    val outline = MaterialTheme.colorScheme.outline\n', ''))
        item = self.item(layer.SCREEN)
        with self.assertRaisesRegex(ValueError, 'duplicate Miuix import'):
            layer.adapt(REPO, item, layer.OBSOLETE_IMPORT + '\n' + self.sources[layer.SCREEN])
        with self.assertRaisesRegex(ValueError, 'monotonic clock boundary'):
            layer.adapt(REPO, item, self.sources[layer.SCREEN] + '\n// ' + layer.CLOCK + '\n')
        with self.assertRaisesRegex(ValueError, 'recipe changed'):
            layer.adapt(REPO, dict(item, operations=[op for op in item['operations'] if op['before'] != layer.OBSOLETE_IMPORT]),
                        self.sources[layer.SCREEN])

    def test_policy_emission_requires_the_exact_v042_predecessors_and_no_second_emitter(self):
        with tempfile.TemporaryDirectory(prefix='bp-ss-policy-') as folder:
            def fresh(name):
                target = Path(folder) / name
                shutil.copytree(self.root / 'base-main', target / 'main')
                shutil.copytree(self.root / 'base-tests', target / 'tests')
                return target / 'main', target / 'tests'
            main, tests = fresh('ok')
            layer.emit_policies(REPO, main, tests)
            for name in ('SearchContentStatePolicy.kt', 'SearchMotionBudgetPolicy.kt'):
                self.assertEqual((self.main / (SEARCH_DIR + name)).read_bytes(), (main / (SEARCH_DIR + name)).read_bytes(), name)
            for name in ('SearchContentStatePolicyTest.kt', 'SearchMotionBudgetPolicyTest.kt'):
                self.assertEqual((self.tests / (SEARCH_DIR + name)).read_bytes(), (tests / (SEARCH_DIR + name)).read_bytes(), name)
            main, tests = fresh('content')
            (main / (SEARCH_DIR + 'SearchContentStatePolicy.kt')).write_text('package x\n', encoding='utf8')
            with self.assertRaisesRegex(ValueError, 'already has an emitter'):
                layer.emit_policies(REPO, main, tests)
            main, tests = fresh('motion')
            (main / (SEARCH_DIR + 'SearchMotionBudgetPolicy.kt')).write_text('package changed\n', encoding='utf8')
            with self.assertRaisesRegex(ValueError, 'motion policy predecessor changed'):
                layer.emit_policies(REPO, main, tests)
            main, tests = fresh('motion-missing')
            (main / (SEARCH_DIR + 'SearchMotionBudgetPolicy.kt')).unlink()
            with self.assertRaisesRegex(ValueError, 'was not emitted'):
                layer.emit_policies(REPO, main, tests)
            main, tests = fresh('test')
            with (tests / (SEARCH_DIR + 'SearchMotionBudgetPolicyTest.kt')).open('a', encoding='utf8', newline='\n') as handle:
                handle.write('// changed\n')
            with self.assertRaisesRegex(ValueError, 'motion test predecessor changed'):
                layer.emit_policies(REPO, main, tests)

    def test_output_overlapping_the_archive_is_rejected_and_leaves_it_untouched(self):
        archive = REPO / layer.ARCHIVE
        before = {path.name: path.read_bytes() for path in archive.iterdir()}
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.producer.generate(REPO, archive / 'child')
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.producer.generate(REPO, self.root / 'elsewhere', archive)
        self.assertEqual(before, {path.name: path.read_bytes() for path in archive.iterdir()})

    def test_generation_is_deterministic(self):
        again = self.root / 'again'
        self.producer.generate(REPO, again / 'main', again / 'tests')
        self.assertEqual(kotlin_files(self.main), kotlin_files(again / 'main'))
        self.assertEqual(kotlin_files(self.tests), kotlin_files(again / 'tests'))

    # -- generated Kotlin contract ---------------------------------------------------------------

    def test_view_model_binds_the_new_settings_to_the_desktop_owned_view_and_keeps_every_guard(self):
        body = self.output('SearchViewModel.kt')
        for token in ('val submittedQuery: String = query.trim(),', 'val recordSearchHistory: Boolean = true,',
                      'val globalPrivacyMode: Boolean = false,', 'query = submittedQuery,',
                      'DesktopOriginalSearchSettings.isSearchHistoryRecordingEnabledSync(application)',
                      'DesktopOriginalSearchSettings.isPrivacyModeEnabledSync(application)',
                      'DesktopOriginalSearchSettings.getSearchHistoryRecordingEnabled(application)',
                      'DesktopOriginalSearchSettings.getPrivacyModeEnabled(application)',
                      'DesktopOriginalSearchSettings.setSearchHistoryRecordingEnabled(\n                application, enabled,',
                      'fun toggleSearchHistoryRecording()', 'fun search(keyword: String, updateDraft: Boolean = true)',
                      'it.withEditedSearchQuery(newQuery)', 'shouldRecordSearchHistory(_uiState.value.recordSearchHistory'):
            self.assertIn(token, body)
        for token in ('core.store.SettingsManager', 'getApplication', 'android.widget', 'android.app', 'AndroidViewModel',
                      'shouldKeepResultsOnQueryChange', 'AppDatabase', 'Toast'):
            self.assertNotIn(token, body)
        self.assertEqual(2, body.count('application.assertCurrent()\n                            val nativeFiltered = aggregate.videos'))
        self.assertIn('internal class SearchViewModel(private val application: DesktopOriginalSearchEnvironment) : AutoCloseable {', body)
        self.assertEqual(1, body.count('fun toggleSearchHistoryRecording('))
        # Every settings call the view model makes exists on the one desktop-owned view.
        environment = ENVIRONMENT.read_text(encoding='utf8')
        view = environment[environment.index('internal object DesktopOriginalSearchSettings'):]
        for name in sorted(set(re.findall(r'DesktopOriginalSearchSettings\.(\w+)\(', body))):
            self.assertRegex(view, r'fun ' + name + r'\(', name)

    def test_screen_returns_to_the_landing_keeps_results_on_edit_and_wires_the_history_switch(self):
        body = self.output('SearchScreen.kt')
        for token in ('SearchBackAction.RETURN_TO_LANDING -> {', 'restoreLandingFocusOnBack', 'pendingLandingFocusRestore',
                      'val clearSearchQuery = {\n        updateSearchQuery("")\n    }',
                      'recordSearchHistory = state.recordSearchHistory,', 'globalPrivacyMode = state.globalPrivacyMode,',
                      'onToggleHistoryRecording = viewModel::toggleSearchHistoryRecording,',
                      'SearchResultPageUiState(query = state.submittedQuery)', 'androidx.compose.ui.text.input.TextFieldValue.Saver',
                      'import com.bilipai.desktop.ui.DesktopOriginalSearchSettings as SettingsManager',
                      'internal fun SearchScreen(', '(System.nanoTime() / 1_000_000L)'):
            self.assertIn(token, body)
        self.assertEqual(2, body.count('viewModel.search(pageResultState.query, updateDraft = false)'))
        for token in ('viewModel.onQueryChange("")\n        viewModel.exitResultsToLanding()', 'android.os.SystemClock',
                      'LocalContext', 'LocalConfiguration', 'stringResource('):
            self.assertNotIn(token, body)
        # Windows keeps the single/multi-column toggle: the retained sheet still takes it and the screen still passes it.
        call = body[body.index('SearchVideoFilterActions('):][:500]
        self.assertIn('singleColumn = listLayout.singleColumn,', call)
        self.assertIn('onLayoutToggle = listLayout.toggle,', call)
        sheet = self.output('SearchVideoFilterSheet.kt')
        actions = sheet[sheet.index('fun SearchVideoFilterActions('):]
        self.assertIn('onLayoutToggle: () -> Unit', actions[:actions.index(') {')])
        self.assertIn('fun SearchVideoFilterBar(', sheet)

    def test_the_screen_keeps_the_layout_toggle_with_one_counted_hunk_and_the_sheet_is_withheld(self):
        screen = self.output('SearchScreen.kt')
        sheet = self.output('SearchVideoFilterSheet.kt')
        # The call head is the previous stage's call head, byte for byte.
        base = (self.root / 'base-main' / (SEARCH_DIR + 'SearchScreen.kt')).read_text(encoding='utf8')
        base_call = base[base.index('SearchVideoFilterActions('):]
        call = screen[screen.index('SearchVideoFilterActions('):]
        self.assertEqual(base_call[:base_call.index('currentVideoTid')], call[:call.index('currentVideoTid')])
        self.assertEqual('SearchVideoFilterActions(\n' + layer.LAYOUT_TOGGLE_ARGUMENTS + layer.FILTER_CALL_INDENT +
                         'currentDurations = state.searchDurations,\n' + layer.FILTER_CALL_INDENT,
                         call[:call.index('currentVideoTid')])
        self.assertEqual(1, screen.count('onLayoutToggle = listLayout.toggle,'))
        self.assertNotIn('showLayoutToggle', screen)
        # Everything the call passes is declared by the retained sheet; showLayoutToggle keeps its default.
        for declaration in ('singleColumn: Boolean,', 'onLayoutToggle: () -> Unit,', 'showLayoutToggle: Boolean = true,'):
            self.assertEqual(1, sheet.count(declaration), declaration)
        self.assertIn('VideoListLayoutToggle(', sheet)
        # The hunk is exactly two lines over the plain whole-v0.4.6 replay, which the manifest pins.
        item = self.item(layer.SCREEN)
        replay = layer.adapt(REPO, item, self.sources[layer.SCREEN])
        self.assertEqual(self.manifest['afterFinalOutputs'][layer.SCREEN]['sha256'], sha(replay))
        retained = layer.retain_layout_toggle(replay)
        self.assertEqual(2, layer.LAYOUT_TOGGLE_ARGUMENTS.count('\n'))
        self.assertEqual(replay, retained.replace(layer.LAYOUT_TOGGLE_ARGUMENTS, '', 1))
        self.assertEqual(layer.SCREEN_WITH_TOGGLE_SHA256, sha(retained))
        self.assertNotIn('onLayoutToggle', self.sources[layer.SCREEN])
        with self.assertRaisesRegex(ValueError, 'filter actions call changed'):
            layer.retain_layout_toggle(replay.replace(layer.LAYOUT_TOGGLE_ANCHOR, 'x'))
        with self.assertRaisesRegex(ValueError, 'filter actions call changed'):
            layer.retain_layout_toggle(retained)
        with self.assertRaisesRegex(ValueError, 'already passes the layout toggle'):
            layer.retain_layout_toggle(replay + '\n// onLayoutToggle\n')
        with self.assertRaisesRegex(ValueError, 'list layout control changed'):
            layer.retain_layout_toggle(replay.replace('val listLayout = rememberVideoListLayoutControl()', 'val listLayout = x'))
        # The sheet is the v0.4.1 body: advancing it is refused by adapt and guarded by retain_filter_sheet.
        _, body = self.final_layer_body(layer.FILTER_SHEET)
        self.assertEqual(self.manifest['beforeFinalOutputs'][layer.FILTER_SHEET]['sha256'], sha(body))
        self.assertEqual(body, layer.retain_filter_sheet(body))
        for broken in (body.replace(layer.SHEET_TOGGLE_PARAMETERS, ''), body.replace('if (showLayoutToggle) {', 'if (true) {'),
                       body.replace('onClick = onLayoutToggle,', 'onClick = {},')):
            with self.assertRaisesRegex(ValueError, 'no longer carries the layout toggle'):
                layer.retain_filter_sheet(broken)
        with self.assertRaisesRegex(ValueError, 'archived but withheld'):
            layer.adapt(REPO, self.item(layer.FILTER_SHEET), self.sources[layer.FILTER_SHEET])

    def test_the_withheld_sheet_delta_is_exactly_the_edits_of_commit_35e2fac8a(self):
        """The archived v0.4.6 sheet is the v0.4.1 sheet plus five counted edits: all of them belong to 35e2fac8a, none to cd86c9f13."""
        _, body = self.final_layer_body(layer.FILTER_SHEET)
        dock = '\n'.join([
            '        if (showLayoutToggle) {',
            '            BottomBarMatchedReusableLiquidDock(',
            '                shape = CircleShape,',
            '                backdrop = miuixBackdrop,',
            '                reuseEnabled = true,',
            '                elasticFeedbackEnabled = true,',
            '                useNeutralLiquidContainer = true,',
            '                shellLensIntensity = resolveFloatingDockGeometryScale(chromeSpec.clearActionSizeDp.toFloat()),',
            '                modifier = Modifier.size(actionSize),',
            '            ) { _ ->',
            '                VideoListLayoutToggle(',
            '                    singleColumn = singleColumn,',
            '                    onClick = onLayoutToggle,',
            '                    modifier = Modifier.size(actionSize),',
            '                    iconModifier = Modifier.size(iconSize),',
            '                )',
            '            }',
            '        }', ''])
        label_before = ('                text = label,\n'
                        '                style = MaterialTheme.typography.labelMedium,\n')
        label_after = ('                text = label,\n'
                       '                // Miuix owns its native chip fill and ignores Material chip colors.\n'
                       '                // Set the selection accent on the label for both renderers.\n'
                       '                color = if (selected) AppContentColors.accent() else AppContentColors.secondaryText(),\n'
                       '                style = MaterialTheme.typography.labelMedium,\n')
        edits = (('import com.android.purebilibili.core.ui.components.VideoListLayoutToggle\n', ''),
                 (layer.SHEET_TOGGLE_PARAMETERS, ''),
                 (dock, ''),
                 (label_before, label_after),
                 ('            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,\n'
                  '            selectedLabelColor = AppContentColors.primaryText(),\n',
                  '            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,\n'
                  '            selectedLabelColor = AppContentColors.accent(),\n'))
        derived = body
        for before, after in edits:
            self.assertEqual(1, derived.count(before), before[:60])
            derived = derived.replace(before, after, 1)
        self.assertEqual(self.sources[layer.FILTER_SHEET], derived)
        # The same fact through the recorded inverse proof of the withheld candidate.
        audit = self.row(layer.FILTER_SHEET)['v046SearchStateAdvance']
        self.assertEqual(self.sources[layer.FILTER_SHEET], layer.restore(body, audit['originalToWindows']))
        self.assertEqual(sha(self.sources[layer.FILTER_SHEET]), audit['withheldOriginalSHA256'])
        for text in (layer.__doc__,):
            for word in ('35e2fac8a', 'cd86c9f13', 'withheld', 'AppSearchEntry', 'AppLiquidAwareSearchField', 'LiquidSearchFieldHeightDp',
                         'retain_layout_toggle', 'v026-search-entry-centered-transition', 'chrome-tab-rails-and-geometry'):
                self.assertIn(word, text, word)

    def test_landing_has_the_larger_text_the_history_switch_and_the_same_window_binding(self):
        body = self.output('SearchLandingUi.kt')
        for token in ('recordSearchHistory: Boolean,', 'globalPrivacyMode: Boolean,', 'onToggleHistoryRecording: () -> Unit,',
                      'shouldRecordSearchHistory(recordSearchHistory, globalPrivacyMode)', '"全局无痕"', '"无痕搜索"', '"记录中"',
                      'import com.bilipai.desktop.ui.LocalDesktopOriginalSearchEnvironment',
                      'val configuration by LocalDesktopOriginalSearchEnvironment.current.configuration.collectAsState()',
                      'widthDp = configuration.screenWidthDp,', 'import androidx.compose.runtime.collectAsState',
                      'com.android.purebilibili.core.ui.components.AppLazyColumn as LazyColumn',
                      'import com.android.purebilibili.core.ui.AppContentColors', '.heightIn(min = 44.dp)'):
            self.assertIn(token, body)
        self.assertNotIn('LocalConfiguration', body)
        self.assertIn('MaterialTheme.typography.bodyMedium', body)
        self.assertEqual(1, body.count('val configuration by LocalDesktopOriginalSearchEnvironment.current.configuration.collectAsState()'))

    def test_policies_carry_the_search_only_gate_and_the_back_to_landing_contract(self):
        content = (self.main / (SEARCH_DIR + 'SearchContentStatePolicy.kt')).read_text(encoding='utf8')
        self.assertIn('internal fun shouldRecordSearchHistory(recordingEnabled: Boolean, globalPrivacyMode: Boolean): Boolean =\n'
                      '    recordingEnabled && !globalPrivacyMode', content)
        motion = (self.main / (SEARCH_DIR + 'SearchMotionBudgetPolicy.kt')).read_text(encoding='utf8')
        self.assertIn('RETURN_TO_LANDING,\n    LEAVE_SEARCH', motion)
        self.assertIn('internal fun SearchUiState.withEditedSearchQuery(newQuery: String): SearchUiState = copy(', motion)
        self.assertNotIn('shouldKeepResultsOnQueryChange', motion)

    def test_new_top_level_names_have_one_owner_and_the_canonical_copy_is_excluded(self):
        generated = [raw.decode('utf8') for raw in kotlin_files(self.main).values()]
        desktop = [path.read_text(encoding='utf8') for path in (DESKTOP / 'main').rglob('*.kt')]
        for pattern in (r'\bfun shouldRecordSearchHistory\(', r'\bfun SearchUiState\.withEditedSearchQuery\(',
                        r'\benum class SearchBackAction\b', r'\bfun resolveSearchBackAction\(',
                        r'\bfun toggleSearchHistoryRecording\(', r'\bdata class SearchUiState\('):
            owners = sum(len(re.findall(pattern, text)) for text in generated + desktop)
            self.assertEqual(1, owners, pattern)
        # Every top-level declaration of both whole policies, derived from the archived files (R-T2): one owner each across
        # the generated Search output and desktop/src/main, receivers included (the with* copies exist for two receivers).
        syntax = v41.parser(REPO)
        build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf8')
        declared = {}
        for name, path in (('SearchContentStatePolicy.kt', layer.CONTENT_POLICY), ('SearchMotionBudgetPolicy.kt', layer.MOTION_POLICY)):
            declared[path] = top_level_declarations(syntax, self.sources[path])
            for kind, receiver, simple in declared[path]:
                owners = sum(len(re.findall(declaration_pattern(kind, receiver, simple), text)) for text in generated + desktop)
                self.assertEqual(1, owners, (name, kind, receiver, simple))
        found = {simple for rows in declared.values() for _, _, simple in rows}
        for simple in ('SearchResultBodyMode', 'SearchResultFooterMode', 'SearchLandingSectionMode', 'SearchResultPresentation',
                       'shouldLoadMoreSearchVideos', 'withLoadMoreStarted', 'withLoadMoreFailure', 'resolveSearchResultPresentation',
                       'resolveSearchLandingSectionMode', 'shouldRecordSearchHistory', 'SearchMotionBudget', 'SearchBackAction',
                       'withEditedSearchQuery', 'SEARCH_LANDING_KEYWORD_CLICK_GUARD_MS'):
            self.assertIn(simple, found, simple)
        self.assertIn(('fun', 'SearchResultPageUiState', 'withLoadMoreStarted'), declared[layer.CONTENT_POLICY])
        self.assertIn(('fun', 'SearchUiState', 'withLoadMoreStarted'), declared[layer.CONTENT_POLICY])
        for name, path in (('SearchContentStatePolicy.kt', layer.CONTENT_POLICY), ('SearchMotionBudgetPolicy.kt', layer.MOTION_POLICY)):
            self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("' + path + '"))'), name)
            # The excluded vendored copy is the older v0.2.5 file: compiling both would duplicate every name it shares.
            canonical = (REPO / path).read_bytes().decode('utf8').replace('\r\n', '\n')
            self.assertNotEqual(self.sources[path], canonical)
            old = set(top_level_declarations(syntax, canonical))
            self.assertTrue(old & set(declared[path]), name)
            # The archive supersedes every name the old copy declares, except the one policy v0.4.6 removed.
            expected = set() if path == layer.CONTENT_POLICY else {('fun', None, 'shouldKeepResultsOnQueryChange')}
            self.assertEqual(expected, old - set(declared[path]), name)

    # -- registration ----------------------------------------------------------------------------

    def test_task_inputs_and_catalog_rows_cover_the_one_producer(self):
        build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf8')
        start = build.index('val extractOriginalSearchPages by tasks.registering(')
        block = build[start:build.index('\n}', start)]
        self.assertEqual(1, block.count('inputs.file("tools/v046_search_state.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-search-state")'))
        self.assertEqual(1, build.count('upstream-slices/v046-search-state"'))
        # The generator imports these two helpers (inverse_proof, need): a change to either must regenerate the Search pages.
        for helper in ('v044_favorite_detail', 'v044_favorite_subscription'):
            self.assertEqual(1, block.count('inputs.file("tools/' + helper + '.py")'), helper)
        generator = (REPO / 'desktop/tools/v046_search_state.py').read_text(encoding='utf8')
        for line in re.findall(r'^from (v\d+_\w+) import', generator, re.M):
            self.assertIn('tools/' + line + '.py', block, line)
        catalog = json.loads((REPO / 'desktop/tools/compatibility-source-inputs.json').read_bytes())
        self.assertIn(layer.ARCHIVE, catalog['buildInputRoots'])
        rows = [row for row in catalog['inputs'] if row['localPath'].startswith(layer.ARCHIVE + '/')]
        self.assertEqual(8, len(rows))
        self.assertTrue(all(row['upstreamCommit'] == layer.COMMIT and not row['partialReuse']
                            and row['features'] == ['v046-search-state'] for row in rows))
        # Only the withheld filter sheet is reference-only: it is archived and verified but never emitted.
        self.assertEqual({layer.FILTER_SHEET}, {row['path'] for row in rows if row['referenceOnly']})
        self.assertIn('withheld', next(row for row in rows if row['path'] == layer.FILTER_SHEET)['usedScope'])
        self.assertIn('layout toggle arguments', next(row for row in rows if row['path'] == layer.SCREEN)['usedScope'])
        self.assertEqual({row['path'] for row in rows}, set(layer.PATHS))
        archive = next(row for row in catalog['archives'] if row['directory'] == layer.ARCHIVE)
        self.assertEqual(10, len(archive['files']))
        for row in archive['files']:
            self.assertEqual(sha((REPO / row['path']).read_bytes()), row['sha256'])

    # -- desktop-owned Windows bindings ----------------------------------------------------------

    def test_windows_preferences_bind_the_silent_switch_beside_privacy_and_check_both_in_the_writer(self):
        raw = PREFERENCES.read_bytes().decode('utf8')
        self.assertEqual(raw.count('\r\n'), raw.count('\n'), 'the file keeps its CRLF endings')
        text = raw.replace('\r\n', '\n')
        for token in ('internal const val SEARCH_HISTORY_RECORDING_KEY = "search_history_recording_enabled"',
                      'val searchHistoryRecordingEnabled: StateFlow<Boolean> = _searchHistoryRecording.asStateFlow()',
                      'fun isSearchHistoryRecordingEnabledSync(): Boolean = synchronized(lock) {',
                      'suspend fun setSearchHistoryRecordingEnabled(enabled: Boolean) = withContext(Dispatchers.IO) { synchronized(lock) {\n        checkWritesOpen()',
                      'if (!isPrivacyModeEnabledSync() && isSearchHistoryRecordingEnabledSync()) {'):
            self.assertIn(token, text)
        self.assertEqual(1, text.count('suggestions.edit().putBoolean(SEARCH_HISTORY_RECORDING_KEY, enabled)'))
        self.assertIn('internal fun writesClosed(): Boolean = synchronized(lock) { writesFrozen }', text)
        # record() is also the writer of the legacy desktop-owned CommunitySearch page: one history list, one switch, and
        # that page has no control of its own. The class says so; the caller below is why.
        record = text[:text.index('    suspend fun record(mid: Long?, keyword: String) {')]
        record = record[record.rindex('/**'):]
        for token in ('CommunitySearch', 'CommunitySearchScreens.kt', 'global privacy mode', 'search-only silent switch',
                      'has no control of its own', 'original Search landing'):
            self.assertIn(token, record, token)
        callers = [path.name for path in (DESKTOP / 'main').rglob('*.kt')
                   if re.search(r'preferences\.record\(mid, term\)', path.read_text(encoding='utf8'))]
        self.assertEqual(['CommunitySearchScreens.kt'], callers)
        self.assertEqual(1, text.count('if (!isPrivacyModeEnabledSync() && isSearchHistoryRecordingEnabledSync()) {'))

    def test_search_environment_exposes_the_switch_and_privacy_flows_through_the_one_settings_view(self):
        text = ENVIRONMENT.read_text(encoding='utf8')
        self.assertNotIn('\r', text)
        for token in ('private val preferences: DesktopSearchPreferences,',
                      'val searchHistoryRecordingEnabled = preferences.searchHistoryRecordingEnabled',
                      'val privacyMode = preferences.privacyMode',
                      'fun isSearchHistoryRecordingEnabledSync(): Boolean {',
                      'suspend fun setSearchHistoryRecordingEnabled(enabled: Boolean) {\n        currentCoroutineContext().ensureActive(); assertCurrent()',
                      'fun getPrivacyModeEnabled(context: DesktopOriginalSearchEnvironment): Flow<Boolean> = context.privacyMode',
                      'fun getSearchHistoryRecordingEnabled(context: DesktopOriginalSearchEnvironment): Flow<Boolean> = context.searchHistoryRecordingEnabled',
                      'fun isSearchHistoryRecordingEnabledSync(context: DesktopOriginalSearchEnvironment) = context.isSearchHistoryRecordingEnabledSync()',
                      'suspend fun setSearchHistoryRecordingEnabled(context: DesktopOriginalSearchEnvironment, value: Boolean) = context.setSearchHistoryRecordingEnabled(value)'):
            self.assertIn(token, text)
        # A facade frozen for a backup restore means this page is retired: the write turns into the cancellation that every
        # other retired write uses (the original view model launches it without a handler). Other faults propagate.
        write = text[text.index('suspend fun setSearchHistoryRecordingEnabled(enabled: Boolean) {'):]
        write = write[:write.index('\n    }\n') + 7]
        for token in ('try {\n            preferences.setSearchHistoryRecordingEnabled(enabled)\n        } catch (failure: IllegalStateException) {',
                      'if (!preferences.writesClosed()) throw failure',
                      'throw CancellationException("Search settings retired for restore").also { it.initCause(failure) }'):
            self.assertIn(token, write, token)
        self.assertLess(write.index('assertCurrent()'), write.index('try {'))
        # The one constructor caller already passes the Root preferences positionally; nothing else builds the environment.
        callers = [path for path in (DESKTOP / 'main').rglob('*.kt') if 'DesktopOriginalSearchEnvironment(' in path.read_text(encoding='utf8')
                   and path != ENVIRONMENT]
        self.assertEqual(['DesktopReadyHomeFactoryBinding.kt'], [path.name for path in callers])

    def test_share_catalog_accepts_the_two_v046_appearance_booleans_and_not_the_silent_switch(self):
        raw = CATALOG.read_bytes().decode('utf8')
        self.assertEqual(raw.count('\r\n'), raw.count('\n'), 'the file keeps its CRLF endings')
        text = raw.replace('\r\n', '\n')
        block = text[text.index('booleans(appearance,'):text.index('names(appearance, "theme_selection_v1"')]
        for key in ('related_video_transition_enabled', 'video_return_content_follow_progress_enabled'):
            self.assertEqual(1, block.count('"' + key + '"'), key)
            self.assertEqual(1, text.count('"' + key + '"'), key)
        self.assertNotIn('"search_history_recording_enabled"', text)
        self.assertIn('search_history_recording_enabled is a v0.4.6 key that Android does not share', text)
        # What the catalog comment claims about the two consumers, and nothing more: the first key has a getter and a setter,
        # the second only a setter, and that setter writes the literal key through the settings namespace of the store.
        for claim in ('Both already have owners in the generated DesktopOriginalFullNavigationSettings',
                      'the second only the setter of the animation settings control (no getter or aggregated',
                      'DesktopSearchStateSettingsTest reads the first back through its getter'):
            self.assertIn(claim, text, claim)
        extractor = (REPO / 'desktop/tools/extract-upstream-full-navigation.py').read_text(encoding='utf8')
        for name in ('getRelatedVideoTransitionEnabled', 'setRelatedVideoTransitionEnabled', 'setVideoReturnContentFollowProgressEnabled'):
            self.assertIn("'" + name + "'", extractor, name)
        self.assertNotIn("'getVideoReturnContentFollowProgressEnabled'", extractor)
        store = canonical_source(REPO, 'app/src/main/java/com/android/purebilibili/core/store/navigation/NavigationSettingsStore.kt')
        store = store.read_text(encoding='utf8').replace('\r\n', '\n')
        self.assertEqual(1, store.count('suspend fun setVideoReturnContentFollowProgressEnabled(context: Context, enabled: Boolean) {\n'
                                        '        context.settingsDataStore.edit { preferences ->\n'
                                        '            preferences[booleanPreferencesKey("video_return_content_follow_progress_enabled")] = enabled'))
        state_test = STATE_TEST.read_text(encoding='utf8')
        self.assertIn('DesktopOriginalFullNavigationSettings.setVideoReturnContentFollowProgressEnabled(DesktopPluginContext(f.store), true)', state_test)
        self.assertIn('f.store.preferences("settings")[keys[1]]', state_test)

    def test_hand_written_kotlin_is_balanced_and_the_jvm_classes_are_counted(self):
        syntax = v41.parser(REPO)
        for path in (PREFERENCES, ENVIRONMENT, CATALOG, RECORDING_TEST, STATE_TEST):
            stack = []
            for token, first, _ in syntax.kotlin_tokens(path.read_bytes().decode('utf8').replace('\r\n', '\n')):
                if token in ('(', '[', '{'):
                    stack.append(token)
                elif token in (')', ']', '}'):
                    self.assertTrue(stack, (path.name, first))
                    self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), (path.name, first))
            self.assertEqual([], stack, path.name)
        self.assertEqual(6, RECORDING_TEST.read_text(encoding='utf8').count('@Test fun'))
        self.assertEqual(5, STATE_TEST.read_text(encoding='utf8').count('@Test fun'))
        for path in (RECORDING_TEST, STATE_TEST):
            self.assertNotIn('\r', path.read_text(encoding='utf8'))
            self.assertNotIn('Thread.sleep', path.read_text(encoding='utf8'))

    def test_generated_kotlin_has_balanced_delimiters(self):
        syntax = v41.parser(REPO)
        names = [SEARCH_DIR + name for name in ('SearchViewModel.kt', 'SearchScreen.kt', 'SearchLandingUi.kt',
                                                 'SearchVideoFilterSheet.kt', 'SearchContentStatePolicy.kt',
                                                 'SearchMotionBudgetPolicy.kt')]
        for name in names:
            stack = []
            for token, first, _ in syntax.kotlin_tokens((self.main / name).read_text(encoding='utf8')):
                if token in ('(', '[', '{'):
                    stack.append(token)
                elif token in (')', ']', '}'):
                    self.assertTrue(stack, (name, first))
                    self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), (name, first))
            self.assertEqual([], stack, name)


if __name__ == '__main__':
    unittest.main()
