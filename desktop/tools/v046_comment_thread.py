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


SPLIT_STATE_ANCHOR = '    val listState = if (effectiveConversationMode) conversationListState else allRepliesListState\n'
SPLIT_STATE_LINE = ('    val desktopSplit = if (com.bilipai.desktop.ui.LocalDesktopCommentDetailSplitPanes.current) {\n'
                    '        remember(rootReply.rpid) { com.bilipai.desktop.ui.DesktopCommentDetailSplitState() }\n'
                    '    } else null\n')
SPLIT_TARGET_ANCHOR = '                highlightedTargetId = targetReplyId\n                listState.animateScrollToItem(targetIndex)\n'
SPLIT_TARGET_LINE = '                if (targetIndex == 0) desktopSplit?.contextScroll?.scrollTo(0)\n'
SPLIT_REFRESH = ('            AdaptivePullToRefreshBox(\n'
                 '                isRefreshing = isRefreshing,\n'
                 '                onRefresh = onRefresh,\n'
                 '                modifier = Modifier.fillMaxSize(),\n'
                 '            ) {\n')
SPLIT_ROOT_ITEM = ('                    item(key = "root_reply") {\n'
                   '                        // The page transition owns reveal; avoid resizing the lazy-list anchor again.\n')
SPLIT_ROOT_BOX = '                        Box(modifier = Modifier.testTag(SUB_REPLY_DETAIL_ROOT_TAG)) {\n'
SPLIT_SECTION = ('                        Column {\n'
                 '                            AppHorizontalDivider(\n'
                 '                                thickness = layoutPolicy.sectionDividerThicknessDp.dp,\n')
SPLIT_END = '                }\n            }\n        }\n    }\n}'


def exact(text, before, after):
    # Not an assert: a missing anchor must fail under any interpreter flags.
    if text.count(before) != 1:
        raise ValueError('Comment detail split anchor changed: '+before[:90].replace('\n', ' '))
    return text.replace(before, after, 1)


# The original comment's own item states. Its renderer is recomposed (not moved) when it leaves
# lazy item 0 for the context pane, so these reads go through the split owner; every other reply,
# and every host without the opt-in, keeps the original `remember`.
SPLIT_ITEM_STATES = [
    ('    var showActionSheet by remember(item.rpid) { mutableStateOf(false) }\n', 'actionSheet', '', 'false'),
    ('    var showFreeCopyDialog by remember(item.rpid) { mutableStateOf(false) }\n', 'freeCopy', '', 'false'),
    ('    var showReportDialog by remember(item.rpid) { mutableStateOf(false) }\n', 'report', '', 'false'),
    ('    var translatedMessage by remember(item.rpid) { mutableStateOf<String?>(null) }\n', 'translation', '<String?>', 'null'),
    ('    var hatedBodyExpanded by remember(item.rpid, isHated) { mutableStateOf(false) }\n', 'hatedBody', '', 'false'),
    ('    var hatePromptHandled by remember(item.rpid) { mutableStateOf(false) }\n', 'hatePrompt', '', 'false'),
    ('    var confirmBlockUser by remember(item.rpid) { mutableStateOf(false) }\n', 'blockUser', '', 'false'),
]


def desktop_split_item_states(text):
    for line, name, kind, initial in SPLIT_ITEM_STATES:
        keys = line[line.index('remember(')+len('remember('):line.index(') {')]
        text = exact(text, line, line[:line.index('remember(')]+
                     'com.bilipai.desktop.ui.rememberDesktopCommentDetailItemState'+kind+
                     '("'+name+'", '+keys+') { '+initial+' }\n')
    return text


def advance_split_item_states(repo, before, output):
    """Last comment-detail stage: route the item states through the split owner (after the inline entrance)."""
    after = desktop_split_item_states(before)
    save(repo, output, 'v046-comment-detail-split-item-states-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, itemStates=[name for _, name, _, _ in SPLIT_ITEM_STATES],
        isTranslatingStaysLocal=True, retainedToCurrent=inverse_proof(before, after)))
    return after



