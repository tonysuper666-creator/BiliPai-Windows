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

/** Real generated VM with CPU publication ports. Root's proposed identity guard is
 * exercised here; this fixture does not claim Root/native integration acceptance. */
class DesktopBangumiPlaybackEpisodesTest {
    @Test fun originalGroupsKeepOrderDeduplicateAndPreferMain() {
        val main = listOf(episode(41), episode(42))
        val detail = sectionDetail().copy(episodes = main)
        assertEquals(listOf(41L, 42L, 43L, 91L), desktopBangumiAllEpisodes(detail).map { it.id })
        assertSame(main[0], desktopBangumiPlaybackEpisodes(detail, 41)[0])
        assertEquals(listOf(41L, 42L), desktopBangumiPlaybackEpisodes(detail, 41).map { it.id })
        assertEquals(listOf(43L), desktopBangumiPlaybackEpisodes(detail, 43).map { it.id })
        assertSame(detail.section!![1].episodes!![3], desktopBangumiPlaybackEpisodes(detail, 43).single())
        assertTrue(desktopBangumiPlaybackEpisodes(detail, 999).isEmpty())
        assertTrue(desktopBangumiPlaybackEpisodes(detail, 0).isEmpty())
        assertTrue(desktopBangumiAllEpisodes(BangumiDetail()).isEmpty())
    }

    @Test fun decodedContainersCannotBorrowAcrossSessionsOrSeasons() {
        val detail = sectionDetail()
        assertTrue(desktopBangumiSameDecodedPlaybackSeason(detail, detail.copy(userStatus = UserStatus())))
        assertFalse(desktopBangumiSameDecodedPlaybackSeason(detail, sectionDetail()))
        assertFalse(desktopBangumiSameDecodedPlaybackSeason(detail, detail.copy(seasonId = 20)))
        assertFalse(desktopBangumiSameDecodedPlaybackSeason(detail, detail.copy(section = detail.section!!.toList())))
        assertFalse(desktopBangumiSameDecodedPlaybackSeason(null, detail))
        assertFalse(desktopBangumiSameDecodedPlaybackSeason(BangumiDetail(), BangumiDetail()))
        assertFalse(desktopBangumiSameDecodedPlaybackSeason(BangumiDetail(episodes = emptyList()),
            BangumiDetail(episodes = emptyList())))
    }

    @Test fun sectionInitialSelectionUsesAidThenRouteThenProgressThenFirst(): Unit = runBlocking {
        val original = sectionDetail()
        val detail = original.copy(userStatus = UserStatus(progress = WatchProgress(lastEpId = 43, lastTime = 7)))
        for ((aid, route, expected) in listOf(Triple(142L, 91L, 42L), Triple(0L, 91L, 91L),
            Triple(0L, 0L, 43L), Triple(0L, 999L, 41L))) {
            val f = Fixture(detail)
            try {
                f.vm.loadBangumiPlay(19, route, preferredAid = aid)
                f.awaitPlans(1); f.awaitRequestFinished()
                assertEquals(listOf(expected), f.episodeRequests.toList())
                assertSame(desktopBangumiAllEpisodes(detail).first { it.id == expected }, f.plans.single().episode)
                assertEquals(if (route == 0L && aid == 0L) 7.0 else 12.0,
                    f.plans.single().nativeSource().startPositionSeconds)
            } finally { f.close() }
        }
    }

    @Test fun nextAndSwitchStayInOriginalSectionAndRejectForeignDto(): Unit = runBlocking {
        val detail = sectionDetail()
        val f = Fixture(detail)
        try {
            f.vm.loadBangumiPlay(19, 41)
            f.awaitPlans(1); f.awaitRequestFinished()
            f.vm.playNextEpisode()
            f.awaitPlans(2); f.awaitRequestFinished()
            assertEquals(listOf(41L, 42L), f.episodeRequests.toList())
            assertEquals(1, (f.vm.uiState.value as BangumiPlayerState.Success).currentEpisodeIndex)
            val before = f.vm.uiState.value
            f.vm.switchEpisode(episode(91)) // equal ID from a different decode is not authority
            assertSame(before, f.vm.uiState.value)
            val finalEpisode = detail.section!![1].episodes!![3]
            f.vm.switchEpisode(finalEpisode)
            f.awaitPlans(3); f.awaitRequestFinished()
            assertSame(finalEpisode, f.plans.last().episode)
            assertEquals(2, (f.vm.uiState.value as BangumiPlayerState.Success).currentEpisodeIndex)
            f.vm.playNextEpisode()
            f.awaitRequestFinished()
            assertEquals(listOf(41L, 42L, 43L), f.episodeRequests.toList())
            assertEquals(listOf(false, true, true, true), f.publicationPhases.toList())
            assertTrue(f.readOnlyFacts.all { (d, e, i) -> desktopBangumiPlaybackEpisodes(d, e.id).getOrNull(i) === e })
        } finally { f.close() }
    }

