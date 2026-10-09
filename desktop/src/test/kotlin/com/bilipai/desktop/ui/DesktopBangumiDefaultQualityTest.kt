package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.android.purebilibili.feature.bangumi.*
import com.android.purebilibili.feature.download.DownloadTask
import com.android.purebilibili.feature.plugin.*
import com.android.purebilibili.feature.video.player.*
import com.android.purebilibili.feature.video.usecase.VideoInteractionUseCase
import com.bilipai.desktop.player.MpvPlayer
import com.bilipai.desktop.plugins.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonPrimitive

/** Actual full PGC VM -> existing native-source plan, synthetic signed DTOs only.
 * The MPV wrapper is never attached/initialized/loaded. Publication records the
 * real plan projection and then throws cancellation; no native ACK is simulated.
 */
class DesktopBangumiDefaultQualityTest {
    @Test fun rootAutoHighestSelectsValidSdr4kInsteadOfResponse112(): Unit = runBlocking {
        fixture(autoHighest = true) { f ->
            val raw = response(112, listOf(video(112), video(120), video(125), video(126)))
            f.reply = { Result.success(raw) }
            f.vm.loadBangumiPlay(19, 41, resumePositionMs = 7_000L)
            f.awaitPlans(1)
            assertEquals(listOf(127), f.requests.toList())
            val plan = f.plans.single()
            assertSame(raw, plan.data)
            assertEquals(120, plan.videoTrack?.id)
            assertEquals(3840, plan.videoTrack?.width)
            assertEquals(2160, plan.videoTrack?.height)
            assertEquals(7.0, plan.nativeSource().startPositionSeconds)
            assertEquals("https://www.bilibili.com/bangumi/play/ep41", plan.nativeSource().referer)
            assertEquals(listOf(120), plan.adaptiveSource()?.videoTracks?.map { it.id })
            val state = f.vm.uiState.value as BangumiPlayerState.Success
            assertEquals(120, state.quality)
            assertEquals(120, plan.payload(state).quality)
            assertSame(raw, state.cachedPlayData)
            assertEquals(112, raw.quality) // the original signed response is not rewritten
        }
    }

    @Test fun explicitOffKeepsOriginalVipSdrAndHdrInitialPolicies(): Unit = runBlocking {
        for ((hdr, expected) in listOf(false to 112, true to 125)) {
            fixture(autoHighest = false, hdr = hdr) { f ->
                f.reply = { Result.success(response(expected, listOf(video(112), video(120), video(125)))) }
                f.vm.loadBangumiPlay(19, 41)
                f.awaitPlans(1)
                assertEquals(listOf(expected), f.requests.toList())
                assertEquals(expected, f.plans.single().videoTrack?.id)
            }
        }
    }

    @Test fun autoHighestKeepsHdrHevcCapabilityAndDoesNotInferDolbyVision(): Unit = runBlocking {
        for ((hevc, expected) in listOf(false to 120, true to 125)) {
            fixture(autoHighest = true, hdr = true, hevc = hevc) { f ->
                f.reply = { Result.success(response(112, listOf(video(120), video(125), video(126)))) }
                f.vm.loadBangumiPlay(19, 41)
                f.awaitPlans(1)
                assertEquals(expected, f.plans.single().videoTrack?.id)
            }
        }
    }

    @Test fun manualQualityAfterAutoHighestUsesTheExplicitOriginalRequest(): Unit = runBlocking {
        fixture(autoHighest = true) { f ->
            f.reply = { qn -> Result.success(response(if (qn == 80) 80 else 112,
                listOf(video(80), video(112), video(120)))) }
            f.vm.loadBangumiPlay(19, 41)
            f.awaitPlans(1)
            assertEquals(120, f.plans.first().videoTrack?.id)
            val autoState = f.vm.uiState.value as BangumiPlayerState.Success
            f.vm.changeQuality(80)
            f.awaitPlans(2)
            assertSame(autoState, f.qualityCaptures.single())
            assertEquals(listOf(127, 80), f.requests.toList())
            assertEquals(80, f.plans.last().videoTrack?.id)
            assertEquals(80, (f.vm.uiState.value as BangumiPlayerState.Success).quality)
            assertFalse(f.resetPlayers.last())
        }
    }

