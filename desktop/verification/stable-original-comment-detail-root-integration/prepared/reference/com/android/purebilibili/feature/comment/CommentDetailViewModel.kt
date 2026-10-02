package com.android.purebilibili.feature.comment

import com.android.purebilibili.data.model.CommentFraudStatus
import com.android.purebilibili.data.model.response.ReplyItem
import com.android.purebilibili.feature.video.viewmodel.SubReplySortMode
import com.android.purebilibili.feature.video.viewmodel.SubReplyUiState
import com.android.purebilibili.feature.video.viewmodel.isSortedSubReplyPageEnd
import com.android.purebilibili.feature.video.viewmodel.resetForSort
import com.android.purebilibili.feature.video.viewmodel.resolveRoutedCommentRootReply
import com.android.purebilibili.feature.video.viewmodel.resolveSubReplyLoadedTotalCount
import com.android.purebilibili.feature.video.viewmodel.resolveSubReplyRemoteTotalCount
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommentDetailFraudResult(
    val rpid: Long,
    val status: CommentFraudStatus
)

internal class CommentDetailViewModel(
    private val scope: CoroutineScope,
    private val requests: DesktopVideoCommentRequests,
    private val commitState: ((() -> Unit) -> Boolean) = { action ->
        if (requests.isOwned()) { action(); true } else false
    },
) {
    private fun <T> MutableStateFlow(initial: T): kotlinx.coroutines.flow.MutableStateFlow<T> =
        com.bilipai.desktop.ui.DesktopHomeOwnedMutableStateFlow(initial, commitState)
    private suspend fun ensureOwned() {
        currentCoroutineContext().ensureActive()
        if (!requests.isOwned()) throw CancellationException("Comment detail owner retired")
    }
    private fun launchOwned(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        ensureOwned(); block()
    }
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
        fraudCheckJob = launchOwned {
            requests.checkCommentStatus(
                aid = oid,
                rpid = reply.rpid,
                rootId = reply.root,
                hasPictures = !reply.content.pictures.isNullOrEmpty(),
                sentAtSeconds = reply.ctime,
                waitMs = 0L
            ).onSuccess { status -> ensureOwned();
                requests.saveFraudRecord(
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

        loadSubReplies(page = 1, paginationOffset = null)
    }

    fun setSortMode(mode: SubReplySortMode) {
        val state = _subReplyState.value
        if (state.sortMode == mode || state.isLoading) return
        subReplyLoadJob?.cancel()
        _subReplyState.update { it.resetForSort(mode) }
        loadSubReplies(page = 1, paginationOffset = null)
    }

    fun loadMore() {
        val state = _subReplyState.value
        if (state.isLoading || state.isEnd || state.rootReply == null) return
        val nextPage = if (state.error != null && state.items.isEmpty()) 1 else state.page + 1
        _subReplyState.value = state.copy(isLoading = true, error = null)
        loadSubReplies(page = nextPage, paginationOffset = state.grpcNextOffset)
    }

    private fun loadSubReplies(page: Int, paginationOffset: String?) {
        val sortMode = _subReplyState.value.sortMode
        val targetReplyId = _subReplyState.value.targetReplyId.takeIf { page == 1 } ?: 0L
        val oid = currentOid
        val rootId = currentRootId
        val type = currentType

        subReplyLoadJob?.cancel()
        subReplyLoadJob = launchOwned {
            requests.getSortedSubCommentsForSubject(
                oid = oid,
                type = type,
                rootId = rootId,
                mode = sortMode.apiMode,
                paginationOffset = paginationOffset,
                targetReplyId = targetReplyId
            ).onSuccess { data -> ensureOwned();
                if (currentOid != oid || currentRootId != rootId || currentType != type) return@onSuccess
                val rootReply = resolveRoutedCommentRootReply(
                    loadedReplies = emptyList(),
                    remoteData = data,
                    rootReplyId = rootId
                ) ?: _subReplyState.value.rootReply
                if (rootReply == null) {
                    _subReplyState.value = _subReplyState.value.copy(
                        isLoading = false,
                        error = "评论可能已被删除或不可见"
                    )
                    return@onSuccess
                }

                val newItems = data.replies.orEmpty()
                val currentItems = _subReplyState.value.items
                val updatedItems = if (page == 1) {
                    newItems
                } else {
                    (currentItems + newItems).distinctBy { it.rpid }
                }

                val remoteTotalCount = resolveSubReplyRemoteTotalCount(
                    data = data,
                    rootReply = rootReply
                )
                val totalCount = resolveSubReplyLoadedTotalCount(
                    rootReply = rootReply,
                    loadedReplyCount = updatedItems.size,
                    remoteReplyCount = remoteTotalCount,
                    previousTotalCount = _subReplyState.value.totalCount
                )
                val isEnd = isSortedSubReplyPageEnd(data.cursor.isEnd, data.grpcNextOffset)

                _subReplyState.value = _subReplyState.value.copy(
                    visible = true,
                    rootReply = rootReply,
                    items = updatedItems.toImmutableList(),
                    baseItems = updatedItems.toImmutableList(),
                    totalCount = totalCount,
                    isLoading = false,
                    page = page,
                    basePage = page,
                    isEnd = isEnd,
                    baseIsEnd = isEnd,
                    grpcNextOffset = data.grpcNextOffset,
                    baseGrpcNextOffset = data.grpcNextOffset,
                    error = null
                )
            }.onFailure { error -> ensureOwned();
                if (currentOid != oid || currentRootId != rootId || currentType != type) return@onFailure
                _subReplyState.value = _subReplyState.value.copy(
                    isLoading = false,
                    error = error.message ?: "回复加载失败"
                )
            }
        }
    }

    fun openConversation(reply: ReplyItem) {
        _subReplyState.update { it.copy(conversationAnchor = reply) }
    }

    fun closeConversation() {
        _subReplyState.update { it.copy(conversationAnchor = null) }
    }

    fun showReplyInput(reply: ReplyItem? = null) {
        _replyingTo.value = reply ?: _subReplyState.value.rootReply
        _showCommentInput.value = true
    }

    fun hideReplyInput() {
        _showCommentInput.value = false
        _replyingTo.value = null
    }

    fun sendReply(
        message: String,
        pictures: List<String> = emptyList(),
        syncToDynamic: Boolean = false,
        onSuccess: () -> Unit = {}
    ) {
        val rootReply = _subReplyState.value.rootReply ?: return
        val replying = _replyingTo.value ?: rootReply
        val parentId = if (replying.rpid == rootReply.rpid) 0L else replying.rpid
        val oid = currentOid
        val type = currentType

        launchOwned {
            _isSending.value = true
            requests.addCommentForSubject(
                oid = oid,
                type = type,
                message = message,
                root = rootReply.rpid,
                parent = parentId,
                syncToDynamic = syncToDynamic
            ).onSuccess { newReply -> ensureOwned();
                _isSending.value = false
                hideReplyInput()
                onSuccess()
                if (newReply != null) {
                    val currentItems = _subReplyState.value.items
                    _subReplyState.value = _subReplyState.value.copy(
                        items = (listOf(newReply) + currentItems).distinctBy { it.rpid }.toImmutableList(),
                        totalCount = _subReplyState.value.totalCount + 1
                    )
                } else {
                    loadSubReplies(page = 1, paginationOffset = null)
                }
            }.onFailure { ensureOwned();
                _isSending.value = false
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
        launchOwned {
            requests.likeCommentForSubject(oid, type, rpid, !isCurrentlyLiked).onFailure { ensureOwned();
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
        launchOwned {
            requests.hateCommentForSubject(oid, type, rpid, !isCurrentlyHated).onFailure { ensureOwned();
                _hatedComments.update { if (isCurrentlyHated) it + rpid else it - rpid }
            }
        }
    }

    fun deleteComment(rpid: Long) {
        val oid = currentOid
        val type = currentType
        launchOwned {
            requests.deleteCommentForSubject(oid, type, rpid).onSuccess {
                val currentItems = _subReplyState.value.items
                _subReplyState.value = _subReplyState.value.copy(
                    items = currentItems.filterNot { it.rpid == rpid }.toImmutableList(),
                    totalCount = (_subReplyState.value.totalCount - 1).coerceAtLeast(0)
                )
            }
        }
    }

    fun startDissolve(rpid: Long) {
        _subReplyState.update { it.copy(dissolvingIds = (it.dissolvingIds + rpid).toPersistentSet()) }
    }
}
