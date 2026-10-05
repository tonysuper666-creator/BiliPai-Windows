"""Full fixed original comment composer on the existing sole Composer domain.

No additional VM, account, draft persistence or request owner is constructed.
The legacy selected-body producer is reused, then the UI-only dialog stamp is
adapted reversibly. Already authorized sends retain the original typed-AID port.
"""
from pathlib import Path
import hashlib, importlib.util, json, os, re
from v029_comment_search import whole_proof

COMMIT = 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480'
ARCHIVE = Path('desktop/upstream-slices/v029-comment-composer')
BASE = 'app/src/main/java/com/android/purebilibili/'
VM = BASE+'feature/video/viewmodel/VideoPlaybackViewModel.kt'
CORE = BASE+'feature/video/viewmodel/VideoComposerViewModel.kt'
INPUT = BASE+'feature/video/screen/VideoDetailInputOverlayAdapter.kt'
DIALOG = BASE+'feature/video/ui/components/CommentInputDialog.kt'

def wide(p):
    value=os.path.abspath(p)
    return Path('\\\\?\\'+value if os.name=='nt' and not value.startswith('\\\\?\\') else value)
def sha(b): return hashlib.sha256(b).hexdigest()
def load_module(path, name):
    spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
def fixed(repo):
    manifest_bytes=wide(repo/ARCHIVE/'manifest.json').read_bytes()
    if sha(manifest_bytes)!=MANIFEST_SHA: raise ValueError('Fixed comment-composer manifest changed')
    manifest=json.loads(manifest_bytes)
    if manifest['upstreamCommit']!=COMMIT or manifest['files']!=PINS: raise ValueError('Unknown comment-composer source set')
    result={}
    for row in PINS:
        raw=wide(repo/ARCHIVE/row['path']).read_bytes()
        if len(raw)!=row['bytes'] or sha(raw)!=row['rawSha256'] or hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()!=row['gitBlob']:
            raise ValueError('Fixed comment-composer raw byte/blob mismatch: '+row['path'])
        result[row['path']]=raw.decode('utf8')
    return result

def replace(body,before,after):
    if body.count(before)!=1: raise ValueError('Counted composer adaptation mismatch: '+before[:100])
    return body.replace(before,after,1)
def function(shared,text,name):
    mask=shared.masked(text)
    found=list(re.finditer(r'(?m)^[ \t]*(?:(?:internal|private|public|override|inline|suspend)\s+)*fun\s+'+re.escape(name)+r'\s*\(',mask))
    if len(found)!=1: raise ValueError('Original composer function selection: '+name)
    begin=found[0].start();end_params=shared.balanced(mask,mask.index('(',begin));opening=mask.index('{',end_params)
    return text[begin:shared.balanced(mask,opening,'{','}')]
def save(output,name,row):
    target=wide(output/(name+'.json'));target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(row,ensure_ascii=True,indent=2)+'\n',encoding='utf8',newline='\n')

