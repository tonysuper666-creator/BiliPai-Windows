"""v0.4.6 image viewer on the existing dynamic card producer.

Source checks only: nothing here compiles Kotlin or runs a JVM test.
"""
from pathlib import Path
from unittest import mock
import hashlib
import importlib.util
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v046_image_viewer as source

spec = importlib.util.spec_from_file_location('sync_upstream_image_viewer', TOOLS / 'sync-upstream.py')
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)

producer_spec = importlib.util.spec_from_file_location('dynamic_card_image_viewer', TOOLS / 'extract-upstream-dynamic-card.py')
producer = importlib.util.module_from_spec(producer_spec)
producer_spec.loader.exec_module(producer)

COMPONENTS = 'com/android/purebilibili/feature/dynamic/components/'
RENDERER = COMPONENTS + 'DesktopOriginalImagePreviewRenderer.kt'
PRESENTATION_PROOF = 'windows-comment-image-presentation.json'
EXCLUDED = {source.ZOOMABLE: source.UPSTREAM_PATHS[source.ZOOMABLE], source.GESTURE: source.UPSTREAM_PATHS[source.GESTURE]}
TOP_LEVEL = {
    'ZOOMABLE_IMAGE_TAG': ('internal', 'val'),
    'ZoomableImage': ('public', 'fun'),
    'ZoomableImageGestureMode': ('internal', 'class'),
    'resolveZoomableImageGestureMode': ('internal', 'fun'),
    'resolveZoomableImagePanBounds': ('internal', 'fun'),
    'clampZoomableImageOffset': ('internal', 'fun'),
    'resolveZoomableImageFlingVelocity': ('internal', 'fun'),
}
STATIC_ROOTS = (
    'app/src/main/java',
    'design-system/src/main/java',
    'core-data/src/main/java',
    'core-player/src/main/java',
    'network-core/src/main/java',
    'settings-core/src/main/java',
    'desktop/src',
    'desktop/upstream-slices',
)
JVM_TEST_CLASSES = ('com.android.purebilibili.feature.dynamic.components.ZoomableImageMotionPolicyTest',
                    'com.android.purebilibili.feature.dynamic.components.ImagePreviewGesturePolicyTest')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def lf(raw):
    """The card producer writes with the platform newline, so its own files may hold CRLF on Windows."""
    return raw.decode('utf-8').replace('\r\n', '\n')


def archive_files():
    return {p.name: p.read_bytes() for p in (REPO / source.ARCHIVE).iterdir() if p.is_file()}


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def prepare_block(build):
    start = build.index('val prepareUpstreamSources by tasks.registering(Sync::class) {')
    return build[start:build.index('\nval extractUpstreamApi ', start)]


def declarations(text, path):
    info = gate.parse_kotlin(text, path, 'generated', 'main')
    return info.package, [(item.visibility, item.kind, item.name) for item in info.decls]


def declaration_hits(root, names):
    hits = []
    base = Path(root)
    if not base.exists():
        return hits
    for path in base.rglob('*'):
        if not path.is_file() or path.suffix not in {'.kt', '.kts'}:
            continue
        raw = path.read_bytes()
        if not any(name.encode('utf-8') in raw for name in names):
            continue
        relative = path.relative_to(REPO).as_posix() if str(path).startswith(str(REPO)) else path.as_posix()
        _package, found = declarations(raw.decode('utf-8').replace('\r\n', '\n'), relative)
        for visibility, kind, name in found:
            if name in names:
                hits.append((relative, visibility, kind, name))
    return hits


