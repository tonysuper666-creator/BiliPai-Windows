package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.android.purebilibili.feature.download.DownloadStatus
import com.android.purebilibili.feature.download.DownloadTask as OriginalTask
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.download.DesktopDownloadManager
import com.bilipai.desktop.download.DownloadMuxer
import com.bilipai.desktop.download.DownloadTask as QueuedTask
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.EventQueue
import java.lang.reflect.Proxy
import java.net.Proxy as NetworkProxy
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/** Actual relocated download component, real Root slot/gate/Assembly, full original
 * PGC VM/Portrait, real Binding/temporary Store, NativeOwner plus existing memory
 * ABI actor, and real DownloadManager/disk/independent Job. No physical Window,
 * Root Entry initialize, PluginRuntime, native initialization, decoder or SDK.
 * HTTP and muxing are CPU fixtures; Load/ABI acceptance is not hardware evidence. */
class DesktopBangumiCourseDownloadOwnershipTest {
    @Test fun originalVmCapturesOnEdtAndQueuesCompleteTaskThroughRealIoAndLaunchCaller(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load()
            val accepted = f.accepted()
            val load = f.owner.captureLoadState()
            f.ui { f.vm.downloadCurrentEpisode(f.settings) }
            val click = f.awaitClick()
            f.awaitQueue()
            val evidence = f.trace.ingress.single { it.ordinal == 2 }
            assertTrue(click.onEdt)
            assertSame(click.job, evidence.bindingCaller)
            assertNotSame(click.job, evidence.ioJob)
            assertNotSame(f.mediaLoadCaller.get(), click.job)
            assertTrue(evidence.ioThread)
            assertFalse(evidence.storeHeldOutside)
            assertFalse(evidence.entryHeldOutside)
            assertFalse(evidence.nativeHeldOutside)
            assertTrue(evidence.directoryReady)
            assertFalse(evidence.statePersistedBeforeManagerCommit)
            val queued = checkNotNull(evidence.queued)
            assertEquals(click.task.copy(status = DownloadStatus.QUEUED, errorMessage = null), queued.item)
            assertSame(accepted.nativeSource.source.authorizationReceipt, queued.authorizationReceipt)
            assertEquals(accepted.nativeSource.source.cookieHeader, queued.cookieHeader)
            assertEquals(accepted.nativeSource.source.streamHeaders, queued.streamHeaders)
            assertEquals("https://www.bilibili.com/cheese/play/ep41", queued.referer)
            assertEquals(19L, queued.seasonId); assertEquals(41L, queued.episodeId); assertTrue(queued.isCourse)
            assertEquals(f.destination.toAbsolutePath().normalize().toString(), queued.destinationRoot)
            assertEquals("Synthetic course", queued.item.title); assertEquals("One", queued.item.episodeLabel)
            assertEquals("course:19", queued.item.groupKey); assertEquals("Synthetic course", queued.item.groupTitle)
            assertEquals(0, queued.item.episodeSortIndex); assertEquals(2, queued.item.episodeCount)
            assertEquals("https://media.invalid/cover.jpg", queued.item.cover)
            assertEquals("Fixture UP", queued.item.ownerName); assertEquals("https://media.invalid/face.jpg", queued.item.ownerFace)
            assertEquals(60, queued.item.duration); assertEquals(120, queued.item.quality); assertEquals("4K", queued.item.qualityDesc)
            assertEquals(click.task.videoUrl, queued.item.videoUrl); assertEquals(click.task.audioUrl, queued.item.audioUrl)
            assertSame(accepted, f.accepted())
            assertSame(load.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(load.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            assertTrue(Files.isRegularFile(f.stateFile)); assertEquals(click.task.id, f.persisted().single().id)
            assertTrue(f.trace.workerJobs.isNotEmpty())
            assertTrue(f.trace.workerJobs.all { it !== click.job && it !== f.gate.scope.coroutineContext[Job] })
            f.releaseMedia.countDown(); f.awaitCompleted(); click.job.join()
            assertFalse(click.job.isCancelled)
            withTimeout(8_000) { while ("已加入课程下载" !in f.toasts) delay(5) }
        }
    }

    @Test fun frozenClickRejectsActualRequestTokenAcceptedObjectSelectedAccountAndRootRetirement(): Unit = runBlocking {
        for (change in listOf("request", "episode", "accepted", "account", "root")) {
            Fixture().useSuspending { f ->
                f.load()
                val task = f.originalTask()
                val op = f.uiResult { f.capture(task) }
                val before = f.accepted()
                val beforeLoad = f.owner.captureLoadState()
                f.ui {
                    when (change) {
                        "request" -> {
                            val token = f.owner.playback.beginDesktopPortraitLoad(
                                PlaybackRequest.create(task.bvid, task.aid, task.cid), f.native)
                            assertTrue(token > beforeLoad.currentLoadRequestToken)
                        }
                        "episode" -> {
                            val episode = (f.vm.uiState.value as BangumiPlayerState.Success).seasonDetail.episodes!![1]
                            f.owner.playback.beginDesktopPortraitLoad(
                                PlaybackRequest.create(episode.bvid, episode.aid, episode.cid), f.native)
                            assertEquals(episode.cid, f.owner.captureLoadState().currentRequest!!.cid)
                        }
                        "accepted" -> {
                            val state = f.vm.uiState.value as BangumiPlayerState.Success
                            val plan = desktopOriginalBangumiNativePlan(state.seasonDetail, state.currentEpisode,
                                state.cachedPlayData!!, state.playUrl!!, state.audioUrl, null,
                                before.nativeSource.source.referer, null, 0L, false)
                            val media = f.owner.native.acceptedMedia({ f.media.accepted(it, plan) })
                            media.withPlaybackIntent(0L, false) {
                                media.accept(media.prepareLegacyDash(task.videoUrl, task.audioUrl.takeIf(String::isNotBlank), emptyMap()))
                            }
                            assertNotSame(before, f.accepted()); assertEquals(before.sourceVersion, f.accepted().sourceVersion)
                        }
                        "account" -> {
                            val epoch = f.repository.sessionEpoch
                            val revision = f.repository.playbackAuthorizationRevision.value
                            assertTrue(f.sessions.setPlaybackAccountMid(42, epoch, f.gate::owns))
                            assertTrue(f.sessions.setPlaybackAccountMid(null, epoch, f.gate::owns))
                            assertEquals(epoch, f.repository.sessionEpoch)
                            assertTrue(f.repository.playbackAuthorizationRevision.value > revision)
                        }
                        "root" -> f.gate.retire()
                    }
                }
                if (change == "root") f.ui { assertFailsWith<CancellationException> { f.native.launch(block = { op() }) } }
                else {
                    val failure = AtomicReference<Throwable?>()
                    val job = f.uiResult { f.native.launch(block = {
                        try { op() } catch (error: Throwable) { failure.set(error); throw error }
                    }) }
                    withTimeout(8_000) { job.join() }
                    assertIs<CancellationException>(failure.get())
                }
                assertTrue(f.manager.tasks.value.isEmpty(), change)
                assertTrue(f.trace.ingress.isEmpty(), change)
                f.assertUnqueuedPersistence(change)
                if (change == "request" || change == "episode") assertSame(before, f.owner.native.current())
            }
        }
    }

    @Test fun cancellingCapturedActualLaunchBeforeFinalQueueAdmissionCannotPersistOrEnqueue(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load()
            f.trace.beforeFinal = { it.bindingCaller.cancel("cancel real download launch before admission") }
            f.ui { f.vm.downloadCurrentEpisode(f.settings) }
            val click = f.awaitClick()
            withTimeout(8_000) { click.job.join() }
            assertTrue(click.job.isCancelled)
            val evidence = f.trace.ingress.single { it.ordinal == 2 }
            assertSame(click.job, evidence.bindingCaller); assertNotSame(click.job, evidence.ioJob)
            assertTrue(evidence.directoryReady) // real marker preparation may precede rejection
            assertNull(evidence.queued); assertTrue(f.manager.tasks.value.isEmpty())
            f.assertUnqueuedPersistence(); assertTrue(f.trace.workerJobs.isEmpty())
            assertTrue(f.native.ownsPlaybackSource())
        }
    }