def domain_delta(repo, output, path, body):
    if path!='com/android/purebilibili/feature/video/viewmodel/VideoComposerViewModel.kt': return body
    sources=fixed(repo)
    producer=load_module(Path(__file__).with_name('extract-upstream-video-comment-ui.py'),'existing_full_comment_composer')
    shared=load_module(Path(__file__).with_name('extract-upstream-dynamic-reply-protocol.py'),'composer_original_lexer')
    emitted=[]
    producer.generate_composer({producer.PLAYBACK:sources[VM]},shared,lambda p,t,*args:emitted.append(t),lambda t,n:function(shared,t,n))
    legacy=emitted[0]
    start=legacy.index('    private val _commentInput =')
    end=legacy.index('\n}\n\ndata class CommentMentionSearchUiState',start)
    members=legacy[start:end]
    original_members=members
    # Required ports borrow the same DomainOwners request view and Root Ops.
    members=members.replace('requests.', 'environment.commentRequests.')
    members=members.replace('info()', 'environment.commentInfo()')
    members=members.replace('loadEmotePackages()', 'environment.loadCommentEmotePackages()')
    members=members.replace('searchMentionUsers(query)', 'environment.searchCommentMentionUsers(query)')
    members=members.replace('launchOwned', 'launchCommentOwned').replace('ensureOwned()', 'ensureCommentOwned()').replace('toast(', 'commentToast(')
    for name in ['showCommentInputDialog','openRootCommentComposer','hideCommentInputDialog','updateCommentDraft','setCommentInput','setReplyingTo','clearReplyingTo','searchCommentMentionUsers','clearCommentMentionSearch','sendComment']:
        members=replace(members,'    fun '+name+'(', '    private fun '+name+'(')
    members=replace(members,'        launchCommentOwned {\n            environment.loadCommentEmotePackages()',
        '''        val stamp = _commentStamp.value ?: return
        commentEmoteJob = launchCommentOwned {
            environment.loadCommentEmotePackages()''')
    members=replace(members,'''                    ensureCommentOwned() 
                    _emotePackages.value = it 
                    isEmotesLoaded = true''', '''                    ensureCommentOwned()
                    publishCommentUi(stamp) {
                        _emotePackages.value = it
                        isEmotesLoaded = true
                    }''')
    members=replace(members,'''                    ensureCommentOwned()  }''', '''                    if (it is CancellationException) throw it
                    ensureCommentOwned()
                }''')
    members=replace(members,'        commentMentionSearchJob = launchCommentOwned {','        val stamp = _commentStamp.value ?: return\n        commentMentionSearchJob = launchCommentOwned {')
    members=replace(members,'''                .onSuccess { users ->
                    ensureCommentOwned()
                    _commentMentionSearchState.update {''', '''                .onSuccess { users ->
                    ensureCommentOwned()
                    publishCommentUi(stamp) {
                    _commentMentionSearchState.update {''')
    members=replace(members,'''                    }
                }
                .onFailure { error ->
                    ensureCommentOwned()
                    _commentMentionSearchState.update {''', '''                    }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    ensureCommentOwned()
                    publishCommentUi(stamp) {
                    _commentMentionSearchState.update {''')
    members=replace(members,'''                    }
                }
        }
    }

    private fun clearCommentMentionSearch()''', '''                    }
                    }
                }
        }
    }

    private fun clearCommentMentionSearch()''')
    members=replace(members,'''        val submission = ++submissionSequence
        _isSendingComment.value = true''', '''        val stamp = _commentStamp.value ?: return
        val sentDraftKey = commentComposerDraftKey(replyTo?.rpid)
        val sentDraft = _composerDrafts.value.comments[sentDraftKey]
        val capturedImages = imageUris.toList()
        val submission = ++commentSubmissionSequence
        _isSendingComment.value = true''')
    members=replace(members,'uploadCommentPictures(imageUris)','uploadCommentPictures(capturedImages)')
    members=replace(members,'''                commentToast(uploadError.message ?: "图片上传失败")''', '''                if (uploadError is CancellationException) throw uploadError
                commentToast(uploadError.message ?: "图片上传失败")''')
    members=replace(members,'''                    _commentInput.value = ""
                    val sentDraftKey = commentComposerDraftKey(replyTo?.rpid)
                    _composerDrafts.update { state ->
                        state.copy(comments = state.comments - sentDraftKey)
                    }
                    _replyingToComment.value = null
                    _showCommentDialog.value = false
                    clearCommentMentionSearch()''', '''                    // A dismissed A send may complete while B is being edited.
                    // Preserve the original mutation/receipt, retire only A's UI.
                    dispatchCommentUi(stamp) {
                        if (_composerDrafts.value.comments[sentDraftKey] == sentDraft) {
                            _commentInput.value = ""
                            _composerDrafts.update { state -> state.copy(comments = state.comments - sentDraftKey) }
                            hideCommentInputDialog()
                            _commentStamp.value = null
                        }
                    }''')
    members=replace(members,'_commentSentEvent.trySend(reply)','_commentSentEvent.trySend(com.bilipai.desktop.ui.DesktopOriginalVideoCommentSentReceipt(sendAid, reply))')
    members=replace(members,'''                .onFailure { error ->
                    ensureCommentOwned()
                    
                    commentToast(error.message ?: "发送失败")''', '''                .onFailure { error ->
                    if (error is CancellationException) throw error
                    ensureCommentOwned()
                    commentToast(error.message ?: "发送失败")''')
    members=replace(members,'submissionSequence == submission','commentSubmissionSequence == submission')
    members=replace(members,'''            if (failure is CancellationException && environment.commentRequests.isOwned() && commentSubmissionSequence == submission) {
                _isSendingComment.value = false
            }''', '''            if (failure is CancellationException) environment.commit {
                if (commentSubmissionSequence == submission) _isSendingComment.value = false
            }''')
    members=replace(members,'Channel<com.android.purebilibili.data.model.response.ReplyItem?>(', 'Channel<com.bilipai.desktop.ui.DesktopOriginalVideoCommentSentReceipt>(')
    additions='''
    // One UI presentation stamp, not a second source/account authority.
    private var commentSubmissionSequence = 0L
    private var commentEmoteJob: kotlinx.coroutines.Job? = null
    private val _commentStamp = MutableStateFlow<com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp?>(null)
    val commentStamp = _commentStamp.asStateFlow()
    private suspend fun ensureCommentOwned() {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        environment.assertCurrent()
        if (!environment.commentRequests.isOwned()) throw kotlinx.coroutines.CancellationException("Comment domain retired")
    }
    private fun launchCommentOwned(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): kotlinx.coroutines.Job =
        environment.scope.launch { ensureCommentOwned(); block() }
    private fun commentToast(message: String) { if (environment.isCurrent()) environment.commentFeedback(message) }
    private fun dispatchCommentUi(stamp: com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp, action: () -> Unit): Boolean {
        var applied = false
        val admitted = stamp.presentation.dispatch {
            if (_commentStamp.value === stamp && _uiState.value.subject == stamp.subject && environment.isCurrent()) {
                action(); applied = true
            }
        }
        return admitted && applied
    }
    private fun publishCommentUi(stamp: com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp, action: () -> Unit) {
        if (!dispatchCommentUi(stamp, action)) throw kotlinx.coroutines.CancellationException("Comment composer presentation retired")
    }
    fun openCommentComposer(presentation: com.bilipai.desktop.ui.DesktopWindowsCommentPresentation,
        reply: com.android.purebilibili.data.model.response.ReplyItem? = null): com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp? {
        var result: com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp? = null
        presentation.dispatch {
            environment.assertCurrent()
            val subject = _uiState.value.subject ?: return@dispatch
            val info = environment.commentInfo() ?: return@dispatch
            if (subject.aid <= 0L || info.aid != subject.aid || info.bvid != subject.bvid || info.cid != subject.cid) return@dispatch
            retireCommentUi()
            val stamp = com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp(presentation, subject)
            _commentStamp.value = stamp
            setReplyingTo(reply)
            showCommentInputDialog()
            result = stamp
        }
        return result
    }
    fun dismissCommentComposer(stamp: com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp): Boolean =
        dispatchCommentUi(stamp) { hideCommentInputDialog(); _commentStamp.value = null }
    fun changeCommentDraft(stamp: com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp, text: String, images: List<String>, sync: Boolean): Boolean =
        dispatchCommentUi(stamp) { updateCommentDraft(text, images.toList(), sync) }
    fun searchCommentMentions(stamp: com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp, query: String): Boolean =
        dispatchCommentUi(stamp) {
            searchCommentMentionUsers(query)
            val pending = commentMentionSearchJob
            pending?.invokeOnCompletion { failure ->
                if (failure is kotlinx.coroutines.CancellationException) dispatchCommentUi(stamp) {
                    if (commentMentionSearchJob === pending && _commentMentionSearchState.value.query == query)
                        _commentMentionSearchState.value = _commentMentionSearchState.value.copy(isLoading = false)
                }
            }
        }
    fun submitComment(stamp: com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp, text: String, images: List<String>, sync: Boolean): Boolean =
        dispatchCommentUi(stamp) { sendComment(text, images.toList(), sync, stamp.subject.aid) }
    private fun retireCommentUi() {
        commentEmoteJob?.cancel(); commentEmoteJob = null
        hideCommentInputDialog()
        _commentStamp.value = null
    }
    fun retireCommentPresentation(presentation: com.bilipai.desktop.ui.DesktopWindowsCommentPresentation) {
        if (_commentStamp.value?.presentation === presentation) {
            commentEmoteJob?.cancel(); commentMentionSearchJob?.cancel()
        }
        environment.commit { if (_commentStamp.value?.presentation === presentation) retireCommentUi() }
    }
'''
    before=body
    body=replace(body,'import com.bilipai.desktop.ui.DesktopOriginalVideoComposerEnvironment','import com.bilipai.desktop.ui.DesktopOriginalVideoComposerEnvironment\nimport com.android.purebilibili.data.model.response.*\nimport kotlinx.coroutines.*\nimport kotlinx.coroutines.flow.update')
    body=replace(body,'        mentionSearchJob?.cancel()\n        _uiState.value = VideoComposerUiState(subject = subject)','        mentionSearchJob?.cancel()\n        retireCommentUi()\n        _uiState.value = VideoComposerUiState(subject = subject)')
    body=replace(body,'        _uiState.value = _uiState.value.copy(commentDraft = text)','''        _uiState.value = _uiState.value.copy(commentDraft = text)
        ensureComposerDraftVideo()
        val draft = _composerDrafts.value.comments[commentComposerDraftKey(_replyingToComment.value?.rpid)] ?: CommentComposerDraft()
        updateCommentDraft(text, draft.imageUris, draft.syncToDynamic)''')
    body=replace(body,'        mentionSearchJob?.cancel()\n        _events.close()','        mentionSearchJob?.cancel()\n        commentEmoteJob?.cancel()\n        commentMentionSearchJob?.cancel()\n        _commentSentEvent.close()\n        _events.close()')
    pos=body.rfind('\n}')
    body=body[:pos]+additions+'\n'+members+body[pos:]
    # Audit the real selection, including upload, mention and send algorithms.
    # Ranges refer to the full immutable raw VM; unused playback code is not
    # copied into another lifecycle/transport owner.
    raw=sources[VM];ranges=[]
    a=raw.index('    private val _showCommentDialog =');b=raw.index('    // 表情包数据',a)
    ranges.append((a,b))
    a=raw.index('    private val _emotePackages =',b);b=raw.index('    // ========== 弹幕发送',a)
    ranges.append((a,b))
    draft=function(shared,raw,'updateCommentDraft');a=raw.index(draft);ranges.append((a,a+len(draft)))
    a=raw.index('    private val _isSendingComment =');b=raw.index('\n',raw.index('    val commentSentEvent =',a));ranges.append((a,b))
    selected=''.join(raw[a:b] for a,b in ranges)
    save(output,'v029-domain-comment-composer-source',dict(upstreamCommit=COMMIT,rawSources=PINS,
        selectedRawRanges=[dict(start=a,end=b,sha256=sha(raw[a:b].encode())) for a,b in ranges],
        selectedRawToLegacyBody=whole_proof(selected,legacy),
        selectedLegacyCompleteBodySha256=sha(legacy.encode()),legacyMemberAdaptation=whole_proof(original_members,members),
        canonicalDomainToGenerated=whole_proof(before,body),legacyComposerProducerReused=True))
    return body

