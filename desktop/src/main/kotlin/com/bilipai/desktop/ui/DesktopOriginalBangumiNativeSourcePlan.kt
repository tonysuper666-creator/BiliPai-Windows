package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.player.buildPlaybackCacheKey
import com.android.purebilibili.feature.bangumi.BangumiPlayerState
import com.android.purebilibili.feature.video.playback.audio.collectAudioStreamCandidates
import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.core.player.policy.PlaybackQualityMode
import com.android.purebilibili.feature.video.usecase.VideoLoadResult
import com.bilipai.desktop.player.PlaybackSegment
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*

/** Pure projection of the original actual PGC protocol and current full VM state.
 * This is neither a resolver nor a source/cache/account authority. Blank BVID,
 * missing uploader/dimensions/statistics stay absent in the existing DTO defaults.
 * In particular season aggregate counts are never episode counts. */
internal data class DesktopOriginalBangumiNativeSourcePlan(
    val detail: BangumiDetail,
    val episode: BangumiEpisode,
    val data: BangumiVideoInfo,
    val videoUrl: String,
    val audioUrl: String?,
    val segments: List<PlaybackSegment>,
    val manifest: String?,
    val videoTrack: DashVideo?,
    val audioTrack: DashAudio?,
    val seekToMs: Long,
    val playWhenReady: Boolean,
) {
    val referer: String = "https://www.bilibili.com/" +
        (if (detail.seasonType == 10) "cheese" else "bangumi") + "/play/ep${episode.id}"
    val title: String = listOf(detail.title, episode.longTitle.ifBlank { episode.title })
        .filter(String::isNotBlank).joinToString(" · ")

    init {
        require(detail.seasonId > 0 && episode.id > 0 && episode.cid >= 0 && episode.aid >= 0)
        require(videoUrl.isNotBlank() && seekToMs >= 0)
        require(segments.isEmpty() || audioUrl == null)
        require(manifest == null || videoTrack != null)
    }

    fun nativeSource(): PlaybackSource = PlaybackSource(videoUrl, audioUrl, referer = referer,
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        title = title, startPositionSeconds = seekToMs / 1_000.0, startPaused = !playWhenReady,
        progressiveSegments = segments)

    /** Pure preparation from the exact retained source: signed selection and
     * the complete progressive plan change; receipt, Cookie and original headers
     * remain the existing accepted values. No completed load binding is reused. */
    fun retainedSource(original: PlaybackSource, video: String, audio: String?): PlaybackSource {
        require(original.referer == referer && video == videoUrl && audio?.takeIf(String::isNotBlank) == audioUrl)
        return original.copy(videoUrl = videoUrl, audioUrl = audioUrl, title = title,
            progressiveSegments = segments,
            nativePublication = null, nativeTransport = null)
    }

    fun byteTracks(source: PlaybackSource): List<DesktopMediaByteTrack> {
        require(source.videoUrl == videoUrl && source.audioUrl == audioUrl && source.referer == referer)
        return if (segments.isNotEmpty()) segments.map { segment ->
            val entries = if (!data.durl.isNullOrEmpty()) data.durl else data.durls.orEmpty()
            val raw = entries.first { segment.url == it.url || segment.url in it.backupUrl.orEmpty() }
            val aliases = (listOf(raw.url) + raw.backupUrl.orEmpty()).filter(String::isNotBlank).filter { it != segment.url }
            capturedDesktopMediaByteTrack(segment.url, aliases, buildPlaybackCacheKey(segment.url, null),
                "progressive", capturedPlaybackMediaHeaders(source))
        }.distinctBy { it.url }
        else desktopOriginalLegacyByteTracks(source,
            listOfNotNull(videoTrack?.getValidUrl()) + videoTrack?.backupUrl.orEmpty() +
                (if (data.dash == null) (if (!data.durl.isNullOrEmpty()) data.durl else data.durls.orEmpty())
                    .filter { videoUrl == it.url || videoUrl in it.backupUrl.orEmpty() }
                    .flatMap { listOf(it.url) + it.backupUrl.orEmpty() } else emptyList()),
            listOfNotNull(audioTrack?.getValidUrl()) + audioTrack?.backupUrl.orEmpty(), emptyMap())
    }

    fun adaptiveSource(): AdaptiveDashPlaybackSource? = manifest?.let { value ->
        val raw = checkNotNull(videoTrack)
        // The existing accepted factory selects getValidUrl(). Preserve the
        // exact chosen signed mirror in that physical plan, including all raw
        // aliases; the original cached response itself remains unchanged.
        val selected = raw.copy(baseUrl = videoUrl,
            backupUrl = (listOf(raw.getValidUrl()) + raw.backupUrl.orEmpty()).distinct().filter { it != videoUrl })
        val selectedAudio = audioTrack?.let { rawAudio -> rawAudio.copy(baseUrl = checkNotNull(audioUrl),
            backupUrl = (listOf(rawAudio.getValidUrl()) + rawAudio.backupUrl.orEmpty()).distinct().filter { it != audioUrl }) }
        AdaptiveDashPlaybackSource(value, listOf(selected), listOfNotNull(selectedAudio),
            PlaybackQualityMode.fromQualityId(selected.id))
    }

    fun payload(state: BangumiPlayerState.Success): VideoLoadResult.Success {
        require(state.cachedPlayData === data) { "PGC metadata must come from the exact published protocol result" }
        require(state.currentEpisode.id == episode.id && state.currentEpisode.cid == episode.cid)
        require(state.seasonDetail.seasonId == detail.seasonId)
        require(state.playUrl == videoUrl && state.audioUrl?.takeIf(String::isNotBlank) == audioUrl)
        val rawUp = detail.upInfo
        val info = ViewInfo(bvid = episode.bvid, aid = episode.aid, cid = episode.cid,
            title = title, desc = detail.evaluate, pic = episode.cover.ifBlank { detail.cover },
            pubdate = episode.pubTime,
            owner = rawUp?.let { Owner(mid = it.mid, name = it.uname, face = it.avatar) } ?: Owner(),
            pages = listOf(Page(cid = episode.cid, page = 1, part = episode.longTitle.ifBlank { episode.title },
                duration = (episode.duration / 1_000L).coerceAtLeast(0L))))
        val rawDuration = data.timelength.takeIf { it > 0L } ?: data.timeLengthAlt.takeIf { it > 0L }
            ?: episode.duration.takeIf { it > 0L } ?: 0L
        return VideoLoadResult.Success(info = info, playUrl = videoUrl, audioUrl = audioUrl,
            related = emptyList(), quality = state.quality, resolvedTargetQuality = state.quality,
            qualityIds = state.acceptQuality, qualityLabels = state.acceptDescription,
            switchableQualityIds = data.dash?.video.orEmpty().map { it.id }.distinct(),
            cachedDashVideos = data.dash?.video.orEmpty(),
            cachedDashAudios = data.dash?.let { collectAudioStreamCandidates(it).map { candidate -> candidate.track } }.orEmpty(),
            cachedDash = data.dash, requestedAudioQuality = state.requestedAudioQuality,
            selectedAudioQuality = state.selectedAudioQuality, availableAudioQualities = state.availableAudioQualities,
            audioFallbackReason = state.audioFallbackReason, emoteMap = emptyMap(),
            isLoggedIn = state.isLoggedIn, isVip = state.isVip,
            isFollowing = detail.userStatus?.follow == 1, isFavorited = false,
            isLiked = state.isLiked, coinCount = state.coinCount, duration = rawDuration,
            videoCodecId = videoTrack?.codecid ?: data.videoCodecid, audioCodecId = audioTrack?.codecid ?: 0,
            adaptiveDashSource = adaptiveSource())
    }
}