    @Test fun admittedQueuePersistsAndSchedulesIndependentlyAfterCallerAndRealRootGateRetire(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load()
            f.trace.afterFinal = { it.bindingCaller.cancel("cancel only after real queue acceptance") }
            f.ui { f.vm.downloadCurrentEpisode(f.settings) }
            val click = f.awaitClick()
            f.awaitQueue()
            withTimeout(8_000) { click.job.join() }
            assertTrue(click.job.isCancelled)
            val evidence = f.trace.ingress.single { it.ordinal == 2 }
            assertNotNull(evidence.queued); assertFalse(evidence.statePersistedBeforeManagerCommit)
            assertTrue(Files.isRegularFile(f.stateFile)); assertEquals(click.task.id, f.persisted().single().id)
            assertTrue(f.trace.workerJobs.isNotEmpty())
            val workers = f.trace.workerJobs.toList()
            assertTrue(workers.all { it !== click.job && it.isActive })
            f.ui { f.gate.retire() }
            assertFalse(f.gate.owns())
            assertTrue(f.repository.isPlaybackReceiptCurrent(evidence.queued!!.authorizationReceipt!!))
            assertTrue(workers.all { it.isActive })
            f.releaseMedia.countDown(); f.awaitCompleted()
            assertEquals(DownloadStatus.COMPLETED, f.persisted().single().status)
            assertTrue(Files.isRegularFile(Path.of(f.manager.tasks.value.single().outputFile!!)))
        }
    }

    @Test fun originalDuplicateAndServerRightsPolicySurviveTheAsynchronousEntry(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load()
            f.ui { f.vm.downloadCurrentEpisode(f.settings) }
            f.awaitClick(); f.awaitQueue()
            f.ui { f.vm.downloadCurrentEpisode(f.settings) }
            withTimeout(8_000) { while (f.clicks.size < 2) delay(5); f.clicks.last().job.join() }
            assertEquals(1, f.manager.tasks.value.size)
            withTimeout(8_000) { while ("该集已在下载列表" !in f.toasts) delay(5) }
            f.releaseMedia.countDown(); f.awaitCompleted()
        }
        for ((seasonType, right) in listOf(10 to 0, 1 to null)) {
            Fixture(seasonType, right).useSuspending { f ->
                f.load()
                f.ui { f.vm.downloadCurrentEpisode(f.settings) }
                withTimeout(8_000) { while (f.toasts.none { "权限" in it }) delay(5) }
                assertTrue(f.manager.tasks.value.isEmpty()); assertTrue(f.trace.ingress.isEmpty())
                f.assertUnqueuedPersistence(); assertTrue(f.native.ownsPlaybackSource())
            }
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T
    private data class Click(val task: OriginalTask, val onEdt: Boolean, val job: Job)
    private data class Ingress(val ordinal: Int, val bindingCaller: Job, val ioJob: Job, val ioThread: Boolean,
        val storeHeldOutside: Boolean, val entryHeldOutside: Boolean, val nativeHeldOutside: Boolean,
        val directoryReady: Boolean, var statePersistedBeforeManagerCommit: Boolean = false, var queued: QueuedTask? = null)

    private inner class Fixture(private val seasonType: Int = 10, private val downloadRight: Int? = null) {
        val directory = Files.createTempDirectory("pgc-course-real-queue-")
        private val createdAbsolute = directory.toAbsolutePath().normalize()
        private val createdReal = directory.toRealPath()
        private val tempAbsolute = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        private val tempReal = tempAbsolute.toRealPath()
        val destination = directory.resolve("downloads")
        val stateFile = directory.resolve("queue/state.json")
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        private val parentJob = SupervisorJob()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val parentScope = CoroutineScope(parentJob + Dispatchers.Swing + CoroutineExceptionHandler { _, error -> failures += error })
        private val slot = DesktopOriginalVideoRootOwnerSlot(repository, parentScope, parentJob::isActive)
        lateinit var gate: DesktopOriginalVideoRootGate
        lateinit var owner: DesktopOriginalVideoOwnerAssembly
        lateinit var native: DesktopOriginalBangumiNativePresenter
        lateinit var media: DesktopOriginalVideoRootMediaFactory
        lateinit var vm: BangumiPlayerViewModel
        lateinit var settings: DesktopOriginalPlayerSettingsContext
        lateinit var manager: DesktopDownloadManager
        lateinit var trace: TracePublication
        private lateinit var initialQueueState: ByteArray
        private lateinit var requests: DesktopOriginalVideoOwnerRequestFactory
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("settings")))
        val player = MpvPlayer()
        private lateinit var actor: PremiumRecoveryActor
        private lateinit var cache: DesktopMediaByteCache
        private lateinit var subtitles: DesktopSubtitleAssets
        private val privacy = DesktopSearchPreferences(directory.resolve("privacy"))
        val clicks = CopyOnWriteArrayList<Click>()
        val toasts = CopyOnWriteArrayList<String>()
        val mediaLoadCaller = AtomicReference<Job?>()
        private val pendingClick = AtomicReference<Pair<OriginalTask, Boolean>?>()
        private val versions = MutableStateFlow<Long?>(null)
        // Exact object received from the real Assembly accepted callback, matching
        // RuntimeBinding.accepted's published-reference projection. No manufactured source.
        private val published = AtomicReference<DesktopOriginalVideoAcceptedPublication?>()
        val releaseMedia = CountDownLatch(1)
        private val mediaBytes = ByteArray(96) { it.toByte() }
        private val transport: OkHttpClient
        private val epoch: Long
        private fun rootOwns() = parentJob.isActive && gate.owns()
        private fun owns() = rootOwns() && slot.currentAssembly() === owner && native.owns()
        private fun assertOwned() { if (!owns()) throw CancellationException("Actual retained CPU slot retired") }
        private val progress = object : DesktopOriginalVideoProgressPort {
            override fun getCachedPosition(bvid: String, cid: Long) = 0L
            override fun savePosition(bvid: String, cid: Long, positionMs: Long) {
                check(bvid in setOf("BV1GJ411x7h7", "BV17x411w7KC")); check(cid in setOf(104L, 105L))
            }
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
            override fun cdnNetwork() = DesktopCdnNetworkObservation(true, "cpu-memory-transport")
        }
        init {
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-secondary", "bili_jct" to "synthetic-secondary-csrf"),
                AccountSummary(42, "Secondary", "", isVip = true))
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"),
                AccountSummary(41, "Fixture", "", isVip = true))
            sessions.saveSpiCookies(mapOf("buvid3" to "synthetic-visitor", "buvid4" to "synthetic-visitor4"), sessions.generation)
            epoch = repository.sessionEpoch
            transport = repository.httpClient.newBuilder().proxy(NetworkProxy.NO_PROXY).retryOnConnectionFailure(false)
                .addInterceptor { chain ->
                    val request = chain.request()
                    check(request.url.isHttps && request.url.port == 443)
                    when (request.url.host) {
                        "www.bilibili.com" -> {
                            check(request.method == "GET" && request.url.encodedPath == "/")
                            response(request, "in-memory visitor bootstrap")
                        }
                        "api.bilibili.com" -> {
                            check(request.method == "GET")
                            val body = when (request.url.encodedPath) {
                                "/x/frontend/finger/spi" -> """{"code":0,"data":{"b_3":"synthetic-visitor","b_4":"synthetic-visitor4"}}"""
                                "/x/web-interface/nav" -> """{"code":0,"data":{"isLogin":true,"mid":41,"vip":{"status":1},"wbi_img":{"img_url":"https://fixture.invalid/0123456789abcdef0123456789abcdef.png","sub_url":"https://fixture.invalid/fedcba9876543210fedcba9876543210.png"}}}"""
                                "/x/space/bangumi/follow/list" -> """{"code":0,"data":{"list":[],"total":0}}"""
                                "/pgc/view/web/season" -> seasonJson()
                                "/pgc/player/web/v2/playurl", "/pgc/player/web/playurl", "/pugv/player/web/playurl" -> playJson()
                                else -> error("Unexpected CPU protocol ${request.url.encodedPath}")
                            }
                            response(request, body)
                        }
                        "media.invalid" -> {
                            check(request.method in setOf("GET", "HEAD"))
                            check(request.url.encodedPath in setOf("/pgc-video.m4s", "/pgc-audio.m4s", "/cover.jpg"))
                            assertFalse(Thread.holdsLock(storeLock())); assertFalse(Thread.holdsLock(entryLock()))
                            assertFalse(Thread.holdsLock(nativeLock()))
                            check(releaseMedia.await(12, TimeUnit.SECONDS)) { "CPU media fixture release timed out" }
                            val range = request.header("Range")?.removePrefix("bytes=")?.split('-')
                            val start = range?.firstOrNull()?.toIntOrNull() ?: 0
                            val end = range?.getOrNull(1)?.toIntOrNull() ?: mediaBytes.lastIndex
                            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("memory")
                                .header("Accept-Ranges", "bytes").header("Content-Length", mediaBytes.size.toString())
                                .apply { if (range != null) header("Content-Range", "bytes $start-$end/${mediaBytes.size}") }
                                .code(if (range == null) 200 else 206)
                                .body((if (request.method == "HEAD") byteArrayOf() else mediaBytes.copyOfRange(start, end + 1))
                                    .toResponseBody()).build()
                        }
                        else -> error("Unexpected CPU origin ${request.url.host}")
                    }
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            subtitles = DesktopSubtitleAssets(transport)
            context.store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(true)))
        }

        suspend fun initialize() {
            privacy.setPrivacyMode(true)
            cache = DesktopMediaByteCache(directory.resolve("bytes"), parentScope, repository)
            slot.install(epoch, { actualGate ->
                gate = actualGate
                settings = DesktopOriginalPlayerSettingsContext(context, gate::owns, gate::commitEntry,
                    largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
                val readbacks = DesktopOriginalVideoEntryReadbacks(repository, epoch, gate.scope, gate::owns, gate::commitEntry)
                val operations = DesktopDynamicCardOperations(repository, epoch, gate::owns)
                val comments = Proxy.newProxyInstance(DesktopVideoCommentRequests::class.java.classLoader,
                    arrayOf(DesktopVideoCommentRequests::class.java)) { _, method, _ ->
                    when (method.name) { "isOwned" -> gate.owns(); "currentMid" -> 41L
                        else -> error("Unexpected comment effect ${method.name}") }
                } as DesktopVideoCommentRequests
                val commentRoot = DesktopOriginalCommentRootOwner(operations, comments, gate.scope, operations.emotes, gate::owns,
                    { error("No comment feedback") })
                val mini = Proxy.newProxyInstance(DesktopOriginalVideoOwnerMini::class.java.classLoader,
                    arrayOf(DesktopOriginalVideoOwnerMini::class.java)) { _, method, args ->
                    when {
                        method.name == "syncCurrentVideoInfo" -> {
                            val projected = args!![0] as VideoPlaybackUiState.Success
                            check(projected.info.cid in setOf(104L, 105L)); Unit
                        }
                        method.name.startsWith("setOn") -> { check(args!![0] == null); Unit }
                        method.name.startsWith("getOn") -> null
                        else -> error("Unexpected Mini effect ${method.name}")
                    }
                } as DesktopOriginalVideoOwnerMini
                val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
                    arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, _ ->
                    when (method.name) { "capturePlaybackDispatch" -> null
                        "enabledPlayerPlugins" -> emptyList<com.android.purebilibili.core.plugin.PlayerPlugin>()
                        else -> error("Unexpected plugin effect ${method.name}") }
                } as DesktopOriginalVideoOwnerPlugins
                val effects = DesktopOriginalVideoOwnerEffects(mini, unused(), unused(), network,
                    object : DesktopOriginalVideoOwnerCache {
                        override fun get(bvid: String, cid: Long): PlayUrlData? = null
                        override fun invalidate(bvid: String, cid: Long) = error("No cache invalidation")
                    }, unused(), unused(), unused(), MutableStateFlow(false))
                media = DesktopOriginalVideoRootMediaFactory(repository, cache, gate, { owner }, {}, {})
                requests = DesktopOriginalVideoOwnerRequestFactory(repository, DesktopLoginRepository(repository), epoch,
                    gate.scope, gate::owns, gate::commitEntry, { PlayerPreferences() }, capabilities,
                    { true }, { false }, { false }, subtitles, privacy,
                    { _, _ -> error("Preseeded CPU identity must not retire") }, media::request)
                DesktopOriginalVideoOwnerAssembly.create(context, settings, repository, epoch, gate.scope,
                    gate::owns, gate::commitEntry, player, DesktopRepositoryPlaybackPublication(repository), { false }, versions,
                    { published.set(it); versions.value = it.sourceVersion }, media::admission,
                    captureInvocation = { state, nativeOwner -> requests.capture(state, nativeOwner) },
                    status = readbacks, prepareAcceptedMedia = { media.accepted(it) },
                    createPlugins = { _, _ -> plugins }, confirmFollow = { error("No follow write") },
                    feedback = { error("No ordinary feedback") }, readHasPrimarySession = readbacks::hasPrimarySession,
                    readHasPrimaryAccessToken = readbacks::hasPrimaryAccessToken, readPrimaryMid = readbacks::primaryMid,
                    readOwnedCooldown = readbacks::cooldownRemainingMs, progress = progress, capabilities = capabilities,
                    applyPreferredVolume = {}, dashSegmentRequestsEnabled = { false },
                    logSeek = { _, _, _, _ -> }, diagnosticLoggingEnabled = { false }, resumeEndedSource = { error("No replay") },
                    root = commentRoot, interactionAnalytics = unused(), effects = effects,
                    todayWatchFeedback = DesktopTodayWatchFeedbackWriteBinding(context, gate::owns, gate::commitEntry))
            }, { }, { }, { })
            ui {
                owner = slot.requireAssembly()
                val portrait = DesktopOriginalPortraitPlatformBinding(owner, slot::currentAssembly, unused(), unused(), unused(), unused(),
                    captureRequest = { requests.captureBinding(it.captureLoadState()) },
                    creatorTeamFollowStateChanges = emptyFlow(), mediaCache = cache,
                    captureCookieHeader = { binding, url -> binding.captureMediaCookieHeader(url) },
                    favoriteQuickSaveDefaultFolder = flowOf(false), blockedUps = unused(), danmaku = unused(),
                    currentCdnPlugin = { null }, presentationLease = { _, _ -> error("No surface") },
                    danmakuLease = { _, _ -> error("No surface") }, releasePager = { _, _ -> error("No surface") },
                    recover = { _, _ -> error("No surface") }, share = { _, _ -> error("No share") },
                    feedback = { _, _ -> error("No feedback") }, surface = { _, _, _ -> error("No rendering") },
                    viewport = { _, _, _, _, _, _, _ -> error("No viewport") }, danmakuSurface = { _, _, _, _, _ -> error("No rendering") },
                    onPreparation = {}, prepareAcceptedMedia = { expected, plan, current ->
                        owner.native.acceptedMedia({ media.accepted(it, plan) }, current)
                    })
                native = DesktopOriginalBangumiNativePresenter(owner, slot::currentAssembly, portrait, gate.scope,
                    { if (::vm.isInitialized) vm.uiState.value as? BangumiPlayerState.Success else null },
                    { value, notify -> requests.captureBinding(value.captureLoadState(), onAuthorizationRetired = notify)
                        .also { mediaLoadCaller.set(it.capturedDownloadCallerJob()) } },
                    { repository.sessionEpoch }, { repository.playbackAuthorizationRevision.value })
                val api = repository.ownedHomeService(BangumiApi::class.java, "https://api.bilibili.com/", epoch, gate::owns)
                val nav = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, gate::owns)
                val hub = DesktopOriginalBangumiHubRepository(api, nav, unused(), { "synthetic-csrf" }, { 41L })
                val pages = DesktopOriginalBangumiPagesRequests(api, hub, { "synthetic-csrf" }, { "synthetic-session" }, gate::owns, gate::owns)
                val account = DesktopOriginalBangumiPlayerAccountView(owner, repository, privacy, native::assertCurrent,
                    ::assertSubject, native::capturedPlaybackBindingOrNull, ::requestRepository)
                trace = TracePublication(DesktopRepositoryPlaybackPublication(repository))
                manager = DesktopDownloadManager(transport, stateFile, DownloadMuxer { video, audio, output ->
                    assertNotNull(video); assertNotNull(audio)
                    assertContentEquals(mediaBytes, Files.readAllBytes(video)); assertContentEquals(mediaBytes, Files.readAllBytes(audio))
                    Files.write(output, byteArrayOf(0, 0, 0, 20) + "ftyp".toByteArray() + mediaBytes)
                }, publication = trace, defaultDestination = { destination })
                // The real manager initializes its persisted empty queue before any click.
                assertTrue(manager.tasks.value.isEmpty())
                initialQueueState = Files.readAllBytes(stateFile)
                assertTrue(persisted().isEmpty())
                val base = object : DesktopOriginalBangumiBaseRequests {
                    override suspend fun getSponsorSegments(bvid: String): List<SponsorSegment> = emptyList()
                    override fun findSegmentAtPosition(segments: List<SponsorSegment>, positionMs: Long): SponsorSegment? = null
                    override suspend fun getDanmakuRawData(cid: Long): ByteArray? = error("No danmaku")
                }
                val environment = DesktopOriginalBangumiPlayerEnvironment(gate.scope, owner.section, settings,
                    DesktopOriginalBangumiPlayerRequestsView(pages, native::capturedPlaybackBinding, native::assertCurrent),
                    account, object : DesktopOriginalBangumiPlayerActions {
                        override suspend fun checkLikeStatus(aid: Long) = false
                        override suspend fun checkCoinStatus(aid: Long) = 0
                    }, owner.environment.interactionUseCase, nav, progress, base, native, flowOf(false),
                    { -1 }, { -1 }, { error("No audio preference write") }, { false }, { false }, { false }, { false }, { null },
                    { _, _, _ -> error("No CDN plugin") }, { _, _, _ -> check(gate.owns()) },
                    { task -> capture(task).also { pendingClick.set(task to EventQueue.isDispatchThread()) } },
                    {}, { block ->
                        val click = pendingClick.getAndSet(null)
                        native.launch(block, start = CoroutineStart.LAZY).also { job ->
                            if (click != null) clicks.add(Click(click.first, click.second, job))
                            job.start()
                        }
                    }, native::commit, native::owns, native::ownsPlaybackSource, network, { false })
                vm = BangumiPlayerViewModel(environment)
                vm.attachPlayer(owner.section)
                gate.scope.launch { vm.toastEvent.collect { toasts.add(it) } }
                player.loadVersioned(PlaybackSource("file:///synthetic-bootstrap.avi"))
                actor = PremiumRecoveryActor(player)
            }
        }
        private fun requestRepository(binding: DesktopOriginalVideoRepositoryBinding) =
            createDesktopOriginalVideoOwnerRequestRepository(repository, binding, subtitles, privacy,
                { receipt, current -> repository.ownedHomeVisitorInitialized(receipt.accountEpoch, current) })
        private fun assertSubject(bvid: String, cid: Long, ep: Long, season: Long) {
            assertOwned()
            val state = vm.uiState.value as? BangumiPlayerState.Success ?: throw CancellationException("No PGC state")
            require(state.currentEpisode.bvid == bvid && state.currentEpisode.cid == cid &&
                state.currentEpisode.id == ep && state.seasonDetail.seasonId == season)
            if (!native.ownsPlaybackSource()) throw CancellationException("Real PGC source retired")
        }
        fun capture(task: OriginalTask): suspend () -> Boolean = captureDesktopOriginalBangumiDownloadTask(task, owner, native,
            manager, ::assertOwned, { vm.uiState.value as? BangumiPlayerState.Success }, ::assertSubject, ::owns,
            ::withPresentationAdmission)
        // CPU wiring of Factory.isPresentationCurrent / withPresentationAdmission:
        // actual Store -> actual entry -> short actual NativeOwner identity read.
        // Physical Factory construction and Runtime PluginBridge are excluded.
        private fun isPresentationCurrent(expected: DesktopOriginalVideoAcceptedPublication): Boolean {
            if (slot.currentAssembly() !== owner) return false
            val receipt = expected.nativeSource.source.authorizationReceipt ?: return false
            return published.get() === expected && gate.owns() && owner.owns() &&
                repository.sessionEpochFlow.value == receipt.accountEpoch &&
                repository.playbackAuthorizationRevision.value == receipt.revision
        }
        private fun withPresentationAdmission(expected: DesktopOriginalVideoAcceptedPublication, action: () -> Unit): Boolean {
            if (slot.currentAssembly() !== owner) return false
            var applied = false
            return try {
                repository.withPrimaryPlaybackAdmission(gate.capturedEpoch,
                    { isPresentationCurrent(expected) }) {
                    gate.commitEntry {
                        if (isPresentationCurrent(expected) && owner.native.isCurrent(expected)) {
                            action() // No native lock remains held after isCurrent.
                            applied = true
                        }
                    } && applied
                }
            } catch (_: CancellationException) { false }
        }
        fun accepted() = checkNotNull(owner.native.current())
        fun originalTask(): OriginalTask {
            val state = vm.uiState.value as BangumiPlayerState.Success
            val episode = state.currentEpisode
            val detail = state.seasonDetail
            val qualityIndex = state.acceptQuality.indexOf(state.quality)
            return OriginalTask(aid = episode.aid, bvid = episode.bvid, cid = episode.cid,
                title = detail.title.ifBlank { episode.title },
                episodeLabel = episode.title.ifBlank { "第${state.currentEpisodeIndex + 1}讲" },
                groupKey = "course:${detail.seasonId}", groupTitle = detail.title,
                episodeSortIndex = state.currentEpisodeIndex, episodeCount = detail.episodes?.size ?: 1,
                cover = episode.cover.ifBlank { detail.cover },
                ownerName = detail.upInfo?.uname.orEmpty(), ownerFace = detail.upInfo?.avatar.orEmpty(),
                duration = (episode.duration / 1000L).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                quality = state.quality, qualityDesc = state.acceptDescription.getOrNull(qualityIndex) ?: "${state.quality}P",
                videoUrl = state.playUrl!!, audioUrl = state.audioUrl.orEmpty())
        }
        suspend fun load() {
            ui { vm.loadBangumiPlay(19, 41) }
            withTimeout(8_000) { while (owner.native.current() == null) delay(5) }
            ui {
                actor.drain(); actor.loaded()
                assertTrue(native.ownsPlaybackSource()); assertFalse(player.state.value.ready)
                assertTrue(actor.native.loads.isNotEmpty())
                val snapshot = checkNotNull(player.currentSourceSnapshot())
                assertEquals(accepted().nativeSource.sourceVersion, snapshot.sourceVersion)
                assertEquals(accepted().nativeSource.source, snapshot.source)
                assertTrue(player.ownsSourceSnapshot(accepted().nativeSource))
            }
        }
        suspend fun awaitClick(): Click = withTimeout(8_000) { while (clicks.isEmpty()) delay(5); clicks.first() }
        suspend fun awaitQueue() = withTimeout(8_000) {
            while (manager.tasks.value.isEmpty() || !Files.isRegularFile(stateFile) || trace.workerJobs.isEmpty()) delay(5)
        }
        suspend fun awaitCompleted() = withTimeout(8_000) {
            while (manager.tasks.value.single().status !in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED)) delay(5)
            assertEquals(DownloadStatus.COMPLETED, manager.tasks.value.single().status, manager.tasks.value.single().error)
            while (persisted().single().status != DownloadStatus.COMPLETED) delay(5)
        }
        fun persisted(): List<QueuedTask> = Json { ignoreUnknownKeys = true }.decodeFromString(
            ListSerializer(QueuedTask.serializer()), Files.readString(stateFile))
        fun assertUnqueuedPersistence(message: String? = null) {
            assertTrue(persisted().isEmpty(), message)
            assertContentEquals(initialQueueState, Files.readAllBytes(stateFile), message)
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        suspend fun <T> uiResult(action: () -> T): T = withContext(Dispatchers.Swing) { action() }
        private fun storeLock() = DesktopSessionStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(sessions)
        private fun entryLock() = DesktopOriginalVideoRootGate::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(gate)
        private fun nativeLock() = MpvPlayer::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(player)
        @Suppress("UNCHECKED_CAST")
        private fun actualNativeContinuationJob(): Job = checkNotNull(
            (DesktopOriginalBangumiNativePresenter::class.java.getDeclaredField("currentCaller").apply { isAccessible = true }
                .get(native) as ThreadLocal<Job?>).get())

        inner class TracePublication(private val actual: DesktopRepositoryPlaybackPublication) : DesktopPlaybackPublication {
            private val admissions = AtomicInteger()
            val ingress = CopyOnWriteArrayList<Ingress>()
            val workerJobs = CopyOnWriteArrayList<Job>()
            var beforeFinal: ((Ingress) -> Unit)? = null
            var afterFinal: ((Ingress) -> Unit)? = null
            override val requiresAccountReceipt get() = actual.requiresAccountReceipt
            override fun isCurrent(source: PlaybackSource) = actual.isCurrent(source)
            override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
                val ordinal = admissions.incrementAndGet()
                if (ordinal > 2) return actual.admit(source, stillOwned, block)
                val binding = (owner.invocations.requireRequestRepository() as DesktopOriginalVideoOwnerRequestRepository).binding
                val caller = binding.capturedDownloadCallerJob()
                val item = clicks.single().task
                val marker = destination.resolve(QueuedTask.directoryName(item.id)).resolve(".bilipai-download")
                val evidence = Ingress(ordinal, caller, actualNativeContinuationJob(), !EventQueue.isDispatchThread(),
                    Thread.holdsLock(storeLock()), Thread.holdsLock(entryLock()), Thread.holdsLock(nativeLock()), Files.isRegularFile(marker))
                ingress.add(evidence)
                assertFalse(evidence.storeHeldOutside); assertFalse(evidence.entryHeldOutside); assertFalse(evidence.nativeHeldOutside)
                if (ordinal == 1) assertFalse(evidence.directoryReady)
                if (ordinal == 2) beforeFinal?.invoke(evidence)
                val result = actual.admit(source, stillOwned, block)
                if (ordinal == 2) {
                    // Observation, cancellation and file queries only AFTER all actual admissions return.
                    assertFalse(Thread.holdsLock(storeLock())); assertFalse(Thread.holdsLock(entryLock()))
                    assertFalse(Thread.holdsLock(nativeLock()))
                    evidence.queued = manager.tasks.value.single()
                    evidence.statePersistedBeforeManagerCommit = persisted().any { it.id == item.id }
                    afterFinal?.invoke(evidence)
                }
                return result
            }
            override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job?): Call.Factory {
                workerJobs.add(checkNotNull(callerJob))
                return actual.calls(delegate, source, stillOwned, callerJob)
            }
        }
        suspend fun close() {
            releaseMedia.countDown()
            if (::manager.isInitialized) {
                manager.close()
                val actualScope = DesktopDownloadManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                    .get(manager) as CoroutineScope
                withTimeout(8_000) { actualScope.coroutineContext.job.join() }
            }
            if (::vm.isInitialized) ui { try { vm.close() } catch (retired: CancellationException) { check(!gate.owns()) } }
            if (::actor.isInitialized) actor.close()
            slot.closeAndJoin()
            parentJob.cancelAndJoin()
            if (::cache.isInitialized) cache.close()
            subtitles.close(); player.close()
            transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
            assertTrue(failures.isEmpty(), failures.toString())
            deleteOnlyCreatedDirectory()
        }
        suspend fun useSuspending(block: suspend (Fixture) -> Unit) {
            try { initialize(); withTimeout(30_000) { block(this@Fixture) } }
            finally { withContext(NonCancellable) { close() } }
        }
        private fun deleteOnlyCreatedDirectory() {
            check(directory.toAbsolutePath().normalize() == createdAbsolute)
            check(createdAbsolute.parent == tempAbsolute && createdReal.parent == tempReal)
            check(createdAbsolute != tempAbsolute && createdAbsolute.fileName.toString().startsWith("pgc-course-real-queue-"))
            fun validate(path: Path, attributes: BasicFileAttributes) {
                val absolute = path.toAbsolutePath().normalize()
                check(absolute.startsWith(createdAbsolute)); check(!attributes.isSymbolicLink && !attributes.isOther)
                check(path.toRealPath() == absolute)
                if (System.getProperty("os.name").startsWith("Windows"))
                    check((Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int) and 0x400 == 0)
            }
            check(directory.toRealPath() == createdReal)
            val nodes = mutableListOf<Path>()
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validate(dir, attrs); nodes.add(dir); return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    validate(file, attrs); nodes.add(file); return FileVisitResult.CONTINUE
                }
            })
            for (path in nodes.sortedByDescending { it.nameCount }) {
                check(directory.toRealPath() == createdReal)
                validate(path, Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS))
                Files.delete(path)
            }
        }
        private fun seasonJson(): String {
            val rights = downloadRight?.let { ""","rights":{"allow_download":$it}""" }.orEmpty()
            return """{"code":0,"result":{"season_id":19,"season_type":$seasonType,"title":"Synthetic course","cover":"https://media.invalid/cover.jpg","up_info":{"mid":7,"uname":"Fixture UP","avatar":"https://media.invalid/face.jpg"},"section":[],"episodes":[{"id":41,"aid":101,"cid":104,"bvid":"BV1GJ411x7h7","title":"One","cover":"https://media.invalid/cover.jpg","duration":60000},{"id":42,"aid":102,"cid":105,"bvid":"BV17x411w7KC","title":"Two","duration":60000}],"user_status":{"vip":1,"follow":0,"follow_status":0}$rights}}"""
        }
    }
    companion object {
        private fun response(request: Request, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("memory").body(body.toResponseBody("application/json".toMediaType())).build()
        private fun playJson() = """{"code":0,"result":{"quality":120,"timelength":60000,"accept_quality":[120,80],"accept_description":["4K","1080P"],"dash":{"duration":60,"video":[{"id":120,"baseUrl":"https://media.invalid/pgc-video.m4s?synthetic=1","bandwidth":120000,"mime_type":"video/mp4","codecs":"avc1.640033","width":3840,"height":2160,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-31","index_range":"32-63"}}],"audio":[{"id":30280,"baseUrl":"https://media.invalid/pgc-audio.m4s?synthetic=1","bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}]}}}"""
    }
}
