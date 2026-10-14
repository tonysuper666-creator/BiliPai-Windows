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
        override fun cachedVideoCid(bvid: String, aid: Long): Long = 0L
        override suspend fun getCachedRelatedVideosResult(bvid: String): Result<List<RelatedVideo>>? = null
        override fun invalidateVideoLoadCaches(bvid: String, aid: Long, cid: Long): Unit = error("No main load refresh")
        override suspend fun getVideoInfoOnly(bvid: String, aid: Long, requestedCid: Long): Result<ViewInfo> = error("No HTTP")
        override suspend fun getInitialPlayUrlData(bvid: String, cid: Long, targetQuality: Int, audioLang: String?): PlayUrlData? = error("No HTTP")
        override suspend fun getVideoDetails(bvid: String, aid: Long, requestedCid: Long, targetQuality: Int?, audioLang: String?): Result<Pair<ViewInfo, PlayUrlData>> = error("No HTTP")
        override suspend fun getRelatedVideos(bvid: String): List<RelatedVideo> = error("No HTTP")
        override suspend fun getRelatedVideosResult(bvid: String): Result<List<RelatedVideo>> = error("No HTTP")
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
                { DesktopOriginalVideoPlaybackInvocation(noNetwork, f.owner.acceptedMedia(f::prepare), {},
                    mediaPreparation = { check(f.owner.isCurrent(f.accepted)) }) }, noNetwork,
                { f.owner.acceptedMedia(f::prepare) },
                captureMetadataBinding = { error("Readonly metadata retry is outside this fixture") },
                acceptedFailureMedia = { ticket -> captures += ticket; f.owner.acceptedMedia(f::prepare, ticket) { f.entry } })
            val versions = MutableStateFlow<Long?>(f.accepted.sourceVersion)
            val section = DesktopOriginalMpvSectionControl(f.player, { f.owner.current()?.sourceVersion }, { f.entry },
                { action -> f.owner.current()?.let { f.owner.admitPlaybackDispatch(it, action) } ?: false },
                { false }, {}, {}, { _, _, _, _ -> }, scope, versions, { it(); true })
            try {
                val context = DesktopPluginContext(DesktopPluginStore(root))
                val actions = object : DesktopOriginalVideoInitialActions {
        // This existing fixture has no cached reply or mutation revision. Query
        // values come from its controlled check implementations; no production defaults.
        override fun interactionSession(): Any = this
        override fun cachedLikeStatus(aid: Long): Boolean? = null
        override fun cachedFollowStatus(mid: Long): Boolean? = null
        override fun likeStatusVersion(aid: Long): Long = 0L
        override fun followStatusVersion(mid: Long): Long = 0L
        override suspend fun queryLikeStatus(aid: Long): Result<Boolean> = try {
            Result.success(checkLikeStatus(aid))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (failed: Exception) { Result.failure(failed) }
        override suspend fun queryFollowStatus(mid: Long): Result<Boolean> = try {
            Result.success(checkFollowStatus(mid))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (failed: Exception) { Result.failure(failed) }

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

    @Test fun pgcParentPauseIntentIsCheckedInsideTheActualNativeLock() {
        Fixture().use { f ->
            val held = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val finished = java.util.concurrent.CountDownLatch(1)
            val checks = java.util.concurrent.atomic.AtomicInteger()
            var observed: Throwable? = null
            val holder = Thread {
                f.player.admitSourceSnapshot(f.accepted.nativeSource) {
                    held.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    f.player.setPaused(false) // Actual user play intent, on the same still-mounted parent.
                }
            }
            f.beforeEntry = { holder.start(); check(held.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            val caller = Job()
            val loads = f.actor.native.loads.size
            val replacement = f.accepted.nativeSource.source.copy(nativePublication = null,
                videoUrl = "https://fixture.invalid/pgc-refreshed")
            val accept = Thread {
                try {
                    f.owner.publishBangumiQualityReplacement(f.accepted.request, replacement,
                        f.accepted.sourceVersion, caller, f.accepted, { true }, {
                            checks.incrementAndGet()
                            assertTrue(f.publication.held)
                            f.player.state.value.paused
                        })
                } catch (failure: Throwable) { observed = failure }
                finally { finished.countDown() }
            }
            try {
                accept.start()
                check(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, checks.get()) // No parent predicate is consumed before that lock.
                release.countDown()
                check(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertIs<CancellationException>(observed)
                assertEquals(1, checks.get())
                assertSame(f.accepted, f.owner.current())
                f.actor.drain()
                assertEquals(loads, f.actor.native.loads.size)
                assertFalse(f.player.state.value.paused)
            } finally {
                release.countDown(); holder.join(5_000); accept.join(5_000); caller.cancel()
            }
        }
    }

    @Test fun pgcAcceptedChildAckDoesNotBorrowTheConsumedParentIntent() {
        Fixture().use { f ->
            val caller = Job()
            var parentCurrent = true
            var checks = 0
            try {
                val source = f.accepted.nativeSource.source.copy(nativePublication = null,
                    videoUrl = "https://fixture.invalid/pgc-120")
                val child = f.owner.publishBangumiQualityReplacement(f.accepted.request, source,
                    f.accepted.sourceVersion, caller, f.accepted, { true }, {
                        checks++; assertTrue(f.publication.held); parentCurrent
                    })
                parentCurrent = false
                assertTrue(caller.complete())
                f.actor.drain(); f.actor.loaded()
                assertEquals(1, checks) // Neither transport handoff nor initial ACK rechecks old intent.
                assertSame(child, f.owner.current())
                assertEquals("https://fixture.invalid/pgc-120", f.actor.native.loads.last()[1])
                assertEquals(source, child.nativeSource.source.copy(nativePublication = null))
                assertEquals(f.accepted.nativeSource.source.audioUrl, child.nativeSource.source.audioUrl)
                assertEquals(f.accepted.nativeSource.source.authorizationReceipt, child.nativeSource.source.authorizationReceipt)
                assertEquals(27.75, child.nativeSource.source.startPositionSeconds)
                assertTrue(child.nativeSource.source.startPaused)
            } finally { caller.cancel() }
        }
    }

    @Test fun pgcEpisodeSourceAccountAndEntryRetirementCannotPublishAnOldRefresh() {
        for (retirement in listOf("episode", "source", "account", "entry")) Fixture().use { f ->
            val source = f.accepted.nativeSource.source.copy(nativePublication = null,
                videoUrl = "https://fixture.invalid/late-pgc-refresh")
            when (retirement) {
                "episode" -> f.owner.publish(f.accepted.request.copy(cid = 99L),
                    source.copy(videoUrl = "https://fixture.invalid/new-episode"),
                    f.player.currentSourceVersion, f.caller) { true }
                "source" -> assertTrue(f.player.recoverSource(f.accepted.sourceVersion,
                    source.copy(videoUrl = "https://fixture.invalid/same-version-other-source")))
                "account" -> f.publication.epoch++
                "entry" -> f.entry = false
            }
            f.actor.drain()
            val loads = f.actor.native.loads.size
            val caller = Job()
            try {
                assertFailsWith<CancellationException>(retirement) {
                    f.owner.publishBangumiQualityReplacement(f.accepted.request, source,
                        f.accepted.sourceVersion, caller, f.accepted, { true })
                }
                f.actor.drain()
                assertEquals(loads, f.actor.native.loads.size, retirement)
            } finally { caller.cancel() }
        }
    }

    private class PendingPgcDispatcher : CoroutineDispatcher() {
        private val pending = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() {
            repeat(256) { (pending.pollFirst() ?: return).run() }
            error("Unexpected unbounded PGC fixture dispatch")
        }
    }
    private inline fun <reified T> unusedPgc(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected PGC ${T::class.java.simpleName}.${method.name}") } as T

    /** Seed only the real VM's owned DTO flow. No load/retry/intent algorithm is
     * reproduced, and no fixture reports a fake accepted playback result. Actual
     * NativeOwner/ABI tests above cover publication; this exercises original VM
     * synchronous manual intent and its real LAZY Job lane before any HTTP. */
    private inner class PgcVmFixture(val f: Fixture) : AutoCloseable {
        val dispatcher = PendingPgcDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val root = java.nio.file.Files.createTempDirectory("bilipai-pgc-intent-")
        val context = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(DesktopPluginStore(root)),
            { f.entry }, { action -> if (!f.entry) false else { action(); true } })
        val control = DesktopOriginalMpvSectionControl(f.player, { f.owner.current()?.sourceVersion }, { f.entry },
            { action -> f.owner.current()?.let { f.owner.admitPlaybackDispatch(it, action) } ?: false },
            { false }, {}, {}, { _, _, _, _ -> }, scope, MutableStateFlow<Long?>(null), { it(); true })
        val state = com.android.purebilibili.feature.bangumi.BangumiPlayerState.Success(
            seasonDetail = BangumiDetail(seasonId = 17L),
            currentEpisode = BangumiEpisode(id = 18L, bvid = f.accepted.request.bvid,
                aid = f.accepted.request.aid, cid = f.accepted.request.cid),
            currentEpisodeIndex = 0, playUrl = f.accepted.nativeSource.source.videoUrl,
            audioUrl = f.accepted.nativeSource.source.audioUrl, quality = 80,
            acceptQuality = listOf(80, 120), acceptDescription = listOf("1080P", "4K"),
            requestedAudioQuality = -1)
        private val progress = object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long) = 0L
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) = Unit
        }
        private val api = unusedPgc<com.android.purebilibili.core.network.BilibiliApi>()
        private val interaction = com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase(
            com.android.purebilibili.data.repository.createTestVideoEngagementProtocol(
                api, { error("No CSRF read") }, { error("No MID read") },
                { error("No session read") }, { error("No token read") },
                { error("No engagement action") }, { error("No follow action") },
                com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol(
                    api, { error("No folder MID") }, { error("No folder CSRF") },
                    { error("No folder action") })), unusedPgc())
        val vm = com.android.purebilibili.feature.bangumi.BangumiPlayerViewModel(
            DesktopOriginalBangumiPlayerEnvironment(scope, control, context,
                unusedPgc(), unusedPgc(), unusedPgc(),
                interaction,
                api, progress, unusedPgc(), unusedPgc(), MutableStateFlow(false),
                { -1 }, { -1 }, { error("No preference write") },
                { false }, { false }, { false }, { false }, { null },
                { _, _, _ -> error("No CDN request") }, { _, _, _ -> error("No playlist mutation") },
                { error("No download") }, {},
                { block -> scope.launch(start = CoroutineStart.LAZY, block = block) },
                { action -> if (!f.entry) false else { action(); true } }, { f.entry },
                { f.owner.isCurrent(f.accepted) }, unusedPgc(), { false },
                publishReadOnlyDetail = { _, _, _, _, _, _ ->
                    error("No initial readonly detail in the native recovery intent fixture")
                },
                prepareMedia = {
                    error("No initial media preparation in the native recovery intent fixture")
                },
                captureLoadCaller = {
                    error("No initial load caller in the native recovery intent fixture")
                }))
        init {
            @Suppress("UNCHECKED_CAST")
            val owned = vm.javaClass.getDeclaredField("_uiState").apply { isAccessible = true }
                .get(vm) as MutableStateFlow<com.android.purebilibili.feature.bangumi.BangumiPlayerState>
            owned.value = state
        }
        override fun close() {
            f.entry = false; vm.close(); scope.cancel(); dispatcher.drain()
            java.nio.file.Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.deleteIfExists(it) }
            }
        }
    }

    @Test fun pgcPendingRetryCannotStartAfterTheActualVmManualQualityEntry() {
        Fixture().use { f -> PgcVmFixture(f).use { h ->
            val revision = h.vm.desktopPlaybackIntentRevision
            val jobs = mutableListOf<Job>()
            var failures = 0
            assertTrue(h.vm.retryNetworkPlayback(h.state, revision, 27_750L,
                { block -> h.scope.launch(start = CoroutineStart.LAZY, block = block).also(jobs::add) },
                { action -> f.owner.admitPlaybackDispatch(f.accepted, action) },
                { f.owner.isCurrent(f.accepted) },
                { _, _ -> error("Stale retry must not prepare/accept media") }, { failures++ }))
            assertEquals(1, jobs.size)
            assertTrue(jobs.single().isActive)
            h.vm.changeQuality(120) // Actual original synchronous UI entry, not a cloned predicate.
            assertTrue(h.vm.desktopPlaybackIntentRevision > revision)
            h.dispatcher.drain()
            assertTrue(jobs.single().isCompleted)
            assertEquals(0, failures) // Unexpected native/API invocation fails the unused real port.
            assertSame(h.state, h.vm.uiState.value)
            assertSame(f.accepted, f.owner.current())
        } }
    }

    @Test fun pgcManualAudioEntryRejectsAnOldTicketBeforeLazyCreationAndNativeLink() {
        Fixture().use { f -> PgcVmFixture(f).use { h ->
            val revision = h.vm.desktopPlaybackIntentRevision
            h.vm.changeAudioQuality(30251) // Actual preference intent; its queued HTTP job stays LAZY.
            var launches = 0
            var admissions = 0
            assertFalse(h.vm.retryNetworkPlayback(h.state, revision, 27_750L,
                { block -> launches++; h.scope.launch(start = CoroutineStart.LAZY, block = block) },
                { action -> admissions++; f.owner.admitPlaybackDispatch(f.accepted, action) },
                { f.owner.isCurrent(f.accepted) },
                { _, _ -> error("Stale audio ticket must not publish") }, { error("No retry") }))
            assertEquals(0, launches); assertEquals(0, admissions)
            assertSame(h.state, h.vm.uiState.value)
        } }
    }
}
