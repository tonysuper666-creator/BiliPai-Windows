package com.bilipai.desktop.ui

import com.android.purebilibili.data.repository.FollowStateChange
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.cache.DesktopMediaByteCache
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/** Existing Root/Window effects assembled by their sole concrete bindings. The
 * factory below directly builds every request/media/native/Runtime/domain owner;
 * it does not replace these effects with booleans, empty queues or callbacks. */
internal class DesktopOriginalVideoRootEntryPorts(
    val settings: DesktopOriginalPlayerSettingsContext,
    val root: DesktopOriginalCommentRootOwner,
    val effects: DesktopOriginalVideoOwnerEffects,
    val capabilities: DesktopOriginalVideoPlaybackCapabilities,
    val progress: DesktopOriginalVideoProgressPort,
    val interactionAnalytics: DesktopOriginalVideoInteractionAnalytics,
    val confirmFollow: (FollowStateChange) -> Unit,
    val feedback: (String) -> Unit,
    val applyPreferredVolume: (DesktopOriginalMpvSectionControl) -> Unit,
    val dashSegmentRequestsEnabled: () -> Boolean,
    val diagnosticLoggingEnabled: () -> Boolean,
    val logSeek: (Long, Long, Long, Long) -> Unit,
    val resumeEndedSource: () -> Unit,
    val auto1080pEnabled: () -> Boolean,
    val directedTrafficEnabled: () -> Boolean,
    val awaitNativeInitialization: suspend () -> Unit,
    val todayWatchFeedback: DesktopTodayWatchFeedbackWriteBinding,
)

/** Install once per Root epoch. All APIs, credential state, cache, MPV, Runtime
 * and subtitle files are the already existing app instances. Construction does
 * no network/native load; requireAssembly is called only after this real factory
 * has been installed and old producers (if any) have genuinely drained. */
