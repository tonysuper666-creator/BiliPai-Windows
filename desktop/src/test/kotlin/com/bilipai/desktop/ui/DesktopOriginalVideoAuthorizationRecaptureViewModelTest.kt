package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
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
import kotlin.test.*

/** Complete original VM -> invocation -> captured repository -> original protocol.
 * A real temporary Store starts logged in without buvid3; every GET is intercepted.
 * Success is bounded to the next captured playurl protocol request and the real
 * client CookieJar, before OkHttp's BridgeInterceptor; no socket/header/native
 * playback is asserted. The original UseCase/VM retain their CancellationException. */
class DesktopOriginalVideoAuthorizationRecaptureViewModelTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private data class InvocationCapture(val state: PlaybackSessionState,
        val receipt: DesktopPlaybackAuthorizationReceipt, val job: Job)
    private data class PlayCapture(val request: Request,
        val receipt: DesktopPlaybackAuthorizationReceipt, val cookies: List<Cookie>)

    private inner class Harness : AutoCloseable {
        val folder = Files.createTempDirectory("original-auth-recapture-")
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        var entry = true
        val epoch: Long
        val requests = CopyOnWriteArrayList<Request>()
        val pending = CopyOnWriteArrayList<Pair<DesktopOriginalVideoAuthorizationRetirement, InvocationCapture>>()
        val captures = CopyOnWriteArrayList<InvocationCapture>()
        val playEntered = CompletableDeferred<PlayCapture>()
        val playRelease = CountDownLatch(1)
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
                            val authorization = checkNotNull(sessions.requestPlaybackAuthorization.get())
                            check(transport.cookieJar === sessions)
                            val cookies = transport.cookieJar.loadForRequest(request.url).toList()
                            playEntered.complete(PlayCapture(request, authorization.receipt, cookies))
                            check(playRelease.await(4, TimeUnit.SECONDS))
                            """{"code":-404,"message":"No fixture media"}"""
                        }
                        "api.bilibili.com" to "/x/web-interface/archive/related" -> """{"code":0,"data":[]}"""
                        else -> throw java.io.IOException("No external fixture transport")
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            subtitles = DesktopSubtitleAssets(transport)
        }
        fun owns() = entry && scope.isActive && repository.sessionEpoch == epoch
        fun commit(action: () -> Unit): Boolean = if (!owns()) false else { action(); true }
        private val media = unused<DesktopOriginalVideoMediaPort>()
        private val actions = unused<DesktopOriginalVideoOwnerActions>()
        private val status = DesktopOriginalVideoEntryReadbacks(repository, epoch, scope, ::owns, ::commit)
        val invocations = DesktopOriginalVideoPlaybackInvocationPorts(scope, ::owns, {
            val callerJob = currentCoroutineContext().job
            val state = vm.captureDesktopLoadState()
            val binding = DesktopOriginalVideoRepositoryBinding.capture(repository, epoch, scope.coroutineContext.job,
                ::owns, ::commit, PlayerPreferences(), null, emptySet(), false, { true }, { false }, { false }, { false },
                { _, _ -> false }, { receipt ->
                    val notice = DesktopOriginalVideoAuthorizationRetirement(vm, receipt, state, ::owns,
                        vm::captureDesktopLoadState, { vm.retry() })
                    if (notice.isCurrent()) pending.add(notice to InvocationCapture(state, receipt, callerJob))
                })
            captures += InvocationCapture(state, binding.receipt, callerJob)
            val raw = createDesktopOriginalVideoOwnerRequestRepository(repository, binding, subtitles, privacy,
                { receipt, current -> repository.ownedHomeVisitorInitialized(receipt.accountEpoch, current) })
            DesktopOriginalVideoPlaybackInvocation(raw, media, binding::assertCurrent)
        }, status, { media })
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
            override fun isHevcSupported() = false
            override fun isAv1Supported() = false
            override fun isHdrSupported() = false
            override fun isDolbyVisionSupported() = false
            override fun isDolbyAtmosAudioSupported() = false
            override fun isDolbySoftwareAudioDecoderRequired() = false
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
                cache, { null }, unused(), unused(), unused(), unused(), MutableStateFlow(false), ::owns, ::commit,
                DesktopTodayWatchFeedbackWriteBinding(context, ::owns, ::commit)))
            vm.initWithContext(settings) // Original context/bootstrap observers complete capture before the load.
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        suspend fun canceledInitialLoad(): DesktopOriginalVideoAuthorizationRetirement {
            withTimeout(5_000) { while (pending.isEmpty()) delay(5) }
            val (notice, capture) = pending.single()
            // Exact Job from the capture that emitted this notice, not another
            // original launch such as a permanent preference observer.
            withTimeout(5_000) { capture.job.join() }
            assertTrue(capture.job.isCancelled)
            return notice
        }
        override fun close() {
            entry = false; scope.cancel(); playRelease.countDown(); subtitles.close()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            folder.toFile().deleteRecursively()
        }
    }

    @Test fun actualOriginalVmCanceledByFirstSpiRetriesOnlyItsOwnLoadWithFreshCapture() = runBlocking<Unit> {
        withTimeout(10_000) { Harness().use { h ->
            h.ui { h.create(); h.vm.loadVideo("BV1GJ411x7h7", 7, cid = 22) }
            val notice = h.canceledInitialLoad()
            h.ui { assertEquals(VideoPlaybackUiState.Loading.Initial, h.vm.uiState.value) }
            assertEquals(1, h.requests.count { it.url.encodedPath == "/x/frontend/finger/spi" })
            assertFalse(h.requests.any { it.url.encodedPath.contains("playurl") })
            h.ui { assertTrue(notice.recaptureIfCurrent()) }
            val play = withTimeout(5_000) { h.playEntered.await() }
            assertEquals("synthetic-visitor", play.cookies.single { it.name == "buvid3" }.value)
            assertEquals("BV1GJ411x7h7", play.request.url.queryParameter("bvid"))
            assertEquals("22", play.request.url.queryParameter("cid"))
            val first = h.pending.single().second
            // Equal revision values alone could belong to another observer;
            // the request ThreadLocal holds this exact Binding receipt instance.
            val next = h.captures.single { it.receipt === play.receipt }
            assertTrue(next.receipt.revision > first.receipt.revision)
            assertTrue(next.state.currentLoadRequestToken > first.state.currentLoadRequestToken)
            assertNotSame(first.state.currentRequest, next.state.currentRequest)
            assertEquals(1, h.pending.size)
            assertEquals(1, h.requests.count { it.url.encodedPath == "/x/frontend/finger/spi" })
        } }
    }

    @Test fun queuedSpiNoticeCannotRetryNewActualVmRequestOrChangedAccount() = runBlocking<Unit> {
        withTimeout(10_000) {
            Harness().use { h ->
                h.ui { h.create(); h.vm.loadVideo("BV1GJ411x7h7", 7, cid = 22) }
                val notice = h.canceledInitialLoad()
                h.ui {
                    h.vm.loadVideo("BV17x411w7KC", 8, cid = 22)
                    val current = h.vm.captureDesktopLoadState()
                    assertFalse(notice.recaptureIfCurrent())
                    assertSame(current.currentRequest, h.vm.captureDesktopLoadState().currentRequest)
                }
                withTimeout(5_000) { h.playEntered.await() }
            }
            Harness().use { h ->
                h.ui { h.create(); h.vm.loadVideo("BV1GJ411x7h7", 7, cid = 22) }
                val notice = h.canceledInitialLoad()
                h.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-next"), AccountSummary(42, "Next", ""))
                h.ui { assertFalse(notice.recaptureIfCurrent()) }
                assertFalse(h.requests.any { it.url.encodedPath.contains("playurl") })
            }
        }
    }
}