# RichCommentText's own AppText: the original comment's lines reach the split owner, so a reading
# point survives the reflow between pane widths. Outside the split root slot the hook is null.
ROOT_TEXT_BEFORE = (
    '        AppText(\n'
    '            text = annotatedString,\n'
    '            inlineContent = inlineContent,\n'
    '            fontSize = fontSize,\n'
    '            color = color,\n'
    '            lineHeight = (fontSize.value * 1.5).sp,\n'
    '            maxLines = maxLines,\n'
    '            modifier = textModifier\n'
    '        )\n')
ROOT_TEXT_AFTER = (
    '        val desktopRootText = com.bilipai.desktop.ui.rememberDesktopCommentDetailRootText(text)\n'
    + ROOT_TEXT_BEFORE.replace('            modifier = textModifier\n',
        '            onTextLayout = desktopRootText?.onTextLayout ?: {},\n'
        '            modifier = desktopRootText?.let { textModifier.then(it.modifier) } ?: textModifier\n'))


def desktop_root_text_layout(text):
    return exact(text, ROOT_TEXT_BEFORE, ROOT_TEXT_AFTER)


def advance_root_text_layout(repo, before, output):
    """Last reply-components stage: the rich comment text reports its layout inside the split root slot."""
    after = desktop_root_text_layout(before)
    save(repo, output, 'v046-comment-detail-root-text-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, hookedFunction='RichCommentText',
        retainedToCurrent=inverse_proof(before, after)))
    return after

def desktop_split_panes(body):
    """Standalone comment detail on a wide window: original comment | replies.

    One page, one list state, one VM. The root renderer moves out of lazy item 0
    into the context pane only when the desktop host opts in and the width is
    split; every reply keeps its key and index, so jump/restore stay valid."""
    body = exact(body, SPLIT_STATE_ANCHOR, SPLIT_STATE_ANCHOR+SPLIT_STATE_LINE)
    body = exact(body, SPLIT_TARGET_ANCHOR, SPLIT_TARGET_ANCHOR.replace(
        '                listState.animateScrollToItem', SPLIT_TARGET_LINE+'                listState.animateScrollToItem'))
    first = body.find(SPLIT_ROOT_ITEM)
    if first < 0 or body.count(SPLIT_ROOT_ITEM) != 1 or not body.endswith(SPLIT_END):
        raise ValueError('Comment detail split anchor changed: root item or page end')
    start = first+len(SPLIT_ROOT_ITEM)
    stop = body.find(SPLIT_SECTION, start)
    root = body[start:stop]
    if stop < 0 or not root.startswith(SPLIT_ROOT_BOX) or not root.endswith('                        }\n') \
            or root.count('item = rootReply,') != 1:
        raise ValueError('Comment detail split anchor changed: root renderer')
    body = body[:start]+'                        if (!desktopSplitActive) desktopRootReply(listState)\n'+body[stop:]
    body = exact(body, SPLIT_REFRESH,
        '            // The parameter shadows the page list: narrow keeps the original avatar push,\n'
        '            // the wide context pane passes null because it is not a lazy item.\n'
        '            val desktopRootReply: @Composable (androidx.compose.foundation.lazy.LazyListState?) -> Unit = { listState ->\n'
        '                com.bilipai.desktop.ui.DesktopCommentDetailRootSlot(desktopSplit, listState) {\n'+root+'                }\n'
        '            }\n'
        '            com.bilipai.desktop.ui.DesktopCommentDetailSplitPanes(\n'
        '                state = desktopSplit,\n'
        '                listState = listState,\n'
        '                active = pageActive,\n'
        '                dividerColor = appearance.dividerColor,\n'
        '                context = { desktopRootReply(null) },\n'
        '            ) { desktopSplitActive ->\n'+SPLIT_REFRESH)
    return body[:-len(SPLIT_END)]+'                }\n            }\n            }\n        }\n    }\n}'


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
    body = desktop_split_panes(body)
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
        broaderInlineEntranceMotionNotClaimed=True, desktopCommentDetailSplitOptIn=True))
    emit_original_tests(repo, output)
    return after
