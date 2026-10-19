"""v0.4.6 comment polish on the sole existing Windows comment owners.

Roadmap slice cmt-closure-merge-and-polish. The v046-comment-list, v046-comment-thread and v046-comment-chrome
slices already carry the reply list, nested reply page, avatar push, cascade and Bangumi panel. This module adds
what they left on the pinned v0.2.5 text:

* VideoCommentAppearance.kt whole (v0.2.7 body and nested reply font bodySmall -> bodyMedium), emitted verbatim
  with its two-case policy suite; the vendored direct copy is excluded in prepareUpstreamSources.
* CommentFraudFeedbackHost.kt, the shared fraud feedback host that 2bc6e885 extracted, with the Windows feedback
  port; the video detail fraud adapter becomes the v0.4.6 thin caller and the fullscreen comment host calls it.
* The inline nested reply bottom-up entrance (8eb5996b) now that the real AppBottomSheetMotion exists.
* The complete v0.4.6 fullscreen comment host declaration (first-mount slide, drag settle, thread motion,
  conversation anchor identity) on the existing Windows owner, and the one anchor line of CommentDetailScreen.

No Kotlin was compiled or run for any output of this module.
"""
from pathlib import Path
import hashlib
import json

from v044_favorite_detail import inverse_proof
import v046_comment_list as comment_list

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-comment-polish'
MANIFEST_SHA256 = '5c6d05ec9cbd7cb0c9c7be9e080a8bb897bb37928d05d812afeec8f8fc37dda1'
MAIN_FILES = (
    'VideoCommentAppearance.kt',
    'CommentFraudFeedbackHost.kt',
    'VideoDetailCommentFraudOverlayAdapter.kt',
    'CommentDetailScreen.kt',
)
TEST_FILES = ('VideoCommentAppearancePolicyTest.kt',)
EXPECTED = set(MAIN_FILES) | set(TEST_FILES)
PACKAGE = {
    'VideoCommentAppearance.kt': 'com.android.purebilibili.feature.video.ui.components',
    'CommentFraudFeedbackHost.kt': 'com.android.purebilibili.feature.video.ui.components',
    'VideoDetailCommentFraudOverlayAdapter.kt': 'com.android.purebilibili.feature.video.screen',
    'CommentDetailScreen.kt': 'com.android.purebilibili.feature.comment',
    'VideoCommentAppearancePolicyTest.kt': 'com.android.purebilibili.feature.video.ui.components',
}
UPSTREAM_PATHS = {
    'VideoCommentAppearance.kt': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoCommentAppearance.kt',
    'CommentFraudFeedbackHost.kt': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentFraudFeedbackHost.kt',
    'VideoDetailCommentFraudOverlayAdapter.kt': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailCommentFraudOverlayAdapter.kt',
    'CommentDetailScreen.kt': 'app/src/main/java/com/android/purebilibili/feature/comment/CommentDetailScreen.kt',
    'VideoCommentAppearancePolicyTest.kt': 'app/src/test/java/com/android/purebilibili/feature/video/ui/components/VideoCommentAppearancePolicyTest.kt',
}
COMPONENTS = 'com/android/purebilibili/feature/video/ui/components/'
APPEARANCE_RELATIVE = COMPONENTS + 'VideoCommentAppearance.kt'
APPEARANCE_TEST_RELATIVE = 'test/' + COMPONENTS + 'V046VideoCommentAppearanceOriginalPolicyTest.kt'
APPEARANCE_TEST_CLASS = 'V046VideoCommentAppearanceOriginalPolicyTest'
FRAUD_HOST_RELATIVE = 'feature/video/ui/components/CommentFraudFeedbackHost.kt'
ADAPTER_RELATIVE = 'feature/video/screen/VideoDetailCommentFraudOverlayAdapter.kt'

