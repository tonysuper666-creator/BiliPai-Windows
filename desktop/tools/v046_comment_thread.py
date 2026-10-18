"""Complete retained v046 nested-page owner on the existing Windows producer."""
from pathlib import Path
import hashlib
import json
from v044_favorite_detail import inverse_proof
from v046_comment_list import COMMIT, MANIFEST_SHA256, declaration, fixed, load, once, save, sha

TEST_ARCHIVE = 'desktop/upstream-slices/v046-comment-thread'
TEST_MANIFEST_SHA256 = '8607df8e875542ab71c44064e910308fa6062667fb429c338902d489985543a6'
ORIGINAL_TEST_SOURCE = 'src/main/java/com/android/purebilibili/feature/video/ui/components/SubReplyDetailComponents.kt'
ACTUAL_TEST_SOURCE = 'build/generated/dynamic-reply/com/android/purebilibili/feature/video/ui/components/DesktopOriginalSubReplyDetailComponents.kt'


def fixed_tests(repo):
    root = Path(repo)/TEST_ARCHIVE
    assert root.is_dir() and not root.is_symlink()
    raw = (root/'manifest.json').read_bytes()
    assert sha(raw) == TEST_MANIFEST_SHA256, 'Nested-page test manifest changed'
    manifest = json.loads(raw)
    assert (manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) == (
        'jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw')
    assert len(manifest['files']) == 1
    assert {p.name for p in root.iterdir()} == {'manifest.json', 'LICENSE', 'SubReplyDetailPresentationPolicyTest.kt'}
    for row in manifest['files']+[manifest['license']]:
        p = root/row['filename']
        assert p.is_file() and not p.is_symlink() and p.name == Path(row['path']).name
        raw = p.read_bytes()
        assert len(raw) == row['bytes'] and sha(raw) == row['sha256']
        assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest() == row['gitBlob']
    return (root/'SubReplyDetailPresentationPolicyTest.kt').read_text(encoding='utf8')


def emit_original_tests(repo, output):
    original = fixed_tests(repo)
    body = once(original, 'class SubReplyDetailPresentationPolicyTest {',
                'class V046SubReplyDetailPresentationOriginalTest {')
    assert body.count(ORIGINAL_TEST_SOURCE) == 4
    body = body.replace(ORIGINAL_TEST_SOURCE, ACTUAL_TEST_SOURCE)
    assert body.count('@Test') == original.count('@Test') == 32
    save(repo, output, 'v046-comment-thread-original-tests-proof', dict(upstreamCommit=COMMIT,
        testManifestSHA256=TEST_MANIFEST_SHA256, completeOriginalClass=True, originalCases=32,
        actualGeneratedProductionSourceReads=4, originalToWindows=inverse_proof(original, body),
        kotlinExecuted=False))
    destination = Path(output)/'test/com/android/purebilibili/feature/video/ui/components/V046SubReplyDetailPresentationOriginalTest.kt'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(body, encoding='utf8', newline='\n')


def page_type(original):
    first = original.index('private data class SubReplyDetailPage(')
    last = original.index('\n)', first)+2
    return original[first:last]


