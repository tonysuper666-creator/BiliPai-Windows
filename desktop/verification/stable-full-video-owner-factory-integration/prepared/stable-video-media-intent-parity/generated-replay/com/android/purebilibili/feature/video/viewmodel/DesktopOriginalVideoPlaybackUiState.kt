package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.audio.*
import com.android.purebilibili.feature.video.playback.policy.PlaybackQualityMode
import com.android.purebilibili.feature.video.subtitle.*
import com.android.purebilibili.feature.video.note.VideoNoteUiState
import com.android.purebilibili.feature.plugin.CdnLineDiagnostic
sealed class VideoPlaybackUiState {
    data class Loading(
        val retryAttempt: Int = 0,
        val maxAttempts: Int = 4,
        val message: String = "\u52a0\u8f7d\u4e2d..."
    ) : VideoPlaybackUiState() {
        companion object { val Initial = Loading() }
    }
    
    data class Success(
        val info: ViewInfo,
        val playUrl: String,
        val audioUrl: String? = null,
        val related: List<RelatedVideo> = emptyList(),
        val currentQuality: Int = 64,
        val playbackQualityMode: PlaybackQualityMode = PlaybackQualityMode.AUTO,
        val adaptiveDashSource: AdaptiveDashPlaybackSource? = null,
        val qualityLabels: List<String> = emptyList(),
        val qualityIds: List<Int> = emptyList(),
        val switchableQualityIds: List<Int> = emptyList(),
        val startPosition: Long = 0L,
        val pendingPlaybackTransitionPositionMs: Long? = null,
        val cachedDashVideos: List<DashVideo> = emptyList(),
        val cachedDashAudios: List<DashAudio> = emptyList(),
        val cachedDash: Dash? = null,
        val requestedAudioQuality: Int = -1,
        val selectedAudioQuality: Int = -1,
        val availableAudioQualities: List<AudioQualityOption> = emptyList(),
        val audioFallbackReason: AudioFallbackReason? = null,
        val isQualitySwitching: Boolean = false,
        val requestedQuality: Int? = null,
        val isLoggedIn: Boolean = false,
        val isVip: Boolean = false,
        val isFollowing: Boolean = false,
        val isFavorited: Boolean = false,
        val isLiked: Boolean = false,
        val isDisliked: Boolean = false,
        val coinCount: Int = 0,
        val emoteMap: Map<String, String> = emptyMap(),
        val isInWatchLater: Boolean = false,  //  稍后再看状态
        val followingMids: Set<Long> = emptySet(),  //  已关注用户 ID 列表
        val videoTags: List<VideoTag> = emptyList(),  //  视频标签列表
        //  CDN 线路切换
        val currentCdnIndex: Int = 0,  // 当前使用的 CDN 索引 (0=主线路)
        val allVideoUrls: List<String> = emptyList(),  // 所有可用视频 URL (主+备用)
        val allAudioUrls: List<String> = emptyList(),   // 所有可用音频 URL (主+备用)
        val cdnCandidateSources: List<com.android.purebilibili.feature.plugin.PlaybackCdnCandidateSource> = emptyList(),
        val cdnLineDiagnostics: List<CdnLineDiagnostic> = emptyList(),
        val isCdnProbing: Boolean = false,
        // 🖼️ [新增] 视频预览图数据（用于进度条拖动预览）
        val videoshotData: VideoshotData? = null,
        // 🎞️ [New] Codec & Audio Info
        val videoCodecId: Int = 0,
        val audioCodecId: Int = 0,
        // 👀 [新增] 在线观看人数

        val onlineCount: String = "",
        // [新增] AI Summary & BGM
        val aiSummary: AiSummaryData? = null,
        val aiSummaryPrompt: AiSummaryPromptState? = null,
        val videoNoteState: VideoNoteUiState = VideoNoteUiState(),
        val bgmInfo: BgmInfo? = null,
        val bgmInfoList: List<BgmInfo> = emptyList(),
        // [New] AI Audio Translation
        val aiAudio: AiAudioInfo? = null,
        val currentAudioLang: String? = null,
        val videoDurationMs: Long = 0L,
        val subtitleEnabled: Boolean = false,
        val subtitleOwnerBvid: String? = null,
        val subtitleOwnerCid: Long = 0L,
        val subtitlePrimaryLanguage: String? = null,
        val subtitleSecondaryLanguage: String? = null,
        val subtitlePrimaryTrackKey: String? = null,
        val subtitleSecondaryTrackKey: String? = null,
        val subtitleTracks: List<SubtitleTrackMeta> = emptyList(),
        val subtitlePrimaryLikelyAi: Boolean = false,
        val subtitleSecondaryLikelyAi: Boolean = false,
        val subtitlePrimaryCues: List<SubtitleCue> = emptyList(),
        val subtitleSecondaryCues: List<SubtitleCue> = emptyList(),
        val ownerFollowerCount: Int? = null,
        val ownerVideoCount: Int? = null,
        // SponsorBlock 空降片段生成的标题徽标（如“赞助/恰饭”），空串表示不展示
        val sponsorVideoLabel: String = ""
    ) : VideoPlaybackUiState() {
        val cdnCount: Int get() = allVideoUrls.size.coerceAtLeast(1)
        val currentCdnLabel: String get() = "线路${currentCdnIndex + 1}"
    }
    
    data class Error(
        val error: VideoLoadError,
        val canRetry: Boolean = true
    ) : VideoPlaybackUiState() {
        val msg: String get() = error.toUserMessage()
    }
}

data class SponsorSkipUiState(
    val visible: Boolean = false,
    val segmentId: String? = null,
    val skipToMs: Long = 0L,
    val label: String? = null
)

enum class SponsorContributionPhase {
    HIDDEN,
    READY,
    MARKING,
    REVIEW,
    SUBMITTING,
    SUCCESS,
}

data class SponsorContributionUiState(
    val phase: SponsorContributionPhase = SponsorContributionPhase.HIDDEN,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val category: String = SponsorCategory.SPONSOR,
    val actionType: String = "skip",
    val serverBaseUrl: String = "",
    val message: String? = null,
) {
    val showsMarkAction: Boolean
        get() = phase == SponsorContributionPhase.READY || phase == SponsorContributionPhase.MARKING

    val showsReview: Boolean
        get() = phase == SponsorContributionPhase.REVIEW ||
            phase == SponsorContributionPhase.SUBMITTING ||
            phase == SponsorContributionPhase.SUCCESS
}

internal data class QualitySwitchFailureDialogState(
    val requestedQualityId: Int,
    val requestedQualityLabel: String,
    val title: String,
    val message: String
)
