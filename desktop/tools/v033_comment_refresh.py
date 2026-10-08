"""Fixed v033 subreply refresh delta in the two existing source-owned producers.
The v029 full source bodies and canonical v025 catalog remain their own epochs.
"""
from pathlib import Path
import hashlib,json,os
COMMIT = '6a95beedce6342219986572620f3b8071b155ef3'
PREVIOUS = '1db8665cb9706dca44fcae0f540f2a3440721089'
ARCHIVE = Path('desktop/upstream-slices/v033-comment-refresh')
MANIFEST_SHA256 = 'ec3ac502e87ee744d7504b8320ebc7ebf11e6685965dbd1f37704f622cd7ffcb'
VM = 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt'
UI = 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/SubReplyDetailComponents.kt'
SELECTED_COMMIT = 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
INPUTS = {'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt': '7fbb7f374ed79d39c1751c59441588c3159bd3540340bd8b15d27987edec22fa', 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/SubReplyDetailComponents.kt': '7b937a3be2194b2e51563a884a18d1d93c2f1eb67335b0b98b81743ff745311b'}
VM_EDITS = [{'name': 'user-refresh-clears-entry-deep-link-target', 'before': '        _subReplyState.value = state.copy(isLoading = true, isRefreshing = true, error = null)\n', 'after': '        _subReplyState.update { current ->\n            current.copy(\n                isLoading = true,\n                isRefreshing = true,\n                error = null,\n                // A user refresh starts a normal first page, not another deep-link lookup.\n                targetReplyId = 0L,\n            )\n        }\n', 'count': 1}]
UI_EDITS = [{'name': 'entry-target-consumption-and-independent-highlight-timeout', 'before': '    var highlightedTargetId by remember(rootReply.rpid) { mutableLongStateOf(0L) }\n', 'after': '    var highlightedTargetId by remember(rootReply.rpid) { mutableLongStateOf(0L) }\n    var targetReplyHandled by remember(rootReply.rpid, targetReplyId) { mutableStateOf(false) }\n    LaunchedEffect(highlightedTargetId) {\n        if (highlightedTargetId <= 0L) return@LaunchedEffect\n        delay(1_400)\n        highlightedTargetId = 0L\n    }\n', 'count': 1}, {'name': 'actual-root-and-conversation-effect-keys', 'before': '    LaunchedEffect(targetReplyId, visibleReplies, isLoading, isEnd) {\n', 'after': '    LaunchedEffect(rootReply.rpid, targetReplyId, visibleReplies, isLoading, isEnd, effectiveConversationMode) {\n', 'count': 1}, {'name': 'do-not-replay-consumed-entry-target', 'before': '            highlightedTargetId = 0L\n            return@LaunchedEffect\n        }\n        val targetIndex = resolveSubReplyTargetListIndex(\n', 'after': '            highlightedTargetId = 0L\n            return@LaunchedEffect\n        }\n        // This is an entry-time navigation request, not a persistent scroll anchor.\n        // Loading/refreshing changes the effect keys but must not replay the jump.\n        if (targetReplyHandled) return@LaunchedEffect\n        val targetIndex = resolveSubReplyTargetListIndex(\n', 'count': 1}, {'name': 'consume-target-before-cancellable-scroll-animation', 'before': '                listState.animateScrollToItem(targetIndex)\n                highlightedTargetId = targetReplyId\n                delay(1_400)\n                highlightedTargetId = 0L\n', 'after': '                // Consume before suspending: a page update or user scroll may cancel\n                // the animation, and must not cause a later update to restart it.\n                targetReplyHandled = true\n                highlightedTargetId = targetReplyId\n                listState.animateScrollToItem(targetIndex)\n', 'count': 1}]
def digest(b):return hashlib.sha256(b).hexdigest()
def raw(path):
    value=os.path.abspath(path)
    return Path('\\\\?\\'+value if os.name=='nt' and not value.startswith('\\\\?\\') else value).read_bytes()
def fixed_sources(repo):
    m=raw(repo/ARCHIVE/'manifest.json')
    if digest(m)!=MANIFEST_SHA256:raise ValueError('v033 comment refresh manifest changed')
    manifest=json.loads(m)
    if manifest.get('schemaVersion')!=1 or manifest.get('fixedUpstreamCommit')!=COMMIT or manifest.get('previousUpstreamCommit')!=PREVIOUS:raise ValueError('v033 comment refresh epoch changed')
    result={}
    for row in manifest['files']:
        b=raw(repo/ARCHIVE/row['archiveFile'])
        blob=hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
        if len(b)!=row['bytes'] or digest(b)!=row['sha256Bytes'] or blob!=row['gitBlob']:raise ValueError('v033 comment refresh whole raw changed')
        result[(row['originalPath'],row['commit'])]=b.decode('utf8')
    if set(result)!={(VM,PREVIOUS),(VM,COMMIT),(UI,PREVIOUS),(UI,COMMIT)}:raise ValueError('v033 comment refresh source closure changed')
    return result
def apply_selected(repo,path,body):
    if path not in INPUTS or digest(body.encode('utf8'))!=INPUTS[path]:raise ValueError('Unknown original v029 comment selection')
    fixed=fixed_sources(repo);edits=VM_EDITS if path==VM else UI_EDITS
    def advance(text):
        source=text;trace=[]
        for edit in edits:
            before,after=edit['before'],edit['after']
            if text.count(before)!=edit['count']:raise ValueError('v033 comment refresh literal seam changed: '+edit['name'])
            at=text.index(before);trace.append((at,before,after));text=text[:at]+after+text[at+len(before):]
        inverse=text
        for at,before,after in reversed(trace):
            if inverse[at:at+len(after)]!=after:raise ValueError('v033 comment refresh inverse mismatch')
            inverse=inverse[:at]+before+inverse[at+len(after):]
        if inverse!=source:raise ValueError('v033 comment refresh whole inverse mismatch')
        return text
    if advance(fixed[(path,PREVIOUS)])!=fixed[(path,COMMIT)]:raise ValueError('v033 comment refresh delta differs from fixed complete original')
    result=advance(body)
    return result,dict(fixedUpstreamCommit=COMMIT,previousUpstreamCommit=PREVIOUS,selectedFullSourceCommit=SELECTED_COMMIT,originalPath=path,inputSHA256LF=digest(body.encode('utf8')),outputSHA256LF=digest(result.encode('utf8')),exactCompleteSelectedSourceInverse=True,partialV033BusinessDelta=True,canonicalEpochAdvanced=False,countedOriginalAdaptations=edits)
