"""Source checks for roadmap slice bgm-hub-pages. Nothing here compiles Kotlin or starts a JVM."""
from pathlib import Path
import ast
import difflib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v046_bangumi_hub_pages as hub

TOOLS = REPO / 'desktop/tools'
UI = REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui'
HOME_PRODUCER = 'extract-upstream-home-bangumi-page.py'
PAGES_PRODUCER = 'extract-upstream-bangumi-pages.py'
JVM_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopBangumiHubPagesV046Test.kt'
JVM_TEST_CASES = 8
HOME_HOOKS = [
    (' from v046_bangumi_hub_pages import advance_home\n v046=advance_home(root,out)\n', ''),
    (',v046HubPages=v046', ''),
]
PAGES_INVENTORY_HOOK = ',v046HubPages=V046_ROWS'
PAGES_HOOK_BLOCK = ("from v046_bangumi_hub_pages import advance_pages,emit_hub_policy_test\nV046_ROWS=advance_pages(C,OUT)\n"
                    "V046_ROWS.append(emit_hub_policy_test(C,OUT.parent/(OUT.name+'-tests')))\n")
PLAYER_HOOK_START = '# v0.4.6 follow-session policy (targetSeasonId'
PLAYER_HOOK_END = "emit_follow_preload_test(C,OUT.parent/(OUT.name+'-tests'))\n"
WHOLE_OUTPUTS = [row[0] for row in hub.HOME_WHOLE + hub.PAGES_WHOLE]
HUNK_OUTPUTS = [row[0] for row in hub.HOME_HUNKS + hub.PAGES_HUNKS]
ALL_OUTPUTS = WHOLE_OUTPUTS + HUNK_OUTPUTS + [hub.OUT_TRANSITION]
HOME_OUTPUTS = [o for o in ALL_OUTPUTS if o in {r[0] for r in hub.HOME_WHOLE} | {r[0] for r in hub.HOME_HUNKS} | {hub.OUT_TRANSITION}]
PAGES_OUTPUTS = [o for o in ALL_OUTPUTS if o not in HOME_OUTPUTS]
KNOWN_FALSE_POSITIVES = {
    'BangumiViewModel.kt': {'viewModelScope'},   # replaced on purpose by the member that reads the page environment scope
    'HomeBangumiTabPage.kt': {'viewModel'},      # a parameter name, the import is replaced on purpose
}
# The v0.2.5 tab role receipts of the three files this layer replaces whole: they describe an intermediate text.
STALE_RECEIPTS = {
    'home': [hub.TAB_ROLE_PROOF_DIRECTORY + '/BangumiHubContent.kt.json',
             hub.TAB_ROLE_PROOF_DIRECTORY + '/HomeBangumiTabPage.kt.json'],
    'pages': [hub.TAB_ROLE_PROOF_DIRECTORY + '/DesktopOriginalBangumiScreen.kt.json'],
}
INVENTORY = {'home': 'bangumi-page-producer-inventory.json', 'pages': 'bangumi-pages-producer-inventory.json'}


def load(name):
    spec = importlib.util.spec_from_file_location('bgm_hub_' + name.replace('-', '_').replace('.', '_'), TOOLS / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_producer(script, output):
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1', PYTHONPATH=str(TOOLS))
    result = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(script), '--repo', str(REPO), '--output', str(output)],
                            env=env, capture_output=True, text=True, timeout=240)
    if result.returncode != 0:
        raise AssertionError(result.stdout + result.stderr)


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in Path(root).rglob('*') if p.is_file()}


def text(path):
    return Path(path).read_bytes().decode('utf8').replace('\r\n', '\n')


def balanced(testcase, name, body, syntax):
    stack = []
    for token, first, _ in syntax.kotlin_tokens(body):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            testcase.assertTrue(stack, (name, first))
            testcase.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), (name, first))
    testcase.assertEqual([], stack, name)


def gradle_braces_balanced(body):
    depth = 0
    i, n = 0, len(body)
    backslash = chr(92)
    while i < n:
        if body.startswith('//', i):
            j = body.find('\n', i)
            i = n if j == -1 else j
            continue
        if body.startswith('/*', i):
            i = body.find('*/', i) + 2
            continue
        if body.startswith('"""', i):
            i = body.find('"""', i + 3) + 3
            continue
        if body[i] == '"':
            j = i + 1
            while j < n and body[j] != '"':
                j += 2 if body[j] == backslash else 1
            i = j + 1
            continue
        if body[i] == "'":
            i = body.find("'", i + 1) + 1
            continue
        depth += (body[i] == '{') - (body[i] == '}')
        if depth < 0:
            return False
        i += 1
    return depth == 0


def audit_tables():
    """OWNERS and VIEW_MODEL_SITES of the account-scoped audit, read without importing (and so running) that test module."""
    path = TOOLS / 'tests/test_v046_account_scoped_requests_source.py'
    wanted = {'WIN', 'UI', 'OWNERS', 'VIEW_MODEL_SITES'}
    nodes = [node for node in ast.parse(path.read_text(encoding='utf8')).body
             if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
             and node.targets[0].id in wanted]
    assert {node.targets[0].id for node in nodes} == wanted, 'The account-scoped audit tables moved'
    namespace = {}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(path), 'exec'), namespace)
    return namespace['OWNERS'], namespace['VIEW_MODEL_SITES']


