package com.bilipai.desktop.data

data class MediaPage<T>(val items: List<T>, val page: Int, val hasMore: Boolean, val total: Int = 0)

data class LiveArea(val id: Int, val name: String, val parentId: Int = 0, val parentName: String = "",
                    val cover: String = "", val children: List<LiveArea> = emptyList())

data class LiveCard(val roomId: Long, val title: String, val cover: String, val author: String,
                    val avatar: String = "", val online: Long = 0, val area: String = "")

data class LiveRoomDetails(val roomId: Long, val title: String, val cover: String, val author: String,
                           val avatar: String, val online: Long, val area: String, val liveStatus: Int,
                           val description: String = "", val locked: Boolean = false) {
    val isLive: Boolean get() = liveStatus == 1
    fun toCard() = LiveCard(roomId, title, cover, author, avatar, online, area)
}

data class MediaQuality(val id: Int, val label: String)
data class LivePlaybackInfo(val source: PlaybackSource, val qualities: List<MediaQuality>,
                            val backupUrls: List<String> = emptyList(),
                            val resolvedPlayback: com.android.purebilibili.feature.live.ResolvedLivePlayback? = null,
                            val candidateIndex: Int = 0, val urlIndex: Int = 0)

data class BangumiCard(val seasonId: Long, val title: String, val cover: String, val subtitle: String = "",
                       val badge: String = "", val score: String = "", val mediaId: Long = 0,
                       val isCourse: Boolean = false)

data class BangumiEpisode(val id: Long, val aid: Long, val bvid: String, val cid: Long, val title: String,
                          val subtitle: String, val cover: String, val durationSeconds: Long,
                          val badge: String = "", val section: String = "", val status: Int = 0,
                          val playable: Boolean = false, val episodeCanView: Boolean = false,
                          val from: String = "")

data class BangumiSeason(val seasonId: Long, val title: String, val cover: String, val description: String,
                         val episodes: List<BangumiEpisode>, val relatedSeasons: List<BangumiCard> = emptyList(),
                         val score: String = "", val downloadAllowed: Boolean = false,
                         val areaRestricted: Boolean = false,
                         val upstreamDetail: com.android.purebilibili.data.model.response.BangumiDetail? = null) {
    val isCourse: Boolean get() = upstreamDetail?.seasonType == 10
    val following: Boolean get() = com.android.purebilibili.feature.bangumi.isBangumiFollowed(upstreamDetail?.userStatus)
    val followLabel: String get() = com.android.purebilibili.feature.bangumi.resolveBangumiFollowStatusLabel(
        upstreamDetail?.userStatus, if (isCourse) "收藏课程" else "追番/追剧")
    val metaChips: List<String> get() = upstreamDetail?.let { com.android.purebilibili.feature.bangumi.resolveBangumiDetailMetaChips(it) }.orEmpty()
    val restrictionLabels: List<String> get() = upstreamDetail?.let { com.android.purebilibili.feature.bangumi.resolveBangumiRestrictionLabels(it) }.orEmpty()
    val danmakuAllowed: Boolean get() = com.android.purebilibili.feature.bangumi.canShowBangumiDanmaku(upstreamDetail?.rights)
}

data class BangumiPlaybackInfo(val source: PlaybackSource, val qualities: List<MediaQuality>,
                              val preview: Boolean = false, val durationSeconds: Long = 0,
                              val downloadAllowed: Boolean = false)
