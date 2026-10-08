package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.bangumi.BangumiPlayerState
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
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
) : DesktopOriginalBangumiNativePublication, DesktopOriginalBangumiSharedPlaybackPresenter {
    private val retired = AtomicBoolean(false)
    private val activated = AtomicBoolean(false)
    private val currentCaller = ThreadLocal<Job?>()
    private val callerKey = object : CoroutineContext.Key<CallerElement> {}
    private inner class CallerElement : ThreadContextElement<Job?> {
        override val key: CoroutineContext.Key<*> get() = callerKey
        override fun updateThreadContext(context: CoroutineContext): Job? = currentCaller.get().also {
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
    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        assertCurrent()
        return scope.launch(CallerElement()) { assembly.invocations.withInvocation { assertCurrent(); block() } }
    }
    override suspend fun beginEpisode(detail: BangumiDetail, episode: BangumiEpisode) {
        currentCoroutineContext().ensureActive(); assertCurrent()
        check(currentCaller.get() === currentCoroutineContext()[Job]) { "PGC load requires its actual Root invocation job" }
        // From this point the sole VM marker is mandatory, even during the
        // short first-capture window or a competing ordinary takeover.
        activated.set(true)
        episodeRetirementCapture?.invoke(detail, episode)
        portrait.captureBangumiPageRequest(this, detail, episode)
        currentCoroutineContext().ensureActive(); assertCurrent()
    }
    /** Only a fresh actual quality Job borrows the current accepted source.
     * It does not retire the episode, start a Store request or stop the player. */
    override suspend fun beginQualityReplacement(state: BangumiPlayerState.Success) {
        currentCoroutineContext().ensureActive(); assertCurrent()
        check(currentCaller.get() === currentCoroutineContext()[Job]) { "PGC quality requires its actual Root invocation job" }
        check(activated.get()) { "PGC quality requires an already installed episode" }
        portrait.captureBangumiQualityRequest(this, state)
        currentCoroutineContext().ensureActive(); assertCurrent()
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
        portrait.publishBangumiSource(this, currentCaller.get(), checkNotNull(currentState()), videoUrl, audioUrl,
            null, seekToMs, referer, dashManifest, playWhenReady = true)
    }
    override fun publishSegments(segmentUrls: List<String>, seekToMs: Long,
        resetPlayer: Boolean, referer: String) {
        assertCurrent(); currentCaller.get()?.ensureActive()
        val urls = segmentUrls.filter(String::isNotBlank)
        require(urls.isNotEmpty())
        portrait.publishBangumiSource(this, currentCaller.get(), checkNotNull(currentState()), urls.first(), null,
            urls, seekToMs, referer, null, playWhenReady = true)
    }
    override fun stopCurrentEpisode() { assertCurrent(); portrait.stopBangumiPlayback(this, currentCaller.get()) }
}
