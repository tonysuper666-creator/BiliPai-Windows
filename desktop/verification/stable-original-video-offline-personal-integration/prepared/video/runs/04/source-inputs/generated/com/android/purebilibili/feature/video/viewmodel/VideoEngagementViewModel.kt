package com.android.purebilibili.feature.video.viewmodel

import com.bilipai.desktop.plugins.DesktopPluginContext as Context
import com.android.purebilibili.core.store.DesktopOriginalVideoInfoSettings as SettingsManager
import com.android.purebilibili.core.util.EasterEggs
import com.android.purebilibili.feature.video.ui.feedback.resolveTripleActionFeedbackMessage
import com.android.purebilibili.feature.video.ui.feedback.resolveTripleActionVisualState
import com.android.purebilibili.feature.video.usecase.TripleActionResult
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

import com.bilipai.desktop.ui.DesktopOriginalVideoEngagementEnvironment
import kotlinx.coroutines.*
data class VideoEngagementSeed(
    val isLoggedIn: Boolean = false,
    val isVip: Boolean = false,
    val isFollowing: Boolean = false,
    val isFavorited: Boolean = false,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val likeCount: Int = 0,
    val coinCount: Int = 0,
    val favoriteCount: Int = 0,
    val isInWatchLater: Boolean = false,
    val followingMids: Set<Long> = emptySet()
)

data class VideoEngagementUiState(
    val subject: VideoSubjectSnapshot? = null,
    val isLoggedIn: Boolean = false,
    val isVip: Boolean = false,
    val isFollowing: Boolean = false,
    val isFavorited: Boolean = false,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val likeCount: Int = 0,
    val coinCount: Int = 0,
    val favoriteCount: Int = 0,
    val isInWatchLater: Boolean = false,
    val followingMids: Set<Long> = emptySet(),
    val userCoinBalance: Double? = null,
    val coinDialogVisible: Boolean = false,
    val likeBurstVisible: Boolean = false,
    val tripleCelebrationVisible: Boolean = false
)

sealed interface VideoEngagementEvent {
    data class Message(val text: String) : VideoEngagementEvent
    data class OpenFollowGroups(val mid: Long) : VideoEngagementEvent
    data class LoadVideo(val bvid: String) : VideoEngagementEvent
    data object InvalidateFavoriteFolders : VideoEngagementEvent
}

fun interface VideoCoinBalanceLoader {
    suspend fun load(): Double
}

interface VideoEngagementActions {
    suspend fun toggleFollow(mid: Long, currentlyFollowing: Boolean): Result<Boolean>
    suspend fun toggleLike(aid: Long, currentlyLiked: Boolean, bvid: String): Result<Boolean>
    suspend fun toggleDislike(aid: Long, currentlyDisliked: Boolean, bvid: String): Result<Boolean>
    suspend fun toggleFavorite(aid: Long, currentlyFavorited: Boolean, bvid: String): Result<Boolean>
    suspend fun toggleWatchLater(aid: Long, currentlyInWatchLater: Boolean, bvid: String): Result<Boolean>
    suspend fun doCoin(aid: Long, count: Int, alsoLike: Boolean, bvid: String): Result<Boolean>
    suspend fun doTripleAction(aid: Long): Result<TripleActionResult>
}

private class DefaultVideoEngagementActions(
    private val useCase: VideoInteractionUseCase
) : VideoEngagementActions {
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

    override suspend fun doTripleAction(aid: Long) = useCase.doTripleAction(aid)
}

internal class VideoEngagementViewModel(private val environment: DesktopOriginalVideoEngagementEnvironment) {
    private val actions get() = environment.actions
    private val coinBalanceLoader get() = environment.coinBalanceLoader
    private val viewModelScope get() = environment.scope
    private fun updateOwned(transform: (VideoEngagementUiState) -> VideoEngagementUiState) =
        environment.commit { _uiState.update(transform) }
    private suspend fun sendOwned(event: VideoEngagementEvent) {
        currentCoroutineContext().ensureActive(); environment.assertOwned()
        _events.send(event)
    }

