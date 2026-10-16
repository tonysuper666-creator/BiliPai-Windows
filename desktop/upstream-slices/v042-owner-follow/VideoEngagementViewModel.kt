package com.android.purebilibili.feature.video.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.purebilibili.core.store.SettingsManager
import com.android.purebilibili.core.store.TokenManager
import com.android.purebilibili.core.util.EasterEggs
import com.android.purebilibili.core.network.NetworkModule
import com.android.purebilibili.feature.video.ui.feedback.resolveTripleActionFeedbackMessage
import com.android.purebilibili.feature.video.ui.feedback.resolveTripleActionVisualState
import com.android.purebilibili.feature.video.ui.feedback.canSubmitVideoFollowAction
import com.android.purebilibili.feature.video.ui.feedback.canSubmitVideoTripleAction
import com.android.purebilibili.feature.video.usecase.TripleActionResult
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class VideoEngagementSeed(
    val isLoggedIn: Boolean = false,
    val isVip: Boolean = false,
    val isFollowing: Boolean = false,
    val isFavorited: Boolean = false,
    val isLiked: Boolean = false,
    val likeStatusKnown: Boolean = false,
    val followStatusKnown: Boolean = false,
    val isDisliked: Boolean = false,
    val likeCount: Int = 0,
    val coinCount: Int = 0,
    val favoriteCount: Int = 0,
    val isInWatchLater: Boolean = false,
    val isRepost: Boolean = false,
    val followingMids: Set<Long> = emptySet()
)

enum class VideoMaidAction { DISLIKE, SHARE, COIN }

data class VideoEngagementUiState(
    val subject: VideoSubjectSnapshot? = null,
    val isLoggedIn: Boolean = false,
    val isVip: Boolean = false,
    val isFollowing: Boolean = false,
    val isFavorited: Boolean = false,
    val isLiked: Boolean = false,
    val likeStatusKnown: Boolean = false,
    val followStatusKnown: Boolean = false,
    val isFollowSubmitting: Boolean = false,
    val isTripleSubmitting: Boolean = false,
    val isDisliked: Boolean = false,
    val likeCount: Int = 0,
    val coinCount: Int = 0,
    val favoriteCount: Int = 0,
    val isInWatchLater: Boolean = false,
    val isRepost: Boolean = false,
    val followingMids: Set<Long> = emptySet(),
    val userCoinBalance: Double? = null,
    val coinDialogVisible: Boolean = false,
    val likeBurstVisible: Boolean = false,
    // 每次成功点赞自增；overlay 以此为 key 重挂动画，连续点赞也能重播
    val likeBurstId: Long = 0L,
    val maidAction: VideoMaidAction? = null,
    val maidActionId: Long = 0L,
    val tripleCelebrationVisible: Boolean = false,
    val tripleCelebrationId: Long = 0L,
    val tripleCelebrationFinished: Boolean = false
) {
    /** 投币上限：原创 2 币，转载服务端强制 1 币。 */
    val coinLimit: Int get() = if (isRepost) 1 else 2
}

sealed interface VideoEngagementEvent {
    data class Message(val text: String) : VideoEngagementEvent
    data class OpenFollowGroups(val mid: Long) : VideoEngagementEvent
    data class LoadVideo(val bvid: String) : VideoEngagementEvent
    data object InvalidateFavoriteFolders : VideoEngagementEvent
}

fun interface VideoCoinBalanceLoader {
    suspend fun load(): Double
}

private val DefaultVideoCoinBalanceLoader = VideoCoinBalanceLoader {
    if (TokenManager.sessDataCache.isNullOrEmpty()) {
        -4.0
    } else {
        try {
            val response = withContext(Dispatchers.IO) {
                withTimeout(5_000L) { NetworkModule.api.getNavInfo() }
            }
            when {
                response.code == 0 && response.data?.isLogin == true -> requireNotNull(response.data).money
                response.code == 0 -> -3.0
                else -> -1.0
            }
        } catch (_: TimeoutCancellationException) {
            -2.0
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            -2.0
        }
    }
}

