package com.bilipai.desktop.ui

import com.android.purebilibili.feature.bangumi.BangumiPlayerState
import com.android.purebilibili.feature.bangumi.BangumiPlayerViewModel
import com.android.purebilibili.feature.video.playback.audio.isPremiumAudioPlaybackFailure
import com.bilipai.desktop.data.DesktopRepository
import kotlinx.coroutines.CoroutineStart

/** Mechanical view of the retained PGC Entry recovery methods. Every collaborator
 * is the same existing owner/presenter/player/recovery actor; no lifetime, request,
 * publication, preference or retry-budget authority is constructed here. */
internal class DesktopOriginalBangumiPremiumAudioRecovery(
    private val owner: DesktopOriginalVideoOwnerAssembly,
    private val native: DesktopOriginalBangumiNativePresenter,
    private val player: DesktopOriginalMpvSectionControl,
    private val repository: DesktopRepository,
    private val viewModelProvider: () -> BangumiPlayerViewModel,
    private val recovery: DesktopOriginalPlaybackRecovery,
    private val success: () -> BangumiPlayerState.Success?,
    private val owns: () -> Boolean,
    private val withPresentationAdmission: (DesktopOriginalVideoAcceptedPublication, () -> Unit) -> Boolean,
) {
    private val viewModel get() = viewModelProvider()
    private var recoveryEpisode: Triple<Long, Long, Long>? = null

    fun failureSourceCurrent(state: BangumiPlayerState.Success,
        expected: DesktopOriginalVideoAcceptedPublication,
        failure: com.bilipai.desktop.player.PlayerFailure, revision: Long): Boolean {
        if (!owns() || viewModel.desktopPlaybackIntentRevision != revision ||
            owner.native.current() !== expected || !native.ownsPlaybackSource()) return false
        val actual = success() ?: return false
        return actual.currentEpisode.id == state.currentEpisode.id &&
            actual.currentEpisode.cid == state.currentEpisode.cid &&
            actual.seasonDetail.seasonId == state.seasonDetail.seasonId &&
            actual.quality == state.quality && actual.requestedAudioQuality == state.requestedAudioQuality &&
            desktopOriginalNativeFailureCurrent(player.nativePlayer, expected, failure)
    }
    fun admitFailureSource(state: BangumiPlayerState.Success,
        expected: DesktopOriginalVideoAcceptedPublication,
        failure: com.bilipai.desktop.player.PlayerFailure, revision: Long, action: () -> Unit): Boolean {
        var applied = false
        return withPresentationAdmission(expected) {
            owner.native.admitPlaybackDispatch(expected) {
                if (failureSourceCurrent(state, expected, failure, revision)) { action(); applied = true }
            }
        } && applied
    }
    fun isCurrentPremiumAudioFailure(state: BangumiPlayerState.Success,
        expected: DesktopOriginalVideoAcceptedPublication,
        failure: com.bilipai.desktop.player.PlayerFailure): Boolean {
        val code = com.bilipai.desktop.player.desktopPremiumAudioFailureCode(failure) ?: return false
        if (!isPremiumAudioPlaybackFailure(code, state.selectedAudioQuality, "mpv/audio-output", null)) return false
        val dash = state.cachedDash ?: return false
        if (state.cachedPlayData?.dash !== dash) return false
        val hiRes = dash.flac?.audio ?: return false
        val audio = state.audioUrl?.takeIf(String::isNotBlank) ?: return false
        val video = state.playUrl?.takeIf(String::isNotBlank) ?: return false
        val source = expected.nativeSource.source
        return source.audioUrl == audio && source.videoUrl == video &&
            (audio == hiRes.getValidUrl() || audio in hiRes.backupUrl.orEmpty()) &&
            dash.video.any { video == it.getValidUrl() || video in it.backupUrl.orEmpty() }
    }

    fun recoverPremiumAudio(state: BangumiPlayerState.Success,
        expected: DesktopOriginalVideoAcceptedPublication,
        failure: com.bilipai.desktop.player.PlayerFailure, revision: Long, identity: Any): Boolean {
        var position = 0L
        var playing = false
        var token = -1L
        var intents: Pair<Long, Long>? = null
        if (!admitFailureSource(state, expected, failure, revision) {
                if (isCurrentPremiumAudioFailure(state, expected, failure)) {
                    position = player.currentPosition.coerceAtLeast(0L)
                    playing = player.playWhenReady
                    token = owner.captureLoadState().currentLoadRequestToken
                    intents = player.nativePlayer.capturePlaybackIntentSerials()
                }
            }) return false
        val capturedIntents = intents ?: return false
        val receipt = expected.nativeSource.source.authorizationReceipt ?: return false
        // Operation-only intent checks survive THIS accepted child, but never
        // become the persistent publication predicate for later user controls.
        fun operationCurrent(): Boolean {
            val actual = success() ?: return false
            val load = owner.captureLoadState()
            return owns() && viewModel.desktopPlaybackIntentRevision == revision &&
                repository.sessionEpoch == receipt.accountEpoch &&
                repository.playbackAuthorizationRevision.value == receipt.revision &&
                load.currentLoadRequestToken == token && load.currentBvid == expected.request.bvid &&
                load.currentCid == expected.request.cid && actual.cachedPlayData === state.cachedPlayData &&
                actual.currentEpisode.id == state.currentEpisode.id &&
                actual.currentEpisode.cid == state.currentEpisode.cid &&
                actual.seasonDetail.seasonId == state.seasonDetail.seasonId &&
                actual.quality == state.quality && actual.requestedAudioQuality == state.requestedAudioQuality
        }
        fun parentCurrent() = operationCurrent() &&
            failureSourceCurrent(state, expected, failure, revision) &&
            player.nativePlayer.ownsPlaybackIntentSerials(capturedIntents)
        val ticket = DesktopOriginalNativeRecoveryTicket(expected, failure.attemptId)
        return viewModel.fallbackPremiumAudioPlayback(state, revision, position, playing,
            launchLazy = { block -> native.launch(block, CoroutineStart.LAZY,
                expected, playing, ::parentCurrent, ticket, ::operationCurrent) },
            admitOrigin = { mutation -> admitFailureSource(state, expected, failure, revision) {
                player.nativePlayer.admitPlaybackIntentSerials(capturedIntents) {
                    if (parentCurrent()) mutation()
                }
            } },
            currentOrigin = ::parentCurrent,
            publication = { pending, publish, complete ->
                native.withPremiumAudioRecoveryState(pending, publish) {
                    complete()
                    recovery.ready() // READY reports success; never resets original budgets.
                }
            },
            onFailure = { recovery.recoveryFailed(identity) })
    }

    fun queuePlaybackFailure(state: BangumiPlayerState.Success,
        expected: DesktopOriginalVideoAcceptedPublication,
        failure: com.bilipai.desktop.player.PlayerFailure, revision: Long,
        evidence: DesktopOriginalPlaybackFailureEvidence, identity: Any) {
        var position = 0L
        var playing = false
        if (!admitFailureSource(state, expected, failure, revision) {
                position = player.currentPosition.coerceAtLeast(0L)
                playing = player.playWhenReady // failure makes isPlaying false, not the user's intent
            }) return
        val episode = Triple(state.seasonDetail.seasonId, state.currentEpisode.id, state.currentEpisode.cid)
        if (recoveryEpisode != episode) { recovery.beginLoad(false); recoveryEpisode = episode }
        // Original shared policy: reliable network refresh and proven Hi-Res
        // AO-init recovery; unrelated decoder/DRM/output errors keep their behavior.
        recovery.fail(DesktopOriginalPlaybackRecoveryTicket(identity,
            evidence.copy(automaticRecovery = evidence.automaticRecovery &&
                (evidence.reason == PlaybackFailureReason.Network || evidence.premiumAudio),
                decoder = false), false, position, playing,
            { failureSourceCurrent(state, expected, failure, revision) },
            { action, _, _ ->
                if (action == com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.FALLBACK_PREMIUM_AUDIO)
                    recoverPremiumAudio(state, expected, failure, revision, identity)
                else if (action != com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.RETRY_NETWORK) false
                else {
                    var retryPosition = 0L
                    var retryPlaying = false
                    val captured = admitFailureSource(state, expected, failure, revision) {
                        retryPosition = player.currentPosition.coerceAtLeast(0L)
                        retryPlaying = player.playWhenReady
                    }
                    if (!captured) false else {
                        // A pause/seek during HTTP keeps the usable failed parent;
                        // only its final native parent gate consumes this predicate.
                        val parentCurrent = {
                            failureSourceCurrent(state, expected, failure, revision) &&
                                player.playWhenReady == retryPlaying && player.currentPosition == retryPosition
                        }
                        viewModel.retryNetworkPlayback(state, revision, retryPosition,
                            launchLazy = { block -> native.launch(block, CoroutineStart.LAZY,
                                expected, retryPlaying, parentCurrent) },
                            admitOrigin = { mutation -> admitFailureSource(state, expected, failure, revision) {
                                if (parentCurrent()) mutation()
                            } },
                            currentOrigin = parentCurrent,
                            publication = native::withNetworkRecoveryState,
                            onFailure = { responseFailure ->
                                queuePlaybackFailure(state, expected, failure, revision,
                                    desktopOriginalApiFailure(desktopOriginalVideoLoadError(responseFailure)), responseFailure)
                            })
                    }
                }
            }))
    }

}