    private val _uiState = MutableStateFlow(VideoEngagementUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<VideoEngagementEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var appContext: Context? = environment.context
    private var locallyModifiedFields: Set<VideoEngagementField> = emptySet()

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
        if (!shouldRebindVideoDomain(_uiState.value.subject, subject)) {
            sync(seed)
            return
        }
        locallyModifiedFields = emptySet()
        environment.commit { _uiState.value = VideoEngagementUiState(
            subject = subject,
            isLoggedIn = seed.isLoggedIn,
            isVip = seed.isVip,
            isFollowing = seed.isFollowing,
            isFavorited = seed.isFavorited,
            isLiked = seed.isLiked,
            isDisliked = seed.isDisliked,
            likeCount = seed.likeCount,
            coinCount = seed.coinCount,
            favoriteCount = seed.favoriteCount,
            isInWatchLater = seed.isInWatchLater,
            followingMids = seed.followingMids
        ) }
    }

    fun sync(seed: VideoEngagementSeed) {
        updateOwned { current ->
            current.copy(
                isLoggedIn = seed.isLoggedIn,
                isVip = seed.isVip,
                isFollowing = if (VideoEngagementField.FOLLOWING in locallyModifiedFields) current.isFollowing else seed.isFollowing,
                isFavorited = if (VideoEngagementField.FAVORITE in locallyModifiedFields) current.isFavorited else seed.isFavorited,
                favoriteCount = if (VideoEngagementField.FAVORITE in locallyModifiedFields) current.favoriteCount else seed.favoriteCount,
                isLiked = if (VideoEngagementField.LIKE in locallyModifiedFields) current.isLiked else seed.isLiked,
                isDisliked = if (VideoEngagementField.DISLIKE in locallyModifiedFields) current.isDisliked else seed.isDisliked,
                likeCount = if (VideoEngagementField.LIKE in locallyModifiedFields) current.likeCount else seed.likeCount,
                coinCount = if (VideoEngagementField.COIN in locallyModifiedFields) current.coinCount else seed.coinCount,
                isInWatchLater = if (VideoEngagementField.WATCH_LATER in locallyModifiedFields) current.isInWatchLater else seed.isInWatchLater,
                followingMids = if (VideoEngagementField.FOLLOWING_MIDS in locallyModifiedFields) current.followingMids else seed.followingMids
            )
        }
    }

    fun setCoinDialogVisible(visible: Boolean) {
        updateOwned { it.copy(coinDialogVisible = visible) }
    }

    fun openCoinDialog() {
        val capturedSubject = _uiState.value.subject
        if (_uiState.value.coinCount >= 2) {
            emitMessage("已投满2个硬币")
            return
        }
        updateOwned { it.copy(coinDialogVisible = true, userCoinBalance = null) }
        viewModelScope.launch {
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            val balance = coinBalanceLoader.load()
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            if (_uiState.value.subject?.generation != capturedSubject?.generation) return@launch
            updateOwned { current ->
                if (current.coinDialogVisible) current.copy(userCoinBalance = balance) else current
            }
        }
    }

