package com.bilipai.desktop.ui

import com.android.purebilibili.core.cooldown.PlaybackCooldownManager
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.BuvidApi
import com.android.purebilibili.data.model.VideoLoadError
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackViewModel
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.swing.Swing
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.lang.reflect.Proxy
import java.net.Proxy as NetworkProxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Complete original VM -> invocation -> captured repository -> original protocol.
 * A real temporary Store starts logged in without buvid3; every GET is intercepted.
 * Success is bounded to the next captured playurl protocol request and the real
 * client CookieJar, before OkHttp's BridgeInterceptor; no socket/header/native
 * playback is asserted. The original UseCase/VM retain their CancellationException. */
class DesktopOriginalVideoMetadataRelatedViewModelTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private data class InvocationCapture(val state: PlaybackSessionState,
        val receipt: DesktopPlaybackAuthorizationReceipt, val job: Job)
    private data class PlayCapture(val request: Request,
        val receipt: DesktopPlaybackAuthorizationReceipt, val cookies: List<Cookie>)

    private inner class Harness : AutoCloseable {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val folder = Files.createTempDirectory("original-auth-recapture-")
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        var entry = true
        val epoch: Long
        val requests = CopyOnWriteArrayList<Request>()
        val retryCaptures = CopyOnWriteArrayList<InvocationCapture>()
        val relatedEntered = CompletableDeferred<Unit>()
        val relatedCalls = AtomicInteger()
        val relatedFailures = AtomicInteger()
        @Volatile var relatedGate = CountDownLatch(0)
        val mediaCalls = AtomicInteger()
        val capabilityReads = AtomicInteger()
        val preparationCalls = AtomicInteger()
        @Volatile var preparationEntered = CompletableDeferred<Unit>()
        @Volatile var preparationGate = CompletableDeferred<Unit>().also { it.complete(Unit) }
        @Volatile var preparationFailure: Exception? = null
        fun holdNativePreparation() {
            preparationEntered = CompletableDeferred()
            preparationGate = CompletableDeferred()
            preparationFailure = null
        }
        private suspend fun prepareFixtureNative() {
            // Only this private fixed-capability fixture controls the OS port.
            // The production raw owner still performs its real timeout/receipt
            // admission and maps this port's ordinary failure into a typed cause.
            val entered = preparationEntered
            val gate = preparationGate
            preparationCalls.incrementAndGet(); entered.complete(Unit)
            gate.await()
            preparationFailure?.let { throw it }
        }
        val crashErrors = CopyOnWriteArrayList<String>()
        val captures = CopyOnWriteArrayList<InvocationCapture>()
        val playEntered = CompletableDeferred<PlayCapture>()
        val playRelease = CountDownLatch(1)
        val guestPlayCalls = AtomicInteger()
        @Volatile var warmingGuestPlay = false
        @Volatile var rejectSelectionNav = false
        val selectionApiCalls = AtomicInteger()
        val transport: OkHttpClient
        val subtitles: DesktopSubtitleAssets
        val privacy = DesktopSearchPreferences(folder.resolve("search"))
        lateinit var vm: VideoPlaybackViewModel
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"), AccountSummary(41, "Fixture", ""))
            epoch = repository.sessionEpoch
            transport = repository.httpClient.newBuilder().proxy(NetworkProxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    check(request.method == "GET" && request.url.isHttps && request.url.port == 443)
                    val body = when (request.url.host to request.url.encodedPath) {
                        "www.bilibili.com" to "/" -> "memory bootstrap"
                        "api.bilibili.com" to "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                        "api.bilibili.com" to "/x/web-interface/view" -> """{"code":0,"data":{"bvid":"${request.url.queryParameter("bvid")}","aid":7,"cid":22,"title":"Fixture","owner":{"mid":51,"name":"Fixture"},"pages":[{"cid":22,"page":1,"part":"Fixture"}]}}"""
                        "api.bilibili.com" to "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"vip":{"status":0},"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                        "api.bilibili.com" to "/x/player/wbi/playurl", "api.bilibili.com" to "/x/player/playurl" -> {
                            // Repository's preceding interceptor installs the exact
                            // request authorization until this interceptor returns.
                            // BridgeInterceptor has not yet composed a Cookie header.
                            val authorization = sessions.requestPlaybackAuthorization.get()
                            check(transport.cookieJar === sessions)
                            val cookies = transport.cookieJar.loadForRequest(request.url).toList()
                            if (authorization != null) {
                                check(sessions.requestGuestBuvid3.get() == null)
                                playEntered.complete(PlayCapture(request, authorization.receipt, cookies))
                            } else {
                                // Original protocol legitimately tries guest HTML5
                                // fallback after owned URL failure. Its real guest
                                // marker/jar must exclude account credentials.
                                checkNotNull(sessions.requestGuestBuvid3.get())
                                check(cookies.none { it.name == "SESSDATA" || it.name == "bili_jct" })
                                guestPlayCalls.incrementAndGet()
                            }
                            if (!warmingGuestPlay) check(playRelease.await(10, TimeUnit.SECONDS))
                            // A real parsable response reaches the original raw
                            // Owner beforeSelection boundary; failure comes from
                            // its separately owned metadata API HTTP412 below.
                            val quality = request.url.queryParameter("qn")?.toIntOrNull() ?: 80
                            """{"code":0,"data":{"quality":$quality,"accept_quality":[$quality],"dash":{"duration":20,"video":[{"id":$quality,"base_url":"https://fixture.invalid/video","codecs":"avc1.640028","mime_type":"video/mp4","bandwidth":1000000}],"audio":[{"id":30280,"base_url":"https://fixture.invalid/audio","codecs":"mp4a.40.2","mime_type":"audio/mp4","bandwidth":128000}]}}}"""
                        }
                        "api.bilibili.com" to "/x/web-interface/archive/related" -> {
                            relatedCalls.incrementAndGet(); relatedEntered.complete(Unit)
                            check(relatedGate.await(10, TimeUnit.SECONDS))
                            if (relatedFailures.getAndDecrement() > 0) """{"code":-500,"message":"related fixture failure"}"""
                            else """{"code":0,"data":[{"aid":18,"bvid":"BV17x411w7KC","cid":80,"title":"Related fixture","pic":"https://fixture.invalid/cover.jpg","duration":20,"owner":{"mid":52,"name":"UP"},"stat":{"view":10,"danmaku":2}}]}"""
                        }
                        else -> throw java.io.IOException("No external fixture transport: " + request.url.encodedPath)
                    }
                    val status = if (rejectSelectionNav && request.url.encodedPath == "/x/web-interface/nav") 412 else 200
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("memory")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            subtitles = DesktopSubtitleAssets(transport)
        }
        fun owns() = entry && scope.isActive && repository.sessionEpoch == epoch
        fun commit(action: () -> Unit): Boolean = if (!owns()) false else { action(); true }
        private val media = Proxy.newProxyInstance(DesktopOriginalVideoMediaPort::class.java.classLoader,
            arrayOf(DesktopOriginalVideoMediaPort::class.java)) { _, method, _ ->
            mediaCalls.incrementAndGet(); error("No real media exists in failing PlayURL fixture: ${method.name}")
        } as DesktopOriginalVideoMediaPort
        private val actions = unused<DesktopOriginalVideoOwnerActions>()
        private val status = DesktopOriginalVideoEntryReadbacks(repository, epoch, scope, ::owns, ::commit)
        val invocations = DesktopOriginalVideoPlaybackInvocationPorts(scope, ::owns, {
            val callerJob = currentCoroutineContext().job
            val state = vm.captureDesktopLoadState()
            val binding = factory.captureBinding(state)
            captures += InvocationCapture(state, binding.receipt, callerJob)
            val raw = createDesktopOriginalVideoOwnerRequestRepository(repository, binding, subtitles, privacy,
                { receipt, current -> repository.ownedHomeVisitorInitialized(receipt.accountEpoch, current) })
            raw.observeTransportMetadata(object : DesktopOriginalVideoTransportObserver {
                override fun info(value: com.android.purebilibili.data.model.response.ViewInfo) = Unit
                override fun playData(bvid: String, cid: Long, value: com.android.purebilibili.data.model.response.PlayUrlData) = Unit
            }, beforeSelection = {
                // Use the actual same-Binding primary API and Repository HTTP
                // mapping. No synthetic VideoLoadError or disabled recovery actor.
                selectionApiCalls.incrementAndGet(); rejectSelectionNav = true
                binding.primaryApi.getNavInfo()
                error("Actual metadata HTTP412 must reject before Native selection")
            })
            // This private headless fixture supplies fixed capabilities; it
            // does not initialize or assert readiness of a physical native core.
            raw.installMediaPreparation(::prepareFixtureNative)
            DesktopOriginalVideoPlaybackInvocation(raw, media, binding::assertCurrent,
                mediaPreparation = raw::awaitMediaPreparation)
        }, status, { media }, captureMetadataBinding = { admission ->
            val state = vm.captureDesktopLoadState()
            val caller = currentCoroutineContext().job
            val binding = factory.captureBinding(state, stillSourceOwned = admission::ownsSubject)
            binding.assertCurrent(); admission.assertCurrent()
            assertEquals(admission.receipt, binding.receipt)
            retryCaptures += InvocationCapture(state, binding.receipt, caller)
            binding
        })
        // No media has ever been accepted in this bounded protocol fixture.
        // The actual VM reads this port to distinguish ordinary/PGC startup;
        // absence must be explicit, while every unrelated plugin effect still fails.
        private val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
            arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, _ ->
            when (method.name) {
                "capturePlaybackDispatch" -> null
                else -> error("Unexpected pre-media plugin effect: ${method.name}")
            }
        } as DesktopOriginalVideoOwnerPlugins
        private val view = DesktopOriginalVideoOwnerRepositoryView(invocations) { 0L }
        private val progress = object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long) = 0L
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) = error("No accepted media")
        }
        private val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
            private fun available(value: Boolean): Boolean { capabilityReads.incrementAndGet(); return value }
            override fun isHevcSupported() = available(false)
            override fun isAv1Supported() = available(false)
            override fun isHdrSupported() = available(false)
            override fun isDolbyVisionSupported() = available(false)
            override fun isDolbyAtmosAudioSupported() = available(false)
            override fun isDolbySoftwareAudioDecoderRequired() = available(false)
        }
        private val mini = object : DesktopOriginalVideoOwnerMini {
            override var onNavigateNextCallback: (() -> Boolean)? = null
            override var onNavigatePreviousCallback: (() -> Boolean)? = null
            override var onHasNextNavigationCallback: (() -> Boolean)? = null
            override var onHasPreviousNavigationCallback: (() -> Boolean)? = null
            override val currentBvid: String? = null
            override val currentCid: Long? = null
            override val isActive = false
            override val player: DesktopOriginalMpvSectionControl? = null
            override fun syncCurrentVideoInfo(state: VideoPlaybackUiState.Success) = error("No accepted media")
            override fun updateCachedVideoTags(bvid: String, tags: List<com.android.purebilibili.data.model.response.VideoTag>) = error("No post-load work")
        }
        private val network = object : DesktopOriginalVideoOwnerNetwork {
            override fun cdnNetwork() = error("No media preparation")
            override fun isMobileData() = false
            override fun isWifi() = true
            override fun relatedNetworkObservation() = DesktopOriginalVideoRelatedNetworkObservation(
                DesktopHomeNetworkObservation(true, 3, 6L, false, "fixture-measured"), 10_000)
            override fun getDefaultQualityId(context: DesktopOriginalPlayerSettingsContext) = 64
        }
        private val cache = object : DesktopOriginalVideoOwnerCache {
            override fun get(bvid: String, cid: Long): com.android.purebilibili.data.model.response.PlayUrlData? = null
            override fun invalidate(bvid: String, cid: Long) = Unit // No fixture accepted cache entry.
        }
        private val settings = DesktopOriginalPlayerSettingsContext(context, ::owns, ::commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        private val api = unused<BilibiliApi>()
        private val interaction = VideoInteractionUseCase(DesktopOriginalVideoEngagementProtocol(api,
            { "synthetic-csrf" }, { 41L }, { "synthetic-session" }, { null }, {}, {},
            DesktopOriginalFavoriteFolderProtocol(api, { 41L }, { "synthetic-csrf" }, {})), unused<DesktopOriginalVideoInteractionAnalytics>())
        private val useCase = DesktopOriginalVideoPlaybackUseCaseEnvironment(context, invocations.repository, actions,
            progress, capabilities, media, { error("No native volume effect") }, { emptyMap() },
            { error("No successful media VIP refresh") }, { false }, { _, _, _, _ -> }, ::owns)
        fun create() {
            vm = VideoPlaybackViewModel(DesktopOriginalVideoPlaybackOwnerEnvironment(scope, settings, invocations,
                view, unused(), actions, useCase, interaction, unused(), mini, unused(), plugins, unused(), network,
                cache, { null }, unused(), DesktopOriginalVideoOwnerCrash { _, type, _ -> crashErrors += type }, unused(), unused(), MutableStateFlow(false), ::owns, ::commit,
                DesktopTodayWatchFeedbackWriteBinding(context, ::owns, ::commit),
                captureNativePublication = { null },
                admitNativePublication = { _, _ -> error("No accepted Native source in failing PlayURL fixture") },
                readRelatedPlaybackState = { error("No Native readback in failing PlayURL fixture") }))
            VideoPlaybackViewModel::class.java.getDeclaredField("appContext").apply { isAccessible = true }.set(vm, settings)
            PlaybackCooldownManager.clearAll()
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        private val factory by lazy {
            DesktopOriginalVideoOwnerRequestFactory(repository, DesktopLoginRepository(repository), epoch,
                scope, ::owns, ::commit, { PlayerPreferences() }, capabilities, { false }, { false }, { false },
                subtitles, privacy, { _, _ -> error("Warmed visitor must not retire this fixture authorization") },
                { _, _, _, _, _ -> error("Metadata-only retry must not prepare Native media") })
        }
        suspend fun start() {
            // Initialize the actual visitor policy before the real request capture;
            // the first request is not a test-synthesized authorization revision.
            factory
            repository.ensureOwnedHomeSession(epoch, ::owns,
                repository.ownedHomeService(BuvidApi::class.java, "https://api.bilibili.com/", epoch, ::owns))
            // Warm the actual guest service/visitor scalar before the first
            // playback Binding. Current Windows guest creation is nonmutating;
            // assert this explicitly rather than retag an existing receipt.
            val revision = repository.playbackAuthorizationRevision.value
            repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, ::owns,
                guest = true).getNavInfo()
            assertEquals(revision, repository.playbackAuthorizationRevision.value)
            // Keep the genuine guest marker/jar assertions in this terminal API
            // scenario. Warm its actual legacy endpoint without blocking startup.
            warmingGuestPlay = true
            try {
                repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, ::owns,
                    guest = true).getPlayUrlLegacy(bvid = "BV1GJ411x7h7", cid = 22L, qn = 80)
            } finally { warmingGuestPlay = false }
            assertEquals(revision, repository.playbackAuthorizationRevision.value)
            ui { create(); vm.loadVideo("BV1GJ411x7h7", 7, cid = 22) }
        }
        fun metadataDiagnostic(admission: DesktopOriginalVideoMetadataAdmission): String {
            val actual = vm.captureDesktopLoadState()
            val expected = captures.single().state
            return "entry=$entry scope=${scope.isActive} epoch=${repository.sessionEpoch}/$epoch " +
                "receiptCurrent=${repository.isPlaybackReceiptCurrent(admission.receipt)} " +
                "revision=${repository.playbackAuthorizationRevision.value}/${admission.receipt.revision} " +
                "mid=${repository.account.value?.mid} ownsSubject=${admission.ownsSubject()} " +
                "token=${actual.currentLoadRequestToken}/${expected.currentLoadRequestToken} " +
                "sameRequest=${actual.currentRequest === expected.currentRequest} " +
                "samePresentation=${vm.desktopMetadataAdmission.value === admission} " +
                "detailToken=${vm.detailState.value.requestToken} guestCalls=${guestPlayCalls.get()}"
        }
        suspend fun until(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(5) }
        suspend fun related(phase: VideoPlaybackUiState.RelatedLoadState) = withTimeout(5_000) {
            vm.detailState.first { it.relatedLoadState == phase && it.info != null }
        }
        suspend fun finishMain(expectedCaptures: Int = 1): InvocationCapture {
            playRelease.countDown()
            val failed = withTimeout(5_000) { vm.uiState.first { it is VideoPlaybackUiState.Error } } as VideoPlaybackUiState.Error
            assertEquals(412, assertIs<VideoLoadError.ApiError>(failed.error).code)
            assertTrue(failed.canRetry)
            assertEquals(1, selectionApiCalls.get())
            assertEquals(expectedCaptures, captures.size)
            val captured = captures.last()
            withTimeout(5_000) { captured.job.join() }
            assertTrue(captured.job.isCompleted); assertFalse(captured.job.isCancelled)
            return captured
        }
        suspend fun failNativePreparation(cause: Exception): Pair<InvocationCapture, VideoPlaybackUiState.Error> {
            preparationFailure = cause; preparationGate.complete(Unit)
            val failed = withTimeout(5_000) { vm.uiState.first { it is VideoPlaybackUiState.Error } }
                as VideoPlaybackUiState.Error
            val marker = assertIs<DesktopOriginalVideoNativePreparationFailure>(
                assertIs<VideoLoadError.UnknownError>(failed.error).throwable)
            // Coroutine debug recovery may copy an exception while retaining the
            // original in its cause chain. Keep exact original identity mandatory.
            val causes = generateSequence(assertNotNull(marker.cause)) { it.cause }.take(16).toList()
            assertTrue(causes.any { it === cause },
                "Native preparation must retain the exact original failure in its bounded cause chain")
            val captured = captures.single()
            withTimeout(5_000) { captured.job.join() }
            assertTrue(captured.job.isCompleted); assertFalse(captured.job.isCancelled)
            ui {
                assertSame(failed, vm.captureDesktopPlaybackState())
                assertSame(captured.state.currentRequest, vm.captureDesktopLoadState().currentRequest)
                assertEquals(captured.state.currentLoadRequestToken, vm.captureDesktopLoadState().currentLoadRequestToken)
                assertEquals(1, captures.size, "Fatal local initialization must not start automatic recovery")
            }
            return captured to failed
        }
        override fun close() {
            entry = false; scope.cancel(); playRelease.countDown(); relatedGate.countDown(); subtitles.close()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            PlaybackCooldownManager.clearAll()
            folder.toFile().deleteRecursively()
            assertTrue(uncaught.isEmpty(), "Unexpected coroutine background failures: $uncaught")
        }
    }


    @Test fun originalVmKeepsReadonlyMetadataAfterBlockedPlayUrlAndTerminalSelectionApi412() = runBlocking<Unit> {
        withTimeout(12_000) { Harness().use { h ->
            h.start()
            withTimeout(5_000) { h.playEntered.await() }
            val detail = h.related(VideoPlaybackUiState.RelatedLoadState.READY)
            assertEquals("Fixture", detail.info?.title)
            assertEquals("BV17x411w7KC", detail.related.single().bvid)
            assertIs<VideoPlaybackUiState.Loading>(h.vm.uiState.value)
            assertEquals(1, h.captures.size); assertEquals(1, h.relatedCalls.get())
            val permission = assertNotNull(h.vm.desktopMetadataAdmission.value)
            assertSame(h.captures.single().receipt, permission.receipt)
            assertTrue(permission.isCurrent()); assertNull(h.vm.subjectSnapshot.value)
            val initial = h.finishMain()
            assertTrue(initial.job.isCompleted)
            assertTrue(permission.isCurrent(), "Completed HTTP parent is not readonly entry authority: " + h.metadataDiagnostic(permission))
            assertTrue(h.guestPlayCalls.get() > 0, "Actual guest lane was validated during prewarm")
            assertSame(detail.related.single(), h.vm.detailState.value.related.single())
            assertIs<VideoPlaybackUiState.Error>(h.vm.uiState.value)
            assertEquals(0, h.mediaCalls.get()); assertTrue(h.uncaught.isEmpty())
        } }
    }

    @Test fun actualVmRelatedRetryCapturesFreshBindingAfterTerminalSelectionApi412MainRequest() = runBlocking<Unit> {
        withTimeout(12_000) { Harness().use { h ->
            h.relatedFailures.set(2); h.start()
            h.related(VideoPlaybackUiState.RelatedLoadState.FAILED)
            assertEquals(2, h.relatedCalls.get())
            assertNotNull(h.vm.desktopMetadataRelatedFailure.value)
            val initial = h.finishMain()
            val permission = assertNotNull(h.vm.desktopMetadataAdmission.value)
            assertTrue(permission.isCurrent(), "Readonly retry entry must remain current: " + h.metadataDiagnostic(permission))
            assertTrue(h.guestPlayCalls.get() > 0, "Actual guest lane was validated during prewarm")
            h.ui { h.vm.retryDesktopMetadataRelated(permission) }
            h.until { h.retryCaptures.size == 1 }
            val ready = h.related(VideoPlaybackUiState.RelatedLoadState.READY)
            assertEquals("BV17x411w7KC", ready.related.single().bvid)
            val retry = h.retryCaptures.single()
            assertNotSame(initial.job, retry.job); assertEquals(initial.receipt, retry.receipt)
            assertSame(initial.state.currentRequest, retry.state.currentRequest)
            assertEquals(initial.state.currentLoadRequestToken, retry.state.currentLoadRequestToken)
            withTimeout(5_000) { retry.job.join() }
            assertTrue(permission.isCurrent()); assertNull(h.vm.desktopMetadataRelatedFailure.value)
            assertEquals(3, h.relatedCalls.get()); assertEquals(0, h.mediaCalls.get())
            assertIs<VideoPlaybackUiState.Error>(h.vm.uiState.value); assertNull(h.vm.subjectSnapshot.value)
            assertTrue(h.uncaught.isEmpty())
        } }
    }

    @Test fun actualVmRejectsLateRelatedResultAfterAccountOrEntryRetirement() = runBlocking<Unit> {
        for (accountChange in listOf(true, false)) withTimeout(12_000) { Harness().use { h ->
            h.relatedGate = CountDownLatch(1); h.start()
            withTimeout(5_000) { h.relatedEntered.await() }
            h.until { h.vm.detailState.value.info != null }
            val permission = assertNotNull(h.vm.desktopMetadataAdmission.value)
            val before = h.vm.detailState.value
            if (accountChange) h.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-other"), AccountSummary(42, "Other", ""))
            else { h.entry = false; h.scope.cancel() }
            assertFalse(permission.isCurrent())
            h.relatedGate.countDown(); h.playRelease.countDown()
            withTimeout(5_000) { h.captures.single().job.join() }
            assertTrue(h.captures.single().job.isCompleted)
            assertEquals(before.related, h.vm.detailState.value.related)
            assertEquals(VideoPlaybackUiState.RelatedLoadState.LOADING, h.vm.detailState.value.relatedLoadState)
            assertNull(h.vm.subjectSnapshot.value); assertEquals(0, h.mediaCalls.get())
            assertFalse(permission.admit { error("Retired metadata may not navigate") })
            assertTrue(h.uncaught.isEmpty())
        } }
    }

    @Test fun actualVmStartsReadonlyDetailAndRelatedWhileNativePreparationIsPending(): Unit = runBlocking<Unit> {
        withTimeout(12_000) { Harness().use { h ->
            h.holdNativePreparation(); h.start()
            withTimeout(5_000) { h.preparationEntered.await() }
            val detail = h.related(VideoPlaybackUiState.RelatedLoadState.READY)
            val permission = assertNotNull(h.vm.desktopMetadataAdmission.value)
            assertEquals("Fixture", detail.info?.title)
            assertEquals("BV17x411w7KC", detail.related.single().bvid)
            assertTrue(permission.isCurrent()); assertNull(h.vm.subjectSnapshot.value)
            assertIs<VideoPlaybackUiState.Loading>(h.vm.uiState.value)
            assertEquals(1, h.preparationCalls.get())
            assertEquals(0, h.capabilityReads.get())
            assertEquals(0, h.mediaCalls.get())
            assertFalse(h.playEntered.isCompleted, "The raw media query must remain behind actual preparation")
            assertTrue(h.captures.single().job.isActive)
            // Drain the genuinely blocked main request before closing the fixture.
            withTimeout(5_000) { h.captures.single().job.cancelAndJoin() }
            assertTrue(h.uncaught.isEmpty())
        } }
    }

    @Test fun actualVmFatalNativePreparationKeepsReadonlyEntryWithoutAutomaticRecovery(): Unit = runBlocking<Unit> {
        withTimeout(12_000) { Harness().use { h ->
            h.holdNativePreparation(); h.start()
            withTimeout(5_000) { h.preparationEntered.await() }
            val detail = h.related(VideoPlaybackUiState.RelatedLoadState.READY)
            val permission = assertNotNull(h.vm.desktopMetadataAdmission.value)
            val (captured, failed) = h.failNativePreparation(IllegalStateException("controlled local native initialization failure"))
            assertSame(permission, h.vm.desktopMetadataAdmission.value)
            assertTrue(permission.isCurrent())
            assertSame(detail.info, h.vm.detailState.value.info)
            assertSame(detail.related.single(), h.vm.detailState.value.related.single())
            assertTrue(failed.canRetry, "A real manual retry remains available")
            val evidence = desktopOriginalApiFailure(failed.error)
            assertEquals(PlaybackFailureReason.Source, evidence.reason)
            assertFalse(evidence.automaticRecovery)
            assertFalse(evidence.decoder); assertFalse(evidence.premiumAudio)
            assertEquals(PlaybackStatus.Failed, h.vm.desktopPlaybackRecoveryState.value.status)
            assertEquals(PlaybackFailureReason.Source, h.vm.desktopPlaybackRecoveryState.value.failure?.reason)
            assertSame(captured.state.currentRequest, h.vm.captureDesktopLoadState().currentRequest)
            assertEquals(0, h.capabilityReads.get()); assertEquals(0, h.mediaCalls.get())
            assertFalse(h.playEntered.isCompleted); assertNull(h.vm.subjectSnapshot.value)
            assertTrue(h.uncaught.isEmpty())
        } }
    }

    @Test fun actualVmManualRetryAfterFatalPreparationCapturesFreshBindingAndWaitsAgain(): Unit = runBlocking<Unit> {
        withTimeout(12_000) { Harness().use { h ->
            h.holdNativePreparation(); h.start()
            withTimeout(5_000) { h.preparationEntered.await() }
            h.related(VideoPlaybackUiState.RelatedLoadState.READY)
            val oldPermission = assertNotNull(h.vm.desktopMetadataAdmission.value)
            val (initial, _) = h.failNativePreparation(IllegalStateException("controlled native failure before manual retry"))
            h.holdNativePreparation()
            h.ui { h.vm.retry() }
            withTimeout(5_000) { h.preparationEntered.await() }
            h.related(VideoPlaybackUiState.RelatedLoadState.READY)
            val retry = h.captures.last()
            val newPermission = assertNotNull(h.vm.desktopMetadataAdmission.value)
            assertEquals(2, h.captures.size)
            assertNotSame(initial.job, retry.job); assertEquals(initial.receipt, retry.receipt)
            assertNotSame(initial.state.currentRequest, retry.state.currentRequest)
            assertEquals(initial.state.currentLoadRequestToken + 1L, retry.state.currentLoadRequestToken)
            assertNotSame(oldPermission, newPermission)
            assertFalse(oldPermission.isCurrent()); assertTrue(newPermission.isCurrent())
            assertEquals(0, h.capabilityReads.get()); assertEquals(0, h.mediaCalls.get())
            assertFalse(h.playEntered.isCompleted)
            h.preparationGate.complete(Unit)
            // After the true fresh preparation, terminate at the existing real
            // same-Binding API412 selection path; no native playback is invented.
            val terminal = h.finishMain(expectedCaptures = 2)
            assertSame(retry.job, terminal.job)
            assertTrue(h.capabilityReads.get() > 0)
            assertTrue(newPermission.isCurrent()); assertNull(h.vm.subjectSnapshot.value)
            assertEquals(0, h.mediaCalls.get()); assertTrue(h.uncaught.isEmpty())
        } }
    }

    @Test fun actualVmCancelledNativePreparationCannotResumeFromALateReadyGrant(): Unit = runBlocking<Unit> {
        withTimeout(12_000) { Harness().use { h ->
            h.holdNativePreparation(); h.start()
            withTimeout(5_000) { h.preparationEntered.await() }
            h.related(VideoPlaybackUiState.RelatedLoadState.READY)
            val main = h.captures.single().job
            withTimeout(5_000) { main.cancelAndJoin() }
            assertTrue(main.isCancelled)
            h.preparationGate.complete(Unit)
            h.ui {
                assertFalse(h.vm.captureDesktopPlaybackState() is VideoPlaybackUiState.Success)
                assertNull(h.vm.subjectSnapshot.value)
                assertFalse(h.playEntered.isCompleted)
                assertEquals(0, h.capabilityReads.get()); assertEquals(0, h.mediaCalls.get())
            }
            assertEquals(1, h.captures.size)
            assertTrue(h.uncaught.isEmpty())
        } }
    }

    @Test fun actualVmRetiredEntryRejectsNativeGrantAndReadonlyActions(): Unit = runBlocking<Unit> {
        withTimeout(12_000) { Harness().use { h ->
            h.holdNativePreparation(); h.start()
            withTimeout(5_000) { h.preparationEntered.await() }
            val detail = h.related(VideoPlaybackUiState.RelatedLoadState.READY)
            val permission = assertNotNull(h.vm.desktopMetadataAdmission.value)
            val main = h.captures.single().job
            h.entry = false
            assertTrue(h.scope.isActive, "Retire the actual entry predicate without borrowing raw Job cancellation")
            assertFalse(permission.isCurrent())
            h.preparationGate.complete(Unit)
            withTimeout(5_000) { main.join() }
            assertTrue(main.isCancelled)
            assertSame(detail.info, h.vm.detailState.value.info)
            assertFalse(h.vm.uiState.value is VideoPlaybackUiState.Success)
            assertFalse(permission.admit { error("Retired readonly permission may not navigate") })
            assertFalse(h.playEntered.isCompleted)
            assertEquals(0, h.capabilityReads.get()); assertEquals(0, h.mediaCalls.get())
            assertNull(h.vm.subjectSnapshot.value); assertTrue(h.uncaught.isEmpty())
        } }
    }
}
