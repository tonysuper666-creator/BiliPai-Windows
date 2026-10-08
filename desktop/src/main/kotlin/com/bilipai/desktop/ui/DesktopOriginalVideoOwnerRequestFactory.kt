package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.bilipai.desktop.data.DesktopLoginRepository
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.data.DesktopRepository
import com.bilipai.desktop.data.DesktopSearchPreferences
import com.bilipai.desktop.player.DesktopSubtitleAssets
import com.bilipai.desktop.player.PlayerPreferences
import kotlinx.coroutines.*

/** Actual request assembly, with this coroutine's original SessionState snapshot,
 * Job, immutable authorization and native baseline. prepareRequestMedia is the
 * sole byte-cache/media decorator; it must retain these values and call the
 * installed resolved-CID/token helper synchronously at accept. It cannot read a
 * latest Binding or make resolver completion retire the accepted source's Job.
 */
internal class DesktopOriginalVideoOwnerRequestFactory(
    private val repository: DesktopRepository,
    private val login: DesktopLoginRepository,
    private val capturedEpoch: Long,
    private val entryScope: CoroutineScope,
    private val stillEntryOwned: () -> Boolean,
    private val commitIfEntryCurrent: ((() -> Unit) -> Boolean),
    private val preferences: () -> PlayerPreferences,
    private val capabilities: DesktopOriginalVideoPlaybackCapabilities,
    private val auto1080pEnabled: () -> Boolean,
    private val directedTrafficEnabled: () -> Boolean,
    private val isMobileData: () -> Boolean,
    private val subtitleAssets: DesktopSubtitleAssets,
    private val privacy: DesktopSearchPreferences,
    private val onPlaybackAuthorizationRetired: (DesktopPlaybackAuthorizationReceipt, PlaybackSessionState) -> Unit,
    private val prepareRequestMedia: (DesktopOriginalVideoOwnerRequestRepository, PlaybackSessionState,
        Long, Job, DesktopOriginalVideoNativeOwner) -> DesktopOriginalVideoMediaPort,
) {
    private val entryJob = checkNotNull(entryScope.coroutineContext[Job])
    private val token = DesktopOriginalVideoTokenRefreshBinding(login, entryJob,
        stillEntryOwned, commitIfEntryCurrent)

    /** One extra same-owner request facet for original Portrait/Story effects.
     * Captures the ACTUAL calling coroutine Job and original playback state. */
    suspend fun captureBinding(state: PlaybackSessionState): DesktopOriginalVideoRepositoryBinding {
        currentCoroutineContext().ensureActive()
        if (!entryScope.isActive || !stillEntryOwned()) throw CancellationException("Original facet entry retired")
        return DesktopOriginalVideoRepositoryBinding.capture(repository, capturedEpoch,
            entryJob, stillEntryOwned, commitIfEntryCurrent, preferences(),
            state.currentRequest?.videoCodecOverride, state.blockedVideoCodecs,
            capabilities.isAv1Supported(), auto1080pEnabled, directedTrafficEnabled,
            isMobileData, token::available, token::refresh,
            { receipt -> onPlaybackAuthorizationRetired(receipt, state) })
    }

    suspend fun capture(state: PlaybackSessionState,
        native: DesktopOriginalVideoNativeOwner): DesktopOriginalVideoPlaybackInvocation {
        currentCoroutineContext().ensureActive()
        if (!entryScope.isActive || !stillEntryOwned()) throw CancellationException("Original load entry retired")
        val requestJob = checkNotNull(currentCoroutineContext()[Job])
        val pageTransition = currentCoroutineContext()[DesktopOriginalVideoPageTransitionIntent]
            ?.capture(state, requestJob)
        val binding = DesktopOriginalVideoRepositoryBinding.capture(repository, capturedEpoch,
            entryJob, stillEntryOwned, commitIfEntryCurrent, preferences(),
            state.currentRequest?.videoCodecOverride, state.blockedVideoCodecs,
            capabilities.isAv1Supported(), auto1080pEnabled, directedTrafficEnabled,
            isMobileData, token::available, token::refresh,
            { receipt -> onPlaybackAuthorizationRetired(receipt, state) })
        // This Factory borrows only a genuinely accepted page's immutable subject.
        // All unrelated/new full requests keep the existing full-load path.
        var pageSuccessor: DesktopOriginalVideoPageSuccessorProof? = null
        if (pageTransition == null) {
            val expected = native.current()
            val subject = expected?.pageSubject
            if (expected != null && subject != null && subject.sameOriginalRequestIdentity(state)) {
                if (expected.nativeSource.source.authorizationReceipt != binding.receipt ||
                    !native.admitPlaybackDispatch(expected) {
                        binding.assertCurrent()
                        pageSuccessor = subject.captureSuccessor(state, requestJob, expected)
                    }) throw CancellationException("Original accepted page capture retired")
            }
        }
        // Page media remains Unknown bootstrap provenance, including successors.
        val bootstrapOrigin = if (pageTransition == null && pageSuccessor == null)
            currentCoroutineContext()[DesktopVideoBootstrapAccepted] else null
        val bootstrap = bootstrapOrigin?.let { DesktopVideoBootstrapReadSource.capture(it, state, binding) }
        val raw = createDesktopOriginalVideoOwnerRequestRepositoryWithPageSuccessor(repository, binding,
            subtitleAssets, privacy, bootstrapOrigin, pageTransition, pageSuccessor,
            { receipt, stillOwned -> repository.ownedHomeVisitorInitialized(receipt.accountEpoch, stillOwned) })
        var baseline: Long? = null
        if (!binding.admitCurrentMutation {
                baseline = pageSuccessor?.let {
                    if (!native.isCurrent(it.expected))
                        throw CancellationException("Original page successor baseline retired")
                    it.expected.sourceVersion
                } ?: native.player.currentSourceVersion
            })
            throw CancellationException("Original media baseline capture retired")
        val media = prepareRequestMedia(raw, state, checkNotNull(baseline), requestJob, native)
        currentCoroutineContext().ensureActive(); binding.assertCurrent()
        return DesktopOriginalVideoPlaybackInvocation(raw, media, binding::assertCurrent, bootstrap)
    }
}