    fun toggleFollow(mid: Long? = null, currentlyFollowing: Boolean? = null) {
        val state = _uiState.value
        val targetMid = mid ?: state.subject?.ownerMid ?: return
        val wasFollowing = currentlyFollowing ?: state.isFollowing
        viewModelScope.launch {
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            actions.toggleFollow(targetMid, wasFollowing)
                .onSuccess { following ->
                    currentCoroutineContext().ensureActive(); environment.assertOwned()
                    if (_uiState.value.subject?.generation != state.subject?.generation) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FOLLOWING_MIDS
                    if (state.subject?.ownerMid == targetMid) {
                        locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FOLLOWING
                    }
                    updateOwned { current ->
                        val followingMids = current.followingMids.toMutableSet().apply {
                            if (following) add(targetMid) else remove(targetMid)
                        }
                        current.copy(
                            isFollowing = if (current.subject?.ownerMid == targetMid) following else current.isFollowing,
                            followingMids = followingMids
                        )
                    }
                    emitMessage(if (following) "关注成功" else "已取消关注")
                    if (following) sendOwned(VideoEngagementEvent.OpenFollowGroups(targetMid))
                }
                .onFailure { if (it is CancellationException) throw it; environment.assertOwned(); emitMessage(it.message ?: "操作失败") }
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
        val wasLiked = currentlyLiked ?: state.isLiked
        viewModelScope.launch {
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            actions.toggleLike(targetAid, wasLiked, targetBvid)
                .onSuccess { liked ->
                    currentCoroutineContext().ensureActive(); environment.assertOwned()
                    if (_uiState.value.subject?.generation != state.subject?.generation) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.LIKE +
                        if (liked) setOf(VideoEngagementField.DISLIKE) else emptySet()
                    updateOwned { current ->
                        if (current.subject?.generation != state.subject?.generation) current
                        else current.copy(
                            isLiked = liked,
                            // 点赞与点踩互斥：点赞时本地静默清除点踩（对齐 PiliPlus）
                            isDisliked = if (liked) false else current.isDisliked,
                            likeCount = (current.likeCount + if (liked == current.isLiked) 0 else if (liked) 1 else -1)
                                .coerceAtLeast(0),
                            likeBurstVisible = liked
                        )
                    }
                    environment.commit { onResult?.invoke(liked) }
                    val easterEggEnabled = appContext?.let(SettingsManager::isEasterEggEnabledSync) == true
                    emitMessage(
                        if (liked && easterEggEnabled) EasterEggs.getLikeMessage()
                        else if (liked) "已点赞" else "已取消点赞"
                    )
                }
                .onFailure { if (it is CancellationException) throw it; environment.assertOwned(); emitMessage(it.message ?: "操作失败") }
        }
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
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            actions.toggleDislike(targetAid, wasDisliked, targetBvid)
                .onSuccess { disliked ->
                    currentCoroutineContext().ensureActive(); environment.assertOwned()
                    if (_uiState.value.subject?.generation != state.subject?.generation) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.DISLIKE +
                        if (disliked) setOf(VideoEngagementField.LIKE) else emptySet()
                    updateOwned { current ->
                        if (current.subject?.generation != state.subject?.generation) current
                        else current.copy(
                            isDisliked = disliked,
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
                .onFailure { if (it is CancellationException) throw it; environment.assertOwned(); emitMessage(it.message ?: "操作失败") }
        }
    }

    fun toggleFavorite() {
        val state = _uiState.value
        val subject = state.subject ?: return
        viewModelScope.launch {
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            actions.toggleFavorite(subject.aid, state.isFavorited, subject.bvid)
                .onSuccess { favorited ->
                    currentCoroutineContext().ensureActive(); environment.assertOwned()
                    if (_uiState.value.subject?.generation != subject.generation) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FAVORITE
                    updateOwned { current ->
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
                    sendOwned(VideoEngagementEvent.InvalidateFavoriteFolders)
                    emitMessage(if (favorited) "已收藏" else "已取消收藏")
                }
                .onFailure { if (it is CancellationException) throw it; environment.assertOwned(); emitMessage(it.message ?: "收藏操作失败") }
        }
    }

    fun toggleWatchLater() {
        val state = _uiState.value
        val subject = state.subject ?: return
        viewModelScope.launch {
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            actions.toggleWatchLater(subject.aid, state.isInWatchLater, subject.bvid)
                .onSuccess { inWatchLater ->
                    currentCoroutineContext().ensureActive(); environment.assertOwned()
                    if (_uiState.value.subject?.generation != subject.generation) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.WATCH_LATER
                    updateOwned { current ->
                        if (current.subject?.generation == subject.generation) {
                            current.copy(isInWatchLater = inWatchLater)
                        } else current
                    }
                    emitMessage(if (inWatchLater) "已添加到稍后再看" else "已从稍后再看移除")
                }
                .onFailure { if (it is CancellationException) throw it; environment.assertOwned(); emitMessage(it.message ?: "操作失败") }
        }
    }

    fun doCoin(count: Int, alsoLike: Boolean) {
        val state = _uiState.value
        val subject = state.subject ?: return
        setCoinDialogVisible(false)
        viewModelScope.launch {
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            actions.doCoin(subject.aid, count, alsoLike, subject.bvid)
                .onSuccess {
                    currentCoroutineContext().ensureActive(); environment.assertOwned()
                    if (_uiState.value.subject?.generation != subject.generation) return@onSuccess
                    locallyModifiedFields = locallyModifiedFields + VideoEngagementField.COIN +
                        if (alsoLike) setOf(VideoEngagementField.LIKE, VideoEngagementField.DISLIKE) else emptySet()
                    updateOwned { current ->
                        if (current.subject?.generation != subject.generation) current
                        else current.copy(
                            coinCount = minOf(current.coinCount + count, 2),
                            isLiked = current.isLiked || alsoLike,
                            isDisliked = if (alsoLike) false else current.isDisliked
                        )
                    }
                    val easterEggEnabled = appContext?.let(SettingsManager::isEasterEggEnabledSync) == true
                    emitMessage(if (easterEggEnabled) EasterEggs.getCoinMessage() else "投币成功")
                }
                .onFailure { if (it is CancellationException) throw it; environment.assertOwned(); emitMessage(it.message ?: "投币失败") }
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
        viewModelScope.launch {
            currentCoroutineContext().ensureActive(); environment.assertOwned()
            emitMessage("正在三连")
            actions.doTripleAction(targetAid)
                .onSuccess { result ->
                    currentCoroutineContext().ensureActive(); environment.assertOwned()
                    if (_uiState.value.subject?.generation != state.subject?.generation) return@onSuccess
                    val visual = resolveTripleActionVisualState(
                        currentLiked = currentLiked ?: state.isLiked,
                        currentCoinCount = currentCoinCount ?: state.coinCount,
                        currentFavorited = currentFavorited ?: state.isFavorited,
                        likeSuccess = result.likeSuccess,
                        coinSuccess = result.coinSuccess,
                        coinFailureMessage = result.coinMessage,
                        favoriteSuccess = result.favoriteSuccess
                    )
                    updateOwned { current ->
                        if (current.subject?.generation != state.subject?.generation) current
                        else current.copy(
                            isLiked = visual.isLiked,
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
                            tripleCelebrationVisible = result.allSuccess
                        )
                    }
                    locallyModifiedFields = locallyModifiedFields + buildSet {
                        if (result.likeSuccess) {
                            add(VideoEngagementField.LIKE)
                            add(VideoEngagementField.DISLIKE)
                        }
                        if (result.coinSuccess) add(VideoEngagementField.COIN)
                        if (result.favoriteSuccess) add(VideoEngagementField.FAVORITE)
                    }
                    if (result.favoriteSuccess) {
                        sendOwned(VideoEngagementEvent.InvalidateFavoriteFolders)
                    }
                    environment.commit { onResult?.invoke(result) }
                    emitMessage(
                        resolveTripleActionFeedbackMessage(
                            result.likeSuccess,
                            result.coinSuccess,
                            result.favoriteSuccess,
                            result.coinMessage
                        )
                    )
                    val context = appContext
                    if (result.allSuccess && context != null && SettingsManager.getTripleJumpEnabled(context).first()) {
                        delay(2_000L)
                        if (_uiState.value.subject?.generation == state.subject?.generation) {
                            sendOwned(VideoEngagementEvent.LoadVideo("BV1JsK5eyEuB"))
                        }
                    }
                }
                .onFailure { if (it is CancellationException) throw it; environment.assertOwned(); emitMessage(it.message ?: "三连失败") }
        }
    }

    fun dismissLikeBurst() {
        updateOwned { it.copy(likeBurstVisible = false) }
    }

    fun dismissTripleCelebration() {
        updateOwned { it.copy(tripleCelebrationVisible = false) }
    }

    fun applyFavoriteFolderResult(isFavorited: Boolean) {
        locallyModifiedFields = locallyModifiedFields + VideoEngagementField.FAVORITE
        updateOwned { current ->
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
        viewModelScope.launch {
            currentCoroutineContext().ensureActive(); environment.assertOwned() sendOwned(VideoEngagementEvent.Message(message)) }
    }
}

