package com.android.purebilibili.feature.video.viewmodel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.bilipai.desktop.ui.DesktopDanmakuSessionEnvironment

/** Original menu and list share one confirmed liked-ID set; no replacement PlaybackVM/list/cache. */
internal class DesktopOriginalDanmakuSession(private val environment:DesktopDanmakuSessionEnvironment):AutoCloseable {
    private val ownerJob=SupervisorJob(environment.scope.coroutineContext[Job])
    private val viewModelScope=CoroutineScope(environment.scope.coroutineContext+ownerJob)
    private val currentCid:Long get()=environment.cid
    private var menuGeneration=0L
    private var statsGeneration=0L
    private var likeGeneration=0L
    private var menuLikeRequest=0L
    private val statsJobs=mutableMapOf<Long,Job>()
    private val statsRequests=mutableMapOf<Long,Long>()
    private fun toast(message:String) {if(environment.isOwned()&&ownerJob.isActive)environment.showFeedback(message)}
    private fun assertOwned(){environment.assertOwned();if(!ownerJob.isActive)throw CancellationException("Danmaku session closed")}
    private val _danmakuMenuState=MutableStateFlow(DanmakuMenuState())
    val danmakuMenuState=_danmakuMenuState.asStateFlow()
    private val _likedDanmakuIds=MutableStateFlow<Set<Long>>(emptySet())
    val likedDanmakuIds=_likedDanmakuIds.asStateFlow()
    override fun close(){++menuGeneration;++statsGeneration;++likeGeneration;ownerJob.cancel();statsJobs.clear();statsRequests.clear()}
    data class DanmakuMenuState(
        val visible: Boolean = false,
        val text: String = "",
        val dmid: Long = 0,
        val userHash: String = "", // 发送者标识 (可能不是纯数字 UID)
        val isSelf: Boolean = false, // 是否是自己发送的
        val voteCount: Int = 0,
        val hasLiked: Boolean = false,
        val voteLoading: Boolean = false,
        val canVote: Boolean = false
    )

    fun showDanmakuMenu(dmid: Long, text: String, userHash: String = "", isSelf: Boolean = false) {
        assertOwned()
        ++menuGeneration
        val supportsVote = dmid > 0L && currentCid > 0L
        _danmakuMenuState.value = DanmakuMenuState(
            visible = true,
            text = text,
            dmid = dmid,
            userHash = userHash,
            isSelf = isSelf,
            voteLoading = supportsVote,
            canVote = supportsVote
        )
        if (supportsVote) {
            refreshDanmakuThumbupState(dmid)
        }
        // 暂停播放 (可选，防止弹幕飘走)
        // if (exoPlayer?.isPlaying == true) exoPlayer?.pause()
    }

    fun hideDanmakuMenu() {
        ++menuGeneration
        _danmakuMenuState.value = _danmakuMenuState.value.copy(visible = false)
        // 恢复播放?
    }

    private fun refreshDanmakuThumbupState(dmid: Long) {
        if (dmid <= 0L || currentCid <= 0L) return

        val request=++statsGeneration
        statsRequests[dmid]=request
        val menuRequest=menuGeneration
        statsJobs.remove(dmid)?.cancel()
        val loading=viewModelScope.launch {
            try {
            environment.actions
                .getDanmakuThumbupState(cid = currentCid, dmid = dmid)
                .also { currentCoroutineContext().ensureActive(); assertOwned(); if(request!=statsRequests[dmid])return@launch }
                .onSuccess { thumbupState ->
                    _likedDanmakuIds.update { if (thumbupState.liked) it + dmid else it - dmid }
                    _danmakuMenuState.update { current ->
                        if (menuRequest!=menuGeneration || !current.visible || current.dmid != dmid) current
                        else current.copy(
                            voteCount = thumbupState.likes,
                            hasLiked = thumbupState.liked,
                            voteLoading = false,
                            canVote = true
                        )
                    }
                }
                .onFailure {
                    _danmakuMenuState.update { current ->
                        if (menuRequest!=menuGeneration || !current.visible || current.dmid != dmid) current
                        else current.copy(voteLoading = false, canVote = false)
                    }
                }
                    } finally {
                if (request==statsRequests[dmid] && menuRequest==menuGeneration && environment.isOwned() && ownerJob.isActive) {
                    _danmakuMenuState.update { current -> if(!current.visible||current.dmid!=dmid)current else current.copy(voteLoading=false) }
                }
            }
        }
        if(!loading.isCompleted)statsJobs[dmid]=loading
    }

