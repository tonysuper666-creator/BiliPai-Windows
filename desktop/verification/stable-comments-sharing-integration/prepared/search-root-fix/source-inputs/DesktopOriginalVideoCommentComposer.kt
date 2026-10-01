package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
/** Source-selected comment-only state; same Root Ops transport, no player/store. */
internal class DesktopOriginalVideoCommentComposer(
    private val scope: CoroutineScope,
    private val requests: DesktopVideoCommentRequests,
    private val info: () -> ViewInfo?,
    private val loadEmotePackages: suspend () -> Result<List<EmotePackage>>,
    private val searchMentionUsers: suspend (String) -> Result<List<MentionSearchUser>>,
    private val feedback: (String) -> Unit,
) {
    private var submissionSequence = 0L
    private suspend fun ensureOwned() {
        currentCoroutineContext().ensureActive()
        if (!requests.isOwned()) throw CancellationException("Video comment composer retired")
    }
    private fun launchOwned(block: suspend CoroutineScope.() -> Unit): Job = scope.launch { ensureOwned(); block() }
    private fun toast(message: String) { if (requests.isOwned()) feedback(message) }
    private val _commentInput = MutableStateFlow("")
    val commentInput = _commentInput.asStateFlow()
    private val _showCommentDialog = MutableStateFlow(false)
    val showCommentDialog = _showCommentDialog.asStateFlow()
    private val _composerDrafts = MutableStateFlow(VideoComposerDraftState())
    val composerDrafts = _composerDrafts.asStateFlow()

    private fun ensureComposerDraftVideo() {
        val videoId = info()?.bvid.orEmpty()
        if (_composerDrafts.value.videoId != videoId) {
            _composerDrafts.value = VideoComposerDraftState(videoId = videoId)
        }
    }

    private val _emotePackages = MutableStateFlow<List<com.android.purebilibili.data.model.response.EmotePackage>>(emptyList())
    val emotePackages = _emotePackages.asStateFlow()
    private var isEmotesLoaded = false

    private fun loadEmotes() {
        if (isEmotesLoaded) return
        launchOwned {
            loadEmotePackages()
                .onSuccess {
                    ensureOwned() 
                    _emotePackages.value = it 
                    isEmotesLoaded = true

                }
                .onFailure {
                    ensureOwned()  }
        }
    }
    
    fun showCommentInputDialog() {

        ensureComposerDraftVideo()
        _showCommentDialog.value = true
        // 懒加载表情包
        loadEmotes()
    }

    fun openRootCommentComposer() {
        clearReplyingTo()
        showCommentInputDialog()
    }
    
    fun hideCommentInputDialog() {
        _showCommentDialog.value = false
        clearReplyingTo()
        clearCommentMentionSearch()
    }

    fun updateCommentDraft(
        text: String,
        imageUris: List<String>,
        syncToDynamic: Boolean
    ) {
        ensureComposerDraftVideo()
        val key = commentComposerDraftKey(_replyingToComment.value?.rpid)
        val draft = CommentComposerDraft(text, imageUris, syncToDynamic)
        _composerDrafts.update { state ->
            state.copy(comments = state.comments + (key to draft))
        }
    }
    private val _isSendingComment = MutableStateFlow(false)
    val isSendingComment = _isSendingComment.asStateFlow()
    
    private val _replyingToComment = MutableStateFlow<com.android.purebilibili.data.model.response.ReplyItem?>(null)
    val replyingToComment = _replyingToComment.asStateFlow()

    private val _commentMentionSearchState = MutableStateFlow(CommentMentionSearchUiState())
    val commentMentionSearchState = _commentMentionSearchState.asStateFlow()

    private var commentMentionSearchJob: Job? = null
    
    fun setCommentInput(text: String) {
        _commentInput.value = text
    }
    
    fun setReplyingTo(comment: com.android.purebilibili.data.model.response.ReplyItem?) {
        _replyingToComment.value = comment
    }
    
    fun clearReplyingTo() {
        _replyingToComment.value = null
    }

    fun searchCommentMentionUsers(query: String) {
        if (_commentMentionSearchState.value.query == query && commentMentionSearchJob?.isActive == true) return

        commentMentionSearchJob?.cancel()
        _commentMentionSearchState.update {
            it.copy(query = query, isLoading = true, errorMessage = null)
        }
        commentMentionSearchJob = launchOwned {
            if (query.isNotBlank()) {
                delay(250L)
            }
            searchMentionUsers(query)
                .onSuccess { users ->
                    ensureOwned()
                    _commentMentionSearchState.update {
                        if (it.query == query) {
                            it.copy(users = users, isLoading = false, errorMessage = null)
                        } else {
                            it
                        }
                    }
                }
                .onFailure { error ->
                    ensureOwned()
                    _commentMentionSearchState.update {
                        if (it.query == query) {
                            it.copy(
                                users = emptyList(),
                                isLoading = false,
                                errorMessage = error.message ?: "搜索@好友失败"
                            )
                        } else {
                            it
                        }
                    }
                }
        }
    }

    fun clearCommentMentionSearch() {
        commentMentionSearchJob?.cancel()
        _commentMentionSearchState.value = CommentMentionSearchUiState()
    }
    
    /**
     * 发送评论
     * @param inputMessage 可选直接传入的内容，如果不传则使用 state 中的内容
     */
    fun sendComment(
        inputMessage: String? = null,
        imageUris: List<String> = emptyList(),
        syncToDynamic: Boolean = false,
        targetAid: Long? = null
    ) {
        if (inputMessage != null) {
            _commentInput.value = inputMessage
        }
        val current = info()
        val sendAid = resolveCommentSendTargetAid(
            requestedAid = targetAid,
            currentAid = current?.aid
        ) ?: return
        val message = _commentInput.value.trim()
        
        if (message.isEmpty() && imageUris.isEmpty()) {
            launchOwned { toast("请输入评论内容") }
            return
        }

        // Capture before launching. The dialog can be dismissed/recomposed immediately after
        // this call; reading the StateFlow later used to lose the reply target and publish a
        // new root comment instead.
        val replyTo = _replyingToComment.value
        val outgoingMessage = resolveCommentReplyMessage(
            message = message,
            replyName = replyTo?.member?.uname,
            replyRoot = replyTo?.root
        )

        if (!requests.isOwned() || _isSendingComment.value) return
        val submission = ++submissionSequence
        _isSendingComment.value = true
        val submissionJob = launchOwned {
            
            val (root, parent) = resolveCommentReplyTargets(
                replyRpid = replyTo?.rpid,
                replyRoot = replyTo?.root
            )
            val picturesResult = uploadCommentPictures(imageUris)
            val pictures = picturesResult.getOrElse { uploadError ->
                
                toast(uploadError.message ?: "图片上传失败")
                _isSendingComment.value = false
                return@launchOwned
            }
            
            requests.addCommentForSubject(
                    type = 1,
                    oid = sendAid,
                    message = outgoingMessage,
                    root = root,
                    parent = parent,
                    pictures = pictures,
                    syncToDynamic = syncToDynamic
                )
                .onSuccess { reply ->
                    ensureOwned()
                    toast(if (replyTo != null) "回复成功" else "评论成功")
                    _commentInput.value = ""
                    val sentDraftKey = commentComposerDraftKey(replyTo?.rpid)
                    _composerDrafts.update { state ->
                        state.copy(comments = state.comments - sentDraftKey)
                    }
                    _replyingToComment.value = null
                    _showCommentDialog.value = false
                    clearCommentMentionSearch()
                    
                    // 通知 UI 刷新评论列表
                    _commentSentEvent.trySend(reply)
                }
                .onFailure { error ->
                    ensureOwned()
                    
                    toast(error.message ?: "发送失败")
                }
            
            _isSendingComment.value = false
        }
        submissionJob.invokeOnCompletion { failure ->
            if (failure is CancellationException && requests.isOwned() && submissionSequence == submission) {
                _isSendingComment.value = false
            }
        }
    }

    private suspend fun uploadCommentPictures(imageUris: List<String>): Result<List<ReplyPicture>> =
        withContext(Dispatchers.IO) {
            try { Result.success(imageUris.take(9).mapIndexed { index, uri ->
                ensureOwned(); requests.uploadCommentPicture(uri,index).getOrElse { throw it }
            }) } catch (cancelled: CancellationException) { throw cancelled }
            catch (failed: Exception) { ensureOwned(); Result.failure(failed) }
        }


    
    // 评论发送成功事件
    private val _commentSentEvent = Channel<com.android.purebilibili.data.model.response.ReplyItem?>(
        capacity = resolvePlayerTransientEventChannelCapacity()
    )
    val commentSentEvent = _commentSentEvent.receiveAsFlow()

}

data class CommentMentionSearchUiState(
    val query: String = "",
    val users: List<MentionSearchUser> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

internal fun resolveCommentReplyTargets(replyRpid: Long?, replyRoot: Long?): Pair<Long, Long> {
    val parent = replyRpid?.takeIf { it > 0L } ?: 0L
    if (parent == 0L) return 0L to 0L
    val root = replyRoot?.takeIf { it > 0L } ?: parent
    return root to parent
}

internal fun resolveCommentReplyMessage(
    message: String,
    replyName: String?,
    replyRoot: Long?
): String {
    val normalizedMessage = message.trim()
    val normalizedName = replyName?.trim().orEmpty()
    return if ((replyRoot ?: 0L) > 0L && normalizedName.isNotEmpty()) {
        " 回复 @$normalizedName : $normalizedMessage"
    } else {
        normalizedMessage
    }
}

internal fun resolveCommentSendTargetAid(
    requestedAid: Long?,
    currentAid: Long?
): Long? {
    return requestedAid?.takeIf { it > 0L } ?: currentAid?.takeIf { it > 0L }
}

internal fun resolvePlayerTransientEventChannelCapacity(): Int = Channel.BUFFERED