# The vendored v0.2.5 direct copy (LF SHA-256 as listed in desktop/upstream-sources.json) that the whole file replaces.
PREVIOUS_APPEARANCE_PATH = UPSTREAM_PATHS['VideoCommentAppearance.kt']
PREVIOUS_APPEARANCE_SHA256 = '4b20a36d9125d2752fe2d856f9684fead69b22441376f12cd3b1879415a5d500'
# Predecessor pins: the exact body or declaration each advance receives from its producer chain.
ADAPTER_PREDECESSOR_SHA256 = '46a7b5dc49ee234c541fbe5914ff486f85c1bd9b277888610f464fd9c49aca99'
INLINE_DECLARATION_SHA256 = '948c7625c49dac2a36ccc1da02f1995463a46788332b3765a78bc05e347be853'
HOST_DECLARATION_SHA256 = '9d47ea945fdac5a784dcbe6c9ff63b482eb21251b0193a628457315e0833c827'
DETAIL_SCREEN_PREDECESSOR_SHA256 = 'd06ab736d12a722ac1563d3bd49bfd6cf802b805eb8a81cf8639393f856f8846'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def need(condition, message):
    if not condition:
        raise ValueError(message)


def once(text, before, after):
    need(text.count(before) == 1, 'Comment polish anchor changed: ' + before[:90].replace('\n', ' '))
    return text.replace(before, after, 1)


def fixed(repo):
    root = Path(repo) / ARCHIVE
    need(root.is_dir() and not root.is_symlink(), 'Missing v046 comment polish inputs')
    need({p.name for p in root.iterdir()} == EXPECTED | {'manifest.json', 'LICENSE'}, 'Comment polish input leaves changed')
    manifest_raw = (root / 'manifest.json').read_bytes()
    need(b'\r' not in manifest_raw and sha(manifest_raw) == MANIFEST_SHA256, 'Comment polish manifest changed')
    manifest = json.loads(manifest_raw)
    need((manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) ==
         ('jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw'), 'Comment polish source identity changed')
    need(len(manifest['files']) == len(EXPECTED) and {row['filename'] for row in manifest['files']} == EXPECTED,
         'Comment polish source set changed')
    sources, raws = {}, {}
    for row in manifest['files']:
        file = root / row['filename']
        need(file.is_file() and not file.is_symlink() and Path(row['path']).name == file.name,
             'Linked or ambiguous comment polish source')
        need(row['path'] == UPSTREAM_PATHS[file.name], 'Comment polish upstream path changed: ' + file.name)
        leaf = file.read_bytes()
        need(b'\r' not in leaf and len(leaf) == row['bytes'] and sha(leaf) == row['sha256'],
             'Comment polish bytes changed: ' + file.name)
        need(hashlib.sha1(b'blob ' + str(len(leaf)).encode() + b'\0' + leaf).hexdigest() == row['gitBlob'],
             'Comment polish Git blob changed: ' + file.name)
        text = leaf.decode('utf8')
        need(text.endswith('\n') and ('package ' + PACKAGE[file.name] + '\n') in text[:400],
             'Comment polish text changed: ' + file.name)
        sources[file.name] = text
        raws[file.name] = leaf
    license_file = root / 'LICENSE'
    need(not license_file.is_symlink() and sha(license_file.read_bytes()) == manifest['license']['sha256'],
         'Comment polish license changed')
    return sources, raws, manifest


def validate_outputs(repo, *outputs):
    fixed(repo)
    root = (Path(repo) / ARCHIVE).resolve()
    for output in outputs:
        if output is None:
            continue
        target = Path(output).resolve()
        need(not (target.is_relative_to(root) or root.is_relative_to(target)),
             'Output overlaps v046 comment polish inputs')


def save(repo, output, name, value):
    comment_list.save(repo, output, name, value)


def ensure_import(text, name):
    return comment_list.ensure_import(text, name)


