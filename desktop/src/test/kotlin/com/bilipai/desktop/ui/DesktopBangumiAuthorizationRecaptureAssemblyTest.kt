package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.cache.DesktopMediaByteCache
import com.bilipai.desktop.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.lang.reflect.Proxy
import java.net.Proxy as NetworkProxy
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Real Assembly.create -> full original PGC VM -> concrete Presenter/Portrait
 * -> temporary SessionStore/Repository -> complete metadata nav and PGC protocol.
 * All HTTP is exact-origin in-memory; no Window, login profile, SDK or native
 * player initialization. The final native-publication port records its real
 * immutable request/DTO/seek intent then throws CE BEFORE source acceptance.
 * This proves producer recapture, not native ACK, hardware pause or media pixels.
 */
class DesktopBangumiAuthorizationRecaptureAssemblyTest {
    @Test fun realVipRefreshRecapturesSameEntryOnceAndPreservesOriginalResume(): Unit = runBlocking {
        Fixture(visitor = true).useSuspending { f ->
            f.ui { f.vm.loadBangumiPlay(19, 41, resumePositionMs = 7_123L) }
            val plan = f.awaitPlan()
            val retired = f.notices.single()
            assertTrue(retired.launch.isCancelled)
            assertEquals(true, f.repository.account.value?.isVip)
            assertEquals(41L, plan.state.currentEpisode.id)
            assertEquals(7_123L, plan.seekToMs)
            assertEquals(120, plan.state.quality)
            assertEquals(127, f.playRequests.single().request.url.queryParameter("qn")?.toInt())
            assertTrue(plan.receipt.revision > f.mediaCaptures.first().receipt.revision)
            assertSame(f.mediaCaptures.last().receipt, plan.receipt)
            assertTrue(f.repository.isPlaybackReceiptCurrent(plan.receipt))
            assertEquals(2, f.mediaCaptures.size)
            // The process initializes SPI once even with a saved visitor cookie.
            // Returning the identical visitor must not retire a second receipt.
            assertEquals(1, f.requests.count { it.url.encodedPath == "/x/frontend/finger/spi" })
            val originalToken = f.mediaCaptures.first().state.currentLoadRequestToken
            assertTrue(f.mediaCaptures.last().state.currentLoadRequestToken > originalToken)
            assertTrue(f.owner.playback.isDesktopBangumiPresenterCurrent(f.native))
            val captures = f.mediaCaptures.size
            val intent = f.vm.desktopPlaybackIntentRevision
            f.ui { retired.deliver(); retired.deliver() }
            assertEquals(captures, f.mediaCaptures.size)
            assertEquals(intent, f.vm.desktopPlaybackIntentRevision)
            assertEquals(1, f.plans.size)
            assertNull(f.player.currentSourceSnapshot())
            assertNull(f.owner.native.current())
        }
    }

    @Test fun firstLoginWithoutVisitorAllowsVipThenSpiRetirementWithFreshSameEntryBindings(): Unit = runBlocking {
        Fixture(visitor = false).useSuspending { f ->
            f.ui { f.vm.loadBangumiPlay(19, 41, resumePositionMs = 8_234L) }
            val plan = f.awaitPlan()
            assertEquals(2, f.notices.size)
            assertEquals(3, f.mediaCaptures.size)
            assertEquals(1, f.requests.count { it.url.encodedPath == "/x/frontend/finger/spi" })
            assertEquals("synthetic-visitor", f.sessions.currentCookies()["buvid3"])
            assertEquals(true, f.repository.account.value?.isVip)
            val receipts = f.mediaCaptures.map { it.receipt }
            assertTrue(receipts.zipWithNext().all { (old, next) -> next.revision > old.revision })
            assertSame(receipts.last(), plan.receipt)
            assertFalse(f.repository.isPlaybackReceiptCurrent(receipts.first()))
            assertFalse(f.repository.isPlaybackReceiptCurrent(receipts[1]))
            assertTrue(f.repository.isPlaybackReceiptCurrent(receipts.last()))
            assertEquals(1, f.playRequests.size)
            assertEquals("synthetic-visitor", f.playRequests.single().cookies.single { it.name == "buvid3" }.value)
            assertEquals(8_234L, plan.seekToMs)
            assertEquals(41L, plan.state.currentEpisode.id)
            val capturedCount = f.mediaCaptures.size
            f.ui { f.notices.forEach { it.deliver(); it.deliver() } }
            assertEquals(capturedCount, f.mediaCaptures.size)
            assertEquals(1, f.plans.size)
            assertNull(f.player.currentSourceSnapshot())
        }
    }