def advance(repo, before, output):
    sources, _ = fixed(repo)
    raw = sources['SubReplyDetailComponents.kt']
    parser = load(repo, 'v046_comment_thread_syntax', 'sync-upstream.py')
    old = declaration(parser, before, 'SubReplyDetailContent')
    inline = declaration(parser, before, 'VideoInlineSubReplyDetailContent')
    assert sha(old) == 'eb6625aca3395c81e7b0194a01adb34b34151a7fb4547c569fb5b9f072930561', 'Current nested-page owner changed'
    assert sha(inline) == 'f7cf277db872e4efa16e764f03bee2a57e296f8ad66961003c381b4646a0c4d4', 'Current inline comment owner changed'
    assert 'private data class SubReplyDetailPage(' not in before
    for name in ['resolveSubReplyDetailScrollRestoreAction', 'shouldSaveSubReplyDetailScrollBeforeConversationEnter',
                 'resolveSubReplyDetailListScrollResetKey', 'resolveSubReplyTargetListIndex']:
        assert declaration(parser, before, name) == declaration(parser, raw, name), 'Original scroll/target dependency changed'
    original = declaration(parser, raw, 'SubReplyDetailContent')
    body = once(original, '    val context = LocalContext.current\n',
                '    val context = LocalContext.current\n    val platform = LocalDesktopCommentBindings.current\n')
    body = once(body, '    val showLoadedReplyCount by com.android.purebilibili.core.store.SettingsManager\n'
                '        .getSubReplyLoadedCountEnabled(context)\n'
                '        .collectAsStateWithLifecycle(initialValue = false)\n',
                '    val showLoadedReplyCount by platform.subReplyLoadedCountEnabled\n'
                '        .collectAsState(initial = false)\n')
    body = once(body, '            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()',
                '            if (platform.isOwned()) platform.showFeedback(error)')
    body = once(body, 'LocalConfiguration.current.screenHeightDp',
                'com.bilipai.desktop.appearance.DesktopWindowConfiguration.current.screenHeightDp')
    body = once(body, 'resolveVideoCommentSkeletonSlotCount(',
                'com.android.purebilibili.feature.video.viewmodel.resolveVideoCommentSkeletonSlotCount(')
    body = once(body, 'LazyColumn(', 'com.android.purebilibili.core.ui.components.AppLazyColumn(')
    body = once(body, '                                avatarListState = listState.takeIf { avatarPushEnabled },\n'
                '                                layoutPolicy = layoutPolicy,\n'
                '                                item = item,',
                '                                avatarListState = listState.takeIf { avatarPushEnabled },\n'
                '                                avatarItemKey = item.rpid,\n'
                '                                layoutPolicy = layoutPolicy,\n'
                '                                item = item,')
    # The actual inline callers already carry the current SubReplyUiState; pass
    # the original anchor identity rather than sharing the zero/default key.
    forwarded = once(inline, '        isConversationMode = state.conversationAnchor != null,\n',
                     '        isConversationMode = state.conversationAnchor != null,\n'
                     '        conversationAnchorId = state.conversationAnchor?.rpid ?: 0L,\n')
    after = once(before, old, page_type(raw)+'\n\n@Composable\n'+body)
    # The old function already has @Composable immediately before it. Keep one
    # annotation on the function, never accidentally annotate the new page DTO.
    after = once(after, '@Composable\nprivate data class SubReplyDetailPage(',
                 'private data class SubReplyDetailPage(')
    after = once(after, inline, forwarded)
    key_import = 'import androidx.compose.runtime.key\n'
    assert key_import not in after
    after = once(after, 'import androidx.compose.runtime.Composable\n',
                 'import androidx.compose.runtime.Composable\n'+key_import)
    for forbidden in ['Toast.makeText', 'LocalConfiguration.', 'SettingsManager.', 'collectAsStateWithLifecycle']:
        assert forbidden not in body, forbidden
    assert 'if (targetReplyHandled) return@LaunchedEffect' in body
    assert body.index('targetReplyHandled = true') < body.index('listState.animateScrollToItem(targetIndex)')
    save(repo, output, 'v046-comment-thread-host-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalHostDeclaration=True,
        originalToWindows=inverse_proof(original, body), retainedToCurrent=inverse_proof(before, after),
        originalPageTypeSHA256=sha(page_type(raw)), currentInlineInputSHA256=sha(inline),
        originalIndependentListStates=True, realConversationAnchorForwarded=True,
        originalViewportSlotSkeletons=True, consumedEntryTargetAndHighlightTimeoutRetained=True,
        sameExistingVideoAndDynamicStateOwners=True, sameOwnedSettingsAndFeedbackPorts=True,
        broaderInlineEntranceMotionNotClaimed=True))
    emit_original_tests(repo, output)
    return after
