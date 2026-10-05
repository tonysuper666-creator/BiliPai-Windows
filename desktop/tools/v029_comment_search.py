"""One fixed v029 comment-search/charged source slice; canonical v025 remains pinned."""
from pathlib import Path
import hashlib, json, os, difflib
COMMIT = 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
ARCHIVE = Path('desktop/upstream-slices/v029-comment-search')
BASE = 'app/src/main/java/com/android/purebilibili/'
VM = BASE+'feature/video/viewmodel/VideoCommentViewModel.kt'
SEARCH = BASE+'feature/video/ui/components/CommentSearchSheet.kt'
REPLY = BASE+'feature/video/ui/components/ReplyComponents.kt'
GRPC = BASE+'data/repository/CommentGrpcRepository.kt'
MODEL = 'core-data/src/main/java/com/android/purebilibili/data/model/response/ResponseModels.kt'
CANONICAL_MODEL_SHA = 'fed6db98e75157fdfef5f9de3916a690808c506c10656747f90b504a1e95221f'
PINS = {'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentSearchSheet.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentSearchSheet.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'bytes': 30902, 'sha256Bytes': '29fc38e81f8a7d0593ae120770f6c98f20e76d0136e7768743e7adbe73b6afff', 'gitBlob': 'a919e0cf1b31aa1af693c0af88e1f57319aa6d71', 'upstreamUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentSearchSheet.kt'}, 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'bytes': 61124, 'sha256Bytes': '7fbb7f374ed79d39c1751c59441588c3159bd3540340bd8b15d27987edec22fa', 'gitBlob': 'b691cb61687329d8f5aebaff3a19da5490520417', 'upstreamUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoCommentViewModel.kt'}, 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyComponents.kt': {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyComponents.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'bytes': 132262, 'sha256Bytes': 'edba82de5f1e9726c104f3ec38d003cc566e72d99b10afcbd57d9a7c3f7848e9', 'gitBlob': 'ebce1b2b777967c0d53d761be2adf9c0a7ce6ff1', 'upstreamUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/feature/video/ui/components/ReplyComponents.kt'}, 'core-data/src/main/java/com/android/purebilibili/data/model/response/ResponseModels.kt': {'path': 'core-data/src/main/java/com/android/purebilibili/data/model/response/ResponseModels.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'bytes': 23622, 'sha256Bytes': '4b15b412ca09d075b598a752af2e79412bfaef0f57c638de197cbc311304f606', 'gitBlob': '02c34879a7ea0d1ab0430894b266afb82fdb4216', 'upstreamUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/core-data/src/main/java/com/android/purebilibili/data/model/response/ResponseModels.kt'}, 'app/src/main/java/com/android/purebilibili/data/repository/CommentGrpcRepository.kt': {'path': 'app/src/main/java/com/android/purebilibili/data/repository/CommentGrpcRepository.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'bytes': 30152, 'sha256Bytes': 'd8a63c16adf2c0a7ac9a96bf2d8d2a5101b9a087017b44fab13fbef36177f02f', 'gitBlob': '1964d34ea6509f070400775be53fda3e8632d555', 'upstreamUrl': 'https://raw.githubusercontent.com/jay3-yy/BiliPai/a4b77f894d0a2dd26c0b9fc144b8adb88ac05480/app/src/main/java/com/android/purebilibili/data/repository/CommentGrpcRepository.kt'}}
MANIFEST_SHA = 'a604a18026b8fa2f38eb0761ab2842b5792fab5f3d565966be0cddc64ef410cb'

def raw(path):
    value=os.path.abspath(path)
    return Path('\\\\?\\'+value if os.name=='nt' and not value.startswith('\\\\?\\') else value).read_bytes()
