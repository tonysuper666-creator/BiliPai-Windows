"""Fixed complete v036 dynamic comment state and original consumer region."""
from pathlib import Path
import hashlib,json,re,textwrap
COMMIT='3608e0347ba3314ac74190d724d78598fdbad879'
MANIFEST_SHA='29d51dc3e89ff4561f06b2c524199e11ea5cd3628c383b90b631118264ea8f61'
PINS=[{'path': 'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt', 'filename': 'DynamicViewModel.kt', 'bytes': 116882, 'sha256Bytes': '1519d7f62101fe5eb077e1cefec4551ce9f80b89588979dc237e81dda4e156c2', 'gitBlob': '2d7b116b344e920e10c9f3c147f668e80dbf4576', 'role': 'complete-comment-state-and-consumers-selected'}, {'path': 'app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicCommentLoadPolicy.kt', 'filename': 'DynamicCommentLoadPolicy.kt', 'bytes': 8092, 'sha256Bytes': 'd549734ddf1afcf3f0112123fb95234cb487b151e4ddd6485dab6149a2bba3dc', 'gitBlob': '26fe8aeed6cebc581eba8215dcfe896f81704bf4', 'role': 'complete-original-pure-policy'}]
def digest(raw):return hashlib.sha256(raw).hexdigest()
def read_sources():
    archive=Path(__file__).resolve().parent.parent/'upstream-slices/v036-dynamic-comment-cache'
    raw=(archive/'manifest.json').read_bytes()
    if digest(raw)!=MANIFEST_SHA:raise ValueError('Fixed dynamic comment manifest changed')
    manifest=json.loads(raw)
    if manifest['fixedUpstreamCommit']!=COMMIT or manifest['files']!=PINS:raise ValueError('Unknown dynamic comment source contract')
    sources={}
    for row in PINS:
        raw=(archive/row['filename']).read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
        if digest(raw)!=row['sha256Bytes'] or len(raw)!=row['bytes'] or blob!=row['gitBlob']:raise ValueError('Fixed dynamic comment original changed')
        sources[row['filename']]=raw.decode('utf8')
    return sources
def select_comment_region():
    from v041_dynamic_comment_identity import dynamic_view_model, COMMIT as identity_commit
    source=dynamic_view_model()
    fields=source[source.index('    private val _selectedDynamic ='):source.index('    // 点赞状态缓存')]
    body=source[source.index('    private fun findDynamicById'):source.index('    fun likeDynamic(')]
    return fields,body,dict(sourceCommit=identity_commit,completeOriginalCommentStateFields=True,
        completeOriginalCommentConsumerRegion=True,wholeDynamicViewModelPorted=False,
        fieldsSHA256LF=digest(fields.encode()),bodySHA256LF=digest(body.encode()))
def generate_policy(output):
    source=read_sources()['DynamicCommentLoadPolicy.kt']
    p=output/'com/android/purebilibili/feature/dynamic/DesktopOriginalDynamicCommentLoadPolicy.kt'
    p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(source.encode())
    return p

def once(source, old, new):
    if source.count(old)!=1:raise ValueError('Dynamic comment platform seam changed: '+old[:100])
    return source.replace(old,new,1)

