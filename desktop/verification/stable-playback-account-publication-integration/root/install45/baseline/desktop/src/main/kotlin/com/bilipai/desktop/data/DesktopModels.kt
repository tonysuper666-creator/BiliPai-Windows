package com.bilipai.desktop.data

import kotlinx.serialization.Serializable
import java.io.IOException

data class VideoCard(
    val bvid: String,
    val title: String,
    val cover: String,
    val author: String,
    val playCount: Long,
    val duration: Int,
    val progressSeconds: Int? = null,
    val preferredCid: Long = 0,
    val pageIndex: Int = 0,
    val viewedAt: Long = 0,
    val publishedAt: Long = 0,
    val authorMid: Long = 0,
)

data class CloudFavoriteFolder(val id: Long, val title: String, val cover: String, val mediaCount: Int)
data class VideoPage(val items: List<VideoCard>, val hasMore: Boolean, val totalCount: Int? = null)
data class CloudHistoryCursor(val max: Long, val viewAt: Long, val business: String)
data class CloudHistoryPage(val items: List<VideoCard>, val nextCursor: CloudHistoryCursor?)
data class FollowingVideoPage(val items: List<VideoCard>, val nextOffset: String?, val updateBaseline: String)

data class VideoPart(val cid: Long, val title: String, val duration: Long)

data class VideoDetails(
    val bvid: String,
    val aid: Long,
    val title: String,
    val description: String,
    val cover: String,
    val author: String,
    val playCount: Long,
    val likeCount: Long,
    val pages: List<VideoPart>,
    val authorMid: Long = 0,
    val raw: com.android.purebilibili.data.model.response.ViewInfo? = null,
)

data class PlaybackSource(
    val videoUrl: String,
    val audioUrl: String?,
    val title: String,
    val referer: String,
    val cookieHeader: String = "",
    val quality: Int = 0,
    val availableQualities: List<PlaybackQuality> = emptyList(),
    val videoAlternatives: List<String> = emptyList(),
    val audioAlternatives: List<String> = emptyList(),
    val progressiveSegments: List<com.bilipai.desktop.player.PlaybackSegment> = emptyList(),
    val videoCodecFamily: String? = null,
    val cachedDashData: com.android.purebilibili.data.model.response.Dash? = null,
    val audioSelection: com.android.purebilibili.feature.video.playback.audio.AudioSelectionDecision? = null,
)

data class PlaybackQuality(val id: Int, val label: String)

data class Comment(
    val id: Long,
    val author: String,
    val avatar: String,
    val text: String,
    val likeCount: Int,
    val timestamp: Long,
    val replyCount: Int = 0,
    val memberId: Long = 0,
    val rootId: Long = 0,
    val previewReplies: List<Comment> = emptyList(),
    val liked: Boolean = false,
)

@Serializable
data class AccountSummary(val mid: Long, val name: String, val avatar: String, val isVip: Boolean = false)

data class QrLogin(val key: String, val url: String)

sealed interface QrLoginState {
    data object Waiting : QrLoginState
    data object Scanned : QrLoginState
    data object Expired : QrLoginState
    data class Complete(val account: AccountSummary) : QrLoginState
}

// OkHttp dispatches interceptor failures through its callback only for IOExceptions.
class BiliApiException(val apiCode: Int, message: String) : IOException(message.ifBlank { "Bilibili 请求失败 ($apiCode)" })