def digest(value): return hashlib.sha256(value).hexdigest()
def fixed_sources(repo):
    data=raw(repo/ARCHIVE/'manifest.json')
    if digest(data)!=MANIFEST_SHA: raise ValueError('Fixed comment-search manifest changed')
    manifest=json.loads(data)
    if manifest.get('fixedUpstreamCommit')!=COMMIT or manifest.get('hashNormalization')!='raw':
        raise ValueError('Unknown comment-search source contract')
    if len(manifest['files'])!=len(PINS): raise ValueError('Incomplete comment-search source set')
    result={}
    for row in manifest['files']:
        path=row['path']
        if path not in PINS or row!=PINS[path]: raise ValueError('Unknown comment-search source identity')
        b=raw(repo/ARCHIVE/path)
        if len(b)!=row['bytes'] or digest(b)!=row['sha256Bytes'] or hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()!=row['gitBlob']:
            raise ValueError('Fixed comment-search raw byte/blob mismatch: '+path)
        result[path]=b.decode('utf8')
    return result

def replace(text,before,after,label,edits,count=1):
    if text.count(before)!=count: raise ValueError('Counted comment adaptation: '+label)
    edits.append(dict(label=label,before=before,after=after,count=count))
    return text.replace(before,after,count)
def reverse(text,edits):
    for e in reversed(edits):
        if text.count(e['after'])!=e['count']: raise ValueError('Comment inverse count: '+e['label'])
        text=text.replace(e['after'],e['before'],e['count'])
    return text
def proof(before,after,edits):
    if reverse(after,edits)!=before: raise ValueError('Comment full-body inverse mismatch')
    return dict(beforeSha256LF=digest(before.encode()),afterSha256LF=digest(after.encode()),
                exactCompleteInverse=True,countedAdaptations=edits)
def select_full(repo,path,canonical):
    newest=fixed_sources(repo)[path]
    return newest,dict(fixedUpstreamCommit=COMMIT,path=path,canonicalSha256LF=digest(canonical.encode()),
                      selectedRawSha256=PINS[path]['sha256Bytes'],canonicalBefore=canonical,
                      completeOriginalSelected=True)

def whole_proof(before,after):
    edits=[]
    old=before.splitlines(keepends=True);new=after.splitlines(keepends=True)
    oldOffsets=[0];newOffsets=[0]
    for line in old: oldOffsets.append(oldOffsets[-1]+len(line))
    for line in new: newOffsets.append(newOffsets[-1]+len(line))
    for op,a,b,c,d in difflib.SequenceMatcher(None,old,new,autojunk=False).get_opcodes():
        if op!='equal': edits.append(dict(beforeOffset=oldOffsets[a],afterOffset=newOffsets[c],
            before=before[oldOffsets[a]:oldOffsets[b]],after=after[newOffsets[c]:newOffsets[d]]))
    forward=before
    for edit in reversed(edits):
        pos=edit['beforeOffset'];old=edit['before']
        if forward[pos:pos+len(old)]!=old: raise ValueError('Whole comment forward index mismatch')
        forward=forward[:pos]+edit['after']+forward[pos+len(old):]
    inverse=after
    for edit in reversed(edits):
        pos=edit['afterOffset'];old=edit['after']
        if inverse[pos:pos+len(old)]!=old: raise ValueError('Whole comment inverse index mismatch')
        inverse=inverse[:pos]+edit['before']+inverse[pos+len(old):]
    if forward!=after or inverse!=before: raise ValueError('Whole comment source was not restored')
    return dict(beforeSha256LF=digest(before.encode()),afterSha256LF=digest(after.encode()),
                exactCompleteInverse=True,indexedEdits=edits)

def save_proof(output,name,path,canonical,generated,selection=None):
    target=output/(name+'.json');target.parent.mkdir(parents=True,exist_ok=True)
    row=dict(source=path,fixedUpstreamCommit=COMMIT,raw=PINS[path],
             canonicalToGenerated=whole_proof(canonical,generated))
    if selection is not None: row['selection']=selection
    target.write_text(json.dumps(row,ensure_ascii=False,indent=2)+'\n',encoding='utf8',newline='\n')

