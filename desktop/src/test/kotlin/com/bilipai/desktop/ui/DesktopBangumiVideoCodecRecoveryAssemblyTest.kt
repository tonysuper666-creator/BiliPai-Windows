package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.model.response.BangumiEpisode
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

/** Actual retained codec/audio recovery component and original shared actor, real
 * slot/gate/Assembly/Presenter/Portrait/NativeOwner and temporary Store/Binding.
 * Existing memory ABI drives real Load and native warning/error + END_FILE events.
 * Declared codec capabilities and raw HTTP fixture responses are test inputs;
 * no authorization/source/budget authority is mocked. The two Factory callback
 * bodies use the same mechanically declared CPU wiring as the retained audio test.
 * Physical Entry/Factory/Runtime/UI, native DLL/GPU decoding and seek ACK are excluded. */
class DesktopBangumiVideoCodecRecoveryAssemblyTest {
    @Test fun actualVideoFailureKeepsSignedAudioAndChoosesHighestCompatibleRow(): Unit = runBlocking {
        for (blockHevc in listOf(false, true)) {
            Fixture(hevcSupported = blockHevc).useSuspending { f ->
                f.load()
                if (blockHevc) f.ui { f.owner.playback.desktopBlockVideoCodec("hev1") }
                val before = f.accepted(); val original = f.state(); val load = f.owner.captureLoadState()
                val preferences = f.preferences(); val calls = f.playCalls.get()
                val failure = f.failVideoAndQueue(evictPositive = true)
                assertEquals(PlayerFailureKind.DECODER, failure.kind); assertTrue(failure.videoDecoderFailed)
                assertTrue(failure.diagnostics.none { "Could not open codec." in it })
                f.awaitVideo(AVC_HIGH, 112)
                val ticket = f.preparations.single(); val caller = checkNotNull(ticket.caller)
                assertSame(caller, f.playbackJob()); withTimeout(8_000) { caller.join() }
                assertTrue(caller.isCompleted); assertFalse(caller.isCancelled)
                assertSame(before, ticket.source); assertEquals(failure.attemptId, ticket.failureAttemptId)
                val after = f.accepted()
                assertNotSame(before, after); assertEquals(before.sourceVersion, after.sourceVersion)
                assertSame(before.nativeSource.source.authorizationReceipt, after.nativeSource.source.authorizationReceipt)
                assertEquals(before.nativeSource.source.cookieHeader, after.nativeSource.source.cookieHeader)
                assertEquals(before.nativeSource.source.streamHeaders, after.nativeSource.source.streamHeaders)
                assertEquals(before.nativeSource.source.primaryAccountEpoch, after.nativeSource.source.primaryAccountEpoch)
                assertEquals(before.request, after.request)
                assertSame(load.currentRequest, f.owner.captureLoadState().currentRequest)
                assertEquals(load.currentLoadRequestToken, f.owner.captureLoadState().currentLoadRequestToken)
                assertTrue("av01" in f.owner.captureLoadState().blockedVideoCodecs)
                if (blockHevc) assertTrue("hev1" in f.owner.captureLoadState().blockedVideoCodecs)
                f.assertVideoPreservesAudio(original, AVC_HIGH, 112, preferences, calls)
                f.ui {
                    f.actor.drain(); f.actor.loaded()
                    @Suppress("UNCHECKED_CAST") val options = f.actor.native.loads.last()[4] as Map<String, String>
                    assertEquals("27.75", options["start"]); assertEquals("yes", options["pause"])
                    assertTrue(f.player.ownsSourceSnapshot(after.nativeSource))
                    // Real commands accepted after the operation-only caller completed.
                    f.owner.section.play(); assertFalse(f.player.state.value.paused)
                    f.owner.section.pause(); assertTrue(f.player.state.value.paused)
                    val first = assertNotNull(f.owner.section.seekToTracked(35_000))
                    val second = assertNotNull(f.owner.section.seekToTracked(27_750))
                    assertTrue(second.operationId > first.operationId)
                    assertTrue(f.player.ownsSourceSnapshot(after.nativeSource))
                    var admitted = false
                    assertTrue(f.owner.native.admitPlaybackDispatch(after) { admitted = true }); assertTrue(admitted)
                }
                // Command admission only; no synthetic native seek ACK is emitted.
                f.replyMode = "manual-avc"
                f.manualQuality(80)
                assertEquals(80, f.state().quality); assertEquals(AVC_LOW, f.state().playUrl)
                assertEquals(80, f.playQualities.last()); assertTrue(f.native.ownsPlaybackSource())
                assertEquals(preferences, f.preferences()); assertTrue(f.preferenceWrites.isEmpty())
            }
        }
    }

