package com.android.purebilibili.feature.comment

import android.net.Uri
import androidx.paging.LoadState
import androidx.paging.PagingConfig
import com.android.purebilibili.core.paging.ForwardPagingSession
import com.android.purebilibili.feature.video.viewmodel.insertSentSubReply
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.repository.CommentFraudRepository
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.data.repository.CommentRepository
import com.android.purebilibili.feature.video.viewmodel.CommentComposerDraft
import com.android.purebilibili.feature.video.viewmodel.CommentComposerDraftState
import com.android.purebilibili.feature.video.viewmodel.commentComposerDraftKey
import com.android.purebilibili.feature.video.viewmodel.SubReplySortMode
import com.android.purebilibili.feature.video.viewmodel.SubReplyPageRequest
import com.android.purebilibili.feature.video.viewmodel.SubReplyUiState
import com.android.purebilibili.feature.video.viewmodel.resetForSort
import com.android.purebilibili.feature.video.viewmodel.resolveRoutedCommentRootReply
import com.android.purebilibili.feature.video.viewmodel.resolveSubReplyLoadedTotalCount
import com.android.purebilibili.feature.video.viewmodel.resolveSubReplyRemoteTotalCount
import com.android.purebilibili.feature.video.viewmodel.resolveSentSubReply
import com.android.purebilibili.feature.video.viewmodel.withSentSubReply
import com.android.purebilibili.feature.video.viewmodel.shouldApplySentSubReplyResult
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommentDetailFraudResult(
    val rpid: Long,
    val status: CommentFraudStatus
)

class CommentDetailViewModel : ViewModel() {
    private val _subReplyState = MutableStateFlow(SubReplyUiState())
    val subReplyState = _subReplyState.asStateFlow()

    private val _likedComments = MutableStateFlow<Set<Long>>(emptySet())
    val likedComments = _likedComments.asStateFlow()

    private val _hatedComments = MutableStateFlow<Set<Long>>(emptySet())
    val hatedComments = _hatedComments.asStateFlow()

    private val _showCommentInput = MutableStateFlow(false)
    val showCommentInput = _showCommentInput.asStateFlow()

    private val _replyingTo = MutableStateFlow<ReplyItem?>(null)
    val replyingTo = _replyingTo.asStateFlow()

    private val _composerDrafts = MutableStateFlow(CommentComposerDraftState())
    val composerDrafts = _composerDrafts.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending = _isSending.asStateFlow()

    private val _fraudResult = MutableStateFlow<CommentDetailFraudResult?>(null)
    val fraudResult = _fraudResult.asStateFlow()
    private var fraudCheckJob: Job? = null

    fun checkCommentFraud(reply: ReplyItem) {
        val oid = currentOid
        if (currentType != 1 || oid <= 0L || reply.rpid <= 0L) return
        fraudCheckJob?.cancel()
        _fraudResult.value = null
        fraudCheckJob = viewModelScope.launch {
            CommentRepository.checkCommentStatus(
                aid = oid,
                rpid = reply.rpid,
                rootId = reply.root,
                hasPictures = !reply.content.pictures.isNullOrEmpty(),
                sentAtSeconds = reply.ctime,
                waitMs = 0L
            ).onSuccess { status ->
                CommentFraudRepository.saveRecord(
                    rpid = reply.rpid,
                    oid = oid,
                    type = 1,
                    root = reply.root,
                    message = reply.content.message,
                    status = status,
                    initialStatus = null
                )
                _fraudResult.value = CommentDetailFraudResult(reply.rpid, status)
            }
        }
    }

    fun dismissFraudResult() {
        _fraudResult.value = null
    }

    private var currentOid: Long = 0L
    private var currentType: Int = 1
    private var currentRootId: Long = 0L
    private var currentTargetReplyId: Long = 0L
    private var subReplyLoadJob: Job? = null
    private var threadSession = 0L
    private var pagingSession: ForwardPagingSession<SubReplyPageRequest, SubReplyPageChunk>? = null
    private val sentReplies = linkedMapOf<Long, ReplyItem>()
    private val deletedReplyIds = mutableSetOf<Long>()

    fun loadInitial(
        oid: Long,
        rootId: Long,
        type: Int = 1,
        targetReplyId: Long = 0L
    ) {
        if (oid <= 0L || rootId <= 0L) return
        if (currentOid == oid && currentRootId == rootId && currentType == type &&
            _subReplyState.value.visible && _subReplyState.value.rootReply != null
        ) {
            return
        }

        if (currentOid != oid || currentRootId != rootId || currentType != type) {
            _composerDrafts.update { it.reset() }
            hideReplyInput()
            _isSending.value = false
        }
        threadSession++
        sentReplies.clear()
        deletedReplyIds.clear()
        currentOid = oid
        currentType = type
        currentRootId = rootId
        currentTargetReplyId = targetReplyId

        subReplyLoadJob?.cancel()
        _subReplyState.value = SubReplyUiState(
            visible = true,
            isLoading = true,
            error = null,
            targetReplyId = targetReplyId.takeIf { it != rootId } ?: 0L
        )

        startSubReplyPaging()
    }