    @Test fun delayedRetirementCannotReloadAnOldEpisodeOrOverwriteNewSameEpisodeResumeIntent(): Unit = runBlocking {
        for ((episode, resume) in listOf(42L to 9_345L, 41L to 10_456L)) {
            Fixture(visitor = true, automaticDelivery = false).useSuspending { f ->
                f.ui { f.vm.loadBangumiPlay(19, 41, resumePositionMs = 1_000L) }
                val old = f.awaitRetirement()
                f.ui { f.vm.loadBangumiPlay(19, episode, resumePositionMs = resume) }
                val next = f.awaitPlan()
                val current = f.owner.captureLoadState()
                val captureCount = f.mediaCaptures.size
                f.ui { old.deliver(); old.deliver() }
                assertEquals(episode, next.state.currentEpisode.id)
                assertEquals(resume, next.seekToMs)
                assertEquals(captureCount, f.mediaCaptures.size)
                assertSame(current.currentRequest, f.owner.captureLoadState().currentRequest)
                assertEquals(current.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
                assertEquals(1, f.plans.size)
            }
        }
    }

    @Test fun delayedRetirementCannotResurrectRetiredRootOrChangedPrimaryAccount(): Unit = runBlocking {
        for (changeAccount in listOf(false, true)) {
            Fixture(visitor = true, automaticDelivery = false).useSuspending { f ->
                f.ui { f.vm.loadBangumiPlay(19, 41, resumePositionMs = 2_000L) }
                val old = f.awaitRetirement()
                val captureCount = f.mediaCaptures.size
                f.ui {
                    if (changeAccount) f.sessions.saveAccount(mapOf("SESSDATA" to "synthetic-next"),
                        AccountSummary(42, "Next", ""))
                    else f.rootCurrent = false
                    old.deliver(); old.deliver()
                }
                assertEquals(captureCount, f.mediaCaptures.size)
                assertTrue(f.playRequests.isEmpty())
                assertTrue(f.plans.isEmpty())
                assertFalse(f.native.owns())
                assertNull(f.player.currentSourceSnapshot())
            }
        }
    }

    @Test fun manualQualityIntentDuringLoadingRejectsDelayedInitialAuthorizationTicket(): Unit = runBlocking {
        Fixture(visitor = true, automaticDelivery = false).useSuspending { f ->
            f.ui { f.vm.loadBangumiPlay(19, 41, resumePositionMs = 3_000L) }
            val old = f.awaitRetirement()
            val captureCount = f.mediaCaptures.size
            val revision = f.vm.desktopPlaybackIntentRevision
            f.ui { f.vm.changeQuality(80); old.deliver(); old.deliver() }
            assertTrue(f.vm.desktopPlaybackIntentRevision > revision)
            assertEquals(captureCount, f.mediaCaptures.size)
            assertTrue(f.playRequests.isEmpty())
            assertTrue(f.plans.isEmpty())
            assertIs<BangumiPlayerState.Loading>(f.vm.uiState.value)
        }
    }

    @Test fun realUninitializedMpvPauseResumeAbaBeforeTicketAdmissionPreventsAutomaticRecapture(): Unit = runBlocking {
        for (pausedAtCapture in listOf(false, true)) {
            Fixture(visitor = true).useSuspending { f ->
                f.ui {
                    f.player.setPaused(pausedAtCapture)
                    f.beforeRetired = {
                        f.player.setPaused(!pausedAtCapture)
                        f.player.setPaused(pausedAtCapture)
                    }
                    f.vm.loadBangumiPlay(19, 41, resumePositionMs = 4_000L)
                }
                withTimeout(8_000L) {
                    while (f.mediaCaptures.isEmpty()) delay(5)
                    f.mediaCaptures.single().caller.join()
                    assertTrue(f.mediaCaptures.single().caller.isCancelled)
                }
                f.ui { assertEquals(pausedAtCapture, f.player.state.value.paused) }
                assertEquals(true, f.repository.account.value?.isVip)
                assertTrue(f.notices.isEmpty())
                assertEquals(1, f.mediaCaptures.size)
                assertTrue(f.playRequests.isEmpty())
                assertTrue(f.plans.isEmpty())
                // The equal-valued intent cannot resurrect a ticket whose
                // one real metadata event was rejected before EventQueue enqueue.
                f.ui { f.player.setPaused(pausedAtCapture) }
                assertEquals(1, f.mediaCaptures.size)
                assertTrue(f.notices.isEmpty())
                assertNull(f.player.currentSourceSnapshot())
            }
        }
    }

    @Test fun selectedPlaybackAccountRevisionAbaRejectsQueuedTicketAndFreshLazyChildWindow(): Unit = runBlocking {
        for (stage in listOf("queued", "link", "body")) {
            Fixture(visitor = true, automaticDelivery = stage != "queued").useSuspending { f ->
                fun switchPlaybackAccountAndBack() {
                    val epoch = f.repository.sessionEpoch
                    val revision = f.repository.playbackAuthorizationRevision.value
                    assertTrue(f.sessions.setPlaybackAccountMid(42, epoch) { true })
                    assertTrue(f.sessions.setPlaybackAccountMid(null, epoch) { true })
                    assertEquals(epoch, f.repository.sessionEpoch)
                    assertTrue(f.repository.playbackAuthorizationRevision.value > revision)
                }
                f.ui {
                    if (stage == "link") f.beforeRecaptureLink = ::switchPlaybackAccountAndBack
                    if (stage == "body") f.beforeRecaptureBody = ::switchPlaybackAccountAndBack
                    f.vm.loadBangumiPlay(19, 41, resumePositionMs = 5_000L)
                }
                val notice = f.awaitRetirement()
                if (stage == "queued") f.ui { switchPlaybackAccountAndBack(); notice.deliver(); notice.deliver() }
                else f.awaitRecaptureChild()
                f.ui { /* Drain the same EDT before reading final producer state. */ }
                assertEquals(1, f.mediaCaptures.size)
                assertTrue(f.playRequests.isEmpty())
                assertTrue(f.plans.isEmpty())
                assertNull(f.player.currentSourceSnapshot())
                assertTrue(f.native.owns()) // the primary/root epoch remained current
            }
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T
    private class MediaCapture(val receipt: DesktopPlaybackAuthorizationReceipt, val state: PlaybackSessionState, val caller: Job)
    private class PlayCapture(val request: Request, val receipt: DesktopPlaybackAuthorizationReceipt, val cookies: List<Cookie>)
    private class PlanCapture(val state: BangumiPlayerState.Success, val receipt: DesktopPlaybackAuthorizationReceipt, val seekToMs: Long)
    private class Notice(val launch: Job, private val delivery: () -> Unit) { fun deliver() = delivery() }

    private inner class Fixture(visitor: Boolean, private val automaticDelivery: Boolean = true) {
        val directory = Files.createTempDirectory("pgc-auth-real-assembly-")
        private val createdAbsoluteDirectory = directory.toAbsolutePath().normalize()
        private val createdRealDirectory = directory.toRealPath()
        private val systemTempDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        private val systemRealTempDirectory = systemTempDirectory.toRealPath()
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        var rootCurrent = true
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.Swing + CoroutineExceptionHandler { _, error -> failures += error })
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("settings")))
        val player = MpvPlayer()
        val requests = CopyOnWriteArrayList<Request>()
        val mediaCaptures = CopyOnWriteArrayList<MediaCapture>()
        val playRequests = CopyOnWriteArrayList<PlayCapture>()
        val plans = CopyOnWriteArrayList<PlanCapture>()
        val notices = CopyOnWriteArrayList<Notice>()
        var beforeRetired: (() -> Unit)? = null
        var beforeRecaptureLink: (() -> Unit)? = null
        var beforeRecaptureBody: (() -> Unit)? = null
        private val recaptureChildren = CopyOnWriteArrayList<Job>()
        private val transport: OkHttpClient
        private val subtitles: DesktopSubtitleAssets
        private val mediaCache: DesktopMediaByteCache
        private val privacy = DesktopSearchPreferences(directory.resolve("privacy"))
        private val epoch: Long
        lateinit var owner: DesktopOriginalVideoOwnerAssembly
        lateinit var native: DesktopOriginalBangumiNativePresenter
        lateinit var vm: BangumiPlayerViewModel
        private lateinit var portrait: DesktopOriginalPortraitPlatformBinding
        private fun owns() = rootCurrent && job.isActive && repository.sessionEpoch == epoch
        private fun commit(action: () -> Unit): Boolean = if (!owns()) false else { action(); true }
        private val settings = DesktopOriginalPlayerSettingsContext(context, ::owns, ::commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        private val progress = object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long) = 0L
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) = error("No accepted source progress")
        }
        private val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
            override fun isHevcSupported() = false
            override fun isAv1Supported() = false
            override fun isHdrSupported() = false
            override fun isDolbyVisionSupported() = false
            override fun isDolbyAtmosAudioSupported() = false
            override fun isDolbySoftwareAudioDecoderRequired() = false
        }
        private val network = object : DesktopOriginalVideoOwnerNetwork {
            override fun isMobileData() = false
            override fun isWifi() = true
            override fun cdnNetwork() = error("No media preparation or CDN transport")
        }
        private val noMedia = unused<DesktopOriginalVideoMediaPort>()

        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-secondary", "bili_jct" to "synthetic-secondary-csrf"),
                AccountSummary(42, "Secondary fixture account", "", isVip = false))
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"),
                AccountSummary(41, "Fixture", "", isVip = false))
            if (visitor) sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-visitor"), sessions.generation)
            epoch = repository.sessionEpoch
            transport = repository.httpClient.newBuilder().proxy(NetworkProxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    check(request.method == "GET" && request.url.isHttps && request.url.port == 443)
                    check(request.url.host in setOf("api.bilibili.com", "www.bilibili.com"))
                    val body = when (request.url.host to request.url.encodedPath) {
                        "www.bilibili.com" to "/" -> "in-memory visitor bootstrap"
                        "api.bilibili.com" to "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                        "api.bilibili.com" to "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":41,"vip":{"status":1},"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                        "api.bilibili.com" to "/x/space/bangumi/follow/list" -> """{"code":0,"data":{"list":[],"total":0}}"""
                        "api.bilibili.com" to "/pgc/view/web/season" -> seasonJson()
                        "api.bilibili.com" to "/pgc/player/web/v2/playurl", "api.bilibili.com" to "/pgc/player/web/playurl" -> {
                            val captured = checkNotNull(sessions.requestPlaybackAuthorization.get())
                            check(transport.cookieJar === sessions)
                            playRequests += PlayCapture(request, captured.receipt,
                                transport.cookieJar.loadForRequest(request.url).toList())
                            playJson()
                        }
                        else -> error("Unexpected fixture HTTP origin/path ${request.url.host}${request.url.encodedPath}")
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            subtitles = DesktopSubtitleAssets(transport)
            // Actual existing byte-cache actor owns a temporary loopback listener;
            // no lease, media preparation or outbound byte transport is requested.
            mediaCache = DesktopMediaByteCache(directory.resolve("bytes"), scope, repository)
            context.store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(true)))
        }

        private suspend fun capture(notify: ((DesktopPlaybackAuthorizationReceipt) -> Unit)? = null): DesktopOriginalVideoRepositoryBinding =
            DesktopOriginalVideoRepositoryBinding.capture(repository, epoch, job, ::owns, ::commit,
                PlayerPreferences(), null, emptySet(), false, { true }, { false }, { false }, { false }, { _, _ -> false }, notify)
        private fun requestRepository(binding: DesktopOriginalVideoRepositoryBinding) =
            createDesktopOriginalVideoOwnerRequestRepository(repository, binding, subtitles, privacy,
                { receipt, current -> repository.ownedHomeVisitorInitialized(receipt.accountEpoch, current) })

        suspend fun initialize() = ui {
            val readbacks = DesktopOriginalVideoEntryReadbacks(repository, epoch, scope, ::owns, ::commit)
            val operations = DesktopDynamicCardOperations(repository, epoch, ::owns)
            val comments = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
                arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, _ ->
                when (method.name) { "isOwned" -> owns(); "currentMid" -> 41L; else -> error("No comment operation ${method.name}") }
            } as DesktopVideoCommentRequests
            val commentRoot = DesktopOriginalCommentRootOwner(operations, comments, scope, operations.emotes, ::owns,
                { error("No fixture feedback effect") })
            val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
                arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, _ ->
                when (method.name) { "capturePlaybackDispatch" -> null; "enabledPlayerPlugins" -> emptyList<com.android.purebilibili.core.plugin.PlayerPlugin>()
                    else -> error("No pre-media plugin effect ${method.name}") }
            } as DesktopOriginalVideoOwnerPlugins
            val cache = object : DesktopOriginalVideoOwnerCache {
                override fun get(bvid: String, cid: Long): PlayUrlData? = null
                override fun invalidate(bvid: String, cid: Long) = error("No ordinary-video cache mutation")
            }
            val effects = DesktopOriginalVideoOwnerEffects(unused(), unused(), unused(), network, cache,
                unused(), unused(), unused(), MutableStateFlow(false))
            owner = DesktopOriginalVideoOwnerAssembly.create(context, settings, repository, epoch, scope,
                ::owns, ::commit, player, DesktopRepositoryPlaybackPublication(repository), { false }, MutableStateFlow(null),
                { error("No accepted native publication") }, { _, _ -> error("No accepted media byte admission") },
                captureInvocation = { _, _ -> val binding = capture()
                    DesktopOriginalVideoPlaybackInvocation(requestRepository(binding), noMedia, binding::assertCurrent) },
                status = readbacks, prepareAcceptedMedia = { error("No accepted media") }, createPlugins = { _, _ -> plugins },
                confirmFollow = { error("No follow write") }, feedback = { error("No UI feedback") },
                readHasPrimarySession = readbacks::hasPrimarySession, readHasPrimaryAccessToken = readbacks::hasPrimaryAccessToken,
                readPrimaryMid = readbacks::primaryMid, readOwnedCooldown = readbacks::cooldownRemainingMs,
                progress = progress, capabilities = capabilities,
                // The constructor's preferred-volume observation is permitted only
                // without an accepted source; no native property setter is called.
                applyPreferredVolume = { check(it.currentNativeSourceVersion() == null) },
                dashSegmentRequestsEnabled = { false }, logSeek = { _, _, _, _ -> error("No native seek") },
                diagnosticLoggingEnabled = { false }, resumeEndedSource = { error("No native restart") }, root = commentRoot,
                interactionAnalytics = unused(), effects = effects,
                todayWatchFeedback = DesktopTodayWatchFeedbackWriteBinding(context, ::owns, ::commit))
            portrait = DesktopOriginalPortraitPlatformBinding(owner, { owner.takeIf { owns() } }, unused(), unused(), unused(), unused(),
                captureRequest = { capture().also { mediaCaptures += MediaCapture(it.receipt, owner.captureLoadState(), currentCoroutineContext().job) } },
                creatorTeamFollowStateChanges = emptyFlow(), mediaCache = mediaCache,
                captureCookieHeader = { _, _ -> error("No native source Cookie header") }, favoriteQuickSaveDefaultFolder = flowOf(false),
                blockedUps = unused(), danmaku = unused(), currentCdnPlugin = { null },
                presentationLease = { _, _ -> error("No presentation lease") }, danmakuLease = { _, _ -> error("No danmaku lease") },
                releasePager = { _, _ -> error("No pager release") }, recover = { _, _ -> error("No surface recovery") },
                share = { _, _ -> error("No sharing") }, feedback = { _, _ -> error("No feedback") },
                surface = { _, _, _ -> error("No rendering") }, viewport = { _, _, _, _, _, _, _ -> error("No viewport") },
                danmakuSurface = { _, _, _, _, _ -> error("No danmaku rendering") },
                onPreparation = { error("No media preparation") }, prepareAcceptedMedia = { _, _, _ -> error("No accepted media") })
            native = DesktopOriginalBangumiNativePresenter(owner, { owner.takeIf { owns() } }, portrait, scope,
                { if (::vm.isInitialized) vm.uiState.value as? BangumiPlayerState.Success else null },
                captureEpisodeRequest = { _, notify ->
                    val state = owner.captureLoadState()
                    capture { receipt -> beforeRetired?.invoke(); notify(receipt, state) }
                        .also { mediaCaptures += MediaCapture(it.receipt, state, currentCoroutineContext().job) }
                },
                accountEpoch = { repository.sessionEpoch },
                authorizationRevision = { repository.playbackAuthorizationRevision.value })
            val api = repository.ownedHomeService(BangumiApi::class.java, "https://api.bilibili.com/", epoch, ::owns)
            val navApi = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, ::owns)
            val hub = DesktopOriginalBangumiHubRepository(api, navApi, unused(), { "synthetic-csrf" }, { 41L })
            val pages = DesktopOriginalBangumiPagesRequests(api, hub, { "synthetic-csrf" }, { "synthetic-session" }, ::owns, ::owns)
            val requestView = DesktopOriginalBangumiPlayerRequestsView(pages, native::capturedPlaybackBinding, native::assertCurrent)
            val account = DesktopOriginalBangumiPlayerAccountView(owner, repository, privacy, native::assertCurrent,
                { _, _, _, _ -> error("No accepted PGC heartbeat") },
                capturedPlaybackBinding = { native.capturedPlaybackBindingOrNull() }, requestRepository = ::requestRepository)
            val publication = object : DesktopOriginalBangumiNativePublication by native {
                override fun launchAuthorizationRecapture(block: suspend CoroutineScope.() -> Unit): Job {
                    val child = native.launchAuthorizationRecapture {
                        beforeRecaptureBody?.invoke()
                        block()
                    }
                    recaptureChildren += child
                    beforeRecaptureLink?.invoke()
                    return child
                }
                override suspend fun beginEpisodeWithAuthorizationRecapture(detail: BangumiDetail, episode: BangumiEpisode,
                    isIntentCurrent: () -> Boolean, onRetired: (Job, () -> Boolean, ((() -> Unit) -> Boolean)) -> Unit) {
                    native.beginEpisodeWithAuthorizationRecapture(detail, episode, isIntentCurrent) { caller, current, admit ->
                        val notice = Notice(caller) { onRetired(caller, current, admit) }; notices += notice
                        if (automaticDelivery) notice.deliver()
                    }
                }
                override fun publishDash(videoUrl: String, audioUrl: String?, seekToMs: Long, resetPlayer: Boolean,
                    referer: String, dashManifest: String?) {
                    native.assertCurrent()
                    val binding = native.capturedPlaybackBinding()
                    val state = vm.uiState.value as BangumiPlayerState.Success
                    assertEquals("https://www.bilibili.com/bangumi/play/ep${state.currentEpisode.id}", referer)
                    assertEquals(state.playUrl, videoUrl); assertEquals(state.audioUrl, audioUrl)
                    assertEquals(state.currentEpisode.bvid, owner.captureLoadState().currentRequest?.bvid)
                    assertEquals(state.currentEpisode.cid, owner.captureLoadState().currentRequest?.cid)
                    binding.assertCurrent()
                    plans += PlanCapture(state, binding.receipt, seekToMs)
                    throw CancellationException("Projection recorded before native acceptance: no load/ACK")
                }
                override fun publishSegments(segmentUrls: List<String>, seekToMs: Long, resetPlayer: Boolean, referer: String) = error("DASH fixture only")
            }
            val environment = DesktopOriginalBangumiPlayerEnvironment(scope, owner.section, settings, requestView, account,
                object : DesktopOriginalBangumiPlayerActions {
                    override suspend fun checkLikeStatus(aid: Long) = false
                    override suspend fun checkCoinStatus(aid: Long) = 0
                }, owner.environment.interactionUseCase, navApi, progress, unused(), publication, flowOf(false),
                { -1 }, { -1 }, { error("No audio preference write") }, { false }, { false }, { false }, { false }, { null },
                { _, _, _ -> error("No CDN plugin") }, { _, _, _ -> check(owns()) }, { error("No download action") },
                { check(it === owner.section); check(owner.native.current() == null) },
                { block -> native.launch(block) }, native::commit, native::owns, native::ownsPlaybackSource,
                network = network, dolbyVisionSupported = { false })
            vm = BangumiPlayerViewModel(environment)
            vm.attachPlayer(owner.section)
            scope.launch { vm.toastEvent.collect {} }
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        suspend fun awaitPlan(): PlanCapture = withTimeout(8_000L) { while (plans.isEmpty()) delay(5); plans.single() }
        suspend fun awaitRetirement(): Notice = withTimeout(8_000L) {
            while (notices.isEmpty()) delay(5)
            notices.single().also { it.launch.join(); assertTrue(it.launch.isCancelled) }
        }
        suspend fun awaitRecaptureChild() = withTimeout(8_000L) {
            while (recaptureChildren.isEmpty()) delay(5)
            recaptureChildren.single().join()
        }
        suspend fun close() {
            ui { if (::vm.isInitialized) vm.close() }
            rootCurrent = false
            if (::owner.isInitialized) owner.close()
            job.cancelAndJoin()
            mediaCache.close(); subtitles.close(); player.close()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            assertTrue(failures.isEmpty(), failures.toString())
            deleteOnlyCreatedFixtureDirectory()
        }
        private fun deleteOnlyCreatedFixtureDirectory() {
            // Validate the final absolute target before traversing or deleting.
            // Never follow Windows junctions/reparse points or symbolic links.
            check(directory.toAbsolutePath().normalize() == createdAbsoluteDirectory)
            check(createdAbsoluteDirectory.parent == systemTempDirectory)
            check(createdRealDirectory.parent == systemRealTempDirectory)
            check(createdAbsoluteDirectory != systemTempDirectory)
            check(createdAbsoluteDirectory.fileName.toString().startsWith("pgc-auth-real-assembly-"))
            fun validate(path: Path, attributes: BasicFileAttributes) {
                val absolute = path.toAbsolutePath().normalize()
                check(absolute.startsWith(createdAbsoluteDirectory))
                check(!attributes.isSymbolicLink && !attributes.isOther)
                check(path.toRealPath() == absolute)
                if (System.getProperty("os.name").startsWith("Windows")) {
                    val flags = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int
                    check(flags and 0x400 == 0) { "Refusing fixture cleanup through a reparse point: $path" }
                }
            }
            check(directory.toRealPath() == createdRealDirectory)
            val validated = mutableListOf<Path>()
            // Default file-tree traversal does not FOLLOW_LINKS. Validate each
            // directory before descent and all nodes before the first deletion.
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validate(dir, attrs); validated.add(dir)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validate(file, attrs); validated.add(file)
                    return FileVisitResult.CONTINUE
                }
            })
            for (path in validated.sortedByDescending { it.nameCount }) {
                check(directory.toRealPath() == createdRealDirectory)
                validate(path, Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS))
                Files.delete(path)
            }
        }
        suspend fun useSuspending(block: suspend (Fixture) -> Unit) {
            try { initialize(); withTimeout(20_000L) { block(this@Fixture) } } finally { close() }
        }
    }

    companion object {
        private fun seasonJson() = """{"code":0,"result":{"season_id":19,"season_type":1,"title":"Synthetic PGC","section":[],"episodes":[{"id":41,"aid":101,"cid":104,"bvid":"BV1GJ411x7h7","title":"One","duration":60000},{"id":42,"aid":102,"cid":105,"bvid":"BV17x411w7KC","title":"Two","duration":60000}],"user_status":{"vip":0,"follow":0,"follow_status":0}}}"""
        private fun playJson() = """{"code":0,"result":{"quality":120,"timelength":60000,"accept_quality":[120,80],"accept_description":["4K","1080P"],"dash":{"duration":60,"video":[{"id":120,"baseUrl":"https://media.invalid/pgc-video.m4s?synthetic=1","bandwidth":120000,"mime_type":"video/mp4","codecs":"avc1.640033","width":3840,"height":2160,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-99","index_range":"100-199"}}],"audio":[{"id":30280,"baseUrl":"https://media.invalid/pgc-audio.m4s?synthetic=1","bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-99","index_range":"100-199"}}]}}}"""
    }
}
