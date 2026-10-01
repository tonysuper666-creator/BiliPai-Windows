package com.android.purebilibili.feature.video.usecase
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.audio.*
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
sealed class VideoLoadResult {
    data class Success(
        val info: ViewInfo,
        val playUrl: String,
        val audioUrl: String?,
        val related: List<RelatedVideo>,
        val quality: Int,
        val resolvedTargetQuality: Int = quality,
        val qualityIds: List<Int>,
        val qualityLabels: List<String>,
        val switchableQualityIds: List<Int> = emptyList(),
        val cachedDashVideos: List<DashVideo>,
        val cachedDashAudios: List<DashAudio>,
        val cachedDash: Dash? = null,
        val requestedAudioQuality: Int = -1,
        val selectedAudioQuality: Int = -1,
        val availableAudioQualities: List<AudioQualityOption> = emptyList(),
        val audioFallbackReason: AudioFallbackReason? = null,
        val emoteMap: Map<String, String>,
        val isLoggedIn: Boolean,
        val isVip: Boolean,
        val isFollowing: Boolean,
        val isFavorited: Boolean,
        val isLiked: Boolean,
        val coinCount: Int,
        // 播放时长（毫秒）：优先播放地址，缺失时回退详情页分集时长。
        val duration: Long = 0,
        // [New] Codec Info for UI display
        val videoCodecId: Int = 0,
        val audioCodecId: Int = 0,
        // [New] AI Translation Info
        val aiAudio: AiAudioInfo? = null,
        val curAudioLang: String? = null,
        val adaptiveDashSource: AdaptiveDashPlaybackSource? = null
    ) : VideoLoadResult()
    
    data class Error(
        val error: VideoLoadError,
        val canRetry: Boolean = true
    ) : VideoLoadResult()
}

data class QualitySwitchResult(
    val videoUrl: String,
    val audioUrl: String?,
    val actualQuality: Int,
    val wasFallback: Boolean,
    val adaptiveDashSource: AdaptiveDashPlaybackSource? = null,
    val cachedDashVideos: List<DashVideo>,
    val cachedDashAudios: List<DashAudio>,
    val cachedDash: Dash? = null,
    val requestedAudioQuality: Int = -1,
    val selectedAudioQuality: Int = -1,
    val availableAudioQualities: List<AudioQualityOption> = emptyList(),
    val audioFallbackReason: AudioFallbackReason? = null,
    val switchableQualityIds: List<Int> = emptyList(),
    val qualityIds: List<Int> = emptyList(),
    val qualityLabels: List<String> = emptyList()
)

data class PlaybackSelectionResult(
    val videoUrl: String,
    val audioUrl: String?,
    val actualQuality: Int,
    val isDashPlayback: Boolean,
    val adaptiveDashSource: AdaptiveDashPlaybackSource? = null,
    val cachedDashVideos: List<DashVideo>,
    val cachedDashAudios: List<DashAudio>,
    val cachedDash: Dash? = null,
    val requestedAudioQuality: Int = -1,
    val selectedAudioQuality: Int = -1,
    val availableAudioQualities: List<AudioQualityOption> = emptyList(),
    val audioFallbackReason: AudioFallbackReason? = null,
    val switchableQualityIds: List<Int>,
    val qualityIds: List<Int>,
    val qualityLabels: List<String>,
    val videoCodec: String? = null,
    val videoBandwidth: Int? = null,
    val audioBandwidth: Int? = null
)