class V046ImageViewerSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.archive = archive_files()
        cls.manifest = json.loads(cls.archive['manifest.json'])
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-iv-')
        cls.root = Path(cls.temporary.name)
        cls.main_root, cls.test_root = cls.root / 'main', cls.root / 'tests'
        captured = {}
        original = source.advance_preview_renderer

        def spy(repo, before):
            after, proof = original(repo, before)
            captured.update(before=before, after=after, proof=proof)
            return after, proof

        with mock.patch.object(source, 'advance_preview_renderer', spy):
            cls.files = producer.generate(REPO, cls.main_root)
        cls.before, cls.after, cls.renderer_proof = captured['before'], captured['after'], captured['proof']
        cls.test_files = producer.generate_image_viewer_tests(REPO, cls.test_root)
        cls.main = tree(cls.main_root)
        cls.tests = tree(cls.test_root)
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))
        cls.proof = json.loads(cls.main[source.PROOF_NAME])
        with mock.patch.object(source, 'advance_preview_renderer', lambda repo, before: (before, None)):
            cls.patched_root = cls.root / 'patched'
            producer.generate(REPO, cls.patched_root)
        cls.patched = tree(cls.patched_root)
        cls.standalone_root = cls.root / 'standalone'
        producer.generate(REPO, cls.standalone_root, True)
        cls.standalone = tree(cls.standalone_root)

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    # --- archive -------------------------------------------------------------------------------------

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        leaves = sorted(self.archive)
        self.assertEqual(sorted(source.EXPECTED | {'LICENSE', 'manifest.json'}), leaves)
        for leaf in leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-iv-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / source.ARCHIVE, repo / source.ARCHIVE)
                target = repo / source.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    source.fixed(repo)
        for kind in ('extra', 'missing'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix='bp-iv-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / source.ARCHIVE, repo / source.ARCHIVE)
                if kind == 'extra':
                    (repo / source.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
                else:
                    (repo / source.ARCHIVE / 'LICENSE').unlink()
                with self.assertRaises(ValueError):
                    source.fixed(repo)
        self.assertEqual(self.archive, archive_files())

    def test_archive_leaves_equal_the_upstream_blobs_and_stay_lf(self):
        self.assertEqual(source.MANIFEST_SHA256, sha(self.archive['manifest.json']))
        self.assertEqual(source.COMMIT, self.manifest['commit'])
        self.assertEqual(source.UPSTREAM_PATHS, {row['filename']: row['path'] for row in self.manifest['files']})
        for name, raw in self.archive.items():
            with self.subTest(leaf=name):
                self.assertNotIn(b'\r', raw)
        have_git = subprocess.run(['git', '-C', str(REPO), 'cat-file', '-e', source.COMMIT + '^{commit}'],
                                  capture_output=True).returncode == 0
        for row in self.manifest['files']:
            with self.subTest(leaf=row['filename']):
                raw = self.archive[row['filename']]
                self.assertEqual((len(raw), sha(raw), hashlib.sha1(b'blob %d\0' % len(raw) + raw).hexdigest()),
                                 (row['bytes'], row['sha256'], row['gitBlob']))
                if have_git:
                    blob = subprocess.run(['git', '-c', 'core.autocrlf=false', '-C', str(REPO), 'cat-file', 'blob',
                                           source.COMMIT + ':' + row['path']], capture_output=True, check=True).stdout
                    self.assertEqual(blob, raw)

    def test_manifest_states_the_scope_and_what_is_not_carried(self):
        self.assertFalse(self.manifest['compiled'])
        self.assertFalse(self.manifest['jvmTestsExecuted'])
        self.assertIn('ImagePreviewDialog.kt (other hunks)', self.manifest['notCarried'])
        self.assertIn('ZoomableImageScalePolicy.kt', self.manifest['notCarried'])
        # The unchanged scale policy is not archived; the vendored copy is the upstream v0.4.6 blob already.
        scale = (REPO / (source.COMPONENTS + 'ZoomableImageScalePolicy.kt')).read_bytes().replace(b'\r\n', b'\n')
        self.assertEqual('7294fdae6ad0e40ad16f5011a88be1ea3020433d',
                         hashlib.sha1(b'blob %d\0' % len(scale) + scale).hexdigest())
        self.assertNotIn('ZoomableImageScalePolicy.kt', self.archive)

    # --- verbatim main files -------------------------------------------------------------------------

    def test_main_files_are_emitted_verbatim_and_the_dialog_is_not_emitted(self):
        for name in source.MAIN_FILES:
            with self.subTest(name=name):
                self.assertEqual(self.archive[name], self.main[COMPONENTS + name])
                row = self.proof['files'][name]
                self.assertEqual(COMPONENTS + name, row['relative'])
                self.assertTrue(row['emittedVerbatim'])
                self.assertEqual(sha(self.archive[name]), row['sha256'])
        self.assertEqual([], [p for p in self.main if p.endswith('Test.kt')])
        self.assertEqual([], [p for p in self.main if p.endswith('/' + source.DIALOG)])
        dialog = self.archive[source.DIALOG]
        self.assertEqual([], [p for p, raw in self.main.items() if p.endswith('.kt') and raw == dialog])
        self.assertFalse(self.proof['dialog']['emitted'])
        self.assertTrue(self.proof['dialog']['pinnedWhole'])
        self.assertEqual(sha(dialog), self.proof['dialog']['sha256'])
        self.assertEqual({source.ZOOMABLE: source.UPSTREAM_PATHS[source.ZOOMABLE],
                          source.GESTURE: source.UPSTREAM_PATHS[source.GESTURE], source.MOTION: None},
                         {name: row['replacesCanonical'] for name, row in self.proof['files'].items()})
        self.assertFalse(self.proof['mainCompiled'])
        self.assertFalse(self.proof['jvmTestsExecuted'])
        self.assertFalse(self.proof['runtimeExecuted'])
        # Like the other slice emitters, the receipt and these files are not part of the producer's own list.
        for name in source.MAIN_FILES:
            self.assertNotIn(self.main_root / COMPONENTS / name, self.files)

    def test_the_vendored_direct_copies_are_the_old_versions_and_the_motion_policy_is_new(self):
        for name, path in EXCLUDED.items():
            with self.subTest(name=name):
                vendored = (REPO / path).read_bytes().replace(b'\r\n', b'\n')
                self.assertNotEqual(vendored, self.archive[name])
        old_gesture = (REPO / EXCLUDED[source.GESTURE]).read_text(encoding='utf-8')
        self.assertIn('else -> ZoomableImageGestureMode.IMAGE_INTERACTION', old_gesture.replace('\r\n', '\n'))
        new_gesture = self.archive[source.GESTURE].decode('utf-8')
        self.assertIn('else -> ZoomableImageGestureMode.UNDECIDED', new_gesture)
        self.assertNotIn('else -> ZoomableImageGestureMode.IMAGE_INTERACTION', new_gesture)
        self.assertFalse((REPO / (source.COMPONENTS + source.MOTION)).exists())
        zoomable = self.archive[source.ZOOMABLE].decode('utf-8')
        for token in ('PointerEventPass.Initial', 'VelocityTracker()', 'resolveZoomableImagePanBounds(',
                      'resolveZoomableImageFlingVelocity(', 'animateTransform(targetScale, targetOffset, 250)',
                      'import com.android.purebilibili.core.ui.LocalSystemReduceMotion\n'):
            self.assertIn(token, zoomable, token)
        self.assertIn('if (reduceMotion || scale <= 1.01f) return', zoomable)

    def test_each_top_level_name_has_one_definer_and_the_vendored_duplicates_are_excluded(self):
        names = set(TOP_LEVEL)
        for leaf in source.MAIN_FILES:
            package, decls = declarations(self.archive[leaf].decode('utf-8'), leaf)
            self.assertEqual(source.PACKAGE, package)
            for visibility, kind, name in decls:
                self.assertEqual(TOP_LEVEL[name][0], visibility, name)
        hits = []
        for relative in STATIC_ROOTS:
            hits.extend(declaration_hits(REPO / relative, names))
        by_name = {}
        for path, _visibility, _kind, name in hits:
            by_name.setdefault(name, []).append(path)
        self.assertEqual(names, set(by_name))
        archive_prefix = source.ARCHIVE + '/'
        for name, paths in by_name.items():
            with self.subTest(name=name):
                archived = [p for p in paths if p.startswith(archive_prefix)]
                self.assertEqual(1, len(archived), paths)
                vendored = [p for p in paths if not p.startswith(archive_prefix)]
                # Every vendored definer is one of the two excluded direct files; the motion policy has none.
                self.assertTrue(set(vendored) <= set(EXCLUDED.values()), vendored)
                self.assertEqual([], [p for p in paths if p.startswith('desktop/src')])
        for name in ('resolveZoomableImagePanBounds', 'clampZoomableImageOffset', 'resolveZoomableImageFlingVelocity'):
            self.assertEqual([source.ARCHIVE + '/' + source.MOTION], by_name[name], name)
        # The producer output defines each name exactly once as well.
        generated = declaration_hits(self.main_root, names)
        for name in names:
            found = [row for row in generated if row[3] == name]
            self.assertEqual(1, len(found), (name, found))
            self.assertTrue(found[0][0].endswith(tuple(COMPONENTS + leaf for leaf in source.MAIN_FILES)), found)
        self.assertEqual([], declaration_hits(self.test_root, names))

    # --- the Windows preview renderer ----------------------------------------------------------------

    def test_renderer_layer_has_an_exact_inverse_and_changes_only_the_two_pinned_hunks(self):
        self.assertEqual(source.RENDERER_PREDECESSOR_SHA256, source.sha(self.before.replace('\r\n', '\n')))
        self.assertEqual(self.before, source.restore(self.after, self.renderer_proof))
        self.assertEqual(self.renderer_proof, source.advance_preview_renderer(REPO, self.before)[1])
        self.assertTrue(self.renderer_proof['wholeInverseExact'])
        self.assertEqual(2, len(self.renderer_proof['hunks']))
        self.assertEqual(source.sha(self.after), self.renderer_proof['afterSHA256'])
        self.assertEqual(source.PAGER_BEFORE_SHA256, self.renderer_proof['pagerBeforeSHA256'])
        self.assertEqual(source.PAGER_AFTER_SHA256, self.renderer_proof['pagerAfterSHA256'])
        self.assertEqual(source.UPSTREAM_PATHS[source.DIALOG], self.renderer_proof['source'])
        self.assertEqual(sha(self.archive[source.DIALOG]), self.renderer_proof['sourceSHA256'])
        self.assertFalse(self.renderer_proof['jvmTestsExecuted'])
        self.assertFalse(self.renderer_proof['mainCompiled'])
        self.assertEqual(self.renderer_proof, self.proof['renderer'])
        # Both hunks are present, in this order, in the emitted renderer file.
        emitted = lf(self.main[RENDERER])
        position = -1
        for hunk in self.renderer_proof['hunks']:
            self.assertIn(hunk['after'], emitted)
            found = emitted.index(hunk['after'])
            self.assertGreater(found, position)
            position = found
        # Taking each hunk out again reproduces the file the producer wrote before this layer existed.
        restored = emitted
        for hunk in self.renderer_proof['hunks']:
            self.assertEqual(1, restored.count(hunk['after']))
            restored = restored.replace(hunk['after'], hunk['before'], 1)
        self.assertEqual(lf(self.patched[RENDERER]), restored)

    def test_pager_span_is_a_verbatim_slice_of_the_pinned_v046_dialog(self):
        dialog = self.archive[source.DIALOG].decode('utf-8')
        start, end = source._pager_span(dialog, 'test')
        span = dialog[start:end]
        self.assertEqual(source.PAGER_AFTER_SHA256, source.sha(span))
        self.assertEqual(1, dialog.count(span))
        self.assertIn(span, self.after)
        self.assertEqual(1, self.after.count(span))
        self.assertEqual(5, span.count('\n'))
        self.assertIn("// The image recognizer consumes vertical moves. Do not restart Pager's\n", span)
        self.assertIn('userScrollEnabled = !isDismissing && !isPreparingDismiss &&\n', span)
        self.assertIn('activeZoomScale <= 1.01f,\n', span)
        self.assertNotIn('isVerticalDismissDragging', span)
        # The old rule is exactly the 0.2.5 span that the renderer held.
        old = source._pager_span(self.before, 'before')
        self.assertEqual(source.PAGER_BEFORE_SHA256, source.sha(self.before[old[0]:old[1]]))
        self.assertIn('userScrollEnabled = !isVerticalDismissDragging &&\n', self.before)
        self.assertNotIn('userScrollEnabled = !isVerticalDismissDragging', self.after)
        # The vertical-dismiss flag itself stays: only the pager no longer restarts on it.
        self.assertEqual(self.before.count('isVerticalDismissDragging') - 1, self.after.count('isVerticalDismissDragging'))
        self.assertIn('isVerticalDismissDragging = true', self.after)
        self.assertEqual(1, self.after.count('beyondViewportPageCount = 1,'))

    def test_reduced_motion_signal_is_provided_once_around_the_preview_content(self):
        self.assertEqual(1, self.before.count(source.PROVIDER_BEFORE))
        self.assertEqual(0, self.after.count(source.PROVIDER_BEFORE))
        self.assertEqual(1, self.after.count(source.PROVIDER_AFTER))
        self.assertEqual(1, self.after.count('LocalSystemReduceMotion provides'))
        self.assertEqual(0, self.before.count('LocalSystemReduceMotion'))
        self.assertEqual(1, self.after.count('com.bilipai.desktop.ui.LocalDesktopDynamicCardBindings provides request.platform,'))
        text = lf(self.main[RENDERER])
        self.assertEqual(1, text.count('com.android.purebilibili.core.ui.LocalSystemReduceMotion provides\n'))
        self.assertEqual(1, text.count('com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion(),\n'))
        # The Windows binding is the one Root SPI_GETCLIENTAREAANIMATION reader and is visible to generated code.
        binding = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopDynamicUserPlatform.kt').read_text(encoding='utf-8')
        self.assertEqual(1, binding.count('@Composable internal fun rememberDesktopDynamicReduceMotion():Boolean {'))
        self.assertIn('0x1042', binding)
        # The Android local that ZoomableImage reads has a definer in a carried archive; its default is false.
        definer = (REPO / 'desktop/upstream-slices/v042-search-dependencies/AppThemeConfig.kt').read_text(encoding='utf-8')
        self.assertIn('val LocalSystemReduceMotion = compositionLocalOf { false }', definer)

    def test_patching_the_layer_out_changes_only_the_renderer_and_the_receipts(self):
        self.assertEqual(set(self.main), set(self.patched))
        different = sorted(path for path in self.main if self.main[path] != self.patched[path])
        self.assertEqual(sorted([RENDERER, PRESENTATION_PROOF, source.PROOF_NAME]), different)
        self.assertIsNone(json.loads(self.patched[source.PROOF_NAME])['renderer'])
        for name in source.MAIN_FILES:
            self.assertEqual(self.main[COMPONENTS + name], self.patched[COMPONENTS + name])

    def test_renderer_layer_fails_closed_on_a_drifted_or_already_advanced_predecessor(self):
        before, after = self.before, self.after
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            source.advance_preview_renderer(REPO, after)
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            source.advance_preview_renderer(REPO, before + '\n')
        with self.assertRaisesRegex(ValueError, 'predecessor changed'):
            source.advance_preview_renderer(REPO, '')
        with self.assertRaisesRegex(ValueError, 'anchor changed'):
            source._replace_once('nothing here', source.PROVIDER_BEFORE, source.PROVIDER_AFTER, 'x')
        with self.assertRaisesRegex(ValueError, 'anchor changed'):
            source._replace_once(before + before, source.PROVIDER_BEFORE, source.PROVIDER_AFTER, 'x')
        with self.assertRaisesRegex(ValueError, 'pager anchors changed'):
            source._pager_span('nothing here', 'x')
        with self.assertRaisesRegex(ValueError, 'pager anchors changed'):
            source._pager_span(before + before, 'x')
        reordered = source.PAGER_END + 'filler\n' + source.PAGER_START
        with self.assertRaisesRegex(ValueError, 'pager span changed'):
            source._pager_span(reordered, 'x')
        with mock.patch.object(source, 'RENDERER_PREDECESSOR_SHA256', source.sha(before.replace('userScrollEnabled', 'userScroll'))):
            with self.assertRaisesRegex(ValueError, 'pager anchors changed|pager span changed|predecessor changed'):
                source.advance_preview_renderer(REPO, before.replace('userScrollEnabled', 'userScroll'))
        changed = before.replace('activeZoomScale <= 1.01f,', 'activeZoomScale <= 1.02f,', 1)
        with mock.patch.object(source, 'RENDERER_PREDECESSOR_SHA256', source.sha(changed)):
            with self.assertRaisesRegex(ValueError, 'pager span changed'):
                source.advance_preview_renderer(REPO, changed)
        with self.assertRaisesRegex(ValueError, 'predecessor inverse failed|inverse span changed|differs from its receipt'):
            source.restore(after + 'x', self.renderer_proof)
        tampered = json.loads(json.dumps(self.renderer_proof))
        tampered['beforeSHA256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'predecessor inverse failed'):
            source.restore(after, tampered)

    def test_standalone_mode_keeps_the_original_bodies_and_skips_this_slice(self):
        self.assertNotIn(source.PROOF_NAME, self.standalone)
        for name in source.MAIN_FILES:
            self.assertNotIn(COMPONENTS + name, self.standalone)
        # Isolated compilation still receives the original direct bodies under their DesktopOriginal names.
        original = lf(self.standalone[COMPONENTS + 'DesktopOriginalZoomableImage.kt'])
        self.assertIn('IMAGE_INTERACTION', lf(self.standalone[COMPONENTS + 'DesktopOriginalZoomableImageGesturePolicy.kt']))
        self.assertNotIn('LocalSystemReduceMotion', original)
        renderer = lf(self.standalone[RENDERER])
        self.assertNotIn('LocalSystemReduceMotion', renderer)
        self.assertIn('userScrollEnabled = !isVerticalDismissDragging &&\n', renderer)
        self.assertEqual(lf(self.patched[RENDERER]), renderer)

    # --- original JVM suites -------------------------------------------------------------------------

    def test_original_jvm_suites_are_emitted_verbatim_in_the_test_root_only(self):
        for name in source.TEST_FILES:
            with self.subTest(name=name):
                self.assertEqual(self.archive[name], self.tests[COMPONENTS + name])
                text = self.archive[name].decode('utf-8')
                self.assertEqual(source.TEST_CASES[name], text.count('@Test'))
                self.assertIn('import kotlin.test.Test\n', text)
                self.assertNotIn('org.junit', text)
                self.assertNotRegex(text, r'(?m)^import android\.')
        self.assertEqual({5, 8}, set(source.TEST_CASES.values()))
        self.assertEqual({COMPONENTS + name for name in source.TEST_FILES},
                         {p for p in self.tests if p.endswith('.kt')})
        self.assertEqual(2, len(self.test_files))
        proof = json.loads(self.tests[source.TESTS_PROOF_NAME])
        self.assertEqual(dict(source.TEST_CASES), {name: row['cases'] for name, row in proof['tests'].items()})
        self.assertFalse(proof['jvmTestsExecuted'])
        self.assertEqual([], [p for p in self.main if p.endswith('.kt') and
                              ('ZoomableImageMotionPolicyTest' in lf(self.main[p]) or 'ImagePreviewGesturePolicyTest' in lf(self.main[p]))])
        self.assertEqual(['ZoomableImageMotionPolicyTest', 'ImagePreviewGesturePolicyTest'],
                         [c.rsplit('.', 1)[1] for c in JVM_TEST_CLASSES])
        # The two class names have no second definer in the hand-written Windows tests.
        for path in (REPO / 'desktop/src/test').rglob('*.kt'):
            text = path.read_text(encoding='utf-8')
            self.assertNotIn('class ZoomableImageMotionPolicyTest', text, str(path))
            self.assertNotIn('class ImagePreviewGesturePolicyTest', text, str(path))

    def test_the_gesture_suite_pins_the_diagonal_start_and_the_motion_suite_the_pan_bounds(self):
        gesture = self.archive[source.GESTURE_TEST].decode('utf-8')
        self.assertIn('fun diagonalStart_waitsForDirectionThenAllowsUpwardDismiss()', gesture)
        self.assertEqual(1, gesture.count('ZoomableImageGestureMode.UNDECIDED'))
        motion = self.archive[source.MOTION_TEST].decode('utf-8')
        self.assertIn('fun wideImage_canPanHorizontallyButNotIntoVerticalBlankSpace()', motion)
        self.assertIn('fun flingIgnoresSlowOrInvalidMotionAndCapsFastMotionInDp()', motion)
        # Both suites call only declarations that exist in the compile universe.
        transition = (REPO / (source.COMPONENTS + 'ImagePreviewTransitionPolicy.kt')).read_text(encoding='utf-8')
        for name in ('shouldEnableImagePreviewVerticalDismiss', 'resolveImagePreviewVerticalDismissDecision',
                     'resolveImagePreviewVerticalDragFrame'):
            self.assertEqual(1, transition.count('internal fun ' + name + '('), name)
            self.assertIn(name + '(', gesture)

    # --- determinism, overlap, special targets -------------------------------------------------------

    def test_generation_is_deterministic_and_incremental_reruns_match(self):
        again = self.root / 'again'
        producer.generate(REPO, again)
        # The producer writes its own files with the platform newline; every file is stable across runs.
        self.assertEqual(self.main, tree(again))
        producer.generate(REPO, again)
        self.assertEqual(self.main, tree(again))
        tests_again = self.root / 'tests-again'
        producer.generate_image_viewer_tests(REPO, tests_again)
        producer.generate_image_viewer_tests(REPO, tests_again)
        self.assertEqual(self.tests, tree(tests_again))

    def test_command_line_writes_the_tests_outside_the_main_root_and_refuses_an_overlap(self):
        main, tests = self.root / 'cli-main', self.root / 'cli-tests'
        environment = dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1', PYTHONUTF8='1')
        done = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(TOOLS / 'extract-upstream-dynamic-card.py'),
                               '--repo', str(REPO), '--output', str(main), '--test-output', str(tests)],
                              cwd=str(REPO / 'desktop'), env=environment, capture_output=True, check=False)
        self.assertEqual(0, done.returncode, done.stderr.decode('utf-8', 'replace')[-2000:])
        self.assertIn('Generated 2 image viewer test source files', done.stdout.decode('utf-8'))
        self.assertEqual(self.main, tree(main))
        self.assertEqual(self.tests, tree(tests))
        nested = self.root / 'cli-nested'
        refused = subprocess.run([sys.executable, '-X', 'utf8', '-B', str(TOOLS / 'extract-upstream-dynamic-card.py'),
                                  '--repo', str(REPO), '--output', str(nested), '--test-output', str(nested / 'test')],
                                 cwd=str(REPO / 'desktop'), env=environment, capture_output=True, check=False)
        self.assertNotEqual(0, refused.returncode)
        self.assertIn('--test-output must be outside the generated main source root', refused.stderr.decode('utf-8', 'replace'))
        self.assertFalse(nested.exists())

    def test_output_overlapping_the_archive_is_rejected_and_the_archive_is_unchanged(self):
        before = archive_files()
        nested = REPO / source.ARCHIVE / 'child'
        try:
            for call in (lambda: producer.generate(REPO, nested),
                         lambda: producer.generate_image_viewer_tests(REPO, nested),
                         lambda: source.emit_main(REPO, nested),
                         lambda: source.validate_outputs(REPO, REPO)):
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    call()
            self.assertFalse(nested.exists())
            self.assertEqual(before, archive_files())
        finally:
            if nested.exists():
                shutil.rmtree(nested)

    def test_special_targets_fail_closed_and_stale_regular_files_are_replaced(self):
        relative = COMPONENTS + source.ZOOMABLE
        with tempfile.TemporaryDirectory(prefix='bp-iv-') as tmp:
            root = Path(tmp)
            target = root / relative
            target.parent.mkdir(parents=True)
            target.write_bytes(b'STALE')
            source.emit_main(REPO, root)
            self.assertEqual(self.archive[source.ZOOMABLE], target.read_bytes())
        for kind in ('directory', 'symlink'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix='bp-iv-') as tmp:
                root = Path(tmp)
                target = root / COMPONENTS / source.GESTURE
                target.parent.mkdir(parents=True)
                if kind == 'directory':
                    target.mkdir()
                else:
                    neighbor = target.parent / 'neighbor.bin'
                    neighbor.write_bytes(b'NEIGHBOR')
                    try:
                        target.symlink_to(neighbor)
                    except OSError:
                        self.skipTest('symlink creation is not permitted')
                with self.assertRaisesRegex(ValueError, 'not a regular file'):
                    source.emit_main(REPO, root)
                # Nothing was written before the refusal.
                self.assertFalse((root / COMPONENTS / source.ZOOMABLE).exists())
                self.assertFalse((root / COMPONENTS / source.MOTION).exists())
                self.assertFalse((root / source.PROOF_NAME).exists())
        with tempfile.TemporaryDirectory(prefix='bp-iv-') as tmp:
            root = Path(tmp)
            (root / COMPONENTS / source.GESTURE_TEST).mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'not a regular file'):
                source.emit_tests(REPO, root)
            self.assertFalse((root / COMPONENTS / source.MOTION_TEST).exists())

    # --- wiring --------------------------------------------------------------------------------------

    def test_gradle_excludes_the_two_direct_copies_and_wires_the_archive_into_the_card_task(self):
        build = self.build
        prepared = prepare_block(build)
        model = gate.parse_gradle(build)
        for name, path in EXCLUDED.items():
            line = 'exclude(canonicalOriginalIdentity("' + path + '"))'
            with self.subTest(name=name):
                self.assertEqual(1, build.count(line), line)
                self.assertEqual(1, prepared.count(line), line)
                self.assertEqual(1, model.sync_excludes.count(path))
        for leaf in (source.MOTION, 'ZoomableImageScalePolicy.kt', source.DIALOG):
            self.assertEqual(0, build.count('canonicalOriginalIdentity("' + source.COMPONENTS + leaf + '")'), leaf)
        block = task_block(build, 'extractUpstreamDynamicFullCard')
        for literal in ('inputs.file("tools/v046_image_viewer.py")', 'inputs.dir("upstream-slices/v046-image-viewer")',
                        'outputs.dir(layout.buildDirectory.dir("generated/dynamic-full-card-tests"))',
                        '"--test-output", layout.buildDirectory.dir("generated/dynamic-full-card-tests").get().asFile.absolutePath)'):
            self.assertEqual(1, build.count(literal), literal)
            self.assertEqual(1, block.count(literal), literal)
        self.assertEqual(1, block.count('outputs.dir(layout.buildDirectory.dir("generated/dynamic-full-card"))'))
        self.assertEqual([], [row for row in build.splitlines() if 'srcDir' in row and 'image-viewer' in row])
        # The main source root is unchanged and never contains the test root.
        self.assertEqual(1, build.count('kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-full-card"))'))
        test_line = 'kotlin.sourceSets.named("test") { kotlin.srcDir(layout.buildDirectory.dir("generated/dynamic-full-card-tests")) }'
        self.assertEqual(1, build.count(test_line))
        self.assertEqual({('build', 'generated/dynamic-full-card-tests')},
                         {pair for pair in model.source_dirs['test'] if pair[1] == 'generated/dynamic-full-card-tests'})
        self.assertNotIn(('build', 'generated/dynamic-full-card-tests'), model.source_dirs['main'])
        task = model.tasks['extractUpstreamDynamicFullCard']
        self.assertIsNone(task.skip)
        self.assertIn(('build', 'generated/dynamic-full-card-tests'), task.args)
        self.assertNotIn('v046', prepared)
        self.assertEqual(1, build.count('tasks.named("compileKotlin") { dependsOn(extractUpstreamDynamicFullCard) }'))

    def test_the_vendored_paths_stay_direct_in_the_source_registry(self):
        registry = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        rows = {row['path']: row for row in registry['sources']}
        for name, path in EXCLUDED.items():
            with self.subTest(name=name):
                self.assertEqual('direct', rows[path]['mode'])
        self.assertNotIn(source.UPSTREAM_PATHS[source.MOTION], rows)
        # The producer's own inventory keeps describing the canonical bodies, which this slice does not change.
        inventory = {row['path']: row for row in producer.inventory(REPO)}
        for path in EXCLUDED.values():
            self.assertEqual('direct', inventory[path]['mode'])
            self.assertEqual(rows[path]['sha256'], inventory[path]['sha256'])
        self.assertNotIn(source.ARCHIVE, json.dumps(registry))

    def test_inventory_rows_catalog_digest_and_counts_register_the_archive(self):
        self.assertIn(source.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(source.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(source.ARCHIVE + '/')]
        self.assertEqual([source.ZOOMABLE, source.GESTURE, source.MOTION, source.DIALOG, source.MOTION_TEST, source.GESTURE_TEST],
                         [Path(row['localPath']).name for row in rows])
        for row in rows:
            with self.subTest(row=row['localPath']):
                self.assertFalse(row['referenceOnly'])
                self.assertEqual(['v046-image-viewer'], row['features'])
                self.assertEqual('raw', row['hashNormalization'])
                self.assertEqual(source.COMMIT, row['upstreamCommit'])
                self.assertEqual(source.UPSTREAM_PATHS[Path(row['localPath']).name], row['path'])
                self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
                self.assertEqual(Path(row['localPath']).name == source.DIALOG, row['partialReuse'])
        archives = [row for row in self.catalog['archives'] if row['directory'] == source.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({source.ARCHIVE + '/' + name for name in source.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        self.assertEqual(rows, [row for row in sync.adapted_compatibility_inputs(REPO)
                                if row['localPath'].startswith(source.ARCHIVE + '/')])
        catalog_bytes = (TOOLS / 'compatibility-source-inputs.json').read_bytes()
        self.assertEqual(sha(catalog_bytes.replace(b'\r\n', b'\n')), sync.COMPATIBILITY_CATALOG_SHA256)

    def test_producer_hooks_run_in_order_and_import_the_generator_lazily(self):
        text = (TOOLS / 'extract-upstream-dynamic-card.py').read_text(encoding='utf-8')
        start = text.index('def generate(')
        validate = text.index('validate_image_viewer_outputs(repo,output)', start)
        host = text.index("host=module(repo,'full_card_host'", start)
        advance = text.index('body,image_viewer_renderer_proof=advance_preview_renderer(repo,body)', start)
        emit_renderer = text.index("emit(p,body,'DesktopOriginalImagePreviewRenderer.kt')", advance)
        preview_core = text.index('files+=emit_preview_core(output)', start)
        emit_main = text.index('emit_image_viewer_main(repo,output,image_viewer_renderer_proof)', preview_core)
        ret = text.index(' return files', emit_main)
        self.assertLess(start, validate)
        self.assertLess(validate, host)
        self.assertLess(advance, emit_renderer)
        self.assertLess(emit_renderer, preview_core)
        self.assertLess(preview_core, emit_main)
        self.assertLess(emit_main, ret)
        self.assertEqual(1, text.count('advance_preview_renderer(repo,body)'))
        self.assertEqual(1, text.count('emit_image_viewer_main(repo,output,image_viewer_renderer_proof)'))
        self.assertEqual(1, text.count('def generate_image_viewer_tests('))
        self.assertEqual(1, text.count('generate_image_viewer_tests(a.repo.resolve(),a.test_output.resolve())'))
        self.assertFalse([line for line in text.splitlines()
                          if line.startswith(('import v046_image_viewer', 'from v046_image_viewer'))])
        # Both layers are skipped in standalone mode.
        self.assertIn(' if not standalone:\n  # v046-image-viewer: the v0.4.6 pager scroll rule', text[advance - 400:advance])
        self.assertIn(' if not standalone:\n  # v046-image-viewer: ZoomableImage', text[emit_main - 800:emit_main])
        # The direct list is untouched: the canonical bodies still feed the standalone output and the inventory.
        self.assertIn("'ZoomableImage','ZoomableImageGesturePolicy','ZoomableImageScalePolicy'", text)
        self.assertNotIn('ZoomableImageMotionPolicy', text)

    def test_no_other_generator_or_producer_names_the_new_files(self):
        allowed = {'v046_image_viewer.py', 'extract-upstream-dynamic-card.py'}
        for path in sorted(TOOLS.glob('*.py')):
            if path.name in allowed or path.name == 'check_slice_symbol_closure.py':
                continue
            text = path.read_text(encoding='utf-8')
            with self.subTest(path=path.name):
                self.assertNotIn('ZoomableImageMotionPolicy', text)
                self.assertNotIn('resolveZoomableImagePanBounds', text)
                self.assertNotIn('v046-image-viewer', text)

    # --- Kotlin hygiene ------------------------------------------------------------------------------

    def balanced(self, text, label):
        stack = []
        for token, first, _end in sync.kotlin_tokens(text):
            if token in ('(', '[', '{'):
                stack.append(token)
            elif token in (')', ']', '}'):
                self.assertTrue(stack, (label, first))
                self.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), (label, first))
        self.assertEqual([], stack, label)

    def test_emitted_kotlin_keeps_balanced_delimiters_and_no_carriage_returns(self):
        for name in source.MAIN_FILES:
            text = self.main[COMPONENTS + name].decode('utf-8')
            self.assertNotIn('\r', text, name)
            self.balanced(text, name)
        for name in source.TEST_FILES:
            text = self.tests[COMPONENTS + name].decode('utf-8')
            self.assertNotIn('\r', text, name)
            self.balanced(text, name)
        self.balanced(lf(self.main[RENDERER]), 'renderer')
        self.balanced(self.after, 'advanced renderer body')
        for name, raw in self.archive.items():
            if name.endswith('.kt'):
                self.balanced(raw.decode('utf-8'), name)

    def test_generated_sources_have_no_android_imports_and_use_only_known_prefixes(self):
        for name in source.MAIN_FILES:
            text = self.archive[name].decode('utf-8')
            self.assertNotRegex(text, r'(?m)^import android\.', name)
            self.assertNotRegex(text, r'(?m)^import androidx\.(core|lifecycle|activity)', name)
            for imported in re.findall(r'(?m)^import ([\w.]+)', text):
                self.assertTrue(imported.startswith(('androidx.compose.', 'coil3.', 'kotlin.', 'kotlinx.coroutines.',
                                                     'com.android.purebilibili.core.ui.')), (name, imported))
        zoomable_imports = set(re.findall(r'(?m)^import ([\w.]+)', self.archive[source.ZOOMABLE].decode('utf-8')))
        self.assertIn('com.android.purebilibili.core.ui.LocalSystemReduceMotion', zoomable_imports)
        self.assertEqual(1, len([i for i in zoomable_imports if i.startswith('com.android.purebilibili.')]))


if __name__ == '__main__':
    unittest.main()
