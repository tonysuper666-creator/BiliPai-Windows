package com.bilipai.desktop.ui

import com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.usecase.VideoPlaybackUseCase
import com.android.purebilibili.data.model.response.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import com.bilipai.desktop.data.DesktopPlaybackAuthorizationReceipt
import com.bilipai.desktop.player.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.swing.Swing
import okhttp3.Call
import kotlin.test.*

/** The installed NativeOwner and actual MPV action/event implementations, using
 * the existing memory-only ABI actor. No DLL/thread/HWND or HTTP is started. */
class DesktopOriginalNativeRecoveryAdmissionTest {
    private val noNetwork = object : DesktopOriginalVideoLoadRepository, DesktopOriginalVideoPlaybackStatus {
        override suspend fun getVideoInfoOnly(bvid: String, aid: Long, requestedCid: Long): Result<ViewInfo> = error("No HTTP")
        override suspend fun getInitialPlayUrlData(bvid: String, cid: Long, targetQuality: Int, audioLang: String?): PlayUrlData? = error("No HTTP")
        override suspend fun getVideoDetails(bvid: String, aid: Long, requestedCid: Long, targetQuality: Int?, audioLang: String?): Result<Pair<ViewInfo, PlayUrlData>> = error("No HTTP")
        override suspend fun getRelatedVideos(bvid: String): List<RelatedVideo> = error("No HTTP")
        override suspend fun getPlayUrlData(bvid: String, cid: Long, qn: Int, audioLang: String?): PlayUrlData? = error("No HTTP")
        override suspend fun getPlaybackNavInfo(): Result<NavData> = error("No account")
        override fun isPlaybackLoggedIn() = false
        override fun isPlaybackVip() = false
        override fun isUsingDedicatedPlaybackAccount() = false
        override fun isAppApiCoolingDown() = false
    }
    private class Publication : DesktopPlaybackPublication {
        var epoch = 7L
        var held = false
        override val requiresAccountReceipt = true
        override fun isCurrent(source: PlaybackSource) = source.authorizationReceipt?.accountEpoch == epoch
        override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
            if (!isCurrent(source) || !stillOwned()) throw CancellationException("synthetic receipt retired")
            val previous = held; held = true
            return try { block() } finally { held = previous }
        }
        override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job?) =
            error("Recovery admission test must not create HTTP")
    }
    private class Fixture : AutoCloseable {
        val player = MpvPlayer()
        val publication = Publication()
        val caller = Job()
        var entry = true
        var beforeEntry: (() -> Unit)? = null
        val owner = DesktopOriginalVideoNativeOwner(player, publication, { publication.epoch }, { true }, { entry },
            { block -> beforeEntry?.also { beforeEntry = null }?.invoke(); if (!entry) false else { block(); true } },
            {}, { _, _ -> error("No byte transport in this fixture") })
        val accepted: DesktopOriginalVideoAcceptedPublication
        val actor: PremiumRecoveryActor
        init {
            // Attach the existing ABI actor to a disposable baseline first, then
            // let actual owner.publish enqueue the tested initial Load itself.
            player.loadVersioned(PlaybackSource("file:///synthetic-bootstrap.avi"))
            actor = PremiumRecoveryActor(player)
            val source = PlaybackSource("https://fixture.invalid/video", "https://fixture.invalid/hires", startPositionSeconds = 27.75,
                startPaused = true, streamHeaders = mapOf("X-Fixture" to "preserved"),
                authorizationReceipt = DesktopPlaybackAuthorizationReceipt(7, 1))
            accepted = owner.publish(PlaybackRequest.create("BVfixture", 9L, 22L), source, player.currentSourceVersion, caller) { true }
            actor.drain(); actor.loaded()
            check(owner.isCurrent(accepted))
        }
        fun prepare(lease: DesktopOriginalVideoAcceptedPublication): DesktopOriginalVideoMediaPort {
            assertFalse(publication.held) // Preparation is outside Store/entry/native admission.
            assertTrue(owner.isCurrent(lease))
            return object : DesktopOriginalVideoMediaPort {
                override fun withPlaybackIntent(startPositionMs: Long, playWhenReady: Boolean, action: () -> Unit) = action()
                override fun prepareLegacyDash(videoUrl: String, audioUrl: String?, cdnCacheKeysByUrl: Map<String, String>) =
                    accepted.nativeSource.source.copy(videoUrl = videoUrl, audioUrl = audioUrl)
                override fun prepareAdaptiveDash(source: AdaptiveDashPlaybackSource, cdnCacheKeysByUrl: Map<String, String>) =
                    error("No adaptive preparation in this fixture")
                override fun prepareProgressive(url: String) = accepted.nativeSource.source.copy(videoUrl = url)
                override fun accept(source: PlaybackSource) = error("The actual NativeOwner must publish")
            }
        }
        override fun close() { actor.close(); owner.close(); caller.cancel(); player.close() }
    }

    @Test fun sameActualAttemptPublishesOneAacReplacementPreservingSourceControlsAndReceipt() {
        Fixture().use { f ->
            val failure = f.actor.fail(-14)
            val token = DesktopOriginalNativeRecoveryTicket(f.accepted, failure.attemptId, f.caller)
            val media = f.owner.acceptedMedia(f::prepare, token) { true }
            val source = media.prepareLegacyDash(f.accepted.nativeSource.source.videoUrl, "https://fixture.invalid/aac", emptyMap())
            val before = f.player.state.value
            media.withPlaybackIntent(27_750L, false) { media.accept(source) }
            val next = assertNotNull(f.owner.current())
            assertNotSame(f.accepted, next); assertEquals(f.accepted.sourceVersion, next.sourceVersion)
            assertEquals("https://fixture.invalid/aac", next.nativeSource.source.audioUrl)
            assertEquals(f.accepted.nativeSource.source.authorizationReceipt, next.nativeSource.source.authorizationReceipt)
            assertEquals(f.accepted.nativeSource.source.streamHeaders, next.nativeSource.source.streamHeaders)
            assertEquals(before.volume, f.player.state.value.volume); assertEquals(before.muted, f.player.state.value.muted)
            assertEquals(before.speed, f.player.state.value.speed); assertTrue(f.player.state.value.paused)
            f.actor.drain()
            assertEquals("https://fixture.invalid/aac", (f.actor.native.loads.last()[4] as Map<*, *>)["audio-files"])
            // This operation may publish its own CDN successor; an unrelated
            // same-version recovery must still retire that continuation.
            val cdn = media.prepareLegacyDash("https://fixture.invalid/video-cdn", "https://fixture.invalid/aac", emptyMap())
            media.accept(cdn)
            assertEquals("https://fixture.invalid/video-cdn", f.owner.current()?.nativeSource?.source?.videoUrl)
            f.player.recoverSource(next.sourceVersion, PlaybackSource("https://fixture.invalid/unrelated"))
            assertFailsWith<CancellationException> { media.accept(cdn) }
        }
    }

    @Test fun newActualFailureAttemptAtFinalEntryAdmissionRejectsOldPreparedMedia() {
        Fixture().use { f ->
            val failure = f.actor.fail(-14)
            val media = f.owner.acceptedMedia(f::prepare, DesktopOriginalNativeRecoveryTicket(f.accepted, failure.attemptId, f.caller)) { true }
            val prepared = media.prepareLegacyDash(f.accepted.nativeSource.source.videoUrl, "https://fixture.invalid/aac", emptyMap())
            val loads = f.actor.native.loads.size
            f.beforeEntry = {
                check(f.player.recoverSource(f.accepted.sourceVersion))
                f.actor.drain(); f.actor.loaded()
                val newer = f.actor.fail(-14)
                assertTrue(newer.attemptId > failure.attemptId)
            }
            assertFailsWith<CancellationException> { media.accept(prepared) }
            assertSame(f.accepted, f.owner.current()); f.actor.drain()
            assertEquals(loads + 1, f.actor.native.loads.size) // Only the deliberate newer attempt, never old AAC.
        }
    }

    @Test fun cancelledCallerAndAccountRetirementCannotPublishPreparedNativeReplacement() {
        for (cancelCaller in listOf(true, false)) Fixture().use { f ->
            val failure = f.actor.fail(-14)
            val media = f.owner.acceptedMedia(f::prepare, DesktopOriginalNativeRecoveryTicket(f.accepted, failure.attemptId, f.caller)) { true }
            val prepared = media.prepareLegacyDash(f.accepted.nativeSource.source.videoUrl, "https://fixture.invalid/aac", emptyMap())
            val loads = f.actor.native.loads.size
            if (cancelCaller) f.caller.cancel() else f.publication.epoch++
            assertFailsWith<CancellationException> { media.accept(prepared) }
            f.actor.drain(); assertEquals(loads, f.actor.native.loads.size)
        }
    }

    @Test fun generatedObserverCurrentPredicateConsumesRealNativeFailureDespiteFreshSnapshotWrappers() = runBlocking<Unit> {
        Fixture().use { f ->
            val failure = f.actor.fail(-14)
            assertNotSame(f.accepted.nativeSource, f.player.currentSourceSnapshot())
            assertNotSame(f.player.currentSourceSnapshot(), f.player.currentSourceSnapshot())
            assertTrue(desktopOriginalNativeFailureCurrent(f.player, f.accepted, failure))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
            val recovered = CompletableDeferred<Unit>()
            val adapter = DesktopOriginalPlaybackRecovery(scope, { it(); true }, {})
            try {
                withContext(Dispatchers.Swing) {
                    assertTrue(adapter.fail(DesktopOriginalPlaybackRecoveryTicket(failure,
                        desktopOriginalNativeFailure(failure, true), false, 27_750, false,
                        { f.owner.isCurrent(f.accepted) && desktopOriginalNativeFailureCurrent(f.player, f.accepted, failure) },
                        { action, _, _ ->
                            assertEquals(com.android.purebilibili.feature.video.state.PlayerErrorRecoveryAction.FALLBACK_PREMIUM_AUDIO, action)
                            recovered.complete(Unit); true
                        })))
                }
                withTimeout(5_000) { recovered.await() }
                f.player.recoverSource(f.accepted.sourceVersion, PlaybackSource("https://fixture.invalid/replaced"))
                assertFalse(desktopOriginalNativeFailureCurrent(f.player, f.accepted, failure))
            } finally { adapter.close(); scope.cancel() }
        }
    }

    @Test fun callerCancellationWhileWaitingForActualNativeGateCannotRecover() {
        Fixture().use { f ->
            val failure = f.actor.fail(-14)
            val media = f.owner.acceptedMedia(f::prepare, DesktopOriginalNativeRecoveryTicket(f.accepted, failure.attemptId, f.caller)) { true }
            val prepared = media.prepareLegacyDash(f.accepted.nativeSource.source.videoUrl, "https://fixture.invalid/aac", emptyMap())
            val nativeHeld = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val finished = java.util.concurrent.CountDownLatch(1)
            var observed: Throwable? = null
            val holder = Thread {
                f.player.admitSourceSnapshot(f.accepted.nativeSource) {
                    nativeHeld.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            f.beforeEntry = { holder.start(); check(nativeHeld.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val loads = f.actor.native.loads.size
            val accept = Thread { try { media.accept(prepared) } catch (failure: Throwable) { observed = failure } finally { finished.countDown() } }
            try {
                accept.start(); check(nativeHeld.await(5, java.util.concurrent.TimeUnit.SECONDS)); f.caller.cancel(); release.countDown()
                check(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)); holder.join(5_000); accept.join(5_000)
                assertIs<CancellationException>(observed); f.actor.drain(); assertEquals(loads, f.actor.native.loads.size)
            } finally { release.countDown(); holder.join(5_000); accept.join(5_000) }
        }
    }

    @Test fun originalUseCaseAacThenCdnSharesOneInvocationAcrossSuspensionAndRestoresOrdinaryMedia() = runBlocking<Unit> {
        Fixture().use { f ->
            val root = java.nio.file.Files.createTempDirectory("bilipai-recovery-invocation-")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
            val captures = mutableListOf<DesktopOriginalNativeRecoveryTicket>()
            val ports = DesktopOriginalVideoPlaybackInvocationPorts(scope, { f.entry },
                { DesktopOriginalVideoPlaybackInvocation(noNetwork, f.owner.acceptedMedia(f::prepare)) {} }, noNetwork,
                { f.owner.acceptedMedia(f::prepare) },
                { ticket -> captures += ticket; f.owner.acceptedMedia(f::prepare, ticket) { f.entry } })
            val versions = MutableStateFlow<Long?>(f.accepted.sourceVersion)
            val section = DesktopOriginalMpvSectionControl(f.player, { f.owner.current()?.sourceVersion }, { f.entry },
                { action -> f.owner.current()?.let { f.owner.admitPlaybackDispatch(it, action) } ?: false },
                { false }, {}, {}, { _, _, _, _ -> }, scope, versions, { it(); true })
            try {
                val context = DesktopPluginContext(DesktopPluginStore(root))
                val actions = object : DesktopOriginalVideoInitialActions {
                    override suspend fun checkFollowStatus(mid: Long): Boolean = error("No account")
                    override suspend fun checkFavoriteStatus(aid: Long): Boolean = error("No account")
                    override suspend fun checkLikeStatus(aid: Long): Boolean = error("No account")
                    override suspend fun checkCoinStatus(aid: Long): Int = error("No account")
                }
                val progress = object : DesktopOriginalVideoProgressPort {
                    override fun getCachedPosition(bvid: String, cid: Long) = 0L
                    override fun savePosition(bvid: String, cid: Long, positionMs: Long) = Unit
                }
                val caps = object : DesktopOriginalVideoPlaybackCapabilities {
                    override fun isHevcSupported() = false
                    override fun isAv1Supported() = false
                    override fun isHdrSupported() = false
                    override fun isDolbyVisionSupported() = false
                    override fun isDolbyAtmosAudioSupported() = false
                    override fun isDolbySoftwareAudioDecoderRequired() = false
                }
                val useCase = VideoPlaybackUseCase(DesktopOriginalVideoPlaybackUseCaseEnvironment(context, ports.repository,
                    actions, progress, caps, ports.media, {}, { emptyMap() }, {}, { false }, { _, _, _, _ -> }, { f.entry }))
                useCase.initWithContext(context); useCase.attachPlayer(section)
                val failure = f.actor.fail(-14)
                val video = DashVideo(id = 80, baseUrl = "https://fixture.invalid/video", codecs = "avc1.640028")
                val audio = DashAudio(id = 30280, baseUrl = "https://fixture.invalid/aac", codecs = "mp4a")
                val finished = CompletableDeferred<Unit>()
                val job = ports.launch(desktopFailure = DesktopOriginalNativeRecoveryTicket(f.accepted, failure.attemptId)) {
                    val selection = assertNotNull(useCase.changeQualityFromCache(80, listOf(video), listOf(audio),
                        Dash(video = listOf(video), audio = listOf(audio)), currentPos = 27_750, durationMs = 120_000,
                        audioQualityPreference = -1, playWhenReady = false))
                    assertEquals("https://fixture.invalid/aac", selection.audioUrl)
                    val aac = assertNotNull(f.owner.current()); assertNotSame(f.accepted, aac)
                    yield() // Same original VM sequence may suspend while its CDN plugin resolves.
                    useCase.playDashVideo("https://fixture.invalid/video-cdn", selection.audioUrl, seekTo = 27_750, playWhenReady = false)
                    val cdn = assertNotNull(f.owner.current()); assertNotSame(aac, cdn)
                    assertEquals("https://fixture.invalid/video-cdn", cdn.nativeSource.source.videoUrl)
                    assertTrue(ports.admitRecoveryAction { assertSame(cdn, f.owner.current()) })
                    finished.complete(Unit)
                }
                withTimeout(5_000) { finished.await(); job.join() }
                assertEquals(1, captures.size); assertSame(job, captures.single().caller); assertFalse(job.isActive)
                f.actor.drain()
                assertEquals("https://fixture.invalid/video-cdn", f.actor.native.loads.last()[1])
                assertTrue(f.player.state.value.paused)
                // Outside the launched context the old ticket must be absent.
                assertEquals("file:///synthetic-normal.avi", ports.media.prepareProgressive("file:///synthetic-normal.avi").videoUrl)
                assertEquals(1, captures.size) // Failure context was restored; no completed failure caller is consulted.
            } finally {
                ports.close(); scope.cancel()
                java.nio.file.Files.walk(root).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) } }
            }
        }
    }
}