    fun setSortMode(mode: SubReplySortMode) {
        val state = _subReplyState.value
        if (state.sortMode == mode || state.isLoading) return
        subReplyLoadJob?.cancel()
        _subReplyState.update { it.resetForSort(mode) }
        startSubReplyPaging()
    }

    fun refresh() {
        val state = _subReplyState.value
        if (currentOid <= 0L || currentRootId <= 0L || state.isRefreshing ||
            (state.isLoading && state.items.isEmpty())
        ) return
        _subReplyState.value = state.copy(isLoading = true, isRefreshing = true, error = null)
        startSubReplyPaging()
    }

    fun loadMore() {
        val state = _subReplyState.value
        if (state.isLoading) return
        if (state.error != null) pagingSession?.retry()
        else if (!state.isEnd && state.rootReply != null) pagingSession?.append()
    }

    private fun startSubReplyPaging() {
        val sortMode = _subReplyState.value.sortMode
        val target = _subReplyState.value.targetReplyId
        val oid = currentOid
        val rootId = currentRootId
        val type = currentType
        val seedRoot = _subReplyState.value.rootReply
        subReplyLoadJob?.cancel()
        var appliedPages = 0
        lateinit var session: ForwardPagingSession<SubReplyPageRequest, SubReplyPageChunk>
        session = ForwardPagingSession(
            config = PagingConfig(pageSize = 1, initialLoadSize = 1, prefetchDistance = 1, enablePlaceholders = false),
            sourceFactory = {
                var resolvedRoot = seedRoot
                subReplyPagingSource { request ->
                    val data = CommentRepository.getMobileSortedSubCommentsForSubject(
                        oid = oid, type = type, rootId = rootId, mode = sortMode.apiMode,
                        paginationOffset = request.offset,
                        targetReplyId = target.takeIf { request.page == 1 } ?: 0L
                    ).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    resolvedRoot = resolveRoutedCommentRootReply(emptyList(), data, rootId) ?: resolvedRoot
                    check(resolvedRoot != null) { "评论可能已被删除或不可见" }
                    data
                }
            },
            onChanged = { pages, states ->
                if (pagingSession === session && currentOid == oid && currentRootId == rootId && currentType == type && states != null) {
                    val failure = (states.refresh as? LoadState.Error)?.error ?: (states.append as? LoadState.Error)?.error
                    val ready = states.refresh is LoadState.NotLoading && pages.size > appliedPages
                    _subReplyState.update { current ->
                        var next = current
                        if (ready) {
                            val last = pages.last()
                            val root = resolveRoutedCommentRootReply(emptyList(), last.data, rootId) ?: current.rootReply ?: seedRoot
                            if (root != null) {
                                val incoming = pages.drop(appliedPages).flatMap { it.data.replies.orEmpty() }
                                var items = (if (appliedPages == 0) incoming else current.items + incoming)
                                    .distinctBy { it.rpid }.filterNot { it.rpid in deletedReplyIds }
                                sentReplies.values.filterNot { it.rpid in deletedReplyIds }.forEach { items = insertSentSubReply(items, it) }
                                val total = resolveSubReplyLoadedTotalCount(
                                    rootReply = root, loadedReplyCount = items.size,
                                    remoteReplyCount = resolveSubReplyRemoteTotalCount(last.data, root), previousTotalCount = current.totalCount
                                )
                                next = current.copy(
                                    visible = true, rootReply = root, items = items.toImmutableList(), baseItems = items.toImmutableList(),
                                    totalCount = total, page = last.request.page, basePage = last.request.page,
                                    grpcNextOffset = last.data.grpcNextOffset, baseGrpcNextOffset = last.data.grpcNextOffset,
                                    retryRequest = null
                                )
                            }
                        }
                        next.copy(
                            isLoading = states.refresh is LoadState.Loading || states.append is LoadState.Loading,
                            isRefreshing = current.isRefreshing && states.refresh is LoadState.Loading,
                            isEnd = states.append.endOfPaginationReached,
                            baseIsEnd = states.append.endOfPaginationReached,
                            error = failure?.let { it.message ?: "回复加载失败" },
                        )
                    }
                    if (ready) appliedPages = pages.size
                }
            }
        )
        pagingSession = session
        subReplyLoadJob = viewModelScope.launch { session.run() }
    }

    fun openConversation(reply: ReplyItem) {
        _subReplyState.update { it.copy(conversationAnchor = reply) }
    }

    fun closeConversation() {
        _subReplyState.update { it.copy(conversationAnchor = null) }
    }

    fun showReplyInput(reply: ReplyItem? = null) {
        _composerDrafts.update { it.open() }
        _replyingTo.value = reply ?: _subReplyState.value.rootReply
        _showCommentInput.value = true
    }

