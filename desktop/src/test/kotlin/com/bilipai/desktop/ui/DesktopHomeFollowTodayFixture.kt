package com.bilipai.desktop.ui

import com.android.purebilibili.feature.home.*
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.data.repository.*
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.*

/** Actual complete generated Home VM and its existing admitted state/reducers.
 * Only discovery responses and the entry/account receipt are controlled. No
 * Root/window, native player, media, HTTP, real account or user profile is used. */
internal class DesktopHomeFollowTodayFixture(
    val compute: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T


    val folder = Files.createTempDirectory("actual-home-follow-today-")
    private val owner = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "home-owner") }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + owner)
    suspend fun <T> onOwner(block: () -> T): T = withContext(owner) { block() }
    val gate = Any()
    @Volatile var current = true
    var loggedIn = false
    val incremental = MutableStateFlow(false)
    val followCalls = mutableListOf<Boolean>()
    var baseline = ""
    var followResponse: suspend () -> Result<DynamicFeedFetchResult> = { Result.success(feed("a", "b")) }
    var historyResponse: suspend () -> Result<HistoryResult> = { Result.failure(Exception("offline")) }
    var historyCalls = 0
    val followRequests = object : DesktopHomeFollowRequests {
        override fun currentUpdateBaseline(scope: DynamicFeedScope, type: String) = baseline
        override suspend fun getDynamicFeed(refresh: Boolean, scope: DynamicFeedScope, type: String, incrementalRefresh: Boolean): Result<DynamicFeedFetchResult> {
            followCalls += incrementalRefresh; return followResponse()
        }
        override fun hasMoreData(scope: DynamicFeedScope, type: String) = true
        override fun syncPaginationAfterRefresh(scope: DynamicFeedScope, type: String, offset: String, hasMore: Boolean) = Unit
    }
    val historyRequests = object : DesktopHomeHistoryRequests {
        override suspend fun getHistoryList(ps: Int, max: Long, viewAt: Long, business: String): Result<HistoryResult> {
            historyCalls++; return historyResponse()
        }
    }
    val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
    var recommend: suspend (Int) -> Result<List<VideoItem>> = { Result.success(listOf(video("BV_initial"))) }
    var region: suspend (Int) -> Result<List<VideoItem>> = { Result.success(listOf(video("BV_region"))) }
    fun commit(action: () -> Unit): Boolean = synchronized(gate) {
        if (!current) false else { action(); true }
    }
    val videoRequests = object : DesktopHomeVideoRequests {
        override suspend fun getHomeVideos(idx: Int): Result<List<VideoItem>> {
            requests += "recommend" to idx; return recommend(idx)
        }
        override suspend fun getRegionVideos(tid: Int, page: Int): Result<List<VideoItem>> {
            requests += "region" to page; return region(page)
        }
        override suspend fun getNavInfo(): Result<DesktopHomeNavPublication> = Result.failure(Exception("No identity in this fixture"))
        override suspend fun getPopularVideos(page: Int) = error("No popular request")
        override suspend fun getRankingVideos(rid: Int, type: String) = error("No ranking request")
        override suspend fun getWeeklyMustWatchVideos() = error("No weekly request")
        override suspend fun getPreciousVideos() = error("No precious request")
        override suspend fun getPreviewVideoUrl(bvid: String, cid: Long) = error("No media request")
        override suspend fun isVerticalVideo(bvid: String, aid: Long) = error("No dimension request")
    }
    private val context = DesktopPluginContext(DesktopPluginStore(folder))
    private val blocked = object : DesktopHomeBlockedRequests {
        override fun getAllBlockedUps() = flowOf(emptyList<com.android.purebilibili.core.database.entity.BlockedUp>())
        override suspend fun blockUp(mid: Long, name: String, face: String) = error("No block action")
        override suspend fun blockUpWithBilibiliSync(mid: Long, name: String, face: String) = error("No relationship action")
    }
    lateinit var vm: DesktopOriginalHomeViewModel
    suspend fun start() = onOwner {
        vm = DesktopOriginalHomeViewModel(DesktopHomeDataEnvironment(
            capturedEpoch = 91L, parentScope = scope, isCurrent = { current }, commitIfCurrent = ::commit,
            isLoggedIn = { loggedIn }, isPrivacyModeEnabledSync = { false },
            analytics = object : DesktopHomeIdentityAnalytics {
                override fun syncUserContext(mid: Long?, isVip: Boolean, privacyModeEnabled: Boolean) = Unit
            }, recommendationContext = context, followingCache = DesktopHomeFollowingCache(context.store, ::commit),
            incrementalTimelineRefresh = incremental, homeRefreshTipVisible = MutableStateFlow(false),
            video = videoRequests, history = historyRequests, live = unused(), messages = unused(), actions = unused(),
            follow = followRequests, blockedUps = blocked, following = unused(), feedback = { error("No feedback") }, todayWatchComputeDispatcher = compute,
        ))
    }
    suspend fun state(predicate: (HomeUiState) -> Boolean) = withTimeout(3_000) { vm.uiState.first(predicate) }
    suspend fun settled(): HomeUiState = state { it.categoryStates[it.currentCategory]?.isLoading == false }
    fun initialJob(): Job = vm.javaClass.getDeclaredField("categoryInitialLoadJob").also { it.isAccessible = true }.get(vm) as Job
    fun refreshJob(): Job {
        val request = vm.javaClass.getDeclaredField("desktopHomeRefresh").also { it.isAccessible = true }.get(vm)
        return request.javaClass.getDeclaredField("caller").also { it.isAccessible = true }.get(request) as Job
    }
    override fun close() {
        if (::vm.isInitialized) vm.close()
        scope.cancel()
        owner.close()
        folder.toFile().deleteRecursively()
    }

    companion object {
        fun video(bvid: String) = VideoItem(bvid = bvid, title = "Synthetic discovery item", duration = 120, owner = Owner(mid = 7, name = "Synthetic creator"))
        fun feed(vararg keys: String, usedBaseline: Boolean = false, updateNum: Int = 0) = DynamicFeedFetchResult(
            items = keys.mapIndexed { index, key -> DynamicItem(id_str = "dyn_$key", type = "DYNAMIC_TYPE_AV",
                modules = DynamicModules(module_dynamic = DynamicContentModule(major = DynamicMajor(type = "MAJOR_TYPE_ARCHIVE",
                    archive = ArchiveMajor(aid = (index + 1).toString(), bvid = key, title = "Synthetic $key", duration_text = "02:00"))))) },
            usedUpdateBaseline = usedBaseline, updateNum = updateNum,
        )
    }
}
