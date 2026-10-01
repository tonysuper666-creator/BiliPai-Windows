package com.android.purebilibili.feature.video.viewmodel
import com.android.purebilibili.data.model.response.AiSummaryData
import com.android.purebilibili.data.model.response.VideoTag
import com.android.purebilibili.feature.video.note.VideoNoteUiState
data class VideoSupplementSeed(
    val aiSummary: AiSummaryData? = null,
    val videoNoteState: VideoNoteUiState = VideoNoteUiState(),
    val videoTags: List<VideoTag> = emptyList(),
    val onlineCount: String = "",
    val ownerFollowerCount: Int? = null,
    val ownerVideoCount: Int? = null
)
data class VideoSupplementUiState(
    val subject: VideoSubjectSnapshot? = null,
    val visible: Boolean = true,
    val aiSummary: AiSummaryData? = null,
    val videoNoteState: VideoNoteUiState = VideoNoteUiState(),
    val videoTags: List<VideoTag> = emptyList(),
    val onlineCount: String = "",
    val ownerFollowerCount: Int? = null,
    val ownerVideoCount: Int? = null
)
internal fun VideoPlaybackUiState.Success.toSubjectSnapshot(
    generation: Long
): VideoSubjectSnapshot = VideoSubjectSnapshot(
    bvid = info.bvid,
    cid = info.cid,
    aid = info.aid,
    ownerMid = info.owner.mid,
    title = info.title,
    coverUrl = info.pic,
    durationMs = videoDurationMs,
    generation = generation
)

internal fun shouldAdvanceVideoSubjectGeneration(
    previous: VideoSubjectSnapshot?,
    next: VideoPlaybackUiState.Success
): Boolean = previous == null ||
    previous.bvid != next.info.bvid ||
    previous.cid != next.info.cid ||
    previous.aid != next.info.aid
internal fun VideoPlaybackUiState.Success.toEngagementSeed(): VideoEngagementSeed =
    VideoEngagementSeed(
        isLoggedIn = isLoggedIn,
        isVip = isVip,
        isFollowing = isFollowing,
        isFavorited = isFavorited,
        isLiked = isLiked,
        isDisliked = isDisliked,
        likeCount = info.stat.like,
        coinCount = coinCount,
        favoriteCount = info.stat.favorite,
        isInWatchLater = isInWatchLater,
        followingMids = followingMids
    )

internal fun VideoPlaybackUiState.Success.withEngagementUiState(
    engagement: VideoEngagementUiState
): VideoPlaybackUiState.Success {
    val subject = engagement.subject ?: return this
    if (subject.aid != info.aid || subject.bvid != info.bvid) return this
    return copy(
        isFollowing = engagement.isFollowing,
        isFavorited = engagement.isFavorited,
        isLiked = engagement.isLiked,
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
internal fun VideoPlaybackUiState.Success.toSupplementSeed(): VideoSupplementSeed =
    VideoSupplementSeed(
        aiSummary = aiSummary,
        videoNoteState = videoNoteState,
        videoTags = videoTags,
        onlineCount = onlineCount,
        ownerFollowerCount = ownerFollowerCount,
        ownerVideoCount = ownerVideoCount
    )
