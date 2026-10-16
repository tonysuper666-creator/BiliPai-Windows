from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
import ast

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'desktop/tools'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import v042_optical_dependencies as current
import test_v042_search_dependencies_source as search_contracts
from test_v042_search_dependencies_source import inverse, load, parameters


class CurrentOpticalDependencyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        search_contracts.CurrentSearchDependencyTests.setUpClass()
        cls.output = search_contracts.CurrentSearchDependencyTests.output
        cls.lexer = search_contracts.CurrentSearchDependencyTests.lexer
        cls.originals, cls.manifest = current.fixed_sources(REPO)
        from v037_player_page_backdrop import emit_foundation
        cls.backdrop_rows = []
        emit_foundation(REPO, cls.output / 'backdrop', cls.backdrop_rows)

    @classmethod
    def tearDownClass(cls):
        search_contracts.CurrentSearchDependencyTests.tearDownClass()

    def leaf(self, tree, leaf):
        matches = list((self.output / tree).rglob(leaf))
        self.assertEqual(1, len(matches), (tree, leaf))
        return matches[0].read_text('utf8')

    def test_whole_current_shared_optics_inverse_to_authenticated_originals(self):
        inventory = json.loads((self.output / 'shared/source-inventory.json').read_bytes())
        for path in current.SHARED_PATHS:
            row = next(r for r in inventory['sources'] if r['path'] == path)
            self.assertEqual(current.identity(REPO, path), row['sourceIdentity'])
            source = (self.output / 'shared' / row['output']).read_text('utf8')
            while source.startswith('// Original'):
                source = source.split('\n', 1)[1]
            self.assertEqual(self.originals[path].decode(), inverse(source, row['wholeOriginalToPlatform']))

    def test_real_nested_callers_have_current_generated_parameter_contracts(self):
        contracts = [
            ('AppTonalPillTabRow', 'dynamic', 'AppMaterial3TonalPillTabRow.kt', 'dynamic', 'AppMaterial3SegmentedControl.kt'),
            ('liquidDockViewport', 'shared', 'LiquidDockViewport.kt', 'shared', 'AppLiquidAwareTabRow.kt'),
            ('rememberChromeBackdropSource', 'backdrop', 'ChromeBackdropSource.kt', 'shared', 'FloatingBottomBar.kt'),
            ('snapTo', 'shared', 'com/android/purebilibili/feature/home/components/miuix/DampedDragAnimation.kt', 'shared', 'FloatingBottomBar.kt'),
            ('release', 'shared', 'com/android/purebilibili/feature/home/components/miuix/DampedDragAnimation.kt', 'shared', 'FloatingBottomBar.kt'),
            ('followPagerPosition', 'shared', 'com/android/purebilibili/feature/home/components/miuix/DampedDragAnimation.kt', 'shared', 'FloatingBottomBar.kt'),
        ]
        for name, tree, leaf, caller_tree, caller_leaf in contracts:
            tokens = self.lexer.kotlin_tokens(self.leaf(tree, leaf))
            definitions = []
            for i in range(len(tokens) - 1):
                if tokens[i][0] != 'fun':
                    continue
                opening = i + 1
                while tokens[opening][0] != '(':
                    opening += 1
                if tokens[opening - 1][0] == name:
                    definitions.append(parameters(tokens, opening, declarations=True))
            self.assertEqual(1, len(definitions), (name, leaf))
            tokens = self.lexer.kotlin_tokens(self.leaf(caller_tree, caller_leaf))
            calls = [parameters(tokens, i + 1) for i in range(len(tokens) - 1)
                if tokens[i][0] == name and tokens[i + 1][0] == '(']
            self.assertTrue(calls, (name, caller_leaf))
            for arguments in calls:
                self.assertFalse(arguments - definitions[0], (name, caller_leaf, arguments - definitions[0]))
        highlight = self.leaf('shared', 'InteractiveHighlight.kt')
        self.assertIn('val pressProgressProvider: (() -> Float)? = null', highlight)
        self.assertIn('var drawPositionProvider: ((Size) -> Offset?)? = null', highlight)

    def test_single_current_backdrop_preserves_hidden_capture_and_native_recording(self):
        original, expected = current.backdrop_source(REPO)
        self.assertEqual(self.originals[current.CHROME].decode(), original)
        self.assertEqual(expected, self.leaf('backdrop', 'ChromeBackdropSource.kt'))
        from v029_comment_search import whole_proof
        self.assertEqual(original, inverse(expected, whole_proof(original, expected)))
        self.assertIn('drawContentToScreen: Boolean = true', expected)
        self.assertIn('recordingBackground: Color = Color.Unspecified', expected)
        self.assertIn('recordContent: (DrawScope.() -> Unit)? = null', expected)
        self.assertIn('else this@drawWithContent.drawContent()', expected)
        self.assertIn('recordContent = videoRecording', self.leaf('backdrop', 'PlayerPageBackdrop.kt'))
        row = next(r for r in self.backdrop_rows if r['path'].endswith('/ChromeBackdropSource.kt'))
        self.assertEqual(current.source_record(REPO, current.CHROME), row['source'])

    def test_shared_surface_roles_and_pager_motion_use_the_complete_current_kernels(self):
        surface = self.leaf('components', 'AppSurfaceTokens.kt')
        # The existing component writer prepends only its original-source comments.
        self.assertTrue(all(line.startswith('//') for line in surface.splitlines()[:2]))
        self.assertEqual(self.originals[current.SURFACE].decode().strip() + '\n', surface.split('\n', 2)[2])
        self.assertIn('fun selectionContainer()', surface)
        self.assertIn('fun primaryContainer()', surface)
        self.assertIn('fun onPrimaryContainer()', surface)
        tonal = self.leaf('dynamic', 'AppMaterial3TonalPillTabRow.kt')
        self.assertTrue(all(line.startswith('//') for line in tonal.splitlines()[:2]))
        tonal_original = self.originals[current.TONAL].decode().strip() + '\n'
        expected_tonal = tonal_original.replace('androidx.compose.ui.platform.LocalConfiguration',
            'com.bilipai.desktop.ui.DesktopDynamicWindowConfiguration')
        self.assertEqual(expected_tonal, tonal.split('\n', 2)[2])
        self.assertEqual(self.originals[current.MOTION].decode(), self.leaf('components', 'LiquidIndicatorMotion.kt'))
        self.assertIn('pagerPressProgress', self.leaf('components', 'LiquidIndicatorMotion.kt'))
        dock_motion = self.leaf('shared', 'com/android/purebilibili/feature/home/components/miuix/DampedDragAnimation.kt')
        self.assertIn('System.nanoTime() / 1_000_000L', dock_motion)
        self.assertNotIn('import android.os.SystemClock', dock_motion)

    def test_actual_space_producer_maps_retained_content_sizing_to_current_tab_api(self):
        producer = ast.parse((REPO / 'desktop/tools/extract-upstream-space-pages.py').read_bytes())
        edits = [call for call in ast.walk(producer) if isinstance(call, ast.Call)
            and isinstance(call.func, ast.Name) and call.func.id == 'edit' and len(call.args) == 4
            and isinstance(call.args[1], ast.Constant)
            and 'contentSizedMiuixNonGlassItems' in str(call.args[1].value)]
        self.assertEqual(1, len(edits))
        before, after, count = [ast.literal_eval(v) for v in edits[0].args[1:]]
        from v037_tab_roles import apply as apply_tab_roles
        from v041_video_status_labels import apply as apply_status_labels
        from v025_source_paths import canonical_source
        path = current.APP + 'feature/space/SpaceScreen.kt'
        # Validate the real retained caller and exact production recipe, without
        # substituting absent Space prerequisite outputs or claiming a compile.
        original = canonical_source(REPO, path).read_text('utf8')
        original = apply_tab_roles(path, apply_status_labels(path, original))
        self.assertEqual(count, original.count(before))
        self.assertIn('contentSizedMiuixNonGlassItems = true', before)
        self.assertEqual('', after)
        mapped = original.replace(before, after)
        self.assertNotIn('contentSizedMiuixNonGlassItems', mapped)
        self.assertIn('miuixNonGlassItemWidthMode = MiuixNonGlassTabItemWidthMode.CONTENT,', mapped)
        self.assertIn('contentSizedMiuixNonGlassMaxItemWidth = Dp.Infinity,', mapped)
        tokens = self.lexer.kotlin_tokens(self.leaf('dynamic', 'DesktopOriginalDynamicNativeTabs.kt'))
        opening = next(i + 1 for i in range(len(tokens) - 1)
            if tokens[i][0] == 'AppNativeTabRow' and tokens[i + 1][0] == '(')
        allowed = parameters(tokens, opening, declarations=True)
        tokens = self.lexer.kotlin_tokens(mapped)
        calls = [parameters(tokens, i + 1) for i in range(len(tokens) - 1)
            if tokens[i][0] == 'AppNativeTabRow' and tokens[i + 1][0] == '(']
        self.assertTrue(calls)
        for arguments in calls:
            self.assertFalse(arguments - allowed, arguments - allowed)

    def test_actual_producer_inputs_and_manual_ui_mode_keep_strict_boundaries(self):
        build = (REPO / 'desktop/build.gradle.kts').read_text('utf8')
        for task in ('extractSharedLiquidTabs', 'extractUpstreamDynamicTabs',
                'extractUpstreamComponents', 'extractOriginalPlayerFullControls'):
            begin = build.index('val ' + task + ' by tasks.registering(')
            block = build[begin:build.index('\n}', begin)]
            self.assertIn('inputs.file("tools/v042_optical_dependencies.py")', block, task)
            self.assertIn('inputs.dir("upstream-slices/v042-optical-dependencies")', block, task)
        self.assertEqual(1, build.count('exclude(canonicalOriginalIdentity("' + current.SURFACE + '"))'))
        fixture = (REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/WindowsVideoActualRootUiFixture.kt').read_text('utf8')
        begin = fixture.index('                    if (manualReplay) {')
        end = fixture.index('                        return@Thread', begin)
        mode = fixture[begin:end]
        for operation in ('click(', 'mouseWheel(', 'Robot(', 'pressKey(', 'enterVideoThroughActualSearch('):
            self.assertNotIn(operation, mode)
        self.assertIn('put("allPreExitAssertionsPassed", false)', mode)
        self.assertIn('put("automaticInputExecuted", false)', mode)
        self.assertLess(end, fixture.index('                    if (replay != null) enterVideoThroughActualSearch(replay)', begin))

    def test_modified_dependency_bytes_are_rejected_before_selection(self):
        with tempfile.TemporaryDirectory(prefix='bilipai-optics-tamper-') as directory:
            root = Path(directory)
            shutil.copytree(REPO / current.ARCHIVE, root / current.ARCHIVE)
            target = root / current.ARCHIVE / 'ChromeBackdropSource.kt'
            target.write_bytes(target.read_bytes() + b'\n// changed\n')
            with self.assertRaisesRegex(ValueError, 'Changed optical dependency'):
                current.fixed_sources(root)


if __name__ == '__main__':
    unittest.main()
