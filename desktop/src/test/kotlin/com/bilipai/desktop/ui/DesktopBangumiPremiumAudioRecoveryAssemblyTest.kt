package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.viewmodel.DesktopVideoCommentRequests
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.player.cache.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.swing.Swing
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

import com.android.purebilibili.core.store.DesktopOriginalPortraitSettings
import com.android.purebilibili.feature.video.playback.audio.*
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.serialization.json.JsonObject

/** Actual retained PGC recovery component, real slot/gate/Assembly, original VM,
 * Portrait, immutable Binding/temporary Store and NativeOwner. Existing memory
 * ABI drives real Load/START_FILE/FILE_LOADED/END_FILE and typed log diagnostics.
 * The presentation callback is CPU wiring of the exact Factory gate body, not
 * physical Entry/Factory/Runtime initialization. No decoder, seek ACK or hardware. */
class DesktopBangumiPremiumAudioRecoveryAssemblyTest {
    @Test fun healthySameEpisodeRestoreKeepsTheAcceptedSourceWithoutAnotherPlayUrl(): Unit = runBlocking {
        for (seasonType in listOf(1, 10)) Fixture(seasonType = seasonType).useSuspending { f ->
            f.load()
            val before = f.accepted()
            val beforeState = f.state()
            val beforeJob = f.playbackJob()
            val requests = f.playCalls.get()
            f.ui {
                assertTrue(f.native.canUseCachedPlayback(beforeState))
                assertTrue(f.owner.section.mediaItemCount > 0)
                val beforePosition = f.owner.section.currentPosition
                val seeks = mutableListOf<DesktopOriginalNativeSeekSubmission>()
                val listener = object : DesktopOriginalMpvOverlayControl.Listener {
                    override fun onSeekQueued(submission: DesktopOriginalNativeSeekSubmission) { seeks += submission }
                }
                f.owner.section.addListener(listener)
                try {
                    f.vm.loadBangumiPlay(19, 41, resumePositionMs = 19_250L)
                    assertSame(before, f.accepted())
                    assertSame(beforeJob, f.playbackJob())
                    val queued = seeks.single()
                    assertEquals(before.nativeSource.sourceVersion, queued.sourceVersion)
                    assertEquals(19_250L, queued.targetPositionMs)
                    assertTrue(queued.operationId > 0L)
                    // A queued native seek is not playback-restart acknowledgement.
                    assertEquals(beforePosition, f.owner.section.currentPosition)
                } finally {
                    f.owner.section.removeListener(listener)
                }
            }
            assertEquals(requests, f.playCalls.get())
            assertEquals(beforeState.currentEpisode, f.state().currentEpisode)
            assertEquals(beforeState.quality, f.state().quality)
            assertEquals(beforeState.audioUrl, f.state().audioUrl)
        }
    }

    @Test fun explicitReloadFetchesAnotherPlayUrlThroughTheOriginalPgcAndCoursePublishers(): Unit = runBlocking {
        for (seasonType in listOf(1, 10)) Fixture(seasonType = seasonType).useSuspending { f ->
            f.load()
            val before = f.accepted()
            val beforeState = f.state()
            val preferences = f.preferences()
            val requests = f.playCalls.get()
            val initialQn = f.playQualities.last()
            val caller = f.uiResult {
                assertTrue(f.native.canUseCachedPlayback(beforeState))
                assertTrue(f.owner.section.mediaItemCount > 0)
                f.vm.retry()
                f.playbackJob()
            }
            withTimeout(8_000) { caller.join() }
            assertFalse(caller.isCancelled)
            val after = f.accepted()
            assertNotSame(before, after)
            assertTrue(after.sourceVersion > before.sourceVersion)
            assertEquals(requests + 1, f.playCalls.get())
            assertEquals(initialQn, f.playQualities.last())
            assertEquals(beforeState.currentEpisode, f.state().currentEpisode)
            assertEquals(beforeState.quality, f.state().quality)
            assertEquals(beforeState.audioUrl, f.state().audioUrl)
            assertEquals(preferences, f.preferences())
            assertTrue(f.preferenceWrites.isEmpty())
            f.ui {
                assertTrue(f.native.ownsPlaybackSource())
                assertTrue(f.player.ownsSourceSnapshot(after.nativeSource))
                assertEquals(0L, f.owner.section.currentPosition)
                assertTrue(f.owner.section.playWhenReady)
            }
        }
    }

