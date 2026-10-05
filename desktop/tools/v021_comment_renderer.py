"""Source-owned Windows comment presentation only.

The complete original bodies remain in the two existing sole producers. This
stage records every counted replacement over their already-adapted full body
and verifies its complete inverse before emission; no duplicate FQCN is emitted.
"""
import hashlib

def sha(text): return hashlib.sha256(text.encode('utf8')).hexdigest()

def inverse(output, edits):
    for edit in reversed(edits):
        offset=edit['offset']; after=edit['after']
        if output[offset:offset+len(after)] != after:
            raise ValueError('Comment presentation inverse mismatch')
        output=output[:offset]+edit['before']+output[offset+len(after):]
    return output

def adapt(body, filename):
    before=body; edits=[]
    def replace(old,new,count=1):
        nonlocal body
        if body.count(old)!=count:
            raise ValueError(f'Comment presentation count for {filename}: {body.count(old)} != {count}: {old[:100]}')
        for _ in range(count):
            offset=body.index(old)
            edits.append(dict(offset=offset,before=old,after=new,count=1))
            body=body[:offset]+new+body[offset+len(old):]
    if filename == 'DesktopOriginalReplyComponents.kt':
        replace('import com.bilipai.desktop.ui.DesktopDynamicTextSelectionSheet as AppModalBottomSheet',
                'import com.bilipai.desktop.ui.DesktopWindowsCommentTextSelectionSheet as AppModalBottomSheet')
        replace('com.android.purebilibili.core.ui.AppAlertDialog(',
                'com.bilipai.desktop.ui.DesktopWindowsCommentAlertDialog(')
    elif filename == 'DesktopOriginalSubReplyDetailComponents.kt':
        replace('com.android.purebilibili.core.ui.AppAlertDialog(',
                'com.bilipai.desktop.ui.DesktopWindowsCommentAlertDialog(')
    elif filename == 'DesktopOriginalReplyReportReasonDialog.kt':
        replace('import com.android.purebilibili.core.ui.*\n',
                'import com.android.purebilibili.core.ui.*\nimport com.bilipai.desktop.ui.DesktopWindowsCommentAlertDialog as AppAlertDialog\n')
    elif filename == 'DesktopOriginalImagePreviewRenderer.kt':
        replace('    val platform: com.bilipai.desktop.ui.DesktopDynamicCardPlatform,\n',
                '    val platform: com.bilipai.desktop.ui.DesktopDynamicCardPlatform,\n    val desktopNativePresentation: com.bilipai.desktop.ui.DesktopWindowsCommentPresentation?,\n')
        replace('        val activeSourceRect = request.activeSourceRect ?: _preparedSourceRect.value',
                """        // Main-window anchors cannot describe a different owned HWND.
        // Reuse the original null-anchor fade/pager path for native comments.
        val activeSourceRect = if (request.desktopNativePresentation == null) request.activeSourceRect ?: _preparedSourceRect.value else null
        if (request.desktopNativePresentation != null) {
            _activeSourceRect.value = null
            _activeSourceKey.value = null
        }""")
        replace('    val latestOnDismiss by rememberUpdatedState(onDismiss)',
                """    val desktopNativePresentation = com.bilipai.desktop.ui.LocalDesktopWindowsCommentPresentation.current
    val latestOnDismiss by rememberUpdatedState(onDismiss)""")
        replace('remember(images, initialIndex, sourceRect, sourceRects, sourceCornerRadiusDp, livePhotoVideos)',
                'remember(images, initialIndex, sourceRect, sourceRects, sourceCornerRadiusDp, livePhotoVideos, desktopNativePresentation)')
        replace('        ImagePreviewOverlayController.show(\n            ImagePreviewOverlayRequest(',
                '        val capturedRequest = ImagePreviewOverlayRequest(')
        replace('                platform = platform,\n',
                '                platform = platform,\n                desktopNativePresentation = desktopNativePresentation,\n')
        replace('                sourceRect = sourceRect,\n                sourceRects = sourceRects,\n                sourceKey = sourceKey,',
                """                sourceRect = sourceRect.takeIf { desktopNativePresentation == null },
                sourceRects = if (desktopNativePresentation == null) sourceRects else emptyMap(),
                sourceKey = sourceKey.takeIf { desktopNativePresentation == null },""")
        replace("""                onDismiss = { latestOnDismiss() }
            )
        )
        onDispose {""",
                """                onDismiss = { latestOnDismiss() }
            )
        if (desktopNativePresentation == null) ImagePreviewOverlayController.show(capturedRequest)
        else if (desktopNativePresentation.canPresentNative()) desktopNativePresentation.dispatch {
            if (desktopNativePresentation.canPresentNative()) ImagePreviewOverlayController.show(capturedRequest)
        }
        onDispose {""")
        replace("""    activeRequest?.let { request ->
        key(request.token) {""",
                """    activeRequest?.let { request ->
        val nativePresentation = request.desktopNativePresentation
        val nativeAlive = nativePresentation?.alive?.collectAsStateWithLifecycle()?.value ?: true
        if (!nativeAlive || nativePresentation?.canPresentNative() == false) {
            // Exact token retirement never dismisses a later or another-page request.
            SideEffect { ImagePreviewOverlayController.dismiss(request.token) }
        } else key(request.token) {""")
        replace("""            Dialog(
                onDismissRequest = {
                    dismissRequestCount++""",
                """            com.bilipai.desktop.ui.DesktopWindowsCommentImagePreviewWindow(
                presentation = request.desktopNativePresentation,
                onDismissRequest = {
                    dismissRequestCount++""")
    else:
        return body, None
    # The sole host.write() emits body.strip()+LF. Include that existing
    # normalization in this stage's indexed inverse rather than hashing a
    # transient pre-write body which is not the actual compilation input.
    leading=body[:len(body)-len(body.lstrip())]
    if leading:
        edits.append(dict(offset=0,before=leading,after='',count=1))
        body=body[len(leading):]
    trailing=body[len(body.rstrip()):]
    if trailing!='\n':
        offset=len(body.rstrip())
        edits.append(dict(offset=offset,before=trailing,after='\n',count=1))
        body=body[:offset]+'\n'
    if inverse(body,edits)!=before:
        raise ValueError('Complete original comment presentation body did not restore')
    return body, dict(stage='windows-comment-native-presentation',filename=filename,
        inputBodySha256=sha(before),outputBodySha256=sha(body),edits=edits,completeInverse=True,
        scope='Container/request lifetime only; original complete UI/algorithms remain in their sole producer')
