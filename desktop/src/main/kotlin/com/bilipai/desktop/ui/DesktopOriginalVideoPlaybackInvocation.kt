package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.bilipai.desktop.player.PlaybackSource
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/** One actual load/quality coroutine's views of the SAME Repository and publication.
 * The factory captures the existing authorization once; all original async children
 * inherit that receipt. Nothing here constructs an API, player, cache or account.
 */
internal class DesktopOriginalVideoPlaybackInvocation(
    val repository: DesktopOriginalVideoLoadRepository,
    val media: DesktopOriginalVideoMediaPort,
    private val assertCurrentPort: () -> Unit,
) {
    fun assertCurrent() = assertCurrentPort()
}

/** Required synchronous view of the actual current playback-account projection.
 * These reads are admitted by the same Store/entry; they do not fetch credentials or
 * invent an authorization for subsequent asynchronous calls.
 */
internal interface DesktopOriginalVideoPlaybackStatus {
    fun isPlaybackLoggedIn(): Boolean
    fun isPlaybackVip(): Boolean
    fun isUsingDedicatedPlaybackAccount(): Boolean
    fun isAppApiCoolingDown(): Boolean
}

/** Request-context forwarding for the original, single persistent UseCase.
 *
 * Network methods require an invocation installed by launch/withInvocation. The
 * per-instance ThreadLocal is only a ThreadContextElement view of that immutable
 * coroutine context, restored after every continuation. It is never a mutable
 * latest-request field. This also covers original synchronous cache-quality media
 * acceptance inside the coroutine and the original parallel detail/playurl tasks.
 *
 * Source replacement outside a network invocation (e.g. a native CDN failure) uses
 * the REQUIRED already accepted source view. Root must verify the exact source
 * version, BVID/CID, epoch and authorization receipt there; a completed network
 * invocation must not be retained to provide that view.
 */