    @Test fun originalNearestHigherFallbackIsUsedWhenManualTargetHasNoLowerCompatibleRow(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(); f.replyMode = "nearest-higher"; f.manualQuality(64)
            assertEquals(64, f.state().quality); assertEquals(AV1_64, f.state().playUrl)
            f.ui { f.actor.drain(); f.actor.loaded() }
            val original = f.state(); val preferences = f.preferences(); val calls = f.playCalls.get()
            f.failVideoAndQueue(); f.awaitVideo(AVC_LOW, 80); f.playbackJob().join()
            f.assertVideoPreservesAudio(original, AVC_LOW, 80, preferences, calls)
            assertTrue("av01" in f.owner.captureLoadState().blockedVideoCodecs)
        }
    }

    @Test fun codecRecoveryAndNewNetworkDashRetainActualTemporaryAacAndCompatibleFamily(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load(); f.failAndQueue(); f.awaitAac(); f.playbackJob().join()
            f.ui { f.actor.drain(); f.actor.loaded() }
            val aac = f.state(); val preferences = f.preferences(); val calls = f.playCalls.get()
            assertEquals(AudioFallbackReason.DECODER_ERROR, aac.audioFallbackReason)
            f.failVideoAndQueue(message = "Error while decoding frame (hardware decoding)!\n")
            f.awaitVideo(AVC_HIGH, 112); f.playbackJob().join()
            f.assertVideoPreservesAudio(aac, AVC_HIGH, 112, preferences, calls)
            f.ui { f.actor.drain(); f.actor.loaded() }
            val before = f.accepted(); f.replyMode = "network-avc"
            f.failAndQueue(network = true)
            withTimeout(8_000) { while (f.playCalls.get() <= calls || f.owner.native.current() === before ||
                !f.native.ownsPlaybackSource()) delay(5) }
            withTimeout(8_000) { f.playbackJob().join() }
            f.assertVideoPreservesAudio(aac, AVC_NETWORK, 112, preferences, calls + 1, AAC_NETWORK)
            assertEquals(112, f.playQualities.last())
            assertEquals(AUDIO_QUALITY_AUTO, f.state().selectedAudioQuality)
            assertEquals(AUDIO_QUALITY_HI_RES, f.state().requestedAudioQuality)
            assertEquals(AudioFallbackReason.DECODER_ERROR, f.state().audioFallbackReason)
            assertEquals(AAC_NETWORK, f.state().audioUrl)
            assertNotSame(before, f.accepted())
            f.ui { f.actor.drain(); f.actor.loaded() }
            assertTrue(f.player.ownsSourceSnapshot(f.accepted().nativeSource))
        }
    }

    @Test fun refreshedMissingCodecFamilyOrDurlFailsWithoutPublishingAnotherFamily(): Unit = runBlocking {
        for (reply in listOf("network-no-avc", "network-durl")) {
            Fixture().useSuspending { f ->
                f.load(); f.failVideoAndQueue(); f.awaitVideo(AVC_HIGH, 112); f.playbackJob().join()
                f.ui { f.actor.drain(); f.actor.loaded() }
                val before = f.accepted(); val state = f.state(); val preferences = f.preferences()
                val published = f.publications.size; val calls = f.playCalls.get(); f.replyMode = reply
                f.failAndQueue(network = true)
                withTimeout(8_000) { while (f.playCalls.get() <= calls || !f.playbackJob().isCompleted ||
                    f.recovery.state.value.status != PlaybackStatus.Failed) delay(5) }
                assertSame(before, f.accepted()); assertEquals(published, f.publications.size)
                assertEquals(state.playUrl, f.state().playUrl); assertEquals(state.audioUrl, f.state().audioUrl)
                assertEquals(preferences, f.preferences()); assertTrue(f.preferenceWrites.isEmpty())
                assertEquals(calls + 1, f.playCalls.get()); assertEquals(112, f.playQualities.last())
                assertNotEquals(PlaybackStatus.Ready, f.recovery.state.value.status)
            }
        }
    }

    @Test fun actualReadyDoesNotResetSharedVideoAndNetworkRetryBudget(): Unit = runBlocking {
        Fixture(hevcSupported = true).useSuspending { f ->
            f.load(); f.failVideoAndQueue(); f.awaitVideo(HEVC_HIGH, 116); f.playbackJob().join()
            f.ui { f.actor.drain(); f.actor.loaded() }
            val calls = f.playCalls.get(); f.replyMode = "network-hevc"
            val parent = f.accepted(); f.failAndQueue(network = true)
            withTimeout(8_000) { while (f.playCalls.get() <= calls || f.owner.native.current() === parent ||
                !f.native.ownsPlaybackSource()) delay(5) }
            withTimeout(8_000) { f.playbackJob().join() }
            f.ui { f.actor.drain(); f.actor.loaded() }
            f.replyMode = "initial"; f.manualQuality(120)
            f.ui { f.actor.drain(); f.actor.loaded() }
            assertEquals(AV1, f.state().playUrl)
            val before = f.accepted(); val preparations = f.preparations.size
            val preferences = f.preferences(); val afterCalls = f.playCalls.get()
            val failure = f.failVideoAndQueue()
            withTimeout(8_000) { while (f.recovery.state.value.status != PlaybackStatus.Failed) delay(5) }
            assertTrue(failure.videoDecoderFailed); assertEquals(PlayerFailureKind.DECODER, failure.kind)
            assertSame(before, f.accepted()); assertEquals(preparations, f.preparations.size)
            assertEquals(AV1, f.state().playUrl)
            // Cached AVC candidates are genuinely available, so failure is the
            // shared original two-decoder-attempt bound after video+network.
            assertTrue(f.state().cachedDash!!.video.any { it.codecs.startsWith("avc") })
            assertEquals(afterCalls, f.playCalls.get()); assertEquals(preferences, f.preferences())
            assertTrue(f.preferenceWrites.isEmpty())
        }
    }

    @Test fun actualAccountTokenEpisodeManualPauseSeekAndCallerRetirementRejectPreparedCodec(): Unit = runBlocking {
        for (mutation in listOf("account", "request", "episode", "manual", "pause", "seek", "cancel")) {
            Fixture().useSuspending { f ->
                f.load(); val before = f.accepted(); val calls = f.playCalls.get(); val preferences = f.preferences()
                val count = f.publications.size
                f.beforePreparation = { ticket ->
                    assertSame(ticket.caller, f.playbackJob())
                    val old = f.owner.captureLoadState()
                    when (mutation) {
                        "account" -> {
                            val epoch = f.repository.sessionEpoch; val revision = f.repository.playbackAuthorizationRevision.value
                            assertTrue(f.sessions.setPlaybackAccountMid(42, epoch, f.gate::owns))
                            assertTrue(f.sessions.setPlaybackAccountMid(null, epoch, f.gate::owns))
                            assertEquals(epoch, f.repository.sessionEpoch)
                            assertTrue(f.repository.playbackAuthorizationRevision.value > revision)
                        }
                        "request" -> {
                            val ep = f.state().currentEpisode
                            assertTrue(f.owner.playback.beginDesktopPortraitLoad(PlaybackRequest.create(ep.bvid, ep.aid, ep.cid), f.native)
                                > old.currentLoadRequestToken)
                        }
                        "episode" -> {
                            val ep = f.state().seasonDetail.episodes!![1]
                            f.owner.playback.beginDesktopPortraitLoad(PlaybackRequest.create(ep.bvid, ep.aid, ep.cid), f.native)
                            assertEquals(ep.cid, f.owner.captureLoadState().currentRequest!!.cid)
                        }
                        "manual" -> {
                            val revision = f.vm.desktopPlaybackIntentRevision
                            f.vm.changeAudioQuality(AUDIO_QUALITY_HI_RES)
                            assertTrue(f.vm.desktopPlaybackIntentRevision > revision)
                        }
                        "pause" -> {
                            val paused = f.player.state.value.paused; val serials = f.player.capturePlaybackIntentSerials()
                            f.player.setPaused(!paused); f.player.setPaused(paused)
                            assertEquals(paused, f.player.state.value.paused); assertFalse(f.player.ownsPlaybackIntentSerials(serials))
                            assertTrue(f.player.ownsSourceSnapshot(before.nativeSource))
                        }
                        "seek" -> {
                            val serials = f.player.capturePlaybackIntentSerials()
                            val first = assertNotNull(f.player.seekToTracked(35.0)); val second = assertNotNull(f.player.seekToTracked(27.75))
                            assertTrue(second > first); assertFalse(f.player.ownsPlaybackIntentSerials(serials))
                            assertTrue(f.player.ownsSourceSnapshot(before.nativeSource))
                        }
                        "cancel" -> checkNotNull(ticket.caller).cancel("cancel exact linked codec recovery launch")
                    }
                }
                f.failVideoAndQueue()
                val ticket = withTimeout(8_000) { while (f.preparations.isEmpty()) delay(5); f.preparations.single() }
                withTimeout(8_000) { checkNotNull(ticket.caller).join() }
                if (mutation == "manual") {
                    withTimeout(8_000) { while (f.preferenceWrites.isEmpty()) delay(5) }
                    assertEquals(listOf(AUDIO_QUALITY_HI_RES), f.preferenceWrites.toList())
                } else {
                    if (mutation == "account") {
                        assertNull(f.owner.native.current(), mutation)
                        assertFalse(f.repository.isPlaybackReceiptCurrent(checkNotNull(before.nativeSource.source.authorizationReceipt)))
                        val snapshot = assertNotNull(f.player.currentSourceSnapshot())
                        assertEquals(before.nativeSource.sourceVersion, snapshot.sourceVersion)
                        assertEquals(before.nativeSource.source, snapshot.source); assertSame(before, f.publications.last())
                    } else assertSame(before, f.owner.native.current(), mutation)
                    assertEquals(preferences, f.preferences()); assertTrue(f.preferenceWrites.isEmpty())
                    assertEquals(count, f.publications.size)
                }
                assertEquals(AV1, f.state().playUrl, mutation)
                assertTrue(f.publications.none { it.nativeSource.source.videoUrl == AVC_HIGH }, mutation)
                assertEquals(calls, f.playCalls.get(), mutation)
                if (mutation == "cancel") assertTrue(checkNotNull(ticket.caller).isCancelled)
            }
        }
    }

    @Test fun actualAudioRendererAuthDrmUnknownAndUnsupportedLogsNeverPromoteVideoRecovery(): Unit = runBlocking {
        data class Observation(val prefix: String, val message: String?, val code: Int = -13,
            val earlierVideo: Boolean = false)
        val observations = listOf(
            Observation("ad", "Could not open codec.\n"),
            Observation("ad_lavc", "Could not initialize decoder\n"),
            Observation("vo/gpu", "renderer failed\n"),
            Observation("vd_lavc", "Could not open codec.\n"), // filter name is not actual client domain
            Observation("vd", "Missing device context.\n"),
            Observation("vd", "Could not open codec. trailing text\n"),
            Observation("unknown", null),
            Observation("vd", "authentication forbidden\n", earlierVideo = true),
            Observation("vd", "DRM encrypted stream\n", earlierVideo = true),
            Observation("vd", "Unsupported hwdec: fixture\n", earlierVideo = true),
            Observation("vd", "Could not open codec.\n", -17),
            Observation("vd", "Could not open codec.\n", -18),
            Observation("vd", "Could not open codec.\n", -19),
            Observation("vd", "Could not open codec.\n", -14),
            Observation("vd", "Could not open codec.\n", -15),
        )
        for (observation in observations) {
            Fixture(hiRes = false).useSuspending { f ->
                f.load(); val before = f.accepted(); val calls = f.playCalls.get(); val preferences = f.preferences()
                val failure = f.uiResult {
                    if (observation.earlierVideo) f.diagnostic("vd", "Could not open codec.\n")
                    observation.message?.let { f.diagnostic(observation.prefix, it) }
                    val failure = f.actor.fail(observation.code)
                    assertSame(failure, f.player.state.value.failure); assertFalse(failure.videoDecoderFailed)
                    assertFalse(f.component.isCurrentVideoCodecFailure(f.state(), before, failure))
                    f.component.queuePlaybackFailure(f.state(), before, failure, f.vm.desktopPlaybackIntentRevision,
                        desktopOriginalNativeFailure(failure, false), failure)
                    failure
                }
                assertEquals(observation.code, failure.nativeCode)
                assertEquals(PlaybackStatus.Failed, f.recovery.state.value.status)
                assertSame(before, f.accepted()); assertTrue(f.preparations.isEmpty())
                assertEquals(calls, f.playCalls.get()); assertEquals(preferences, f.preferences())
                assertTrue(f.preferenceWrites.isEmpty())
            }
        }
    }

    @Test fun actualNextLoadResetsVideoEvidenceAndWrongTerminalEntryCannotPublishFailure(): Unit = runBlocking {
        Fixture().useSuspending { f ->
            f.load()
            val first = f.uiResult {
                f.diagnostic("vd", "Decoder init failed for av1\n")
                f.endFile(999, -13)
                assertNull(f.player.state.value.failure) // END_FILE entry does not match START_FILE 11
                f.actor.fail(-13)
            }
            assertTrue(first.videoDecoderFailed); assertEquals(PlayerFailureKind.DECODER, first.kind)
            f.manualQuality(120)
            f.ui { f.actor.drain(); f.actor.loaded(); assertNull(f.player.state.value.failure) }
            val next = f.uiResult { f.diagnostic("ad", "Could not open codec.\n"); f.actor.fail(-13) }
            assertFalse(next.videoDecoderFailed); assertTrue(next.attemptId > first.attemptId)
            assertSame(next, f.player.state.value.failure)
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T
    private inner class Fixture(private val hevcSupported: Boolean = false, private val hiRes: Boolean = true) {
        val directory = Files.createTempDirectory("pgc-codec-real-owner-").toRealPath()
        private val createdAbsolute = directory.toAbsolutePath().normalize()
        private val createdReal = directory.toRealPath()
        private val tempAbsolute = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        private val tempReal = tempAbsolute.toRealPath()
        val sessions = DesktopSessionStore.temporary()
        val repository = DesktopRepository(sessions)
        private val parentJob = SupervisorJob()
        private val failures = CopyOnWriteArrayList<Throwable>()
        private val parentScope = CoroutineScope(parentJob + Dispatchers.Swing + CoroutineExceptionHandler { _, error -> failures += error })
        private val slot = DesktopOriginalVideoRootOwnerSlot(repository, parentScope, parentJob::isActive)
        lateinit var gate: DesktopOriginalVideoRootGate
        lateinit var owner: DesktopOriginalVideoOwnerAssembly
        val readOnlyFacts = CopyOnWriteArrayList<Triple<BangumiDetail, BangumiEpisode, Int>>()
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
        var replyMode = "initial"
        private val audioPreference = if (hiRes) AUDIO_QUALITY_HI_RES else AUDIO_QUALITY_AUTO
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
            override fun isHevcSupported() = hevcSupported
            override fun isAv1Supported() = true
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
                                    playJson(replyMode)
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
                "default_audio_quality" to JsonPrimitive(audioPreference), "audio_quality" to JsonPrimitive(audioPreference),
                "video_codec" to JsonPrimitive("av01"),
                "video_second_codec" to JsonPrimitive(if (hevcSupported) "hev1" else "avc1")))
            context.store.update("settings", mapOf("audio_quality_preference" to JsonPrimitive(audioPreference),
                "video_codec_preference" to JsonPrimitive("av01"),
                "video_second_codec_preference" to JsonPrimitive(if (hevcSupported) "hev1" else "avc1")))
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
                        "enabledPlayerPlugins" -> emptyList<com.android.purebilibili.core.plugin.DesktopSingleSessionPlayerPlugin>()
                        else -> error("Unexpected plugin effect ${method.name}") }
                } as DesktopOriginalVideoOwnerPlugins
                val effects = DesktopOriginalVideoOwnerEffects(mini, unused(), unused(), network,
                    object : DesktopOriginalVideoOwnerCache {
                        override fun get(bvid: String, cid: Long): PlayUrlData? = null
                        override fun invalidate(bvid: String, cid: Long) = error("No cache invalidation")
                    }, unused(), unused(), unused(), MutableStateFlow(false))
                media = DesktopOriginalVideoRootMediaFactory(repository, cache, gate, { owner }, {})
                requests = DesktopOriginalVideoOwnerRequestFactory(repository, DesktopLoginRepository(repository), epoch,
                    gate.scope, gate::owns, gate::commitEntry, { PlayerPreferences(defaultAudioQuality = audioPreference, lastSelectedAudioQuality = audioPreference) }, capabilities,
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
                    storyFeed = { _, _, _, _ -> error("Unexpected Story feed in DesktopBangumiVideoCodecRecoveryAssemblyTest fixture") },
                    captureRequest = { requests.captureBinding(it.captureLoadState()) },
                    captureMetadataRequest = { value ->
                        require(value === owner)
                        requests.captureBinding(value.captureLoadState())
                    },
                    createPageRequestRepository = { value, binding ->
                        require(value === owner)
                        requestRepository(binding).also { raw ->
                            raw.installMediaPreparation {
                                // Same explicit fixed-capability headless port as
                                // this fixture's ordinary Invocation; no physical readiness claim.
                                currentCoroutineContext().ensureActive()
                                if (!gate.owns()) throw CancellationException("Fixture preparation entry retired")
                            }
                        }
                    },
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
                    }, native::commit, native::owns, native::ownsPlaybackSource, network, { false },
                    publishReadOnlyDetail = { detail, episode, index, _, postBegin, isLoadCurrent ->
                        currentCoroutineContext().ensureActive(); native.assertCurrent()
                        val launchCaller = native.captureInitialDetailLaunchCaller()
                        if (!isLoadCurrent(launchCaller)) throw CancellationException("Fixture readonly PGC load replaced")
                        require(detail.episodes?.getOrNull(index) === episode)
                        val binding = if (postBegin) native.capturedPlaybackBinding()
                            else (owner.invocations.requireRequestRepository() as DesktopOriginalVideoOwnerRequestRepository).binding
                        binding.assertCurrent()
                        if (!binding.admitCurrentMutation {
                                native.assertCurrent()
                                if (!isLoadCurrent(launchCaller)) throw CancellationException("Fixture readonly PGC load replaced")
                                readOnlyFacts += Triple(detail, episode, index)
                            }) throw CancellationException("Fixture readonly PGC admission retired")
                    },
                    prepareMedia = {
                        // Existing fixed-capability headless fixture. Use the actual
                        // captured PGC Binding and required raw stage, not fake NativeReady.
                        currentCoroutineContext().ensureActive(); native.assertCurrent()
                        val binding = native.capturedPlaybackBinding()
                        requestRepository(binding).also { raw ->
                            raw.installMediaPreparation {
                                currentCoroutineContext().ensureActive(); native.assertCurrent(); binding.assertCurrent()
                            }
                        }.awaitMediaPreparation()
                        currentCoroutineContext().ensureActive(); native.assertCurrent(); binding.assertCurrent()
                    },
                    captureLoadCaller = native::captureInitialDetailLaunchCaller)
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
                assertEquals(audioPreference, state().selectedAudioQuality)
                assertEquals(audioPreference, state().requestedAudioQuality)
                assertEquals(if (hiRes) HI_RES else AAC, state().audioUrl)
                assertEquals(AV1, state().playUrl)
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
        suspend fun failVideoAndQueue(message: String = "Could not open codec.\n", evictPositive: Boolean = false): PlayerFailure = uiResult {
            diagnostic("vd", message)
            if (evictPositive) repeat(40) { diagnostic("fixture", "bounded native observation $it\n") }
            val failure = actor.fail(-13)
            assertSame(failure, player.state.value.failure)
            assertEquals(-13, failure.nativeCode); assertEquals(PlayerFailureKind.DECODER, failure.kind)
            assertTrue(failure.videoDecoderFailed)
            val state = state(); val expected = accepted()
            assertTrue(component.failureSourceCurrent(state, expected, failure, vm.desktopPlaybackIntentRevision))
            assertTrue(component.isCurrentVideoCodecFailure(state, expected, failure))
            assertFalse(component.isCurrentPremiumAudioFailure(state, expected, failure))
            component.queuePlaybackFailure(state, expected, failure, vm.desktopPlaybackIntentRevision,
                desktopOriginalNativeFailure(failure, false), failure)
            failure
        }
        suspend fun awaitVideo(url: String, quality: Int) = withTimeout(8_000) {
            while ((vm.uiState.value as? BangumiPlayerState.Success)?.playUrl != url || state().quality != quality ||
                recovery.state.value.status != PlaybackStatus.Ready || !native.ownsPlaybackSource()) delay(5)
        }
        fun assertVideoPreservesAudio(original: BangumiPlayerState.Success, url: String, quality: Int,
            expectedPreferences: JsonObject, expectedCalls: Int, expectedAudio: String? = original.audioUrl) {
            val actual = state(); val source = accepted().nativeSource.source
            assertEquals(url, actual.playUrl); assertEquals(quality, actual.quality)
            assertEquals(original.currentEpisode, actual.currentEpisode)
            assertEquals(original.seasonDetail.seasonId, actual.seasonDetail.seasonId)
            assertEquals(original.requestedAudioQuality, actual.requestedAudioQuality)
            assertEquals(original.selectedAudioQuality, actual.selectedAudioQuality)
            assertEquals(original.audioFallbackReason, actual.audioFallbackReason)
            assertEquals(expectedAudio, actual.audioUrl); assertEquals(url, source.videoUrl)
            assertEquals(actual.audioUrl, source.audioUrl)
            assertEquals("https://www.bilibili.com/cheese/play/ep41", source.referer)
            assertEquals(27_750L, owner.section.currentPosition); assertFalse(owner.section.playWhenReady)
            assertEquals(expectedPreferences, preferences()); assertTrue(preferenceWrites.isEmpty())
            assertEquals(expectedCalls, playCalls.get())
        }
        suspend fun manualQuality(quality: Int) {
            val before = accepted()
            val job = uiResult { vm.changeQuality(quality); launchedJobs.last() }
            withTimeout(8_000) { job.join() }
            assertFalse(job.isCancelled)
            assertNotSame(before, accepted()); assertTrue(native.ownsPlaybackSource())
        }
        fun endFile(entry: Long, code: Int) = Memory(24).use { data ->
            data.clear(); data.setInt(0, 4); data.setInt(4, code); data.setLong(8, entry)
            PremiumRecoveryActor::class.java.getDeclaredMethod("event", Integer.TYPE, Pointer::class.java)
                .apply { isAccessible = true }.invoke(actor, 7, data)
            Unit
        }
        fun diagnostic(module: String, content: String) {
            fun text(value: String): Memory = value.toByteArray(Charsets.UTF_8).let { bytes ->
                Memory(bytes.size + 1L).apply { write(0, bytes, 0, bytes.size); setByte(bytes.size.toLong(), 0) }
            }
            text(module).use { prefix -> text("error").use { level -> text(content).use { message ->
                Memory(32).use { data ->
                    data.clear(); data.setPointer(0, prefix); data.setPointer(8, level); data.setPointer(16, message)
                    data.setInt(24, 20)
                    PremiumRecoveryActor::class.java.getDeclaredMethod("event", Integer.TYPE, Pointer::class.java)
                        .apply { isAccessible = true }.invoke(actor, 2, data)
                }
            } } }
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
            check(createdAbsolute != tempAbsolute && createdAbsolute.fileName.toString().startsWith("pgc-codec-real-owner-"))
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
            return """{"code":0,"result":{"season_id":19,"season_type":10,"title":"Synthetic course","cover":"https://media.invalid/cover.jpg","up_info":{"mid":7,"uname":"Fixture UP","avatar":"https://media.invalid/face.jpg"},"section":[],"episodes":[{"id":41,"aid":101,"cid":104,"bvid":"BV1GJ411x7h7","title":"One","cover":"https://media.invalid/cover.jpg","duration":60000},{"id":42,"aid":102,"cid":105,"bvid":"BV17x411w7KC","title":"Two","duration":60000}],"user_status":{"vip":1,"follow":0,"follow_status":0}}}"""
        }
    }
    companion object {
        private const val AV1 = "https://media.invalid/av1_120.m4s?synthetic=1"
        private const val AV1_64 = "https://media.invalid/av1_64.m4s?synthetic=1"
        private const val AVC_HIGH = "https://media.invalid/avc_112.m4s?synthetic=1"
        private const val AVC_LOW = "https://media.invalid/avc_80.m4s?synthetic=1"
        private const val HEVC_HIGH = "https://media.invalid/hevc_116.m4s?synthetic=1"
        private const val AVC_NETWORK = "https://media.invalid/avc_112.m4s?synthetic=2"
        private const val HEVC_NETWORK = "https://media.invalid/hevc_116.m4s?synthetic=2"
        private const val AAC = "https://media.invalid/pgc-audio.m4s?synthetic=1"
        private const val AAC_NETWORK = "https://media.invalid/pgc-audio.m4s?synthetic=2"
        private const val HI_RES = "https://media.invalid/hires.flac?synthetic=1"
        private fun response(request: Request, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("memory").body(body.toResponseBody("application/json".toMediaType())).build()
        private fun videoRow(id: Int, url: String, codec: String, bandwidth: Int = 100000): String =
            """{"id":$id,"baseUrl":"$url","bandwidth":$bandwidth,"mime_type":"video/mp4","codecs":"$codec","width":3840,"height":2160,"frameRate":"30","codecid":${if (codec.startsWith("av01")) 13 else if (codec.startsWith("hev")) 12 else 7},"segment_base":{"initialization":"0-31","index_range":"32-63"}}"""
        private fun playJson(mode: String): String {
            if (mode == "network-durl") return """{"code":0,"result":{"quality":112,"timelength":60000,"accept_quality":[112,80],"durl":[{"order":1,"length":60000,"size":1000,"url":"https://media.invalid/legacy.flv?synthetic=2"}]}}"""
            val rows = when (mode) {
                "nearest-higher" -> listOf(videoRow(64, AV1_64, "av01.0.08M.08"), videoRow(80, AVC_LOW, "avc1.640028"))
                "manual-avc" -> listOf(videoRow(80, AVC_LOW, "avc1.640028"))
                "network-no-avc" -> listOf(videoRow(120, AV1, "av01.0.08M.08"), videoRow(116, HEVC_NETWORK, "hev1.1.6.L153.B0"))
                "network-avc", "network-hevc" -> listOf(
                    videoRow(120, "https://media.invalid/av1_120.m4s?synthetic=2", "av01.0.08M.08"),
                    videoRow(116, HEVC_NETWORK, "hev1.1.6.L153.B0"),
                    videoRow(112, AVC_NETWORK, "avc1.640033"), videoRow(80, AVC_LOW, "avc1.640028"))
                "initial" -> listOf(
                    videoRow(120, AV1, "av01.0.08M.08"),
                    videoRow(112, "https://media.invalid/av1_112.m4s?synthetic=1", "av01.0.08M.08"),
                    videoRow(116, HEVC_HIGH, "hev1.1.6.L153.B0"),
                    videoRow(116, "https://media.invalid/unknown_116.m4s?synthetic=1", "vp09.00.51.08"),
                    videoRow(112, "https://media.invalid/avc_112_highrate.m4s?synthetic=1", "avc1.640033", 300000),
                    videoRow(112, AVC_HIGH, "avc1.640033"), videoRow(80, AVC_LOW, "avc1.640028"),
                    videoRow(125, "https://media.invalid/hdr_125.m4s?synthetic=1", "avc1.640033"),
                    videoRow(126, "https://media.invalid/dv_126.m4s?synthetic=1", "hev1.2.6.L153.B0"))
                else -> error("Unknown raw protocol mode $mode")
            }
            val quality = when (mode) { "nearest-higher" -> 64; "manual-avc" -> 80; else -> 120 }
            val aacUrl = if (mode.startsWith("network-")) AAC_NETWORK else AAC
            val aac = """{"id":30280,"baseUrl":"$aacUrl","bandwidth":192000,"mime_type":"audio/mp4","codecs":"mp4a.40.2","segment_base":{"initialization":"0-31","index_range":"32-63"}}"""
            return """{"code":0,"result":{"quality":$quality,"timelength":60000,"accept_quality":[120,116,112,80,64],"accept_description":["4K","3K","1080P+","1080P","720P"],"dash":{"duration":60,"video":[${rows.joinToString(",")}],"audio":[$aac],"flac":{"audio":{"id":30251,"baseUrl":"$HI_RES","bandwidth":800000,"mime_type":"audio/flac","codecs":"fLaC","segment_base":{"initialization":"0-31","index_range":"32-63"}}}}}}"""
        }
    }
}
