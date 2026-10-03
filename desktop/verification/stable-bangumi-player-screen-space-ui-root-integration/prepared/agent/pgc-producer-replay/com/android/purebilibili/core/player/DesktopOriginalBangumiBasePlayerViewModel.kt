// GENERATED full original body; upstream 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589; LF 40f38ceb1e9372ee3bd9edbbc833be8675588d5ac71aacd62aa9dbac66af761b
package com.android.purebilibili.core.player

import com.android.purebilibili.data.model.response.SponsorSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.bilipai.desktop.ui.*
import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer
import com.bilipai.desktop.ui.DesktopOriginalPlayerSettingsContext as Context
import android.util.Log as Logger

/**
 * 播放器基类 ViewModel
 * 
 * 提供 VideoPlaybackViewModel 和 BangumiPlayerViewModel 共用的功能：
 * 1. ExoPlayer 管理
 * 2. 空降助手 (SponsorBlock) 逻辑
 * 3. DASH 视频播放
 * 4. 弹幕数据加载
 */
internal abstract class BasePlayerViewModel(protected val environment: DesktopOriginalBangumiPlayerEnvironment) : AutoCloseable {
    private val viewModelScope get() = environment.scope
    private fun <T> MutableStateFlow(initial: T): MutableStateFlow<T> =
        DesktopHomeOwnedMutableStateFlow(initial, environment.commitIfCurrent)

    
    // ========== 播放器引用 ==========
    private var attachedPlayer: ExoPlayer? = null
    // Real shared Section is visible only for this actual PGC source.
    protected val exoPlayer: ExoPlayer? get() = attachedPlayer?.takeIf { environment.ownsPlaybackSource() }
    
    /**
     * 绑定播放器实例
     */
    open fun attachPlayer(player: ExoPlayer) {
        require(player === environment.player) { "PGC must borrow the existing shared Section" }
        attachedPlayer = player
        environment.applyPreferredVolume(player)
    }
    
    /**
     * 获取播放器当前位置
     */
    fun getPlayerCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L
    
    /**
     * 获取播放器总时长
     */
    fun getPlayerDuration(): Long {
        val duration = exoPlayer?.duration ?: 0L
        return if (duration < 0) 0L else duration
    }
    