def vm_delta(body):
    before=body;edits=[]
    body=replace(body,'import com.android.purebilibili.data.repository.CommentGrpcRepository',
        'import com.android.purebilibili.data.repository.DesktopDynamicCommentGrpc as CommentGrpcRepository',
        'existing owned gRPC mode identity',edits)
    body=replace(body,'    private var fullSearchJob: Job? = null',
        '    private var fullSearchJob: Job? = null\n    private var fullSearchRequest: Any? = null\n    private var fullSearchSourceLease: Any? = null',
        'UI request identity only; actual source permission stays in caller',edits)
    body=replace(body,'    fun loadAllCommentsForSearch() {\n        val state = _fullSearchState.value\n        if (state.isLoading || state.isReady) return',
        '''    fun loadAllCommentsForSearch(
        sourceLease: Any = requests,
        stillSearchOwned: () -> Boolean = requests::isOwned,
        admitSearch: ((() -> Unit) -> Boolean) = { action ->
            if (requests.isOwned()) { action(); true } else false
        },
    ): Any? {
        if (!requests.isOwned() || !stillSearchOwned()) return null
        val state = _fullSearchState.value
        if (fullSearchSourceLease === sourceLease && (state.isLoading || state.isReady)) return fullSearchRequest''',
        'captured caller source lease; reuse only exact same presentation',edits)
    body=replace(body,'        if (!subject.isValid) return\n        fullSearchJob?.cancel()\n        _fullSearchState.value = FullCommentSearchUiState(isLoading = true)\n        fullSearchJob = launchOwned {',
        '''        if (!subject.isValid) return null
        val ticket = Any()
        if (!admitSearch {
                if (!requests.isOwned() || !stillSearchOwned()) throw CancellationException("Comment search source retired")
                if (fullSearchSourceLease !== sourceLease) resetFullCommentSearch()
                fullSearchJob?.cancel()
                fullSearchSourceLease = sourceLease
                fullSearchRequest = ticket
                _fullSearchState.value = FullCommentSearchUiState(isLoading = true)
            }) return null
        val launched = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val caller = currentCoroutineContext()[Job]!!
            fun publish(action: () -> Unit) {
                currentCoroutineContextCheck(caller)
                if (!admitSearch {
                        currentCoroutineContextCheck(caller)
                        if (fullSearchRequest !== ticket || fullSearchSourceLease !== sourceLease ||
                            !requests.isOwned() || !stillSearchOwned() ||
                            !shouldApplyCommentSubjectResult(subject, currentSubject))
                            throw CancellationException("Comment search request retired")
                        action()
                    }) throw CancellationException("Comment search publication retired")
            }
            try {
            ensureRequestOwned()
            publish { Unit }''',
        'link lazy actual job before IO and final owner/subject admission',edits)
    body=replace(body,'                ).getOrNull()\n                if (data == null) {',
        '''                ).also { result ->
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                }.getOrNull()
                ensureRequestOwned()
                publish { Unit }
                if (data == null) {''',
        'result cancellation propagates and each await retires old request',edits)
    body=replace(body,'            while (isActive) {\n                val data = requests.getCommentsForSubject(',
        '            while (isActive) {\n                publish { Unit }\n                val data = requests.getCommentsForSubject(',
        'no successor-page IO after source or request retirement',edits)
    body=replace(body,'                _fullSearchState.value = _fullSearchState.value.copy(\n                    loadedCount = collected.size,\n                    totalCount = totalCount\n                )',
        '''                publish {
                    _fullSearchState.value = _fullSearchState.value.copy(
                        loadedCount = collected.size,
                        totalCount = totalCount
                    )
                }''', 'original progress only at final same-source gate',edits)
    body=replace(body,'            val deduped = collected.distinctBy { it.rpid }\n            _fullSearchReplies.value = deduped',
        '            val deduped = collected.distinctBy { it.rpid }\n            publish {\n            _fullSearchReplies.value = deduped',
        'original dedupe/final state grouped publication',edits)
    body=replace(body,'''                error = failure
            )
        }
    }

    fun resetFullCommentSearch() {
        fullSearchJob?.cancel()''',
        '''                error = failure
            )
            }
            } finally {
                // Only this request may release its busy state; cleanup does not
                // turn cancellation into an error or clear a successor's data.
                if (fullSearchRequest === ticket && fullSearchSourceLease === sourceLease &&
                    requests.isOwned() && stillSearchOwned()) admitSearch {
                    if (fullSearchRequest === ticket && fullSearchSourceLease === sourceLease &&
                        requests.isOwned() && stillSearchOwned())
                        _fullSearchState.value = _fullSearchState.value.copy(isLoading = false)
                }
            }
        }
        fullSearchJob = launched
        launched.start()
        return ticket
    }

    private fun currentCoroutineContextCheck(caller: Job) {
        caller.ensureActive()
    }

    fun cancelFullCommentSearch(expectedRequest: Any?, expectedSourceLease: Any): Boolean {
        if (expectedRequest == null || fullSearchRequest !== expectedRequest ||
            fullSearchSourceLease !== expectedSourceLease) return false
        fullSearchRequest = null
        fullSearchJob?.cancel()
        fullSearchJob = null
        _fullSearchState.value = _fullSearchState.value.copy(isLoading = false)
        return true
    }

    fun resetFullCommentSearch() {
        fullSearchRequest = null
        fullSearchSourceLease = null
        fullSearchJob?.cancel()''', 'exact-request close/cleanup; original reset algorithm retained',edits)
    return body,proof(before,body,edits)