    @Test fun actualAudioFailureAcceptsAacAndReleasedChildRetainsNormalControls(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load()
            val before = f.accepted()
            val state = f.state()
            val load = f.owner.captureLoadState()
            val preferences = f.preferences()
            val calls = f.playCalls.get()
            f.failAndQueue()
            f.awaitAac()
            val ticket = f.preparations.single()
            val caller = checkNotNull(ticket.caller)
            assertSame(caller, f.playbackJob())
            withTimeout(8_000) { caller.join() }
            assertTrue(caller.isCompleted); assertFalse(caller.isCancelled)
            assertSame(before, ticket.source)
            val after = f.accepted()
            assertNotSame(before, after)
            assertEquals(before.sourceVersion, after.sourceVersion)
            assertSame(before.nativeSource.source.authorizationReceipt, after.nativeSource.source.authorizationReceipt)
            assertEquals(before.nativeSource.source.cookieHeader, after.nativeSource.source.cookieHeader)
            assertEquals(before.nativeSource.source.streamHeaders, after.nativeSource.source.streamHeaders)
            assertEquals(before.nativeSource.source.primaryAccountEpoch, after.nativeSource.source.primaryAccountEpoch)
            assertEquals(before.request, after.request)
            assertSame(load.currentRequest, f.owner.captureLoadState().currentRequest)
            assertEquals(load.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
            f.assertAac(state, preferences, calls)
            f.ui {
                assertEquals(27_750L, f.owner.section.currentPosition)
                assertFalse(f.owner.section.playWhenReady)
                f.actor.drain(); f.actor.loaded()
                assertEquals(after.nativeSource.source.nativeLoadUrl, f.actor.native.loads.last()[1])
                @Suppress("UNCHECKED_CAST")
                val options = f.actor.native.loads.last()[4] as Map<String, String>
                assertEquals("27.75", options["start"]); assertEquals("yes", options["pause"])
                assertTrue(f.player.ownsSourceSnapshot(after.nativeSource))
                // Completed recovery caller and changed user pause/seek serials
                // must not remain in the persistent child publication predicate.
                f.owner.section.play(); assertFalse(f.player.state.value.paused)
                f.owner.section.pause(); assertTrue(f.player.state.value.paused)
                val first = assertNotNull(f.owner.section.seekToTracked(35_000))
                val second = assertNotNull(f.owner.section.seekToTracked(27_750))
                assertTrue(second.operationId > first.operationId)
                assertTrue(f.player.ownsSourceSnapshot(after.nativeSource))
                var admitted = false
                assertTrue(f.owner.native.admitPlaybackDispatch(after) { admitted = true })
                assertTrue(admitted)
            }
            // Do not drain pending seeks: this verifies command admission only.
            f.manualHiRes()
            assertEquals(AUDIO_QUALITY_HI_RES, f.state().selectedAudioQuality)
            assertEquals(AUDIO_QUALITY_HI_RES, f.state().requestedAudioQuality)
            assertNotEquals(AudioFallbackReason.DECODER_ERROR, f.state().audioFallbackReason)
            assertEquals(listOf(AUDIO_QUALITY_HI_RES), f.preferenceWrites.toList())
            assertEquals(calls, f.playCalls.get())
        }
    }

