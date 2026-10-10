package com.bilipai.desktop.ui

import com.android.purebilibili.core.cooldown.PlaybackCooldownManager
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.DesktopOriginalFavoriteFolderProtocol
import com.android.purebilibili.data.repository.DesktopOriginalVideoEngagementProtocol
import com.android.purebilibili.feature.video.playback.loader.PlaybackRequest
import com.android.purebilibili.feature.video.playback.session.PlaybackSessionStore
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.android.purebilibili.feature.video.usecase.VideoLoadResult
import com.android.purebilibili.feature.video.usecase.VideoPlaybackUseCase
import com.android.purebilibili.feature.video.viewmodel.*
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState.RelatedLoadState
import com.bilipai.desktop.data.*
import com.bilipai.desktop.player.*
import com.bilipai.desktop.player.PlaybackSource
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.lang.reflect.Proxy
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Real generated UseCase and same-player event admission, with the existing
 * memory-only MPV ABI. No DLL, HWND, socket, SDK or user account is opened. */
class DesktopWindowsRelatedVideosRegressionTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private fun playData() = PlayUrlData(quality = 80, acceptQuality = listOf(80), dash = Dash(
        video = listOf(DashVideo(id = 80, baseUrl = "https://fixture.invalid/video", codecs = "avc1.640028")),
        audio = listOf(DashAudio(id = 30280, baseUrl = "https://fixture.invalid/audio", codecs = "mp4a"))))

    private inner class MainLoadFixture : AutoCloseable {
        val folder = Files.createTempDirectory("related-main-load-")
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        val info = ViewInfo(bvid = "BVfixture", aid = 17, cid = 70, title = "Fixture")
        var relatedCalls = 0
        var detailCalls = 0
        val repository = object : DesktopOriginalVideoLoadRepository {
            override suspend fun getVideoInfoOnly(bvid: String, aid: Long, requestedCid: Long): Result<ViewInfo> {
                detailCalls++; return Result.success(info)
            }
            override suspend fun getInitialPlayUrlData(bvid: String, cid: Long, targetQuality: Int, audioLang: String?) = playData()
            override suspend fun getVideoDetails(bvid: String, aid: Long, requestedCid: Long, targetQuality: Int?, audioLang: String?): Result<Pair<ViewInfo, PlayUrlData>> {
                detailCalls++; return Result.success(info to playData())
            }
            override suspend fun getRelatedVideos(bvid: String): List<RelatedVideo> {
                relatedCalls++; error("Related list must not enter the main playback load")
            }
            override suspend fun getRelatedVideosResult(bvid: String): Result<List<RelatedVideo>> {
                relatedCalls++; error("Related result must not enter the main playback load")
            }
            override suspend fun getPlayUrlData(bvid: String, cid: Long, qn: Int, audioLang: String?) = error("No second playback lookup")
            override suspend fun getPlaybackNavInfo(): Result<NavData> = error("No account lookup")
            override fun isPlaybackLoggedIn() = false
            override fun isPlaybackVip() = false
            override fun isUsingDedicatedPlaybackAccount() = false
            override fun isAppApiCoolingDown() = false
        }
        val capabilities = object : DesktopOriginalVideoPlaybackCapabilities {
            override fun isHevcSupported() = false
            override fun isAv1Supported() = false
            override fun isHdrSupported() = false
            override fun isDolbyVisionSupported() = false
            override fun isDolbyAtmosAudioSupported() = false
            override fun isDolbySoftwareAudioDecoderRequired() = false
        }
        val useCase = VideoPlaybackUseCase(DesktopOriginalVideoPlaybackUseCaseEnvironment(
            context, repository, unused(), unused(), capabilities, unused(),
            { error("No native volume during metadata load") }, { emptyMap() },
            { error("No primary account mutation") }, { false }, { _, _, _, _ -> }, { true }))
        suspend fun load(bvid: String, cid: Long) = useCase.loadVideo(bvid = bvid, aid = 17, cid = cid,
            defaultQuality = 80, audioQualityPreference = 30280, videoCodecPreference = "H264",
            videoSecondCodecPreference = "H264", audioLang = null, playWhenReady = true,
            isAv1SupportedOverride = false, isHdrSupportedOverride = false,
            isDolbyVisionSupportedOverride = false, onProgress = {})
        init { PlaybackCooldownManager.clearAll() }
        override fun close() { PlaybackCooldownManager.clearAll(); folder.toFile().deleteRecursively() }
    }

    @Test fun bothActualBootstrapRoutesFinishWithoutCallingRelatedRepository() = runBlocking<Unit> {
        for ((bvid, cid) in listOf("BVfixture" to 70L, "av17" to 0L)) {
            MainLoadFixture().use { f ->
                val result = assertIs<VideoLoadResult.Success>(withTimeout(2_000) { f.load(bvid, cid) })
                assertEquals(1, f.detailCalls); assertEquals(0, f.relatedCalls)
                assertEquals("https://fixture.invalid/video", result.playUrl)
                assertEquals("https://fixture.invalid/audio", result.audioUrl)
                assertTrue(result.related.isEmpty()); assertEquals(0, PlaybackCooldownManager.getConsecutiveFailures())
            }
        }
    }

    private class Publication(private val delegate: DesktopPlaybackPublication? = null,
        initialEpoch: Long = 7L) : DesktopPlaybackPublication {
        @Volatile var epoch = initialEpoch
        val depth = ThreadLocal.withInitial { 0 }
        override val requiresAccountReceipt = true
        override fun isCurrent(source: PlaybackSource) = source.authorizationReceipt?.accountEpoch == epoch &&
            (delegate?.isCurrent(source) ?: true)
        override fun <T> admit(source: PlaybackSource, stillOwned: () -> Boolean, block: () -> T): T {
            if (!isCurrent(source) || !stillOwned()) throw CancellationException("Fixture receipt retired")
            fun instrument(): T {
                val before = depth.get(); depth.set(before + 1)
                return try { block() } finally { depth.set(before) }
            }
            return if (delegate != null) delegate.admit(source, stillOwned, ::instrument) else instrument()
        }
        override fun calls(delegate: Call.Factory, source: PlaybackSource, stillOwned: () -> Boolean, callerJob: Job?) =
            error("The memory fixture never opens HTTP")
    }

    private class NativeFixture(realPublication: DesktopPlaybackPublication? = null,
        receipt: DesktopPlaybackAuthorizationReceipt = DesktopPlaybackAuthorizationReceipt(7, 1)) : AutoCloseable {
        val player = MpvPlayer()
        val publication = Publication(realPublication, receipt.accountEpoch)
        val initialJob = Job()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val versions = MutableStateFlow<Long?>(null)
        @Volatile var entry = true
        var afterEventAdmission: () -> Unit = {}
        val owner = DesktopOriginalVideoNativeOwner(player, publication, { publication.epoch }, { false }, { entry },
            { block -> if (!entry) false else { block(); true } },
            { versions.value = it.sourceVersion }, { _, _ -> error("No byte transport") })
        val actor: PremiumRecoveryActor
        val original: DesktopOriginalVideoAcceptedPublication
        init {
            player.loadVersioned(PlaybackSource("file:///C:/related-bootstrap.avi"))
            actor = PremiumRecoveryActor(player)
            original = owner.publish(PlaybackRequest.create("BVfixture", 17, 70),
                PlaybackSource("https://fixture.invalid/video", "https://fixture.invalid/audio", startPaused = true,
                    authorizationReceipt = receipt),
                player.currentSourceVersion, initialJob) { true }
            actor.drain(); actor.loaded(); check(owner.isCurrent(original))
        }
        val control = DesktopOriginalMpvSectionControl(player, { owner.current()?.sourceVersion }, { entry },
            { block -> owner.current()?.let { owner.admitPlaybackDispatch(it, block) } ?: false },
            { false }, {}, { error("No ended-source replay") }, { _, _, _, _ -> }, scope, versions,
            { block ->
                val admitted = owner.current()?.let { owner.admitPlaybackDispatch(it, block) } ?: false
                afterEventAdmission()
                admitted
            })
        fun replaceAudioAtSameVersion(): DesktopOriginalVideoAcceptedPublication {
            val failure = actor.fail(-14)
            val media = owner.acceptedMedia({ captured ->
                assertEquals(0, publication.depth.get())
                object : DesktopOriginalVideoMediaPort {
                    override fun withPlaybackIntent(startPositionMs: Long, playWhenReady: Boolean, action: () -> Unit) = action()
                    override fun prepareLegacyDash(videoUrl: String, audioUrl: String?, cdnCacheKeysByUrl: Map<String, String>) =
                        captured.nativeSource.source.copy(videoUrl = videoUrl, audioUrl = audioUrl)
                    override fun prepareAdaptiveDash(source: com.android.purebilibili.core.player.dash.AdaptiveDashPlaybackSource,
                        cdnCacheKeysByUrl: Map<String, String>): PlaybackSource? = error("No adaptive preparation")
                    override fun prepareProgressive(url: String) = error("No progressive preparation")
                    override fun accept(source: PlaybackSource) = error("Only real NativeOwner publishes")
                }
            }, DesktopOriginalNativeRecoveryTicket(original, failure.attemptId, initialJob)) { true }
            val replacement = media.prepareLegacyDash(original.nativeSource.source.videoUrl,
                "https://fixture.invalid/second-audio", emptyMap())
            media.withPlaybackIntent(0, false) { media.accept(replacement) }
            return assertNotNull(owner.current()).also {
                assertEquals(original.sourceVersion, it.sourceVersion); assertNotSame(original, it)
            }
        }
        override fun close() { scope.cancel(); actor.close(); owner.close(); initialJob.cancel(); player.close() }
    }

    @Test fun actualSourceCallbackLeavesAdmissionBeforeLaunchingItsContinuation() = runBlocking<Unit> {
        NativeFixture().use { f ->
            val inside = CompletableDeferred<Long>()
            val outside = CompletableDeferred<Long>()
            val listener = object : DesktopOriginalMpvOverlayControl.Listener {
                override fun onSourceTransition(sourceVersion: Long) {
                    assertTrue(f.publication.depth.get() > 0); inside.complete(sourceVersion)
                }
                override fun onSourceTransitionAfterAdmission(sourceVersion: Long, source: OwnedPlaybackSourceSnapshot) {
                    assertEquals(0, f.publication.depth.get())
                    assertTrue(f.owner.isCurrent(f.original))
                    assertEquals(f.original.nativeSource.sourceVersion, source.sourceVersion)
                    assertEquals(f.original.nativeSource.source, source.source)
                    outside.complete(sourceVersion)
                }
            }
            f.control.addListener(listener)
            assertEquals(f.original.sourceVersion, withTimeout(2_000) { inside.await() })
            assertEquals(f.original.sourceVersion, withTimeout(2_000) { outside.await() })
            f.control.removeListener(listener)
        }
    }

    @Test fun retirementAfterActualEventAdmissionCannotRunTheOldPostCallback() = runBlocking<Unit> {
        NativeFixture().use { f ->
            val inside = CompletableDeferred<Unit>()
            var after = 0
            f.afterEventAdmission = { f.entry = false }
            val listener = object : DesktopOriginalMpvOverlayControl.Listener {
                override fun onSourceTransition(sourceVersion: Long) { inside.complete(Unit) }
                override fun onSourceTransitionAfterAdmission(sourceVersion: Long, source: OwnedPlaybackSourceSnapshot) { after++ }
            }
            f.control.addListener(listener)
            withTimeout(2_000) { inside.await() }
            yield(); assertEquals(0, after)
            f.control.removeListener(listener)
        }
    }

    @Test fun sameVersionNativeReplacementIsANewPostAdmissionSourceTransition() = runBlocking<Unit> {
        NativeFixture().use { f ->
            val transitions = CopyOnWriteArrayList<OwnedPlaybackSourceSnapshot>()
            val listener = object : DesktopOriginalMpvOverlayControl.Listener {
                override fun onSourceTransitionAfterAdmission(sourceVersion: Long, source: OwnedPlaybackSourceSnapshot) {
                    assertEquals(0, f.publication.depth.get())
                    val current = assertNotNull(f.owner.current()).nativeSource
                    assertEquals(current.sourceVersion, source.sourceVersion)
                    assertEquals(current.source, source.source)
                    transitions += source
                }
            }
            f.control.addListener(listener)
            withTimeout(2_000) { while (transitions.size < 1) delay(1) }
            assertNotSame(f.player.currentSourceSnapshot(), f.player.currentSourceSnapshot())
            for (speed in listOf("1.25", "1.5")) {
                f.actor.native.mpv_set_property_string(com.sun.jna.Pointer(1), "speed", speed)
                f.actor.loaded(); yield()
                assertEquals(1, transitions.size, "Fresh wrappers for the same source are not new transitions")
            }
            val child = f.replaceAudioAtSameVersion()
            f.actor.drain(); f.actor.loaded()
            withTimeout(2_000) { while (transitions.size < 2) delay(1) }
            assertEquals(listOf(f.original.sourceVersion, child.sourceVersion), transitions.map { it.sourceVersion })
            assertEquals(listOf(f.original.nativeSource.source, child.nativeSource.source), transitions.map { it.source })
            f.control.removeListener(listener)
        }
    }

    private data class Capture(val source: DesktopOriginalVideoRelatedRequestSource, val job: Job)

    /** Only DTO inputs and asynchronous port results are controlled. Loading,
     * retry, native admission, source facet and playlist orchestration run in
     * the real full VM, repository view and invocation implementation. */
    private inner class VmFixture(val wifi: Boolean = false) : AutoCloseable {
        val native = NativeFixture()
        val folder = Files.createTempDirectory("related-full-vm-")
        val context = DesktopPluginContext(DesktopPluginStore(folder))
        val captures = CopyOnWriteArrayList<Capture>()
        val relatedCalls = CopyOnWriteArrayList<DesktopOriginalVideoAcceptedPublication>()
        val preloadDetails = CopyOnWriteArrayList<DesktopOriginalVideoAcceptedPublication>()
        val preloadPlayUrls = CopyOnWriteArrayList<DesktopOriginalVideoAcceptedPublication>()
        val cacheReads = CopyOnWriteArrayList<Pair<String, Long>>()
        val playlistWrites = CopyOnWriteArrayList<List<PlaylistItem>>()
        var answer: suspend () -> Result<List<RelatedVideo>> = { Result.success(emptyList()) }
        var preloadAnswer: suspend () -> Result<Pair<ViewInfo, PlayUrlData>> = {
            Result.success(ViewInfo(bvid = "BVrelated", aid = 18, cid = 80) to playData())
        }
        fun current() = native.entry && native.publication.epoch == 7L
        fun commit(block: () -> Unit): Boolean = if (!current()) false else { block(); true }
        private fun outsideAdmission() { assertEquals(0, native.publication.depth.get()) }
        private suspend fun requestSource(): DesktopOriginalVideoAcceptedPublication {
            outsideAdmission()
            val facet = assertNotNull(currentCoroutineContext()[DesktopOriginalVideoRelatedRequestSource])
            assertTrue(facet.stillOwned()); assertTrue(native.owner.isCurrent(facet.expected))
            return facet.expected
        }
        private val raw = object : DesktopOriginalVideoOwnerRepository by unused<DesktopOriginalVideoOwnerRepository>() {
            override suspend fun getRelatedVideos(bvid: String): List<RelatedVideo> = error("Use the Result-preserving actual related path")
            override suspend fun getRelatedVideosResult(bvid: String): Result<List<RelatedVideo>> {
                val source = requestSource(); assertEquals(source.request.bvid, bvid)
                relatedCalls += source
                // Deliberately no post-result fixture guard: the real VM must
                // reject even a non-cooperative late protocol completion.
                return answer()
            }
            override suspend fun getVideoDetails(bvid: String, aid: Long, requestedCid: Long,
                targetQuality: Int?, audioLang: String?): Result<Pair<ViewInfo, PlayUrlData>> {
                assertEquals("BVrelated", bvid); preloadDetails += requestSource()
                return preloadAnswer()
            }
            override suspend fun getPlayUrlData(bvid: String, cid: Long, qn: Int, audioLang: String?): PlayUrlData? {
                assertEquals("BVrelated", bvid); assertEquals(80L, cid)
                preloadPlayUrls += requestSource(); return playData()
            }
            override suspend fun refreshVipStatusForPreferredQualityIfNeeded(isLoggedIn: Boolean, cachedIsVip: Boolean,
                storedQuality: Int, autoHighestEnabled: Boolean) = cachedIsVip
            override fun isPlaybackLoggedIn() = false
            override fun isPlaybackVip() = false
            override fun isUsingDedicatedPlaybackAccount() = false
            override fun isAppApiCoolingDown() = false
        }
        private val media = unused<DesktopOriginalVideoMediaPort>()
        private val status = object : DesktopOriginalVideoPlaybackStatus {
            override fun isPlaybackLoggedIn() = false
            override fun isPlaybackVip() = false
            override fun isUsingDedicatedPlaybackAccount() = false
            override fun isAppApiCoolingDown() = false
        }
        val invocations = DesktopOriginalVideoPlaybackInvocationPorts(native.scope, ::current, {
            outsideAdmission()
            val caller = currentCoroutineContext().job
            val facet = assertNotNull(currentCoroutineContext()[DesktopOriginalVideoRelatedRequestSource])
            captures += Capture(facet, caller)
            DesktopOriginalVideoPlaybackInvocation(raw, media) {
                if (!current() || !caller.isActive || !facet.stillOwned() || !native.owner.isCurrent(facet.expected))
                    throw CancellationException("Captured related request retired")
            }
        }, status, { error("No media publication during related loading") })
        private val repository = DesktopOriginalVideoOwnerRepositoryView(invocations) { 0L }
        private val actions = unused<DesktopOriginalVideoOwnerActions>()
        private val playlist = object : DesktopOriginalVideoOwnerPlaylist {
            override val playlist = MutableStateFlow<List<PlaylistItem>>(emptyList())
            override val currentIndex = MutableStateFlow(-1)
            override val playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
            override val isExternalPlaylist = MutableStateFlow(false)
            override val externalPlaylistSource = MutableStateFlow(ExternalPlaylistSource.NONE)
            override fun playAt(index: Int): PlaylistItem? = error("No external queue")
            override fun playNext(): PlaylistItem? = error("No next command")
            override fun playPrevious(): PlaylistItem? = error("No previous command")
            override fun setPlaylist(items: List<PlaylistItem>, startIndex: Int) {
                assertTrue(native.publication.depth.get() > 0)
                playlistWrites += items.toList(); playlist.value = items; currentIndex.value = startIndex
            }
        }
        private val network = object : DesktopOriginalVideoOwnerNetwork {
            override fun cdnNetwork() = error("No CDN network")
            override fun isMobileData() = false
            override fun isWifi() = wifi
            override fun getDefaultQualityId(context: DesktopOriginalPlayerSettingsContext) = 80
        }
        private val cache = object : DesktopOriginalVideoOwnerCache {
            override fun get(bvid: String, cid: Long): PlayUrlData? {
                outsideAdmission()
                // The cache fixture is a request port, like the production
                // captured cache view. Exercise the actual Invocation admission
                // before recording access, without creating another cache.
                invocations.requireRequestRepository()
                cacheReads += bvid to cid; return null
            }
            override fun invalidate(bvid: String, cid: Long) = error("No main playback retry")
        }
        private val api = unused<BilibiliApi>()
        private val interaction = VideoInteractionUseCase(DesktopOriginalVideoEngagementProtocol(api,
            { "fixture-csrf" }, { 7L }, { "fixture-session" }, { null }, {}, {},
            DesktopOriginalFavoriteFolderProtocol(api, { 7L }, { "fixture-csrf" }, {})),
            unused<DesktopOriginalVideoInteractionAnalytics>())
        private val settings = DesktopOriginalPlayerSettingsContext(context, ::current, ::commit,
            largeScreenOrFoldableConfiguration = { false }, isDebugBuild = { false })
        private val useCase = DesktopOriginalVideoPlaybackUseCaseEnvironment(context, repository, actions,
            unused(), unused(), media, { error("No volume mutation") }, { emptyMap() },
            { error("No account mutation") }, { false }, { _, _, _, _ -> }, ::current)
        private val plugins = Proxy.newProxyInstance(DesktopOriginalVideoOwnerPlugins::class.java.classLoader,
            arrayOf(DesktopOriginalVideoOwnerPlugins::class.java)) { _, method, _ ->
            when (method.name) {
                "capturePlaybackDispatch" -> native.owner.current()
                else -> error("No plugin effect during deferred related loading: ${method.name}")
            }
        } as DesktopOriginalVideoOwnerPlugins
        val vm = VideoPlaybackViewModel(DesktopOriginalVideoPlaybackOwnerEnvironment(native.scope, settings, invocations,
            repository, unused(), actions, useCase, interaction, unused(), unused(), playlist, plugins, unused(),
            network, cache, { null }, unused(), unused(), unused(), unused(), MutableStateFlow(false), ::current, ::commit,
            DesktopTodayWatchFeedbackWriteBinding(context, ::current, ::commit),
            captureNativePublication = native.owner::current,
            admitNativePublication = native.owner::admitPlaybackDispatch))
        val session = field("playbackSessionStore").get(vm) as PlaybackSessionStore
        val initial = VideoPlaybackUiState.Success(
            info = ViewInfo(bvid = "BVfixture", aid = 17, cid = 70, title = "Fixture"),
            playUrl = "https://fixture.invalid/video", audioUrl = "https://fixture.invalid/audio",
            currentQuality = 120, requestedQuality = 120, qualityIds = listOf(80, 120),
            requestedAudioQuality = 30251, selectedAudioQuality = 30251, currentAudioLang = "ja",
            cachedDashVideos = listOf(DashVideo(id = 120, baseUrl = "https://fixture.invalid/video")),
            cachedDashAudios = listOf(DashAudio(id = 30251, baseUrl = "https://fixture.invalid/audio")),
            relatedLoadState = RelatedLoadState.LOADING)
        private fun field(name: String) = VideoPlaybackViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") fun seed(state: VideoPlaybackUiState.Success,
            request: PlaybackRequest = native.original.request, token: Long = 12) {
            (field("_uiState").get(vm) as MutableStateFlow<VideoPlaybackUiState>).value = state
            session.updateCurrentMedia(state.info.bvid, state.info.cid)
            session.setCurrentRequest(request); session.setCurrentLoadRequestToken(token)
        }
        init {
            seed(initial)
            field("appContext").set(vm, settings)
            field("exoPlayer").set(vm, native.control)
            PlaybackCooldownManager.clearAll()
        }
        fun start(expected: DesktopOriginalVideoAcceptedPublication = assertNotNull(native.owner.current()), restart: Boolean = false) {
            val load = vm.captureDesktopLoadState()
            val method = VideoPlaybackViewModel::class.java.getDeclaredMethod("loadRelatedVideosAfterPlayback",
                String::class.java, java.lang.Long.TYPE, DesktopOriginalVideoAcceptedPublication::class.java, java.lang.Boolean.TYPE)
                .apply { isAccessible = true }
            try { method.invoke(vm, load.currentBvid, load.currentLoadRequestToken, expected, restart) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        suspend fun state(phase: RelatedLoadState) = withTimeout(3_000) {
            vm.uiState.first { (it as? VideoPlaybackUiState.Success)?.relatedLoadState == phase } as VideoPlaybackUiState.Success
        }
        suspend fun until(predicate: () -> Boolean) = withTimeout(3_000) { while (!predicate()) delay(1) }
        fun replaceBvid(): VideoPlaybackUiState.Success {
            val request = PlaybackRequest.create("BVsuccessor", 18, 71)
            native.owner.publish(request, native.original.nativeSource.source.copy(videoUrl = "https://fixture.invalid/successor"),
                native.player.currentSourceVersion, native.initialJob) { true }
            native.actor.drain(); native.actor.loaded()
            return initial.copy(info = ViewInfo(bvid = request.bvid, aid = request.aid, cid = request.cid),
                playUrl = "https://fixture.invalid/successor", currentQuality = 80).also { seed(it, request) }
        }
        override fun close() { native.close(); PlaybackCooldownManager.clearAll(); folder.toFile().deleteRecursively() }
    }

    private fun assertPlaybackUnchanged(expected: VideoPlaybackUiState.Success, actual: VideoPlaybackUiState.Success) {
        assertEquals(expected.currentQuality, actual.currentQuality)
        assertEquals(expected.requestedQuality, actual.requestedQuality)
        assertEquals(expected.requestedAudioQuality, actual.requestedAudioQuality)
        assertEquals(expected.selectedAudioQuality, actual.selectedAudioQuality)
        assertEquals(expected.currentAudioLang, actual.currentAudioLang)
        assertEquals(expected.playUrl, actual.playUrl); assertEquals(expected.audioUrl, actual.audioUrl)
        assertEquals(expected.cachedDashVideos, actual.cachedDashVideos); assertEquals(expected.cachedDashAudios, actual.cachedDashAudios)
    }

    @Test fun emptyActualRelatedReplyIsReadyAndPreservesPlayingQualityAndAudio() = runBlocking<Unit> {
        VmFixture().use { f ->
            f.start(); val ready = f.state(RelatedLoadState.READY)
            assertTrue(ready.related.isEmpty()); assertPlaybackUnchanged(f.initial, ready)
            assertEquals(1, f.relatedCalls.size); assertEquals(1, f.playlistWrites.size)
            assertSame(f.native.original, f.native.owner.current()); assertTrue(f.cacheReads.isEmpty())
        }
    }

    @Test fun actualRelatedFailureAndRetryPreserveSuccessInsteadOfReloadingMedia() = runBlocking<Unit> {
        for (recover in listOf(true, false)) VmFixture().use { f ->
            var attempt = 0
            f.answer = {
                attempt++
                if (recover && attempt == 2) Result.success(emptyList())
                else Result.failure(java.io.IOException("Fixture related failure"))
            }
            f.start(); val result = f.state(if (recover) RelatedLoadState.READY else RelatedLoadState.FAILED)
            assertEquals(2, attempt); assertPlaybackUnchanged(f.initial, result)
            assertSame(f.native.original, f.native.owner.current())
            assertEquals(if (recover) 1 else 0, f.playlistWrites.size)
            assertEquals(0, PlaybackCooldownManager.getConsecutiveFailures())
        }
    }

    @Test fun lateOldBvidOrRetiredAccountCannotPublishRelatedOrStartPreload() = runBlocking<Unit> {
        for (replaceBvid in listOf(true, false)) VmFixture(wifi = true).use { f ->
            val response = CompletableDeferred<Result<List<RelatedVideo>>>()
            f.answer = { withContext(NonCancellable) { response.await() } }
            f.start(); f.until { f.relatedCalls.size == 1 }
            val originalJob = f.captures.single().job
            if (replaceBvid) f.replaceBvid() else f.native.publication.epoch++
            val expected = f.vm.uiState.value
            val loads = f.native.actor.native.loads.size
            response.complete(Result.success(listOf(RelatedVideo(bvid = "BVrelated", cid = 80, title = "Related"))))
            withTimeout(3_000) { originalJob.join() }
            assertEquals(expected, f.vm.uiState.value); assertEquals(loads, f.native.actor.native.loads.size)
            assertTrue(f.playlistWrites.isEmpty()); assertTrue(f.preloadDetails.isEmpty())
            assertTrue(f.preloadPlayUrls.isEmpty()); assertTrue(f.cacheReads.isEmpty())
        }
    }

    @Test fun sameBvidSourceContinuationCancelsOldRelatedWithoutSpendingItsFailureBudget() = runBlocking<Unit> {
        VmFixture().use { f ->
            val oldResponse = CompletableDeferred<Result<List<RelatedVideo>>>()
            f.answer = { withContext(NonCancellable) { oldResponse.await() } }
            f.start(); f.until { f.relatedCalls.size == 1 }
            val oldJob = f.captures.single().job
            val child = f.native.replaceAudioAtSameVersion()
            val now = f.initial.copy(currentQuality = 80, selectedAudioQuality = 30280)
            f.seed(now)
            var childAttempt = 0
            f.answer = {
                childAttempt++
                if (childAttempt == 1) Result.failure(java.io.IOException("Child related retry"))
                else Result.success(emptyList())
            }
            f.start(child)
            oldResponse.complete(Result.failure(CancellationException("Old quality load was cancelled")))
            withTimeout(3_000) { oldJob.join() }
            val ready = f.state(RelatedLoadState.READY)
            assertEquals(2, childAttempt); assertEquals(3, f.relatedCalls.size)
            assertPlaybackUnchanged(now, ready); assertSame(child, f.native.owner.current())
            assertEquals(0, PlaybackCooldownManager.getConsecutiveFailures())
        }
    }

    @Test fun actualPlaylistPreloadLeavesAdmissionAndRetainsTheCompletedRelatedSourceFacet() = runBlocking<Unit> {
        for (retire in listOf(false, true)) VmFixture(wifi = true).use { f ->
            val detail = CompletableDeferred<Result<Pair<ViewInfo, PlayUrlData>>>()
            f.preloadAnswer = { withContext(NonCancellable) { detail.await() } }
            f.answer = { Result.success(listOf(RelatedVideo(bvid = "BVrelated", cid = 80, title = "Related"))) }
            f.start(); f.state(RelatedLoadState.READY); f.until { f.preloadDetails.size == 1 }
            val relatedJob = f.captures.first().job
            withTimeout(3_000) { relatedJob.join() }
            assertTrue(relatedJob.isCompleted); assertFalse(relatedJob.isActive)
            assertSame(f.native.original, f.preloadDetails.single())
            assertTrue(f.captures.size >= 2)
            assertTrue(f.captures.all { it.source.expected === f.native.original })
            val preloadJob = f.captures.last().job
            if (retire) f.native.replaceAudioAtSameVersion()
            detail.complete(Result.success(ViewInfo(bvid = "BVrelated", aid = 18, cid = 80) to playData()))
            withTimeout(3_000) { preloadJob.join() }
            assertEquals(if (retire) 0 else 1, f.cacheReads.size)
            assertEquals(if (retire) 0 else 1, f.preloadPlayUrls.size)
            assertEquals(1, f.playlistWrites.size)
        }
    }

    @Test fun realRepositoryServiceDecoratesCallCreationAndEnqueueWithTheCapturedNativeSource() = runBlocking<Unit> {
        // The current case proves this route can succeed; the two retirement
        // cases exercise the real newCall -> enqueue window and late delivery.
        for (retirement in listOf("current", "before-enqueue", "late-response")) {
            val sessions = DesktopSessionStore.temporary()
            sessions.saveAccount(mapOf("SESSDATA" to "synthetic-session", "bili_jct" to "synthetic-csrf"),
                AccountSummary(41, "Fixture", ""))
            val repository = DesktopRepository(sessions)
            val epoch = repository.sessionEpoch
            val receipt = repository.capturePlaybackAuthorization(epoch) { true }.receipt
            NativeFixture(DesktopRepositoryPlaybackPublication(repository), receipt).use { native ->
                val callsCreated = AtomicInteger()
                val callsStarted = AtomicInteger()
                val requests = AtomicInteger()
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val transport = repository.httpClient.newBuilder().proxy(java.net.Proxy.NO_PROXY)
                    .retryOnConnectionFailure(false)
                    .eventListenerFactory {
                        assertTrue(native.publication.depth.get() > 0,
                            "Actual OkHttp newCall must borrow NativeOwner admission")
                        callsCreated.incrementAndGet()
                        if (retirement == "before-enqueue") native.owner.close()
                        object : EventListener() {
                            override fun callStart(call: Call) { callsStarted.incrementAndGet() }
                        }
                    }
                    .addInterceptor { chain ->
                        assertEquals(0, native.publication.depth.get(), "HTTP executes outside publication")
                        val request = chain.request()
                        check(request.method == "GET" && request.url.host == "api.bilibili.com" &&
                            request.url.encodedPath == "/x/web-interface/archive/related" &&
                            request.url.queryParameter("bvid") == "BVfixture")
                        requests.incrementAndGet(); entered.complete(Unit)
                        if (retirement == "late-response") check(release.await(4, TimeUnit.SECONDS))
                        // No chain.proceed: every actual Retrofit/OkHttp request
                        // receives this memory body before a socket is opened.
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("memory")
                            .body("""{"code":0,"data":[]}""".toResponseBody("application/json".toMediaType())).build()
                    }.build()
                DesktopRepository::class.java.getDeclaredField("client").apply { isAccessible = true }
                    .set(repository, transport)
                try {
                    val operation = async(Dispatchers.Default) {
                        runCatching {
                            val caller = currentCoroutineContext().job
                            val source = DesktopOriginalVideoRelatedRequestSource(native.original) { true }
                            val binding = DesktopOriginalVideoRepositoryBinding.capture(repository, epoch,
                                native.initialJob, { native.owner.isCurrent(native.original) },
                                { action -> native.owner.admitPlaybackDispatch(native.original, action) },
                                PlayerPreferences(), null, emptySet(), false, { false }, { false }, { false },
                                { false }, { _, _ -> false },
                                decorateCalls = { delegate -> source.calls(delegate, native.owner, caller) })
                            assertEquals(receipt, binding.receipt)
                            binding.protocol.getRelatedVideosResult("BVfixture")
                        }
                    }
                    if (retirement == "late-response") {
                        withTimeout(3_000) { entered.await() }
                        native.owner.close()
                        release.countDown()
                    }
                    val completed = withTimeout(5_000) { operation.await() }
                    assertEquals(1, callsCreated.get())
                    if (retirement == "current") {
                        assertTrue(completed.getOrThrow().getOrThrow().isEmpty())
                        assertEquals(1, callsStarted.get()); assertEquals(1, requests.get())
                        assertSame(native.original, native.owner.current())
                    } else {
                        val rejected = completed.exceptionOrNull() ?: completed.getOrNull()?.exceptionOrNull()
                        assertIs<CancellationException>(rejected)
                        assertNull(native.owner.current())
                        val expectedEnqueues = if (retirement == "before-enqueue") 0 else 1
                        assertEquals(expectedEnqueues, callsStarted.get())
                        assertEquals(expectedEnqueues, requests.get())
                    }
                } finally {
                    release.countDown()
                    transport.dispatcher.executorService.shutdownNow(); transport.connectionPool.evictAll()
                }
            }
        }
    }
}