    fun recallDanmaku(dmid: Long) {
        if (currentCid == 0L) {
            viewModelScope.launch { toast("视频未加载") }
            return
        }
        
        assertOwned()
        viewModelScope.launch {
            environment.actions
                .recallDanmaku(cid = currentCid, dmid = dmid)
                .also { currentCoroutineContext().ensureActive(); assertOwned() }
                .onSuccess { message ->
                    toast(message.ifEmpty { "撤回成功" })
                }
                .onFailure { error ->
                    toast(error.message ?: "撤回失败")
                }
        }
    }

    fun likeDanmaku(dmid: Long) {
        assertOwned()
        if (dmid <= 0L) {
            viewModelScope.launch { toast("当前弹幕不支持点赞") }
            return
        }

        val menuState = _danmakuMenuState.value
        val shouldLike = if (menuState.visible && menuState.dmid == dmid && menuState.canVote) {
            !menuState.hasLiked
        } else {
            true
        }
        likeDanmaku(dmid = dmid, like = shouldLike)
    }

    fun likeDanmaku(dmid: Long, like: Boolean = true) {
        if (currentCid == 0L) {
            viewModelScope.launch { toast("视频未加载") }
            return
        }
        if (dmid <= 0L) {
            viewModelScope.launch { toast("当前弹幕不支持点赞") }
            return
        }

        assertOwned()
        val menuRequest=menuGeneration
        val request=++likeGeneration
        if(_danmakuMenuState.value.visible && _danmakuMenuState.value.dmid==dmid)menuLikeRequest=request
        statsJobs.remove(dmid)?.cancel(); statsRequests[dmid]=++statsGeneration
        _danmakuMenuState.update { current ->
            if (menuRequest!=menuGeneration || !current.visible || current.dmid != dmid) current
            else current.copy(voteLoading = true)
        }
        
        viewModelScope.launch {
            try {
            environment.actions
                .likeDanmaku(cid = currentCid, dmid = dmid, like = like)
                .also { currentCoroutineContext().ensureActive(); assertOwned() }
                .onSuccess {
                    _likedDanmakuIds.update { if (like) it + dmid else it - dmid }
                    _danmakuMenuState.update { current ->
                        if (menuRequest!=menuGeneration || !current.visible || current.dmid != dmid) current
                        else {
                            val delta = when {
                                like && !current.hasLiked -> 1
                                !like && current.hasLiked -> -1
                                else -> 0
                            }
                            current.copy(
                                hasLiked = like,
                                voteCount = (current.voteCount + delta).coerceAtLeast(0),
                                voteLoading = false,
                                canVote = true
                            )
                        }
                    }
                    toast(if (like) "点赞成功" else "已取消点赞")
                    refreshDanmakuThumbupState(dmid)
                }
                .onFailure { error ->
                    _danmakuMenuState.update { current ->
                        if (menuRequest!=menuGeneration || !current.visible || current.dmid != dmid) current
                        else current.copy(voteLoading = false)
                    }
                    toast(error.message ?: "操作失败")
                }
                    } finally {
                if(request==menuLikeRequest && menuRequest==menuGeneration && environment.isOwned() && ownerJob.isActive) {
                    _danmakuMenuState.update { current -> if(!current.visible||current.dmid!=dmid)current else current.copy(voteLoading=false) }
                }
            }
        }
    }

    fun reportDanmaku(dmid: Long, reason: Int) {
        reportDanmaku(dmid = dmid, reason = reason, content = "")
    }

    fun reportDanmaku(dmid: Long, reason: Int, content: String = "") {
        if (currentCid == 0L) {
            viewModelScope.launch { toast("视频未加载") }
            return
        }
        
        assertOwned()
        viewModelScope.launch {
            environment.actions
                .reportDanmaku(cid = currentCid, dmid = dmid, reason = reason, content = content)
                .also { currentCoroutineContext().ensureActive(); assertOwned() }
                .onSuccess {
                    toast("举报成功")
                }
                .onFailure { error ->
                    toast(error.message ?: "举报失败")
                }
        }
    }
}