interface VideoEngagementActions {
    suspend fun queryLikeStatus(aid: Long): Result<Boolean>
    suspend fun queryFollowStatus(mid: Long): Result<Boolean>
    suspend fun toggleFollow(mid: Long, currentlyFollowing: Boolean): Result<Boolean>
    suspend fun toggleLike(aid: Long, currentlyLiked: Boolean, bvid: String): Result<Boolean>
    suspend fun toggleDislike(aid: Long, currentlyDisliked: Boolean, bvid: String): Result<Boolean>
    suspend fun toggleFavorite(aid: Long, currentlyFavorited: Boolean, bvid: String): Result<Boolean>
    suspend fun toggleWatchLater(aid: Long, currentlyInWatchLater: Boolean, bvid: String): Result<Boolean>
    suspend fun doCoin(aid: Long, count: Int, alsoLike: Boolean, bvid: String): Result<Boolean>
    suspend fun doTripleAction(aid: Long, coinCount: Int): Result<TripleActionResult>
}

private class DefaultVideoEngagementActions(
    private val useCase: VideoInteractionUseCase = VideoInteractionUseCase()
) : VideoEngagementActions {
    override suspend fun queryLikeStatus(aid: Long) =
        com.android.purebilibili.data.repository.ActionRepository.queryLikeStatus(aid)

    override suspend fun queryFollowStatus(mid: Long) =
        com.android.purebilibili.data.repository.ActionRepository.queryFollowStatus(mid)

    override suspend fun toggleFollow(mid: Long, currentlyFollowing: Boolean) =
        useCase.toggleFollow(mid, currentlyFollowing)

    override suspend fun toggleLike(aid: Long, currentlyLiked: Boolean, bvid: String) =
        useCase.toggleLike(aid, currentlyLiked, bvid)

    override suspend fun toggleDislike(aid: Long, currentlyDisliked: Boolean, bvid: String) =
        useCase.toggleDislike(aid, currentlyDisliked, bvid)

    override suspend fun toggleFavorite(aid: Long, currentlyFavorited: Boolean, bvid: String) =
        useCase.toggleFavorite(aid, currentlyFavorited, bvid)

    override suspend fun toggleWatchLater(aid: Long, currentlyInWatchLater: Boolean, bvid: String) =
        useCase.toggleWatchLater(aid, currentlyInWatchLater, bvid)

    override suspend fun doCoin(aid: Long, count: Int, alsoLike: Boolean, bvid: String) =
        useCase.doCoin(aid, count, alsoLike, bvid)

    override suspend fun doTripleAction(aid: Long, coinCount: Int) =
        useCase.doTripleAction(aid, coinCount)
}

