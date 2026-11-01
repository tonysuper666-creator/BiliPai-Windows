package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.android.purebilibili.feature.video.viewmodel.SponsorContributionPhase
import com.android.purebilibili.feature.plugin.SponsorBlockConfig
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*
import com.bilipai.desktop.plugins.*
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.awt.EventQueue
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.Proxy as NetworkProxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.ProxySelector
import java.net.URI
import java.net.SocketAddress
import java.util.UUID
import com.android.purebilibili.data.repository.SponsorBlockRepository
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.serialization.json.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual complete original VM, Slot/Gate/Assembly/RequestFactory/RootMediaFactory,
 * NativeOwner, RuntimeBinding and PluginBridge. Only HTTP responses, external queue
 * and platform effects are fixture inputs. Sponsor uses the actual original provider
 * and Repository with its original configurable URL pointed at isolated loopback.
 * No Windows Composable/gesture is executed by these business tests.
 * The existing memory ABI executes MPV
 * commands/ACKs without a native DLL, rendering thread, HWND or physical decoder.
 * One real Runtime is retained for this class, matching PluginManager's process
 * lifetime. All optional providers are disabled in its temporary Store before
 * registration. No fake Runtime readiness or manufactured accepted source exists. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesktopOriginalSponsorPresentationAssemblyTest {
    private lateinit var runtimeDirectory: Path
    private lateinit var store: DesktopPluginStore
    private lateinit var runtime: DesktopPluginRuntime
    private var originalProxySelector: ProxySelector? = null
    private var loopbackSelector: ProxySelector? = null
    private val rejectedPublicRequests = CopyOnWriteArrayList<String>()

    @BeforeAll fun createRuntime(): Unit = runBlocking {
        originalProxySelector = ProxySelector.getDefault()
        val selector = object : ProxySelector() {
            override fun select(uri: URI): List<NetworkProxy> {
                if ((uri.scheme != "http" && uri.scheme != "socket") || uri.host != "127.0.0.1" || uri.port !in 1..65535 || uri.userInfo != null) {
                    rejectedPublicRequests.add("${uri.scheme}://${uri.host}:${uri.port}${uri.path}")
                    // A fixture policy denial must escape OkHttp route retries; it is not a transport IOException.
                    throw SecurityException("The original public client is restricted to loopback HTTP")
                }
                return listOf(NetworkProxy.NO_PROXY)
            }
            override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
        }
        loopbackSelector = selector
        ProxySelector.setDefault(selector)
        try {
        // Read only: never replace the original Repository's fixed client. If an
        // older class initialized it first, fail before enabling any provider.
        // Root must run this class in its own fresh test worker in that situation.
        val originalClient = SponsorBlockRepository::class.java.getDeclaredField("client")
            .apply { isAccessible = true }.get(null) as OkHttpClient
        assertNull(originalClient.proxy, "An explicit original-client proxy could bypass the loopback selector")
        assertSame(selector, originalClient.proxySelector, "A previously initialized public client cannot provide this fixture's fail-closed boundary")
        runtimeDirectory = Files.createTempDirectory("sponsor-actual-runtime-").toRealPath()
        store = DesktopPluginStore(runtimeDirectory.resolve("settings"))
        val ids = listOf("sponsor_block", "danmaku_enhance", "eye_protection", "bilipai_feed_filter",
            "home_feed_anonymizer", "adfilter", "subscription_feed", "today_watch", "cdn_region", "dlna_cast", "google_cast")
        store.update("plugin_prefs", ids.associate { "plugin_enabled_$it" to JsonPrimitive(false) })
        runtime = withContext(Dispatchers.Swing) { DesktopPluginRuntime(store) }
        assertSame(runtime.context, com.bilipai.desktop.plugins.desktopPluginContext(PluginManager.getContext()),
            "PluginManager belongs to a prior Runtime; run SponsorPresentation in a fresh test worker")
        withTimeout(8_000) { for (id in ids) PluginManager.awaitPluginReady(id) }
        assertTrue(runtime.plugins.value.none { it.enabled })
        } finally {
            if (ProxySelector.getDefault() === selector) ProxySelector.setDefault(originalProxySelector)
        }
    }

    @AfterAll fun closeRuntime(): Unit = runBlocking {
        try {
            if (::runtime.isInitialized) runtime.shutdownForRestore()
            if (::runtimeDirectory.isInitialized) deleteCreatedDirectory(runtimeDirectory, "sponsor-actual-runtime-")
            assertTrue(rejectedPublicRequests.isEmpty(), rejectedPublicRequests.toString())
        } finally {
            if (loopbackSelector != null && ProxySelector.getDefault() === loopbackSelector) ProxySelector.setDefault(originalProxySelector)
        }
    }

    @Test fun actualOriginalProviderPublishesOneSkipReceiptAndPreservesVoteZeroAndReviewResults(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(A, 101, 104)
            val source = f.accepted()
            val skip = f.awaitSkip(source)
            f.assertSkip(skip, source)
            assertSame(runtime.originalSponsorBlock.getActiveSegment(), skip.segment)
            f.ui { f.owner.playback.voteCurrentSponsorSegment(0) }
            withTimeout(8_000) { while (f.server.votes.isEmpty() || f.toasts.none { it.contains("已提交社区投票") }) delay(5) }
            val vote = f.server.votes.single()
            assertEquals("POST", vote.method)
            assertEquals(emptyMap<String, String>(), vote.query)
            val voteBody = Json.parseToJsonElement(vote.body).jsonObject
            assertEquals(0, voteBody.getValue("type").jsonPrimitive.int)
            assertEquals(skip.segment?.UUID, voteBody.getValue("UUID").jsonPrimitive.content)
            assertEquals(USER_ID, voteBody.getValue("userID").jsonPrimitive.content)
            assertEquals(setOf("UUID", "userID", "type"), voteBody.keys)
            f.ui { f.owner.playback.markWholeVideoAsSponsor() }
            val review = f.contribution()
            assertSame(source, review.source)
            assertEquals(SponsorContributionPhase.REVIEW, review.state.phase)
            assertEquals(SponsorActionType.FULL, review.state.actionType)
            assertEquals(0L, review.state.startMs); assertEquals(0L, review.state.endMs)
            val failure = f.submitHeld(409, "duplicate fixture submission")
            assertEquals(SponsorContributionPhase.SUBMITTING, f.contribution().state.phase)
            failure.reply.release(); withTimeout(8_000) { failure.job.join() }
            assertFalse(failure.job.isCancelled)
            val retry = f.contribution()
            assertSame(source, retry.source)
            assertEquals(SponsorContributionPhase.REVIEW, retry.state.phase)
            assertFalse(retry.state.message.isNullOrBlank())
            assertEquals(review.state.category, retry.state.category)
            assertEquals(review.state.actionType, retry.state.actionType)
            val success = f.submitHeld(200, "[]")
            success.reply.release(); withTimeout(8_000) { success.job.join() }
            assertFalse(success.job.isCancelled)
            assertSame(source, f.contribution().source)
            assertEquals(SponsorContributionPhase.SUCCESS, f.contribution().state.phase)
            assertEquals("社区片段已提交", f.contribution().state.message)
            for (request in f.server.submissions) f.assertSubmission(request, source, SponsorCategory.SPONSOR)
            f.ui { f.owner.playback.cancelSponsorContribution() }
            assertSame(source, f.contribution().source)
            assertEquals(SponsorContributionPhase.READY, f.contribution().state.phase)
            assertEquals(f.owner.playback.sponsorContributionUiState.value, f.contribution().state)
        }
    }

    @Test fun sameSourceCancelAndRestartRejectsTheOldActualSubmissionCompletion(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(A, 101, 104)
            val source = f.accepted(); val original = f.owner.captureLoadState()
            f.awaitSkip(source)
            f.ui { f.owner.playback.markWholeVideoAsSponsor() }
            val oldReview = f.contribution()
            val held = f.submitHeld(200, "[]")
            f.ui {
                f.owner.playback.cancelSponsorContribution()
                assertEquals(SponsorContributionPhase.READY, f.contribution().state.phase)
                f.owner.playback.markWholeVideoAsSponsor()
                f.owner.playback.setSponsorContributionCategory(SponsorCategory.SELFPROMO)
            }
            val restarted = f.contribution()
            assertNotSame(oldReview, restarted)
            assertSame(source, restarted.source)
            assertEquals(SponsorContributionPhase.REVIEW, restarted.state.phase)
            assertEquals(SponsorCategory.SELFPROMO, restarted.state.category)
            assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            held.reply.release(); withTimeout(8_000) { held.job.join() }
            assertFalse(held.job.isCancelled, "Canceling the UI draft is not canceling this still-owned server request")
            assertSame(source, f.accepted())
            assertSame(restarted, f.contribution(), "A completed old request must not replace the newer original REVIEW")
            assertEquals(restarted.state, f.owner.playback.sponsorContributionUiState.value)
            f.assertSubmission(f.server.submissions.single(), source, SponsorCategory.SPONSOR)
            val fresh = f.submitHeld(200, "[]")
            fresh.reply.release(); withTimeout(8_000) { fresh.job.join() }
            assertEquals(SponsorContributionPhase.SUCCESS, f.contribution().state.phase)
            assertEquals(SponsorCategory.SELFPROMO, f.contribution().state.category)
            f.assertSubmission(f.server.submissions.last(), source, SponsorCategory.SELFPROMO)
        }
    }

    @Test fun actualSameBvQualityChildRetiresOldNativeActionsAndCanStartANewOriginalDraft(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(A, 101, 104)
            val source = f.accepted(); val original = f.owner.captureLoadState()
            val skip = f.awaitSkip(source)
            f.ui { f.owner.playback.markWholeVideoAsSponsor() }
            val oldReview = f.contribution(); val voteCount = f.server.votes.size
            f.ui { f.owner.playback.changeQuality(32) }
            withTimeout(8_000) {
                while (f.owner.native.current() == null || f.owner.native.current() === source ||
                    (f.owner.playback.uiState.value as? VideoPlaybackUiState.Success)?.currentQuality != 32 || f.state().isQualitySwitching) delay(5)
            }
            val child = f.accepted()
            assertNotSame(source, child)
            assertEquals(source.request.bvid, child.request.bvid); assertEquals(source.request.cid, child.request.cid)
            assertTrue(child.sourceVersion > source.sourceVersion)
            assertEquals(32, f.state().currentQuality)
            assertTrue(child.nativeSource.source.videoUrl.contains("quality=32"))
            assertSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertFalse(f.owner.native.isCurrent(skip.source))
            var oldActionEntered = false
            f.ui {
                assertFalse(f.owner.native.admitPlaybackDispatch(source) {
                    oldActionEntered = true; f.owner.playback.voteCurrentSponsorSegment(0)
                })
                assertFalse(f.owner.native.admitPlaybackDispatch(checkNotNull(oldReview.source)) {
                    oldActionEntered = true; f.owner.playback.setSponsorContributionCategory(SponsorCategory.INTRO)
                })
            }
            assertFalse(oldActionEntered)
            assertEquals(voteCount, f.server.votes.size)
            withTimeout(8_000) { while (f.runtimeBinding.generation(child) == null) delay(5) }
            f.ui { f.actor.drain(); f.actor.loaded(); f.owner.playback.markSponsorContributionBoundary() }
            val fresh = f.contribution()
            assertSame(child, fresh.source)
            assertEquals(SponsorContributionPhase.MARKING, fresh.state.phase)
            assertEquals(27_750L, fresh.state.startMs)
            assertNotSame(oldReview, fresh)
            // A paused unchanged position need not produce another original
            // ShowButton. Any retained old immutable receipt stays retired;
            // it must never be re-stamped with the child's accepted source.
            f.owner.playback.desktopSponsorPresentation.value.skip?.let { published ->
                if (published.source === child) f.assertSkip(published, child)
                else assertSame(source, published.source)
            }
            assertTrue(f.presentationEmissions.any { it.skip === skip })
            assertTrue(f.presentationEmissions.any { it.contribution === oldReview })
            assertTrue(f.presentationEmissions.any { it.contribution === fresh })
        }
    }

    @Test fun actualNetworkChildCannotPublishTheOldSubmissionOrReuseItsImmutableSourceReceipt(): Unit = runBlocking {
        Fixture(refreshExpiredUrls = true).useSuspending { f ->
            f.load(A, 101, 104, audioLang = "en")
            val source = f.accepted(); val original = f.owner.captureLoadState()
            val oldSkip = f.awaitSkip(source)
            f.ui { f.owner.playback.markWholeVideoAsSponsor() }
            val oldReview = f.contribution(); val held = f.submitHeld(200, "[]")
            f.failExpiredNetwork()
            withTimeout(8_000) {
                while (f.playRequests.size < 2 || f.owner.native.current() == null || f.owner.native.current() === source ||
                    (f.owner.playback.uiState.value as? VideoPlaybackUiState.Success)?.playUrl != retryVideo(2)) delay(5)
            }
            val child = f.accepted()
            assertEquals(source.request.bvid, child.request.bvid); assertEquals(source.request.cid, child.request.cid)
            assertTrue(child.sourceVersion > source.sourceVersion)
            assertNotSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertTrue(assertNotNull(f.owner.captureLoadState().currentRequest).force)
            assertFalse(f.owner.native.isCurrent(oldSkip.source))
            var oldActionEntered = false
            f.ui { assertFalse(f.owner.native.admitPlaybackDispatch(checkNotNull(oldReview.source)) {
                oldActionEntered = true; f.owner.playback.setSponsorContributionCategory(SponsorCategory.INTRO)
            }) }
            assertFalse(oldActionEntered)
            held.reply.release(); withTimeout(8_000) { held.job.join() }
            withTimeout(8_000) { while (f.runtimeBinding.generation(child) == null) delay(5) }
            f.ui { f.actor.drain(); f.actor.loaded() }
            withTimeout(8_000) {
                while (f.owner.playback.desktopSponsorPresentation.value.contribution?.let {
                    it.source === child && it.state.phase == SponsorContributionPhase.READY
                } != true) delay(5)
            }
            assertEquals(2, f.playRequests.size)
            assertSame(child, f.contribution().source)
            assertEquals(SponsorContributionPhase.READY, f.contribution().state.phase)
            assertEquals(1, f.server.submissions.size)
            assertFalse(f.presentationEmissions.any { it.contribution?.source === child &&
                it.contribution?.state?.phase == SponsorContributionPhase.SUCCESS })
            f.assertSkip(f.awaitSkip(child), child)
            f.ui { f.owner.playback.markWholeVideoAsSponsor() }
            assertSame(child, f.contribution().source)
            assertEquals(SponsorContributionPhase.REVIEW, f.contribution().state.phase)
            assertEquals(27.75, child.nativeSource.source.startPositionSeconds)
            assertTrue(child.nativeSource.source.startPaused)
        }
    }

    private data class Capture(val state: PlaybackSessionState, val job: Job)
    private data class CdnEmission(val source: DesktopOriginalVideoAcceptedPublication, val index: Int, val runtimeGeneration: Long?)
    private data class HeldDispatch(val context: CoroutineContext, val runnable: Runnable) {
        val job get() = checkNotNull(context[Job])
    }
    /** Delay the actual DEFAULT launch before Assembly captures its SessionState.
     * Every released continuation executes on the same real EDT. This never
     * edits VM state, NativeOwner, an authorization receipt or Runtime generation. */
    private class OneDispatchHold : CoroutineDispatcher() {
        private val lock = Any()
        private var armedThread: Thread? = null
        private var held: HeldDispatch? = null
        override fun isDispatchNeeded(context: CoroutineContext) = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val intercepted = synchronized(lock) {
                // The click synchronously starts DEFAULT on this EDT. An unrelated
                // HTTP completion can dispatch concurrently and must never steal
                // the test's delay or become the Job that the test later joins.
                if (armedThread === Thread.currentThread() && context[Job]?.isActive == true) {
                    check(held == null); armedThread = null; held = HeldDispatch(context, block); true
                } else false
            }
            if (!intercepted) EventQueue.invokeLater(block)
        }
        fun arm() = synchronized(lock) {
            check(EventQueue.isDispatchThread() && armedThread == null && held == null)
            armedThread = Thread.currentThread()
        }
        fun take(): HeldDispatch = synchronized(lock) { checkNotNull(held) }
        fun release(expected: HeldDispatch) {
            synchronized(lock) { check(held === expected); held = null }
            EventQueue.invokeLater(expected.runnable)
        }
        fun drainOnClose() { val pending = synchronized(lock) { armedThread = null; held.also { held = null } }; pending?.let { EventQueue.invokeLater(it.runnable) } }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private inner class Fixture(private val holdTags: Boolean = false, private val refreshExpiredUrls: Boolean = false) {
        val directory = Files.createTempDirectory("sponsor-original-owner-").toRealPath()
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        private val parentJob = SupervisorJob()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val dispatcher = OneDispatchHold()
        private val scope = CoroutineScope(parentJob + dispatcher + CoroutineExceptionHandler { _, error -> failures += error })
        private val slot = DesktopOriginalVideoRootOwnerSlot(repository, scope, parentJob::isActive)
        lateinit var gate: DesktopOriginalVideoRootGate
        lateinit var owner: DesktopOriginalVideoOwnerAssembly
        lateinit var runtimeBinding: DesktopOriginalVideoRootRuntimeBinding
        lateinit var actor: PremiumRecoveryActor
        val player = MpvPlayer()
        val captures = CopyOnWriteArrayList<Capture>()
        val publications = CopyOnWriteArrayList<DesktopOriginalVideoAcceptedPublication>()
        val preparations = CopyOnWriteArrayList<DesktopOriginalMediaCachePreparation>()
        val cdnEmissions = CopyOnWriteArrayList<CdnEmission>()
        val presentationEmissions = CopyOnWriteArrayList<DesktopOriginalSponsorPresentation>()
        val server = LoopbackSponsorServer()
        val toasts = CopyOnWriteArrayList<String>()
        private val unexpectedRequests = CopyOnWriteArrayList<String>()
        val playRequests = CopyOnWriteArrayList<Request>()
        @Volatile private var firstUrlsExpired = false
        val tagsEntered = CompletableDeferred<Unit>()
        val tagsRelease = CountDownLatch(if (holdTags) 1 else 0)
        var beforePreparation: ((DesktopOriginalMediaCachePreparation) -> Unit)? = null
        private val context = runtime.context
        private val privacy = DesktopSearchPreferences(directory.resolve("privacy"))
        private lateinit var cache: DesktopMediaByteCache
        private val transport: OkHttpClient
        private val subtitles: DesktopSubtitleAssets
        private val epoch: Long
        private val playlist = MutableStateFlow<List<PlaylistItem>>(emptyList())
        private val index = MutableStateFlow(0)
        private val queue = object : DesktopOriginalVideoOwnerPlaylist {
            override val playlist get() = this@Fixture.playlist
            override val currentIndex get() = index
            override val playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
            override val isExternalPlaylist = MutableStateFlow(false)
            override val externalPlaylistSource = MutableStateFlow(ExternalPlaylistSource.NONE)
            override fun setPlaylist(items: List<PlaylistItem>, startIndex: Int) { playlist.value = items; index.value = startIndex }
            override fun playAt(index: Int) = playlist.value.getOrNull(index).also { if (it != null) this@Fixture.index.value = index }
            override fun playNext(): PlaylistItem? = error("No queue navigation")
            override fun playPrevious(): PlaylistItem? = error("No queue navigation")
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
            override fun syncCurrentVideoInfo(state: VideoPlaybackUiState.Success) { check(state.info.bvid in listOf(A, B)) }
            override fun updateCachedVideoTags(bvid: String, tags: List<VideoTag>) { check(bvid in listOf(A, B)) }
        }
        private val network = object : DesktopOriginalVideoOwnerNetwork {
            override fun isMobileData() = false
            override fun isWifi() = true
            override fun relatedNetworkObservation() = DesktopOriginalVideoRelatedNetworkObservation(
                DesktopHomeNetworkObservation(true, 3, 71L, false, "cpu-memory-transport"),
                downstreamBandwidthKbps = null,
            )
            override fun cdnNetwork() = DesktopCdnNetworkObservation(true, "cpu-memory-transport")
            override fun getDefaultQualityId(context: DesktopOriginalPlayerSettingsContext) = 64
        }
        private val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
            override fun isHevcSupported() = false
            override fun isAv1Supported() = false
            override fun isHdrSupported() = false
            override fun isDolbyVisionSupported() = false
            override fun isDolbyAtmosAudioSupported() = false
            override fun isDolbySoftwareAudioDecoderRequired() = false
        }
        private val progress = object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long) = 0L
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) { check(bvid in listOf(A, B)); check(cid in listOf(104L, 105L)) }
        }
        init {
            // The original guest fetch deliberately omits audioLang. This case verifies
            // the logged-in language-preserving route with private synthetic credentials.
            if (refreshExpiredUrls) sessions.saveAccount(
                mapOf("SESSDATA" to "synthetic-retry-session", "bili_jct" to "synthetic-retry-csrf"),
                AccountSummary(41, "Retry fixture", "", isVip = false))
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-visitor", "buvid4" to "synthetic-visitor4"), sessions.generation)
            epoch = repository.sessionEpoch
            store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(false), "video_codec" to JsonPrimitive("avc1"),
                "video_second_codec" to JsonPrimitive("avc1"), "wifi_quality" to JsonPrimitive(64)))
            store.update("settings", mapOf("video_codec_preference" to JsonPrimitive("avc1"), "video_second_codec_preference" to JsonPrimitive("avc1")))
            store.update("video_note_settings", mapOf("video_note_enabled" to JsonPrimitive(false)))
            transport = repository.httpClient.newBuilder().proxy(NetworkProxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    if (!request.url.isHttps || request.url.port != 443) rejectRequest(request)
                    if (request.url.host in setOf("media.invalid", "backup.invalid")) return@addInterceptor memoryMedia(request)
                    if (request.url.host == "bvc.bilivideo.com") return@addInterceptor memoryPbp(request)
                    if (request.url.host !in setOf("api.bilibili.com", "www.bilibili.com")) rejectRequest(request)
                    val bvid = request.url.queryParameter("bvid") ?: A
                    val body = when (request.url.encodedPath) {
                        "/" -> "memory bootstrap"
                        "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                        "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":${refreshExpiredUrls},"vip":{"status":0},"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                        "/x/web-interface/view" -> viewJson(bvid)
                        "/x/player/wbi/playurl", "/x/player/playurl" -> {
                            val revision = synchronized(playRequests) { playRequests.add(request); playRequests.size }
                            if (refreshExpiredUrls) {
                                check(bvid == A && revision in 1..2)
                                retryPlayJson(revision)
                            } else playJson(bvid)
                        }
                        "/x/web-interface/archive/related" -> """{"code":0,"data":[]}"""
                        "/x/tag/archive/tags" -> {
                            if (holdTags && bvid == A) { tagsEntered.complete(Unit); check(tagsRelease.await(8, TimeUnit.SECONDS)) }
                            """{"code":0,"data":[{"tag_id":71,"tag_name":"Fresh metadata"}]}"""
                        }
                        "/x/player/wbi/v2", "/x/player/v2" -> """{"code":0,"data":{"subtitle":{"subtitles":[]}}}"""
                        "/x/emote/user/panel/web" -> """{"code":0,"data":{"packages":[]}}"""
                        else -> """{"code":-404,"message":"No optional fixture data"}"""
                    }
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            subtitles = DesktopSubtitleAssets(transport)
        }

        suspend fun initialize() {
            // Save valid original configuration before enabling the real provider.
            // No public default URL can be used by this fixture's onVideoLoad.
            runtime.setEnabled("sponsor_block", false)
            runtime.saveSponsorConfiguration(SponsorBlockConfig(autoSkip = false,
                serverBaseUrl = server.baseUrl, userId = USER_ID,
                communityContributionEnabled = true, communityTrackingEnabled = false,
                dailySummaryNotificationEnabled = false))
            runtime.setEnabled("sponsor_block", true)
            val stored = assertNotNull(runtime.configuration("sponsor_block"))
            assertEquals(server.baseUrl, Json.parseToJsonElement(stored).jsonObject.getValue("serverBaseUrl").jsonPrimitive.content)
            privacy.setPrivacyMode(true)
            cache = DesktopMediaByteCache(directory.resolve("bytes"), scope, repository)
            slot.install(epoch, { actualGate ->
                gate = actualGate
                val settings = DesktopOriginalPlayerSettingsContext(context, gate::owns, gate::commitEntry,
                    largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
                val readbacks = DesktopOriginalVideoEntryReadbacks(repository, epoch, gate.scope, gate::owns, gate::commitEntry)
                val operations = DesktopDynamicCardOperations(repository, epoch, gate::owns)
                val comments = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
                    arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, _ ->
                    when (method.name) { "isOwned" -> gate.owns(); "currentMid" -> null
                        else -> error("No comment effect ${method.name}") }
                } as DesktopVideoCommentRequests
                val root = DesktopOriginalCommentRootOwner(operations, comments, gate.scope, operations.emotes, gate::owns,
                    { error("No comment feedback") })
                val effects = DesktopOriginalVideoOwnerEffects(mini, queue, unused(), network,
                    object : DesktopOriginalVideoOwnerCache {
                        override fun get(bvid: String, cid: Long): PlayUrlData? = null
                        override fun invalidate(bvid: String, cid: Long) = Unit
                    }, object : DesktopOriginalVideoOwnerAnalytics {
                        override fun logVideoPlay(videoId: String, title: String, author: String) { check(videoId in listOf(A, B)) }
                        override fun logQualityChange(videoId: String, fromQuality: Int, toQuality: Int) { check(videoId == A && fromQuality == 64 && toQuality == 32) }
                    }, DesktopOriginalVideoOwnerCrash { _, _, message -> error("Unexpected original load error $message") },
                    unused(), MutableStateFlow(false))
                val media = DesktopOriginalVideoRootMediaFactory(repository, cache, gate, { owner }, { preparation ->
                    preparations.add(preparation); beforePreparation?.invoke(preparation)
                })
                val requests = DesktopOriginalVideoOwnerRequestFactory(repository, DesktopLoginRepository(repository), epoch,
                    gate.scope, gate::owns, gate::commitEntry, { PlayerPreferences() }, capabilities,
                    { true }, { false }, { false }, subtitles, privacy,
                    { _, _ -> error("Preseeded visitor authorization must not retire") }, media::request)
                runtimeBinding = DesktopOriginalVideoRootRuntimeBinding(repository, runtime, gate) { owner.native }
                DesktopOriginalVideoOwnerAssembly.create(context, settings, repository, epoch, gate.scope,
                    gate::owns, gate::commitEntry, player, DesktopRepositoryPlaybackPublication(repository), { false },
                    runtimeBinding.sourceVersions, { expected -> publications.add(expected); runtimeBinding.accepted(expected) },
                    media::admission, captureMetadataBinding = { error("No readonly metadata recapture in this fixture") }, captureInvocation = { state, native ->
                        captures.add(Capture(state, currentCoroutineContext().job)); requests.capture(state, native, mediaPreparation = {
                            // Fixed-capability headless fixture; no physical NativeReady claim.
                            currentCoroutineContext().ensureActive()
                            if (!gate.owns()) throw CancellationException("Fixture preparation entry retired")
                        })
                    }, status = readbacks, prepareAcceptedMedia = media::accepted,
                    createPlugins = { native, invocations -> DesktopOriginalVideoOwnerPluginBridge(runtime, native, invocations,
                        runtimeBinding::generation, invocations::captureCurrentRequestAdmission, runtimeBinding::calls) },
                    confirmFollow = { error("No follow") }, feedback = {}, readHasPrimarySession = readbacks::hasPrimarySession,
                    readHasPrimaryAccessToken = readbacks::hasPrimaryAccessToken, readPrimaryMid = readbacks::primaryMid,
                    readOwnedCooldown = readbacks::cooldownRemainingMs, progress = progress, capabilities = capabilities,
                    applyPreferredVolume = {}, dashSegmentRequestsEnabled = { false }, logSeek = { _, _, _, _ -> },
                    diagnosticLoggingEnabled = { false }, resumeEndedSource = { error("No replay") }, root = root,
                    interactionAnalytics = unused(), effects = effects,
                    todayWatchFeedback = DesktopTodayWatchFeedbackWriteBinding(context, gate::owns, gate::commitEntry))
            }, {}, { runtimeBinding.close() }, {})
            ui {
                player.loadVersioned(PlaybackSource("file:///synthetic-bootstrap.avi"))
                actor = PremiumRecoveryActor(player)
                owner = slot.requireAssembly()
                owner.playback.initWithContext(DesktopOriginalPlayerSettingsContext(context, gate::owns, gate::commitEntry,
                    largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false }))
            }
            // Observe the actual original backing StateFlow without seeding,
            // replacing or mutating it. Its synchronous commit is distinct from
            // the original public combine(...).stateIn projection on Main.
            gate.scope.launch(Dispatchers.Unconfined) {
                originalState().collect { state ->
                    if (state is VideoPlaybackUiState.Success && state.currentCdnIndex == 1) {
                        owner.native.current()?.let { child -> cdnEmissions.add(CdnEmission(child, state.currentCdnIndex, runtimeBinding.generation(child))) }
                    }
                }
            }
            gate.scope.launch(Dispatchers.Unconfined) {
                owner.playback.desktopSponsorPresentation.collect { presentationEmissions.add(it) }
            }
            gate.scope.launch { owner.playback.toastEvent.collect { toasts.add(it.toString()) } }
        }
        fun state() = owner.playback.uiState.value as VideoPlaybackUiState.Success
        @Suppress("UNCHECKED_CAST")
        fun originalState(): StateFlow<VideoPlaybackUiState> = owner.playback.javaClass
            .getDeclaredField("_uiState").apply { isAccessible = true }.get(owner.playback) as StateFlow<VideoPlaybackUiState>
        fun accepted() = checkNotNull(owner.native.current())
        fun publicationRequestJob(expected: DesktopOriginalVideoAcceptedPublication): Job {
            val initial = assertIs<DesktopOriginalVideoInitialPublication>(expected.nativeSource.source.nativePublication)
            // Read the exact immutable request Job of the actual production
            // publication. No Job/source/state is replaced by this observation.
            return DesktopOriginalVideoInitialPublication::class.java.getDeclaredField("requestJob")
                .apply { isAccessible = true }.get(initial) as Job
        }
        private fun rejectRequest(request: Request): Nothing {
            unexpectedRequests.add("${request.method} ${request.url.host}${request.url.encodedPath}")
            throw IOException("Unrecognized memory fixture request")
        }
        private fun memoryMedia(request: Request): Response {
            if (request.method !in setOf("HEAD", "GET") || request.url.encodedPath !in
                listOf(A, B).flatMap { listOf("/$it-video.m4s", "/$it-audio.m4s") }) rejectRequest(request)
            if (refreshExpiredUrls && firstUrlsExpired && request.url.queryParameter("retry_revision") == "1") {
                return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(403).message("expired fixture URL")
                    .body(ByteArray(0).toResponseBody("application/octet-stream".toMediaType())).build()
            }
            val content = ByteArray(4096) { (it % 251).toByte() }
            val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("memory media")
                .header("Accept-Ranges", "bytes").header("ETag", "\"synthetic-${request.url.encodedPath}\"")
            val type = "application/octet-stream".toMediaType()
            if (request.method == "HEAD") return builder.code(200).header("Content-Length", "${content.size}")
                .body(ByteArray(0).toResponseBody(type)).build()
            val range = request.header("Range")
            if (range == null) return builder.code(200).header("Content-Length", "${content.size}")
                .body(content.toResponseBody(type)).build()
            val match = Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(range) ?: rejectRequest(request)
            val start = match.groupValues[1].toLongOrNull() ?: rejectRequest(request)
            val requestedEnd = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull() ?: (content.size - 1L)
            if (start >= content.size || requestedEnd < start) return builder.code(416)
                .header("Content-Range", "bytes */${content.size}").body(ByteArray(0).toResponseBody(type)).build()
            val end = minOf(requestedEnd, content.size - 1L)
            val bytes = content.copyOfRange(start.toInt(), end.toInt() + 1)
            return builder.code(206).header("Content-Range", "bytes $start-$end/${content.size}")
                .header("Content-Length", "${bytes.size}").body(bytes.toResponseBody(type)).build()
        }
        private fun memoryPbp(request: Request): Response {
            val bvid = request.url.queryParameter("bvid")
            val subject = when (bvid) { A -> "104" to "101"; B -> "105" to "102"; else -> rejectRequest(request) }
            if (request.method != "GET" || request.url.encodedPath != "/pbp/data" ||
                request.url.queryParameterNames != setOf("cid", "bvid", "aid") ||
                request.url.queryParameter("cid") != subject.first || request.url.queryParameter("aid") != subject.second)
                rejectRequest(request)
            // Exact original API returns ResponseBody; its real metadata protocol
            // invokes parsePbpProgressData on top-level step_sec/events.default.
            val body = """{"code":0,"step_sec":5,"events":{"default":[0,1,3,1]}}"""
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory PBP")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        suspend fun load(bvid: String, aid: Long, cid: Long, audioLang: String? = null) {
            ui { owner.playback.loadVideo(bvid, aid, cid = cid, audioLang = audioLang, autoPlay = false) }
            awaitLoaded(bvid)
            ui { actor.drain(); actor.loaded(); player.setPaused(true); actor.drain() }
            withTimeout(8_000) { while (runtimeBinding.generation(accepted()) == null) delay(5) }
            withTimeout(8_000) {
                while (state().relatedLoadState != VideoPlaybackUiState.RelatedLoadState.READY) delay(5)
            }
        }
        suspend fun awaitLoaded(bvid: String) = withTimeout(8_000) {
            while ((owner.playback.uiState.value as? VideoPlaybackUiState.Success)?.info?.bvid != bvid ||
                owner.native.current()?.request?.bvid != bvid) delay(5)
        }
        /** Deliver actual log-message and end-file ABI input to the existing memory MPV actor.
         * The real registered section listener and original recovery controller initiate retry;
         * this helper never calls VM.retry, replaces a VM state or fabricates a source ticket. */
        suspend fun failExpiredNetwork(): PlayerFailure = uiResult {
            check(refreshExpiredUrls)
            firstUrlsExpired = true
            fun text(value: String): Memory = value.toByteArray(Charsets.UTF_8).let { bytes ->
                Memory(bytes.size + 1L).apply { write(0, bytes, 0, bytes.size); setByte(bytes.size.toLong(), 0) }
            }
            text("ffmpeg").use { prefix -> text("error").use { level -> text("connection failed").use { message ->
                Memory(32).use { data ->
                    data.clear(); data.setPointer(0, prefix); data.setPointer(8, level); data.setPointer(16, message)
                    data.setInt(24, 20)
                    PremiumRecoveryActor::class.java.getDeclaredMethod("event", Integer.TYPE, Pointer::class.java)
                        .apply { isAccessible = true }.invoke(actor, 2, data)
                }
            } } }
            actor.fail(-13).also { failure ->
                assertSame(failure, player.state.value.failure)
                assertEquals(PlayerFailureKind.NETWORK, failure.kind)
                assertEquals(-13, failure.nativeCode)
            }
        }
        suspend fun holdCdnSwitch(): HeldDispatch = uiResult {
            assertTrue(owner.environment.plugins.isPlaybackDispatchCurrent(accepted()))
            assertTrue(state().cdnCount > 1)
            dispatcher.arm(); owner.playback.switchCdnTo(1); dispatcher.take()
        }
        fun release(held: HeldDispatch) = dispatcher.release(held)
        fun assertOutsideAdmission() {
            fun lock(type: Class<*>, instance: Any) = type.getDeclaredField("lock").apply { isAccessible = true }.get(instance)
            assertFalse(Thread.holdsLock(lock(DesktopSessionStore::class.java, sessions)))
            assertFalse(Thread.holdsLock(lock(DesktopOriginalVideoRootGate::class.java, gate)))
            assertFalse(Thread.holdsLock(lock(MpvPlayer::class.java, player)))
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        suspend fun <T> uiResult(action: () -> T): T = withContext(Dispatchers.Swing) { action() }
        fun contribution() = checkNotNull(owner.playback.desktopSponsorPresentation.value.contribution)
        suspend fun awaitSkip(expected: DesktopOriginalVideoAcceptedPublication): DesktopOriginalSponsorSkipPresentation = withTimeout(8_000) {
            while (owner.playback.desktopSponsorPresentation.value.skip?.let { it.source === expected && it.state.visible } != true) delay(5)
            checkNotNull(owner.playback.desktopSponsorPresentation.value.skip)
        }
        fun assertSkip(skip: DesktopOriginalSponsorSkipPresentation, expected: DesktopOriginalVideoAcceptedPublication) {
            assertSame(expected, skip.source)
            assertEquals(expected.request.bvid, state().info.bvid)
            assertEquals(expected.request.cid, state().info.cid)
            assertTrue(skip.state.visible)
            val segment = assertNotNull(skip.segment)
            assertEquals(SEGMENT_ID, segment.UUID)
            assertEquals(segment.UUID, skip.state.segmentId)
            assertEquals(segment.endTimeMs, skip.state.skipToMs)
            assertEquals(42_500L, skip.state.skipToMs)
            @Suppress("UNCHECKED_CAST")
            val original = owner.playback.javaClass.getDeclaredField("_sponsorSkipUiState")
                .apply { isAccessible = true }.get(owner.playback) as StateFlow<com.android.purebilibili.feature.video.viewmodel.SponsorSkipUiState>
            assertEquals(original.value, skip.state)
        }
        suspend fun submitHeld(code: Int, body: String): PendingSubmission {
            assertEquals(server.baseUrl, runtime.originalSponsorBlock.getCommunityServerBaseUrl())
            val source = accepted(); val original = owner.captureLoadState().currentRequest
            assertTrue(owner.environment.plugins.isPlaybackDispatchCurrent(source))
            assertEquals(SponsorContributionPhase.REVIEW, contribution().state.phase)
            val reply = server.arm(code, body)
            // Read actual entry children around the original synchronous VM launch;
            // the held response keeps that existing IO child alive. No Job is created
            // or substituted here. Existing captureInvocation proves its real request.
            val job = uiResult {
                val before = gate.scope.coroutineContext.job.children.toSet()
                owner.playback.submitSponsorContribution()
                val added = gate.scope.coroutineContext.job.children.filter { it !in before }.toList()
                assertEquals(1, added.size, "The original submit invocation must be the newly launched entry child")
                added.single()
            }
            withTimeout(8_000) { reply.entered.await() }
            val capture = captures.single { it.job === job }
            assertSame(original, capture.state.currentRequest)
            assertSame(source, accepted())
            assertSame(source, contribution().source)
            return PendingSubmission(reply, job)
        }
        fun assertSubmission(request: SponsorRequest, expected: DesktopOriginalVideoAcceptedPublication, category: String) {
            assertEquals("POST", request.method); assertEquals("${server.basePath}/skipSegments", request.path)
            assertTrue(request.query.isEmpty())
            val body = Json.parseToJsonElement(request.body).jsonObject
            assertEquals(expected.request.bvid, body.getValue("videoID").jsonPrimitive.content)
            assertEquals(expected.request.cid.toString(), body.getValue("cid").jsonPrimitive.content)
            assertEquals(USER_ID, body.getValue("userID").jsonPrimitive.content)
            assertEquals("BiliPai", body.getValue("userAgent").jsonPrimitive.content)
            assertEquals(120f, body.getValue("videoDuration").jsonPrimitive.float)
            val segment = body.getValue("segments").jsonArray.single().jsonObject
            assertEquals(category, segment.getValue("category").jsonPrimitive.content)
            assertEquals(SponsorActionType.FULL, segment.getValue("actionType").jsonPrimitive.content)
            assertEquals(listOf(0f, 0f), segment.getValue("segment").jsonArray.map { it.jsonPrimitive.float })
        }
        suspend fun close() {
            var first: Throwable? = null
            suspend fun clean(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val earlier = first
                    if (earlier == null) first = failure else if (earlier !== failure) earlier.addSuppressed(failure)
                }
            }
            clean { server.releaseAll(); tagsRelease.countDown(); dispatcher.drainOnClose() }
            clean { slot.closeAndJoin() }; clean { parentJob.cancelAndJoin() }
            clean { if (::runtimeBinding.isInitialized) runtimeBinding.close() }
            clean { if (::actor.isInitialized) actor.close() }
            clean { if (::cache.isInitialized) cache.close() }
            clean { subtitles.close() }; clean { player.close() }
            clean { transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll() }
            clean { runtime.setEnabled("sponsor_block", false) }
            clean { server.close() }
            clean { assertTrue(failures.isEmpty(), failures.toString()) }
            clean { assertTrue(unexpectedRequests.isEmpty(), unexpectedRequests.toString()) }
            clean { deleteCreatedDirectory(directory, "sponsor-original-owner-") }
            first?.let { throw it }
        }
        suspend fun useSuspending(block: suspend (Fixture) -> Unit) {
            var primary: Throwable? = null
            val previousSelector = ProxySelector.getDefault()
            val guard = checkNotNull(loopbackSelector)
            ProxySelector.setDefault(guard) // SAME suite instance retained by the fixed original client
            try { initialize(); withTimeout(30_000) { block(this@Fixture) } }
            catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                try {
                    try { withContext(NonCancellable) { close() } }
                    catch (cleanup: Throwable) {
                        val failure = primary
                        if (failure == null) throw cleanup
                        if (failure !== cleanup) failure.addSuppressed(cleanup)
                    }
                } finally {
                    // All entry jobs/server handlers have drained before restoring
                    // this temporary JVM default. The guard owns no server or port state.
                    if (ProxySelector.getDefault() === guard) ProxySelector.setDefault(previousSelector)
                }
            }
        }
    }

    private data class SponsorRequest(val method: String, val path: String, val query: Map<String, String>, val body: String)
    private class Reply(val code: Int, val body: String) {
        val entered = CompletableDeferred<SponsorRequest>()
        val releaseLatch = CountDownLatch(1)
        fun release() { releaseLatch.countDown() }
    }
    private data class PendingSubmission(val reply: Reply, val job: Job)

    /** The unmodified original Repository uses its real public OkHttp client.
     * Only its original user-configurable server URL points at this ephemeral
     * loopback service. The service never redirects or forwards any request. */
    private class LoopbackSponsorServer : AutoCloseable {
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val responses = LinkedBlockingQueue<Reply>()
        private val allReplies = CopyOnWriteArrayList<Reply>()
        val submissions = CopyOnWriteArrayList<SponsorRequest>()
        val votes = CopyOnWriteArrayList<SponsorRequest>()
        val gets = CopyOnWriteArrayList<SponsorRequest>()
        private val executor = Executors.newCachedThreadPool { task -> Thread(task, "sponsor-fixture-loopback").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        // A unique original cache key survives OS port reuse between test cases.
        val basePath = "/api/${UUID.randomUUID()}"
        val baseUrl = "http://127.0.0.1:${server.address.port}$basePath"
        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                try {
                    check(exchange.remoteAddress.address.isLoopbackAddress)
                    check(exchange.requestHeaders.getFirst("Host") == "127.0.0.1:${server.address.port}")
                    check(exchange.requestHeaders.getFirst("Cookie") == null && exchange.requestHeaders.getFirst("Authorization") == null)
                    val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { pair ->
                        val parts = pair.split('=', limit = 2)
                        URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8)
                    }
                    val request = SponsorRequest(exchange.requestMethod, exchange.requestURI.path, query,
                        exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) })
                    var code = 200; var body = ""
                    when {
                        request.method == "GET" && request.path == "$basePath/skipSegments" -> {
                            check(request.query["videoID"] in listOf(A, B))
                            check(request.query["cid"] in listOf("104", "105"))
                            gets.add(request); body = SEGMENT_JSON
                        }
                        request.method == "POST" && request.path == "$basePath/voteOnSponsorTime" -> {
                            // v0.4.6: the vote is a JSON body {UUID,userID,type}, never a query string.
                            check(request.query.isEmpty())
                            val vote = Json.parseToJsonElement(request.body).jsonObject
                            check(vote.getValue("UUID").jsonPrimitive.content == SEGMENT_ID)
                            check(vote.getValue("userID").jsonPrimitive.content == USER_ID)
                            check(vote.getValue("type").jsonPrimitive.int == 0)
                            votes.add(request)
                        }
                        request.method == "POST" && request.path == "$basePath/skipSegments" -> {
                            submissions.add(request)
                            val response = responses.poll() ?: error("No original submission reply was armed")
                            response.entered.complete(request)
                            check(response.releaseLatch.await(15, TimeUnit.SECONDS)) { "Held original submission was not released" }
                            code = response.code; body = response.body
                        }
                        else -> error("Unexpected loopback Sponsor request $request")
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    // A retired original call can close its socket before the held response.
                    // Sending then may fail; that is not a different HTTP/network request.
                    try { exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
                    catch (_: IOException) { }
                } catch (failure: Throwable) { failures.add(failure) }
                finally { exchange.close() }
            }
            server.start()
        }
        fun arm(code: Int, body: String): Reply = Reply(code, body).also { allReplies.add(it); responses.add(it) }
        fun releaseAll() { allReplies.forEach { it.release() } }
        override fun close() {
            releaseAll(); server.stop(0); executor.shutdownNow()
            check(executor.awaitTermination(8, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty(), failures.toString())
        }
    }

    companion object {
        private const val USER_ID = "SponsorFixtureOnly0123456789abcdef"
        private const val SEGMENT_ID = "sponsor-fixture-segment"
        private const val SEGMENT_JSON = """[{"segment":[20.0,42.5],"UUID":"sponsor-fixture-segment","category":"sponsor","actionType":"skip","videoDuration":120.0,"votes":1}]"""
        private const val A = "BV1GJ411x7h7"
        private const val B = "BV17x411w7KC"
        private fun video(bvid: String) = "https://media.invalid/$bvid-video.m4s?synthetic=1"
        private fun backupVideo(bvid: String) = "https://backup.invalid/$bvid-video.m4s?synthetic=1"
        private fun audio(bvid: String) = "https://media.invalid/$bvid-audio.m4s?synthetic=1"
        private fun backupAudio(bvid: String) = "https://backup.invalid/$bvid-audio.m4s?synthetic=1"
        private fun viewJson(bvid: String): String {
            check(bvid in listOf(A, B)); val aid = if (bvid == A) 101 else 102; val cid = if (bvid == A) 104 else 105
            return """{"code":0,"data":{"bvid":"$bvid","aid":$aid,"cid":$cid,"title":"Fixture $bvid","pic":"https://media.invalid/cover.jpg","duration":60,"owner":{"mid":51,"name":"Fixture","face":""},"pages":[{"cid":$cid,"page":1,"part":"Fixture","duration":60}]}}"""
        }
        private fun playJson(bvid: String): String {
            fun videoTrack(id: Int, url: String) = """{"id":$id,"baseUrl":"$url","backupUrl":[],"bandwidth":100000,"mime_type":"video/mp4","codecs":"avc1.640028","width":1280,"height":720,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-31","index_range":"32-63"}}"""
            val tracks = listOf(videoTrack(64, video(bvid)), videoTrack(32, "${video(bvid)}&quality=32")).joinToString(",")
            return """{"code":0,"data":{"quality":64,"timelength":60000,"accept_quality":[64,32],"accept_description":["720P","480P"],"dash":{"duration":60,"video":[$tracks],"audio":[{"id":30280,"baseUrl":"${audio(bvid)}","backupUrl":[],"bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}]}}}"""
        }
        private fun retryVideo(revision: Int) = "https://media.invalid/$A-video.m4s?retry_revision=$revision"
        private fun retryAudio(revision: Int) = "https://media.invalid/$A-audio.m4s?retry_revision=$revision"
        private fun retryPlayJson(revision: Int): String = """{"code":0,"data":{"quality":64,"timelength":60000,"cur_language":"en","accept_quality":[64],"accept_description":["720P"],"dash":{"duration":60,"video":[{"id":64,"baseUrl":"${retryVideo(revision)}","backupUrl":[],"bandwidth":100000,"mime_type":"video/mp4","codecs":"avc1.640028","width":1280,"height":720,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-31","index_range":"32-63"}}],"audio":[{"id":30280,"baseUrl":"${retryAudio(revision)}","backupUrl":[],"bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}]}}}"""
        private fun deleteCreatedDirectory(directory: Path, prefix: String) {
            val absolute = directory.toAbsolutePath().normalize(); val real = directory.toRealPath()
            val temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
            check(absolute.parent == temp && real.parent == temp.toRealPath() && absolute != temp)
            check(absolute.fileName.toString().startsWith(prefix))
            fun validate(path: Path, attrs: BasicFileAttributes) {
                val value = path.toAbsolutePath().normalize()
                check(value.startsWith(absolute) && !attrs.isSymbolicLink && !attrs.isOther && path.toRealPath() == value)
                if (System.getProperty("os.name").startsWith("Windows"))
                    check((Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int) and 0x400 == 0)
            }
            val nodes = mutableListOf<Path>()
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult { validate(dir, attrs); nodes.add(dir); return FileVisitResult.CONTINUE }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { validate(file, attrs); nodes.add(file); return FileVisitResult.CONTINUE }
            })
            for (path in nodes.sortedByDescending { it.nameCount }) {
                check(directory.toRealPath() == real); validate(path, Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)); Files.delete(path)
            }
        }
    }
}
