package com.bilipai.desktop.ui

import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Call
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual source permission, MPV control, invocation media span, page proof and
 * NativeOwner, using the existing memory ABI actor. No DLL/HWND/HTTP or original
 * VM branch algorithm is simulated. Queue submission is not a playback result. */
class DesktopOriginalInteractiveBranchAdmissionTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private class PendingDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() {
            repeat(256) { (pending.pollFirst() ?: return).run() }
            error("Unexpected unbounded fixture dispatch")
        }
    }
    private class Publication : DesktopPlaybackPublication {
        var held = false
        override val requiresAccountReceipt = true
        override fun isCurrent(source: PlaybackSource) = source.authorizationReceipt?.accountEpoch == 7L
        override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
            if (!isCurrent(source) || !stillOwned()) throw CancellationException("Fixture publication retired")
            val previous = held; held = true
            return try { block() } finally { held = previous }
        }
        override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job?) =
            error("No fixture HTTP")
    }
    private class Fixture : AutoCloseable {
        val player = MpvPlayer()
        val publication = Publication()
        val dispatcher = PendingDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val initialJob = Job()
        var entry = true
        var metadataCurrent = true
        var beforeSourceAdmission: (() -> Unit)? = null
        var prepared = 0
        var defaultCommands = 0
        val request = PlaybackRequest.create("BVfixture", 17L, 70L)
        var session = PlaybackSessionState(currentBvid = request.bvid, currentCid = request.cid,
            currentLoadRequestToken = 4L, currentRequest = request)
        val owner = DesktopOriginalVideoNativeOwner(player, publication, { 7L }, { false }, { entry },
            { action -> if (!entry) false else { action(); true } }, {},
            { _, _ -> error("No fixture byte transport") })
        val actor: PremiumRecoveryActor
        val origin: DesktopOriginalVideoAcceptedPublication
        val control: DesktopOriginalMpvSectionControl
        init {
            player.loadVersioned(PlaybackSource("file:///C:/fixture-bootstrap.avi"))
            actor = PremiumRecoveryActor(player)
            origin = owner.publish(request, PlaybackSource("file:///C:/fixture-origin.avi", startPaused = true,
                authorizationReceipt = DesktopPlaybackAuthorizationReceipt(7L, 1L)),
                player.currentSourceVersion, initialJob) { true }
            actor.drain(); actor.loaded()
            control = DesktopOriginalMpvSectionControl(player, { owner.current()?.sourceVersion }, { entry },
                { action -> defaultCommands++; owner.current()?.let { owner.admitPlaybackDispatch(it, action) } ?: false },
                { false }, { assertTrue(publication.held); prepared++ }, { error("No ended-source replay") },
                { _, _, _, _ -> }, scope, MutableStateFlow<Long?>(null), { it(); true })
        }
        fun sourcePermission() = DesktopOriginalInteractiveChoiceSource(origin, session, { session },
            { metadataCurrent && owner.isCurrent(origin) }, { action ->
                beforeSourceAdmission?.also { beforeSourceAdmission = null }?.invoke()
                owner.admitPlaybackDispatch(origin, action)
            })
        fun source(url: String) = origin.nativeSource.source.copy(videoUrl = url, nativePublication = null)
        fun preparer(lease: DesktopOriginalVideoAcceptedPublication): DesktopOriginalVideoMediaPort {
            assertFalse(publication.held)
            return object : DesktopOriginalVideoMediaPort {
                override fun withPlaybackIntent(startPositionMs: Long, playWhenReady: Boolean, action: () -> Unit) = action()
                override fun prepareLegacyDash(videoUrl: String, audioUrl: String?, cdnCacheKeysByUrl: Map<String, String>) =
                    lease.nativeSource.source.copy(videoUrl = videoUrl, audioUrl = audioUrl, nativePublication = null)
                override fun prepareAdaptiveDash(source: AdaptiveDashPlaybackSource, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource? =
                    error("No adaptive fixture preparation")
                override fun prepareProgressive(url: String) = lease.nativeSource.source.copy(videoUrl = url, nativePublication = null)
                override fun accept(source: PlaybackSource) = error("Actual NativeOwner performs recovery accept")
            }
        }
        override fun close() {
            scope.cancel(); dispatcher.drain(); actor.close(); owner.close(); initialJob.cancel(); player.close()
        }
    }

    @Test fun retiringMetadataAtFinalOriginAdmissionCannotWriteThroughLiveDynamicControl() {
        Fixture().use { f ->
            val permission = f.sourcePermission()
            val before = f.player.state.value
            val properties = f.actor.native.properties.toList()
            f.beforeSourceAdmission = { f.metadataCurrent = false }
            assertTrue(f.control.isOwned())
            f.control.withSourceCommandAdmission(permission::commit) {
                f.control.volume = 0.75f
                f.control.playWhenReady = true
                f.control.prepare()
            }
            f.actor.drain()
            assertSame(f.origin, f.owner.current()) // The native source still exists; only the captured permit retired.
            assertEquals(before, f.player.state.value)
            assertEquals(properties, f.actor.native.properties)
            assertEquals(0, f.prepared)
            assertEquals(0, f.defaultCommands)
        }
    }

    @Test fun actualInteractivePageChildAcceptsAckAndCommandsAfterOldPermitRetires() {
        Fixture().use { f ->
            val permission = f.sourcePermission()
            val caller = Job()
            try {
                val intent = DesktopOriginalVideoPageTransitionIntent(f.request.bvid, 71L, -1, 80,
                    null, true, f.request.cid, f.session, 9L, { true }, { 9L }, { false }, interactiveBranch = true)
                val proof = intent.capture(f.session, caller)
                f.session = f.session.copy(currentCid = intent.cid)
                val child = f.owner.publishWithPageSubject(proof.resolvedCommittedSubject(f.session),
                    f.source("file:///C:/fixture-interactive-child.avi"), f.origin.sourceVersion,
                    caller, null, intent, null) { intent.sameOriginalRequest(f.session) && f.session.currentCid == intent.cid }
                f.metadataCurrent = false
                assertFalse(permission.isCurrent())
                assertTrue(caller.complete()) // A completed legitimate branch still owns its queued initial ACK.
                f.actor.drain(); f.actor.loaded()
                assertSame(child, f.owner.current())
                assertSame(intent, child.pageSubject)
                assertEquals("file:///C:/fixture-interactive-child.avi", f.actor.native.loads.last()[1])
                f.control.withSourceCommandAdmission({ action -> f.owner.admitPlaybackDispatch(child, action) }) {
                    f.control.volume = 0.75f
                    f.control.playWhenReady = true
                    f.control.setPlaybackSpeed(1.5f)
                    f.control.prepare()
                }
                f.actor.drain()
                assertEquals(75.0, f.player.state.value.volume)
                assertFalse(f.player.state.value.paused)
                assertEquals(1.5, f.player.state.value.speed)
                assertEquals(1, f.prepared)
                assertEquals(0, f.defaultCommands)
            } finally { caller.cancel() }
        }
    }

    @Test fun repeatedAcceptAndSameVersionReplacementCannotBorrowOldSourceAdmission() {
        Fixture().use { f ->
            val permission = f.sourcePermission()
            var accepts = 0
            val delegate = object : DesktopOriginalVideoMediaPort {
                override fun withPlaybackIntent(startPositionMs: Long, playWhenReady: Boolean, action: () -> Unit) = action()
                override fun prepareLegacyDash(videoUrl: String, audioUrl: String?, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource =
                    error("Only pure progressive preparation is supplied")
                override fun prepareAdaptiveDash(source: AdaptiveDashPlaybackSource, cdnCacheKeysByUrl: Map<String, String>): PlaybackSource? =
                    error("No adaptive preparation")
                override fun prepareProgressive(url: String): PlaybackSource {
                    assertFalse(f.publication.held)
                    return f.source(url)
                }
                override fun accept(source: PlaybackSource) {
                    accepts++
                    f.owner.publish(f.request.copy(cid = 71L), source, f.player.currentSourceVersion, f.initialJob) { true }
                }
            }
            val ports = DesktopOriginalVideoPlaybackInvocationPorts(f.scope, { f.entry },
                { error("No network invocation") }, unused(), { delegate })
            try {
                val loads = f.actor.native.loads.size
                ports.withMediaSubmissionAdmission(permission::commit) {
                    val source = ports.media.prepareProgressive("file:///C:/fixture-branch.avi")
                    ports.media.withPlaybackIntent(0L, true) { ports.media.accept(source) }
                    assertFailsWith<CancellationException> { ports.media.accept(source) }
                }
                f.actor.drain(); f.actor.loaded()
                assertEquals(1, accepts)
                assertEquals(loads + 1, f.actor.native.loads.size)
                // The retired borrowed wrapper must be absent after its lexical finally.
                ports.media.accept(ports.media.prepareProgressive("file:///C:/fixture-next.avi"))
                f.actor.drain(); f.actor.loaded()
                assertEquals(2, accepts)
                val child = assertNotNull(f.owner.current())
                val recovery = f.owner.acceptedMedia(f::preparer)
                recovery.accept(recovery.prepareProgressive("file:///C:/fixture-same-version-recovery.avi"))
                f.actor.drain(); f.actor.loaded()
                val replacement = assertNotNull(f.owner.current())
                assertNotSame(child, replacement)
                assertEquals(child.sourceVersion, replacement.sourceVersion)
                assertTrue(f.control.isOwned())
                val before = f.player.state.value
                val properties = f.actor.native.properties.toList()
                f.control.withSourceCommandAdmission({ action -> f.owner.admitPlaybackDispatch(child, action) }) {
                    f.control.volume = 0.75f
                    f.control.playWhenReady = true
                    f.control.prepare()
                }
                f.actor.drain()
                assertEquals(before, f.player.state.value)
                assertEquals(properties, f.actor.native.properties)
                assertEquals(0, f.prepared)
            } finally { ports.close() }
        }
    }

    @Test fun commandCancellationRestoresOuterAdmissionThenOrdinaryDefaultControl() {
        Fixture().use { f ->
            var outer = 0
            f.control.withSourceCommandAdmission({ action ->
                outer++; f.owner.admitPlaybackDispatch(f.origin, action)
            }) {
                assertFailsWith<CancellationException> {
                    f.control.withSourceCommandAdmission({ false }) {
                        throw CancellationException("Controlled synchronous cancellation")
                    }
                }
                f.control.volume = 0.5f
            }
            assertEquals(1, outer)
            assertEquals(0, f.defaultCommands)
            f.control.playWhenReady = true
            f.actor.drain()
            assertEquals(1, f.defaultCommands)
            assertEquals(50.0, f.player.state.value.volume)
            assertFalse(f.player.state.value.paused)
        }
    }

    @Test fun actualInvocationFinallyRunsOnceWhenFactoryCancelsBeforeVmBody() {
        val dispatcher = PendingDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var captures = 0
        var bodies = 0
        var cleanup = 0
        val ports = DesktopOriginalVideoPlaybackInvocationPorts(scope, { true }, {
            captures++; throw CancellationException("Controlled Factory retirement")
        }, unused(), { error("No accepted media") })
        try {
            val job = ports.launch(start = CoroutineStart.LAZY, desktopFinally = { cleanup++ }) { bodies++ }
            assertEquals(0, captures)
            assertTrue(job.start())
            dispatcher.drain()
            assertTrue(job.isCancelled)
            assertTrue(job.isCompleted)
            assertEquals(1, captures)
            assertEquals(0, bodies)
            assertEquals(1, cleanup)
            dispatcher.drain()
            assertEquals(1, cleanup)
        } finally { ports.close(); scope.cancel(); dispatcher.drain() }
    }
}