def drop_import(repo, text, name):
    """Remove an import whose simple name no declaration of the file uses any more."""
    simple = name.rsplit('.', 1)[-1]
    line = 'import ' + name + '\n'
    need(text.count(line) == 1, 'Import to drop is not present exactly once: ' + name)
    rest = text.replace(line, '', 1)
    body = '\n'.join(item for item in rest.split('\n') if not item.startswith('import '))
    tokens = comment_list.load(repo, 'v046_comment_polish_drop', 'sync-upstream.py')
    need(simple not in [word for word, _, _ in tokens.kotlin_tokens(body)], 'Import is still used: ' + name)
    return rest


# ---------------------------------------------------------------- VideoCommentAppearance (whole file)

def emit_appearance(repo, output):
    """Whole v0.4.6 VideoCommentAppearance.kt and its original policy suite."""
    validate_outputs(repo, output)
    sources, raws, _ = fixed(repo)
    from v025_source_paths import canonical_source
    previous = canonical_source(repo, PREVIOUS_APPEARANCE_PATH).read_text(encoding='utf-8').replace('\r\n', '\n')
    need(sha(previous) == PREVIOUS_APPEARANCE_SHA256, 'Vendored v0.2.5 VideoCommentAppearance changed')
    original = sources['VideoCommentAppearance.kt']
    need(original.count('VideoCommentTextRole.BODY -> typography.bodyMedium.fontSize') == 1 and
         original.count('VideoCommentTextRole.SUB_REPLY,') == 1, 'v0.4.6 comment body font step changed')
    need(previous.count('VideoCommentTextRole.BODY -> typography.bodySmall.fontSize') == 1, 'Pinned comment body font changed')
    upstream_test = sources['VideoCommentAppearancePolicyTest.kt']
    need(upstream_test.count('@Test') == 2 and upstream_test.count('class VideoCommentAppearancePolicyTest {') == 1,
         'Original comment appearance policy suite changed')
    renamed = once(upstream_test, 'class VideoCommentAppearancePolicyTest {', 'class ' + APPEARANCE_TEST_CLASS + ' {')
    for relative, data in ((APPEARANCE_RELATIVE, raws['VideoCommentAppearance.kt']), (APPEARANCE_TEST_RELATIVE, renamed.encode('utf8'))):
        target = Path(output) / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        need(target.read_bytes() == data, 'Comment appearance readback differs: ' + relative)
    save(repo, output, 'v046-comment-appearance-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, wholeOriginalFile=True, originalSHA256=sha(original),
        vendoredDirectCopyExcluded=True, vendoredToV046=inverse_proof(previous, original),
        originalPolicyCases=2, originalTestRenamedTo=APPEARANCE_TEST_CLASS,
        originalToWindowsTest=inverse_proof(upstream_test, renamed), jvmTestsExecuted=False))
    return {'files': [dict(path=APPEARANCE_RELATIVE, sha256=sha(raws['VideoCommentAppearance.kt'])),
                      dict(path=APPEARANCE_TEST_RELATIVE, sha256=sha(renamed))]}


# ---------------------------------------------------------------- fraud feedback host and its video detail adapter

def fraud_host_body(repo, output):
    """The v0.4.6 shared host with the Windows feedback port; returns the body for the producer's emit()."""
    sources, _, _ = fixed(repo)
    original = sources['CommentFraudFeedbackHost.kt']
    body = once(original, 'import android.widget.Toast\n', '')
    body = once(body, 'import androidx.compose.ui.platform.LocalContext\n',
                'import com.bilipai.desktop.ui.LocalDesktopCommentBindings\n')
    body = once(body, '    val context = LocalContext.current\n',
                '    val platform = LocalDesktopCommentBindings.current\n')
    body = once(body, 'LaunchedEffect(commentViewModel, enabled, context.applicationContext) {',
                'LaunchedEffect(commentViewModel, enabled, platform) {')
    body = once(body, 'Toast.makeText(context, lightMessage, Toast.LENGTH_SHORT).show()',
                'if (platform.isOwned()) platform.showFeedback(lightMessage)')
    for forbidden in ('Toast', 'LocalContext', 'context'):
        need(forbidden not in body, 'Android context seam survived: ' + forbidden)
    need(body.count('internal fun CommentFraudFeedbackHost(') == 1 and
         body.count('commentViewModel: VideoCommentViewModel,\n    enabled: Boolean = true,') == 1,
         'Shared fraud host signature changed')
    save(repo, output, 'v046-comment-fraud-host-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, wholeOriginalFile=True, originalSHA256=sha(original),
        originalToWindows=inverse_proof(original, body), sameEventSourceAsAdapterAndSheetHost=True,
        windowsFeedbackPort='LocalDesktopCommentBindings.current.showFeedback', jvmTestsExecuted=False))
    return body