def adapt_comment_region(repo, media, parser):
    fields,body,selection=select_comment_region()
    original_fields,original_body=fields,body
    fields=once(fields,'    private val commentCache = DynamicCommentCache()\n','')
    fields=once(fields,'        _commentUiState.update(transform)\n',
        '        if (!isOwned()) return\n        _commentUiState.update { if (isOwned()) transform(it) else it }\n')
    fields+='    internal val selectedCommentTarget: StateFlow<DynamicCommentTarget?> = _selectedCommentTarget.asStateFlow()\n'
    fields+='    private val _commentConfirmationRevision = MutableStateFlow(0L)\n'
    fields+='    internal val commentConfirmationRevision: StateFlow<Long> = _commentConfirmationRevision.asStateFlow()\n'
    from v041_dynamic_comment_identity import dynamic_view_model
    source=dynamic_view_model()
    def function(name):return textwrap.indent(media.function(source,name,parser).strip(),'    ')
    body=once(body,function('findDynamicById'),'''    private fun findDynamicById(dynamicId: String): DynamicItem? {
        if (dynamicId != subjectId || !isOwned()) return null
        return _selectedDynamic.value?.takeIf { it.id_str == subjectId }
            ?: seedItem()?.takeIf { it.id_str == subjectId }
    }''')
    body=once(body,function('uploadCommentPictures'),'''    private suspend fun uploadCommentPictures(imageUris: List<String>): List<ReplyPicture> =
        withContext(Dispatchers.IO) {
            require(imageUris.size <= 9) { "最多选择 9 张图片" }
            imageUris.mapIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                if (!isOwned()) throw CancellationException("Reply image owner retired")
                requests.uploadCommentPicture(uri, index).getOrElse { throw it }.also {
                    currentCoroutineContext().ensureActive()
                    if (!isOwned()) throw CancellationException("Reply image owner retired")
                }
            }
        }''')
    body=body.replace('List<Uri>','List<String>').replace('CommentRepository.','requests.')
    body=body.replace('DynamicRepository.getDynamicDetail(', 'requests.getDynamicDetail(')
    body=body.replace('launchAccount {','launchOwned {').replace('return@launchAccount','return@launchOwned')
    body=body.replace('viewModelScope.launch {','launchOwned {').replace('return@launch\n','return@launchOwned\n')
    # Never read TokenManager or manufacture a CSRF string on Windows. The
    # existing typed request binding remains the sole authentication authority.
    body=once(body,'                val csrf = com.android.purebilibili.core.store.TokenManager.csrfCache\n                if (csrf.isNullOrEmpty()) {',
        '                if (!requests.hasCsrf()) {')
    # All original result callbacks are admitted after suspension. Candidate
    # helpers only produce values; the original request/subject/sort check still
    # chooses the one result published to the page.
    body=re.sub(r'(\.on(?:Success|Failure)\s*\{(?:\s*\w+\s*->)?\s*\n)',r'\1                    ensurePageOwned();\n',body)
    body=re.sub(r'((?:onSuccess|onFailure)[ \t]*=[ \t]*\{(?:[ \t]*\w+[ \t]*->)?)',r'\1\n                    ensurePageOwned();',body)
    body=body.replace('fullDetail ->\n','fullDetail ->\n                        ensureRequestOwned()\n')
    body=once(body,'                val attempts = candidateResults.mapNotNull { it.attempt }',
        '                ensureRequestOwned()\n                val attempts = candidateResults.mapNotNull { it.attempt }')
    # The candidate reducer has no UI/cache writes. Its child jobs check page
    # ownership; all mounted request callbacks retain Root's generation guard.
    a=body.index('    private suspend fun loadDynamicCommentCandidate(')
    z=body.index('    private fun applyLoadedDynamicComments(',a)
    candidate=body[a:z]
    body=body[:a].replace('ensurePageOwned();','ensureRequestOwned();')+candidate+body[z:].replace('ensurePageOwned();','ensureRequestOwned();')
    body=body.replace('                if (requestId == commentLoadRequestId) {',
        '                if (isOwned() && requestId == commentLoadRequestId) {')
    body=body.replace('                if (requestId == subReplyLoadRequestId',
        '                if (isOwned() && requestId == subReplyLoadRequestId')
    body=body.replace('            } catch (e: Exception) {\n',
        '            } catch (e: Exception) {\n                ensureRequestOwned()\n')
    # Keep the already mounted comments/target/cursor on a manual refresh.
    # This preserves the existing Windows refresh contract when the transport
    # fails; a successful original reducer still replaces/merges its own page.
    body=once(body,'                        comments = emptyList(),\n                        totalCount = if (isRefresh) it.totalCount else fallbackCount,',
        '                        comments = if (isRefresh && it.dynamicId == dynamicId) it.comments else emptyList(),\n'
        '                        totalCount = if (isRefresh) it.totalCount else fallbackCount,')
    body=once(body,'                _selectedCommentTarget.value = null\n                commentNextPage = 1\n                commentsEnd = true\n                commentGrpcNextOffset = null',
        '                if (!isRefresh) {\n                    _selectedCommentTarget.value = null\n'
        '                    commentNextPage = 1\n                    commentsEnd = true\n'
        '                    commentGrpcNextOffset = null\n                }')
    body=body.replace('        val requestId = ++commentLoadRequestId\n        commentLoadJob?.cancel()',
        '        val requestId = ++commentLoadRequestId\n'
        '        subReplyLoadRequestId++\n        subReplyLoadJob?.cancel()\n'
        '        _subReplyState.update { it.copy(visible = false, isLoading = false, error = null) }\n'
        '        commentLoadJob?.cancel()',1)
    # Public callbacks are owned by the raw detail identity and cannot revive a
    # retired page. Reject another item before setting the original detail field.
    body=body.replace('        _selectedDynamic.value = item\n',
        '        if (!isOwned() || item?.id_str != subjectId) return\n        _selectedDynamic.value = item\n')
    for name in ['refreshComments','closeCommentSheet','setDynamicCommentSortMode',
                 'openSubReply','closeSubReply','setSubReplySortMode','refreshSubReplies',
                 'startCommentReply','clearCommentReplyTarget','loadMoreComments',
                 'loadMoreSubReplies','deleteDynamicComment','toggleDynamicCommentTop','reportDynamicComment']:
        pattern=r'(    fun '+name+r'\([\s\S]*?\)\s*\{\n)'
        body,count=re.subn(pattern,r'\1        if (!isOwned()) return\n',body,count=1)
        if count!=1:raise ValueError('Original public callback changed: '+name)
    body=once(body,'    fun openSubReplyFromRoute(rootReplyId: Long, targetReplyId: Long = 0L): Boolean {',
        '    fun openSubReplyFromRoute(rootReplyId: Long, targetReplyId: Long = 0L): Boolean {\n        if (!isOwned()) return false')
    body=body.replace('        if (rpid <= 0L) return','        if (!isOwned() || rpid <= 0L) return')
    # Count receipts protect Root's detail readback from an older response even
    # when the confirmed count itself is unchanged. Resets/cache hits emit none.
    body=once(body,'        cacheCurrentComments(appliedReplies, sortMode.apiMode)',
        '        _commentConfirmationRevision.update { it + 1L }\n        cacheCurrentComments(appliedReplies, sortMode.apiMode)')
    body=once(body,'                    cacheCurrentComments(mergedReplies)',
        '                    _commentConfirmationRevision.update { it + 1L }\n                    cacheCurrentComments(mergedReplies)')
    body=once(body,'                    onResult(true, "评论已删除")',
        '                    _commentConfirmationRevision.update { it + 1L }\n'
        '                    cacheCurrentComments(_commentUiState.value.comments)\n'
        '                    onResult(true, "评论已删除")')
    # Preserve the original request/reducers/rollback, with Root's synchronous
    # same-reply mutation admission. Completion releases even queued jobs.
    for name,next_name,marker in [
        ('likeComment','hateComment','        updateCurrentComments { applyDynamicCommentLikeInList(it, rpid, toLiked) }'),
        ('hateComment','findDynamicComment','        val toHated = !isDynamicCommentHated(current)')]:
        a=body.index('    fun '+name+'(')
        z=body.index(('    private fun ' if next_name=='findDynamicComment' else '    fun ')+next_name+'(',a)
        block=body[a:z]
        block=once(block,marker,'        val mutationHolder = acquireReplyMutation(rpid) ?: return\n'+marker)
        block=once(block,'        launchOwned {','        val mutationJob = launchOwned {')
        block=once(block,'\n        }\n    }','\n        }\n        mutationJob.invokeOnCompletion { replyMutationHolders.remove(rpid, mutationHolder) }\n    }')
        body=body[:a]+block+body[z:]
    a=body.index('    fun postComment(');z=body.index('    private suspend fun uploadCommentPictures',a)
    post=body[a:z]
    post=once(post,'        onResult: (Boolean, String) -> Unit,',
        '        onSubmissionCancelled: () -> Unit = {},\n        onResult: (Boolean, String) -> Unit,')
    post=once(post,'                val replyTarget = _commentReplyTarget.value\n','')
    post=post.replace('onResult(', 'deliverSubmissionResult(')
    post=once(post,'        launchOwned {','''        if (!isOwned() || dynamicId != subjectId) return
        val replyTarget = _commentReplyTarget.value
        val submissionId = commentSubmissionSequence.incrementAndGet()
        val resultDelivered = AtomicBoolean(false)
        fun deliverSubmissionResult(success: Boolean, message: String) {
            if (isOwned() && commentSubmissionSequence.get() == submissionId && resultDelivered.compareAndSet(false, true)) {
                onResult(success, message)
            }
        }
        val submissionJob = launchOwned {''')
    post=once(post,'                val pictures = uploadCommentPictures(imageUris)\n',
        '                val pictures = uploadCommentPictures(imageUris)\n                ensureRequestOwned()\n')
    post=once(post,'                if (response.isSuccess) {',
        '                ensureRequestOwned()\n                if (response.isSuccess) {')
    post=once(post,'        }\n    }\n', '''        }
        submissionJob.invokeOnCompletion { failure ->
            if (failure is CancellationException && isOwned() && commentSubmissionSequence.get() == submissionId &&
                resultDelivered.compareAndSet(false, true)) {
                onSubmissionCancelled()
            }
        }
    }
''')
    body=body[:a]+post+body[z:]
    # No account/content ids or raw transport failures are logged by the port.
    body=re.sub(r'^\s*com\.android\.purebilibili\.core\.util\.Logger\.[dew]\([^\n]+\)\n','\n',body,flags=re.M)
    body=re.sub(r'^\s*com\.android\.purebilibili\.core\.util\.Logger\.[dew]\(\n[\s\S]*?^\s*\)\n','\n',body,flags=re.M)
    body=body.replace('                e.printStackTrace()\n','')
    # The current canonical Video VM owns the same original dissolve helper.
    video=(repo/'desktop/upstream-slices/v036-video-comment-cache/VideoCommentViewModel.kt').read_text(encoding='utf-8')
    dissolve=textwrap.indent(media.function(video,'startSubDissolve',parser).strip(),'    ')
    if 'fun startSubDissolve(' not in body:
        dissolve=dissolve.replace('        val current = _subReplyState.value',
            '        if (!isOwned() || rpid <= 0L) return\n        val current = _subReplyState.value')
        body+='\n'+dissolve+'\n'
    body=once(body,'    fun deleteDynamicComment(rpid: Long, onResult: (Boolean, String) -> Unit) {\n        if (!isOwned()) return',
        '    fun deleteDynamicComment(rpid: Long, onResult: (Boolean, String) -> Unit) {\n'
        '        if (!isOwned()) return\n'
        '        _subReplyState.update { it.copy(dissolvingIds = (it.dissolvingIds - rpid).toImmutableSet()) }')
    body=body.replace('        launchOwned {\n            delay(SUB_DISSOLVE_MS)',
        '        launchOwned {\n            delay(SUB_DISSOLVE_MS)')
    body=body.replace('            _subReplyState.update { it.copy(dissolvingIds = (it.dissolvingIds - rpid).toImmutableSet()) }',
        '            ensureRequestOwned()\n            _subReplyState.update { it.copy(dissolvingIds = (it.dissolvingIds - rpid).toImmutableSet()) }')
    for forbidden in ['CommentRepository.','DynamicRepository.','TokenManager.','Logger.','android.net.Uri','launchAccount','_comments.value','_commentTotalCount.value']:
        if forbidden in body:raise ValueError('Unexpected Android/obsolete dynamic seam: '+forbidden)
    from v029_comment_search import whole_proof
    proof=dict(selection,fieldsInverse=whole_proof(original_fields,fields),consumersInverse=whole_proof(original_body,body),
        originalConsumerFunctionsRetained=True,accountCacheBindingRequired=True,
        originalSingleCommentUiStateRetained=True,wholeDynamicViewModelPorted=False)
    return fields,body,proof