    /**
     * 跳转到指定位置
     */
    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
    }
    
    // ========== 空降助手 (SponsorBlock) ==========
    
    private val _sponsorSegments = MutableStateFlow<List<SponsorSegment>>(emptyList())
    val sponsorSegments: StateFlow<List<SponsorSegment>> = _sponsorSegments.asStateFlow()
    
    private val _currentSponsorSegment = MutableStateFlow<SponsorSegment?>(null)
    val currentSponsorSegment: StateFlow<SponsorSegment?> = _currentSponsorSegment.asStateFlow()
    
    private val _showSkipButton = MutableStateFlow(false)
    val showSkipButton: StateFlow<Boolean> = _showSkipButton.asStateFlow()
    
    private val skippedSegmentIds = mutableSetOf<String>()
    
    /**
     * 加载空降片段
     */
    protected fun loadSponsorSegments(bvid: String) {
        environment.launch {
            try {
                val segments = environment.baseRequests.getSponsorSegments(bvid)
                _sponsorSegments.value = segments
                skippedSegmentIds.clear()
                Logger.d(TAG, " SponsorBlock: loaded ${segments.size} segments for $bvid")
            } catch (e: Exception) {
                Logger.w(TAG, " SponsorBlock: load failed: ${e.message}")
            }
        }
    }
    
    /**
     * 检查当前播放位置是否在空降片段内，并执行跳过逻辑
     * 
     * @param context 需要 Context 来读取设置
     * @return 是否执行了自动跳过
     */
    suspend fun checkAndSkipSponsor(context: Context): Boolean {
        val player = exoPlayer ?: return false
        val segments = _sponsorSegments.value
        if (segments.isEmpty()) return false
        
        val currentPos = player.currentPosition
        val segment = environment.baseRequests.findSegmentAtPosition(segments, currentPos)
        
        if (segment != null && segment.UUID !in skippedSegmentIds) {
            _currentSponsorSegment.value = segment
            
            val autoSkip = environment.sponsorAutoSkip.first()
            
            if (autoSkip) {
                player.seekTo(segment.endTimeMs)
                skippedSegmentIds.add(segment.UUID)
                _currentSponsorSegment.value = null
                _showSkipButton.value = false
                onSponsorSkipped(segment)
                return true
            } else {
                _showSkipButton.value = true
            }
        } else if (segment == null) {
            _currentSponsorSegment.value = null
            _showSkipButton.value = false
        }
        
        return false
    }
    
    /**
     * 手动跳过当前空降片段
     */
    fun skipCurrentSponsorSegment() {
        val segment = _currentSponsorSegment.value ?: return
        val player = exoPlayer ?: return
        
        player.seekTo(segment.endTimeMs)
        skippedSegmentIds.add(segment.UUID)
        _currentSponsorSegment.value = null
        _showSkipButton.value = false
        
        onSponsorSkipped(segment)
    }
    
    /**
     * 忽略当前空降片段（不跳过）
     */
    fun dismissSponsorSkipButton() {
        val segment = _currentSponsorSegment.value ?: return
        skippedSegmentIds.add(segment.UUID)
        _currentSponsorSegment.value = null
        _showSkipButton.value = false
    }
    
    /**
     * 重置空降片段状态（切换视频时调用）
     */
    protected fun resetSponsorState() {
        _sponsorSegments.value = emptyList()
        _currentSponsorSegment.value = null
        _showSkipButton.value = false
        skippedSegmentIds.clear()
    }
    
    /**
     * 空降片段被跳过后的回调（子类可覆盖以显示 toast 等）
     */
    protected open fun onSponsorSkipped(segment: SponsorSegment) {
        // 子类可覆盖
    }
    
    // ========== DASH 视频播放 ==========
    
    /**
     * 播放 DASH 格式视频（视频+音频分离）
     * 
     * @param videoUrl 视频流 URL
     * @param audioUrl 音频流 URL（可选）
     * @param seekToMs 开始播放位置（毫秒）
     * @param resetPlayer 是否重置播放器状态（默认true，切换清晰度时可设为false以减少闪烁）
     */
    protected fun playDashVideo(
        videoUrl: String, audioUrl: String?, seekToMs: Long = 0L,
        resetPlayer: Boolean = true, referer: String = "https://www.bilibili.com",
        dashManifest: String? = null
    ) {
        environment.assertCurrent()
        environment.applyPreferredVolume(environment.player)
        // Required same Binding / SessionStore / cache / native publication.
        // Native plan preserves original MPD-or-DASH fallback and playback intent.
        environment.native.publishDash(videoUrl, audioUrl, seekToMs, resetPlayer, referer, dashManifest)
    }

    /**
     * Media3 cannot infer the initialization/index ranges of Bilibili's standalone m4s URLs
     * from a pair of progressive sources. When the API gives us a complete DASH description,
     * use a small local MPD so Media3 requests the fMP4 byte ranges correctly.
     */




    /**
     * 播放分段 durl 视频（多段 MP4）
     */
    protected fun playSegmentedVideo(
        segmentUrls: List<String>, seekToMs: Long = 0L,
        resetPlayer: Boolean = true, referer: String = "https://www.bilibili.com"
    ) {
        environment.assertCurrent()
        val cleanUrls = segmentUrls.filter { it.isNotBlank() }
        if (cleanUrls.isEmpty()) return
        if (cleanUrls.size == 1) {
            playDashVideo(cleanUrls.first(), null, seekToMs, resetPlayer, referer)
            return
        }
        environment.applyPreferredVolume(environment.player)
        // All original DURL entries must enter the shared native EDL/cache plan.
        environment.native.publishSegments(cleanUrls, seekToMs, resetPlayer, referer)
    }


    
    /**
     * 播放普通视频（单一 URL）
     * 
     * @param url 视频 URL
     * @param seekToMs 开始播放位置（毫秒）
     */
    protected fun playVideo(url: String, seekToMs: Long = 0L) {
        playDashVideo(url, null, seekToMs)
    }
    
    // ========== 弹幕数据 ==========
    
    private val _danmakuData = MutableStateFlow<ByteArray?>(null)
    val danmakuData: StateFlow<ByteArray?> = _danmakuData.asStateFlow()
    
    /**
     * 加载弹幕数据
     */
    protected fun loadDanmaku(cid: Long) {
        environment.launch {
            val data = environment.baseRequests.getDanmakuRawData(cid)
            if (data != null) {
                _danmakuData.value = data
                Logger.d(TAG, "📝 Danmaku loaded: ${data.size} bytes for cid=$cid")
            }
        }
    }
    
    /**
     * 清除弹幕数据
     */
    protected fun clearDanmaku() {
        _danmakuData.value = null
    }
    
    // ========== 生命周期 ==========
    
    override fun close() {
        attachedPlayer = null
    }
    
    companion object {
        private const val TAG = "BasePlayerVM"
    }
}
