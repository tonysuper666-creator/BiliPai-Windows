package com.bilipai.desktop.player

import com.android.purebilibili.data.model.response.Dash
import com.android.purebilibili.data.model.response.DashVideo
import com.android.purebilibili.data.model.response.getBestVideo
import com.android.purebilibili.feature.video.playback.audio.*
import com.android.purebilibili.feature.video.viewmodel.*

/** A request policy, never a second protocol schema: media and audio decisions remain original models. */
internal data class DesktopDashSelection(
    val video: DashVideo?,
    val audio: AudioSelectionDecision,
    val firstCodec: String,
    val secondCodec: String,
)

internal fun resolveDesktopDashSelection(
    dash: Dash,
    quality: Int,
    preferences: PlayerPreferences,
    codecOverride: String? = null,
    blockedCodecs: Set<String> = emptySet(),
    isHevcSupported: Boolean = true,
    isAv1Supported: Boolean = true,
    isDolbyAudioSupported: Boolean = true,
    isDolbyAudioSoftwareDecoded: Boolean = true,
): DesktopDashSelection {
    val normalized = preferences.normalized()
    val first = resolveEffectiveVideoCodecPreference(codecOverride, normalized.videoCodecPreference, blockedCodecs)
    val second = resolveEffectiveVideoSecondCodecPreference(codecOverride, normalized.videoSecondCodecPreference)
    val video = dash.getBestVideo(quality, first, second, isHevcSupported,
        resolveEffectiveAv1Support(isAv1Supported, blockedCodecs))
    val requested = resolveRequestedAudioQuality(normalized.defaultAudioQuality, normalized.lastSelectedAudioQuality)
    val audio = resolveAudioStreamSelection(dash, requested, normalized.speed.toFloat(),
        isDolbyAudioSupported = isDolbyAudioSupported,
        isDolbyAudioSoftwareDecoded = isDolbyAudioSoftwareDecoded)
    return DesktopDashSelection(video, audio, first, second)
}

/** This is a Windows client API option: the source's failure fallback always wins over global intent. */
internal fun resolveMpvHardwareDecoding(enabled: Boolean, sourceRequiresSoftware: Boolean): String =
    if (enabled && !sourceRequiresSoftware) "auto-safe" else "no"
