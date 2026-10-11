package com.bilipai.desktop.ui

import com.android.purebilibili.core.plugin.PluginManager
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionState
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*
import com.bilipai.desktop.plugins.*
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
import java.lang.reflect.Proxy
import java.net.Proxy as NetworkProxy
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

/** Actual complete original VM, Slot/Gate/Assembly/RequestFactory/RootMediaFactory,
 * NativeOwner, RuntimeBinding and PluginBridge. Only HTTP responses, external queue
 * and platform effects are fixture inputs. The existing memory ABI executes MPV
 * commands/ACKs without a native DLL, rendering thread, HWND or physical decoder.
 * One real Runtime is retained for this class, matching PluginManager's process
 * lifetime. All optional providers are disabled in its temporary Store before
 * registration. No fake Runtime readiness or manufactured accepted source exists. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesktopOriginalVideoCdnSelectionAssemblyTest {
    private lateinit var runtimeDirectory: Path
    private lateinit var store: DesktopPluginStore
    private lateinit var runtime: DesktopPluginRuntime

    @BeforeAll fun createRuntime(): Unit = runBlocking {
        runtimeDirectory = Files.createTempDirectory("cdn-actual-runtime-")
        store = DesktopPluginStore(runtimeDirectory.resolve("settings"))
        val ids = listOf("sponsor_block", "danmaku_enhance", "eye_protection", "bilipai_feed_filter",
            "home_feed_anonymizer", "adfilter", "subscription_feed", "today_watch", "cdn_region", "dlna_cast", "google_cast")
        store.update("plugin_prefs", ids.associate { "plugin_enabled_$it" to JsonPrimitive(false) })
        runtime = withContext(Dispatchers.Swing) { DesktopPluginRuntime(store) }
        withTimeout(8_000) { for (id in ids) PluginManager.awaitPluginReady(id) }
        assertTrue(runtime.plugins.value.none { it.enabled })
    }

    @AfterAll fun closeRuntime(): Unit = runBlocking {
        if (::runtime.isInitialized) runtime.shutdownForRestore()
        if (::runtimeDirectory.isInitialized) deleteCreatedDirectory(runtimeDirectory, "cdn-actual-runtime-")
    }

    @Test fun delayedOriginalCdnJobCannotRetargetClickedAUrlsToLaterActualB(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(A, 101, 104)
            val clicked = f.accepted(); val original = f.owner.captureLoadState()
            val held = f.holdCdnSwitch()
            assertTrue(held.job.isActive)
            assertFalse(f.captures.any { it.job === held.job })
            f.load(B, 102, 105)
            val later = f.accepted(); val state = f.state()
            assertNotSame(original.currentRequest, f.owner.captureLoadState().currentRequest)
            assertNotSame(clicked, later)
            f.release(held)
            withTimeout(8_000) { held.job.join() }
            assertTrue(held.job.isCancelled)
            val actualCapture = f.captures.single { it.job === held.job }
            assertSame(f.owner.captureLoadState().currentRequest, actualCapture.state.currentRequest)
            assertEquals(B, actualCapture.state.currentBvid)
            assertSame(later, f.accepted())
            assertEquals(state.playUrl, f.state().playUrl)
            assertEquals(B, f.state().info.bvid)
            assertFalse(f.preparations.any { it.source.videoUrl == backupVideo(A) })
            f.ui { f.actor.drain() }
            assertFalse(f.actor.native.loads.any { it.getOrNull(1) == backupVideo(A) })
            assertFalse(f.toasts.any { it.contains("已切换到线路2") })
        }
    }

    @Test fun newOriginalRequestDuringRealPreparationRejectsOldFinalAccept(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(A, 101, 104)
            val before = f.accepted()
            var observed = false
            f.beforePreparation = { preparation ->
                if (preparation.source.videoUrl == backupVideo(A)) {
                    observed = true
                    f.assertOutsideAdmission()
                    assertSame(before, f.accepted())
                    f.owner.playback.loadVideo(B, 102, cid = 105, autoPlay = false)
                    assertEquals(B, f.owner.captureLoadState().currentRequest?.bvid)
                }
            }
            val held = f.holdCdnSwitch(); f.release(held)
            withTimeout(8_000) { held.job.join() }
            assertTrue(observed); assertTrue(held.job.isCancelled)
            f.awaitLoaded(B)
            assertEquals(B, f.accepted().request.bvid)
            assertFalse(f.publications.any { it.nativeSource.source.videoUrl == backupVideo(A) })
            f.ui { f.actor.drain(); f.actor.loaded() }
            assertFalse(f.actor.native.loads.any { it.getOrNull(1) == backupVideo(A) })
            assertFalse(f.toasts.any { it.contains("已切换到线路2") })
        }
    }

    @Test fun ownSuccessorKeepsLatestRealMetadataBeforeItsAsyncRuntimeGenerationIsReady(): Unit = runBlocking {
        Fixture(holdTags = true).useSuspending { f ->
            f.load(A, 101, 104)
            val before = f.accepted(); val request = f.owner.captureLoadState()
            assertTrue(f.state().videoTags.isEmpty())
            withTimeout(8_000) { f.tagsEntered.await() }
            val held = f.holdCdnSwitch()
            f.tagsRelease.countDown()
            withTimeout(8_000) { while (f.state().videoTags.singleOrNull()?.tag_name != "Fresh metadata") delay(5) }
            val latest = f.state()
            assertSame(before, f.accepted())
            assertSame(request.currentRequest, f.owner.captureLoadState().currentRequest)
            f.release(held)
            withTimeout(8_000) { held.job.join() }
            assertFalse(held.job.isCancelled)
            val child = f.accepted()
            assertNotSame(before, child)
            assertEquals(backupVideo(A), child.nativeSource.source.videoUrl)
            assertEquals(backupAudio(A), child.nativeSource.source.audioUrl)
            assertEquals(1, f.state().currentCdnIndex)
            assertEquals(latest.videoTags, f.state().videoTags)
            assertEquals(latest.related, f.state().related)
            assertEquals(latest.relatedLoadState, f.state().relatedLoadState)
            assertSame(request.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(request.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            val emission = f.cdnEmissions.first { it.source === child }
            assertNull(emission.runtimeGeneration) // actual public UI publication precedes Main worker readiness
            assertEquals(1, emission.index)
            withTimeout(8_000) { while (f.runtimeBinding.generation(child) == null) delay(5) }
            assertTrue(f.owner.environment.plugins.isPlaybackDispatchCurrent(child))
            f.ui { f.actor.drain(); f.actor.loaded() }
            assertTrue(f.player.ownsSourceSnapshot(child.nativeSource))
            assertEquals(27.75, child.nativeSource.source.startPositionSeconds)
            assertTrue(child.nativeSource.source.startPaused)
            @Suppress("UNCHECKED_CAST") val options = f.actor.native.loads.last()[4] as Map<String, String>
            assertEquals("27.75", options["start"]); assertEquals("yes", options["pause"])
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
        private var armed = false
        private var held: HeldDispatch? = null
        override fun isDispatchNeeded(context: CoroutineContext) = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val intercepted = synchronized(lock) {
                if (armed) { check(held == null); armed = false; held = HeldDispatch(context, block); true } else false
            }
            if (!intercepted) EventQueue.invokeLater(block)
        }
        fun arm() = synchronized(lock) { check(!armed && held == null); armed = true }
        fun take(): HeldDispatch = synchronized(lock) { checkNotNull(held) }
        fun release(expected: HeldDispatch) {
            synchronized(lock) { check(held === expected); held = null }
            EventQueue.invokeLater(expected.runnable)
        }
        fun drainOnClose() { val pending = synchronized(lock) { armed = false; held.also { held = null } }; pending?.let { EventQueue.invokeLater(it.runnable) } }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private inner class Fixture(private val holdTags: Boolean = false) {
        val directory = Files.createTempDirectory("cdn-original-owner-")
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
        val toasts = CopyOnWriteArrayList<String>()
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
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-visitor", "buvid4" to "synthetic-visitor4"), sessions.generation)
            epoch = repository.sessionEpoch
            store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(false), "video_codec" to JsonPrimitive("avc1"),
                "video_second_codec" to JsonPrimitive("avc1"), "wifi_quality" to JsonPrimitive(64)))
            store.update("settings", mapOf("video_codec_preference" to JsonPrimitive("avc1"), "video_second_codec_preference" to JsonPrimitive("avc1")))
            store.update("video_note_settings", mapOf("video_note_enabled" to JsonPrimitive(false)))
            transport = repository.httpClient.newBuilder().proxy(NetworkProxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    check(request.url.isHttps && request.url.port == 443)
                    check(request.url.host in setOf("api.bilibili.com", "www.bilibili.com"))
                    val bvid = request.url.queryParameter("bvid") ?: A
                    val body = when (request.url.encodedPath) {
                        "/" -> "memory bootstrap"
                        "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                        "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":false,"vip":{"status":0},"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                        "/x/web-interface/view" -> viewJson(bvid)
                        "/x/player/wbi/playurl", "/x/player/playurl" -> playJson(bvid)
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
                        override fun logQualityChange(videoId: String, fromQuality: Int, toQuality: Int) = error("No quality change")
                    }, DesktopOriginalVideoOwnerCrash { _, _, message -> error("Unexpected original load error $message") },
                    unused(), MutableStateFlow(false))
                val media = DesktopOriginalVideoRootMediaFactory(repository, cache, gate, { owner }, { preparation ->
                    preparations.add(preparation); beforePreparation?.invoke(preparation)
                }, {})
                val requests = DesktopOriginalVideoOwnerRequestFactory(repository, DesktopLoginRepository(repository), epoch,
                    gate.scope, gate::owns, gate::commitEntry, { PlayerPreferences() }, capabilities,
                    { true }, { false }, { false }, subtitles, privacy,
                    { _, _ -> error("Preseeded visitor authorization must not retire") }, media::request)
                runtimeBinding = DesktopOriginalVideoRootRuntimeBinding(repository, runtime, gate) { owner.native }
                DesktopOriginalVideoOwnerAssembly.create(context, settings, repository, epoch, gate.scope,
                    gate::owns, gate::commitEntry, player, DesktopRepositoryPlaybackPublication(repository), { false },
                    runtimeBinding.sourceVersions, { expected -> publications.add(expected); runtimeBinding.accepted(expected) },
                    media::admission, captureInvocation = { state, native ->
                        captures.add(Capture(state, currentCoroutineContext().job)); requests.capture(state, native)
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
            // The real public StateFlow emission runs immediately in its emitter,
            // before the separate Main Runtime worker can consume accepted events.
            gate.scope.launch(Dispatchers.Unconfined) {
                owner.playback.uiState.collect { state ->
                    if (state is VideoPlaybackUiState.Success && state.currentCdnIndex == 1) {
                        owner.native.current()?.let { child -> cdnEmissions.add(CdnEmission(child, state.currentCdnIndex, runtimeBinding.generation(child))) }
                    }
                }
            }
            gate.scope.launch { owner.playback.toastEvent.collect { toasts.add(it.toString()) } }
        }
        fun state() = owner.playback.uiState.value as VideoPlaybackUiState.Success
        fun accepted() = checkNotNull(owner.native.current())
        suspend fun load(bvid: String, aid: Long, cid: Long) {
            ui { owner.playback.loadVideo(bvid, aid, cid = cid, autoPlay = false) }
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
        suspend fun close() {
            tagsRelease.countDown(); dispatcher.drainOnClose()
            slot.closeAndJoin(); parentJob.cancelAndJoin()
            if (::runtimeBinding.isInitialized) runtimeBinding.close()
            if (::actor.isInitialized) actor.close()
            if (::cache.isInitialized) cache.close()
            subtitles.close(); player.close()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            assertTrue(failures.isEmpty(), failures.toString())
            deleteCreatedDirectory(directory, "cdn-original-owner-")
        }
        suspend fun useSuspending(block: suspend (Fixture) -> Unit) {
            try { initialize(); withTimeout(30_000) { block(this@Fixture) } }
            finally { withContext(NonCancellable) { close() } }
        }
    }

    companion object {
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
        private fun playJson(bvid: String): String = """{"code":0,"data":{"quality":64,"timelength":60000,"accept_quality":[64],"accept_description":["720P"],"dash":{"duration":60,"video":[{"id":64,"baseUrl":"${video(bvid)}","backupUrl":["${backupVideo(bvid)}"],"bandwidth":100000,"mime_type":"video/mp4","codecs":"avc1.640028","width":1280,"height":720,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-31","index_range":"32-63"}}],"audio":[{"id":30280,"baseUrl":"${audio(bvid)}","backupUrl":["${backupAudio(bvid)}"],"bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}]}}}"""
        private fun deleteCreatedDirectory(directory: Path, prefix: String) {
            val absolute = directory.toAbsolutePath().normalize(); val real = directory.toRealPath()
            val temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
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
