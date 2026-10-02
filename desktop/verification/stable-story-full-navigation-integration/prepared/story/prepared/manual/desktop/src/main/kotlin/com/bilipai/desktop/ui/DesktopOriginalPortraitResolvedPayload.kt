package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.ui.pager.*
import com.android.purebilibili.feature.video.usecase.*

/** Metadata from the actual resolved Pager response/selection. The selected URLs
 * are the SAME Source published to MPV; no success, codec, credential or CID is
 * inferred from a seed. First-load interaction defaults match original
 * shouldFetchInteractionStatusOnVideoLoad=false and deferred status refresh.
 */
internal fun desktopOriginalPortraitResolvedPayload(info: ViewInfo, data: PlayUrlData,
    source: com.bilipai.desktop.data.PlaybackSource, selected: PortraitPlaybackStreamUrls,
    recommendations: List<RelatedVideo>, loggedIn: Boolean, vip: Boolean): VideoLoadResult.Success {
    require(info.bvid.isNotBlank() && info.aid > 0 && info.cid > 0)
    require(source.videoUrl.isNotBlank() && source.authorizationReceipt != null)
    val audio = selected.audioSelection
    val videos = data.dash?.video.orEmpty()
    val video = videos.firstOrNull { it.getValidUrl() == selected.videoUrl || selected.videoUrl in it.backupUrl.orEmpty() }
    val qualities = resolvePortraitAvailableQualityIds(data.acceptQuality, videos.map { it.id }.distinct())
    return VideoLoadResult.Success(
        info=info, playUrl=source.videoUrl, audioUrl=source.audioUrl, related=recommendations,
        quality=video?.id ?: data.quality, resolvedTargetQuality=video?.id ?: data.quality,
        qualityIds=qualities, qualityLabels=resolvePortraitQualityMenuLabels(qualities),
        switchableQualityIds=videos.map { it.id }.distinct(), cachedDashVideos=videos,
        cachedDashAudios=data.dash?.let { com.android.purebilibili.feature.video.playback.audio.collectAudioStreamCandidates(it).map { candidate -> candidate.track } }.orEmpty(),
        cachedDash=data.dash, requestedAudioQuality=audio?.requestedPreferenceId ?: -1,
        selectedAudioQuality=audio?.selectedPreferenceId ?: -1,
        availableAudioQualities=audio?.availableOptions.orEmpty(), audioFallbackReason=audio?.fallbackReason,
        emoteMap=emptyMap(), isLoggedIn=loggedIn, isVip=vip, isFollowing=false,
        isFavorited=false, isLiked=false, coinCount=0,
        duration=resolveVideoLoadDurationMs(data.timelength,info),
        videoCodecId=video?.codecid ?: data.videoCodecid,
        audioCodecId=audio?.selected?.track?.codecid ?: 0,
        aiAudio=data.aiAudio, curAudioLang=data.curLanguage,
        adaptiveDashSource=null // Pager published a legacy selected video/audio pair, not adaptive MPD.
    )
}