def advance_fraud_adapter(repo, before, output):
    """The v0.4.6 thin adapter on the Windows composer and platform types."""
    sources, _, _ = fixed(repo)
    need(sha(before) == ADAPTER_PREDECESSOR_SHA256, 'Current Windows fraud adapter changed')
    original = sources['VideoDetailCommentFraudOverlayAdapter.kt']
    body = once(original, 'import android.content.Context\n', '')
    body = once(body, 'import androidx.compose.runtime.LaunchedEffect\n',
                'import androidx.compose.runtime.CompositionLocalProvider\nimport androidx.compose.runtime.LaunchedEffect\n')
    body = once(body, 'import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel\n',
                'import com.android.purebilibili.feature.video.viewmodel.DesktopOriginalVideoCommentComposer\n'
                'import com.bilipai.desktop.ui.DesktopCommentPlatform\n'
                'import com.bilipai.desktop.ui.LocalDesktopCommentBindings\n')
    body = once(body, '    context: Context,\n', '    platform: DesktopCommentPlatform,\n')
    body = once(body, 'playbackViewModel: VideoPlaybackViewModel,', 'playbackViewModel: DesktopOriginalVideoCommentComposer,')
    # The shared host reads the mounted owner from the composition; the adapter's explicit platform stays authoritative.
    body = once(body, '    CommentFraudFeedbackHost(commentViewModel = commentViewModel)\n',
                '    CompositionLocalProvider(LocalDesktopCommentBindings provides platform) {\n'
                '        CommentFraudFeedbackHost(commentViewModel = commentViewModel)\n'
                '    }\n')
    for forbidden in ('Context', 'VideoPlaybackViewModel', 'Toast', 'CommentFraudResultDialog', 'fraudDialogStatus'):
        need(forbidden not in body, 'Fraud adapter still carries: ' + forbidden)
    save(repo, output, 'v046-comment-fraud-adapter-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, wholeOriginalFile=True, originalSHA256=sha(original),
        originalToWindows=inverse_proof(original, body), retainedToCurrent=inverse_proof(before, body),
        sameReceiptToGenericVmChain=True, explicitPlatformProvidedToSharedHost=True, jvmTestsExecuted=False))
    return body


# ---------------------------------------------------------------- inline nested reply entrance (8eb5996b)

ENTRANCE_BLOCK = '''
    // Inline hosts replace the main list directly, unlike window sheets and the
    // portrait overlay host. Give this panel its own full-height bottom-up entry.
    val motion = com.android.purebilibili.core.ui.rememberAppBottomSheetMotion()
    val motionEnabled = !motion.reducedMotion &&
        com.android.purebilibili.core.ui.LocalAppThemeConfig.current.uiEntranceAnimationEnabled
    val entranceProgress = remember(rootReply.rpid) {
        Animatable(if (motionEnabled) 1f else 0f)
    }
    LaunchedEffect(rootReply.rpid, motionEnabled) {
        if (motionEnabled) {
            entranceProgress.animateTo(0f, animationSpec = motion.dragSettleSpec)
        } else {
            entranceProgress.snapTo(0f)
        }
    }
'''
ENTRANCE_MODIFIER = '''        modifier = modifier.graphicsLayer {
            translationY = size.height * entranceProgress.value.coerceIn(0f, 1f)
        },
'''
INLINE_IMPORTS = ('androidx.compose.animation.core.Animatable', 'androidx.compose.ui.graphics.graphicsLayer')


