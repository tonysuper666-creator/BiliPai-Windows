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

/** Actual route action and full PGC VM; native publication is a recorded projection, never a native load. */
class DesktopBangumiFollowRoutePlayerTest {
    @Test fun realFollowRouteZeroEpisodeResolvesHistoryOrFirstEpisode() = runBlocking {
        for (history in listOf<Long?>(null, 42L, 999L)) {
            fixture(autoHighest = false) { f ->
                val pushed = mutableListOf<com.android.purebilibili.navigation3.BiliPaiNavKey>()
                val commands = Proxy.newProxyInstance(DesktopOriginalRootRouteCommands::class.java.classLoader,
                    arrayOf(DesktopOriginalRootRouteCommands::class.java)) { _, method, args ->
                    check(method.name == "push") { "Unexpected route command ${method.name}" }
                    pushed += args!![0] as com.android.purebilibili.navigation3.BiliPaiNavKey
                    true
                } as DesktopOriginalRootRouteCommands
                val navigation = desktopOriginalRootHomeNavigation(commands, null, null)
                navigation.onBangumiPlayFollow(19L)
                val key = pushed.single() as com.android.purebilibili.navigation3.BiliPaiNavKey.BangumiPlayer
                assertEquals(19L, key.seasonId); assertEquals(0L, key.epId)
                navigation.onBangumiSeasonClick(19L)
                assertEquals(com.android.purebilibili.navigation3.BiliPaiNavKey.BangumiDetail(19L), pushed[1])
                assertEquals(2, pushed.size)
                f.expectedEpisode = if (history == 42L) 42L else 41L
                f.suppliedDetail = detail().copy(episodes = listOf(
                    BangumiEpisode(id = 41, aid = 101, cid = 104, duration = 60_000),
                    BangumiEpisode(id = 42, aid = 102, cid = 104, duration = 60_000)),
                    userStatus = history?.let { UserStatus(progress = WatchProgress(lastEpId = it, lastTime = 9)) })
                f.reply = { Result.success(response(80, listOf(video(80)))) }
                f.vm.loadBangumiPlay(key.seasonId, key.epId)
                f.awaitPlans(1)
                assertEquals(listOf(19L to 0L), f.detailRequests.toList())
                assertEquals(f.expectedEpisode, f.plans.single().episode.id)
                assertEquals(if (history == 42L) 9.0 else 0.0, f.plans.single().nativeSource().startPositionSeconds)
                assertEquals(1, f.requests.size)
            }
        }
    }
    private suspend fun fixture(autoHighest: Boolean, hdr: Boolean = false, hevc: Boolean = true,
        vip: Boolean = true, loggedIn: Boolean = true, block: suspend (Fixture) -> Unit) {
        val f = Fixture(autoHighest, hdr, hevc, vip, loggedIn)
        try { block(f) } finally { f.close() }
    }

    private class Fixture(autoHighest: Boolean, hdr: Boolean, hevc: Boolean, vip: Boolean, loggedIn: Boolean) {
        var suppliedDetail = detail()
        var expectedEpisode = 41L
        val detailRequests = CopyOnWriteArrayList<Pair<Long, Long>>()
        val active = AtomicBoolean(true)
        private val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> uncaught += failure })
        val requests = CopyOnWriteArrayList<Int>()
        val readOnlyFacts = CopyOnWriteArrayList<Triple<BangumiDetail, BangumiEpisode, Int>>()
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
        private val engagement = com.android.purebilibili.data.repository.createTestVideoEngagementProtocol(api, { null }, { null }, { null }, { null },
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
                override suspend fun getSeasonDetail(seasonId: Long, epId: Long): Result<BangumiDetail> { detailRequests += seasonId to epId; return Result.success(suppliedDetail) }
                override suspend fun getPugvSeasonDetail(seasonId: Long, epId: Long) = error("No course request")
                override suspend fun getBangumiPlayUrl(epId: Long, qn: Int, cid: Long, bvid: String?,
                    seasonId: Long?, aid: Long, isCourse: Boolean): Result<BangumiVideoInfo> {
                    check(active.get())
                    assertEquals(expectedEpisode, epId); assertEquals(104L, cid); assertEquals(19L, seasonId)
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
                override suspend fun refreshVipStatusForPreferredQualityIfNeeded(isLoggedIn: Boolean,
                    cachedIsVip: Boolean, storedQuality: Int, autoHighestEnabled: Boolean) = cachedIsVip
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
                override fun getCachedPosition(bvid: String, cid: Long) = 0L
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
            active::get, { false }, object : DesktopOriginalVideoOwnerNetwork {
                override fun cdnNetwork() = error("No CDN observation")
                override fun isMobileData() = false
                override fun isWifi() = true
                override fun relatedNetworkObservation() = DesktopOriginalVideoRelatedNetworkObservation(
                    DesktopHomeNetworkObservation(true, 3, 71L, false, "cpu-memory-transport"),
                    downstreamBandwidthKbps = null,
                )
            }, { false },
            publishReadOnlyDetail = { detail, episode, index, _, _, isLoadCurrent ->
                currentCoroutineContext().ensureActive(); settings.requireCurrent()
                check(active.get()); require(detail.episodes?.getOrNull(index) === episode)
                // Await registration of this actual existing VM launch; never
                // synthesize a Job or compare a nested body Job to the parent.
                val actualLoad = withTimeout<Job>(4_000L) {
                    while (true) {
                        val candidate = launchedRequests.singleOrNull { it.isActive && isLoadCurrent(it) }
                        if (candidate != null) return@withTimeout candidate
                        yield()
                    }
                    error("Unreachable registered PGC launch")
                }
                if (!active.get() || !isLoadCurrent(actualLoad))
                    throw CancellationException("CPU plan readonly load retired")
                readOnlyFacts += Triple(detail, episode, index)
            },
            prepareMedia = {
                // Existing CPU plan-only publisher and fixed supplied capabilities;
                // no MPV/DLL/hardware readiness or accepted source is claimed.
                currentCoroutineContext().ensureActive(); settings.requireCurrent()
                check(active.get() && scope.isActive)
            },
            captureLoadCaller = {
                // This CPU fixture launches directly in scope; this is its
                // actual returned outer load Job, with no invocation wrapper.
                currentCoroutineContext().ensureActive(); settings.requireCurrent()
                check(active.get() && scope.isActive)
                checkNotNull(currentCoroutineContext()[Job])
            })
        val vm: BangumiPlayerViewModel = BangumiPlayerViewModel(environment)
        init {
            store.update("quality_settings", mapOf("auto_highest_quality" to JsonPrimitive(autoHighest)))
            vm.attachPlayer(section)
            scope.launch { vm.toastEvent.collect {} }
        }
        suspend fun awaitPlans(count: Int) { awaitRequestFinished(); assertEquals(count, plans.size) }
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