    @Test fun drmResponseStillRefetches80AndDoesNotSelectHigherClearTrack(): Unit = runBlocking {
        fixture(autoHighest = true) { f ->
            f.reply = { qn -> Result.success(response(if (qn == 80) 80 else 127,
                listOf(video(80), video(120)), drm = qn != 80)) }
            f.vm.loadBangumiPlay(19, 41)
            f.awaitPlans(1)
            assertEquals(listOf(127, 80), f.requests.toList())
            assertEquals(80, f.plans.single().videoTrack?.id)
            assertFalse(f.plans.single().data.isDrm)
        }
    }

    @Test fun serverPermissionDowngradeAndInvalidHigherUrlsRemainAuthoritative(): Unit = runBlocking {
        fixture(autoHighest = true, vip = false, loggedIn = false) { f ->
            f.reply = { Result.success(response(64, listOf(video(64), video(120).copy(baseUrl = "")))) }
            f.vm.loadBangumiPlay(19, 41)
            f.awaitPlans(1)
            assertEquals(listOf(127), f.requests.toList())
            assertEquals(64, f.plans.single().videoTrack?.id)
            assertFalse((f.vm.uiState.value as BangumiPlayerState.Success).isLoggedIn)
        }
    }

    @Test fun lateInitialReplyCannotPublishAfterActualEntryAdmissionRetires(): Unit = runBlocking {
        fixture(autoHighest = true) { f ->
            val started = CompletableDeferred<Unit>()
            val deferredResponse = CompletableDeferred<Result<BangumiVideoInfo>>()
            f.reply = { started.complete(Unit); deferredResponse.await() }
            f.vm.loadBangumiPlay(19, 41)
            withTimeout(4_000L) { started.await() }
            f.active.set(false)
            deferredResponse.complete(Result.success(response(112, listOf(video(120)))))
            f.awaitRequestFinished()
            assertTrue(f.plans.isEmpty())
            assertTrue(f.vm.uiState.value is BangumiPlayerState.Loading)
            assertFailsWith<CancellationException> { f.environment.autoHighestQualityEnabled() }
        }
    }

    private suspend fun fixture(autoHighest: Boolean, hdr: Boolean = false, hevc: Boolean = true,
        vip: Boolean = true, loggedIn: Boolean = true, block: suspend (Fixture) -> Unit) {
        val f = Fixture(autoHighest, hdr, hevc, vip, loggedIn)
        try { block(f) } finally { f.close() }
    }