def advance_inline_entrance(repo, before, output):
    sources, _ = comment_list.fixed(repo)
    parser = comment_list.load(repo, 'v046_inline_entrance_syntax', 'sync-upstream.py')
    old = comment_list.declaration(parser, before, 'VideoInlineSubReplyDetailContent')
    need(sha(old) == INLINE_DECLARATION_SHA256, 'Current inline nested reply owner changed')
    original = comment_list.declaration(parser, sources['SubReplyDetailComponents.kt'], 'VideoInlineSubReplyDetailContent')
    need(original.count(ENTRANCE_BLOCK) == 1 and original.count(ENTRANCE_MODIFIER) == 1,
         'v0.4.6 inline entrance changed')
    # The retained declaration is exactly the original minus the entrance: nothing else differs.
    stripped = original.replace(ENTRANCE_BLOCK, '', 1).replace(ENTRANCE_MODIFIER, '        modifier = modifier,\n', 1)
    need(stripped == old, 'The retained inline owner differs from the original beyond the entrance')
    after = once(before, old, original)
    for name in INLINE_IMPORTS:
        need(('import ' + name + '\n') in sources['SubReplyDetailComponents.kt'], 'Original import changed: ' + name)
        after = ensure_import(after, name)
    save(repo, output, 'v046-inline-subreply-entrance-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalDeclaration=True,
        originalDeclarationSHA256=sha(original), retainedToCurrent=inverse_proof(before, after),
        onlyTheEntranceDiffers=True, realSheetMotionMember='AppBottomSheetMotion.dragSettleSpec',
        platformAdaptationNeeded=False, danmakuComposerExitNotClaimed=True, jvmTestsExecuted=False))
    return after


# ---------------------------------------------------------------- fullscreen comment host (2bc6e885 + 8eb5996b)

HOST_SORT_BEFORE = '''    val defaultSortMode by com.android.purebilibili.core.store.SettingsManager
        .getCommentDefaultSortMode(context)
        .collectAsStateWithLifecycle(initialValue = com.android.purebilibili.core.store.SettingsManager.getCommentDefaultSortModeSync(context),
'''
HOST_SORT_AFTER = '''    val defaultSortMode by com.android.purebilibili.core.store.DesktopOriginalReplySettings.getCommentDefaultSortMode(context)
        .collectAsStateWithLifecycle(initialValue = com.android.purebilibili.core.store.DesktopOriginalReplySettings.getCommentDefaultSortModeSync(context),
'''
HOST_DECORATIONS_BEFORE = '''    val commentMemberDecorationsEnabled by com.android.purebilibili.core.store.SettingsManager
        .getCommentMemberDecorationsEnabled(context)
'''
HOST_DECORATIONS_AFTER = '''    val commentMemberDecorationsEnabled by com.android.purebilibili.core.store.DesktopOriginalReplySettings.getCommentMemberDecorationsEnabled(context)
'''
HOST_BLUR_BEFORE = '''                                                coveredBlurEffectCache.resolve(blurFrame.blurRadiusPx)
'''
HOST_BLUR_AFTER = '''                                                androidx.compose.ui.graphics.BlurEffect(
                                                    radiusX = blurFrame.blurRadiusPx,
                                                    radiusY = blurFrame.blurRadiusPx,
                                                )
'''
HOST_FRAUD_IMPORTS = (
    'com.android.purebilibili.data.model.CommentFraudStatus',
    'com.android.purebilibili.data.repository.resolveCommentFraudLightMessage',
    'com.android.purebilibili.data.repository.shouldShowCommentFraudResultDialog',
)