    @Test fun actualNetworkRetryAfterAacKeepsEffectiveAndRequestedPreferences(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(); val original = f.state(); val preferences = f.preferences()
            f.failAndQueue(); f.awaitAac(); f.playbackJob().join()
            val aac = f.accepted(); val calls = f.playCalls.get()
            f.ui { f.actor.drain(); f.actor.loaded() }
            f.failAndQueue(network = true)
            withTimeout(8_000) {
                while (f.playCalls.get() <= calls || f.owner.native.current() === aac ||
                    !f.native.ownsPlaybackSource()) delay(5)
            }
            val retry = f.playbackJob()
            withTimeout(8_000) { retry.join() }
            assertFalse(retry.isCancelled)
            f.assertAac(original, preferences, calls + 1)
            assertEquals(120, f.playQualities.last())
            assertNotSame(aac, f.accepted())
            assertEquals(27_750L, f.owner.section.currentPosition)
            assertFalse(f.owner.section.playWhenReady)
            f.ui { f.actor.drain(); f.actor.loaded() }
            assertTrue(f.player.ownsSourceSnapshot(f.accepted().nativeSource))
        }
    }

    @Test fun readyAndManualHiResDoNotResetActualSameEpisodePremiumBudget(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(); f.failAndQueue(); f.awaitAac(); f.playbackJob().join()
            f.ui { f.actor.drain(); f.actor.loaded() }
            f.manualHiRes()
            f.ui { f.actor.drain(); f.actor.loaded() }
            val before = f.accepted(); val calls = f.playCalls.get()
            f.failAndQueue()
            withTimeout(8_000) { while (f.recovery.state.value.status != PlaybackStatus.Failed) delay(5) }
            assertSame(before, f.accepted())
            assertEquals(1, f.preparations.size)
            assertEquals(AUDIO_QUALITY_HI_RES, f.state().selectedAudioQuality)
            assertEquals(AUDIO_QUALITY_HI_RES, f.state().requestedAudioQuality)
            assertNotEquals(AudioFallbackReason.DECODER_ERROR, f.state().audioFallbackReason)
            assertEquals(calls, f.playCalls.get())
            assertEquals(listOf(AUDIO_QUALITY_HI_RES), f.preferenceWrites.toList())
            assertEquals(PlayerFailureKind.AUDIO_OUTPUT, f.player.state.value.failure?.kind)
        }
    }

    @Test fun realMutationsAndCapturedCallerCancellationRejectStalePreparedRecovery(): Unit = runBlocking {
        for (mutation in listOf("account", "request", "episode", "manual", "pause", "seek", "cancel")) {
            Fixture().useSuspending { f ->
                f.load(); val before = f.accepted(); val calls = f.playCalls.get(); val preferences = f.preferences()
                f.beforePreparation = { ticket ->
                    assertSame(ticket.caller, f.playbackJob())
                    val old = f.owner.captureLoadState()
                    when (mutation) {
                        "account" -> {
                            val epoch = f.repository.sessionEpoch
                            val revision = f.repository.playbackAuthorizationRevision.value
                            assertTrue(f.sessions.setPlaybackAccountMid(42, epoch, f.gate::owns))
                            assertTrue(f.sessions.setPlaybackAccountMid(null, epoch, f.gate::owns))
                            assertEquals(epoch, f.repository.sessionEpoch)
                            assertTrue(f.repository.playbackAuthorizationRevision.value > revision)
                        }
                        "request" -> {
                            val state = f.state(); val episode = state.currentEpisode
                            val token = f.owner.playback.beginDesktopPortraitLoad(
                                PlaybackRequest.create(episode.bvid, episode.aid, episode.cid), f.native)
                            assertTrue(token > old.currentLoadRequestToken)
                        }
                        "episode" -> {
                            val episode = f.state().seasonDetail.episodes!![1]
                            f.owner.playback.beginDesktopPortraitLoad(
                                PlaybackRequest.create(episode.bvid, episode.aid, episode.cid), f.native)
                            assertEquals(episode.cid, f.owner.captureLoadState().currentRequest!!.cid)
                        }
                        "manual" -> {
                            val revision = f.vm.desktopPlaybackIntentRevision
                            f.vm.changeAudioQuality(AUDIO_QUALITY_HI_RES)
                            assertTrue(f.vm.desktopPlaybackIntentRevision > revision)
                        }
                        "pause" -> {
                            val paused = f.player.state.value.paused
                            val serials = f.player.capturePlaybackIntentSerials()
                            f.player.setPaused(!paused); f.player.setPaused(paused)
                            assertEquals(paused, f.player.state.value.paused)
                            assertFalse(f.player.ownsPlaybackIntentSerials(serials))
                            assertTrue(f.player.ownsSourceSnapshot(before.nativeSource))
                        }
                        "seek" -> {
                            val serials = f.player.capturePlaybackIntentSerials()
                            val first = assertNotNull(f.player.seekToTracked(35.0))
                            val second = assertNotNull(f.player.seekToTracked(27.75))
                            assertTrue(second > first)
                            assertFalse(f.player.ownsPlaybackIntentSerials(serials))
                            assertTrue(f.player.ownsSourceSnapshot(before.nativeSource))
                        }
                        "cancel" -> checkNotNull(ticket.caller).cancel("cancel the exact original recovery launch")
                    }
                }
                f.failAndQueue()
                val ticket = withTimeout(8_000) { while (f.preparations.isEmpty()) delay(5); f.preparations.single() }
                withTimeout(8_000) { checkNotNull(ticket.caller).join() }
                if (mutation == "manual") {
                    withTimeout(8_000) { while (f.preferenceWrites.isEmpty()) delay(5) }
                    assertEquals(listOf(AUDIO_QUALITY_HI_RES), f.preferenceWrites.toList())
                } else {
                    if (mutation == "account") {
                        // Real account ABA retires the immutable receipt; current()
                        // correctly hides its old accepted reference, not the player snapshot.
                        assertNull(f.owner.native.current(), mutation)
                        assertFalse(f.repository.isPlaybackReceiptCurrent(checkNotNull(before.nativeSource.source.authorizationReceipt)))
                        val snapshot = assertNotNull(f.player.currentSourceSnapshot())
                        assertEquals(before.nativeSource.sourceVersion, snapshot.sourceVersion, mutation)
                        assertEquals(before.nativeSource.source, snapshot.source, mutation)
                        assertSame(before, f.publications.last(), mutation)
                    } else assertSame(before, f.owner.native.current(), mutation)
                    assertEquals(preferences, f.preferences(), mutation)
                    assertTrue(f.preferenceWrites.isEmpty(), mutation)
                }
                assertEquals(AUDIO_QUALITY_HI_RES, f.state().selectedAudioQuality, mutation)
                assertNotEquals(AudioFallbackReason.DECODER_ERROR, f.state().audioFallbackReason, mutation)
                assertTrue(f.publications.none { it.nativeSource.source.audioUrl == AAC }, mutation)
                assertEquals(calls, f.playCalls.get(), mutation)
                if (mutation == "cancel") assertTrue(checkNotNull(ticket.caller).isCancelled)
            }
        }
    }

    @Test fun absentStandardAudioFailsTruthfullyWithoutPublicationOrGlobalWrite(): Unit = runBlocking {
        Fixture(includeAac = false).useSuspending { f ->
            f.load(); val before = f.accepted(); val preferences = f.preferences(); val calls = f.playCalls.get()
            val initialJob = f.playbackJob()
            f.failAndQueue()
            withTimeout(8_000) { while (f.recovery.state.value.status != PlaybackStatus.Failed ||
                f.playbackJob() === initialJob || !f.playbackJob().isCompleted) delay(5) }
            assertSame(before, f.accepted())
            assertTrue(f.preparations.isEmpty())
            assertEquals(AUDIO_QUALITY_HI_RES, f.state().selectedAudioQuality)
            assertNotEquals(AudioFallbackReason.DECODER_ERROR, f.state().audioFallbackReason)
            assertEquals(preferences, f.preferences()); assertTrue(f.preferenceWrites.isEmpty())
            assertEquals(calls, f.playCalls.get())
            assertEquals(PlayerFailureKind.AUDIO_OUTPUT, f.player.state.value.failure?.kind)
            assertNotEquals(PlaybackStatus.Ready, f.recovery.state.value.status)
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T
    private inner class Fixture(private val includeAac: Boolean = true, private val seasonType: Int = 10) {
        val directory = Files.createTempDirectory("pgc-audio-real-owner-")
        private val createdAbsolute = directory.toAbsolutePath().normalize()
        private val createdReal = directory.toRealPath()
        private val tempAbsolute = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        private val tempReal = tempAbsolute.toRealPath()
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
        private lateinit var requests: DesktopOriginalVideoOwnerRequestFactory
        private val context = DesktopPluginContext(DesktopPluginStore(directory.resolve("settings")))
        val player = MpvPlayer()
        lateinit var actor: PremiumRecoveryActor
        lateinit var recovery: DesktopOriginalPlaybackRecovery
        lateinit var component: DesktopOriginalBangumiPremiumAudioRecovery
        val preparations = CopyOnWriteArrayList<DesktopOriginalNativeRecoveryTicket>()
        val publications = CopyOnWriteArrayList<DesktopOriginalVideoAcceptedPublication>()
        val preferenceWrites = CopyOnWriteArrayList<Int>()
        val launchedJobs = CopyOnWriteArrayList<Job>()
        val playCalls = AtomicInteger()
        val playQualities = CopyOnWriteArrayList<Int>()
        var beforePreparation: ((DesktopOriginalNativeRecoveryTicket) -> Unit)? = null
        private lateinit var cache: DesktopMediaByteCache
        private lateinit var subtitles: DesktopSubtitleAssets
        private val privacy = DesktopSearchPreferences(directory.resolve("privacy"))
        val toasts = CopyOnWriteArrayList<String>()
        private val versions = MutableStateFlow<Long?>(null)
        // Exact object received from the real Assembly accepted callback, matching
        // RuntimeBinding.accepted's published-reference projection. No manufactured source.
        private val published = AtomicReference<DesktopOriginalVideoAcceptedPublication?>()
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
            override fun relatedNetworkObservation() = DesktopOriginalVideoRelatedNetworkObservation(
                DesktopHomeNetworkObservation(true, 3, 71L, false, "cpu-memory-transport"),
                downstreamBandwidthKbps = null,
            )
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
                                "/pgc/player/web/v2/playurl", "/pgc/player/web/playurl", "/pugv/player/web/playurl" -> {
                                    playCalls.incrementAndGet()
                                    playQualities.add(checkNotNull(request.url.queryParameter("qn")).toInt())
                                    playJson(includeAac)
                                }
                                else -> error("Unexpected CPU protocol ${request.url.encodedPath}")
                            }
                            response(request, body)
                        }
                        else -> error("Unexpected CPU origin ${request.url.host}")
                    }
                }.build()
            DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }.set(repository, transport)
            subtitles = DesktopSubtitleAssets(transport)
            context.store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(true),
                "default_audio_quality" to JsonPrimitive(AUDIO_QUALITY_HI_RES),
                "audio_quality" to JsonPrimitive(AUDIO_QUALITY_HI_RES)))
            context.store.update("settings", mapOf("audio_quality_preference" to JsonPrimitive(AUDIO_QUALITY_HI_RES)))
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
                media = DesktopOriginalVideoRootMediaFactory(repository, cache, gate, { owner }, {})
                requests = DesktopOriginalVideoOwnerRequestFactory(repository, DesktopLoginRepository(repository), epoch,
                    gate.scope, gate::owns, gate::commitEntry, { PlayerPreferences(defaultAudioQuality = AUDIO_QUALITY_HI_RES, lastSelectedAudioQuality = AUDIO_QUALITY_HI_RES) }, capabilities,
                    { true }, { false }, { false }, subtitles, privacy,
                    { _, _ -> error("Preseeded CPU identity must not retire") }, media::request)
                DesktopOriginalVideoOwnerAssembly.create(context, settings, repository, epoch, gate.scope,
                    gate::owns, gate::commitEntry, player, DesktopRepositoryPlaybackPublication(repository), { false }, versions,
                    { published.set(it); publications.add(it); versions.value = it.sourceVersion }, media::admission,
                    captureMetadataBinding = { error("No readonly metadata recapture in this fixture") }, captureInvocation = { state, nativeOwner -> requests.capture(state, nativeOwner, mediaPreparation = {
                        // Fixed-capability headless fixture; no physical NativeReady claim.
                        currentCoroutineContext().ensureActive()
                        if (!gate.owns()) throw CancellationException("Fixture preparation entry retired")
                    }) },
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
                    onPreparation = {}, prepareAcceptedMedia = { expected, plan, current, failure ->
                        // Observational hook BEFORE media creation, outside all real gates.
                        // The ticket is created/caller-captured by actual NativePresenter.
                        if (failure != null) {
                            assertFalse(Thread.holdsLock(storeLock())); assertFalse(Thread.holdsLock(entryLock()))
                            assertFalse(Thread.holdsLock(nativeLock()))
                            preparations.add(failure)
                            val observer = beforePreparation; beforePreparation = null
                            observer?.invoke(failure)
                        }
                        owner.native.acceptedMedia({ media.accepted(it, plan) }, failure, current)
                    })
                native = DesktopOriginalBangumiNativePresenter(owner, slot::currentAssembly, portrait, gate.scope,
                    { if (::vm.isInitialized) vm.uiState.value as? BangumiPlayerState.Success else null },
                    { value, notify -> requests.captureBinding(value.captureLoadState(), onAuthorizationRetired = notify) },
                    { repository.sessionEpoch }, { repository.playbackAuthorizationRevision.value })
                val api = repository.ownedHomeService(BangumiApi::class.java, "https://api.bilibili.com/", epoch, gate::owns)
                val nav = repository.ownedHomeService(BilibiliApi::class.java, "https://api.bilibili.com/", epoch, gate::owns)
                val hub = DesktopOriginalBangumiHubRepository(api, nav, unused(), { "synthetic-csrf" }, { 41L })
                val pages = DesktopOriginalBangumiPagesRequests(api, hub, { "synthetic-csrf" }, { "synthetic-session" }, gate::owns, gate::owns)
                val account = DesktopOriginalBangumiPlayerAccountView(owner, repository, privacy, native::assertCurrent,
                    ::assertSubject, native::capturedPlaybackBindingOrNull, ::requestRepository)
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
                    { settings.getSharedPreferences("quality_settings", 0).getInt("default_audio_quality", AUDIO_QUALITY_HI_RES) },
                    { settings.getSharedPreferences("quality_settings", 0).getInt("audio_quality", AUDIO_QUALITY_HI_RES) },
                    { value -> DesktopOriginalPortraitSettings.setAudioQuality(settings, value); preferenceWrites.add(value) },
                    { false }, { false }, { false }, { false }, { null },
                    { _, _, _ -> error("No CDN plugin") }, { _, _, _ -> check(gate.owns()) },
                    { error("No download effect") }, {}, { block ->
                        native.launch(block = block, start = CoroutineStart.LAZY).also { job ->
                            launchedJobs.add(job); job.start()
                        }
                    }, native::commit, native::owns, native::ownsPlaybackSource, network, { false })
                vm = BangumiPlayerViewModel(environment)
                vm.attachPlayer(owner.section)
                recovery = DesktopOriginalPlaybackRecovery(gate.scope,
                    { action -> owner.environment.commit { assertOwned(); action() } }, ::assertOwned)
                component = DesktopOriginalBangumiPremiumAudioRecovery(owner, native, owner.section, repository,
                    { vm }, recovery, { vm.uiState.value as? BangumiPlayerState.Success }, ::owns,
                    ::withPresentationAdmission)
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
        suspend fun load() {
            ui { vm.loadBangumiPlay(19, 41) }
            withTimeout(8_000) { while (owner.native.current() == null) delay(5) }
            ui {
                actor.drain(); actor.loaded()
                player.setPaused(true); actor.drain()
                assertEquals(120, state().quality)
                assertEquals(AUDIO_QUALITY_HI_RES, state().selectedAudioQuality)
                assertEquals(AUDIO_QUALITY_HI_RES, state().requestedAudioQuality)
                assertEquals(HI_RES, state().audioUrl)
                assertTrue(native.ownsPlaybackSource()); assertFalse(player.state.value.ready)
                assertTrue(actor.native.loads.isNotEmpty())
                val snapshot = checkNotNull(player.currentSourceSnapshot())
                assertEquals(accepted().nativeSource.sourceVersion, snapshot.sourceVersion)
                assertEquals(accepted().nativeSource.source, snapshot.source)
                assertTrue(player.ownsSourceSnapshot(accepted().nativeSource))
            }
        }
        fun state() = vm.uiState.value as BangumiPlayerState.Success
        fun preferences(): JsonObject = JsonObject(mapOf(
            "quality_settings" to context.store.preferences("quality_settings"),
            "settings" to context.store.preferences("settings")))
        fun playbackJob(): Job = BangumiPlayerViewModel::class.java.getDeclaredField("playbackLoadJob")
            .apply { isAccessible = true }.get(vm) as Job
        suspend fun failAndQueue(network: Boolean = false) = ui {
            if (network) networkDiagnostic()
            val failure = actor.fail(if (network) -13 else -14)
            assertSame(failure, player.state.value.failure)
            assertEquals(if (network) PlayerFailureKind.NETWORK else PlayerFailureKind.AUDIO_OUTPUT, failure.kind)
            assertEquals(if (network) -13 else -14, failure.nativeCode)
            val state = state(); val expected = accepted()
            assertTrue(component.failureSourceCurrent(state, expected, failure, vm.desktopPlaybackIntentRevision))
            val premium = component.isCurrentPremiumAudioFailure(state, expected, failure)
            assertEquals(!network, premium)
            component.queuePlaybackFailure(state, expected, failure, vm.desktopPlaybackIntentRevision,
                desktopOriginalNativeFailure(failure, premium), failure)
        }
        suspend fun awaitAac() = withTimeout(8_000) {
            while ((vm.uiState.value as? BangumiPlayerState.Success)?.audioFallbackReason != AudioFallbackReason.DECODER_ERROR ||
                recovery.state.value.status != PlaybackStatus.Ready || !native.ownsPlaybackSource()) delay(5)
        }
        fun assertAac(original: BangumiPlayerState.Success, expectedPreferences: JsonObject, expectedCalls: Int) {
            val actual = state(); val source = accepted().nativeSource.source
            assertEquals(original.quality, actual.quality); assertEquals(120, actual.quality)
            assertEquals(AUDIO_QUALITY_HI_RES, actual.requestedAudioQuality)
            assertEquals(AUDIO_QUALITY_AUTO, actual.selectedAudioQuality)
            assertEquals(AudioFallbackReason.DECODER_ERROR, actual.audioFallbackReason)
            assertEquals(original.currentEpisode, actual.currentEpisode)
            assertEquals(original.seasonDetail.seasonId, actual.seasonDetail.seasonId)
            assertEquals(original.playUrl, actual.playUrl); assertEquals(AAC, actual.audioUrl)
            assertEquals(actual.playUrl, source.videoUrl); assertEquals(AAC, source.audioUrl)
            assertEquals("https://www.bilibili.com/cheese/play/ep41", source.referer)
            assertEquals(expectedPreferences, preferences()); assertTrue(preferenceWrites.isEmpty())
            assertEquals(expectedCalls, playCalls.get())
        }
        suspend fun manualHiRes() {
            val count = preferenceWrites.size
            val job = uiResult { vm.changeAudioQuality(AUDIO_QUALITY_HI_RES); launchedJobs.last() }
            withTimeout(8_000) { while (preferenceWrites.size == count || !native.ownsPlaybackSource()) delay(5) }
            withTimeout(8_000) { job.join() }
            assertFalse(job.isCancelled)
        }
        private fun networkDiagnostic() {
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
        }
        suspend fun ui(action: () -> Unit) = withContext(Dispatchers.Swing) { action() }
        suspend fun <T> uiResult(action: () -> T): T = withContext(Dispatchers.Swing) { action() }
        private fun storeLock() = DesktopSessionStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(sessions)
        private fun entryLock() = DesktopOriginalVideoRootGate::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(gate)
        private fun nativeLock() = MpvPlayer::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(player)
        suspend fun close() {
            if (::recovery.isInitialized) recovery.close()
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
            check(createdAbsolute != tempAbsolute && createdAbsolute.fileName.toString().startsWith("pgc-audio-real-owner-"))
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
            return """{"code":0,"result":{"season_id":19,"season_type":$seasonType,"title":"Synthetic course","cover":"https://media.invalid/cover.jpg","up_info":{"mid":7,"uname":"Fixture UP","avatar":"https://media.invalid/face.jpg"},"section":[],"episodes":[{"id":41,"aid":101,"cid":104,"bvid":"BV1GJ411x7h7","title":"One","cover":"https://media.invalid/cover.jpg","duration":60000},{"id":42,"aid":102,"cid":105,"bvid":"BV17x411w7KC","title":"Two","duration":60000}],"user_status":{"vip":1,"follow":0,"follow_status":0}}}"""
        }
    }
    companion object {
        private const val AAC = "https://media.invalid/pgc-audio.m4s?synthetic=1"
        private const val HI_RES = "https://media.invalid/hires.flac?synthetic=1"
        private fun response(request: Request, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("memory").body(body.toResponseBody("application/json".toMediaType())).build()
        private fun playJson(includeAac: Boolean): String {
            val aac = if (includeAac) """{"id":30280,"baseUrl":"$AAC","bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}""" else ""
            return """{"code":0,"result":{"quality":120,"timelength":60000,"accept_quality":[120,80],"accept_description":["4K","1080P"],"dash":{"duration":60,"video":[{"id":120,"baseUrl":"https://media.invalid/pgc-video.m4s?synthetic=1","bandwidth":120000,"mime_type":"video/mp4","codecs":"avc1.640033","width":3840,"height":2160,"frameRate":"30","codecid":7,"segment_base":{"initialization":"0-31","index_range":"32-63"}}],"audio":[$aac],"flac":{"audio":{"id":30251,"baseUrl":"$HI_RES","bandwidth":800000,"mime_type":"audio/flac","codecs":"fLaC","segment_base":{"initialization":"0-31","index_range":"32-63"}}}}}}"""
        }
    }
}
