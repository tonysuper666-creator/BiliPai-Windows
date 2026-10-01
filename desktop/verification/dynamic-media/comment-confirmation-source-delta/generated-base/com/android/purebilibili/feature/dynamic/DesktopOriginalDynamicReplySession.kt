// GENERATED from app/src/main/java/com/android/purebilibili/feature/dynamic/DynamicViewModel.kt; do not edit.
// LF-normalized SHA-256: 9981e964c6b90b51b93bfc7808490043fe60b460a8cc6491027afbbe2d0d13f5
package com.android.purebilibili.feature.dynamic
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.viewmodel.*
import com.bilipai.desktop.ui.DesktopDynamicReplyRequests
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

internal class DesktopOriginalDynamicReplySession(
    private val subjectId: String,
    parentScope: CoroutineScope,
    private val requests: DesktopDynamicReplyRequests,
    private val seedItem: () -> DynamicItem?,
    private val stillOwned: () -> Boolean = { true },
) : AutoCloseable {
    private val alive = AtomicBoolean(true)
    private val ownedJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val viewModelScope = CoroutineScope(parentScope.coroutineContext + ownedJob)
    fun isOwned(): Boolean = alive.get() && ownedJob.isActive && stillOwned() && requests.isOwned()
    private val replyMutationHolders = ConcurrentHashMap<Long, Any>()
    private fun acquireReplyMutation(rpid: Long): Any? {
        val holder = Any()
        return holder.takeIf { replyMutationHolders.putIfAbsent(rpid, holder) == null }
    }
    private inner class ReplyTaskScope(
        private val requestId: Long,
        override val coroutineContext: kotlin.coroutines.CoroutineContext,
    ) : CoroutineScope {
        fun ensureRequestOwned() {
            coroutineContext.ensureActive()
            if (!isOwned() || requestId != commentLoadRequestId) throw CancellationException("Reply owner retired")
        }
    }
    private fun launchOwned(block: suspend ReplyTaskScope.() -> Unit): Job {
        val requestId = commentLoadRequestId
        return viewModelScope.launch {
            val task = ReplyTaskScope(requestId, coroutineContext)
            task.ensureRequestOwned()
            task.block()
        }
    }
    override fun close() {
        if (alive.getAndSet(false)) {
            commentLoadRequestId++
            ownedJob.cancel()
        }
    }
    private val _selectedDynamic = MutableStateFlow<DynamicItem?>(null)
    private val _selectedCommentTarget = MutableStateFlow<DynamicCommentTarget?>(null)
    val selectedDynamicId: StateFlow<String?> = _selectedDynamic
        .map { it?.id_str }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L),
            initialValue = null
        )
    
    // 评论列表
    private val _comments = MutableStateFlow<List<com.android.purebilibili.data.model.response.ReplyItem>>(emptyList())
    val comments: StateFlow<List<com.android.purebilibili.data.model.response.ReplyItem>> = _comments.asStateFlow()

    private val _dynamicCommentSortMode = MutableStateFlow(CommentSortMode.HOT)
    val dynamicCommentSortMode: StateFlow<CommentSortMode> = _dynamicCommentSortMode.asStateFlow()

    private var subReplyLoadJob: Job? = null
    private val _subReplyState = MutableStateFlow(SubReplyUiState())
    val subReplyState: StateFlow<SubReplyUiState> = _subReplyState.asStateFlow()

    private val _commentReplyTarget = MutableStateFlow<DynamicCommentComposerTarget?>(null)
    internal val commentReplyTarget: StateFlow<DynamicCommentComposerTarget?> = _commentReplyTarget.asStateFlow()
    
    // [新增] 动态评论总数 (从评论接口获取实时数据)
    private val _commentTotalCount = MutableStateFlow(0)
    val commentTotalCount: StateFlow<Int> = _commentTotalCount.asStateFlow()
    
    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading: StateFlow<Boolean> = _commentsLoading.asStateFlow()

    private val _commentsLoadingMore = MutableStateFlow(false)
    val commentsLoadingMore: StateFlow<Boolean> = _commentsLoadingMore.asStateFlow()

    private var commentNextPage = 1
    private var commentsEnd = true
    private var commentGrpcNextOffset: String? = null
    private var commentLoadJob: Job? = null
    private var commentLoadRequestId = 0L
    
    internal val selectedCommentTarget: StateFlow<DynamicCommentTarget?> = _selectedCommentTarget.asStateFlow()
    private fun findDynamicById(dynamicId: String): DynamicItem? {
        if (dynamicId != subjectId || !isOwned()) return null
        return _selectedDynamic.value?.takeIf { it.id_str == dynamicId } ?: seedItem()?.takeIf { it.id_str == subjectId }
    }
    
    /**
     *  打开评论弹窗
     */
    fun openCommentSheet(dynamicId: String) {
        val item = findDynamicById(dynamicId)
        if (!isOwned()) return
        _selectedDynamic.value = item
        if (item != null) {
            loadCommentsForDynamic(item)
        }
    }

    fun openCommentSheet(
        item: DynamicItem,
        rootReplyId: Long = 0L,
        targetReplyId: Long = 0L
    ) {
        if (!isOwned() || item.id_str != subjectId) return
        _selectedDynamic.value = item
        loadCommentsForDynamic(
            item = item,
            routedRootReplyId = rootReplyId,
            routedTargetReplyId = targetReplyId
        )
    }
    
    /**
     *  关闭评论弹窗
     */
    fun closeCommentSheet() {
        commentLoadRequestId++
        commentLoadJob?.cancel()
        commentLoadJob = null
        _selectedDynamic.value = null
        _selectedCommentTarget.value = null
        _comments.value = emptyList()
        subReplyLoadJob?.cancel()
        _subReplyState.value = SubReplyUiState()
        _commentReplyTarget.value = null
        _commentsLoadingMore.value = false
        commentNextPage = 1
        commentsEnd = true
        commentGrpcNextOffset = null
        _dynamicCommentSortMode.value = CommentSortMode.HOT
        // [新增] 清空计数
        _commentTotalCount.value = 0
    }

    fun setDynamicCommentSortMode(mode: CommentSortMode) {
        if (!isOwned()) return
        if (mode != CommentSortMode.HOT && mode != CommentSortMode.NEWEST) return
        if (_dynamicCommentSortMode.value == mode) return
        _dynamicCommentSortMode.value = mode
        val item = _selectedDynamic.value ?: return
        _comments.value = emptyList()
        _commentsLoadingMore.value = false
        commentNextPage = 1
        commentsEnd = true
        commentGrpcNextOffset = null
        loadCommentsForDynamic(item)
    }
    
    /**
     *  加载动态评论 (使用正确的 oid 和 type)
     */
    private fun loadCommentsForDynamic(
        item: DynamicItem,
        routedRootReplyId: Long = 0L,
        routedTargetReplyId: Long = 0L
    ) {
        val requestId = ++commentLoadRequestId
        subReplyLoadJob?.cancel()
        _subReplyState.update { it.copy(visible = false, isLoading = false, error = null) }
        commentLoadJob?.cancel()
        commentLoadJob = launchOwned {
            val sortMode = _dynamicCommentSortMode.value
            _commentsLoading.value = true
            _commentsLoadingMore.value = false
            _selectedCommentTarget.value = null
            commentNextPage = 1
            commentsEnd = true
            commentGrpcNextOffset = null
            val fallbackCount = item.modules.module_stat?.comment?.count ?: 0
            _commentTotalCount.value = fallbackCount
            
            try {
                var effectiveItem = item
                var targets = resolveDynamicCommentTargets(effectiveItem)
                if (targets.isEmpty() && effectiveItem.id_str.isNotBlank()) {

                    requests.getDynamicDetail(effectiveItem.id_str).getOrNull()?.let { fullDetail ->
                        ensureRequestOwned()
                        effectiveItem = fullDetail
                        _selectedDynamic.value = fullDetail
                        targets = resolveDynamicCommentTargets(fullDetail)
                    }
                }
                if (targets.isEmpty()) {
                    return@launchOwned
                }

                val attempts = mutableListOf<DynamicCommentLoadAttempt>()
                targets.forEachIndexed { index, target ->

                    val exactCount = requests.getCommentCountForSubject(
                        oid = target.oid,
                        type = target.type
                    ).getOrNull()
                    val result = requests.getCommentsForSubject(
                        oid = target.oid,
                        type = target.type,
                        page = 1,
                        ps = 20,
                        mode = sortMode.apiMode,
                        fallbackOnMissingLocation = target.type != 11
                    )
                    result.onSuccess { data ->
                    ensureRequestOwned()
                        val payload = resolveDynamicCommentPayload(
                            data = data,
                            fallbackCount = exactCount ?: 0,
                            includeHotReplies = sortMode == CommentSortMode.HOT
                        )
                        attempts += DynamicCommentLoadAttempt(
                            target = target,
                            replies = payload.replies,
                            totalCount = payload.totalCount,
                            candidateIndex = index,
                            nextPage = 2,
                            isEnd = resolveDynamicMainCommentPageEnd(
                                cursorIsEnd = data.cursor.isEnd,
                                fetchedReplyCount = data.replies.orEmpty().size,
                                loadedReplyCount = payload.replies.size,
                                totalCount = payload.totalCount
                            ),
                            grpcNextOffset = data.grpcNextOffset.takeIf { it.isNotBlank() }
                        )
                    }.onFailure { error ->
                    ensureRequestOwned()

                        if ((exactCount ?: 0) > 0) {
                            attempts += DynamicCommentLoadAttempt(
                                target = target,
                                replies = emptyList(),
                                totalCount = exactCount ?: 0,
                                candidateIndex = index,
                                nextPage = 2,
                                isEnd = true,
                                grpcNextOffset = null
                            )
                        }
                    }
                }

                val selected = selectPreferredDynamicCommentAttempt(
                    attempts = attempts,
                    expectedCount = fallbackCount
                )
                ensureRequestOwned()
                if (requestId != commentLoadRequestId) return@launchOwned
                if (selected != null) {
                    if (_dynamicCommentSortMode.value != sortMode) {
                        return@launchOwned
                    }
                    _selectedCommentTarget.value = selected.target
                    _comments.value = selected.replies
                    _commentTotalCount.value = selected.totalCount
                    commentNextPage = selected.nextPage
                    commentsEnd = selected.isEnd
                    commentGrpcNextOffset = selected.grpcNextOffset
                    if (routedRootReplyId > 0L) {
                        openSubReplyFromRoute(
                            rootReplyId = routedRootReplyId,
                            targetReplyId = routedTargetReplyId
                        )
                    }
                } else {
                    _comments.value = emptyList()
                    _commentTotalCount.value = fallbackCount
                    commentNextPage = 1
                    commentsEnd = true
                    commentGrpcNextOffset = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                if (isOwned() && requestId == commentLoadRequestId) {
                    _commentsLoading.value = false
                }
            }
        }
    }

    fun loadMoreComments() {
        val target = _selectedCommentTarget.value ?: return
        if (_commentsLoading.value || _commentsLoadingMore.value || commentsEnd) return

        val pageToLoad = commentNextPage
        val sortMode = _dynamicCommentSortMode.value
        val requestId = commentLoadRequestId
        val paginationOffset = commentGrpcNextOffset
        _commentsLoadingMore.value = true
        launchOwned {
            try {
                requests.getCommentsForSubject(
                    oid = target.oid,
                    type = target.type,
                    page = pageToLoad,
                    ps = 20,
                    mode = sortMode.apiMode,
                    paginationOffset = paginationOffset,
                    fallbackOnMissingLocation = target.type != 11
                ).onSuccess { data ->
                    ensureRequestOwned()
                    if (!shouldApplyDynamicCommentPageResult(
                            activeRequestId = commentLoadRequestId,
                            requestId = requestId,
                            activeTarget = _selectedCommentTarget.value,
                            requestTarget = target,
                            activeSortMode = _dynamicCommentSortMode.value,
                            requestSortMode = sortMode,
                        )
                    ) return@onSuccess

                    val currentReplies = _comments.value
                    val newReplies = data.replies.orEmpty()
                    val mergedReplies = (currentReplies + newReplies).distinctBy { it.rpid }
                    val addedReplyCount = mergedReplies.size - currentReplies.size
                    val totalCount = maxOf(
                        data.getAllCount(),
                        _commentTotalCount.value,
                        mergedReplies.size
                    )
                    _comments.value = mergedReplies
                    _commentTotalCount.value = totalCount
                    commentNextPage = pageToLoad + 1
                    commentGrpcNextOffset = data.grpcNextOffset.takeIf { it.isNotBlank() }
                    commentsEnd = resolveDynamicMainCommentPageEnd(
                        cursorIsEnd = data.cursor.isEnd,
                        fetchedReplyCount = addedReplyCount,
                        loadedReplyCount = mergedReplies.size,
                        totalCount = totalCount
                    )
                }.onFailure { error ->
                    ensureRequestOwned()

                }
            } finally {
                if (isOwned() && requestId == commentLoadRequestId) {
                    _commentsLoadingMore.value = false
                }
            }
        }
    }
    
    /**
     *  加载评论 (兼容旧调用方式)
     */
    fun loadComments(dynamicId: String) {
        val item = findDynamicById(dynamicId)
        if (item != null) {
            loadCommentsForDynamic(item)
        }
    }

    fun openSubReply(rootReply: ReplyItem, targetReplyId: Long = 0L) {
        if (!isOwned()) return
        val target = _selectedCommentTarget.value ?: return
        subReplyLoadJob?.cancel()
        _subReplyState.value = SubReplyUiState(
            visible = true,
            rootReply = rootReply,
            targetReplyId = targetReplyId.takeIf { it != rootReply.rpid } ?: 0L,
            totalCount = resolveSubReplyLoadedTotalCount(
                rootReply = rootReply,
                loadedReplyCount = rootReply.replies.orEmpty().size,
                remoteReplyCount = 0
            ),
            isLoading = true,
            page = 1
        )
        loadSubReplies(
            oid = target.oid,
            type = target.type,
            rootId = rootReply.rpid,
            page = 1
        )
    }

    fun openSubReplyFromRoute(rootReplyId: Long, targetReplyId: Long = 0L): Boolean {
        if (!isOwned()) return false
        val target = _selectedCommentTarget.value ?: return false
        if (rootReplyId <= 0L) return false

        if (openLoadedRoutedSubReply(rootReplyId, targetReplyId)) return true

        subReplyLoadJob?.cancel()
        markRoutedSubReplyLoading(rootReplyId, targetReplyId)
        loadRoutedSubReplyFromRemote(target, rootReplyId, targetReplyId)
        return true
    }

    private fun openLoadedRoutedSubReply(rootReplyId: Long, targetReplyId: Long): Boolean {
        resolveRoutedCommentRootReply(
            loadedReplies = _comments.value,
            remoteData = null,
            rootReplyId = rootReplyId
        )?.let { rootReply ->
            openSubReply(rootReply, targetReplyId)
            return true
        }
        return false
    }

    private fun markRoutedSubReplyLoading(rootReplyId: Long, targetReplyId: Long) {
        _subReplyState.value = _subReplyState.value.copy(
            visible = false,
            isLoading = true,
            error = null,
            targetReplyId = targetReplyId.takeIf { it != rootReplyId } ?: 0L
        )
    }

    private fun loadRoutedSubReplyFromRemote(
        target: DynamicCommentTarget,
        rootReplyId: Long,
        targetReplyId: Long
    ) {
        subReplyLoadJob = launchOwned {
            requests.getSortedSubCommentsForSubject(
                oid = target.oid,
                type = target.type,
                rootId = rootReplyId,
                mode = SubReplySortMode.TIME.apiMode,
                targetReplyId = targetReplyId
            ).onSuccess { data ->
                    ensureRequestOwned()
                if (_selectedCommentTarget.value != target) return@onSuccess
                showRoutedSubReply(data, rootReplyId, targetReplyId)
            }.onFailure { error ->
                    ensureRequestOwned()
                if (_selectedCommentTarget.value != target) return@onFailure
                _subReplyState.value = _subReplyState.value.copy(
                    isLoading = false,
                    error = error.message ?: "回复加载失败"
                )
            }
        }
    }

    private fun showRoutedSubReply(data: ReplyData, rootReplyId: Long, targetReplyId: Long) {
        val rootReply = resolveRoutedCommentRootReply(
            loadedReplies = emptyList(),
            remoteData = data,
            rootReplyId = rootReplyId
        )
        if (rootReply == null) {
            _subReplyState.value = _subReplyState.value.copy(
                isLoading = false,
                error = "回复可能已被删除或不可见"
            )
            return
        }

        val items = data.replies.orEmpty()
        val remoteTotalCount = resolveSubReplyRemoteTotalCount(
            data = data,
            rootReply = rootReply
        )
        val totalCount = resolveSubReplyLoadedTotalCount(
            rootReply = rootReply,
            loadedReplyCount = items.size,
            remoteReplyCount = remoteTotalCount
        )
        val isEnd = isSortedSubReplyPageEnd(data.cursor.isEnd, data.grpcNextOffset)
        _subReplyState.value = SubReplyUiState(
            visible = true,
            rootReply = rootReply,
            items = items.toImmutableList(),
            baseItems = items.toImmutableList(),
            totalCount = totalCount,
            isLoading = false,
            page = 1,
            basePage = 1,
            isEnd = isEnd,
            baseIsEnd = isEnd,
            grpcNextOffset = data.grpcNextOffset,
            baseGrpcNextOffset = data.grpcNextOffset,
            targetReplyId = targetReplyId.takeIf { it != rootReplyId } ?: 0L
        )
    }

    fun closeSubReply() {
        subReplyLoadJob?.cancel()
        _subReplyState.value = _subReplyState.value.copy(visible = false, isLoading = false)
    }

    fun setSubReplySortMode(mode: SubReplySortMode) {
        if (!isOwned()) return
        val state = _subReplyState.value
        val target = _selectedCommentTarget.value ?: return
        val root = state.rootReply ?: return
        if (!state.visible || state.sortMode == mode) return
        subReplyLoadJob?.cancel()
        _subReplyState.update { it.resetForSort(mode) }
        loadSubReplies(target.oid, target.type, root.rpid, page = 1, paginationOffset = null)
    }

    fun loadMoreSubReplies() {
        val state = _subReplyState.value
        val target = _selectedCommentTarget.value ?: return
        val rootReply = state.rootReply ?: return
        if (state.isLoading || state.isEnd) return
        val nextPage = if (state.error != null && state.items.isEmpty()) 1 else state.page + 1
        _subReplyState.value = state.copy(isLoading = true, error = null)
        loadSubReplies(
            oid = target.oid,
            type = target.type,
            rootId = rootReply.rpid,
            page = nextPage,
            paginationOffset = state.grpcNextOffset
        )
    }

    private fun loadSubReplies(
        oid: Long,
        type: Int,
        rootId: Long,
        page: Int,
        paginationOffset: String? = _subReplyState.value.grpcNextOffset
    ) {
        val sortMode = _subReplyState.value.sortMode
        val targetReplyId = _subReplyState.value.targetReplyId.takeIf { page == 1 } ?: 0L
        subReplyLoadJob?.cancel()
        subReplyLoadJob = launchOwned {
            val result = requests.getSortedSubCommentsForSubject(
                oid = oid,
                type = type,
                rootId = rootId,
                mode = sortMode.apiMode,
                targetReplyId = targetReplyId,
                paginationOffset = paginationOffset
            )
            result.onSuccess { data ->
                    ensureRequestOwned()
                val current = _subReplyState.value
                val target = _selectedCommentTarget.value
                if (!current.visible || current.rootReply?.rpid != rootId ||
                    target?.oid != oid || target?.type != type || current.sortMode != sortMode
                ) return@onSuccess
                val newItems = data.replies.orEmpty()
                val updatedItems = if (page == 1) {
                    newItems
                } else {
                    (current.items + newItems).distinctBy { it.rpid }
                }
                val remoteTotalCount = resolveSubReplyRemoteTotalCount(
                    data = data,
                    rootReply = current.rootReply
                )
                val totalCount = resolveSubReplyLoadedTotalCount(
                    rootReply = current.rootReply,
                    loadedReplyCount = updatedItems.size,
                    remoteReplyCount = remoteTotalCount,
                    previousTotalCount = current.totalCount
                )
                val isEnd = isSortedSubReplyPageEnd(data.cursor.isEnd, data.grpcNextOffset)
                _subReplyState.value = resolveDynamicSubReplyStateAfterSuccess(
                    currentState = current,
                    newItems = newItems,
                    page = page,
                    isEnd = isEnd,
                    totalCount = totalCount,
                    grpcNextOffset = data.grpcNextOffset
                )
            }.onFailure { error ->
                    ensureRequestOwned()
                val target = _selectedCommentTarget.value
                if (!_subReplyState.value.visible || _subReplyState.value.rootReply?.rpid != rootId ||
                    target?.oid != oid || target?.type != type
                ) return@onFailure
                _subReplyState.value = resolveDynamicSubReplyStateAfterFailure(
                    currentState = _subReplyState.value,
                    errorMessage = error.message ?: "回复加载失败"
                )
            }
        }
    }
    
    /**
     *  发表评论
     */
    fun startCommentReply(reply: com.android.purebilibili.data.model.response.ReplyItem) {
        if (!isOwned()) return
        _commentReplyTarget.value = resolveDynamicCommentReplyTarget(reply)
    }

    fun clearCommentReplyTarget() {
        if (!isOwned()) return
        _commentReplyTarget.value = null
    }

    fun postComment(dynamicId: String, message: String, onResult: (Boolean, String) -> Unit) {
        if (!isOwned()) return
        val replyTarget = _commentReplyTarget.value
        launchOwned {
            try {
                if (!requests.hasCsrf()) {
                    onResult(false, "请先登录")
                    return@launchOwned
                }
                val item = findDynamicById(dynamicId)
                if (item == null) {
                    onResult(false, "动态不存在")
                    return@launchOwned
                }
                val target = _selectedCommentTarget.value
                    ?: resolveDynamicCommentTargets(item).firstOrNull()
                if (target == null) {
                    onResult(false, "无法确定评论参数")
                    return@launchOwned
                }
                val response = requests.addCommentForSubject(
                    oid = target.oid,
                    type = target.type,
                    message = message,
                    root = replyTarget?.rootRpid ?: 0L,
                    parent = replyTarget?.parentRpid ?: 0L
                )
                ensureRequestOwned()
                if (response.isSuccess) {
                    _commentReplyTarget.value = null
                    onResult(true, "评论成功")
                    loadComments(dynamicId)
                } else {
                    onResult(false, response.exceptionOrNull()?.message ?: "评论失败")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureRequestOwned()
                onResult(false, e.message ?: "网络错误")
            }
        }
    }

    fun likeComment(rpid: Long, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!isOwned() || rpid <= 0L) return
        val current = _comments.value.firstOrNull { it.rpid == rpid }
            ?: _comments.value.firstNotNullOfOrNull { parent ->
                parent.replies.orEmpty().firstOrNull { it.rpid == rpid }
            }
            ?: _subReplyState.value.items.firstOrNull { it.rpid == rpid }
            ?: _subReplyState.value.rootReply?.takeIf { it.rpid == rpid }
        if (current == null) return
        val toLiked = !isDynamicCommentLiked(current)
        val target = _selectedCommentTarget.value
        if (target == null) {
            onResult(false, "无法确定评论参数")
            return
        }
        val mutationHolder = acquireReplyMutation(rpid) ?: return
        _comments.value = applyDynamicCommentLikeInList(_comments.value, rpid, toLiked)
        val subState = _subReplyState.value
        _subReplyState.value = subState.copy(
            rootReply = subState.rootReply?.let { root ->
                if (root.rpid == rpid) applyDynamicCommentLike(root, toLiked) else root
            },
            items = applyDynamicCommentLikeInList(subState.items, rpid, toLiked).toImmutableList()
        )
        val mutationJob = launchOwned {
            requests.likeCommentForSubject(
                oid = target.oid,
                type = target.type,
                rpid = rpid,
                like = toLiked
            ).onFailure { error ->
                    ensureRequestOwned()
                _comments.value = applyDynamicCommentLikeInList(_comments.value, rpid, !toLiked)
                val rollback = _subReplyState.value
                _subReplyState.value = rollback.copy(
                    rootReply = rollback.rootReply?.let { root ->
                        if (root.rpid == rpid) applyDynamicCommentLike(root, !toLiked) else root
                    },
                    items = applyDynamicCommentLikeInList(rollback.items, rpid, !toLiked).toImmutableList()
                )
                onResult(false, error.message ?: "操作失败")
            }.onSuccess {
                    ensureRequestOwned()
                onResult(true, if (toLiked) "已点赞" else "已取消")
            }
        }
        mutationJob.invokeOnCompletion { replyMutationHolders.remove(rpid, mutationHolder) }
    }

    fun hateComment(rpid: Long, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (!isOwned() || rpid <= 0L) return
        val current = _comments.value.firstNotNullOfOrNull { reply ->
            findDynamicComment(reply, rpid)
        } ?: _subReplyState.value.items.firstOrNull { it.rpid == rpid }
            ?: _subReplyState.value.rootReply?.takeIf { it.rpid == rpid }
            ?: return
        val target = _selectedCommentTarget.value
        if (target == null) {
            onResult(false, "无法确定评论参数")
            return
        }
        val mutationHolder = acquireReplyMutation(rpid) ?: return
        val toHated = !isDynamicCommentHated(current)
        _comments.value = applyDynamicCommentHateInList(_comments.value, rpid, toHated)
        val subState = _subReplyState.value
        _subReplyState.value = subState.copy(
            rootReply = subState.rootReply?.let { root ->
                if (root.rpid == rpid) applyDynamicCommentHate(root, toHated) else root
            },
            items = applyDynamicCommentHateInList(subState.items, rpid, toHated).toImmutableList(),
        )
        val mutationJob = launchOwned {
            requests.hateCommentForSubject(
                oid = target.oid,
                type = target.type,
                rpid = rpid,
                hate = toHated,
            ).fold(
                onSuccess = {
                    ensureRequestOwned()
 onResult(true, if (toHated) "点踩成功" else "已取消点踩") },
                onFailure = { error ->
                    ensureRequestOwned()

                    _comments.value = replaceDynamicCommentInList(_comments.value, current)
                    val rollback = _subReplyState.value
                    _subReplyState.value = rollback.copy(
                        rootReply = rollback.rootReply?.let { root ->
                            if (root.rpid == rpid) current else root
                        },
                        items = replaceDynamicCommentInList(rollback.items, current).toImmutableList(),
                    )
                    onResult(false, error.message ?: "点踩失败")
                },
            )
        }
        mutationJob.invokeOnCompletion { replyMutationHolders.remove(rpid, mutationHolder) }
    }

    private fun findDynamicComment(
        reply: com.android.purebilibili.data.model.response.ReplyItem,
        rpid: Long,
    ): com.android.purebilibili.data.model.response.ReplyItem? {
        if (reply.rpid == rpid) return reply
        return reply.replies.orEmpty().firstNotNullOfOrNull { findDynamicComment(it, rpid) }
    }

    fun deleteDynamicComment(rpid: Long, onResult: (Boolean, String) -> Unit) {
        if (!isOwned()) return
        _subReplyState.update { it.copy(dissolvingIds = (it.dissolvingIds - rpid).toImmutableSet()) }
        val target = _selectedCommentTarget.value
        if (target == null || rpid <= 0L) {
            onResult(false, "无法确定评论参数")
            return
        }
        launchOwned {
            requests.deleteCommentForSubject(
                oid = target.oid,
                type = target.type,
                rpid = rpid,
            ).fold(
                onSuccess = {
                    ensureRequestOwned()

                    _comments.value = removeDynamicCommentFromList(_comments.value, rpid)
                    val subState = _subReplyState.value
                    _subReplyState.value = if (subState.rootReply?.rpid == rpid) {
                        SubReplyUiState()
                    } else {
                        subState.copy(
                            items = subState.items.filterNot { it.rpid == rpid }.toImmutableList(),
                            totalCount = (subState.totalCount - 1).coerceAtLeast(0),
                        )
                    }
                    _commentTotalCount.value = (_commentTotalCount.value - 1).coerceAtLeast(0)
                    onResult(true, "评论已删除")
                },
                onFailure = { ensureRequestOwned()
                    onResult(false, it.message ?: "删除失败") },
            )
        }
    }

    fun toggleDynamicCommentTop(
        reply: com.android.purebilibili.data.model.response.ReplyItem,
        onResult: (Boolean, String) -> Unit,
    ) {
        val target = _selectedCommentTarget.value
        if (target == null || reply.rpid <= 0L) {
            onResult(false, "无法确定评论参数")
            return
        }
        launchOwned {
            requests.setCommentTopForSubject(
                oid = target.oid,
                type = target.type,
                rpid = reply.rpid,
                isCurrentlyTop = reply.replyControl?.isUpTop == true,
            ).fold(
                onSuccess = {
                    ensureRequestOwned()

                    _selectedDynamic.value?.id_str?.takeIf(String::isNotBlank)?.let(::loadComments)
                    onResult(true, if (reply.replyControl?.isUpTop == true) "已取消置顶" else "已置顶")
                },
                onFailure = { ensureRequestOwned()
                    onResult(false, it.message ?: "置顶操作失败") },
            )
        }
    }

    fun reportDynamicComment(
        rpid: Long,
        reason: Int,
        onResult: (Boolean, String) -> Unit,
    ) {
        val target = _selectedCommentTarget.value
        if (target == null || rpid <= 0L) {
            onResult(false, "无法确定评论参数")
            return
        }
        launchOwned {
            requests.reportCommentForSubject(
                oid = target.oid,
                type = target.type,
                rpid = rpid,
                reason = reason,
            ).fold(
                onSuccess = {
                    ensureRequestOwned()
 onResult(true, "举报成功") },
                onFailure = { ensureRequestOwned()
                    onResult(false, it.message ?: "举报失败") },
            )
        }
    }

    private fun removeDynamicCommentFromList(
        comments: List<com.android.purebilibili.data.model.response.ReplyItem>,
        rpid: Long,
    ): List<com.android.purebilibili.data.model.response.ReplyItem> = comments.mapNotNull { reply ->
        if (reply.rpid == rpid) {
            null
        } else {
            reply.copy(
                replies = reply.replies?.filterNot { child -> child.rpid == rpid },
            )
        }
    }
    
    /**
     *  点赞动态
     */

fun startSubDissolve(rpid: Long) {
    if (!isOwned() || rpid <= 0L) return
    val current = _subReplyState.value
    _subReplyState.value = current.copy(dissolvingIds = (current.dissolvingIds + rpid).toImmutableSet())
}

}
