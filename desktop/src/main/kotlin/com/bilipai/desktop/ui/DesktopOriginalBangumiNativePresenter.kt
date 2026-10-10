package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.bangumi.BangumiPlayerState
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import java.awt.EventQueue
import kotlin.coroutines.CoroutineContext

/** Required full PGC VM's producer lifetime on the installed original Assembly.
 * Per-coroutine Job is a ThreadContextElement view, never a latest-binding field.
 * Both new-source publication and same-receipt accepted recovery are implemented
 * by the existing concrete Portrait capture/native owner, not a new media actor.
 * Root retains this scope for Mini/covered routes and cancels it on takeover. */
internal class DesktopOriginalBangumiNativePresenter(
    private val assembly: DesktopOriginalVideoOwnerAssembly,
    private val currentAssembly: () -> DesktopOriginalVideoOwnerAssembly?,
    private val portrait: DesktopOriginalPortraitPlatformBinding,
    private val scope: CoroutineScope,
    private val currentState: () -> BangumiPlayerState.Success?,
    private val captureEpisodeRequest: suspend (DesktopOriginalVideoOwnerAssembly, (DesktopPlaybackAuthorizationReceipt, PlaybackSessionState) -> Unit) -> DesktopOriginalVideoRepositoryBinding,
    private val accountEpoch: () -> Long,
    private val authorizationRevision: () -> Long,
) : DesktopOriginalBangumiNativePublication, DesktopOriginalBangumiSharedPlaybackPresenter {
    private val retired = AtomicBoolean(false)
    private val activated = AtomicBoolean(false)
    private val currentCaller = ThreadLocal<Job?>()
    // Coroutine-local view of the exact failed origin and its playback intent.
    // The existing Portrait capture/native owner still owns parent -> child acceptance.
    private class NetworkRecovery(val source: DesktopOriginalVideoAcceptedPublication,
        val playWhenReady: Boolean, val parentCurrent: () -> Boolean) {
        var publicationState: BangumiPlayerState.Success? = null // synchronous lexical view only
    }
    private val currentRecovery = ThreadLocal<NetworkRecovery?>()
    private val callerKey = object : CoroutineContext.Key<CallerElement> {}
    private inner class CallerElement : ThreadContextElement<Job?> {
        private val launchedCaller = java.util.concurrent.atomic.AtomicReference<Job?>()
        fun actualLaunchCaller(): Job = checkNotNull(launchedCaller.get())
        override val key: CoroutineContext.Key<*> get() = callerKey
        override fun updateThreadContext(context: CoroutineContext): Job? = currentCaller.get().also {
            // First continuation of THIS scope.launch has its actual returned Job.
            // Nested withInvocation/withContext keeps the same element, never replaces it.
            launchedCaller.compareAndSet(null, checkNotNull(context[Job]))
            currentCaller.set(checkNotNull(context[Job]))
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) {
            if (oldState == null) currentCaller.remove() else currentCaller.set(oldState)
        }
    }
    fun owns(): Boolean = !retired.get() && scope.isActive && currentAssembly() === assembly && assembly.owns() &&
        (!activated.get() || assembly.playback.isDesktopBangumiPresenterCurrent(this))
    fun assertCurrent() {
        if (!owns()) throw CancellationException("Original PGC presenter replaced")
        currentCaller.get()?.let { caller ->
            caller.ensureActive()
            if (activated.get()) portrait.assertBangumiCallerCurrent(this, caller)
        }
    }
    /** Injected into the original VM's owned flows. A superseded quality/load
     * response cannot write Success before its later native publish rejects it. */
    fun commit(action: () -> Unit): Boolean {
        var applied = false
        return assembly.environment.commit {
            assertCurrent(); action(); applied = true
        } && applied
    }
    private var retirementCapture: (() -> Unit)? = null
    private var episodeRetirementCapture: ((BangumiDetail, BangumiEpisode) -> Unit)? = null
    fun installRetirementCapture(onRetirement: () -> Unit, beforeEpisode: (BangumiDetail, BangumiEpisode) -> Unit) {
        assertCurrent()
        check(retirementCapture == null && episodeRetirementCapture == null)
        retirementCapture = onRetirement
        episodeRetirementCapture = beforeEpisode
    }
    override fun onSharedPlaybackReplaced() {
        if (retired.compareAndSet(false, true)) {
            try { retirementCapture?.invoke() }
            finally { scope.cancel("Original shared playback taken over") }
        }
    }
    /** Root injects THIS launch into the complete original VM. Original invocation
     * services capture primary actions/heartbeat in the actual job; the PGC media
     * binding is captured only after the sole Store starts its new request. */
    fun launch(block: suspend CoroutineScope.() -> Unit, start: CoroutineStart = CoroutineStart.DEFAULT,
        recoverySource: DesktopOriginalVideoAcceptedPublication? = null, recoveryPlayWhenReady: Boolean = true,
        recoveryParentCurrent: () -> Boolean = { true }): Job {
        assertCurrent()
        val recovery = recoverySource?.let { NetworkRecovery(it, recoveryPlayWhenReady, recoveryParentCurrent) }
        return scope.launch(CallerElement() + currentRecovery.asContextElement(recovery), start = start) {
            assembly.invocations.withInvocation { assertCurrent(); block() }
        }
    }
    /** Read-only identity of the original PGC launch. Not the nested body Job
     * and never a later playbackLoadJob getter or a newly created lifetime. */
    internal suspend fun captureInitialDetailLaunchCaller(): Job {
        val context = currentCoroutineContext(); context.ensureActive(); assertCurrent()
        return checkNotNull(context[callerKey]).actualLaunchCaller().also { it.ensureActive() }
    }
    override suspend fun beginEpisode(detail: BangumiDetail, episode: BangumiEpisode) =
        beginEpisode(detail, episode, null, null)

    override suspend fun beginEpisodeWithAuthorizationRecapture(detail: BangumiDetail, episode: BangumiEpisode,
        isIntentCurrent: () -> Boolean,
        onRetired: (Job, () -> Boolean, (() -> Unit) -> Boolean) -> Unit) =
        beginEpisode(detail, episode, isIntentCurrent, onRetired)

    override fun launchAuthorizationRecapture(block: suspend CoroutineScope.() -> Unit): Job =
        launch(block, start = CoroutineStart.LAZY)

    private suspend fun beginEpisode(detail: BangumiDetail, episode: BangumiEpisode,
        isIntentCurrent: (() -> Boolean)?,
        onRetired: ((Job, () -> Boolean, (() -> Unit) -> Boolean) -> Unit)?) {
        currentCoroutineContext().ensureActive(); assertCurrent()
        check(currentCaller.get() === currentCoroutineContext()[Job]) { "PGC load requires its actual Root invocation job" }
        val launchCaller = captureInitialDetailLaunchCaller()
        activated.set(true)
        episodeRetirementCapture?.invoke(detail, episode)
        val baselineVersion = java.util.concurrent.atomic.AtomicLong(-1L)
        val baselineSource = java.util.concurrent.atomic.AtomicReference<com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot?>()
        val baselinePlaying = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val baselineIntents = java.util.concurrent.atomic.AtomicReference<Pair<Long, Long>?>()
        val capturedBinding = java.util.concurrent.atomic.AtomicReference<DesktopOriginalVideoRepositoryBinding?>()
        fun sourceCurrent(): Boolean {
            val actual = assembly.native.player.currentSourceSnapshot()
            val expected = baselineSource.get()
            return baselineVersion.get() >= 0L && baselineIntents.get()?.let(assembly.native.player::ownsPlaybackIntentSerials) == true &&
                assembly.native.player.currentSourceVersion == baselineVersion.get() &&
                (if (expected == null) actual == null else actual != null &&
                    actual.sourceVersion == expected.sourceVersion && actual.source == expected.source) &&
                !assembly.native.player.state.value.paused == baselinePlaying.get()
        }
        val request = if (onRetired == null || isIntentCurrent == null)
            portrait.captureBangumiPageRequest(this, detail, episode)
        else portrait.captureBangumiPageRequest(this, detail, episode) { value ->
            captureEpisodeRequest(value) { receipt, capturedState ->
                val metadataRevision = capturedBinding.get()?.authorizationRetirementRevision()
                    ?: return@captureEpisodeRequest
                // This notice is emitted only by the actual metadata mutation
                // of this immutable Binding. Old Job completion/receipt.current
                // is deliberately not the authority for its one-time consumption.
                lateinit var retirement: DesktopOriginalVideoAuthorizationRetirement
                fun current() = owns() && accountEpoch() == receipt.accountEpoch &&
                    authorizationRevision() == metadataRevision && isIntentCurrent() && sourceCurrent()
                fun admit(action: () -> Unit): Boolean {
                    var applied = false
                    return assembly.environment.commit {
                        val intents = baselineIntents.get()
                        if (intents != null) assembly.native.player.admitPlaybackIntentSerials(intents) {
                            if (retirement.isCurrent()) {
                                val apply = { if (retirement.isCurrent()) { action(); applied = true } }
                                val snapshot = baselineSource.get()
                                if (snapshot == null) apply()
                                else assembly.native.player.admitSourceSnapshot(snapshot, apply)
                            }
                        }
                    } && applied
                }
                retirement = DesktopOriginalVideoAuthorizationRetirement(this, receipt, capturedState,
                    ::current, assembly::captureLoadState,
                    { onRetired(launchCaller, retirement::isCurrent, ::admit) })
                if (retirement.isCurrent()) EventQueue.invokeLater { retirement.recaptureIfCurrent() }
            }.also { capturedBinding.set(it) }
        }
        // Freeze the original source/pause observation before nav/SPI may retire
        // the receipt; Portrait removes its transient capture when this Job ends.
        var frozen = false
        if (!request.admitCurrentMutation {
                val intents = assembly.native.player.capturePlaybackIntentSerials()
                assembly.native.player.admitPlaybackIntentSerials(intents) {
                    baselineIntents.set(intents)
                    baselineVersion.set(assembly.native.player.currentSourceVersion)
                    baselineSource.set(assembly.native.player.currentSourceSnapshot())
                    baselinePlaying.set(!assembly.native.player.state.value.paused)
                    frozen = true
                }
            } || !frozen) throw CancellationException("PGC authorization baseline retired")
        currentCoroutineContext().ensureActive(); assertCurrent()
    }
    /** Only a fresh actual quality Job borrows the current accepted source.
     * It does not retire the episode, start a Store request or stop the player. */
    override suspend fun beginQualityReplacement(state: BangumiPlayerState.Success) {
        currentCoroutineContext().ensureActive(); assertCurrent()
        check(currentCaller.get() === currentCoroutineContext()[Job]) { "PGC quality requires its actual Root invocation job" }
        check(activated.get()) { "PGC quality requires an already installed episode" }
        val recovery = currentRecovery.get()
        portrait.captureBangumiQualityRequest(this, state, recovery?.source, recovery?.parentCurrent ?: { true })
        currentCoroutineContext().ensureActive(); assertCurrent()
    }
    /** Only this actual recovery invocation borrows pending original Success for
     * media preparation. Root/VM observable state changes only after acceptance. */
    internal fun withNetworkRecoveryState(state: BangumiPlayerState.Success, action: () -> Unit) {
        assertCurrent()
        val recovery = checkNotNull(currentRecovery.get()) { "PGC recovery invocation required" }
        val previous = recovery.publicationState
        recovery.publicationState = state
        try {
            // Base VM's preferred-volume command is a short write to this exact parent.
            // The lexical wrapper does not hold admission during media preparation.
            assembly.section.withSourceCommandAdmission({ mutation ->
                var applied = false
                assembly.native.admitPlaybackDispatch(recovery.source) {
                    if (recovery.parentCurrent()) { mutation(); applied = true }
                } && applied
            }, action)
        } finally { recovery.publicationState = previous }
    }
    fun capturedPlaybackBindingOrNull(): DesktopOriginalVideoRepositoryBinding? {
        assertCurrent()
        return currentCaller.get()?.let { portrait.capturedBangumiRequestOrNull(this, it) }
    }
    fun capturedPlaybackBinding(): DesktopOriginalVideoRepositoryBinding {
        assertCurrent()
        return portrait.capturedBangumiRequest(this, checkNotNull(currentCaller.get()) {
            "PGC playurl requires its actual load/quality coroutine"
        })
    }
    fun ownsPlaybackSource(): Boolean {
        if (!owns() || !activated.get()) return false
        val state = currentState() ?: return false
        return portrait.ownsBangumiPlayback(this, state)
    }
    override fun canUseCachedPlayback(state: BangumiPlayerState.Success): Boolean =
        owns() && activated.get() && portrait.ownsBangumiPlayback(this, state) && state.cachedPlayData != null
    override fun publishDash(videoUrl: String, audioUrl: String?, seekToMs: Long,
        resetPlayer: Boolean, referer: String, dashManifest: String?) {
        assertCurrent(); currentCaller.get()?.ensureActive()
        portrait.publishBangumiSource(this, currentCaller.get(), currentRecovery.get()?.publicationState ?: checkNotNull(currentState()), videoUrl, audioUrl,
            null, seekToMs, referer, dashManifest, playWhenReady = currentRecovery.get()?.playWhenReady ?: true)
    }
    override fun publishSegments(segmentUrls: List<String>, seekToMs: Long,
        resetPlayer: Boolean, referer: String) {
        assertCurrent(); currentCaller.get()?.ensureActive()
        val urls = segmentUrls.filter(String::isNotBlank)
        require(urls.isNotEmpty())
        portrait.publishBangumiSource(this, currentCaller.get(), currentRecovery.get()?.publicationState ?: checkNotNull(currentState()), urls.first(), null,
            urls, seekToMs, referer, null, playWhenReady = currentRecovery.get()?.playWhenReady ?: true)
    }
    override fun stopCurrentEpisode() { assertCurrent(); portrait.stopBangumiPlayback(this, currentCaller.get()) }
}