internal class DesktopOriginalVideoRootFactory(
    private val context: DesktopPluginContext,
    private val repository: DesktopRepository,
    private val login: DesktopLoginRepository,
    private val runtime: DesktopPluginRuntime,
    private val player: MpvPlayer,
    private val subtitleAssets: DesktopSubtitleAssets,
    private val mediaCache: DesktopMediaByteCache,
    private val privacy: DesktopSearchPreferences,
    private val preferences: () -> PlayerPreferences,
    private val entryPorts: (DesktopOriginalVideoRootGate) -> DesktopOriginalVideoRootEntryPorts,
    private val onPrimaryVipReceiptRetired: (DesktopPlaybackAuthorizationReceipt) -> Unit,
    private val onPreparation: (DesktopOriginalMediaCachePreparation) -> Unit,
) {
    private data class Built(val assembly: DesktopOriginalVideoOwnerAssembly,
        val runtime: DesktopOriginalVideoRootRuntimeBinding, val gate: DesktopOriginalVideoRootGate,
        val ports: DesktopOriginalVideoRootEntryPorts, val requests: DesktopOriginalVideoOwnerRequestFactory,
        val media: DesktopOriginalVideoRootMediaFactory)
    private val built = AtomicReference<Built?>()

    fun create(gate: DesktopOriginalVideoRootGate): DesktopOriginalVideoOwnerAssembly {
        check(gate.owns())
        val ports = entryPorts(gate)
        require(ports.settings.pluginContext.store === context.store)
        require(ports.root.operations.expectedEpoch == gate.capturedEpoch)
        val owner = AtomicReference<DesktopOriginalVideoOwnerAssembly?>()
        fun assembly() = checkNotNull(owner.get()) { "Original video construction has not completed" }
        val media = DesktopOriginalVideoRootMediaFactory(repository, mediaCache, gate, ::assembly,
            onPreparation, ports.awaitNativeInitialization)
        val runtimeBinding = DesktopOriginalVideoRootRuntimeBinding(repository, runtime, gate) { assembly().native }
        val readbacks = DesktopOriginalVideoEntryReadbacks(repository, gate.capturedEpoch,
            gate.scope, gate::owns, gate::commitEntry)
        val requests = DesktopOriginalVideoOwnerRequestFactory(repository, login, gate.capturedEpoch,
            gate.scope, gate::owns, gate::commitEntry, preferences, ports.capabilities,
            ports.auto1080pEnabled, ports.directedTrafficEnabled, ports.effects.network::isMobileData,
            subtitleAssets, privacy, onPrimaryVipReceiptRetired, media::request)
        try {
            val value = DesktopOriginalVideoOwnerAssembly.create(context, ports.settings, repository,
                gate.capturedEpoch, gate.scope, gate::owns, gate::commitEntry,
                player, DesktopRepositoryPlaybackPublication(repository), { preferences().muted },
                runtimeBinding.sourceVersions, runtimeBinding::accepted, media::admission,
                captureInvocation = { state, native ->
                    ports.awaitNativeInitialization()
                    requests.capture(state, native)
                },
                readbacks, media::accepted,
                createPlugins = { native, invocations -> DesktopOriginalVideoOwnerPluginBridge(runtime, native,
                    invocations, runtimeBinding::generation, invocations::captureCurrentRequestAdmission,
                    runtimeBinding::calls) },
                ports.confirmFollow, ports.feedback, readbacks::hasPrimarySession,
                readbacks::hasPrimaryAccessToken, readbacks::primaryMid, readbacks::cooldownRemainingMs,
                ports.progress, ports.capabilities, ports.applyPreferredVolume, ports.dashSegmentRequestsEnabled,
                ports.logSeek, ports.diagnosticLoggingEnabled, ports.resumeEndedSource,
                ports.root, ports.interactionAnalytics, ports.effects, ports.todayWatchFeedback)
            owner.set(value)
            built.set(Built(value, runtimeBinding, gate, ports, requests, media))
            gate.scope.coroutineContext[Job]!!.invokeOnCompletion { runtimeBinding.close() }
            return value
        } catch (error: Throwable) { gate.retire(); runtimeBinding.close(); throw error }
    }

    fun adopt(value: DesktopOriginalVideoOwnerAssembly, handoff: DesktopOrdinaryPlaybackHandoff): Boolean {
        val construction = built.get()?.takeIf { it.assembly === value } ?: return false
        construction.runtime.prepareInheritance(handoff)
        if (value.adopt(handoff) == null) return false
        value.loadInheritedDetails(handoff)
        return true
    }

    /** Required original Portrait/Story API facet. Never a completed Binding,
     * latest receipt or another Retrofit/client. Capture inside each actual Job. */
    suspend fun captureRequest(value: DesktopOriginalVideoOwnerAssembly): DesktopOriginalVideoRepositoryBinding {
        val construction = checkNotNull(built.get()?.takeIf { it.assembly === value })
        construction.ports.awaitNativeInitialization()
        currentCoroutineContext().ensureActive()
        if (!value.owns() || !construction.gate.owns()) throw CancellationException("Original request facet retired")
        return construction.requests.captureBinding(value.captureLoadState())
    }

    fun sourceVersions(value: DesktopOriginalVideoOwnerAssembly): kotlinx.coroutines.flow.StateFlow<Long?> =
        checkNotNull(built.get()?.takeIf { it.assembly === value }).runtime.sourceVersions

    fun gate(value: DesktopOriginalVideoOwnerAssembly): DesktopOriginalVideoRootGate =
        checkNotNull(built.get()?.takeIf { it.assembly === value }).gate

    fun entryPorts(value: DesktopOriginalVideoOwnerAssembly): DesktopOriginalVideoRootEntryPorts =
        checkNotNull(built.get()?.takeIf { it.assembly === value }).ports

    fun resumeEndedSource(value: DesktopOriginalVideoOwnerAssembly) {
        val construction = checkNotNull(built.get()?.takeIf { it.assembly === value })
        val accepted = value.native.current() ?: throw CancellationException("Original replay source retired")
        val media = value.native.acceptedMedia(construction.media::accepted)
        media.withPlaybackIntent(0L, true) {
            media.accept(accepted.nativeSource.source.copy(startPositionSeconds = 0.0, startPaused = false))
        }
    }

    /** Overlay's lock-order-safe predicate: only existing atomic references and
     * StateFlow values. Overlay itself guards its actual native source version. */
    fun isPresentationCurrent(value: DesktopOriginalVideoOwnerAssembly,
        expected: DesktopOriginalVideoAcceptedPublication): Boolean {
        val construction = built.get()?.takeIf { it.assembly === value } ?: return false
        val receipt = expected.nativeSource.source.authorizationReceipt ?: return false
        return construction.runtime.publishedReference() === expected && construction.gate.owns() && value.owns() &&
            repository.sessionEpochFlow.value == receipt.accountEpoch &&
            repository.playbackAuthorizationRevision.value == receipt.revision
    }

    /** Store -> entry; the short native identity verification returns before
     * action enters Overlay.requestLock. Never wrap Overlay in native.admit. */
    fun withPresentationAdmission(value: DesktopOriginalVideoOwnerAssembly,
        expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
        val construction = built.get()?.takeIf { it.assembly === value } ?: return false
        var applied = false
        return try {
            repository.withPrimaryPlaybackAdmission(construction.gate.capturedEpoch,
                { isPresentationCurrent(value, expected) }) {
                construction.gate.commitEntry {
                    if (isPresentationCurrent(value, expected) && value.native.isCurrent(expected)) {
                        action() // No native lock remains held after isCurrent.
                        applied = true
                    }
                } && applied
            }
        } catch (_: CancellationException) { false }
    }

    /** Capture the real PluginBridge token before admission retires. VM.close
     * uses its captured old generation; cleanup never reads a successor source. */
    fun beforeRetire(value: DesktopOriginalVideoOwnerAssembly) {
        built.get()?.takeIf { it.assembly === value && it.gate.owns() }?.let {
            value.environment.plugins.capturePlaybackDispatch()
        }
    }

    fun afterDrain(value: DesktopOriginalVideoOwnerAssembly) {
        built.get()?.takeIf { it.assembly === value }?.let { previous ->
            if (built.compareAndSet(previous, null)) previous.runtime.close()
        }
    }

    /** Legacy Card pageIndex has no original PlaylistItem field. Only the missing
     * CID/non-first-P path uses this SAME captured raw protocol before replacing
     * that selected original playlist row and beginning the original VM load. */
    suspend fun resolveQueueCard(value: DesktopOriginalVideoOwnerAssembly,
        card: VideoCard, stillCurrent: () -> Boolean): VideoCard {
        if (card.preferredCid > 0 || card.pageIndex <= 0) return card
        val construction = checkNotNull(built.get()?.takeIf { it.assembly === value })
        val gate = construction.gate
        fun owns() = value.owns() && gate.owns() && stillCurrent()
        currentCoroutineContext().ensureActive()
        if (!owns()) throw CancellationException("Original queue CID request retired")
        construction.ports.awaitNativeInitialization()
        val preferences = preferences()
        val token = DesktopOriginalVideoTokenRefreshBinding(login, checkNotNull(gate.scope.coroutineContext[Job]),
            ::owns, gate::commitEntry)
        val binding = DesktopOriginalVideoRepositoryBinding.capture(repository, gate.capturedEpoch,
            checkNotNull(gate.scope.coroutineContext[Job]), ::owns, gate::commitEntry,
            preferences, null, emptySet(), construction.ports.capabilities.isAv1Supported(),
            construction.ports.auto1080pEnabled, construction.ports.directedTrafficEnabled,
            construction.ports.effects.network::isMobileData, token::available, token::refresh)
        val info = binding.rawRepository.getVideoInfoOnly(card.bvid, 0L, 0L).getOrThrow()
        currentCoroutineContext().ensureActive(); binding.assertCurrent()
        val page = info.pages.getOrNull(card.pageIndex)
            ?: throw IllegalArgumentException("The selected video part is unavailable")
        require(page.cid > 0L && info.bvid == card.bvid)
        return card.copy(preferredCid = page.cid)
    }
}