    fun hideReplyInput() {
        _showCommentInput.value = false
        _replyingTo.value = null
    }

    fun updateCommentDraft(
        sessionId: Long,
        replyRpid: Long?,
        text: String,
        imageUris: List<Uri>,
        syncToDynamic: Boolean
    ) {
        _composerDrafts.update {
            it.update(sessionId, replyRpid, CommentComposerDraft(text, imageUris, syncToDynamic))
        }
    }

    fun sendReply(
        message: String,
        pictures: List<Uri> = emptyList(),
        syncToDynamic: Boolean = false,
        onSuccess: () -> Unit = {}
    ) {
        if (_isSending.value) return
        val rootReply = _subReplyState.value.rootReply ?: return
        val replying = _replyingTo.value ?: rootReply
        val parentId = if (replying.rpid == rootReply.rpid) 0L else replying.rpid
        val oid = currentOid
        val type = currentType
        val sendSession = threadSession
        val sendDraftSession = _composerDrafts.value.sessionId
        val sendDraftReplyRpid = replying.rpid
        val sentDraft = _composerDrafts.value.drafts[commentComposerDraftKey(sendDraftReplyRpid)]
        _isSending.value = true

        viewModelScope.launch {
            // Bind the result to the thread that opened this composer, not the current destination.
            CommentRepository.addCommentForSubject(
                oid = oid,
                type = type,
                message = message,
                root = rootReply.rpid,
                parent = parentId,
                syncToDynamic = syncToDynamic
            ).onSuccess { newReply ->
                if (currentOid == oid && currentType == type && currentRootId == rootReply.rpid &&
                    threadSession == sendSession
                ) {
                    _isSending.value = false
                }
                if (currentOid != oid || currentType != type || !shouldApplySentSubReplyResult(
                        rootReply.rpid, sendSession, threadSession, _subReplyState.value
                    )
                ) return@onSuccess
                _composerDrafts.update {
                    it.complete(sendDraftSession, sendDraftReplyRpid, sentDraft)
                }
                if (_composerDrafts.value.sessionId == sendDraftSession) {
                    hideReplyInput()
                }
                if (newReply != null) {
                    val resolvedReply = resolveSentSubReply(newReply, rootReply.rpid, parentId, replying)
                    sentReplies[resolvedReply.rpid] = resolvedReply
                    _subReplyState.update {
                        it.withSentSubReply(
                            resolvedReply,
                            conversationItemsAreFiltered = false
                        )
                    }
                } else {
                    startSubReplyPaging()
                }
                if (_composerDrafts.value.sessionId == sendDraftSession) {
                    onSuccess()
                }
            }.onFailure {
                if (currentOid == oid && currentType == type && currentRootId == rootReply.rpid &&
                    threadSession == sendSession
                ) {
                    _isSending.value = false
                }
            }
        }
    }

    fun likeComment(rpid: Long) {
        val isCurrentlyLiked = _likedComments.value.contains(rpid)
        val oid = currentOid
        val type = currentType
        _likedComments.update { if (isCurrentlyLiked) it - rpid else it + rpid }
        if (!isCurrentlyLiked) {
            _hatedComments.update { it - rpid }
        }
        viewModelScope.launch {
            CommentRepository.likeCommentForSubject(oid, type, rpid, !isCurrentlyLiked).onFailure {
                _likedComments.update { if (isCurrentlyLiked) it + rpid else it - rpid }
            }
        }
    }

    fun hateComment(rpid: Long) {
        val isCurrentlyHated = _hatedComments.value.contains(rpid)
        val oid = currentOid
        val type = currentType
        _hatedComments.update { if (isCurrentlyHated) it - rpid else it + rpid }
        if (!isCurrentlyHated) {
            _likedComments.update { it - rpid }
        }
        viewModelScope.launch {
            CommentRepository.hateCommentForSubject(oid, type, rpid, !isCurrentlyHated).onFailure {
                _hatedComments.update { if (isCurrentlyHated) it + rpid else it - rpid }
            }
        }
    }

    fun deleteComment(rpid: Long) {
        val oid = currentOid
        val type = currentType
        val session = threadSession
        viewModelScope.launch {
            CommentRepository.deleteCommentForSubject(oid, type, rpid).onSuccess {
                if (session != threadSession || currentOid != oid || currentType != type) return@onSuccess
                deletedReplyIds += rpid
                sentReplies.remove(rpid)
                val currentItems = _subReplyState.value.items
                _subReplyState.value = _subReplyState.value.copy(
                    items = currentItems.filterNot { it.rpid == rpid }.toImmutableList(),
                    baseItems = _subReplyState.value.baseItems.filterNot { it.rpid == rpid }.toImmutableList(),
                    totalCount = (_subReplyState.value.totalCount - 1).coerceAtLeast(0)
                )
            }
        }
    }

    fun startDissolve(rpid: Long) {
        _subReplyState.update { it.copy(dissolvingIds = (it.dissolvingIds + rpid).toPersistentSet()) }
    }
}