def charged_delta(repo,body):
    newest=fixed_sources(repo)[REPLY];before=body;edits=[]
    def between(text,start,end):
        a=text.index(start);return text[a:text.index(end,a)]
    body=replace(body,'private const val COMMENT_INLINE_UP_BADGE_ID = "comment_inline_up_badge"',
        'private const val COMMENT_INLINE_CHARGED_BADGE_ID = "comment_inline_charged_badge"\nprivate const val COMMENT_INLINE_UP_BADGE_ID = "comment_inline_up_badge"',
        'original inline charged identity',edits)
    old=between(body,'    val contentPrefix = remember(showTopBadge)', '    val specialLabelText')
    new=between(newest,'    // 充电专属评论：优先取服务端 charged_desc','    val specialLabelText')
    body=replace(body,old,new,'complete original charged prefix consumer',edits)
    body=replace(body,'    val topBadgeInlineContent = rememberInlineTopBadgeContent()\n',
        '    val topBadgeInlineContent = rememberInlineTopBadgeContent()\n    val chargedBadgeInlineContent = rememberInlineChargedBadgeContent()\n',
        'both original rich-text inline producers',edits,2)
    body=replace(body,'        topBadgeInlineContent,\n','        topBadgeInlineContent,\n        chargedBadgeInlineContent,\n',
        'original inline cache keys',edits,2)
    body=replace(body,'            COMMENT_INLINE_TOP_BADGE_ID to topBadgeInlineContent,',
        '            COMMENT_INLINE_TOP_BADGE_ID to topBadgeInlineContent,\n            COMMENT_INLINE_CHARGED_BADGE_ID to chargedBadgeInlineContent,',
        'original reference map',edits)
    body=replace(body,'            put(COMMENT_INLINE_TOP_BADGE_ID, topBadgeInlineContent)',
        '            put(COMMENT_INLINE_TOP_BADGE_ID, topBadgeInlineContent)\n            put(COMMENT_INLINE_CHARGED_BADGE_ID, chargedBadgeInlineContent)',
        'original full rich text map',edits)
    new=between(newest,'// 充电评论徽标（仿官方样式）','private const val COMMENT_PICTURE_MAX_RETRIES')
    body=replace(body,'private const val COMMENT_PICTURE_MAX_RETRIES',new+'private const val COMMENT_PICTURE_MAX_RETRIES',
        'complete original tag/inline/resolver bodies',edits)
    return body,proof(before,body,edits)

def emit_models(repo,output):
    from v025_source_paths import canonical_source
    before=canonical_source(repo,MODEL).read_text(encoding='utf8').replace('\r\n','\n')
    if digest(before.encode())!=CANONICAL_MODEL_SHA:
        raise ValueError('Canonical v025 ResponseModels pin changed')
    newest=fixed_sources(repo)[MODEL]
    # Fixed v029 changes only the complete final ReplyControl declaration.
    marker='@Serializable\ndata class ReplyControl('
    a=before.index(marker);b=newest.index(marker)
    edits=[];after=replace(before,before[a:],newest[b:],'whole fixed original ReplyControl including charged_desc',edits)
    target=output/'com/android/purebilibili/data/model/response/ResponseModels.kt'
    target.parent.mkdir(parents=True,exist_ok=True);target.write_text(after,encoding='utf8',newline='\n')
    (output/'v029-comment-model-source-proof.json').write_text(json.dumps(proof(before,after,edits),ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    return target
