"""v0.4.6 sheet motion on the Windows-adapted DesktopOriginalDetailAppSheetComponents body."""
from pathlib import Path
import contextlib
import importlib.util
import io
import json
import re
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO/'desktop/tools'
sys.path.insert(0, str(TOOLS))
import v046_sheet_motion as sheet

HEADER = ('// Original source design-system/src/main/java/com/android/purebilibili/core/ui/AppSheetComponents.kt\n'
          '// LF SHA256 a8610387b35385aeb61f67959ea4c32d91721dba9b34d7c541047148df3bac92\n')
V040_GOOD = ('    inputs.files("tools/extract-upstream-dynamic-detail-container.py", "tools/extract-appearance-platform.py",\n'
             '        "tools/sync-upstream.py")\n    inputs.file("tools/v040_native_chrome.py")\n'
             '    inputs.dir("upstream-slices/v040-native-chrome")')


def load(name):
    spec = importlib.util.spec_from_file_location('sheet_motion_'+name.replace('-', '_').replace('.py', ''), TOOLS/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def quiet(function, *args):
    with contextlib.redirect_stdout(io.StringIO()):
        return function(*args)


def files_under(root):
    found = {}
    for path in root.rglob('*'):
        if path.is_symlink() or not path.is_file():
            continue
        found[path.relative_to(root).as_posix()] = path.read_bytes()
    return found


def motion_params(text):
    start = text.index('data class AppBottomSheetMotion(')
    return text[start:text.index('\n)\n', start) + 1]


class OriginalSheetMotionSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bp-sheet-')
        cls.output = Path(cls.temp.name)
        cls.page = load('extract-upstream-dynamic-detail-container.py')
        cls.sources, cls.manifest = sheet.fixed(REPO)
        quiet(cls.page.generate, REPO, cls.output/'after')
        with patch('v046_sheet_motion.advance', side_effect=lambda repo, before: (before, None)):
            quiet(cls.page.generate, REPO, cls.output/'before')
        cls.current = (cls.output/'after'/sheet.OUTPUT_RELATIVE).read_text(encoding='utf8')
        cls.previous = (cls.output/'before'/sheet.OUTPUT_RELATIVE).read_text(encoding='utf8')
        cls.proof = json.loads((cls.output/'after'/sheet.PROOF_NAME).read_text(encoding='utf8'))
        cls.body = cls.current[len(HEADER):]
        cls.predecessor = cls.previous[len(HEADER):]

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_archive_license_and_manifest_reject_tampered_or_extra_leaves(self):
        self.assertEqual(sheet.COMMIT, self.manifest['commit'])
        self.assertEqual(self.sources['AppSheetComponents.kt'], (REPO/sheet.ARCHIVE/'AppSheetComponents.kt').read_text(encoding='utf8'))
        self.assertEqual('0a62399789bc06570c3d3b26b91b93e7b2f0b8ef', self.manifest['files'][0]['gitBlob'])
        self.assertEqual('1693624e55708780fc8f10bcfb4dee9c60fde5451eaaa1633019eb3805a02504', self.manifest['files'][0]['sha256'])
        self.assertEqual(sheet.PREDECESSOR_SHA256, self.manifest['beforeFinalOutputs'][sheet.OUTPUT_RELATIVE]['sha256'])
        for name in [*sheet.EXPECTED, 'manifest.json', 'LICENSE', 'unexpected.kt']:
            with self.subTest(name=name), tempfile.TemporaryDirectory(prefix='bp-sheet-t-') as temp:
                root = Path(temp)/sheet.ARCHIVE
                shutil.copytree(REPO/sheet.ARCHIVE, root)
                file = root/name
                file.write_bytes((file.read_bytes() if file.exists() else b'')+b'\n')
                with self.assertRaises(ValueError):
                    sheet.fixed(Path(temp))

    def test_emitted_body_restores_v046_original_and_the_pinned_predecessor(self):
        self.assertTrue(self.current.startswith(HEADER))
        self.assertNotIn(b'\r', self.current.encode('utf8'))
        self.assertEqual(self.sources['AppSheetComponents.kt'], sheet.restore(self.body, self.proof['originalToWindows']))
        self.assertEqual(self.predecessor, sheet.restore(self.body, self.proof['retainedToCurrent']))
        self.assertEqual(sheet.PREDECESSOR_SHA256, sheet.sha(self.predecessor))
        self.assertEqual(sheet.PREDECESSOR_SHA256, self.proof['predecessorSHA256'])
        self.assertTrue(self.proof['predecessorIsAdaptSheetBodyWithoutProducerHeader'])
        self.assertTrue(self.proof['windowsAdaptationsRetained'])
        self.assertEqual([], self.proof['stoppedHunks'])
        self.assertEqual([label for label, _old, _new in sheet.HUNKS], self.proof['appliedHunks'])
        self.assertEqual([row['label'] for row in sheet.MECHANICAL_REANCHORS],
                         [row['label'] for row in self.proof['mechanicalReanchors']])
        advanced, proof = sheet.advance(REPO, self.predecessor)
        self.assertEqual(self.body, advanced)
        self.assertEqual(self.sources['AppSheetComponents.kt'], sheet.restore(advanced, proof['originalToWindows']))

    def test_layer_changes_only_the_sheet_and_its_proof_and_drift_fails_closed(self):
        before = files_under(self.output/'before')
        after = files_under(self.output/'after')
        diff = sorted(path for path in set(before) | set(after) if before.get(path) != after.get(path))
        self.assertEqual([sheet.OUTPUT_RELATIVE, sheet.PROOF_NAME], diff)
        self.assertFalse((self.output/'before'/sheet.PROOF_NAME).exists())
        self.assertEqual(before['v037-whole-app-sheet-platform-proof.json'], after['v037-whole-app-sheet-platform-proof.json'])
        for bad in (self.body, self.predecessor+'\n', self.predecessor.replace('package ', 'pakage ', 1), ''):
            with self.subTest(bad=bad[:40]), self.assertRaisesRegex(ValueError, 'predecessor changed'):
                sheet.advance(REPO, bad)

    def test_real_producer_repeats_and_overlap_rejection_preserves_raw_leaves(self):
        original = {p.name: p.read_bytes() for p in (REPO/sheet.ARCHIVE).iterdir() if p.is_file() and not p.is_symlink()}
        snapshot = files_under(self.output/'after')
        quiet(self.page.generate, REPO, self.output/'after')
        self.assertEqual(snapshot, files_under(self.output/'after'))
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.page.generate(REPO, REPO/sheet.ARCHIVE/'child')
        self.assertEqual(original, {p.name: p.read_bytes() for p in (REPO/sheet.ARCHIVE).iterdir() if p.is_file() and not p.is_symlink()})
        self.assertFalse((REPO/sheet.ARCHIVE/'child').exists())

    def test_gradle_task_and_catalog_track_the_archive(self):
        sync = load('sync-upstream.py')
        rows = [row for row in sync.adapted_compatibility_inputs(REPO) if str(row['localPath']).startswith(sheet.ARCHIVE+'/')]
        self.assertEqual([sheet.ARCHIVE+'/AppSheetComponents.kt'], [row['localPath'] for row in rows])
        self.assertEqual(['v046-sheet-motion'], rows[0]['features'])
        self.assertFalse(rows[0]['referenceOnly'])
        self.assertTrue(rows[0]['partialReuse'])
        self.assertIn('reducedMotion', rows[0]['usedScope'])
        self.assertIn('dragSettleSpec', rows[0]['usedScope'])
        self.assertIn('AppContentColors.primaryText()', rows[0]['usedScope'])
        catalog = json.loads((TOOLS/'compatibility-source-inputs.json').read_bytes())
        self.assertIn(sheet.ARCHIVE, catalog['buildInputRoots'])
        # Registration is append-only, so the archive keeps its exact position when later slices are appended
        # (v046-home-refresh-ui-policy and v046-comment-polish follow it); it was the last root when this was written.
        self.assertEqual(1, catalog['buildInputRoots'].count(sheet.ARCHIVE))
        self.assertEqual(138, catalog['buildInputRoots'].index(sheet.ARCHIVE))
        archives = [row for row in catalog['archives'] if row['directory'] == sheet.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual([sheet.ARCHIVE+'/AppSheetComponents.kt', sheet.ARCHIVE+'/LICENSE', sheet.ARCHIVE+'/manifest.json'],
                         sorted(leaf['path'] for leaf in archives[0]['files']))
        build = (REPO/'desktop/build.gradle.kts').read_text(encoding='utf8')
        at = build.index('val extractUpstreamDynamicDetailContainer by tasks.registering(')
        block = build[at:build.index('\n}', at)]
        self.assertEqual(1, block.count('inputs.file("tools/v046_sheet_motion.py")'))
        self.assertEqual(1, block.count('inputs.dir("upstream-slices/v046-sheet-motion")'))
        self.assertEqual(1, build.count('inputs.file("tools/v046_sheet_motion.py")'))
        self.assertEqual(1, build.count('inputs.dir("upstream-slices/v046-sheet-motion")'))
        self.assertEqual(1, build.count(V040_GOOD))
        producer = (TOOLS/'extract-upstream-dynamic-detail-container.py').read_text(encoding='utf8')
        self.assertNotIn('v046_sheet_motion', producer.split('def safe(', 1)[0])
        generate = producer[producer.index('def generate('):]
        self.assertLess(generate.index('OUTPUT = Path(output)'), generate.index('from v046_sheet_motion import validate_outputs'))
        self.assertLess(generate.index('from v046_sheet_motion import validate_outputs'), generate.index('_generate_body()'))
        thread = producer[producer.index('def thread_container()'):producer.index('def _generate_body()')]
        self.assertEqual(1, thread.count('from v046_sheet_motion import advance'))
        self.assertEqual(1, producer.count('from v046_sheet_motion import advance'))
        self.assertEqual(1, producer.count('from v046_sheet_motion import validate_outputs'))
        self.assertEqual(1, producer.count(sheet.PROOF_NAME))
        self.assertIn('if sheet_proof is not None:', thread)

    def test_motion_members_are_declared_once_and_windows_adaptations_stay(self):
        params = motion_params(self.body)
        self.assertEqual(1, params.count('val reducedMotion:'))
        self.assertEqual(1, params.count('val dragSettleSpec:'))
        self.assertEqual(1, self.body.count('val reducedMotion:'))
        self.assertEqual(1, self.body.count('val dragSettleSpec:'))
        self.assertEqual(1, self.body.count('val reducedMotion = LocalSystemReduceMotion.current || !LocalComponentMotionEnabled.current'))
        self.assertIn('reducedMotion: Boolean = false,', params)
        self.assertIn('dragSettleSpec: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 600f),', params)
        self.assertIn('internal fun resolveAdaptiveBottomSheetMotionSpec(\n    uiStyle: AppUiStyle,\n    reducedMotion: Boolean = false,', self.body)
        self.assertIn('contentColor: Color = AppContentColors.primaryText(),', self.body)
        self.assertIn('visible: Boolean = true,', self.body)
        self.assertIn('onDismissStarted: () -> Unit = {},', self.body)
        self.assertIn('sheetState.hide()', self.body)
        self.assertIn('sheetState.show()', self.body)
        self.assertIn('DesktopCommentDialogNavigationHost', self.body)
        self.assertIn('LocalWindowSizeClass', self.body)
        self.assertIn('findRootCoordinates()', self.body)
        self.assertIn('neverEqualPolicy()', self.body)
        for token in ('LocalView', 'LocalConfiguration', 'import android.', 'hingeSafeRegions',
                      'HingeSafeOverlayHost', 'findViewTreeNavigationEventDispatcherOwner',
                      'LocalHingeSafeOverlayRegions', 'decorFitsSystemWindows'):
            self.assertNotIn(token, self.body, token)
        self.assertIsNone(re.search(r'(?<![\w.])android\.', self.body))
        syntax = load('sync-upstream.py')
        stack = []
        for token, first, _last in syntax.kotlin_tokens(self.current):
            if token in '([{':
                stack.append(token)
            elif token in ')]}':
                self.assertTrue(stack, first)
                self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
        self.assertEqual([], stack)


if __name__ == '__main__':
    unittest.main()