def overlay_delta(repo,output,body):
    before=body;fixed(repo)
    begin=body.index('@Composable internal fun DesktopOriginalVideoCommentInputOverlay(')
    end=body.index('\n@Composable\nprivate fun VideoDetailCommentInputOverlayContent',begin)
    owner=body[begin:end]
    overload=owner.replace('composer: DesktopOriginalVideoCommentComposer,','composer: VideoComposerViewModel,\n    stamp: com.bilipai.desktop.ui.DesktopOriginalVideoCommentComposerStamp,')
    overload=overload.replace('visible = showCommentInput,','visible = showCommentInput && composer.commentStamp.value === stamp && stamp.presentation.isCurrent(),')
    overload=overload.replace('dismiss = composer::hideCommentInputDialog,','dismiss = { composer.dismissCommentComposer(stamp); Unit },')
    overload=overload.replace('searchMentions = composer::searchCommentMentionUsers,','searchMentions = { query -> composer.searchCommentMentions(stamp, query); Unit },')
    overload=overload.replace('updateDraft = composer::updateCommentDraft,','updateDraft = { text, images, sync -> composer.changeCommentDraft(stamp, text, images, sync); Unit },')
    overload=overload.replace('composer.sendComment(message, imageUris, syncToDynamic)','composer.submitComment(stamp, message, imageUris, syncToDynamic)')
    body=body[:end]+'\n'+overload+body[end:]
    save(output,'v029-domain-comment-input-source',dict(upstreamCommit=COMMIT,rawSources=PINS,
        completeOriginalContentPreserved=True,canonicalToGenerated=whole_proof(before,body)))
    return body