def import_candidates(syntax, original, retained):
    """Names the original imports, the retained text uses as bare code tokens and neither imports nor declares."""
    def imports(body):
        found = {}
        for match in re.finditer(r'(?m)^import\s+([\w.]+?)(\.\*)?(?:\s+as\s+(\w+))?\s*$', body):
            if match.group(2):
                continue
            found[match.group(3) or match.group(1).rsplit('.', 1)[-1]] = match.group(1)
        return found
    imported = set(imports(retained))
    tokens = syntax.kotlin_tokens(re.sub(r'(?m)^import .*$', '', retained))
    declared, depth = set(), 0
    for index, (word, _, _) in enumerate(tokens[:-1]):
        if depth == 0 and word in {'fun', 'val', 'var', 'class', 'object', 'interface', 'typealias'}:
            declared.add(tokens[index + 1][0])
        depth += (word == '{') - (word == '}')
    words = [word for word, _, _ in tokens]
    found = set()
    for name in imports(original):
        if name in imported or name in declared:
            continue
        bare = sum(1 for i, word in enumerate(words) if word == name and (i == 0 or words[i - 1] != '.')
                   and (i + 1 == len(words) or words[i + 1] not in {':', '='}))
        if bare:
            found.add(name)
    return found


class BangumiHubPagesSliceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-hub-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.sources, cls.manifest = hub.fixed(REPO)
        cls.syntax = load('sync-upstream.py')
        run_producer(TOOLS / HOME_PRODUCER, cls.root / 'home')
        run_producer(TOOLS / PAGES_PRODUCER, cls.root / 'pages')
        # The same producers with this slice's hook removed give the predecessor stage of every output.
        home = (TOOLS / HOME_PRODUCER).read_text(encoding='utf8')
        pages = (TOOLS / PAGES_PRODUCER).read_text(encoding='utf8')
        for old, new in HOME_HOOKS:
            assert home.count(old) == 1, old
            home = home.replace(old, new)
        assert pages.count(PAGES_HOOK_BLOCK) == 1 and pages.count(PAGES_INVENTORY_HOOK) == 1
        pages = pages.replace(PAGES_HOOK_BLOCK, '').replace(PAGES_INVENTORY_HOOK, '')
        for name, body, key in [('home-before.py', home, 'home'), ('pages-before.py', pages, 'pages')]:
            (cls.root / name).write_text(body, encoding='utf8', newline='\n')
            run_producer(cls.root / name, cls.root / ('before-' + key))
        cls.home = cls.root / 'home'
        cls.pages = cls.root / 'pages'
        cls.tests = cls.root / 'pages-tests'
        cls.vm = text(cls.pages / hub.OUT_VM)
        cls.screen = text(cls.pages / hub.OUT_SCREEN)

    def out(self, output, before=False):
        side = 'home' if output in HOME_OUTPUTS else 'pages'
        return self.root / (('before-' + side) if before else side) / output

    def proof(self, output):
        side = 'home' if output in HOME_OUTPUTS else 'pages'
        return json.loads((self.root / side / hub.PROOF_DIRECTORY / (Path(output).stem + '.json')).read_text(encoding='utf8'))

    # ---- archive -------------------------------------------------------------------------------------------------

    def test_archive_rejects_a_changed_extra_or_missing_leaf_manifest_and_license(self):
        self.assertEqual(8, len(self.sources))
        self.assertEqual(hub.COMMIT, self.manifest['commit'])
        for name in [*hub.EXPECTED, 'manifest.json', 'LICENSE', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-hub-a-') as temp:
                repo = Path(temp)
                shutil.copytree(REPO / hub.ARCHIVE, repo / hub.ARCHIVE)
                file = repo / hub.ARCHIVE / name
                file.write_bytes((file.read_bytes() if file.exists() else b'') + b'\n')
                with self.assertRaises(ValueError):
                    hub.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-hub-a-') as temp:
            repo = Path(temp)
            shutil.copytree(REPO / hub.ARCHIVE, repo / hub.ARCHIVE)
            (repo / hub.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                hub.fixed(repo)

    def test_archive_leaves_are_the_upstream_blobs_and_lf_only(self):
        for row in self.manifest['files']:
            blob = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob',
                                   hub.COMMIT + ':' + row['path']], capture_output=True, check=True).stdout
            self.assertEqual(blob, (REPO / hub.ARCHIVE / row['filename']).read_bytes(), row['filename'])
            self.assertNotIn(b'\r', blob)
        self.assertEqual(hub.LEAVES, {row['filename']: row['path'] for row in self.manifest['files']})

    def test_outputs_inside_the_archive_or_containing_it_are_rejected(self):
        for target in [REPO / hub.ARCHIVE / 'child', REPO / 'desktop/upstream-slices']:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                hub.validate_outputs(REPO, target)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                hub.advance_home(REPO, target)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                hub.advance_pages(REPO, target)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                hub.emit_hub_policy_test(REPO, target)

    # ---- repository page size ----------------------------------------------------------------------------------------

    def test_the_v046_repository_is_the_pinned_canonical_file_plus_exactly_the_two_page_size_hunks(self):
        canonical, carried = hub.repository_delta(REPO)
        self.assertEqual(2, len(hub.REPOSITORY_DELTA))
        changed = [(a.strip(), b.strip()) for a, b in zip(canonical.splitlines(), carried.splitlines()) if a != b]
        self.assertEqual([('pageSize: Int = 20', 'pageSize: Int = 30'), ('pageSize: Int = 21,', 'pageSize: Int = 30,')], changed)
        self.assertEqual(len(canonical.splitlines()), len(carried.splitlines()))
        self.assertEqual(self.sources['BangumiRepository.kt'], carried)
        upstream = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'diff', '--numstat',
                                   '79e8fa3019f5d70b2dee77db1ce9ce99a84bbe40', hub.COMMIT, '--', hub.REPOSITORY_PATH],
                                  capture_output=True, text=True, check=True).stdout.split()
        self.assertEqual(['2', '2'], upstream[:2])

    def test_the_selected_repository_bodies_carry_the_same_two_defaults_and_nothing_else_changes(self):
        for output, hunks in [(hub.OUT_HUB_REPO, hub.HUB_REPO_HUNKS), (hub.OUT_PAGES_REPO, hub.PAGES_REPO_HUNKS)]:
            before, after = text(self.out(output, before=True)), text(self.out(output))
            self.assertEqual(1, len(hunks))
            changed = [(a.strip(), b.strip()) for a, b in zip(before.splitlines(), after.splitlines()) if a != b]
            self.assertEqual(1, len(changed))
            self.assertEqual(len(before.splitlines()), len(after.splitlines()))
            self.assertTrue(changed[0][1].endswith('= 30') or changed[0][1].endswith('= 30,'))
        self.assertIn('pageSize: Int = 30,\n        params: Map<String, String> = emptyMap(),', text(self.out(hub.OUT_HUB_REPO)))
        self.assertIn('pageSize: Int = 30\n): Result<BangumiIndexData>', text(self.out(hub.OUT_PAGES_REPO)))
        # every other default stays what the v0.4.6 blob says: search 20, my follow 30
        carried = self.sources['BangumiRepository.kt']
        for token in ['pageSize: Int = 20,', 'pageSize: Int = 30,']:
            self.assertIn(token, carried)

    # ---- layers ---------------------------------------------------------------------------------------------------

    def test_every_predecessor_and_every_final_output_matches_its_manifest_pin(self):
        for output in WHOLE_OUTPUTS + HUNK_OUTPUTS:
            self.assertEqual(self.manifest['beforeFinalOutputs'][output]['sha256'], hub.sha(text(self.out(output, before=True))), output)
        for output in ALL_OUTPUTS:
            self.assertEqual(self.manifest['afterFinalOutputs'][output]['sha256'], hub.sha(text(self.out(output))), output)
        self.assertEqual(sorted(WHOLE_OUTPUTS + HUNK_OUTPUTS), sorted(self.manifest['beforeFinalOutputs']))
        self.assertEqual(sorted(ALL_OUTPUTS), sorted(self.manifest['afterFinalOutputs']))
        self.assertFalse(self.out(hub.OUT_TRANSITION, before=True).exists())

    def test_every_layer_is_the_exact_inverse_of_its_proofs_on_both_ends(self):
        for output in WHOLE_OUTPUTS + HUNK_OUTPUTS:
            proof = self.proof(output)
            self.assertEqual(text(self.out(output, before=True)), hub.restore(text(self.out(output)), proof['retainedToCurrent']), output)
        for output, leaf, adaptations, header in hub.HOME_WHOLE + hub.PAGES_WHOLE + [hub.TRANSITION]:
            proof = self.proof(output)
            adapted = hub.adapt(self.sources[leaf], adaptations)
            self.assertEqual(self.sources[leaf], hub.restore(adapted, proof['originalToWindows']), output)
            body = text(self.out(output))
            self.assertTrue(body.endswith(adapted), output)
            self.assertEqual(len(adaptations), proof['adaptationCount'])
            if header:
                self.assertEqual(hub.header_for(self.manifest, leaf) + adapted, body)
            else:
                self.assertEqual(adapted, body)

    def test_changed_and_new_files_are_exactly_the_layered_outputs_and_their_proofs(self):
        for side, outputs in [('home', HOME_OUTPUTS), ('pages', PAGES_OUTPUTS)]:
            before, after = tree(self.root / ('before-' + side)), tree(self.root / side)
            # The only files that disappear are the stale tab role receipts of the three replaced files.
            self.assertEqual(sorted(STALE_RECEIPTS[side]), sorted(k for k in before if k not in after))
            inventory = [k for k in before if k.endswith('-producer-inventory.json')]
            self.assertEqual(1, len(inventory))
            changed = sorted(k for k in before if k in after and before[k] != after[k] and k != inventory[0])
            self.assertEqual(sorted(o for o in outputs if o != hub.OUT_TRANSITION), changed)
            self.assertNotEqual(before[inventory[0]], after[inventory[0]])
            new = sorted(k for k in after if k not in before)
            expected = sorted([hub.PROOF_DIRECTORY + '/' + Path(o).stem + '.json' for o in outputs] +
                              ([hub.OUT_TRANSITION] if side == 'home' else []))
            self.assertEqual(expected, new)
        before, after = tree(self.root / 'before-pages-tests'), tree(self.tests)
        self.assertEqual([], [k for k in before if before[k] != after.get(k)])
        self.assertEqual([hub.TEST_OUTPUT, hub.PROOF_DIRECTORY + '/BangumiHubPolicyTest.json'], sorted(k for k in after if k not in before))

    def test_predecessor_drift_or_a_missing_output_is_rejected_before_anything_is_written(self):
        for side, advance in [('before-home', hub.advance_home), ('before-pages', hub.advance_pages)]:
            outputs = HOME_OUTPUTS if side == 'before-home' else PAGES_OUTPUTS
            for output in [o for o in outputs if o != hub.OUT_TRANSITION]:
                with self.subTest(output=output), tempfile.TemporaryDirectory(prefix='bp-hub-d-') as temp:
                    work = Path(temp) / 'o'
                    shutil.copytree(self.root / side, work)
                    snapshot = tree(work)
                    target = work / output
                    target.write_bytes(target.read_bytes() + b'\n// drift\n')
                    drifted = tree(work)
                    with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                        advance(REPO, work)
                    self.assertEqual(drifted, tree(work))
                    target.write_bytes(snapshot[output])
                    target.unlink()
                    with self.assertRaisesRegex(ValueError, 'predecessor output is missing'):
                        advance(REPO, work)
        # An already advanced output is not a predecessor: the layer never stacks on its own result, and writes nothing.
        for side, advance in [('home', hub.advance_home), ('pages', hub.advance_pages)]:
            with self.subTest(side=side), tempfile.TemporaryDirectory(prefix='bp-hub-d-') as temp:
                work = Path(temp) / 'o'
                shutil.copytree(self.root / side, work)
                before = tree(work)
                with self.assertRaisesRegex(ValueError, 'predecessor changed'):
                    advance(REPO, work)
                self.assertEqual(before, tree(work))

    def test_a_new_emission_left_by_an_earlier_run_is_regenerated_and_never_blocks_the_layer(self):
        # Gradle re-runs the producer into the same directory and no producer clears it: the one file that is new in
        # this layer has to be overwritten like every other emission, whatever it holds.
        for stale in ['stale text of an earlier run', text(self.out(hub.OUT_TRANSITION)), '']:
            with self.subTest(stale=stale[:20]), tempfile.TemporaryDirectory(prefix='bp-hub-d-') as temp:
                work = Path(temp) / 'o'
                shutil.copytree(self.root / 'before-home', work)
                (work / hub.OUT_TRANSITION).write_text(stale, encoding='utf8', newline='\n')
                hub.advance_home(REPO, work)
                self.assertEqual(text(self.out(hub.OUT_TRANSITION)), text(work / hub.OUT_TRANSITION))
                # Everything but the producer's own inventory is exactly what the producer run left.
                skip = INVENTORY['home']
                self.assertEqual({k: v for k, v in tree(self.home).items() if k != skip},
                                 {k: v for k, v in tree(work).items() if k != skip})

    def test_a_changed_adaptation_anchor_is_rejected_by_its_count(self):
        for label, old, new, count in hub.VM_ADAPTATIONS + hub.SCREEN_ADAPTATIONS + hub.TAB_PAGE_ADAPTATIONS + hub.TRANSITION_ADAPTATIONS:
            with self.subTest(label=label):
                with self.assertRaisesRegex(ValueError, 'anchor changed'):
                    hub.counted('unrelated text', old, new, count, label)
        for label, old, new, count in hub.VM_ADAPTATIONS:
            with self.assertRaisesRegex(ValueError, 'anchor changed'):
                hub.counted(self.sources['BangumiViewModel.kt'] + old, old, new, count, label)
        self.assertEqual((7, 11, 9, 1), (len(hub.VM_ADAPTATIONS), len(hub.SCREEN_ADAPTATIONS), len(hub.TAB_PAGE_ADAPTATIONS),
                                          len(hub.TRANSITION_ADAPTATIONS)))

    # ---- whole files ------------------------------------------------------------------------------------------------

    def test_content_and_policy_are_the_untouched_upstream_bytes_and_every_tab_row_names_its_role(self):
        for output, leaf in [(hub.OUT_HUB_CONTENT, 'BangumiHubContent.kt'), (hub.OUT_HUB_POLICY, 'BangumiHubPolicy.kt')]:
            self.assertEqual((REPO / hub.ARCHIVE / leaf).read_bytes(), self.out(output).read_bytes().replace(b'\r\n', b'\n'), output)
        for output, leaf in [(hub.OUT_HUB_CONTENT, 'BangumiHubContent.kt'), (hub.OUT_TAB_PAGE, 'HomeBangumiTabPage.kt'),
                             (hub.OUT_SCREEN, 'BangumiScreen.kt')]:
            body = text(self.out(output))
            calls = len(re.findall(r'\b(?:AppThemeAdaptiveTabRow|AppLiquidAwareTabRow)\s*\(', body))
            roles = len(re.findall(r'role = com\.android\.purebilibili\.core\.ui\.components\.AppTabRowRole\.\w+,', body))
            self.assertGreater(calls, 0, output)
            self.assertEqual(calls, roles, output)
        # The v037 tab role projection is moot for these files: the producer must not have needed it for them.
        self.assertIn('AppTabRowRole.FILTER', text(self.out(hub.OUT_HUB_CONTENT)))
        self.assertNotIn('AppTabRowIndicatorPresentation', text(self.out(hub.OUT_HUB_CONTENT)) + self.screen)

    def test_home_hub_files_use_only_their_own_windows_adaptations_and_keep_no_android_globals(self):
        tab = text(self.out(hub.OUT_TAB_PAGE))
        for token in ['SettingsManager', 'DownloadManager', 'androidx.compose.ui.platform.LocalContext',
                      'androidx.lifecycle.viewmodel.compose.viewModel']:
            self.assertNotIn(token, tab, token)
        self.assertEqual(1, tab.count('internal fun HomeBangumiTabPage('))
        self.assertEqual(1, tab.count('com.bilipai.desktop.ui.LocalDesktopBangumiHubViewModel.current'))
        self.assertEqual(1, tab.count('if (com.android.purebilibili.core.ui.isMiuixNonGlassEnabled()) {'))
        # The 0.2.5 recipe and this layer adapt the same nine anchors, the layer only re-anchors them on the v0.4.6 text.
        inventory = json.loads((self.root / 'before-home' / 'bangumi-page-producer-inventory.json').read_text(encoding='utf8'))
        row = next(r for r in inventory['emitted'] if r['path'] == hub.OUT_TAB_PAGE)
        older = sorted((c['before'], c['after']) for c in row['adaptations'] if 'role = ' not in c['after'])
        newer = sorted((old.rstrip('\n'), new.rstrip('\n')) for _, old, new, _ in hub.TAB_PAGE_ADAPTATIONS)
        self.assertEqual(9, len(older))
        self.assertEqual(older, newer)

    def test_screen_keeps_both_entry_points_internal_and_binds_the_environment_exactly_where_the_old_recipe_did(self):
        self.assertEqual(2, self.screen.count('internal fun BangumiScreen('))
        self.assertEqual(1, self.screen.count('private fun BangumiHubScreen('))
        for token in ['android.os.Build', 'SettingsManager', 'DownloadManager', 'shouldAllowRenderEffectBackedHazeEffect(',
                      'viewModel: BangumiHubViewModel = viewModel()', 'LocalContext.current.settings']:
            self.assertNotIn(token, self.screen, token)
        self.assertEqual(1, self.screen.count('    viewModel: BangumiHubViewModel,\n    onPlayEpisode:'))
        self.assertEqual(1, self.screen.count('LocalDesktopOriginalBangumiPagesEnvironment.current.hazeEffectSupported'))
        self.assertEqual(1, self.screen.count('viewModel = LocalDesktopOriginalBangumiPagesEnvironment.current.hub,\n    )'))
        self.assertEqual(1, self.screen.count('context.settings.homeSettings.map { it.showPgcTimeline }'))
        self.assertEqual(1, self.screen.count('context.saveCover(url, title)'))
        self.assertEqual(1, self.screen.count('rememberSaveable(state.accountGeneration)'))
        self.assertEqual(1, self.screen.count('BangumiCardTransitionHost('))
        self.assertEqual(1, self.screen.count('minTabWidth = if (themeConfig.liquidGlassEnabled) 64.dp'))
        self.assertEqual(1, self.screen.count('rememberNowPlayingBarOverlayVisible()'))
        inventory = json.loads((self.root / 'before-pages' / 'bangumi-pages-producer-inventory.json').read_text(encoding='utf8'))
        row = next(r for r in inventory['emitted'] if r['output'].endswith('DesktopOriginalBangumiScreen.kt'))
        older = sorted((c['before'], c['after']) for c in row['changes'] if 'role = ' not in c['after'])
        newer = sorted((old.rstrip('\n'), new.rstrip('\n')) for _, old, new, _ in hub.SCREEN_ADAPTATIONS)
        self.assertEqual(11, len(older))
        self.assertEqual(older, newer)

    def test_imports_the_original_gave_are_still_satisfied_in_every_carried_output(self):
        for output, leaf, adaptations, header in hub.HOME_WHOLE + hub.PAGES_WHOLE + [hub.TRANSITION]:
            found = import_candidates(self.syntax, self.sources[leaf], text(self.out(output)))
            self.assertEqual(KNOWN_FALSE_POSITIVES.get(leaf, set()), found, leaf)

    # ---- single UiState --------------------------------------------------------------------------------------------

    def test_view_model_has_one_backing_state_and_the_old_properties_derive_from_it(self):
        self.assertEqual(1, self.vm.count('MutableStateFlow(BangumiUiState()'))
        self.assertEqual(1, self.vm.count('private val _uiState = DesktopHomeOwnedMutableStateFlow(BangumiUiState(), desktopEnvironment.commitIfCurrent)'))
        self.assertEqual(1, self.vm.count('val uiState = _uiState.asStateFlow()'))
        self.assertEqual(0, len(re.findall(r'\bMutableStateFlow\(', self.vm.replace('DesktopHomeOwnedMutableStateFlow(', ''))))
        legacy = ['displayMode', 'selectedType', 'listState', 'timelineState', 'detailState', 'searchState', 'myFollowState',
                  'myFollowType', 'myFollowStats', 'filter', 'searchKeyword']
        for name in legacy:
            self.assertRegex(self.vm, r'val ' + name + r': StateFlow<[^\n]*\n\s+\.distinctUntilChanged\(\)\.stateIn\(viewModelScope, SharingStarted\.Eagerly', name)
        self.assertEqual(len(legacy), self.vm.count('.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly'))
        self.assertNotRegex(self.vm, r'private\s+val\s+_(?!uiState\b)\w+\s*=\s*(?:Desktop\w+)?MutableStateFlow\(')
        self.assertEqual(1, self.vm.count('private val viewModelScope get() = desktopEnvironment.scope'))
        self.assertEqual(1, self.vm.count('internal class BangumiViewModel(private val desktopEnvironment: DesktopOriginalBangumiPagesEnvironment)'))
        data = self.vm[self.vm.index('data class BangumiUiState('):]
        data = data[:data.index('\n)')]
        for name in legacy:
            self.assertRegex(data, r'val ' + name + ':')
        # The public shape: exactly these eleven fields in this order (the JVM test constructs the state positionally).
        self.assertEqual(legacy, re.findall(r'(?m)^    val (\w+):', data))
        for token in ['viewModelScope.launch']:
            self.assertGreaterEqual(self.vm.count(token), 8)
        for token in ['import androidx.lifecycle.viewModelScope', 'BangumiRepository.', 'TokenManager', 'import com.android.purebilibili.data.repository.BangumiRepository']:
            self.assertNotIn(token, self.vm, token)

    def test_every_load_rejects_stale_generations_and_every_cache_read_goes_through_the_session_check(self):
        for token in ['val generation = ++listGeneration', 'val generation = ++searchGeneration', 'val generation = ++timelineGeneration',
                      'val generation = ++myFollowGeneration', 'if (generation != listGeneration || session != ensureFollowCacheSession()) return@fold',
                      'if (generation != searchGeneration) return@fold', 'isActiveFollowRequest(session, generation)']:
            self.assertGreaterEqual(self.vm.count(token), 1, token)
        body = self.vm[self.vm.index('private fun ensureFollowCacheSession()'):]
        body = body[:body.index('private fun isActiveFollowRequest')]
        for token in ['followStatusCache.clear()', 'followStatusValueCache.clear()', 'followedSeasonIds.clear()',
                      'loadedFollowStatsTypes.clear()', '++detailRequestGeneration', '++myFollowGeneration']:
            self.assertIn(token, body, token)
        # the blocking v0.2.5 preload is gone: only the targeted background verification remains
        for token in ['preloadFollowedSeasons()', 'loadedFollowTypes', 'ensureFollowedSeasonsLoaded']:
            self.assertNotIn(token, self.vm, token)
        self.assertEqual(1, self.vm.count('preloadFollowedSeasonsForType('))
        call = self.vm[self.vm.index('preloadFollowedSeasonsForType('):]
        call = call[:call.index('\n            )\n')]
        for token in ['targetSeasonId = seasonId', 'sessionProvider = desktopEnvironment.followSession', 'fetchPage = { requestedType, page, pageSize ->']:
            self.assertIn(token, call, token)

    def test_view_model_uses_only_environment_members_and_request_methods_that_exist(self):
        root = (UI / 'DesktopOriginalBangumiPagesRoot.kt').read_text(encoding='utf8')
        environment = root[root.index('internal class DesktopOriginalBangumiPagesEnvironment('):]
        environment = environment[:environment.index('\n)\n')]
        for member in sorted(set(re.findall(r'desktopEnvironment\.(\w+)', self.vm))):
            self.assertRegex(environment, r'val ' + member + r'\b', member)
        requests = text(self.out(hub.OUT_PAGES_REPO))
        for method in sorted(set(re.findall(r'desktopEnvironment\.requests\.(\w+)\(', self.vm))):
            self.assertRegex(requests, r'suspend fun ' + method + r'\(', method)
        self.assertIn('val followSession: () -> BangumiFollowSession,', environment)
        callers = 0
        for file in (REPO / 'desktop/src').rglob('*.kt'):
            body = file.read_text(encoding='utf8')
            callers += body.count('DesktopOriginalBangumiPagesEnvironment(') - body.count('class DesktopOriginalBangumiPagesEnvironment(')
        self.assertEqual(1, callers)
        self.assertEqual(1, root.count('followSession = { desktopOriginalBangumiPagesFollowSession(gate.mid != null, gate.epoch) })'))

    def test_detail_screen_collects_the_single_state_with_the_upstream_lines_of_3b37b7a2(self):
        detail = text(self.out(hub.OUT_DETAIL))
        self.assertEqual(1, detail.count('    val uiState by viewModel.uiState.collectAsStateWithLifecycle()\n    val detailState = uiState.detailState\n'))
        self.assertNotIn('viewModel.detailState', detail)
        blob = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'show',
                               hub.DETAIL_COMMIT + ':' + self.manifest['detailScreenHunk']['upstreamPath']],
                              capture_output=True, check=True).stdout.decode('utf8')
        stripped = [line.strip() for line in blob.splitlines()]
        for line in hub.DETAIL_UPSTREAM_LINES:
            self.assertIn(line, stripped)
        self.assertEqual(hub.DETAIL_UPSTREAM_LINES, self.manifest['detailScreenHunk']['upstreamLines'])
        self.assertEqual(hub.DETAIL_COMMIT, self.manifest['detailScreenHunk']['upstreamCommit'])
        added = [new.replace(old, '').strip() for _, old, new in hub.DETAIL_HUNKS]
        self.assertEqual(['\n'.join(hub.DETAIL_UPSTREAM_LINES)], [a.replace('\n    ', '\n') for a in added])

    def test_hub_state_gains_the_account_generation_next_to_the_login_flag_and_the_ui_reads_it(self):
        hub_vm = text(self.out(hub.OUT_HUB_VM))
        self.assertEqual(1, hub_vm.count('    val accountGeneration: Long = 0L,\n'))
        self.assertLess(hub_vm.index('val isLoggedIn: Boolean = false,'), hub_vm.index('val accountGeneration: Long = 0L,'))
        self.assertLess(hub_vm.index('val accountGeneration: Long = 0L,'), hub_vm.index('val channel: BangumiChannel'))
        self.assertEqual(1, self.screen.count('state.accountGeneration'))
        content = text(self.out(hub.OUT_HUB_CONTENT))
        self.assertEqual(1, content.count('accountGeneration = state.accountGeneration,'))
        self.assertEqual(1, content.count('    accountGeneration: Long,\n'))
        self.assertEqual(1, content.count('remember(accountGeneration)'))
        # Windows personal page owners are rebuilt per Root session epoch: the audit that established this must still
        # list both owners of the hub view model, otherwise the constant 0 generation needs a new justification.
        owners, sites = audit_tables()
        rows = dict(owners)
        self.assertIn('bangumi-hub', rows)
        hub_files = {Path(path).name for path, _token in rows['bangumi-hub']}
        self.assertEqual({'DesktopOriginalBangumiPagesRoot.kt', 'DesktopOriginalHomeEmbeddedAggregate.kt'}, hub_files)
        for name in hub_files:
            self.assertTrue(any(Path(path).name == name and 'BangumiHubViewModel(' in token for path, token in rows['bangumi-hub']), name)
        self.assertEqual(hub_files, sites['BangumiHubViewModel'])
        # and the only two places that construct the hub view model on Windows are exactly those files
        built = {file.name for file in (REPO / 'desktop/src/main').rglob('*.kt')
                 if re.search(r'\bBangumiHubViewModel\(', file.read_text(encoding='utf8'))}
        self.assertEqual(hub_files, built)

    # ---- card transition on Windows ----------------------------------------------------------------------------------

    def test_card_transition_is_whole_with_one_counted_destination_adaptation_and_stays_off_on_windows(self):
        body = text(self.out(hub.OUT_TRANSITION))
        self.assertEqual(1, body.count('com.bilipai.desktop.ui.DesktopOriginalBangumiCardDetailScreen('))
        self.assertEqual(0, body.count('BangumiDetailScreen('))
        self.assertEqual(1, body.count('    if (onPlayEpisode == null) {\n        content(onBangumiClick, onBangumiEpisodeClick, true)\n        return\n    }'))
        adapter = (UI / 'DesktopOriginalBangumiCardDetail.kt').read_text(encoding='utf8')
        call = body[body.index('DesktopOriginalBangumiCardDetailScreen('):]
        call = call[:call.index('\n            )')]
        passed = set(re.findall(r'^\s+(\w+) = ', call, re.M))
        declared = set(re.findall(r'^\s{4}(\w+): ', adapter[adapter.index('internal fun DesktopOriginalBangumiCardDetailScreen('):], re.M))
        self.assertEqual(passed, declared)
        self.assertEqual({'seasonId', 'epId', 'onBack', 'onEpisodeClick', 'contentTransitionReady', 'onSeasonClick',
                          'onReviewsClick', 'onUserClick', 'modifier'}, declared)
        self.assertEqual(1, adapter.count('remember(environment) { BangumiViewModel(environment) }'))
        for file in (REPO / 'desktop/src/main').rglob('*.kt'):
            self.assertNotRegex(file.read_text(encoding='utf8'), r'onPlayEpisode\s*=', file.name)
        calls = 0
        for other in list(self.home.rglob('*.kt')) + list(self.pages.rglob('*.kt')):
            calls += other.read_text(encoding='utf8').count('BangumiCardTransitionHost(') - \
                other.read_text(encoding='utf8').count('fun BangumiCardTransitionHost(')
        self.assertEqual(1, calls)
        host = (REPO / 'desktop/upstream-slices/v042-search-layout/SharedCardContainerHost.kt').read_text(encoding='utf8')
        self.assertEqual(1, host.count('internal fun <T : Any> SharedCardContainerHost('))
        self.assertEqual(self.sources['BangumiCardTransition.kt'].count('SharedCardContainerHost<OpenBangumiCard>('), 1)

    def test_every_new_top_level_name_has_exactly_one_owner_in_the_generated_and_desktop_sources(self):
        names = {'class BangumiUiState': r'class BangumiUiState\b', 'class BangumiViewModel': r'class BangumiViewModel\b',
                 'class BangumiCardTransition': r'class BangumiCardTransition\b',
                 'rememberBangumiCardTransition': r'fun rememberBangumiCardTransition\b',
                 'BangumiCardTransitionHost': r'fun BangumiCardTransitionHost\b',
                 'resolveBangumiIndexChoiceDisplayLabel': r'fun resolveBangumiIndexChoiceDisplayLabel\b',
                 'DesktopOriginalBangumiCardDetailScreen': r'fun DesktopOriginalBangumiCardDetailScreen\b',
                 'desktopOriginalBangumiPagesFollowSession': r'fun desktopOriginalBangumiPagesFollowSession\b',
                 'BangumiHubScreen': r'fun BangumiHubScreen\b'}
        files = list(self.home.rglob('*.kt')) + list(self.pages.rglob('*.kt')) + list((REPO / 'desktop/src/main').rglob('*.kt'))
        for label, pattern in names.items():
            found = [f.name for f in files if re.search(r'(?m)^(?:@\w+\s+)*(?:internal |private |public )?(?:data )?' + pattern,
                                                       f.read_text(encoding='utf8'))]
            self.assertEqual(1, len(found), (label, found))
        # BangumiScreen keeps its two overloads (hub view model and the source compatible bridge), each once
        self.assertEqual(2, len(re.findall(r'(?m)^internal fun BangumiScreen\(', self.screen)))

    # ---- original test ------------------------------------------------------------------------------------------------

    def test_hub_policy_upstream_suite_is_emitted_with_only_the_junit_import_binding(self):
        emitted = text(self.tests / hub.TEST_OUTPUT)
        expected = self.sources['BangumiHubPolicyTest.kt']
        for old, new in hub.JUNIT_IMPORTS:
            expected = expected.replace(old, new)
        self.assertEqual(expected, emitted)
        self.assertEqual(hub.TEST_COUNT, emitted.count('@Test'))
        self.assertNotIn('org.junit', emitted)
        proof = json.loads((self.tests / hub.PROOF_DIRECTORY / 'BangumiHubPolicyTest.json').read_text(encoding='utf8'))
        self.assertEqual(self.sources['BangumiHubPolicyTest.kt'], hub.restore(emitted, proof['originalToWindows']))
        self.assertEqual([], [p for p in self.pages.rglob('*Test.kt')])
        self.assertEqual(3, len(hub.JUNIT_IMPORTS))
        self.assertNotIn('resolveBangumiIndexChoiceDisplayLabel', emitted)

    # ---- determinism, wiring, hygiene -----------------------------------------------------------------------------------

    def test_producers_repeat_deterministically(self):
        again = self.root / 'again'
        run_producer(TOOLS / HOME_PRODUCER, again / 'home')
        run_producer(TOOLS / PAGES_PRODUCER, again / 'pages')
        self.assertEqual(tree(self.home), tree(again / 'home'))
        self.assertEqual(tree(self.pages), tree(again / 'pages'))
        self.assertEqual(tree(self.tests), tree(again / 'pages-tests'))

    def test_running_each_producer_twice_into_the_same_output_directory_is_byte_identical(self):
        # Gradle re-runs the producers whenever an input changes and nothing clears the output directory first: the
        # second run has to regenerate the predecessor stage, pass the hash checks and end on the same bytes.
        twice = self.root / 'twice'
        run_producer(TOOLS / HOME_PRODUCER, twice / 'home')
        run_producer(TOOLS / PAGES_PRODUCER, twice / 'pages')
        first = {name: tree(twice / name) for name in ['home', 'pages', 'pages-tests']}
        self.assertEqual(tree(self.home), first['home'])
        self.assertEqual(tree(self.pages), first['pages'])
        self.assertEqual(tree(self.tests), first['pages-tests'])
        self.assertTrue((twice / 'home' / hub.OUT_TRANSITION).is_file())
        run_producer(TOOLS / HOME_PRODUCER, twice / 'home')
        run_producer(TOOLS / PAGES_PRODUCER, twice / 'pages')
        for name, before in first.items():
            self.assertEqual(before, tree(twice / name), name)

    def test_the_v025_tab_role_receipts_of_the_three_replaced_files_are_gone_and_the_projection_is_a_no_op(self):
        import v037_tab_roles
        for side, receipts in STALE_RECEIPTS.items():
            for receipt in receipts:
                self.assertTrue((self.root / ('before-' + side) / receipt).is_file(), receipt)
                self.assertFalse((self.root / side / receipt).exists(), receipt)
            self.assertFalse((self.root / side / hub.TAB_ROLE_PROOF_DIRECTORY).exists(), side)
            # the removal is recorded next to each whole file in the producer inventory
            inventory = json.loads((self.root / side / INVENTORY[side]).read_text(encoding='utf8'))
            removed = sorted(row['output'] for row in inventory['v046HubPages'] if row.get('staleTabRoleReceiptRemoved'))
            self.assertEqual(sorted(Path(r).name[:-len('.json')] for r in receipts), sorted(Path(o).name for o in removed))
            kept = [row['output'] for row in inventory['v046HubPages'] if row.get('staleTabRoleReceiptRemoved') is False]
            self.assertEqual(sorted(o for o, *_ in (hub.HOME_WHOLE if side == 'home' else hub.PAGES_WHOLE)
                                    if o not in removed), sorted(kept))
        # v0.4.6 declares every tab role itself, so the explicit role projection finds nothing to change in these files
        for leaf in ['BangumiHubContent.kt', 'HomeBangumiTabPage.kt', 'BangumiScreen.kt']:
            self.assertEqual(self.sources[leaf], v037_tab_roles.apply(hub.LEAVES[leaf], self.sources[leaf]), leaf)

    def test_hooks_are_single_and_sit_outside_the_player_slice_hook_range(self):
        home = (TOOLS / HOME_PRODUCER).read_text(encoding='utf8')
        pages = (TOOLS / PAGES_PRODUCER).read_text(encoding='utf8')
        self.assertEqual(1, home.count('advance_home('))
        self.assertEqual(1, pages.count('advance_pages('))
        self.assertEqual(1, pages.count('emit_hub_policy_test('))
        start = pages.index(PLAYER_HOOK_START)
        end = pages.index(PLAYER_HOOK_END) + len(PLAYER_HOOK_END)
        self.assertNotIn('v046_bangumi_hub_pages', pages[start:end])
        self.assertGreater(pages.index('advance_pages('), end)
        self.assertLess(home.index('advance_home('), home.index("bangumi-page-producer-inventory.json').write_text("))

    def test_gradle_wiring_catalog_rows_and_balanced_build_script(self):
        build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf8')
        self.assertTrue(gradle_braces_balanced(build))
        block = build[build.index('tasks.matching { it.name in setOf("extractOriginalHomeBangumiPage", "extractOriginalBangumiPages") }.configureEach {\n    inputs.files("tools/v046_bangumi_hub_pages.py"'):]
        block = block[:block.index('\n}\n')]
        self.assertEqual(1, block.count('inputs.files("tools/v046_bangumi_hub_pages.py", "tools/v044_favorite_detail.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-bangumi-hub-pages")'))
        self.assertEqual(1, build.count('upstream-slices/v046-bangumi-hub-pages'))
        # No new source root, no canonical exclude: both producers own their outputs and the test root already exists.
        self.assertEqual(1, build.count('kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/independent-bangumi-pages-tests")) }'))
        self.assertEqual(0, build.count('exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/feature/bangumi'))
        self.assertEqual(0, build.count('exclude(canonicalOriginalIdentity("app/src/main/java/com/android/purebilibili/data/repository/BangumiRepository.kt"))'))
        sync = load('sync-upstream.py')
        rows = [row for row in sync.adapted_compatibility_inputs(REPO) if row['localPath'].startswith(hub.ARCHIVE + '/')]
        self.assertEqual(8, len(rows))
        self.assertEqual({'BangumiRepository.kt'}, {Path(r['localPath']).name for r in rows if r['partialReuse']})
        self.assertTrue(all(not row['referenceOnly'] for row in rows))
        catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf8'))
        self.assertIn(hub.ARCHIVE, catalog['buildInputRoots'])
        self.assertEqual({*hub.EXPECTED, 'LICENSE', 'manifest.json'},
                         {Path(f['path']).name for a in catalog['archives'] if a['directory'] == hub.ARCHIVE for f in a['files']})

    def test_changed_generated_and_desktop_owned_kotlin_keeps_balanced_delimiters(self):
        for output in ALL_OUTPUTS:
            balanced(self, output, text(self.out(output)), self.syntax)
        balanced(self, 'policy-test', text(self.tests / hub.TEST_OUTPUT), self.syntax)
        for path in [UI / 'DesktopOriginalBangumiPagesRoot.kt', UI / 'DesktopOriginalBangumiCardDetail.kt', JVM_TEST]:
            balanced(self, path.name, path.read_text(encoding='utf8'), self.syntax)

    def test_new_jvm_test_has_the_counted_cases_and_needs_no_window_gpu_or_network(self):
        body = JVM_TEST.read_text(encoding='utf8')
        self.assertEqual(JVM_TEST_CASES, len(re.findall(r'@Test fun ', body)))
        for forbidden in ['MpvPlayer', 'java.net', 'okhttp', 'Retrofit', 'ProcessBuilder', 'System.load', 'javax.swing', 'java.awt',
                          'runBlocking', 'Thread.sleep']:
            self.assertNotIn(forbidden, body)
        for name in ['resolveBangumiIndexChoiceDisplayLabel', 'desktopOriginalBangumiPagesFollowSession', 'BangumiUiState',
                     'BangumiHubUiState']:
            self.assertIn(name, body)
        # Every case exercises slice logic: the single state case builds BangumiUiState positionally, so the eleven legacy
        # fields keep their declared order, and no case only re-tests Kotlin's data class copy.
        shape = body[body.index('val state = BangumiUiState(\n'):]
        shape = shape[:shape.index('\n        )\n')]
        self.assertEqual(11, len([line for line in shape.splitlines()[1:] if line.strip()]))
        self.assertNotIn('.copy(selectedType', body)
        self.assertNotIn('oneCopyChangesOnlyTheNamedField', body)

    def test_touched_text_files_are_lf_only(self):
        paths = [TOOLS / 'v046_bangumi_hub_pages.py', TOOLS / 'tests/test_v046_bangumi_hub_pages_source.py', TOOLS / HOME_PRODUCER,
                 TOOLS / PAGES_PRODUCER, TOOLS / 'compatibility-source-inputs.json', TOOLS / 'sync-upstream.py',
                 TOOLS / 'tests/test_sync_upstream.py', TOOLS / 'tests/test_v046_bangumi_player_source.py',
                 REPO / 'desktop/build.gradle.kts', UI / 'DesktopOriginalBangumiPagesRoot.kt',
                 UI / 'DesktopOriginalBangumiCardDetail.kt', JVM_TEST, *(REPO / hub.ARCHIVE).iterdir()]
        for path in paths:
            self.assertNotIn(b'\r', path.read_bytes(), path.name)


if __name__ == '__main__':
    unittest.main()