    private class Fixture(autoHighest: Boolean, hdr: Boolean, hevc: Boolean, vip: Boolean, loggedIn: Boolean) {
        val active = AtomicBoolean(true)
        private val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> uncaught += failure })
        val requests = CopyOnWriteArrayList<Int>()
        val plans = CopyOnWriteArrayList<DesktopOriginalBangumiNativeSourcePlan>()
        val qualityCaptures = CopyOnWriteArrayList<BangumiPlayerState.Success>()
        val resetPlayers = CopyOnWriteArrayList<Boolean>()
        private val requestFinished = CompletableDeferred<Unit>()
        private val launchedRequests = CopyOnWriteArrayList<Job>()
        var reply: suspend (Int) -> Result<BangumiVideoInfo> = { error("Set synthetic response") }
        private val player = MpvPlayer()
        private val section = DesktopOriginalMpvSectionControl(player, { null }, active::get,
            { action -> if (active.get()) { action(); true } else false }, { false },
            { error("No accepted native source") }, { error("No native restart") },
            { _, _, _, _ -> error("No native seek") }, scope, MutableStateFlow<Long?>(null),
            { action -> if (active.get()) { action(); true } else false })
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
            arrayOf(BilibiliApi::class.java)) { _, method, _ -> error("Unexpected account API ${method.name}") } as BilibiliApi
        private val directory = Files.createTempDirectory("pgc-default-quality-")
        private val store = DesktopPluginStore(directory)
        private val settings = DesktopOriginalPlayerSettingsContext(DesktopPluginContext(store), active::get,
            { action -> if (active.get()) { action(); true } else false })
        private val folder = DesktopOriginalFavoriteFolderProtocol(api, { null }, { null }) { check(active.get()) }
        private val engagement = DesktopOriginalVideoEngagementProtocol(api, { null }, { null }, { null }, { null },
            { check(active.get()) }, { error("No follow action") }, folder)
        private val analytics = object : DesktopOriginalVideoInteractionAnalytics {
            override fun logLike(videoId: String, isLiked: Boolean) = error("No analytics")
            override fun logDislike(videoId: String, isDisliked: Boolean) = error("No analytics")
            override fun logFavorite(videoId: String, isFavorited: Boolean) = error("No analytics")
            override fun logFollow(userId: String, isFollowed: Boolean) = error("No analytics")
            override fun logCoin(videoId: String, coinCount: Int) = error("No analytics")
        }
        val environment = DesktopOriginalBangumiPlayerEnvironment(scope, section, settings,
            object : DesktopOriginalBangumiPlayerRequests {
                override suspend fun getSeasonDetail(seasonId: Long, epId: Long) = Result.success(detail())
                override suspend fun getPugvSeasonDetail(seasonId: Long, epId: Long) = error("No course request")
                override suspend fun getBangumiPlayUrl(epId: Long, qn: Int, cid: Long, bvid: String?,
                    seasonId: Long?, aid: Long, isCourse: Boolean): Result<BangumiVideoInfo> {
                    check(active.get())
                    assertEquals(41L, epId); assertEquals(104L, cid); assertEquals(19L, seasonId)
                    assertFalse(isCourse)
                    requests += qn
                    return try { reply(qn) } finally { requestFinished.complete(Unit) }
                }
                override suspend fun getMyFollowBangumi(type: Int, page: Int, pageSize: Int) = Result.success(MyFollowBangumiData(total = 0))
                override suspend fun followBangumi(seasonId: Long, isCourse: Boolean) = error("No follow action")
                override suspend fun unfollowBangumi(seasonId: Long, isCourse: Boolean) = error("No follow action")
                override suspend fun updateBangumiFollowStatus(seasonId: Long, status: Int) = error("No follow action")
            }, object : DesktopOriginalBangumiPlayerAccount {
                override fun isPlaybackVip() = vip
                override fun isPlaybackLoggedIn() = loggedIn
                override fun hasPlaybackSessionCookie() = loggedIn
                override fun playbackAccessToken(): String? = null
                override fun hasPrimarySessionCookie() = false
                override suspend fun reportPlayHeartbeat(bvid: String, cid: Long, playedTime: Long, aid: Long,
                    epid: Long, sid: Long, videoType: Int, subType: Int?): Boolean = error("No accepted heartbeat")
            }, object : DesktopOriginalBangumiPlayerActions {
                override suspend fun checkLikeStatus(aid: Long) = false
                override suspend fun checkCoinStatus(aid: Long) = 0
            }, VideoInteractionUseCase(engagement, analytics), api,
            object : DesktopOriginalVideoProgressPort {
                override fun getCachedPosition(bvid: String, cid: Long) = 12_000L
                override fun savePosition(bvid: String, cid: Long, positionMs: Long) = error("No accepted progress writer")
            }, object : DesktopOriginalBangumiBaseRequests {
                override suspend fun getSponsorSegments(bvid: String): List<SponsorSegment> = error("No sponsor request")
                override fun findSegmentAtPosition(segments: List<SponsorSegment>, positionMs: Long): SponsorSegment? = error("No sponsor request")
                override suspend fun getDanmakuRawData(cid: Long): ByteArray? = error("No danmaku request")
            }, object : DesktopOriginalBangumiNativePublication {
                override suspend fun beginEpisode(detail: BangumiDetail, episode: BangumiEpisode) { check(active.get()) }
                override suspend fun beginQualityReplacement(state: BangumiPlayerState.Success) {
                    currentCoroutineContext().ensureActive()
                    check(active.get())
                    // Projection-only capture of this exact preceding plan; no native source or ACK is created.
                    assertSame(vm.uiState.value, state)
                    val previous = plans.last()
                    assertSame(previous.data, state.cachedPlayData)
                    assertSame(previous.episode, state.currentEpisode)
                    assertSame(previous.detail, state.seasonDetail)
                    qualityCaptures += state
                }
                override fun publishDash(videoUrl: String, audioUrl: String?, seekToMs: Long, resetPlayer: Boolean,
                    referer: String, dashManifest: String?) {
                    check(active.get())
                    val state = vm.uiState.value as BangumiPlayerState.Success
                    val plan = desktopOriginalBangumiNativePlan(state.seasonDetail, state.currentEpisode,
                        checkNotNull(state.cachedPlayData), videoUrl, audioUrl, null, referer, dashManifest,
                        seekToMs, playWhenReady = true)
                    resetPlayers += resetPlayer
                    plans += plan
                    throw CancellationException("Recorded projection only: no native load/ACK")
                }
                override fun publishSegments(segmentUrls: List<String>, seekToMs: Long, resetPlayer: Boolean, referer: String) = error("DASH fixture only")
                override fun stopCurrentEpisode() = Unit
                override fun canUseCachedPlayback(state: BangumiPlayerState.Success) = false
            }, flowOf(false), { -1 }, { -1 }, { error("No audio preference write") },
            { hevc }, { hdr }, { false }, { false }, { null },
            { _, _, _ -> error("No CDN plugin") }, { _, _, _ -> check(active.get()) },
            { _: DownloadTask -> error("No download action") }, { check(it === section) },
            { block -> scope.launch(block = block).also { launchedRequests += it } }, { action -> if (active.get()) { action(); true } else false },
            active::get, { false })
        val vm: BangumiPlayerViewModel = BangumiPlayerViewModel(environment)
        init {
            store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(autoHighest)))
            vm.attachPlayer(section)
            scope.launch { vm.toastEvent.collect {} }
        }
        suspend fun awaitPlans(count: Int) { withTimeout(4_000L) { while (plans.size < count) delay(10L) } }
        suspend fun awaitRequestFinished() {
            withTimeout(4_000L) {
                requestFinished.await()
                // These are exactly the VM's environment.launch request jobs;
                // the independent Section/toast collectors are not included.
                launchedRequests.toList().forEach { it.join() }
            }
        }
        suspend fun close() {
            vm.close()
            active.set(false)
            scope.cancel()
            withTimeout(4_000L) { scope.coroutineContext[Job]!!.join() }
            player.close()
            assertTrue(uncaught.isEmpty(), uncaught.toString())
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    companion object {
        private fun detail() = BangumiDetail(seasonId = 19, seasonType = 1, title = "Synthetic PGC",
            episodes = listOf(BangumiEpisode(id = 41, aid = 101, cid = 104, bvid = "", duration = 60_000)))
        private fun video(qn: Int) = DashVideo(id = qn, baseUrl = "https://media.invalid/pgc-$qn.m4s?synthetic=1",
            bandwidth = qn * 1000, mimeType = "video/mp4", codecs = if (qn in listOf(125, 126)) "hev1.2.4.L153.B0" else "avc1.640033",
            width = if (qn == 120) 3840 else 1920, height = if (qn == 120) 2160 else 1080,
            frameRate = "30", segmentBase = SegmentBase(initialization = "0-99", indexRange = "100-199"),
            codecid = if (qn in listOf(125, 126)) 12 else 7)
        private fun response(qn: Int, videos: List<DashVideo>, drm: Boolean = false) = BangumiVideoInfo(quality = qn,
            isDrm = drm, timelength = 60_000, acceptQuality = videos.map { it.id },
            acceptDescription = videos.map { "Synthetic ${it.id}" }, dash = Dash(duration = 60, video = videos,
                audio = listOf(DashAudio(id = 30280, baseUrl = "https://media.invalid/pgc-audio.m4s?synthetic=1",
                    bandwidth = 192000, mimeType = "audio/mp4", codecs = "mp4a.40.2",
                    segmentBase = SegmentBase(initialization = "0-99", indexRange = "100-199"), codecid = 0))))
    }
}