def dialog_delta(repo,output,canonical,body):
    sources=fixed(repo)
    if canonical.replace('\r\n','\n') != sources[DIALOG]: raise ValueError('Complete original input dialog changed between fixed025 and fixed029')
    before=body
    body=replace(body,'import androidx.compose.ui.window.Dialog\n','import com.bilipai.desktop.ui.DesktopWindowsCommentComposerWindow as Dialog\n')
    save(output,'v029-domain-comment-dialog-source',dict(upstreamCommit=COMMIT,rawSources=PINS,
        fixedCanonicalEntireBodyIdentical=True,platformContainerOnly=whole_proof(before,body)))
    return body

# Inserted by private preparation from the exact fixed source manifest.
PINS = [{'path': 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoPlaybackViewModel.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'a7655b4bcd5fbe40a3e7d95dac2e28a797a7c341', 'rawSha256': '45009eec81d77beb29d41ab5894b75407fbec110032db60a1f84565e08be70f3', 'bytes': 408734}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoComposerDraftState.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '6bee22ff931a4fe6ada17de45d4c0010db2556f6', 'rawSha256': '6d083e05389f81252b6635a26834e985acbef211523e7b8097da1588f0d91757', 'bytes': 608}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/viewmodel/VideoComposerViewModel.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': 'f0386145720d34c9ec5e46a6ae2fa4336b1108f9', 'rawSha256': '6500e13a311b41e86e35b60d1fb3144cdb658b5ce09bd2d6f20ef467b325312d', 'bytes': 1874}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/screen/VideoDetailInputOverlayAdapter.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '0acb9165274c8eefeafecabe0908df620c3219bb', 'rawSha256': 'dd79197d956204ecc4c4d5f2c6ea1522e7c2caf29344cdaa3cdc0235b58de150', 'bytes': 10375}, {'path': 'app/src/main/java/com/android/purebilibili/feature/video/ui/components/CommentInputDialog.kt', 'commit': 'a4b77f894d0a2dd26c0b9fc144b8adb88ac05480', 'gitBlob': '295a8ca27659ec6f0af7bfb086d991dc55715916', 'rawSha256': '63b0a8ff1541b5e8d29ed221cb4c7c3e1ad7c6102e3ee4816239703c7e769257', 'bytes': 57445}]
MANIFEST_SHA = '76a86a02c390b27a6fe76ce4858cdf1485191987406abbf833d05f9e1d1aa8a1'
