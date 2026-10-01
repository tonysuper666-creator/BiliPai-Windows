package com.bilipai.desktop.player

import com.android.purebilibili.feature.video.playback.audio.*
import com.bilipai.desktop.data.PlaybackSource as ResolvedSource
import com.bilipai.desktop.player.platform.DesktopPremiumAudioMedia3ErrorCodes
import com.bilipai.desktop.player.platform.DesktopPremiumAudioMpvErrors
import java.net.URI

/** Only a proven native audio-output init error is currently mapped. Generic/video decoder errors stay unclassified. */
internal fun desktopPremiumAudioFailureCode(failure: PlayerFailure): Int? =
    if(failure.kind == PlayerFailureKind.AUDIO_OUTPUT && failure.nativeCode == DesktopPremiumAudioMpvErrors.MPV_ERROR_AO_INIT_FAILED)
        DesktopPremiumAudioMedia3ErrorCodes.ERROR_CODE_AUDIO_TRACK_INIT_FAILED else null

internal sealed interface DesktopPremiumAudioRecoveryPlan {
    data object NotApplicable : DesktopPremiumAudioRecoveryPlan
    data class Unavailable(val reason: Reason) : DesktopPremiumAudioRecoveryPlan
    enum class Reason { MISSING_CACHED_DASH, NO_STANDARD_AUDIO, NO_PLAYABLE_STANDARD_URL }
    class Ready(val source: ResolvedSource) : DesktopPremiumAudioRecoveryPlan {
        override fun toString() = "PremiumAudioRecoveryPlan.Ready(selectedQuality=${source.audioSelection?.selectedPreferenceId})"
    }
}

/** Original selector decides the standard stream. Retain user's HiRes request; only effective current audio falls back. */
internal fun resolveDesktopPremiumAudioRecovery(
    source: ResolvedSource,
    failure: PlayerFailure,
    playbackSpeed: Double,
): DesktopPremiumAudioRecoveryPlan {
    val selected = source.audioSelection ?: return DesktopPremiumAudioRecoveryPlan.NotApplicable
    val code = desktopPremiumAudioFailureCode(failure) ?: return DesktopPremiumAudioRecoveryPlan.NotApplicable
    if(!isPremiumAudioPlaybackFailure(code, selected.selectedPreferenceId, "mpv/audio-output", null))
        return DesktopPremiumAudioRecoveryPlan.NotApplicable
    val dash = source.cachedDashData ?: return DesktopPremiumAudioRecoveryPlan.Unavailable(DesktopPremiumAudioRecoveryPlan.Reason.MISSING_CACHED_DASH)
    val decision = resolveAudioStreamSelection(dash, AUDIO_QUALITY_AUTO, playbackSpeed.toFloat())
    val standard = decision.selected?.takeIf { it.kind == AudioStreamKind.STANDARD }
        ?: return DesktopPremiumAudioRecoveryPlan.Unavailable(DesktopPremiumAudioRecoveryPlan.Reason.NO_STANDARD_AUDIO)
    fun normalize(address: String): String? {
        val value = if(address.startsWith("//")) "https:$address" else address
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf { uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null }
    }
    val audioUrl = normalize(standard.track.getValidUrl())
        ?: return DesktopPremiumAudioRecoveryPlan.Unavailable(DesktopPremiumAudioRecoveryPlan.Reason.NO_PLAYABLE_STANDARD_URL)
    val audio = decision.copy(requestedPreferenceId = selected.requestedPreferenceId, fallbackReason = AudioFallbackReason.DECODER_ERROR)
    return DesktopPremiumAudioRecoveryPlan.Ready(source.copy(audioUrl = audioUrl,
        audioAlternatives = standard.track.backupUrl.orEmpty().mapNotNull(::normalize).filter { it != audioUrl }.distinct(), audioSelection = audio))
}

/** Future retry should retain a temporary decoder fallback without overwriting the user's persisted HiRes preference. */
internal fun effectiveAudioPreferenceAfterFailure(selection: AudioSelectionDecision?): Int? =
    selection?.let { if(it.fallbackReason == AudioFallbackReason.DECODER_ERROR) it.effectivePreferenceId else it.requestedPreferenceId }

/** A refreshed AAC stream retains the same video's failed HiRes intent without selecting it again. */
internal fun retainDesktopPremiumAudioFallback(source: ResolvedSource, previous: AudioSelectionDecision?): ResolvedSource {
    if (previous?.fallbackReason != AudioFallbackReason.DECODER_ERROR) return source
    val selected = source.audioSelection ?: return source
    if (selected.selected?.kind != AudioStreamKind.STANDARD || selected.effectivePreferenceId != AUDIO_QUALITY_AUTO) return source
    return source.copy(audioSelection = selected.copy(requestedPreferenceId = previous.requestedPreferenceId,
        fallbackReason = AudioFallbackReason.DECODER_ERROR))
}

/** Uses the real native recovery operation: preserve actual CDN video, headers, subtitles, owner, position and pause. */
internal fun applyDesktopPremiumAudioRecovery(
    player: MpvPlayer,
    failure: PlayerFailure,
    plan: DesktopPremiumAudioRecoveryPlan.Ready,
    accountIsCurrent: () -> Boolean,
): Boolean {
    if(desktopPremiumAudioFailureCode(failure) == null || plan.source.audioSelection?.selectedPreferenceId != AUDIO_QUALITY_AUTO ||
        plan.source.audioSelection?.fallbackReason != AudioFallbackReason.DECODER_ERROR) return false
    val owned = player.currentSourceSnapshot()?.takeIf { it.sourceVersion == failure.sourceVersion } ?: return false
    val state = player.state.value
    if(state.failure?.attemptId != failure.attemptId || !accountIsCurrent()) return false
    val replacement = owned.source.copy(audioUrl = plan.source.audioUrl)
    return player.recoverSource(failure.sourceVersion, replacement, state.positionSeconds, state.paused,
        forceSoftwareDecoding = false, expectedFailureAttemptId = failure.attemptId)
}
