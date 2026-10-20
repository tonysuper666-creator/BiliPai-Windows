from pathlib import Path
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
import v042_search_dependencies as current
import v046_search_state as state_layer
from v041_search_all import parser


def load(filename):
    spec = importlib.util.spec_from_file_location('search_dependency_' + filename.replace('-', '_'),
        REPO / 'desktop/tools' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def inverse(text, proof):
    assert hashlib.sha256(text.encode()).hexdigest() == proof['afterSha256LF']
    for edit in reversed(proof['indexedEdits']):
        at = edit['afterOffset']
        assert text[at:at + len(edit['after'])] == edit['after']
        text = text[:at] + edit['before'] + text[at + len(edit['after']):]
    assert hashlib.sha256(text.encode()).hexdigest() == proof['beforeSha256LF']
    return text


def parameters(tokens, start, *, declarations=False):
    """Names at this real argument-list depth; nested lambdas/defaults are excluded."""
    parens, braces, brackets = 1, 0, 0
    names = set()
    for i in range(start + 1, len(tokens)):
        token = tokens[i][0]
        if (parens, braces, brackets) == (1, 0, 0) and i + 1 < len(tokens):
            if tokens[i + 1][0] == (':' if declarations else '=') and (
                declarations or tokens[i-1][0] in ('(', ',')):
                names.add(token)
        parens += (token == '(') - (token == ')')
        braces += (token == '{') - (token == '}')
        brackets += (token == '[') - (token == ']')
        if parens == 0:
            return names
    raise AssertionError('Unclosed actual Kotlin parameter list')


class CurrentSearchDependencyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bilipai-current-search-dependencies-')
        cls.output = Path(cls.temp.name)
        cls.originals, cls.manifest = current.fixed_sources(REPO)
        cls.lexer = parser(REPO)
        load('extract-upstream-search-pages.py').generate(REPO, cls.output/'search', cls.output/'tests')
        load('extract-upstream-shared-liquid-tabs.py').generate(REPO, cls.output/'shared', True)
        load('extract-upstream-home-page.py').generate(REPO, cls.output/'home', True)
        load('extract-upstream-dynamic-tabs.py').generate(REPO, cls.output/'dynamic', True)
        load('extract-upstream-danmaku-settings.py').generate(REPO, cls.output/'danmaku', True)
        load('extract-upstream-components.py').generate(REPO, cls.output/'components', True)
        from v037_player_foundations import emit_foundations
        cls.foundations = []
        emit_foundations(cls.output/'foundations', cls.foundations)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def leaf(self, tree, name):
        values = list((self.output/tree).rglob(name))
        self.assertEqual(1, len(values), (tree, name))
        return values[0].read_text(encoding='utf-8')

    def test_complete_current_glass_dependencies_inverse_to_authenticated_originals(self):
        inventory = json.loads((self.output/'shared/source-inventory.json').read_bytes())
        for path in current.SHARED_PATHS:
            row = next(row for row in inventory['sources'] if row['path'] == path
                and row.get('sourceIdentity', {}).get('pinnedCommit') == current.COMMIT)
            self.assertEqual(current.COMMIT, row['sourceIdentity']['pinnedCommit'])
            source = (self.output/'shared'/row['output']).read_text(encoding='utf-8')
            lines = source.splitlines(keepends=True)
            while lines[0].startswith(('// OriginalSource:', '// OriginalSHA256:', '// OriginalCommit:')):
                lines.pop(0)
            source = ''.join(lines)
            self.assertEqual(self.originals[path].decode(), inverse(source, row['wholeOriginalToPlatform']))

    def test_actual_changed_callers_match_real_generated_owner_parameter_names(self):
        owners = [*list((self.output/'shared/generated').rglob('*.kt')),
            *list((self.output/'dynamic').rglob('*.kt')), *list((self.output/'home').rglob('*.kt')),
            *list((self.output/'components').rglob('*.kt'))]
        contracts = {
            'FloatingBottomBar': [('shared', 'BottomBarFloatingSegmentedControl.kt')],
            'BottomBarLiquidSegmentedControl': [('home', 'HomeCategoryPage.kt')],
            'AppNativeTabRow': [('shared', 'BottomBarLiquidSegmentedControl.kt')],
            'AppNativeSegmentedControl': [('shared', 'BottomBarLiquidSegmentedControl.kt')],
            'homeTopBottomBarMatchedSurface': [('search', 'SearchScreen.kt')],
            'AppMaterial3SegmentedControl': [('dynamic', 'DesktopOriginalDynamicNativeTabs.kt')],
            'AppMiuixSegmentedControl': [('dynamic', 'DesktopOriginalDynamicNativeTabs.kt')],
            'AppMaterial3TabRow': [('dynamic', 'DesktopOriginalDynamicNativeTabs.kt')],
            'AppMiuixTabRow': [('dynamic', 'DesktopOriginalDynamicNativeTabs.kt')],
        }
        definitions = {}
        for path in owners:
            tokens = self.lexer.kotlin_tokens(path.read_text(encoding='utf-8'))
            for i, (token, _, _) in enumerate(tokens):
                if token != 'fun':
                    continue
                opening = i + 1
                while opening < len(tokens) and tokens[opening][0] != '(':
                    opening += 1
                name = tokens[opening-1][0]
                if name in contracts:
                    self.assertNotIn(name, definitions)
                    definitions[name] = parameters(tokens, opening, declarations=True)
        self.assertEqual(set(contracts), set(definitions))
        for name, callers in contracts.items():
            for tree, leaf in callers:
                tokens = self.lexer.kotlin_tokens(self.leaf(tree, leaf))
                calls = [parameters(tokens, i+1) for i in range(len(tokens)-1)
                    if tokens[i][0] == name and tokens[i+1][0] == '(']
                self.assertTrue(calls, (name, leaf))
                for arguments in calls:
                    self.assertFalse(arguments - definitions[name], (name, leaf, arguments-definitions[name]))

    def test_focused_clear_policy_and_audio_avoidance_use_real_current_sources(self):
        # The v0.4.6 search state layer advanced this v0.4.2 emission; its receipt recovers the v0.4.2 original.
        emitted = self.leaf('search', 'SearchMotionBudgetPolicy.kt')
        self.assertEqual(state_layer.fixed(REPO)[0][state_layer.MOTION_POLICY], emitted)
        proof = json.loads((self.output/'search'/state_layer.PROOF_NAME).read_bytes())
        receipt = next(row for row in proof['policies'] if row['path'] == state_layer.MOTION_POLICY)['retainedToCurrent']
        self.assertEqual(self.originals[current.SEARCH_MOTION].decode(), state_layer.restore(emitted, receipt))
        screen = self.leaf('search', 'SearchScreen.kt')
        self.assertEqual(2, screen.count('(System.nanoTime() / 1_000_000L)'))
        self.assertNotIn('android.os.SystemClock', screen)
        avoidance = self.leaf('search', 'DesktopOriginalSearchNowPlayingAvoidance.kt')
        self.assertIn('.barOverlayVisible', avoidance)
        self.assertIn('AudioNowPlayingSession', avoidance)
        actual = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalNowPlayingOwners.kt').read_text(encoding='utf-8')
        self.assertIn('AudioNowPlayingSession.barOverlayVisible', actual)

    def test_actual_home_chrome_retains_whole_current_interaction_and_native_sizing(self):
        matching = [p for p in (self.output/'home').rglob('*.kt')
            if 'fun Modifier.homeTopBottomBarMatchedSurface(' in p.read_text(encoding='utf-8')]
        self.assertEqual(1, len(matching))
        expected = current.extension_declaration(REPO, self.originals[current.TOP_BAR].decode(), 'homeTopBottomBarMatchedSurface')
        actual = current.extension_declaration(REPO, matching[0].read_text(encoding='utf-8'), 'homeTopBottomBarMatchedSurface')
        self.assertEqual(expected, actual)
        self.assertIn('interactionState = interactionState', actual)
        category = self.leaf('home', 'HomeCategoryPage.kt')
        self.assertIn('equalizeMiuixNonGlassItemWidths = false', category)
        self.assertNotIn('contentSizedMiuixNonGlassItems', category)

    def test_real_native_tabs_foundations_and_content_colors_keep_complete_originals(self):
        for path in current.FOUNDATIONS:
            self.assertEqual(self.originals[path], (self.output/'foundations'/path.split('/main/java/',1)[1]).read_bytes())
        self.assertEqual(self.originals[current.CONTENT_COLORS].decode(), self.leaf('danmaku', 'AppContentColors.kt'))
        native = self.leaf('dynamic', 'DesktopOriginalDynamicNativeTabs.kt')
        self.assertIn('fun resolveLiquidGlassTabSlotHorizontalPadding', native)
        self.assertIn('labelFontSize: TextUnit', native)

    def test_three_original_policy_test_classes_remain_byte_exact_and_outside_main(self):
        rows = [row for row in self.manifest['files'] if '/test/java/' in row['path']]
        self.assertEqual(3, len(rows))
        count = 0
        state_sources = state_layer.fixed(REPO)[0]
        proof = json.loads((self.output/'search'/state_layer.PROOF_NAME).read_bytes())
        for row in rows:
            target = self.output/'tests'/row['path'].split('/test/java/',1)[1]
            if row['path'] == state_layer.MOTION_TEST:
                # Replaced by the v0.4.6 original (18 cases either way); the receipt recovers the v0.4.2 bytes.
                self.assertEqual(state_sources[row['path']].encode(), target.read_bytes())
                receipt = next(item for item in proof['policies'] if item['path'] == row['path'])['retainedToCurrent']
                self.assertEqual(self.originals[row['path']].decode(), state_layer.restore(target.read_text(encoding='utf-8'), receipt))
            else:
                self.assertEqual(self.originals[row['path']], target.read_bytes())
            count += target.read_text(encoding='utf-8').count('@Test')
        self.assertEqual(25, count)
        self.assertEqual([], list((self.output/'search').rglob('*Test.kt')))

    def test_all_actual_consumers_declare_inputs_and_old_direct_motion_copy_is_excluded(self):
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf-8')
        for task in ('extractOriginalHomePage','extractOriginalDanmakuSettings','extractSharedLiquidTabs',
            'extractUpstreamDynamicTabs','extractOriginalPlayerFullControls','extractOriginalSearchPages'):
            start = build.index('val '+task+' by tasks.registering(')
            block = build[start:build.index('\n}', start)]
            self.assertIn('inputs.file("tools/v042_search_dependencies.py")', block, task)
            self.assertIn('inputs.dir("upstream-slices/v042-search-dependencies")', block, task)
        self.assertIn('exclude(canonicalOriginalIdentity("'+current.SEARCH_MOTION+'"))', build)
        bridge = (REPO/'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopOriginalSearchEnvironment.kt').read_text(encoding='utf-8')
        self.assertIn('enabled && LocalDesktopOriginalSearchActive.current', bridge)

    def test_changed_raw_dependency_fails_before_any_source_selection(self):
        with tempfile.TemporaryDirectory(prefix='bilipai-search-dependency-tamper-') as folder:
            root = Path(folder)
            shutil.copytree(REPO/current.ARCHIVE, root/current.ARCHIVE)
            leaf = root/current.ARCHIVE/'FloatingBottomBar.kt'
            leaf.write_bytes(leaf.read_bytes()+b'\n// changed\n')
            with self.assertRaisesRegex(ValueError, 'Changed current Search dependency'):
                current.fixed_sources(root)


if __name__ == '__main__':
    unittest.main()
