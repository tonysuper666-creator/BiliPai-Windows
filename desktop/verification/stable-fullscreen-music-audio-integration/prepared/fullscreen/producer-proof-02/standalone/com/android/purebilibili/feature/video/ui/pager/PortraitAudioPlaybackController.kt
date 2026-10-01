package com.android.purebilibili.feature.video.ui.pager

import com.bilipai.desktop.ui.DesktopOriginalMpvSectionControl as ExoPlayer
import com.bilipai.desktop.ui.DesktopOriginalPortraitMediaFactory as DefaultMediaSourceFactory
import com.android.purebilibili.data.model.response.Dash
import com.android.purebilibili.feature.plugin.PlaybackCdnPlugin
import com.android.purebilibili.feature.video.playback.audio.AudioSelectionDecision
import com.android.purebilibili.feature.video.playback.audio.collectAudioStreamCandidates
import com.android.purebilibili.feature.video.playback.audio.resolveAudioStreamSelection

internal data class PortraitAudioSourceSwitchResult(
    val videoUrl: String,
    val audioUrl: String,
    val selection: AudioSelectionDecision
)

/**
 * 复用当前视频轨，仅替换竖屏播放器的音频轨，并保留进度和播放状态。
 */
internal fun switchPortraitPlaybackAudioSource(
    player: ExoPlayer,
    mediaSourceFactory: DefaultMediaSourceFactory,
    dash: Dash,
    currentVideoUrl: String,
    requestedAudioQuality: Int,
    targetVideoQuality: Int,
    mediaId: String,
    cdnPlugin: PlaybackCdnPlugin?,
    isDolbyAudioSupported: Boolean,
    isDolbyAudioSoftwareDecoded: Boolean
): PortraitAudioSourceSwitchResult? {
    if (currentVideoUrl.isBlank() || mediaId.isBlank()) return null
    val selection = resolveAudioStreamSelection(
        dash = dash,
        requestedAudioQuality = requestedAudioQuality,
        playbackSpeed = player.playbackParameters.speed,
        isDolbyAudioSupported = isDolbyAudioSupported,
        isDolbyAudioSoftwareDecoded = isDolbyAudioSoftwareDecoded
    )
    val selectedAudioUrl = selection.selected?.track?.getValidUrl()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val resolvedUrls = resolvePortraitPlaybackCdnUrls(
        streamUrls = PortraitPlaybackStreamUrls(
            videoUrl = currentVideoUrl,
            audioUrl = selectedAudioUrl,
            audioSelection = selection
        ),
        cachedDashVideos = dash.video,
        cachedDashAudios = collectAudioStreamCandidates(
            dash = dash,
            isDolbyAudioSupported = isDolbyAudioSupported
        ).map { it.track },
        targetQuality = targetVideoQuality,
        cdnPlugin = cdnPlugin
    )
    val resolvedAudioUrl = resolvedUrls.audioUrl?.takeIf { it.isNotBlank() } ?: return null
    val currentPosition = player.currentPosition.coerceAtLeast(0L)
    val playWhenReady = player.playWhenReady
    if (!mediaSourceFactory.replaceAudioSource(player, resolvedUrls.videoUrl,
            resolvedAudioUrl, mediaId, currentPosition, playWhenReady)) return null
    return PortraitAudioSourceSwitchResult(
        videoUrl = resolvedUrls.videoUrl,
        audioUrl = resolvedAudioUrl,
        selection = selection
    )
}