class VideoEngagementViewModel(
    private val actions: VideoEngagementActions = DefaultVideoEngagementActions(),
    private val coinBalanceLoader: VideoCoinBalanceLoader = DefaultVideoCoinBalanceLoader,
    private val tripleJumpEnabled: suspend (Context?) -> Boolean = { context ->
        context != null && SettingsManager.getTripleJumpEnabled(context).first()
    }
) : ViewModel() {
    private val _uiState = MutableStateFlow(VideoEngagementUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<VideoEngagementEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var appContext: Context? = null
    private var locallyModifiedFields: Set<VideoEngagementField> = emptySet()
    private var tripleCelebrationSequence = 0L
    // 点赞切换排队锁：保证快速连点时每次点击都基于最新状态交替生效
    private val likeToggleMutex = Mutex()
    private var likeStatusRecoveryJob: Job? = null
    private val followToggleMutex = Mutex()
    private var ownerFollowRequests = 0
    private var boundSession: Any = interactionSession()
    private var likeRevision = 0L
    private var followRevision = 0L

    private fun interactionSession(): Any = TokenManager.accountIdentity.value to TokenManager.sessDataCache

    private fun isCurrent(state: VideoEngagementUiState, session: Any): Boolean =
        _uiState.value.subject == state.subject && boundSession == session && interactionSession() == session

    private fun Throwable.rethrowCancellation() {
        if (this is CancellationException) throw this
    }

    private enum class VideoEngagementField {
        FOLLOWING,
        FAVORITE,
        LIKE,
        DISLIKE,
        COIN,
        WATCH_LATER,
        FOLLOWING_MIDS
    }

    fun initWithContext(context: Context) {
        appContext = context.applicationContext
    }

    fun bindSubject(subject: VideoSubjectSnapshot, seed: VideoEngagementSeed) {
        if (boundSession == interactionSession() && !shouldRebindVideoDomain(_uiState.value.subject, subject)) {
            sync(seed)
            return
        }
        likeStatusRecoveryJob?.cancel()
        likeStatusRecoveryJob = null
        boundSession = interactionSession()
        ++likeRevision
        ++followRevision
        locallyModifiedFields = emptySet()
        ownerFollowRequests = 0
        val previous = _uiState.value
        _uiState.value = VideoEngagementUiState(
            subject = subject,
            likeBurstId = previous.likeBurstId,
            maidActionId = previous.maidActionId,
            isLoggedIn = seed.isLoggedIn,
            isVip = seed.isVip,
            isFollowing = seed.isFollowing,
            isFavorited = seed.isFavorited,
            isLiked = seed.isLiked,
            likeStatusKnown = seed.likeStatusKnown,
            followStatusKnown = seed.followStatusKnown,
            isDisliked = seed.isDisliked,
            likeCount = seed.likeCount,
            coinCount = seed.coinCount,
            favoriteCount = seed.favoriteCount,
            isInWatchLater = seed.isInWatchLater,
            isRepost = seed.isRepost,
            followingMids = seed.followingMids
        )
    }

    fun sync(seed: VideoEngagementSeed) {
        _uiState.update { current ->
            current.copy(
                isLoggedIn = seed.isLoggedIn,
                isVip = seed.isVip,
                isFollowing = if (VideoEngagementField.FOLLOWING in locallyModifiedFields) current.isFollowing else seed.isFollowing,
                isFavorited = if (VideoEngagementField.FAVORITE in locallyModifiedFields) current.isFavorited else seed.isFavorited,
                favoriteCount = if (VideoEngagementField.FAVORITE in locallyModifiedFields) current.favoriteCount else seed.favoriteCount,
                isLiked = if (VideoEngagementField.LIKE in locallyModifiedFields) current.isLiked else seed.isLiked,
                likeStatusKnown = if (VideoEngagementField.LIKE in locallyModifiedFields) current.likeStatusKnown else seed.likeStatusKnown,
                followStatusKnown = if (VideoEngagementField.FOLLOWING in locallyModifiedFields) current.followStatusKnown else seed.followStatusKnown,
                isDisliked = if (VideoEngagementField.DISLIKE in locallyModifiedFields) current.isDisliked else seed.isDisliked,
                likeCount = if (VideoEngagementField.LIKE in locallyModifiedFields) current.likeCount else seed.likeCount,
                coinCount = if (VideoEngagementField.COIN in locallyModifiedFields) current.coinCount else seed.coinCount,
                isRepost = seed.isRepost,
                followingMids = if (VideoEngagementField.FOLLOWING_MIDS in locallyModifiedFields) current.followingMids else seed.followingMids
            )
        }
    }

    fun setCoinDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(coinDialogVisible = visible) }
    }

    fun openCoinDialog() {
        val state = _uiState.value
        if (state.coinCount >= state.coinLimit) {
            emitMessage(if (state.isRepost) "转载视频最多投1个硬币" else "已投满2个硬币")
            return
        }
        _uiState.update { it.copy(coinDialogVisible = true, userCoinBalance = null) }
        viewModelScope.launch {
            val balance = coinBalanceLoader.load()
            _uiState.update { current ->
                if (current.coinDialogVisible) current.copy(userCoinBalance = balance) else current
            }
        }
    }

    fun toggleFollow(mid: Long? = null, currentlyFollowing: Boolean? = null) {
        changeFollow(mid, followOnly = false)
    }

    /** Attention cards only request following; they never toggle an existing follow off. */
    fun followOwner() {
        changeFollow(mid = null, followOnly = true)
    }

    private fun changeFollow(mid: Long?, followOnly: Boolean) {
        val state = _uiState.value
        val targetMid = mid ?: state.subject?.ownerMid ?: return
        val isOwner = targetMid == state.subject?.ownerMid
        // 普通关注入口可重查未知状态；指令卡片仍由 followOnly 的已知状态门禁保护。
        if (isOwner && !state.followStatusKnown && ownerFollowRequests > 0) return
        if (followOnly && !canSubmitVideoFollowAction(
                statusKnown = state.followStatusKnown,
                isFollowing = state.isFollowing,
                isSubmitting = state.isFollowSubmitting,
            )
        ) return
        val session = interactionSession()
        if (isOwner && ownerFollowRequests++ == 0) {
            _uiState.update { it.copy(isFollowSubmitting = true) }
        }
        viewModelScope.launch {
            try {
                followToggleMutex.withLock {
                    if (!isCurrent(state, session)) return@withLock
                    val latest = _uiState.value
                    if (isOwner && !latest.followStatusKnown) {
                        val following = queryVideoInteractionStatusWithRetry(
                            isCurrent = { isCurrent(state, session) },
                            query = { actions.queryFollowStatus(targetMid) },
                        ).getOrElse {
                            it.rethrowCancellation()
                            if (isCurrent(state, session)) emitMessage("关注状态获取失败，请稍后重试")
                            return@withLock
                        }
                        if (!isCurrent(state, session)) return@withLock
                        // 查询恢复后即使切换失败，也不让旧的未知 seed 覆盖真实关注状态。
                        locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FOLLOWING +
                            VideoEngagementField.FOLLOWING_MIDS
                        _uiState.update { current ->
                            current.copy(
                                isFollowing = following,
                                followStatusKnown = true,
                                followingMids = if (following) current.followingMids + targetMid else current.followingMids - targetMid,
                            )
                        }
                    }
                    val wasFollowing = if (isOwner) _uiState.value.isFollowing else {
                        com.android.purebilibili.data.repository.ActionRepository.cachedFollowStatus(targetMid)
                            ?: actions.queryFollowStatus(targetMid)
                                .getOrElse {
                                    it.rethrowCancellation()
                                    if (isCurrent(state, session)) emitMessage("关注状态尚未确认，请稍后重试")
                                    return@withLock
                                }
                    }
                    if (!isCurrent(state, session) || (followOnly && wasFollowing)) return@withLock
                    val revision = ++followRevision
                    actions.toggleFollow(targetMid, if (followOnly) false else wasFollowing)
                        .onSuccess { following ->
                            if (!isCurrent(state, session) || revision != followRevision) return@onSuccess
                            locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FOLLOWING_MIDS
                            if (isOwner) {
                                locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FOLLOWING
                            }
                            _uiState.update { current ->
                                current.copy(
                                    isFollowing = if (isOwner) following else current.isFollowing,
                                    followStatusKnown = current.followStatusKnown || isOwner,
                                    followingMids = if (following) {
                                        current.followingMids + targetMid
                                    } else {
                                        current.followingMids - targetMid
                                    }
                                )
                            }
                            emitMessage(if (following) "关注成功" else "已取消关注")
                            if (following) _events.send(VideoEngagementEvent.OpenFollowGroups(targetMid))
                        }
                        .onFailure {
                            it.rethrowCancellation()
                            if (isCurrent(state, session)) emitMessage(it.message ?: "操作失败")
                        }
                }
            } finally {
                if (isOwner && isCurrent(state, session) && --ownerFollowRequests == 0) {
                    _uiState.update { it.copy(isFollowSubmitting = false) }
                }
            }
        }
    }

    fun toggleLike(
        aid: Long? = null,
        bvid: String? = null,
        currentlyLiked: Boolean? = null,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val state = _uiState.value
        val targetAid = aid ?: state.subject?.aid ?: return
        val targetBvid = bvid ?: state.subject?.bvid ?: return
        if (targetAid != state.subject?.aid || targetBvid != state.subject?.bvid) return
        if (!state.likeStatusKnown && likeStatusRecoveryJob?.isActive == true) return
        val session = interactionSession()
        val likeJob = viewModelScope.launch {
            // 串行化点赞切换：快速连点时排队执行，且每次执行前重读最新
            // isLiked，避免两个在途请求基于同一个过期状态算出相同目标，
            // 导致某次点击"吞掉"（无状态变化、无动画）。
            likeToggleMutex.withLock {
                if (!isCurrent(state, session)) return@withLock
                if (!_uiState.value.likeStatusKnown) {
                    val liked = queryVideoInteractionStatusWithRetry(
                        isCurrent = { isCurrent(state, session) },
                        query = { actions.queryLikeStatus(targetAid) },
                    ).getOrElse {
                        it.rethrowCancellation()
                        if (isCurrent(state, session)) emitMessage("点赞状态获取失败，请稍后重试")
                        return@withLock
                    }
                    if (!isCurrent(state, session)) return@withLock
                    // A deferred seed must not reset a status recovered by this action.
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.LIKE
                    _uiState.update { current ->
                        current.copy(isLiked = liked, likeStatusKnown = true)
                    }
                }
                val revision = ++likeRevision
                val wasLiked = _uiState.value.isLiked
                actions.toggleLike(targetAid, wasLiked, targetBvid)
                    .onSuccess { liked ->
                        if (!isCurrent(state, session) || revision != likeRevision) return@onSuccess
                        locallyModifiedFields = locallyModifiedFields + VideoEngagementField.LIKE +
                            if (liked) setOf(VideoEngagementField.DISLIKE) else emptySet()
                        _uiState.update { current ->
                            if (current.subject?.generation != state.subject?.generation) current
                            else current.copy(
                                isLiked = liked,
                                likeStatusKnown = true,
                                // 点赞与点踩互斥：点赞时本地静默清除点踩（对齐 PiliPlus）
                                isDisliked = if (liked) false else current.isDisliked,
                                likeCount = (current.likeCount + if (liked == current.isLiked) 0 else if (liked) 1 else -1)
                                    .coerceAtLeast(0),
                                likeBurstVisible = liked,
                                maidAction = null,
                                likeBurstId = if (liked) current.likeBurstId + 1 else current.likeBurstId
                            )
                        }
                        onResult?.invoke(liked)
                        val easterEggEnabled = appContext?.let(SettingsManager::isEasterEggEnabledSync) == true
                        emitMessage(
                            if (liked && easterEggEnabled) EasterEggs.getLikeMessage()
                            else if (liked) "已点赞" else "已取消点赞"
                        )
                    }
                    .onFailure {
                        it.rethrowCancellation()
                        if (isCurrent(state, session)) emitMessage(it.message ?: "操作失败")
                    }
            }
        }
        if (!state.likeStatusKnown) likeStatusRecoveryJob = likeJob
    }

    fun toggleDislike(
        aid: Long? = null,
        bvid: String? = null,
        currentlyDisliked: Boolean? = null
    ) {
        val state = _uiState.value
        val targetAid = aid ?: state.subject?.aid ?: return
        val targetBvid = bvid ?: state.subject?.bvid ?: return
        val wasDisliked = currentlyDisliked ?: state.isDisliked
        viewModelScope.launch {
            actions.toggleDislike(targetAid, wasDisliked, targetBvid)
                .onSuccess { disliked ->
                    if (_uiState.value.subject?.generation != state.subject?.generation) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.DISLIKE +
                        if (disliked) setOf(VideoEngagementField.LIKE) else emptySet()
                    _uiState.update { current ->
                        if (current.subject?.generation != state.subject?.generation) current
                        else current.copy(
                            isDisliked = disliked,
                            likeBurstVisible = false,
                            maidAction = if (disliked) VideoMaidAction.DISLIKE else null,
                            maidActionId = if (disliked) current.maidActionId + 1 else current.maidActionId,
                            // 点踩与点赞互斥：点踩时本地静默取消点赞（对齐 PiliPlus）
                            isLiked = if (disliked) false else current.isLiked,
                            likeCount = if (disliked && current.isLiked) {
                                (current.likeCount - 1).coerceAtLeast(0)
                            } else {
                                current.likeCount
                            }
                        )
                    }
                    emitMessage(if (disliked) "已点踩" else "已取消点踩")
                }
                .onFailure { emitMessage(it.message ?: "操作失败") }
        }
    }

    fun toggleFavorite() {
        val state = _uiState.value
        val subject = state.subject ?: return
        viewModelScope.launch {
            actions.toggleFavorite(subject.aid, state.isFavorited, subject.bvid)
                .onSuccess { favorited ->
                    if (_uiState.value.subject?.generation != subject.generation) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FAVORITE
                    _uiState.update { current ->
                        if (current.subject?.generation == subject.generation) {
                            current.copy(
                                isFavorited = favorited,
                                favoriteCount = (
                                    current.favoriteCount +
                                        if (favorited == current.isFavorited) 0 else if (favorited) 1 else -1
                                    ).coerceAtLeast(0)
                            )
                        } else current
                    }
                    _events.send(VideoEngagementEvent.InvalidateFavoriteFolders)
                    emitMessage(if (favorited) "已收藏" else "已取消收藏")
                }
                .onFailure { emitMessage(it.message ?: "收藏操作失败") }
        }
    }

    fun toggleWatchLater() {
        val state = _uiState.value
        val subject = state.subject ?: return
        viewModelScope.launch {
            actions.toggleWatchLater(subject.aid, state.isInWatchLater, subject.bvid)
                .onSuccess { inWatchLater ->
                    if (_uiState.value.subject?.generation != subject.generation) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.WATCH_LATER
                    _uiState.update { current ->
                        if (current.subject?.generation == subject.generation) {
                            current.copy(isInWatchLater = inWatchLater)
                        } else current
                    }
                    emitMessage(if (inWatchLater) "已添加到稍后再看" else "已从稍后再看移除")
                }
                .onFailure { emitMessage(it.message ?: "操作失败") }
        }
    }

    fun doCoin(count: Int, alsoLike: Boolean) {
        val state = _uiState.value
        val subject = state.subject ?: return
        val session = interactionSession()
        if (alsoLike && !state.likeStatusKnown) {
            emitMessage("点赞状态尚未确认，请取消附带点赞后重试")
            return
        }
        setCoinDialogVisible(false)
        viewModelScope.launch {
            likeToggleMutex.withLock {
            if (!isCurrent(state, session) || (alsoLike && !_uiState.value.likeStatusKnown)) return@withLock
            actions.doCoin(subject.aid, count, alsoLike, subject.bvid)
                .onSuccess {
                    if (!isCurrent(state, session)) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.COIN +
                        if (alsoLike) setOf(VideoEngagementField.LIKE, VideoEngagementField.DISLIKE) else emptySet()
                    _uiState.update { current ->
                        if (current.subject?.generation != subject.generation) current
                        else current.copy(
                            coinCount = minOf(current.coinCount + count, current.coinLimit),
                            likeBurstVisible = false,
                            maidAction = VideoMaidAction.COIN,
                            maidActionId = current.maidActionId + 1,
                            isLiked = current.isLiked || alsoLike,
                            likeStatusKnown = current.likeStatusKnown || alsoLike,
                            likeCount = current.likeCount + if (alsoLike && current.likeStatusKnown && !current.isLiked) 1 else 0,
                            isDisliked = if (alsoLike) false else current.isDisliked
                        )
                    }
                    val easterEggEnabled = appContext?.let(SettingsManager::isEasterEggEnabledSync) == true
                    emitMessage(if (easterEggEnabled) EasterEggs.getCoinMessage() else "投币成功")
                }
                .onFailure {
                    it.rethrowCancellation()
                    if (isCurrent(state, session)) emitMessage(it.message ?: "投币失败")
                }
            }
        }
    }

    fun doTripleAction(
        aid: Long? = null,
        bvid: String? = null,
        currentLiked: Boolean? = null,
        currentCoinCount: Int? = null,
        currentFavorited: Boolean? = null,
        onResult: ((TripleActionResult) -> Unit)? = null
    ) {
        val state = _uiState.value
        val targetAid = aid ?: state.subject?.aid ?: return
        val targetBvid = bvid ?: state.subject?.bvid ?: return
        if (targetAid != state.subject?.aid || targetBvid != state.subject?.bvid) return
        if (!state.likeStatusKnown) {
            emitMessage("点赞状态尚未确认，请稍后重试")
            return
        }
        if (!canSubmitVideoTripleAction(
                statusKnown = state.likeStatusKnown,
                isLiked = state.isLiked,
                isCoined = state.coinCount > 0,
                isFavorited = state.isFavorited,
                isSubmitting = state.isTripleSubmitting,
            )
        ) return
        val session = interactionSession()
        _uiState.update { it.copy(isTripleSubmitting = true) }
        viewModelScope.launch {
            try {
            likeToggleMutex.withLock {
            if (!isCurrent(state, session) || !_uiState.value.likeStatusKnown) return@withLock
            val latest = _uiState.value
            if (!canSubmitVideoTripleAction(
                    statusKnown = latest.likeStatusKnown,
                    isLiked = latest.isLiked,
                    isCoined = latest.coinCount > 0,
                    isFavorited = latest.isFavorited,
                    isSubmitting = false,
                )
            ) return@withLock
            val actionState = _uiState.value
            emitMessage("正在三连")
            actions.doTripleAction(targetAid, actionState.coinLimit)
                .onSuccess { result ->
                    if (!isCurrent(state, session)) return@onSuccess
                    val visual = resolveTripleActionVisualState(
                        currentLiked = actionState.isLiked,
                        currentCoinCount = actionState.coinCount,
                        currentFavorited = actionState.isFavorited,
                        likeSuccess = result.likeSuccess || result.coinSuccess,
                        coinSuccess = result.coinSuccess,
                        coinFailureMessage = result.coinMessage,
                        favoriteSuccess = result.favoriteSuccess,
                        attemptedCoinCount = actionState.coinLimit
                    )
                    val celebrationId = ++tripleCelebrationSequence
                    _uiState.update { current ->
                        if (current.subject?.generation != state.subject?.generation) current
                        else current.copy(
                            isLiked = visual.isLiked,
                            likeStatusKnown = current.likeStatusKnown || result.likeSuccess || result.coinSuccess,
                            // 三连含点赞，点踩态随之清除（对齐 PiliPlus）
                            isDisliked = if (visual.isLiked) false else current.isDisliked,
                            likeCount = (
                                current.likeCount +
                                    if (visual.isLiked == current.isLiked) 0 else if (visual.isLiked) 1 else -1
                                ).coerceAtLeast(0),
                            coinCount = visual.coinCount,
                            isFavorited = visual.isFavorited,
                            favoriteCount = (
                                current.favoriteCount +
                                    if (visual.isFavorited == current.isFavorited) 0 else if (visual.isFavorited) 1 else -1
                                ).coerceAtLeast(0),
                            tripleCelebrationVisible = result.allSuccess,
                            maidAction = null,
                            likeBurstVisible = false,
                            tripleCelebrationId = celebrationId,
                            tripleCelebrationFinished = false
                        )
                    }
                    locallyModifiedFields = locallyModifiedFields + buildSet {
                        if (result.likeSuccess || result.coinSuccess) {
                            add(VideoEngagementField.LIKE)
                            add(VideoEngagementField.DISLIKE)
                        }
                        if (result.coinSuccess) add(VideoEngagementField.COIN)
                        if (result.favoriteSuccess) add(VideoEngagementField.FAVORITE)
                    }
                    if (result.favoriteSuccess) {
                        _events.send(VideoEngagementEvent.InvalidateFavoriteFolders)
                    }
                    onResult?.invoke(result)
                    emitMessage(
                        resolveTripleActionFeedbackMessage(
                            result.likeSuccess,
                            result.coinSuccess,
                            result.favoriteSuccess,
                            result.coinMessage
                        )
                    )
                    if (result.allSuccess && tripleJumpEnabled(appContext)) {
                        viewModelScope.launch {
                        // 庆祝等待不占用点赞锁；跳转前仍核对主体、会话和庆祝编号。
                        val completed = withTimeoutOrNull(5_000L) {
                            _uiState.first {
                                it.subject?.generation != state.subject?.generation ||
                                    it.tripleCelebrationId != celebrationId || !it.tripleCelebrationVisible
                            }
                        }
                        if (completed == null) {
                            cancelTripleCelebration(celebrationId)
                        } else if (isCurrent(state, session) && completed.subject == state.subject &&
                            completed.tripleCelebrationId == celebrationId && completed.tripleCelebrationFinished
                        ) {
                            _events.send(VideoEngagementEvent.LoadVideo("BV1JsK5eyEuB"))
                        }
                        }
                    }
                }
                .onFailure {
                    it.rethrowCancellation()
                    if (isCurrent(state, session)) emitMessage(it.message ?: "三连失败")
                }
            }
            } finally {
                if (isCurrent(state, session)) {
                    _uiState.update { it.copy(isTripleSubmitting = false) }
                }
            }
        }
    }

    fun dismissLikeBurst(expectedId: Long? = null) {
        _uiState.update {
            if (expectedId == null || it.likeBurstId == expectedId) it.copy(likeBurstVisible = false) else it
        }
    }

    fun showShareFeedback(bvid: String) {
        _uiState.update {
            if (it.subject?.bvid != bvid) it else it.copy(
                likeBurstVisible = false,
                maidAction = VideoMaidAction.SHARE,
                maidActionId = it.maidActionId + 1
            )
        }
    }

    fun dismissMaidAction(expectedId: Long) {
        _uiState.update {
            if (it.maidActionId == expectedId) it.copy(maidAction = null) else it
        }
    }

    fun dismissTripleCelebration() {
        cancelTripleCelebration(_uiState.value.tripleCelebrationId)
    }

    fun completeTripleCelebration(celebrationId: Long) {
        _uiState.update {
            if (it.tripleCelebrationId == celebrationId) {
                it.copy(tripleCelebrationVisible = false, tripleCelebrationFinished = true)
            } else it
        }
    }

    fun cancelTripleCelebration(celebrationId: Long) {
        _uiState.update {
            if (it.tripleCelebrationId == celebrationId) {
                it.copy(tripleCelebrationVisible = false, tripleCelebrationFinished = false)
            } else it
        }
    }

    fun applyFavoriteFolderResult(isFavorited: Boolean) {
        locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FAVORITE
        _uiState.update { current ->
            current.copy(
                isFavorited = isFavorited,
                favoriteCount = (
                    current.favoriteCount +
                        if (isFavorited == current.isFavorited) 0 else if (isFavorited) 1 else -1
                    ).coerceAtLeast(0)
            )
        }
    }

    internal fun emitMessage(message: String) {
        viewModelScope.launch { _events.send(VideoEngagementEvent.Message(message)) }
    }
}