internal class DesktopOriginalVideoPlaybackInvocationPorts(
    private val entryScope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val capture: suspend () -> DesktopOriginalVideoPlaybackInvocation,
    private val status: DesktopOriginalVideoPlaybackStatus,
    private val acceptedMedia: () -> DesktopOriginalVideoMediaPort,
    private val acceptedFailureMedia: ((DesktopOriginalNativeRecoveryTicket) -> DesktopOriginalVideoMediaPort)? = null,
) {
    private val closed = AtomicBoolean(false)
    private val currentThreadInvocation = ThreadLocal<DesktopOriginalVideoPlaybackInvocation?>()
    private val invocationKey = object : CoroutineContext.Key<InvocationElement> {}
    private val currentThreadFailure = ThreadLocal<FailureElement?>()
    private val failureKey = object : CoroutineContext.Key<FailureElement> {}

    private inner class FailureElement(val ticket: DesktopOriginalNativeRecoveryTicket) :
        ThreadContextElement<FailureElement?> {
        private val caller = java.util.concurrent.atomic.AtomicReference<Job?>()
        private val capturedMedia = java.util.concurrent.atomic.AtomicReference<DesktopOriginalVideoMediaPort?>()
        fun media(): DesktopOriginalVideoMediaPort {
            val job = checkNotNull(caller.get())
            if (!job.isActive) throw CancellationException("Native recovery caller retired")
            capturedMedia.get()?.let { return it }
            // Preparation remains outside monitors. All original AAC/CDN stages
            // share this operation's exact successor chain, not a latest getter.
            val prepared = checkNotNull(acceptedFailureMedia) { "Actual native failure media port is required" }(ticket.forCaller(job))
            return if (capturedMedia.compareAndSet(null, prepared)) prepared else checkNotNull(capturedMedia.get())
        }
        override val key: CoroutineContext.Key<*> get() = failureKey
        override fun updateThreadContext(context: CoroutineContext): FailureElement? {
            // The first continuation is the actual entry launch Job; nested
            // withContext/async completion must not replace it with a temporary Job.
            caller.compareAndSet(null, checkNotNull(context[Job]))
            return currentThreadFailure.get().also { currentThreadFailure.set(this) }
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: FailureElement?) {
            if (oldState == null) currentThreadFailure.remove() else currentThreadFailure.set(oldState)
        }
    }

    private inner class InvocationElement(val invocation: DesktopOriginalVideoPlaybackInvocation) :
        ThreadContextElement<DesktopOriginalVideoPlaybackInvocation?> {
        override val key: CoroutineContext.Key<*> get() = invocationKey
        override fun updateThreadContext(context: CoroutineContext): DesktopOriginalVideoPlaybackInvocation? =
            currentThreadInvocation.get().also { currentThreadInvocation.set(invocation) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: DesktopOriginalVideoPlaybackInvocation?) {
            if (oldState == null) currentThreadInvocation.remove() else currentThreadInvocation.set(oldState)
        }
    }

    fun assertCurrent() {
        if (closed.get() || !isCurrent() || entryScope.coroutineContext[Job]?.isActive == false)
            throw CancellationException("Original video entry retired")
    }

    private fun activeThreadInvocation(): DesktopOriginalVideoPlaybackInvocation? {
        assertCurrent()
        return currentThreadInvocation.get()?.also(DesktopOriginalVideoPlaybackInvocation::assertCurrent)
    }

    /** Read-only view of THIS continuation's captured repository. Extended
     * original metadata/action ports use it; there is no mutable latest binding. */
    internal fun requireRequestRepository(): DesktopOriginalVideoLoadRepository =
        activeThreadInvocation()?.repository
            ?: error("Original playback network invocation is required")

    private suspend fun activeInvocation(): DesktopOriginalVideoPlaybackInvocation {
        currentCoroutineContext().ensureActive(); assertCurrent()
        return (currentCoroutineContext()[invocationKey]?.invocation
            ?: error("Original playback network invocation is required"))
            .also(DesktopOriginalVideoPlaybackInvocation::assertCurrent)
    }

    suspend fun <T> withInvocation(block: suspend CoroutineScope.() -> T): T {
        currentCoroutineContext().ensureActive(); assertCurrent()
        // Nested original coroutine helpers retain the same captured receipt.
        val inherited = currentCoroutineContext()[invocationKey]
        if (inherited != null) {
            inherited.invocation.assertCurrent()
            return coroutineScope(block)
        }
        val invocation = capture()
        currentCoroutineContext().ensureActive(); assertCurrent(); invocation.assertCurrent()
        return withContext(InvocationElement(invocation)) {
            invocation.assertCurrent(); block()
        }
    }

    fun launch(
        context: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        desktopFailure: DesktopOriginalNativeRecoveryTicket? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        assertCurrent()
        // Preserve original dispatchers/start modes. Capture happens in the actual
        // launched Job, not before launch or in a completed factory coroutine.
        val ownedContext = desktopFailure?.let { context + FailureElement(it) } ?: context
        return entryScope.launch(ownedContext, start) { withInvocation(block) }
    }

    val repository: DesktopOriginalVideoLoadRepository = object : DesktopOriginalVideoLoadRepository {
        override suspend fun getVideoInfoOnly(bvid: String, aid: Long, requestedCid: Long): Result<ViewInfo> =
            activeInvocation().repository.getVideoInfoOnly(bvid, aid, requestedCid)
        override suspend fun getInitialPlayUrlData(bvid: String, cid: Long, targetQuality: Int, audioLang: String?): PlayUrlData? =
            activeInvocation().repository.getInitialPlayUrlData(bvid, cid, targetQuality, audioLang)
        override suspend fun getVideoDetails(bvid: String, aid: Long, requestedCid: Long, targetQuality: Int?, audioLang: String?): Result<Pair<ViewInfo, PlayUrlData>> =
            activeInvocation().repository.getVideoDetails(bvid, aid, requestedCid, targetQuality, audioLang)
        override suspend fun getRelatedVideos(bvid: String): List<RelatedVideo> =
            activeInvocation().repository.getRelatedVideos(bvid)
        override suspend fun getPlayUrlData(bvid: String, cid: Long, qn: Int, audioLang: String?): PlayUrlData? =
            activeInvocation().repository.getPlayUrlData(bvid, cid, qn, audioLang)
        override suspend fun getPlaybackNavInfo(): Result<NavData> = activeInvocation().repository.getPlaybackNavInfo()
        override fun isPlaybackLoggedIn(): Boolean = activeThreadInvocation()?.repository?.isPlaybackLoggedIn() ?: status.isPlaybackLoggedIn()
        override fun isPlaybackVip(): Boolean = activeThreadInvocation()?.repository?.isPlaybackVip() ?: status.isPlaybackVip()
        override fun isUsingDedicatedPlaybackAccount(): Boolean = activeThreadInvocation()?.repository?.isUsingDedicatedPlaybackAccount() ?: status.isUsingDedicatedPlaybackAccount()
        override fun isAppApiCoolingDown(): Boolean = activeThreadInvocation()?.repository?.isAppApiCoolingDown() ?: status.isAppApiCoolingDown()
    }

    // Only a synchronous call span. Fixed accepted media must not be rebuilt
    // between withPlaybackIntent/prepare/accept, especially outside an invocation.
    private val lexicalMedia = ThreadLocal<DesktopOriginalVideoMediaPort?>()
    private fun activeMedia(): DesktopOriginalVideoMediaPort {
        assertCurrent()
        val request = activeThreadInvocation()
        return lexicalMedia.get() ?: currentThreadFailure.get()?.media() ?: request?.media ?: acceptedMedia().also { assertCurrent() }
    }

    fun admitRecoveryAction(action: () -> Unit): Boolean {
        val media = activeMedia() as? DesktopOriginalNativeRecoveryMediaPort
            ?: error("Actual native recovery completion admission is required")
        return media.admitRecoveryAction(action)
    }

    val media: DesktopOriginalVideoMediaPort = object : DesktopOriginalVideoMediaPort {
        override fun withPlaybackIntent(startPositionMs:Long,playWhenReady:Boolean,action:()->Unit) {
            val captured = activeMedia()
            val previous = lexicalMedia.get()
            lexicalMedia.set(captured)
            try { captured.withPlaybackIntent(startPositionMs,playWhenReady,action) }
            finally { if (previous == null) lexicalMedia.remove() else lexicalMedia.set(previous) }
        }
        override fun prepareLegacyDash(videoUrl: String, audioUrl: String?, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource =
            activeMedia().prepareLegacyDash(videoUrl, audioUrl, cdnCacheKeysByUrl)
        override fun prepareAdaptiveDash(source: AdaptiveDashPlaybackSource, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource? =
            activeMedia().prepareAdaptiveDash(source, cdnCacheKeysByUrl)
        override fun prepareProgressive(url: String): PlaybackSource = activeMedia().prepareProgressive(url)
        override fun accept(source: PlaybackSource) = activeMedia().accept(source)
    }

    /** Admission only. Root cancels and joins the actual entry scope outside locks.
     * Closing this façade neither stops the native source nor closes global assets. */
    fun close() { closed.set(true) }
}
