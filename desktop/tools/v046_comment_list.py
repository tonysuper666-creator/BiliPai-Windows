"""Retained v046 shared comments on the sole existing Windows owners."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys
from v044_favorite_detail import inverse_proof

COMMIT = 'd6bf8b1655ae910999860bf13e65c8fcea5b345b'
ARCHIVE = 'desktop/upstream-slices/v046-comment-list'
MANIFEST_SHA256 = 'bf6aec2a3a7802d5394520136c79c7d96ee2913be31b1cd449fa886a9ec5d8b1'
PACKAGE = 'com/android/purebilibili/feature/video/ui/components/'


def sha(value):
    return hashlib.sha256(value.encode('utf8') if isinstance(value, str) else value).hexdigest()


def fixed(repo):
    root = Path(repo)/ARCHIVE
    assert root.is_dir() and not root.is_symlink(), 'Missing v046 comment inputs'
    raw = (root/'manifest.json').read_bytes()
    assert sha(raw) == MANIFEST_SHA256, 'Comment manifest changed'
    manifest = json.loads(raw)
    assert (manifest['repository'], manifest['tag'], manifest['commit'], manifest['hashNormalization']) == (
        'jay3-yy/BiliPai', 'v0.4.6', COMMIT, 'raw')
    assert len(manifest['files']) == 22
    assert {p.name for p in root.iterdir()} == {r['filename'] for r in manifest['files']} | {'manifest.json', 'LICENSE'}
    sources = {}
    for row in manifest['files']:
        file = root/row['filename']
        assert file.is_file() and not file.is_symlink() and file.name == Path(row['path']).name
        raw = file.read_bytes()
        assert len(raw) == row['bytes'] and sha(raw) == row['sha256'], file.name
        assert hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest() == row['gitBlob'], file.name
        sources[file.name] = raw.decode('utf8').replace('\r\n', '\n')
    assert len(sources) == 22
    assert not (root/'LICENSE').is_symlink() and sha((root/'LICENSE').read_bytes()) == manifest['license']['sha256']
    return sources, manifest


def load(repo, name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(repo)/'desktop/tools'/filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def save(repo, output, name, value):
    root = (Path(repo)/ARCHIVE).resolve()
    output = Path(output).resolve()
    assert not (output.is_relative_to(root) or root.is_relative_to(output)), 'Output overlaps retained comments'
    output.mkdir(parents=True, exist_ok=True)
    (output/(name+'.json')).write_text(json.dumps(value, indent=2)+'\n', encoding='utf8', newline='\n')


def fun_span(parser, text, name):
    tokens = parser.kotlin_tokens(text)
    hits = [i for i, (word, _, _) in enumerate(tokens)
            if word == 'fun' and i+1 < len(tokens) and tokens[i+1][0] == name]
    assert len(hits) == 1, (name, len(hits))
    index = hits[0]
    first = text.rfind('\n', 0, tokens[index][1])+1
    while tokens[index][0] != '(':
        index += 1
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == '(')-(tokens[index][0] == ')')
    while tokens[index][0] != '{':
        index += 1
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == '{')-(tokens[index][0] == '}')
    return first, tokens[index][2]


def declaration(parser, text, name):
    first, last = fun_span(parser, text, name)
    return text[first:last]


def once(text, before, after):
    assert text.count(before) == 1, (before[:100], text.count(before))
    return text.replace(before, after, 1)


def ensure_import(text, name):
    """Add one import for a symbol the copied original declaration already names; imports need no order."""
    line = 'import '+name+'\n'
    if line in text:
        return text
    first = text.index('\nimport ')+1
    return text[:first]+line+text[first:]


# v046_identity_badges replaces the home-page UserVipBadge.kt output with the v0.4.6
# definer. These owners keep the original fully qualified call and its count.
VIP_PINK_ORIGINAL = 'com.android.purebilibili.core.ui.components.userVipPink()'


def windows_vip_pink(text, expected):
    assert text.count(VIP_PINK_ORIGINAL) == expected, ('Original VIP colour owners changed', text.count(VIP_PINK_ORIGINAL))
    return text


def translation_job(parser, text):
    marker = 'isTranslating = true'
    assert text.count(marker) == 1
    first = text.rfind('scope.launch {', 0, text.index(marker))
    assert first >= 0
    tokens = parser.kotlin_tokens(text)
    index = next(i for i, (word, start, _) in enumerate(tokens) if word == '{' and start > first)
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == '{')-(tokens[index][0] == '}')
    return text[first:tokens[index][2]]


def advance_reply_components(repo, before, output):
    sources, manifest = fixed(repo)
    assert sha(before) == manifest['beforeWindowsReplyComponentsBodySHA256'], 'Current Windows reply owner changed'
    parser = load(repo, 'v046_comment_list_syntax', 'sync-upstream.py')
    old = declaration(parser, before, 'ReplyItemView')
    original = declaration(parser, sources['ReplyComponents.kt'], 'ReplyItemView')
    body = once(original, '    val context = LocalContext.current\n',
                '    val context = LocalContext.current\n    val platform = LocalDesktopCommentBindings.current\n')
    body = once(body, 'SettingsManager.getCommentCollapsedReplyPreviewLimitSync(context)', 'platform.collapsedReplyPreviewLimit')
    body = once(body, '    val blockedUpRepository = remember { BlockedUpRepository.getInstance(context) }\n', '')
    body = once(body, '    var pendingSaveReply by remember(item.rpid) { mutableStateOf<ReplyItem?>(null) }\n', '')
    # Windows uses the existing file picker/export transaction. Android storage
    # permission state has no Windows counterpart and must not become another owner.
    start = body.index('    val storagePermission = rememberStoragePermissionState')
    end = body.index('    fun requestSaveReplyCommentImage()', start)
    assert 'storagePermission' not in body[:start]
    body = body[:start]+body[end:]
    for name in ['launchSaveReplyCommentImage', 'requestSaveReplyCommentImage', 'shareReplyComment', 'blockReplyUser']:
        body = once(body, declaration(parser, body, name), declaration(parser, old, name))
    body = once(body, translation_job(parser, body), translation_job(parser, old))
    body = once(body, 'com.android.purebilibili.core.ui.AppAlertDialog(',
                'com.bilipai.desktop.ui.DesktopWindowsCommentAlertDialog(')
    for forbidden in ['rememberStoragePermissionState', 'saveReplyCommentImageToGallery',
                      'BlockedUpRepository.getInstance', 'Toast.makeText', 'Intent(',
                      'CommentGrpcRepository.translateReply']:
        assert forbidden not in body, forbidden
    assert 'avatarListState: LazyListState? = null' in body and 'PushingReplyMemberAvatar(' in body
    body = windows_vip_pink(body, 2)
    assert body.count('AppContentColors.error()') == 1
    after = once(before, old, body)
    after = ensure_import(after, 'com.android.purebilibili.core.ui.AppContentColors')
    if 'import androidx.compose.foundation.lazy.LazyListState\n' not in after:
        after = once(after, 'package com.android.purebilibili.feature.video.ui.components\n',
                     'package com.android.purebilibili.feature.video.ui.components\n\nimport androidx.compose.foundation.lazy.LazyListState\n')
    save(repo, output, 'v046-reply-list-renderer-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalDeclaration=True,
        originalDeclarationSHA256=sha(original), originalToWindows=inverse_proof(original, body),
        retainedToCurrent=inverse_proof(before, after),
        retainedWindowsLocalFunctions=['launchSaveReplyCommentImage', 'requestSaveReplyCommentImage', 'shareReplyComment', 'blockReplyUser'],
        sameWindowsTranslationJob=True, everyOtherCurrentDeclarationRetained=True))
    return after


def advance_subreply_components(repo, before, output):
    sources, _ = fixed(repo)
    parser = load(repo, 'v046_subreply_list_syntax', 'sync-upstream.py')
    old = declaration(parser, before, 'SubReplyDetailItem')
    old_host = declaration(parser, before, 'SubReplyDetailContent')
    assert sha(old) == '6b86b7905450b1ea92cfc816fba2abf6cc29cbbcc325a20930e974c983923b9b', 'Current nested reply renderer changed'
    assert sha(old_host) == '26d70389f5424efe7d33d1bd8af3911b0e998883e3eb502fa558bf721c9beb95', 'Current nested reply list owner changed'
    original = declaration(parser, sources['SubReplyDetailComponents.kt'], 'SubReplyDetailItem')
    body = once(original, '    val context = LocalContext.current\n',
                '    val context = LocalContext.current\n    val platform = LocalDesktopCommentBindings.current\n')
    body = once(body, '    val blockedUpRepository = remember { BlockedUpRepository.getInstance(context) }\n', '')
    body = once(body, '    var pendingSaveReply by remember(item.rpid) { mutableStateOf<ReplyItem?>(null) }\n', '')
    first = body.index('    val storagePermission = rememberStoragePermissionState')
    last = body.index('    fun requestSaveReplyCommentImage()', first)
    body = body[:first]+body[last:]
    for name in ['launchSaveReplyCommentImage', 'requestSaveReplyCommentImage', 'shareReplyComment', 'blockReplyUser']:
        body = once(body, declaration(parser, body, name), declaration(parser, old, name))
    body = once(body, translation_job(parser, body), translation_job(parser, old))
    body = once(body, 'com.android.purebilibili.core.ui.AppAlertDialog(',
                'com.bilipai.desktop.ui.DesktopWindowsCommentAlertDialog(')
    for forbidden in ['rememberStoragePermissionState', 'saveReplyCommentImageToGallery',
                      'BlockedUpRepository.getInstance', 'Toast.makeText', 'Intent(',
                      'CommentGrpcRepository.translateReply']:
        assert forbidden not in body, forbidden
    body = windows_vip_pink(body, 1)
    assert body.count('AppContentColors.error()') == 1 and body.count('mutableIntStateOf(') == 1
    host = once(old_host, '    val appearance = rememberVideoCommentAppearance()\n',
                '    val appearance = rememberVideoCommentAppearance()\n    val avatarPushEnabled = rememberCommentAvatarPushEnabled()\n')
    calls = call_spans(parser, host, 'SubReplyDetailItem')
    assert len(calls) == 2, 'Root and loaded nested reply owners changed'
    for first, last in reversed(calls):
        call = host[first:last]
        root = 'item = rootReply,' in call
        assert root or 'item = item,' in call
        key = '"root_reply"' if root else 'item.rpid'
        call = once(call, 'SubReplyDetailItem(\n', 'SubReplyDetailItem(\n'+
                    '                            avatarListState = listState.takeIf { avatarPushEnabled },\n'+
                    '                            avatarItemKey = '+key+',\n')
        host = host[:first]+call+host[last:]
    after = once(once(before, old, body), old_host, host)
    # The complete original renderer consumes two newer spacing fields. Keep
    # its whole original layout contract/factory together, rather than inventing
    # ad-hoc values or leaving an uncompilable old DTO behind.
    def layout_type(text):
        first = text.index('internal data class SubReplyDetailLayoutPolicy(')
        last = text.index('\n)', first)+2
        return text[first:last]
    original_type = layout_type(sources['SubReplyDetailComponents.kt'])
    original_factory = declaration(parser, sources['SubReplyDetailComponents.kt'], 'resolveSubReplyDetailLayoutPolicy')
    after = once(after, layout_type(before), original_type)
    after = once(after, declaration(parser, before, 'resolveSubReplyDetailLayoutPolicy'), original_factory)
    for imported in ['androidx.compose.foundation.lazy.LazyListState', 'androidx.compose.ui.layout.onSizeChanged']:
        line = 'import '+imported+'\n'
        if line not in after:
            after = once(after, 'import androidx.compose.ui.Modifier\n', 'import androidx.compose.ui.Modifier\n'+line)
    # The copied original renderer names these two symbols; the retained Windows file never imported them.
    for imported in ['androidx.compose.runtime.mutableIntStateOf', 'com.android.purebilibili.core.ui.AppContentColors']:
        after = ensure_import(after, imported)
    save(repo, output, 'v046-subreply-list-renderer-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalItemDeclaration=True,
        originalToWindows=inverse_proof(original, body), retainedToCurrent=inverse_proof(before, after),
        sameExistingConversationAndListOwner=True, exactRootLazyKey='root_reply', exactChildLazyKey='item.rpid',
        actualListStateForwarded=True, existingWindowsSaveShareBlockAndTranslationRetained=True,
        completeOriginalLayoutTypeAndFactory=True, originalLayoutTypeSHA256=sha(original_type),
        originalLayoutFactorySHA256=sha(original_factory),
        broaderV046ConversationHostNotClaimed=True))
    return after


def advance_dynamic_items(repo, before, output):
    sources, _ = fixed(repo)
    parser = load(repo, 'v046_dynamic_items_syntax', 'sync-upstream.py')
    media = load(repo, 'v046_dynamic_items_selector', 'extract-upstream-media.py')
    old = media.function(before, 'dynamicInlineCommentItems', parser)
    assert sha(old) == '3a7c2aef1c72792cfa8f604ac9b58be5823d0b17218f728a91265eb6658aa589', 'Current dynamic comment slot owner changed'
    original = media.function(sources['DynamicCommentSheet.kt'], 'dynamicInlineCommentItems', parser)
    assert 'avatarListState: LazyListState? = null' in original
    assert 'avatarListState = avatarListState,' in original
    after = once(before, old, original)
    if 'import androidx.compose.foundation.lazy.LazyListState\n' not in after:
        after = once(after, 'import androidx.compose.foundation.lazy.LazyListScope\n',
                     'import androidx.compose.foundation.lazy.LazyListScope\nimport androidx.compose.foundation.lazy.LazyListState\n')
    save(repo, output, 'v046-dynamic-inline-comment-items-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalExtension=True,
        originalDeclarationSHA256=sha(original), retainedToCurrent=inverse_proof(before, after),
        existingRawRowsAndCallbacks=True, noAdditionalSessionCacheOrTransport=True,
        broaderDynamicCommentSheetNotClaimed=True))
    return after


def advance_dynamic_detail_layout(repo, before, output):
    parser = load(repo, 'v046_dynamic_detail_avatar_syntax', 'sync-upstream.py')
    assert 'avatarPushEnabled' not in before, 'Dynamic detail avatar preference already has an owner'
    after = once(before, '    if (!platform.isOwned() || !session.isOwned()) return\n',
                 '    if (!platform.isOwned() || !session.isOwned()) return\n'+
                 '    val avatarPushEnabled = com.android.purebilibili.feature.video.ui.components.rememberCommentAvatarPushEnabled()\n')
    spans = call_spans(parser, after, 'dynamicInlineCommentItems')
    assert len(spans) == 1, 'Actual dynamic detail comment-list owner changed'
    first, last = spans[0]
    call = after[first:last]
    advanced = once(call, 'dynamicInlineCommentItems(\n', 'dynamicInlineCommentItems(\n'+
                    '                        avatarListState = (if (useSplitLayout) commentListState else detailListState)\n'+
                    '                            .takeIf { avatarPushEnabled },\n')
    after = after[:first]+advanced+after[last:]
    sources, _ = fixed(repo)
    original_wiring = '''avatarListState = (if (useSplitLayout) commentListState else detailListState)
                            .takeIf { avatarPushEnabled },'''
    assert original_wiring in sources['DynamicDetailScreen.kt'] and original_wiring in after
    save(repo, output, 'v046-dynamic-detail-avatar-owner-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, actualOriginalVisibleListWiring=True,
        originalWiring=original_wiring, existingAccountAndSessionAdmissionRetained=True,
        retainedToCurrent=inverse_proof(before, after), newListStateCreated=False,
        broaderV046DetailScreenNotClaimed=True))
    return after


def settings_members(repo):
    sources, _ = fixed(repo)
    text = sources['SettingsManager.kt']
    body = text[text.index('object SettingsManager {')+len('object SettingsManager {'):text.rfind('}')]
    parser = load(repo, 'v046_comment_settings_syntax', 'sync-upstream.py')
    selector = load(repo, 'v046_comment_settings_declarations', 'extract-appearance-platform.py')
    return selector.declarations(parser, body, ['KEY_COMMENT_AVATAR_PUSH_ENABLED',
        'getCommentAvatarPushEnabled', 'setCommentAvatarPushEnabled'])


def advance_settings(repo, before, output, owner):
    assert owner in {'reply', 'playback'}
    assert 'getCommentAvatarPushEnabled(' not in before and 'KEY_COMMENT_AVATAR_PUSH_ENABLED' not in before
    closing = before.rfind('}')
    assert closing >= 0 and not before[closing+1:].strip()
    members = settings_members(repo)
    if owner == 'reply':
        sources, _ = fixed(repo)
        parser = load(repo, 'v046_reply_sort_settings_syntax', 'sync-upstream.py')
        members += '\n'+declaration(parser, sources['SettingsManager.kt'], 'setCommentDefaultSortMode')+'\n'
        assert 'setCommentDefaultSortMode(' not in before
    after = before[:closing]+members+'\n'+before[closing:]
    save(repo, output, 'v046-comment-avatar-'+owner+'-settings-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalMembers=True,
        membersSHA256=sha(members), sameExistingSettingsOwner=True,
        retainedToCurrent=inverse_proof(before, after)))
    return after


def emit_shared(repo, output):
    root = (Path(repo)/ARCHIVE).resolve()
    destination = Path(output).resolve()
    assert not (destination.is_relative_to(root) or root.is_relative_to(destination)), 'Output overlaps retained comments'
    sources, _ = fixed(repo)
    # Keep the complete placement kernel, its upstream attribution and GPL notice.
    emitted = {}
    for name in ['NagramChatAvatarPlacement.kt', 'CommentContentReveal.kt', 'CommentCascadingContent.kt']:
        emitted[PACKAGE+name] = sources[name]
    assert sources['CommentContentReveal.kt'].count('sheetMotion.reducedMotion') == 1
    assert sources['CommentCascadingContent.kt'].count('motion.reducedMotion') == 1
    # AppBottomSheetMotion.reducedMotion is a real member since v046-sheet-motion (desktop/tools/v046_sheet_motion.py),
    # so these two reads need no compatibility extension.
    original = sources['CommentAvatarPush.kt']
    body = once(original, 'import androidx.compose.ui.platform.LocalContext', 'import com.bilipai.desktop.ui.LocalDesktopCommentBindings')
    body = once(body, 'import androidx.lifecycle.compose.collectAsStateWithLifecycle', 'import androidx.compose.runtime.collectAsState')
    body = once(body, 'import com.android.purebilibili.core.store.SettingsManager', 'import com.android.purebilibili.core.store.DesktopOriginalReplySettings')
    body = once(body, '    val context = LocalContext.current', '    val platform = LocalDesktopCommentBindings.current')
    body = once(body, 'remember(context) { SettingsManager.getCommentAvatarPushEnabled(context) }',
                'remember(platform) { DesktopOriginalReplySettings.getCommentAvatarPushEnabled(platform.context) }')
    body = once(body, '.collectAsStateWithLifecycle(initialValue = false)', '.collectAsState(initial = false)')
    emitted[PACKAGE+'DesktopOriginalCommentAvatarPush.kt'] = body
    # The current ReplyMemberAvatar declaration is already byte-identical to v046.
    # It remains in its existing owner and is deliberately not emitted again here.
    for relative, text in emitted.items():
        target = Path(output)/relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf8', newline='\n')
    test = Path(output)/'test'/PACKAGE/'V046CommentAvatarPushOriginalPolicyTest.kt'
    test.parent.mkdir(parents=True, exist_ok=True)
    original_test = sources['CommentAvatarPushPolicyTest.kt']
    test.write_text(once(original_test, 'class CommentAvatarPushPolicyTest {',
                         'class V046CommentAvatarPushOriginalPolicyTest {'), encoding='utf8', newline='\n')
    save(repo, output, 'v046-comment-avatar-shared-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, originalToWindows=inverse_proof(original, body),
        completeOriginalPlacementAndMotion=True, retainedNagramAttribution=True,
        originalPolicyCasesWritten=13, emitted=[dict(path=p, sha256=sha(t)) for p, t in emitted.items()]))
    return emitted


def call_spans(parser, text, name):
    tokens = parser.kotlin_tokens(text)
    result = []
    for index, (word, first, _) in enumerate(tokens):
        if word != name or index+1 >= len(tokens) or tokens[index+1][0] != '(':
            continue
        if index and tokens[index-1][0] in {'fun', 'class'}:
            continue
        end = index+1
        depth = 1
        while depth:
            end += 1
            depth += (tokens[end][0] == '(')-(tokens[end][0] == ')')
        result.append((first, tokens[end][2]))
    return result


def advance_comment_tab(repo, before, output):
    sources, _ = fixed(repo)
    parser = load(repo, 'v046_shared_tab_syntax', 'sync-upstream.py')
    old = declaration(parser, before, 'VideoCommentTab')
    assert sha(old) == '29dd6c290b47c88ef6d33baf7768ee1e0f17c69f1b790a5fd7dd647401087337', 'Current shared comment list changed'
    original = declaration(parser, sources['VideoContentSection.kt'], 'VideoCommentTab')
    body = once(original, 'LocalConfiguration.current.screenHeightDp',
                'com.bilipai.desktop.appearance.DesktopWindowConfiguration.current.screenHeightDp')
    body = once(body, 'com.android.purebilibili.core.ui.animation.MaybeDissolvableVideoCard(',
                'com.bilipai.desktop.ui.DesktopReplyDissolvableContainer(')
    after = once(before, old, body)
    save(repo, output, 'v046-shared-comment-tab-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalDeclaration=True,
        originalToWindows=inverse_proof(original, body), retainedToCurrent=inverse_proof(before, after),
        actualPinnedIdsForwarded=True, explicitOwnerAndDuration=True, existingDensityAndDissolvePorts=True))
    return after


def advance_search_sheet(repo, before, output):
    sources, _ = fixed(repo)
    assert sha(before) == '57d21fe8457f60fd3d7eb691134a05c2a3e376c7123bf2b8359d143ee87183fb', 'Current Windows comment search changed'
    original = sources['CommentSearchSheet.kt']
    body = once(original, 'import android.widget.Toast\n', '')
    body = once(body, 'import androidx.compose.ui.platform.LocalClipboardManager\n', '')
    body = once(body, 'import androidx.compose.ui.platform.LocalContext\n',
                'import coil3.compose.LocalPlatformContext as LocalContext\nimport com.bilipai.desktop.ui.LocalDesktopCommentBindings\n')
    body = once(body, '    val clipboardManager = LocalClipboardManager.current',
                '    val platform = LocalDesktopCommentBindings.current')
    body = once(body, 'clipboardManager.setText(AnnotatedString(entry.reply.content.message))',
                'platform.copyText(entry.reply.content.message, "评论")')
    body = once(body, 'Toast.makeText(context, "评论已复制", Toast.LENGTH_SHORT).show()',
                'platform.showFeedback("评论已复制")')
    body = once(body, 'import com.android.purebilibili.core.ui.AppModalBottomSheet\n',
                'import com.bilipai.desktop.ui.DesktopWindowsCommentSearchModalSheet as AppModalBottomSheet\n')
    assert 'val glassActive = false' in body
    body = windows_vip_pink(body, 1)
    assert 'avatarListState = listState.takeIf { avatarPushEnabled }' in body
    assert 'platform.copyText(entry.reply.content.message, "评论")' in before
    assert 'platform.showFeedback("评论已复制")' in before
    save(repo, output, 'v046-comment-search-sheet-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalFile=True,
        originalToWindows=inverse_proof(original, body), retainedToCurrent=inverse_proof(before, body),
        originalSearchPoolFilteringAndSorting=True, existingFullSearchVmCallbacks=True,
        existingWindowsModalClipboardFeedbackAndCoilPorts=True, originalGlassDisabledPolicyRetained=True,
        actualRootAndChildEntryKeysRetained=True, sameExplicitListState=True))
    return body


def advance_main_comment_list(repo, before, output):
    sources, _ = fixed(repo)
    parser = load(repo, 'v046_main_list_syntax', 'sync-upstream.py')
    old = declaration(parser, before, 'VideoCommentMainList')
    assert sha(old) == 'a59cf855214749dd79d390e9c19e4bb76befc2c10fe6594645e3bb82f2ee45d3', 'Current fullscreen/general comment list changed'
    original = declaration(parser, sources['VideoCommentSheetHost.kt'], 'VideoCommentMainList')
    for name in ['LoadingFooter', 'NoMoreFooter']:
        assert declaration(parser, before, name) == declaration(parser, sources['VideoCommentSheetHost.kt'], name), 'Main comment footer dependency changed'
    body = once(original, '    val context = LocalContext.current\n',
                '    val platform = com.bilipai.desktop.ui.LocalDesktopCommentBindings.current\n    val context = platform.context\n')
    body = once(body, 'LocalConfiguration.current.screenHeightDp',
                'com.bilipai.desktop.appearance.DesktopWindowConfiguration.current.screenHeightDp')
    body = once(body, 'SettingsManager.setCommentDefaultSortMode(context, mode.apiMode)',
                'com.android.purebilibili.core.store.DesktopOriginalVideoCommentSheetSettings.setCommentDefaultSortMode(context, mode.apiMode)')
    body = once(body, 'resolveVideoCommentSkeletonSlotCount(',
                'com.android.purebilibili.feature.video.viewmodel.resolveVideoCommentSkeletonSlotCount(')
    body = once(body, 'LazyColumn(', 'com.android.purebilibili.core.ui.components.AppLazyColumn(')
    assert body.count('AppContentColors.error()') == 1
    after = once(before, old, body)
    after = ensure_import(after, 'com.android.purebilibili.core.ui.AppContentColors')
    save(repo, output, 'v046-main-comment-list-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalListDeclaration=True,
        originalToWindows=inverse_proof(original, body), retainedToCurrent=inverse_proof(before, after),
        actualListStateForwarded=True, existingGenericVmAndSettingsOwner=True,
        fullSearchStateAndLoadCallbackConnected=True, originalViewportSkeletons=True,
        originalFooterDeclarationsAlreadyByteIdentical=True,
        originalAppLazyColumnPreserved=True, broaderV046FullscreenHostNotClaimed=True))
    return after


def advance_bgm_comment_content(repo, before, output):
    sources, _ = fixed(repo)
    parser = load(repo, 'v046_bgm_comments_syntax', 'sync-upstream.py')
    old = declaration(parser, before, 'BgmDetailContent')
    assert sha(old) == '2d8b7b29f480edb60d27dc5d6cff5bac8a1ff2e2de4ce745adc934caa561dabc', 'Current actual Windows BGM content changed'
    original = declaration(parser, sources['BgmDetailScreen.kt'], 'BgmDetailContent')
    body = once(original, '    modifier: Modifier = Modifier,\n',
                '    modifier: Modifier = Modifier,\n    nowPlayingBarOverlayVisible: Boolean,\n    onMediaSearch: (String, String, String) -> Boolean,\n')
    overlay = '''                val nowPlayingBarOverlayVisible by com.android.purebilibili.feature.audio.player
                    .AudioNowPlayingSession.barOverlayVisible
                    .collectAsStateWithLifecycle()
'''
    body = once(body, overlay, '')
    call = 'BgmInfoCard(detail, onVideosClick, onVideoClick, onUserClick,'
    assert body.count(call) == 2
    body = body.replace(call, call+' onMediaSearch,')
    body = once(body, 'AppContentColors.accent()', 'com.android.purebilibili.core.ui.AppContentColors.accent()')
    body = once(body, 'AppContentColors.primaryText()', 'com.android.purebilibili.core.ui.AppContentColors.primaryText()')
    after = once(before, old, body)
    save(repo, output, 'v046-bgm-comment-content-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalContentDeclaration=True,
        originalToWindows=inverse_proof(original, body), retainedToCurrent=inverse_proof(before, after),
        originalCommentBodyAndThemeColors=True, actualWindowsNowPlayingStateParameter=True,
        actualWindowsMediaSearchCallback=True, newAudioSessionCreated=False,
        originalBgmHasNoAvatarListWiring=True, broaderV046BgmScreenNotClaimed=True))
    return after


def advance_content_comments(repo, before, output):
    parser = load(repo, 'v046_content_comment_syntax', 'sync-upstream.py')
    after = before
    # This is the existing presentation-only projection of the same generic VM.
    # Carry its real pinned set through; no cache or parallel comment state.
    start = after.index('internal class VideoContentCommentState(')
    opening = after.index('(', start)
    tokens = parser.kotlin_tokens(after[opening:])
    index = 0
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == '(')-(tokens[index][0] == ')')
    end = opening+tokens[index][2]
    state = after[start:end]
    projected = once(state, '    val dissolvingIds: Set<Long>,',
                     '    val dissolvingIds: Set<Long>,\n    val pinnedReplyIds: Set<Long> = emptySet(),')
    after = once(after, state, projected)
    after = once(after, '    val dissolvingIds = commentState.dissolvingIds',
                 '    val dissolvingIds = commentState.dissolvingIds\n    val pinnedReplyIds = commentState.pinnedReplyIds')
    # The landscape panel is a separate Windows-owned function: it needs its own real pinned input
    # (its callers read the same generic VM state) instead of a name from the portrait owner.
    landscape = declaration(parser, after, 'LandscapeCommentPanel')
    assert 'pinnedReplyIds' not in landscape
    after = once(after, landscape, once(landscape, '    modifier: Modifier = Modifier,\n) {\n',
                 '    pinnedReplyIds: Set<Long> = emptySet(),\n    modifier: Modifier = Modifier,\n) {\n'))
    spans = call_spans(parser, after, 'VideoCommentTab')
    assert len(spans) == 2, 'Ordinary inline/fullscreen comment list owners changed'
    for first, last in reversed(spans):
        call = after[first:last]
        marker = 'info = info,'
        at = call.index(marker)
        indent = call[call.rfind('\n', 0, at)+1:at]
        replacement = ('upMid = info.owner.mid,\n'+indent+
            'maxTimestampMs = info.pages.firstOrNull { it.cid == info.cid }?.duration?.times(1000L)\n'+indent+
            '    ?: info.pages.firstOrNull()?.duration?.times(1000L),')
        advanced = once(call, marker, replacement)
        line = next(line for line in advanced.splitlines(keepends=True) if line.strip() == 'videoTags = videoTags,')
        advanced = once(advanced, line, '')
        advanced = once(advanced, 'dissolvingIds = dissolvingIds,',
                        'dissolvingIds = dissolvingIds,\n'+indent+'pinnedReplyIds = pinnedReplyIds,')
        after = after[:first]+advanced+after[last:]
    save(repo, output, 'v046-content-comment-callers-proof', dict(upstreamCommit=COMMIT,
        sameVmProjectionExtended=True, actualListCallers=2, metadataDurationRulePreserved=True,
        retainedToCurrent=inverse_proof(before, after)))
    return after


def advance_bottom_calls(repo, before, output, label):
    parser = load(repo, 'v046_bottom_call_syntax', 'sync-upstream.py')
    spans = call_spans(parser, before, 'BottomInputBar')
    assert len(spans) == 1, (label, len(spans))
    first, last = spans[0]
    call = before[first:last]
    marker = 'isLiked = engagementState.isLiked,'
    at = call.index(marker)
    indent = call[call.rfind('\n', 0, at)+1:at]
    advanced = once(call, marker, marker+'\n'+indent+'likeStatusKnown = engagementState.likeStatusKnown,')
    after = before[:first]+advanced+before[last:]
    save(repo, output, 'v046-bottom-call-'+label+'-proof', dict(upstreamCommit=COMMIT,
        actualEngagementKnownStateForwarded=True, retainedToCurrent=inverse_proof(before, after)))
    return after


def advance_tablet_avatars(repo, before, output, label):
    sources, _ = fixed(repo)
    config = {
        'TabletVideoLayout': ('TabletSecondaryContent', 'reply_', '7f889bebc1e793c8d67fe6feecc44784b90edba089c7076dc9ed799d1cec72b8'),
        'TabletCinemaLayout': ('CinemaCommentsPane', 'curtain_reply_', 'b2cb80dc39c0579640dad1d55061b9f242bd08cc2c1895d1dd2b3b2284a2dbef'),
    }
    owner_name, key_prefix, expected = config[label]
    parser = load(repo, 'v046_tablet_avatar_syntax', 'sync-upstream.py')
    owner = declaration(parser, before, owner_name)
    assert sha(owner) == expected, 'Current actual tablet/cinema comment owner changed'
    assert owner.count('val listState = rememberLazyListState()') == 1
    key = '"'+key_prefix+'${reply.rpid}"'
    original_owner = declaration(parser, sources[label+'.kt'], owner_name)
    original_call = next(original_owner[first:last] for first, last in call_spans(parser, original_owner, 'ReplyItemView'))
    assert 'avatarListState = listState.takeIf { avatarPushEnabled }' in original_call
    assert 'avatarItemKey = '+key in original_call
    assert 'key = { "'+key_prefix+'${it.rpid}" }' in owner
    marker = 'val listState = rememberLazyListState()\n'
    at = owner.index(marker)
    indent = owner[owner.rfind('\n', 0, at)+1:at]
    advanced = once(owner, marker, marker+indent+
                    'val avatarPushEnabled = com.android.purebilibili.feature.video.ui.components.rememberCommentAvatarPushEnabled()\n')
    calls = call_spans(parser, advanced, 'ReplyItemView')
    assert len(calls) == 1
    first, last = calls[0]
    call = advanced[first:last]
    next_line = call.index('\n')+1
    # Use the actual call's argument indentation, retaining the whole owner body.
    arguments = call[next_line:]
    call_indent = arguments[:len(arguments)-len(arguments.lstrip(' '))]
    replacement = once(call, 'ReplyItemView(\n', 'ReplyItemView(\n'+call_indent+
                       'avatarListState = listState.takeIf { avatarPushEnabled },\n'+call_indent+
                       'avatarItemKey = '+key+',\n')
    advanced = advanced[:first]+replacement+advanced[last:]
    after = once(before, owner, advanced)
    save(repo, output, 'v046-'+label+'-avatar-owner-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, actualCommentOwner=owner_name,
        originalAvatarArgumentsVerified=True, actualLazyKeyPrefix=key_prefix,
        sameExistingListState=True, retainedToCurrent=inverse_proof(before, after),
        broaderV046TabletFileNotClaimed=True))
    return after


def advance_landscape_pins(repo, before, output):
    parser = load(repo, 'v046_landscape_pins_syntax', 'sync-upstream.py')
    spans = call_spans(parser, before, 'LandscapeCommentPanel')
    assert len(spans) == 2, 'Actual landscape comment panel callers changed'
    after = before
    for first, last in reversed(spans):
        marker = 'dissolvingIds = commentState.dissolvingIds,'
        advanced = once(after[first:last], marker, marker+' pinnedReplyIds = commentState.pinnedReplyIds,')
        after = after[:first]+advanced+after[last:]
    save(repo, output, 'v046-landscape-comment-pins-proof', dict(upstreamCommit=COMMIT,
        actualLandscapeCallers=2, sameGenericVmPinnedSet=True, retainedToCurrent=inverse_proof(before, after)))
    return after


def advance_phone_comments(repo, before, output):
    parser = load(repo, 'v046_phone_comment_syntax', 'sync-upstream.py')
    spans = call_spans(parser, before, 'VideoContentCommentState')
    assert len(spans) == 1, 'Actual phone projection owner changed'
    first, last = spans[0]
    call = before[first:last]
    marker = 'dissolvingIds = commentState.dissolvingIds,'
    at = call.index(marker)
    indent = call[call.rfind('\n', 0, at)+1:at]
    advanced = once(call, marker, marker+'\n'+indent+'pinnedReplyIds = commentState.pinnedReplyIds,')
    after = before[:first]+advanced+before[last:]
    after = advance_bottom_calls(repo, after, output, 'phone')
    save(repo, output, 'v046-phone-comment-projection-proof', dict(upstreamCommit=COMMIT,
        actualGenericVmPinnedSetForwarded=True, retainedToCurrent=inverse_proof(before, after)))
    return after


def advance_bottom_bar(repo, before, output):
    sources, _ = fixed(repo)
    parser = load(repo, 'v046_bottom_bar_syntax', 'sync-upstream.py')
    old = declaration(parser, before, 'BottomInputBar')
    assert sha(old) == '1ba036e36b284ebdf8e3c385afcac17373b64bece4de8ddee59f24017a6cc558', 'Current Windows bottom bar changed'
    original = sources['BottomInputBar.kt']
    body = once(original, 'import androidx.compose.ui.platform.LocalContext\n', '')
    body = once(body, 'import com.android.purebilibili.core.store.SettingsManager\n',
                'import com.bilipai.desktop.ui.LocalDesktopOriginalHomeSettingsRootServices\nimport com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes\n')
    block = '''    val context = LocalContext.current
    val homeSettings by SettingsManager
        .getHomeSettings(context)
        .collectAsStateWithLifecycle(initialValue = HomeSettings())
    val appNavigationSettings by SettingsManager
        .getAppNavigationSettings(context)
        .collectAsStateWithLifecycle(initialValue = AppNavigationSettings())'''
    body = once(body, block, '''    val settings = LocalDesktopOriginalHomeSettingsRootServices.current.home
    val homeSettings by settings.homeSettings.collectAsStateWithLifecycle()
    val appNavigationSettings by settings.navigation.collectAsStateWithLifecycle()''')
    body = once(body, 'SettingsManager.BottomBarVisibilityMode.SCROLL_HIDE',
                'DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.SCROLL_HIDE')
    assert 'SettingsManager' not in body and 'LocalContext' not in body
    assert body.count('likeStatusKnown = likeStatusKnown,') >= 4, 'Incomplete original known-state chain'
    # Both helpers are byte-identical to the v046 originals and already live in the sole
    # DesktopOriginalDetailInputPadding owner; a second copy is a Kotlin redeclaration.
    selector = load(repo, 'v046_bottom_bar_helper_selector', 'extract-appearance-platform.py')
    for helper in ['shouldUseFloatingLiquidBottomInputBar', 'resolveBottomInputBarContentBottomPadding']:
        selected = selector.declarations(parser, body, [helper]).rstrip()
        assert selected == selector.declarations(parser, original, [helper]).rstrip()
        assert selected.startswith('internal fun '+helper) and selected.count('fun ') == 1, helper
        body = once(body, selected, '')
    save(repo, output, 'v046-shared-bottom-input-bar-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalFile=True,
        originalToWindows=inverse_proof(original, body), retainedToCurrent=inverse_proof(before, body),
        sameRootGlobalSettingsPort=True, ordinaryVideoAndBangumiShareSettings=True,
        unknownLikeStatePreserved=True))
    return body


def braced_statement(parser, text, marker):
    assert text.count(marker) == 1, marker
    first = text.index(marker)
    tokens = parser.kotlin_tokens(text[first:])
    index = next(i for i, t in enumerate(tokens) if t[0] == '{')
    depth = 1
    while depth:
        index += 1
        depth += (tokens[index][0] == '{')-(tokens[index][0] == '}')
    return text[first:first+tokens[index][2]]


def advance_bangumi_ui(repo, before, output, relative):
    if not relative.endswith(('/DesktopOriginalBangumiPlayerContent.kt', '/DesktopOriginalBangumiPlayerScreen.kt')):
        return before
    sources, _ = fixed(repo)
    parser = load(repo, 'v046_pgc_comments_syntax', 'sync-upstream.py')
    after = before
    if relative.endswith('/DesktopOriginalBangumiPlayerContent.kt'):
        assert 'BangumiCommentPanel(' not in before
        after = once(after, 'import androidx.compose.foundation.lazy.LazyColumn\n',
                     'import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.rememberLazyListState\n')
        after = once(after, 'import com.android.purebilibili.feature.video.ui.components.VideoCommentMainList\n', '')
        after = once(after, '    commentViewModel: VideoCommentViewModel,\n',
                     '    commentViewModel: VideoCommentViewModel,\n    isVideoPlaying: Boolean,\n    onTimestampClick: ((Long) -> Unit)?,\n')
        after = once(after, '    val isCourse = detail.seasonType == 10\n', '''    val commentPlatform = com.bilipai.desktop.ui.LocalDesktopCommentBindings.current
    val showIdentityDecorations by remember(commentPlatform) {
        com.android.purebilibili.core.store.DesktopOriginalReplySettings
            .getCommentMemberDecorationsEnabled(commentPlatform.context)
    }.collectAsStateWithLifecycle(initialValue = false)
    val commentListState = key(currentEpisode.id) { rememberLazyListState() }
    val isCourse = detail.seasonType == 10
''')
        first, last = call_spans(parser, after, 'VideoCommentMainList')[0]
        original_key = braced_statement(parser, sources['BangumiPlayerContent.kt'], 'key(currentEpisode.id) {\n                            BangumiCommentPanel(')
        # The source span starts at key, so only continuation indentation changes.
        after = after[:first]+original_key+after[last:]
        assert 'VideoCommentMainList(' not in after and after.count('BangumiCommentPanel(') == 1
        proof = dict(originalEpisodeBranch=original_key, episodeKeyedPanelAndScrollState=True,
                     sameGenericVm=True, realSettingsDecorations=True, realPlaybackAndSeekArgumentsRequired=True)
    else:
        spans = call_spans(parser, after, 'BangumiPlayerContent')
        assert len(spans) == 1, 'PGC actual content owner changed'
        first, last = spans[0]
        call = after[first:last]
        advanced = once(call, 'commentViewModel = commentViewModel,',
                        'commentViewModel = commentViewModel,\n                            isVideoPlaying = playbackDebugSnapshot.isPlaying,\n                            onTimestampClick = ::seekFromComment,')
        after = after[:first]+advanced+after[last:]
        original_seek = declaration(parser, sources['BangumiPlayerScreen.kt'], 'seekFromComment')
        guarded_seek = once(original_seek, '    fun seekFromComment(positionMs: Long) {\n',
                            '    fun seekFromComment(positionMs: Long) {\n        if (!platform.owns() || !exoPlayer.isOwned()) return\n')
        after = once(after, '    fun toggleOrientation() {', guarded_seek+'\n\n    fun toggleOrientation() {')
        proof = dict(reactiveExistingPlayerDebugSnapshot=True, actualNativeSeekAction=True,
                     originalSeekToWindows=inverse_proof(original_seek, guarded_seek), noAdditionalPlayerOrListener=True)
    save(repo, output, 'v046-'+Path(relative).stem+'-comment-caller-proof', dict(
        upstreamCommit=COMMIT, sourceManifestSHA256=MANIFEST_SHA256,
        retainedToCurrent=inverse_proof(before, after), **proof))
    return after


def emit_bangumi_panel(repo, output):
    root = (Path(repo)/ARCHIVE).resolve()
    destination = Path(output).resolve()
    assert not (destination.is_relative_to(root) or root.is_relative_to(destination)), 'Output overlaps retained comments'
    sources, _ = fixed(repo)
    original = sources['BangumiCommentPanel.kt']
    body = once(original, 'import androidx.compose.ui.platform.LocalContext\n',
                'import com.bilipai.desktop.ui.LocalDesktopCommentBindings\n')
    body = once(body, 'import com.android.purebilibili.core.store.SettingsManager\n',
                'import com.android.purebilibili.core.store.DesktopOriginalReplySettings\n')
    body = once(body, '    val context = LocalContext.current',
                '    val platform = LocalDesktopCommentBindings.current\n    val context = platform.context')
    body = once(body, '''        viewModel.setSortMode(mode)
        scope.launch { SettingsManager.setCommentDefaultSortMode(context, mode.apiMode) }''', '''        if (platform.isOwned()) {
            viewModel.setSortMode(mode)
            scope.launch {
                if (platform.isOwned()) DesktopOriginalReplySettings.setCommentDefaultSortMode(context, mode.apiMode)
            }
        }''')
    emitted = {
        'com/android/purebilibili/feature/bangumi/ui/player/DesktopOriginalBangumiCommentPanel.kt': body,
        PACKAGE+'DesktopOriginalVideoCommentFloatingHeader.kt': sources['VideoCommentFloatingHeader.kt'],
    }
    for relative, text in emitted.items():
        path = Path(output)/relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf8', newline='\n')
    save(repo, output, 'v046-bangumi-comment-panel-proof', dict(upstreamCommit=COMMIT,
        sourceManifestSHA256=MANIFEST_SHA256, completeOriginalPanel=True, completeOriginalFloatingHeader=True,
        originalToWindows=inverse_proof(original, body), sameGlobalReplySettings=True,
        sameGenericVmAndSearch=True, emitted=list(emitted)))
    return emitted