internal fun VideoPlaybackUiState.Success.toEngagementSeed(): VideoEngagementSeed =
    VideoEngagementSeed(
        isLoggedIn = !TokenManager.sessDataCache.isNullOrEmpty(),
        isVip = TokenManager.isVipCache,
        isFollowing = isFollowing,
        isFavorited = isFavorited,
        isLiked = isLiked,
        likeStatusKnown = likeStatusKnown,
        followStatusKnown = followStatusKnown,
        isDisliked = isDisliked,
        likeCount = info.stat.like,
        coinCount = coinCount,
        favoriteCount = info.stat.favorite,
        isInWatchLater = isInWatchLater,
        isRepost = info.isRepost,
        followingMids = followingMids
    )

internal fun VideoPlaybackUiState.Success.withEngagementUiState(
    engagement: VideoEngagementUiState
): VideoPlaybackUiState.Success {
    val subject = engagement.subject ?: return this
    if (subject.aid != info.aid || subject.bvid != info.bvid || subject.ownerMid != info.owner.mid) return this
    return copy(
        isFollowing = engagement.isFollowing,
        isFavorited = engagement.isFavorited,
        isLiked = engagement.isLiked,
        likeStatusKnown = engagement.likeStatusKnown,
        followStatusKnown = engagement.followStatusKnown,
        isDisliked = engagement.isDisliked,
        coinCount = engagement.coinCount,
        isInWatchLater = engagement.isInWatchLater,
        followingMids = engagement.followingMids,
        info = info.copy(
            stat = info.stat.copy(
                like = engagement.likeCount,
                favorite = engagement.favoriteCount
            )
        )
    )
}
