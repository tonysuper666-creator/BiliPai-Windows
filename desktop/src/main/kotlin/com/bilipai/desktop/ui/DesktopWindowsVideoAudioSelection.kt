package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.player.PlayerState
import com.bilipai.desktop.player.PlayerTrack

/** API translation choices are distinct from MPV's track IDs in the current file. */
internal data class DesktopWindowsAudioLanguageOption(val language: String?, val label: String)

internal fun desktopWindowsCurrentAudioLanguage(success: VideoPlaybackUiState.Success): String? =
    success.currentAudioLang?.takeIf(String::isNotBlank)

internal fun desktopWindowsAudioLanguageOptions(success: VideoPlaybackUiState.Success): List<DesktopWindowsAudioLanguageOption> {
    val languages = success.aiAudio?.items.orEmpty().filter {
        it.langCode.isNotBlank() && it.langCode.length <= 64 &&
            it.langCode == it.langCode.trim() && it.langCode.none { c -> c.isISOControl() }
    }.distinctBy { it.langCode }.map {
        DesktopWindowsAudioLanguageOption(it.langCode, it.langDoc.trim().takeIf(String::isNotEmpty) ?: it.langCode)
    }
    return if (languages.isEmpty()) emptyList() else listOf(DesktopWindowsAudioLanguageOption(null, "原声")) + languages
}

internal fun desktopWindowsNativeAudioTracks(state: PlayerState): List<PlayerTrack> =
    state.tracks.filter { it.type == "audio" && it.id > 0 }.distinctBy { it.id }

internal fun desktopWindowsNativeAudioTrackLabel(track: PlayerTrack): String =
    listOfNotNull(track.title?.takeIf(String::isNotBlank), track.language?.takeIf(String::isNotBlank))
        .joinToString(" · ").ifBlank { "音轨 ${track.id}" }

/** No new generation or preference: the existing VM receives an explicit source replacement. */
internal data class DesktopWindowsAudioLanguageSelection(val request: PlaybackRequest, val positionMs: Long)

/** Used by the original VM's near-end branch; explicit audio replacement keeps even a paused final position. */
internal fun desktopWindowsShouldRestartPlaybackAtEnd(
    durationMs: Long, positionMs: Long, explicitStartPositionMs: Long?,
): Boolean = explicitStartPositionMs == null && durationMs > 0L && positionMs >= durationMs - 5_000L

internal fun resolveDesktopWindowsAudioLanguageSelection(
    success: VideoPlaybackUiState.Success, language: String?, state: PlayerState,
): DesktopWindowsAudioLanguageSelection? {
    if (success.isQualitySwitching || success.info.bvid.isBlank() || success.info.cid <= 0L ||
        desktopWindowsCurrentAudioLanguage(success) == language ||
        desktopWindowsAudioLanguageOptions(success).none { it.language == language } ||
        !state.positionSeconds.isFinite() || state.positionSeconds < 0.0 || state.loading) return null
    val positionMs = (state.positionSeconds * 1_000.0).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()
    return DesktopWindowsAudioLanguageSelection(PlaybackRequest.create(
        success.info.bvid, success.info.aid, success.info.cid, force = true,
        autoPlay = !state.paused && !state.ended, audioLang = language,
    ), positionMs)
}

/** Immutable popup receipt. Equal BV/CID or numeric native version cannot revive it. */
internal class DesktopWindowsVideoAudioSelection(
    val assembly: DesktopOriginalVideoOwnerAssembly,
    val accepted: DesktopOriginalVideoAcceptedPublication,
    val success: VideoPlaybackUiState.Success,
    val loadToken: Long,
    val presentationCurrent: () -> Boolean,
)

internal fun desktopWindowsAudioSelectionIdentityCurrent(
    capturedSource: DesktopOriginalVideoAcceptedPublication, currentSource: DesktopOriginalVideoAcceptedPublication?,
    capturedSuccess: VideoPlaybackUiState.Success, currentSuccess: VideoPlaybackUiState?,
    capturedLoadToken: Long, session: PlaybackSessionState,
): Boolean = currentSource === capturedSource && currentSuccess === capturedSuccess &&
    !capturedSuccess.isQualitySwitching && capturedLoadToken > 0L &&
    session.currentLoadRequestToken == capturedLoadToken &&
    capturedSuccess.info.bvid == capturedSource.request.bvid && capturedSuccess.info.cid == capturedSource.request.cid &&
    session.currentBvid == capturedSource.request.bvid && session.currentCid == capturedSource.request.cid
