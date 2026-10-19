"""v0.4.6 comment polish on the sole existing Windows comment owners (roadmap cmt-closure-merge-and-polish)."""
from contextlib import ExitStack
from pathlib import Path
from unittest import mock
import hashlib
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[3]
TOOLS = REPO / 'desktop/tools'
sys.path.insert(0, str(TOOLS))
import check_slice_symbol_closure as gate
import v046_comment_list as comment_list
import v046_comment_polish as polish
from v046_favorite_editor import restore
from v025_source_paths import canonical_source


def load(filename, label):
    spec = importlib.util.spec_from_file_location(label, TOOLS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load('sync-upstream.py', 'sync_upstream_comment_polish')
REPLY = load('extract-upstream-dynamic-reply.py', 'extract_reply_comment_polish')
COMMENT_UI = load('extract-upstream-video-comment-ui.py', 'extract_comment_ui_comment_polish')
PAGER = load('extract-upstream-video-fullscreen-pager.py', 'extract_pager_comment_polish')
BGM = load('extract-upstream-bgm-detail.py', 'extract_bgm_comment_polish')

COMPONENTS = 'com/android/purebilibili/feature/video/ui/components/'
SUBREPLY = COMPONENTS + 'DesktopOriginalSubReplyDetailComponents.kt'
HOST = COMPONENTS + 'VideoCommentSheetHost.kt'
FRAUD_HOST = 'com/android/purebilibili/' + polish.FRAUD_HOST_RELATIVE
ADAPTER = 'com/android/purebilibili/' + polish.ADAPTER_RELATIVE
DETAIL = 'com/android/purebilibili/feature/comment/CommentDetailScreen.kt'
APPEARANCE_PATH = polish.UPSTREAM_PATHS['VideoCommentAppearance.kt']
TASKS = ('extractUpstreamDynamicReply', 'extractVideoCommentUi', 'extractOriginalVideoFullscreenPager', 'extractBgmDetail')
JVM_TEST = REPO / 'desktop/src/test/kotlin/com/bilipai/desktop/ui/DesktopCommentFontRolesTest.kt'
PROOFS = {
    'reply': ('v046-comment-appearance-proof.json', 'v046-inline-subreply-entrance-proof.json'),
    'ui': ('v046-comment-fraud-adapter-proof.json', 'v046-comment-fraud-host-proof.json'),
    'pager': ('v046-comment-sheet-host-proof.json',),
    'bgm': ('v046-comment-detail-anchor-proof.json',),
}


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def tree(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(Path(root).rglob('*')) if p.is_file()}


def text(files, relative):
    # host.write() in the reply producer writes platform newlines; every other writer here emits LF.
    return files[relative].decode('utf8').replace('\r\n', '\n')


def body(files, relative):
    """A dynamic-reply output without its two-line GENERATED header."""
    return text(files, relative).split('\n', 2)[2]


def run_all(root, patched):
    outputs = {name: Path(root) / name for name in ('reply', 'ui', 'pager', 'bgm')}
    with ExitStack() as stack:
        if patched:
            stack.enter_context(mock.patch.object(polish, 'advance_inline_entrance', lambda repo, before, output: before))
            stack.enter_context(mock.patch.object(polish, 'emit_appearance', lambda repo, output: None))
            stack.enter_context(mock.patch.object(polish, 'advance_fraud_adapter', lambda repo, before, output: before))
            stack.enter_context(mock.patch.object(polish, 'fraud_host_body',
                                                  lambda repo, output: 'package com.android.purebilibili.feature.video.ui.components\n'))
            stack.enter_context(mock.patch.object(polish, 'advance_comment_sheet_host', lambda repo, before, output: before))
            stack.enter_context(mock.patch.object(polish, 'advance_comment_detail_screen', lambda repo, before, output: before))
        REPLY.generate(REPO, outputs['reply'])
        COMMENT_UI.generate(REPO, outputs['ui'])
        PAGER.generate(REPO, outputs['pager'])
        BGM.generate(REPO, outputs['bgm'])
    return {name: tree(path) for name, path in outputs.items()}


def balanced(case, source):
    stack = []
    for token, first, _end in sync.kotlin_tokens(source):
        if token in ('(', '[', '{'):
            stack.append(token)
        elif token in (')', ']', '}'):
            case.assertTrue(stack, first)
            case.assertEqual({')': '(', ']': '[', '}': '{'}[token], stack.pop(), first)
    case.assertEqual([], stack)


def task_block(build, name):
    start = build.index('val ' + name + ' by tasks.registering(Exec::class) {')
    return build[start:build.index('\n}\n', start)]


def declared(source, path):
    info = gate.parse_kotlin(source, path, 'generated', 'main')
    return info.package, [(item.visibility, item.kind, item.name) for item in info.decls]


class V046CommentPolishSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='bp-cpol-')
        root = Path(cls.temporary.name)
        cls.real = run_all(root / 'real', False)
        cls.base = run_all(root / 'base', True)
        cls.sources, cls.raws, cls.manifest = polish.fixed(REPO)
        cls.list_sources, _ = comment_list.fixed(REPO)
        cls.parser = comment_list.load(REPO, 'v046_comment_polish_test_syntax', 'sync-upstream.py')
        cls.build = (REPO / 'desktop/build.gradle.kts').read_text(encoding='utf-8')
        cls.catalog = json.loads((TOOLS / 'compatibility-source-inputs.json').read_text(encoding='utf-8'))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def proof(self, group, name):
        return json.loads(self.real[group][name])

    def changed(self, group):
        real, base = self.real[group], self.base[group]
        return {path for path in set(real) | set(base) if real.get(path) != base.get(path)}

    # ---------------------------------------------------------------- archive

    def test_archive_leaf_manifest_license_and_extra_leaf_reject_tampering(self):
        leaves = sorted(p.name for p in (REPO / polish.ARCHIVE).iterdir())
        self.assertEqual(sorted(polish.EXPECTED | {'LICENSE', 'manifest.json'}), leaves)
        for leaf in leaves:
            with self.subTest(leaf=leaf), tempfile.TemporaryDirectory(prefix='bp-cpol-') as tmp:
                repo = Path(tmp)
                shutil.copytree(REPO / polish.ARCHIVE, repo / polish.ARCHIVE)
                target = repo / polish.ARCHIVE / leaf
                target.write_bytes(target.read_bytes() + b'\n')
                with self.assertRaises(ValueError):
                    polish.fixed(repo)
        with tempfile.TemporaryDirectory(prefix='bp-cpol-') as tmp:
            repo = Path(tmp)
            shutil.copytree(REPO / polish.ARCHIVE, repo / polish.ARCHIVE)
            (repo / polish.ARCHIVE / 'Extra.kt').write_bytes(b'package extra\n')
            with self.assertRaises(ValueError):
                polish.fixed(repo)
            (repo / polish.ARCHIVE / 'Extra.kt').unlink()
            (repo / polish.ARCHIVE / 'LICENSE').unlink()
            with self.assertRaises(ValueError):
                polish.fixed(repo)

    def test_archive_leaves_are_the_upstream_git_blobs(self):
        import subprocess
        for row in self.manifest['files']:
            with self.subTest(name=row['filename']):
                blob = subprocess.run(
                    ['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', polish.COMMIT + ':' + row['path']],
                    cwd=str(REPO), capture_output=True, check=True).stdout
                self.assertEqual(blob, self.raws[row['filename']])
                self.assertNotIn(b'\r', blob)

    def test_output_overlapping_the_archive_is_rejected(self):
        nested = REPO / polish.ARCHIVE / 'child'
        before = {p.name: p.read_bytes() for p in (REPO / polish.ARCHIVE).iterdir()}
        try:
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                polish.validate_outputs(REPO, nested)
            with self.assertRaisesRegex(ValueError, 'overlaps'):
                polish.validate_outputs(REPO, REPO)
            for producer in (REPLY, COMMENT_UI, PAGER, BGM):
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    producer.generate(REPO, nested)
            self.assertFalse(nested.exists())
        finally:
            if nested.exists():
                shutil.rmtree(nested)
        self.assertEqual(before, {p.name: p.read_bytes() for p in (REPO / polish.ARCHIVE).iterdir()})

    # ---------------------------------------------------------------- v0.2.7 comment font (whole file)

    def test_comment_font_file_is_the_whole_v046_file_and_changes_only_the_body_step(self):
        emitted = self.real['reply'][polish.APPEARANCE_RELATIVE]
        self.assertEqual(self.raws['VideoCommentAppearance.kt'], emitted)
        vendored = canonical_source(REPO, APPEARANCE_PATH).read_text(encoding='utf-8').replace('\r\n', '\n')
        self.assertEqual(polish.PREVIOUS_APPEARANCE_SHA256, sha(vendored))
        new = emitted.decode('utf8')
        self.assertIn('VideoCommentTextRole.BODY -> typography.bodySmall.fontSize', vendored)
        self.assertIn('VideoCommentTextRole.SUB_REPLY,\n        VideoCommentTextRole.BODY -> typography.bodyMedium.fontSize', new)
        self.assertNotIn('VideoCommentTextRole.BODY -> typography.bodySmall.fontSize', new)
        self.assertIn('VideoCommentTextRole.AUTHOR -> typography.bodySmall.fontSize', new)
        proof = self.proof('reply', 'v046-comment-appearance-proof.json')
        self.assertEqual(polish.COMMIT, proof['upstreamCommit'])
        self.assertEqual(polish.MANIFEST_SHA256, proof['sourceManifestSHA256'])
        hunks = proof['vendoredToV046']['hunks']
        self.assertEqual(2, len(hunks))
        self.assertIn('bodySmall.fontSize', hunks[-1]['before'])
        self.assertIn('bodyMedium.fontSize', hunks[-1]['after'])
        self.assertNotIn('bodyMedium', ''.join(h['before'] for h in hunks))
        self.assertEqual(vendored, restore(new, proof['vendoredToV046']))
        self.assertFalse(proof['jvmTestsExecuted'])
        # Every other emitted file of the reply producer is untouched by the font file.
        self.assertEqual({polish.APPEARANCE_RELATIVE, polish.APPEARANCE_TEST_RELATIVE,
                          'v046-comment-appearance-proof.json', 'v046-inline-subreply-entrance-proof.json', SUBREPLY},
                         self.changed('reply'))

    def test_comment_font_policy_suite_is_the_original_with_one_class_rename(self):
        emitted = text(self.real['reply'], polish.APPEARANCE_TEST_RELATIVE)
        original = self.sources['VideoCommentAppearancePolicyTest.kt']
        self.assertEqual(2, original.count('@Test'))
        self.assertEqual(2, emitted.count('@Test'))
        self.assertEqual(1, emitted.count('class ' + polish.APPEARANCE_TEST_CLASS + ' {'))
        self.assertNotIn('class VideoCommentAppearancePolicyTest', emitted)
        self.assertEqual(original, emitted.replace(polish.APPEARANCE_TEST_CLASS, 'VideoCommentAppearancePolicyTest', 1))
        proof = self.proof('reply', 'v046-comment-appearance-proof.json')
        self.assertEqual(original, restore(emitted, proof['originalToWindowsTest']))
        self.assertTrue(polish.APPEARANCE_TEST_RELATIVE.startswith('test/'))
        main_roots = [path for path in self.real['reply'] if path.startswith('com/') and path.endswith('Test.kt')]
        self.assertEqual([], main_roots)
        balanced(self, emitted)

    def test_vendored_direct_copy_is_excluded_exactly_once_and_stays_direct(self):
        line = 'exclude(canonicalOriginalIdentity("' + APPEARANCE_PATH + '"))'
        start = self.build.index('val prepareUpstreamSources by tasks.registering(Sync::class) {')
        prepared = self.build[start:self.build.index('\nval extractUpstreamApi ', start)]
        self.assertEqual(1, self.build.count(line))
        self.assertEqual(1, prepared.count(line))
        self.assertEqual(1, gate.parse_gradle(self.build).sync_excludes.count(APPEARANCE_PATH))
        sources = json.loads((REPO / 'desktop/upstream-sources.json').read_text(encoding='utf-8'))
        listed = [row for row in sources['sources'] if row['path'] == APPEARANCE_PATH]
        self.assertEqual(1, len(listed))
        self.assertEqual('direct', listed[0]['mode'])

    def test_font_file_declarations_have_one_owner_in_the_comment_producers_and_desktop_sources(self):
        package, declarations = declared(self.sources['VideoCommentAppearance.kt'], 'VideoCommentAppearance.kt')
        self.assertEqual(polish.PACKAGE['VideoCommentAppearance.kt'], package)
        names = {name for _visibility, _kind, name in declarations}
        self.assertTrue({'VideoCommentTextRole', 'resolveVideoCommentFontSize', 'VideoCommentTypographyTokens',
                         'VideoCommentAppearance', 'resolveVideoCommentAppearance',
                         'rememberVideoCommentAppearance'} <= names)
        hits = []
        for group, files in self.real.items():
            for relative, raw in files.items():
                if relative.endswith('.kt') and not relative.startswith('test/') and b'VideoCommentTextRole' in raw:
                    pkg, rows = declared(raw.decode('utf8'), relative)
                    hits.extend((group, relative, name) for _v, _k, name in rows if name in names and pkg == package)
        for path in (REPO / 'desktop/src/main').rglob('*.kt'):
            raw = path.read_bytes()
            if b'VideoCommentTextRole' in raw:
                pkg, rows = declared(raw.decode('utf8'), path.as_posix())
                hits.extend(('desktop', path.as_posix(), name) for _v, _k, name in rows if name in names and pkg == package)
        self.assertEqual(sorted(names), sorted(name for _g, _r, name in hits))
        self.assertEqual({('reply', polish.APPEARANCE_RELATIVE)}, {(g, r) for g, r, _n in hits})

    # ---------------------------------------------------------------- shared fraud host and adapter

    def test_shared_fraud_host_is_the_whole_v046_file_with_the_windows_feedback_port(self):
        host = text(self.real['ui'], FRAUD_HOST)
        original = self.sources['CommentFraudFeedbackHost.kt']
        proof = self.proof('ui', 'v046-comment-fraud-host-proof.json')
        self.assertEqual(original, restore(host, proof['originalToWindows']))
        self.assertEqual(polish.COMMIT, proof['upstreamCommit'])
        for token in ('val platform = LocalDesktopCommentBindings.current',
                      'LaunchedEffect(commentViewModel, enabled, platform) {',
                      'if (platform.isOwned()) platform.showFeedback(lightMessage)',
                      'internal fun CommentFraudFeedbackHost(', 'enabled: Boolean = true,',
                      '\n        CommentFraudResultDialog(\n', 'commentViewModel.dismissFraudResult()',
                      'commentViewModel.startDissolve(rpid)', 'fraudEvent.collect'):
            self.assertEqual(1, host.count(token), token)
        for token in ('Toast', 'LocalContext', 'context', 'import android.'):
            self.assertNotIn(token, host, token)
        balanced(self, host)
        # Declared once across the four producers and desktop-owned sources.
        hits = []
        for group, files in self.real.items():
            for relative, raw in files.items():
                if relative.endswith('.kt') and b'fun CommentFraudFeedbackHost(' in raw:
                    hits.append((group, relative))
        for path in (REPO / 'desktop/src').rglob('*.kt'):
            if b'fun CommentFraudFeedbackHost(' in path.read_bytes():
                hits.append(('desktop', path.as_posix()))
        self.assertEqual([('ui', FRAUD_HOST)], hits)

    def test_fraud_adapter_is_the_v046_thin_caller_on_the_windows_composer_and_platform(self):
        adapter = text(self.real['ui'], ADAPTER)
        baseline = text(self.base['ui'], ADAPTER)
        self.assertEqual(polish.ADAPTER_PREDECESSOR_SHA256, sha(baseline))
        proof = self.proof('ui', 'v046-comment-fraud-adapter-proof.json')
        self.assertEqual(baseline, restore(adapter, proof['retainedToCurrent']))
        self.assertEqual(self.sources['VideoDetailCommentFraudOverlayAdapter.kt'], restore(adapter, proof['originalToWindows']))
        self.assertEqual(1, adapter.count('CommentFraudFeedbackHost(commentViewModel = commentViewModel)'))
        self.assertEqual(1, adapter.count('CompositionLocalProvider(LocalDesktopCommentBindings provides platform) {'))
        for token in ('fraudDialogStatus', 'CommentFraudResultDialog', 'resolveCommentFraudLightMessage',
                      'shouldShowCommentFraudResultDialog', 'Toast', 'import android.'):
            self.assertNotIn(token, adapter, token)
        self.assertIn('playbackViewModel.commentSentEvent.collect { reply ->', adapter)
        self.assertEqual(1, adapter.count('commentViewModel.onExternalCommentSent('))
        balanced(self, adapter)
        # The one Windows caller keeps its five positional arguments in the declared order.
        root = (REPO / 'desktop/src/main/kotlin/com/bilipai/desktop/ui/DesktopVideoCommentRoot.kt').read_text(encoding='utf-8')
        self.assertEqual(1, root.count('VideoDetailCommentFraudOverlayAdapter(platform, composer, viewModel, info.aid, fraudEnabled)'))
        self.assertIn('    platform: DesktopCommentPlatform,\n    playbackViewModel: DesktopOriginalVideoCommentComposer,\n'
                      '    commentViewModel: VideoCommentViewModel,\n    aid: Long?,\n    fraudDetectionEnabled: Boolean,\n', adapter)
        self.assertEqual({ADAPTER, FRAUD_HOST, 'video-comment-source-identities.json',
                          'v046-comment-fraud-adapter-proof.json', 'v046-comment-fraud-host-proof.json'},
                         self.changed('ui'))

    # ---------------------------------------------------------------- inline nested reply entrance

    def test_inline_nested_reply_entrance_is_the_whole_original_declaration(self):
        final = body(self.real['reply'], SUBREPLY)
        before = body(self.base['reply'], SUBREPLY)
        proof = self.proof('reply', 'v046-inline-subreply-entrance-proof.json')
        self.assertEqual(before, restore(final, proof['retainedToCurrent']))
        name = 'VideoInlineSubReplyDetailContent'
        old = comment_list.declaration(self.parser, before, name)
        new = comment_list.declaration(self.parser, final, name)
        original = comment_list.declaration(self.parser, self.list_sources['SubReplyDetailComponents.kt'], name)
        self.assertEqual(polish.INLINE_DECLARATION_SHA256, sha(old))
        self.assertEqual(original, new)
        self.assertEqual(old, new.replace(polish.ENTRANCE_BLOCK, '', 1).replace(
            polish.ENTRANCE_MODIFIER, '        modifier = modifier,\n', 1))
        for token in ('com.android.purebilibili.core.ui.rememberAppBottomSheetMotion()', 'motion.dragSettleSpec',
                      'Animatable(if (motionEnabled) 1f else 0f)', 'modifier = modifier.graphicsLayer {',
                      'translationY = size.height * entranceProgress.value.coerceIn(0f, 1f)'):
            self.assertEqual(1, new.count(token), token)
        for imported in polish.INLINE_IMPORTS:
            self.assertEqual(1, final.count('import ' + imported + '\n'), imported)
        # Everything but that declaration and its two imports is byte-identical.
        self.assertEqual(before.replace(old, new, 1).count('\n'), final.count('\n') - len(polish.INLINE_IMPORTS))
        stripped = final.replace(new, old, 1)
        for imported in polish.INLINE_IMPORTS:
            stripped = stripped.replace('import ' + imported + '\n', '', 1)
        for imported in polish.INLINE_IMPORTS:
            self.assertEqual(before.count('import ' + imported + '\n'), 0)
        self.assertEqual(before, stripped)
        balanced(self, final)
        self.assertFalse(proof['platformAdaptationNeeded'])
        self.assertTrue(proof['danmakuComposerExitNotClaimed'])

    def test_the_entrance_names_only_members_that_exist_in_the_windows_tree(self):
        sheet = (REPO / 'desktop/upstream-slices/v046-sheet-motion/AppSheetComponents.kt').read_text(encoding='utf-8')
        self.assertEqual(1, sheet.count('fun rememberAppBottomSheetMotion(): AppBottomSheetMotion {'))
        self.assertEqual(1, sheet.count('    val dragSettleSpec: FiniteAnimationSpec<Float>'))
        self.assertEqual(1, sheet.count('    val reducedMotion: Boolean = false,'))
        theme = (REPO / 'design-system/src/main/java/com/android/purebilibili/core/ui/AppThemeConfig.kt').read_text(encoding='utf-8')
        self.assertEqual(1, theme.count('val uiEntranceAnimationEnabled: Boolean = true,'))
        self.assertEqual(1, theme.count('val LocalAppThemeConfig = staticCompositionLocalOf'))

    # ---------------------------------------------------------------- fullscreen comment host

    def test_fullscreen_host_is_the_whole_v046_declaration_with_only_platform_adaptations(self):
        final = text(self.real['pager'], HOST)
        before = text(self.base['pager'], HOST)
        proof = self.proof('pager', 'v046-comment-sheet-host-proof.json')
        self.assertEqual(before, restore(final, proof['retainedToCurrent']))
        name = 'VideoCommentSheetHost'
        old = comment_list.declaration(self.parser, before, name)
        new = comment_list.declaration(self.parser, final, name)
        original = comment_list.declaration(self.parser, self.list_sources['VideoCommentSheetHost.kt'], name)
        self.assertEqual(polish.HOST_DECLARATION_SHA256, sha(old))
        self.assertEqual(original, restore(new, proof['originalToWindows']))
        # The Windows delta from the complete original is exactly the recorded platform adaptations.
        expected = original.replace('fun VideoCommentSheetHost(\n', 'internal fun VideoCommentSheetHost(\n', 1)
        expected = expected.replace('    val context = LocalContext.current\n',
                                    '    val platform = com.bilipai.desktop.ui.LocalDesktopCommentBindings.current\n'
                                    '    val context = platform.context\n', 1)
        expected = expected.replace(polish.HOST_SORT_BEFORE, polish.HOST_SORT_AFTER, 1)
        expected = expected.replace(polish.HOST_DECORATIONS_BEFORE, polish.HOST_DECORATIONS_AFTER, 1)
        expected = expected.replace('                            val coveredBlurEffectCache = remember { BlurRenderEffectCache() }\n', '', 1)
        expected = expected.replace('Build.VERSION.SDK_INT >= Build.VERSION_CODES.S',
                                    'com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()', 1)
        expected = expected.replace(polish.HOST_BLUR_BEFORE, polish.HOST_BLUR_AFTER, 1)
        self.assertEqual(expected, new)
        for token in ('val sheetVisibility = remember { MutableTransitionState(false) }',
                      'sheetVisibility.targetState = hostVisible', 'visibleState = sheetVisibility,',
                      'animationSpec = if (isDraggingSheet) tween(0) else motionSpec.dragSettleSpec,',
                      'isDismissDragSettling = false\n', 'isDragDismissExitPending = false\n',
                      'enter = motionSpec.contentEnter,', 'conversationAnchorId = subReplyState.conversationAnchor?.rpid ?: 0L,',
                      'CommentFraudFeedbackHost(commentViewModel = commentViewModel, enabled = handleFraudEvents)'):
            self.assertGreaterEqual(new.count(token), 1, token)
            self.assertEqual(original.count(token), new.count(token), token)
        self.assertEqual(1, new.count('visibleState = sheetVisibility,'))
        self.assertEqual(1, new.count('CommentFraudFeedbackHost(commentViewModel = commentViewModel, enabled = handleFraudEvents)'))
        for token in ('LocalContext', 'SettingsManager.', 'BlurRenderEffectCache', 'Build.VERSION', 'Toast',
                      'fraudDialogStatus', 'CommentFraudResultDialog(',
                      'slideInVertically(animationSpec = tween(260))'):
            self.assertNotIn(token, new, token)
        # Only the scrim keeps the plain visibility flag, as in the original; the sheet uses the transition state.
        self.assertEqual(1, new.count('visible = hostVisible,'))
        self.assertEqual(1, original.count('visible = hostVisible,'))
        # Same parameters, so the existing callers stay valid.
        self.assertEqual(old.split(') {\n', 1)[0], new.split(') {\n', 1)[0])
        self.assertEqual(1, final.count('import androidx.compose.animation.core.MutableTransitionState\n'))
        for dropped in polish.HOST_FRAUD_IMPORTS:
            self.assertNotIn('import ' + dropped + '\n', final)
            self.assertIn('import ' + dropped + '\n', before)
        # Every declaration other than the host is byte-identical.
        self.assertEqual(before.replace(old, '', 1).count('fun '), final.replace(new, '', 1).count('fun '))
        balanced(self, final)
        self.assertEqual({HOST, 'v046-comment-sheet-host-proof.json'}, {p for p in self.changed('pager')
                                                                          if p.endswith('.kt') or p.startswith('v046')})

    def test_host_body_change_is_limited_to_the_documented_hunks(self):
        before = text(self.base['pager'], HOST)
        final = text(self.real['pager'], HOST)
        proof = self.proof('pager', 'v046-comment-sheet-host-proof.json')
        hunks = proof['retainedToCurrent']['hunks']
        self.assertEqual(before, restore(final, proof['retainedToCurrent']))
        changed_text = '\n'.join(h['before'] + h['after'] for h in hunks)
        for survivor in ('VideoCommentMainList(', 'resolveVideoCommentSheetHostContent(', 'rememberLazyListState()'):
            self.assertNotIn(survivor, changed_text)
        # The two original helpers the thread panel uses still have exactly one definer in the retained file or its owner.
        self.assertGreaterEqual(len(hunks), 8)

    # ---------------------------------------------------------------- BGM navigation destination

    def test_comment_detail_screen_forwards_the_conversation_anchor_once(self):
        final = text(self.real['bgm'], DETAIL)
        before = text(self.base['bgm'], DETAIL)
        self.assertEqual(polish.DETAIL_SCREEN_PREDECESSOR_SHA256, sha(before))
        proof = self.proof('bgm', 'v046-comment-detail-anchor-proof.json')
        self.assertEqual(before, restore(final, proof['retainedToCurrent']))
        self.assertEqual(1, len(proof['retainedToCurrent']['hunks']))
        self.assertEqual(['', polish.DETAIL_LINE], [proof['retainedToCurrent']['hunks'][0]['before'],
                                                     proof['retainedToCurrent']['hunks'][0]['after']])
        self.assertEqual(1, final.count(polish.DETAIL_ANCHOR + polish.DETAIL_LINE))
        self.assertIn(polish.DETAIL_ANCHOR + polish.DETAIL_LINE, self.sources['CommentDetailScreen.kt'])
        pinned = canonical_source(REPO, polish.UPSTREAM_PATHS['CommentDetailScreen.kt']).read_text(encoding='utf-8')
        self.assertEqual(self.sources['CommentDetailScreen.kt'].replace(polish.DETAIL_LINE, '', 1), pinned.replace('\r\n', '\n'))
        self.assertEqual({DETAIL, 'v046-comment-detail-anchor-proof.json'}, self.changed('bgm'))
        balanced(self, final)

    # ---------------------------------------------------------------- the entry parts the earlier slices already carry

    def test_two_dp_gap_and_stable_skeleton_slots_are_already_in_the_generated_comment_owners(self):
        thread = body(self.real['reply'], SUBREPLY)
        reply = text(self.real['reply'], COMPONENTS + 'DesktopOriginalReplyComponents.kt')
        for token in ('commentBottomPaddingDp = 2,', 'contentToActionSpacingDp = 2,',
                      'Spacer(modifier = Modifier.height(layoutPolicy.contentToActionSpacingDp.dp))',
                      'bottom = layoutPolicy.commentBottomPaddingDp.dp,',
                      'videoCommentSkeletonItems(', 'keyPrefix = "subreply_skeleton_",',
                      'modifier = videoCommentItemModifier().padding(bottom = 1.dp)'):
            self.assertEqual(1, thread.count(token), token)
        self.assertEqual(1, reply.count('Spacer(modifier = Modifier.height(2.dp))'))
        self.assertEqual(1, reply.count('bottom = 2.dp,'))
        archive_skeleton = (REPO / 'desktop/upstream-slices/v036-video-comment-viewport/VideoCommentSkeletonSlots.kt').read_bytes()
        import subprocess
        blob = subprocess.run(['git', '-c', 'core.autocrlf=false', 'cat-file', 'blob', polish.COMMIT + ':' +
                               'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoCommentSkeletonSlots.kt'],
                              cwd=str(REPO), capture_output=True, check=True).stdout
        self.assertEqual(blob, archive_skeleton)

    def test_avatar_push_cascade_and_bangumi_panel_stay_where_the_comment_list_slice_put_them(self):
        shared = text(self.real['reply'], COMPONENTS + 'CommentCascadingContent.kt')
        self.assertIn('internal fun <T> CommentCascadingContent(', shared)
        thread = body(self.real['reply'], SUBREPLY)
        self.assertEqual(2, thread.count('CommentCascadingContent('))
        self.assertIn('avatarListState = listState.takeIf { avatarPushEnabled },', thread)
        notice = (REPO / 'desktop/upstream-slices/v046-comment-list/NagramChatAvatarPlacement.kt').read_text(encoding='utf-8')
        self.assertIn('SPDX-License-Identifier: GPL-2.0-or-later', notice)
        self.assertEqual(self.list_sources['NagramChatAvatarPlacement.kt'], text(self.real['reply'], COMPONENTS + 'NagramChatAvatarPlacement.kt'))

    def test_nagram_avatar_placement_attribution_ships_with_the_carried_notices(self):
        base = REPO / 'desktop/src/main/resources/licenses/upstream-carried-assets'
        provenance = json.loads((base / 'provenance.json').read_bytes())
        rows = [row for row in provenance['covers'] if row['name'] == 'NagramChatAvatarPlacement.kt']
        self.assertEqual(1, len(rows))
        self.assertEqual(['piliplus-LICENSE.txt'], rows[0]['notices'])
        header = (REPO / rows[0]['archivePath']).read_text(encoding='utf-8')
        self.assertIn('Copyright Nikolai Kudashov, 2013-2018.', header)
        self.assertIn('SPDX-License-Identifier: GPL-2.0-or-later', header)
        self.assertIn('Source revision: fb56ea9801e5de68c60f0152b444bbe2a0fda520', header)
        notice = (base / 'NOTICE.txt').read_bytes()
        self.assertNotIn(b'\r', notice)
        notice = notice.decode('utf-8')
        for token in ('NagramChatAvatarPlacement.kt', 'Copyright Nikolai Kudashov, 2013-2018.',
                      'SPDX-License-Identifier: GPL-2.0-or-later', 'fb56ea9801e5de68c60f0152b444bbe2a0fda520',
                      'Telegram / Nagram Chat Avatar Placement', 'It is not legal advice.'):
            self.assertIn(token, notice, token)

    # ---------------------------------------------------------------- fail closed

    def test_foreign_predecessors_and_missing_anchors_fail_before_any_output(self):
        with tempfile.TemporaryDirectory(prefix='bp-cpol-') as tmp:
            out = Path(tmp)
            adapter = text(self.base['ui'], ADAPTER)
            self.assertRaises(ValueError, polish.advance_fraud_adapter, REPO, adapter + '\n', out)
            self.assertRaises(ValueError, polish.advance_fraud_adapter, REPO, text(self.real['ui'], ADAPTER), out)
            detail = text(self.base['bgm'], DETAIL)
            self.assertRaises(ValueError, polish.advance_comment_detail_screen, REPO, detail + '\n', out)
            self.assertRaises(ValueError, polish.advance_comment_detail_screen, REPO, text(self.real['bgm'], DETAIL), out)
            host = text(self.base['pager'], HOST)
            self.assertRaises((ValueError, AssertionError), polish.advance_comment_sheet_host, REPO, 'package x\n', out)
            self.assertRaises(ValueError, polish.advance_comment_sheet_host, REPO,
                              host.replace('val hostVisible = ', 'val hostVisible2 = ', 1), out)
            self.assertRaises(ValueError, polish.advance_comment_sheet_host, REPO, text(self.real['pager'], HOST), out)
            before = body(self.base['reply'], SUBREPLY)
            self.assertRaises((ValueError, AssertionError), polish.advance_inline_entrance, REPO, 'package x\n', out)
            self.assertRaises(ValueError, polish.advance_inline_entrance, REPO,
                              before.replace('if (!state.visible || rootReply == null) return',
                                             'if (!state.visible || rootReply == null) return // x', 1), out)
            self.assertRaises(ValueError, polish.advance_inline_entrance, REPO, body(self.real['reply'], SUBREPLY), out)
            self.assertEqual([], list(out.iterdir()))

    def test_unused_import_removal_refuses_a_name_that_is_still_used(self):
        source = 'package p\nimport a.b.Used\nimport a.b.Gone\nfun f() { Used() }\n'
        self.assertEqual('package p\nimport a.b.Used\nfun f() { Used() }\n', polish.drop_import(REPO, source, 'a.b.Gone'))
        with self.assertRaises(ValueError):
            polish.drop_import(REPO, source, 'a.b.Used')
        with self.assertRaises(ValueError):
            polish.drop_import(REPO, source, 'a.b.Absent')

    def test_producers_are_deterministic_for_the_changed_outputs(self):
        with tempfile.TemporaryDirectory(prefix='bp-cpol-') as tmp:
            again = run_all(Path(tmp), False)
        for group in self.real:
            self.assertEqual(self.real[group], again[group], group)

    # ---------------------------------------------------------------- wiring

    def test_gradle_inputs_inventory_and_producer_hooks(self):
        marker = ('tasks.matching { it.name in setOf(\n    "extractUpstreamDynamicReply", "extractVideoCommentUi", '
                  '"extractOriginalVideoFullscreenPager", "extractBgmDetail",\n) }.configureEach {\n'
                  '    inputs.file("tools/v046_comment_polish.py")\n    inputs.dir("upstream-slices/v046-comment-polish")\n}\n')
        self.assertEqual(1, self.build.count(marker))
        self.assertEqual(1, self.build.count('inputs.file("tools/v046_comment_polish.py")'))
        self.assertEqual(1, self.build.count('inputs.dir("upstream-slices/v046-comment-polish")'))
        for task in TASKS:
            self.assertIn('val ' + task + ' by tasks.registering(Exec::class) {', self.build)
            self.assertIn('"' + task + '"', marker)
        self.assertNotIn('srcDir', '\n'.join(line for line in self.build.splitlines() if 'comment-polish' in line))
        self.assertIn(polish.ARCHIVE, self.catalog['buildInputRoots'])
        self.assertEqual(1, self.catalog['buildInputRoots'].count(polish.ARCHIVE))
        rows = [row for row in self.catalog['inputs'] if row['localPath'].startswith(polish.ARCHIVE + '/')]
        self.assertEqual(len(polish.EXPECTED), len(rows))
        self.assertEqual({polish.UPSTREAM_PATHS[name] for name in polish.EXPECTED}, {row['path'] for row in rows})
        for row in rows:
            self.assertFalse(row['referenceOnly'])
            self.assertEqual(['v046-comment-polish'], row['features'])
            self.assertEqual('raw', row['hashNormalization'])
            self.assertEqual(polish.COMMIT, row['upstreamCommit'])
            self.assertTrue(row['usedScope'].isascii() and row['usedScope'])
        self.assertEqual({'CommentDetailScreen.kt'}, {row['localPath'].rsplit('/', 1)[1] for row in rows if row['partialReuse']})
        archives = [row for row in self.catalog['archives'] if row['directory'] == polish.ARCHIVE]
        self.assertEqual(1, len(archives))
        self.assertEqual({polish.ARCHIVE + '/' + name for name in polish.EXPECTED | {'LICENSE', 'manifest.json'}},
                         {leaf['path'] for leaf in archives[0]['files']})
        self.assertEqual(rows, [row for row in sync.adapted_compatibility_inputs(REPO)
                                if row['localPath'].startswith(polish.ARCHIVE + '/')])

        def producer_source(name):
            return (TOOLS / name).read_text(encoding='utf-8')

        reply = producer_source('extract-upstream-dynamic-reply.py')
        start = reply.index('def generate(')
        self.assertLess(reply.index('validate_comment_polish_outputs(repo, output)', start), reply.index('host = load(', start))
        self.assertLess(reply.index('body = advance_comment_thread(repo, body, output)'),
                        reply.index('body = advance_inline_entrance(repo, body, output)'))
        self.assertLess(reply.index('body = advance_inline_entrance(repo, body, output)'),
                        reply.index('emitted.append(host.write(output, path, read(repo, path), body, filename))'))
        self.assertLess(reply.index('emit(path, \'package com.bilipai.desktop.ui\\nimport androidx.compose.animation.core.*'),
                        reply.index('emit_appearance(repo, output)'))
        self.assertLess(reply.index('emit_appearance(repo, output)'), reply.index('    return emitted\n'))
        ui = producer_source('extract-upstream-video-comment-ui.py')
        self.assertLess(ui.index('body=advance_fraud_adapter(repo,body,output)'),
                        ui.index("emit('feature/video/screen/VideoDetailCommentFraudOverlayAdapter.kt',body"))
        self.assertLess(ui.index("emit('feature/video/screen/VideoDetailCommentFraudOverlayAdapter.kt',body"),
                        ui.index("emit('feature/video/ui/components/CommentFraudFeedbackHost.kt',fraud_host_body(repo,output)"))
        pager = producer_source('extract-upstream-video-fullscreen-pager.py')
        self.assertLess(pager.index('text=advance_main_comment_list(repo,text,output)'),
                        pager.index('text=advance_comment_sheet_host(repo,text,output)'))
        self.assertLess(pager.index('text=advance_comment_sheet_host(repo,text,output)'),
                        pager.index('apply_fullscreen_tap_gestures(repo,spec[\'output\'],text,output)'))
        bgm = producer_source('extract-upstream-bgm-detail.py')
        self.assertLess(bgm.index('body=comment_detail_root_entry_delta(relative,body)'),
                        bgm.index('body=advance_comment_detail_screen(repo,body,output)'))
        for name in ('extract-upstream-dynamic-reply.py', 'extract-upstream-video-comment-ui.py',
                     'extract-upstream-video-fullscreen-pager.py', 'extract-upstream-bgm-detail.py'):
            source = producer_source(name)
            self.assertEqual(1, source.count('validate_comment_polish_outputs('), name)
            self.assertFalse(any(line.startswith(('import v046_comment_polish', 'from v046_comment_polish'))
                                 for line in source.splitlines()), name)

    def test_no_other_generator_declares_the_polished_names(self):
        for path in sorted(TOOLS.glob('*.py')):
            if path.name in {'v046_comment_polish.py', 'check_slice_symbol_closure.py'}:
                continue
            source = path.read_text(encoding='utf-8')
            self.assertNotIn('CommentFraudFeedbackHost(commentViewModel = commentViewModel, enabled', source, path.name)
            self.assertNotIn('V046VideoCommentAppearanceOriginalPolicyTest', source, path.name)

    # ---------------------------------------------------------------- JVM sources

    def test_jvm_sources_are_delimiter_balanced_and_counted(self):
        own = JVM_TEST.read_text(encoding='utf-8')
        self.assertNotIn('\r', own)
        self.assertEqual(3, own.count('@Test'))
        self.assertEqual(1, own.count('class DesktopCommentFontRolesTest {'))
        self.assertIn('package com.bilipai.desktop.ui\n', own)
        balanced(self, own)
        for group, relatives in (('reply', (polish.APPEARANCE_RELATIVE, polish.APPEARANCE_TEST_RELATIVE)),
                                 ('ui', (FRAUD_HOST, ADAPTER)), ('pager', (HOST,)), ('bgm', (DETAIL,))):
            for relative in relatives:
                self.assertNotIn(b'\r', self.real[group][relative], relative)
                balanced(self, text(self.real[group], relative))
        # host.write() in the reply producer keeps its pre-existing platform newlines for the nested reply file.
        balanced(self, text(self.real['reply'], SUBREPLY))

    def test_symbol_gate_records_the_fraud_host_as_closed_not_as_a_gap(self):
        gaps = json.loads((TOOLS / 'symbol-closure-expected-gaps.json').read_text(encoding='utf-8'))
        symbol = 'com.android.purebilibili.feature.video.ui.components.CommentFraudFeedbackHost'
        listed = [gap['symbol'] for group in gaps['groups'] for gap in group['gaps']]
        self.assertNotIn(symbol, listed)
        self.assertEqual([], [group for group in gaps['groups'] if group['closedBy'] == 'cmt-closure-merge-and-polish'])
        retired = [row for row in gaps['retiredSeeds'] if row['symbol'] == 'CommentFraudFeedbackHost']
        self.assertEqual(1, len(retired))
        self.assertEqual('declaration', retired[0]['kind'])
        self.assertEqual('com.android.purebilibili.feature.video.ui.components', retired[0]['package'])
        self.assertEqual('cmt-closure-merge-and-polish', retired[0]['closedBy'])


if __name__ == '__main__':
    unittest.main()
