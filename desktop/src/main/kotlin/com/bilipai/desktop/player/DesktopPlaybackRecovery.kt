package com.bilipai.desktop.player

import com.bilipai.desktop.player.platform.DesktopMedia3ErrorCodes as PlaybackException
import com.android.purebilibili.feature.plugin.*
import com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction
import com.android.purebilibili.feature.video.state.decidePlayerErrorRecovery
import com.bilipai.desktop.data.PlaybackSource as ResolvedPlaybackSource

/** Maps native evidence into the unchanged upstream recovery policy. */
internal class DesktopPlaybackRecoveryBudget {
    var retries = 0; private set
    var cdnSwitches = 0; private set

    fun action(failure: PlayerFailure, hasAlternatives: Boolean): PlayerErrorRecoveryAction = decidePlayerErrorRecovery(
        errorCode = if (failure.kind == PlayerFailureKind.NETWORK) {
            when {
                failure.httpStatus != null -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                failure.networkTimedOut -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                else -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            }
        } else -1,
        hasCdnAlternatives = hasAlternatives, retryCount = retries, maxRetries = 3,
        cdnSwitchCount = cdnSwitches, maxCdnSwitches = 2,
        isDecoderLikeFailure = failure.kind == PlayerFailureKind.DECODER,
    )

    fun consume(action: PlayerErrorRecoveryAction): Long = when (action) {
        PlayerErrorRecoveryAction.SWITCH_CDN -> { cdnSwitches++; 500L }
        PlayerErrorRecoveryAction.RETRY_NETWORK -> { retries++; (1_000L * (1 shl (retries - 1))).coerceAtMost(8_000L) }
        PlayerErrorRecoveryAction.RETRY_DECODER_FALLBACK, PlayerErrorRecoveryAction.RETRY_NON_NETWORK -> { retries++; 0L }
        else -> 0L
    }

    fun ready() { retries = 0; cdnSwitches = 0 }
}

/** Pair only signed URLs returned for these exact streams, including audio-only mirrors. */
internal fun authorizedPlaybackCandidates(source: ResolvedPlaybackSource,
    health: Map<String, CdnCandidateHealth> = emptyMap()): List<PlaybackCdnCandidate> {
    if (source.progressiveSegments.size > 1)
        return listOf(PlaybackCdnCandidate(source.videoUrl, source.audioUrl, PlaybackCdnCandidateSource.ORIGINAL))
    val video = (listOf(source.videoUrl) + source.videoAlternatives).filter(String::isNotBlank).distinct()
    val audio = (listOfNotNull(source.audioUrl) + source.audioAlternatives).filter(String::isNotBlank).distinct()
    val count = maxOf(video.size, audio.size)
    val pairedVideo = (0 until count).map { video.getOrElse(it) { video.first() } }
    val pairedAudio = if (audio.isEmpty()) emptyList() else (0 until count).map { audio.getOrElse(it) { audio.first() } }
    val candidates = buildPlaybackCdnCandidates(pairedVideo, pairedAudio)
    val order = sortAuthorizedCdnCandidates(buildAuthorizedCdnCandidates(video), health).map { it.url }
    return candidates.sortedBy { order.indexOf(it.videoUrl) }
}

/** Only a loaded decoded track acknowledges media readiness for replenishing the original budget. */
internal fun isMediaReadyForRecovery(state: PlayerState): Boolean = !state.loading && !state.ended && state.error == null &&
    state.failure == null && (state.videoCodec != null || state.audioCodec != null)