internal fun desktopOriginalBangumiNativePlan(detail: BangumiDetail, episode: BangumiEpisode,
    data: BangumiVideoInfo, videoUrl: String, audioUrl: String?, segmentUrls: List<String>?,
    referer: String, manifest: String?, seekToMs: Long, playWhenReady: Boolean): DesktopOriginalBangumiNativeSourcePlan {
    val audio = audioUrl?.takeIf(String::isNotBlank)
    val rawDurl = if (!data.durl.isNullOrEmpty()) data.durl else data.durls.orEmpty()
    val cleanSegments = segmentUrls?.filter(String::isNotBlank)
    val rawPlayable = rawDurl.mapNotNull { raw ->
        (raw.url.takeIf(String::isNotBlank) ?: raw.backupUrl.orEmpty().firstOrNull(String::isNotBlank))?.let { it to raw }
    }
    val segments = if (cleanSegments != null) {
        require(audio == null && cleanSegments.isNotEmpty())
        // Keep the exact original order/count. A foreign or rewritten segment must
        // not inherit another DURL's duration or be mistaken for a complete stream.
        require(cleanSegments == rawPlayable.map { it.first })
        rawPlayable.map { (url, raw) -> PlaybackSegment(url, raw.length.takeIf { length -> length > 0 }?.div(1_000.0)) }
    } else emptyList()
    if (audio == null && data.dash == null) require(rawPlayable.size <= 1 || cleanSegments != null) {
        "All original PGC progressive segments must be published"
    }
    val videoTrack = data.dash?.video?.firstOrNull { videoUrl == it.getValidUrl() || videoUrl in it.backupUrl.orEmpty() }
    val audioTrack = data.dash?.let { collectAudioStreamCandidates(it).map { candidate -> candidate.track } }
        ?.firstOrNull { audio != null && (audio == it.getValidUrl() || audio in it.backupUrl.orEmpty()) }
    if (data.dash != null) require(videoTrack != null && (audio == null || audioTrack != null)) {
        "PGC source selection must stay inside this exact signed protocol response"
    } else require(rawDurl.any { videoUrl == it.url || videoUrl in it.backupUrl.orEmpty() }) {
        "PGC progressive source is absent from this exact signed response"
    }
    val plan = DesktopOriginalBangumiNativeSourcePlan(detail, episode, data, videoUrl, audio, segments,
        manifest?.takeIf(String::isNotBlank), videoTrack, audioTrack, seekToMs.coerceAtLeast(0), playWhenReady)
    require(referer == plan.referer) { "PGC media must retain the actual episode's original Referer" }
    if (plan.manifest != null) require(videoTrack != null && (audio == null || audioTrack != null)) {
        "PGC MPD must use actual raw selected representations"
    }
    return plan
}