def advance_comment_sheet_host(repo, before, output):
    sources, _ = comment_list.fixed(repo)
    parser = comment_list.load(repo, 'v046_comment_host_syntax', 'sync-upstream.py')
    old = comment_list.declaration(parser, before, 'VideoCommentSheetHost')
    need(sha(old) == HOST_DECLARATION_SHA256, 'Current fullscreen comment host changed')
    original = comment_list.declaration(parser, sources['VideoCommentSheetHost.kt'], 'VideoCommentSheetHost')
    need(original.count('CommentFraudFeedbackHost(commentViewModel = commentViewModel, enabled = handleFraudEvents)') == 1,
         'v0.4.6 host no longer calls the shared fraud host')
    body = once(original, 'fun VideoCommentSheetHost(\n', 'internal fun VideoCommentSheetHost(\n')
    body = once(body, '    val context = LocalContext.current\n',
                '    val platform = com.bilipai.desktop.ui.LocalDesktopCommentBindings.current\n'
                '    val context = platform.context\n')
    body = once(body, HOST_SORT_BEFORE, HOST_SORT_AFTER)
    body = once(body, HOST_DECORATIONS_BEFORE, HOST_DECORATIONS_AFTER)
    # The Windows renderer has no Android RenderEffect cache or SDK level: same radius, desktop BlurEffect.
    body = once(body, '                            val coveredBlurEffectCache = remember { BlurRenderEffectCache() }\n', '')
    body = once(body, 'Build.VERSION.SDK_INT >= Build.VERSION_CODES.S',
                'com.bilipai.desktop.ui.desktopDetailRenderEffectsSupported()')
    body = once(body, HOST_BLUR_BEFORE, HOST_BLUR_AFTER)
    for forbidden in ('LocalContext', 'SettingsManager.', 'BlurRenderEffectCache', 'Build.VERSION', 'Toast',
                      'fraudDialogStatus', 'CommentFraudResultDialog('):
        need(forbidden not in body, 'Android seam survived in the host: ' + forbidden)
    for required in ('val sheetVisibility = remember { MutableTransitionState(false) }',
                     'visibleState = sheetVisibility,', 'isDismissDragSettling', 'isDragDismissExitPending',
                     'conversationAnchorId = subReplyState.conversationAnchor?.rpid ?: 0L,',
                     'animationSpec = if (isDraggingSheet) tween(0) else motionSpec.dragSettleSpec,'):
        need(required in body, 'v0.4.6 host member missing: ' + required)
    after = once(before, old, body)
    after = ensure_import(after, 'androidx.compose.animation.core.MutableTransitionState')
    for name in HOST_FRAUD_IMPORTS:
        after = drop_import(repo, after, name)
    save(repo, output, 'v046-comment-sheet-host-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalDeclaration=True,
        originalDeclarationSHA256=sha(original), originalToWindows=inverse_proof(original, body),
        retainedToCurrent=inverse_proof(before, after), sharedFraudHostCalledOnce=True,
        firstMountSlideUp=True, dragSettleSpecAndExitPending=True, conversationAnchorIdentity=True,
        sameParametersAndCallers=True, jvmTestsExecuted=False))
    return after


# ---------------------------------------------------------------- BGM navigation destination (8eb5996b)

DETAIL_ANCHOR = '                        isConversationMode = subReplyState.conversationAnchor != null,\n'
DETAIL_LINE = '                        conversationAnchorId = subReplyState.conversationAnchor?.rpid ?: 0L,\n'


def advance_comment_detail_screen(repo, before, output):
    sources, _, _ = fixed(repo)
    need(sha(before) == DETAIL_SCREEN_PREDECESSOR_SHA256, 'Current Windows comment detail screen changed')
    original = sources['CommentDetailScreen.kt']
    need(original.count(DETAIL_ANCHOR + DETAIL_LINE) == 1, 'v0.4.6 comment detail anchor changed')
    need('conversationAnchorId' not in before, 'Comment detail screen already forwards the conversation anchor')
    after = once(before, DETAIL_ANCHOR, DETAIL_ANCHOR + DETAIL_LINE)
    save(repo, output, 'v046-comment-detail-anchor-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, originalSHA256=sha(original),
        originalOnlyAddsTheLineToPinnedSource=True, retainedToCurrent=inverse_proof(before, after),
        jvmTestsExecuted=False))
    return after