    @Test fun ordinaryMainEpisodeSelectionStillWorks(): Unit = runBlocking {
        val detail = BangumiDetail(seasonId = 19, episodes = listOf(episode(41), episode(42)))
        val f = Fixture(detail)
        try {
            f.vm.loadBangumiPlay(19, 41); f.awaitPlans(1); f.awaitRequestFinished()
            f.vm.playNextEpisode(); f.awaitPlans(2); f.awaitRequestFinished()
            assertEquals(listOf(41L, 42L), f.episodeRequests.toList())
        } finally { f.close() }
    }
    @Test fun lateResponseFromRetiredSeasonCannotPublishIntoNewSeason(): Unit = runBlocking {
        val f = Fixture(sectionDetail())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            f.reply = {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                Result.success(response(80, listOf(video(80))))
            }
            f.vm.loadBangumiPlay(19, 41)
            withTimeout(4_000L) { started.await() }
            f.requestedDetail = sectionDetail().copy(seasonId = 20)
            f.reply = { Result.success(response(80, listOf(video(80)))) }
            f.vm.loadBangumiPlay(20, 41)
            f.awaitPlans(1)
            release.complete(Unit)
            f.awaitRequestFinished()
            assertEquals(1, f.plans.size)
            assertEquals(20L, f.plans.single().detail.seasonId)
            assertSame(f.requestedDetail.section!![1].episodes!![0], f.plans.single().episode)
        } finally { release.complete(Unit); f.close() }
    }

    private class Fixture(val input: BangumiDetail, autoHighest: Boolean = false, hdr: Boolean = false, hevc: Boolean = true, vip: Boolean = true, loggedIn: Boolean = true) {
        var requestedDetail: BangumiDetail = input
        val active = AtomicBoolean(true)
        private val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> uncaught += failure })
        val requests = CopyOnWriteArrayList<Int>()
        val episodeRequests = CopyOnWriteArrayList<Long>()
        val readOnlyFacts = CopyOnWriteArrayList<Triple<BangumiDetail, BangumiEpisode, Int>>()
        val publicationPhases = CopyOnWriteArrayList<Boolean>()
        val plans = CopyOnWriteArrayList<DesktopOriginalBangumiNativeSourcePlan>()
        val qualityCaptures = CopyOnWriteArrayList<BangumiPlayerState.Success>()
        val resetPlayers = CopyOnWriteArrayList<Boolean>()
        private val requestFinished = CompletableDeferred<Unit>()
        private val launchedRequests = CopyOnWriteArrayList<Job>()
        val launchedRequestCount: Int get() = launchedRequests.size
        /** Plan-only by default; a test may let the VM reach the attached player. */
        @Volatile var ownsPlaybackSource = false
        var reply: suspend (Int) -> Result<BangumiVideoInfo> = { Result.success(response(80, listOf(video(80)))) }
        private val player = MpvPlayer()
        private val section = DesktopOriginalMpvSectionControl(player, { null }, active::get,
            { action -> if (active.get()) { action(); true } else false }, { false },
            { error("No accepted native source") }, { error("No native restart") },
            { _, _, _, _ -> error("No native seek") }, scope, MutableStateFlow<Long?>(null),
            { action -> if (active.get()) { action(); true } else false })
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
            arrayOf(BilibiliApi::class.java)) { _, method, _ -> error("Unexpected account API ${method.name}") } as BilibiliApi
        private val directory = Files.createTempDirectory("pgc-playback-episodes-")
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
                override suspend fun getSeasonDetail(seasonId: Long, epId: Long) = Result.success(requestedDetail)
                override suspend fun getPugvSeasonDetail(seasonId: Long, epId: Long) = error("No course request")
                override suspend fun getBangumiPlayUrl(epId: Long, qn: Int, cid: Long, bvid: String?,
                    seasonId: Long?, aid: Long, isCourse: Boolean): Result<BangumiVideoInfo> {
                    check(active.get())
                    episodeRequests += epId; assertEquals(104L, cid); assertEquals(requestedDetail.seasonId, seasonId)
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
            active::get, { ownsPlaybackSource }, object : DesktopOriginalVideoOwnerNetwork {
                override fun cdnNetwork() = error("No CDN observation")
                override fun isMobileData() = false
                override fun isWifi() = true
                override fun relatedNetworkObservation() = DesktopOriginalVideoRelatedNetworkObservation(
                    DesktopHomeNetworkObservation(true, 3, 71L, false, "cpu-memory-transport"),
                    downstreamBandwidthKbps = null,
                )
            }, { false },
            publishReadOnlyDetail = { detail, episode, index, _, postBegin, isLoadCurrent ->
                currentCoroutineContext().ensureActive(); settings.requireCurrent()
                check(active.get()); require(desktopBangumiPlaybackEpisodes(detail, episode.id).getOrNull(index) === episode)
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
                publicationPhases += postBegin
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
        private fun episode(id: Long) = BangumiEpisode(id = id, aid = id + 100, cid = 104, duration = 60_000)
        private fun sectionDetail() = BangumiDetail(seasonId = 19, section = listOf(
            BangumiSection(episodes = emptyList()),
            BangumiSection(episodes = listOf(episode(41), episode(42), episode(42), episode(43))),
            BangumiSection(episodes = listOf(episode(91)))))
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
