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
class DesktopHomeRequestOwnershipTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private inner class Harness : AutoCloseable {
        val folder = Files.createTempDirectory("actual-home-load-")
        val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(scheduler))
        val gate = Any()
        @Volatile var current = true
        var loggedIn = false
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
            override suspend fun getPopularVideos(page: Int): Result<List<VideoItem>> { requests += "popular" to page; return Result.success(listOf(video("BV_popular"))) }
            override suspend fun getRankingVideos(rid: Int, type: String): Result<List<VideoItem>> { requests += "ranking" to rid; return Result.success(listOf(video("BV_ranking"))) }
            override suspend fun getWeeklyMustWatchVideos(): Result<List<VideoItem>> { requests += "weekly" to 1; return Result.success(listOf(video("BV_weekly"))) }
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
        fun start() {
            vm = DesktopOriginalHomeViewModel(DesktopHomeDataEnvironment(
                capturedEpoch = 91L, parentScope = scope, isCurrent = { current }, commitIfCurrent = ::commit,
                isLoggedIn = { loggedIn }, isPrivacyModeEnabledSync = { false },
                analytics = object : DesktopHomeIdentityAnalytics {
                    override fun syncUserContext(mid: Long?, isVip: Boolean, privacyModeEnabled: Boolean) = Unit
                }, recommendationContext = context, followingCache = DesktopHomeFollowingCache(context.store, ::commit),
                incrementalTimelineRefresh = MutableStateFlow(false), homeRefreshTipVisible = MutableStateFlow(false),
                video = videoRequests, history = unused(), live = unused(), messages = unused(), actions = unused(),
                follow = unused(), blockedUps = blocked, following = unused(), feedback = { error("No feedback") },
            ))
        }
        suspend fun state(predicate: (HomeUiState) -> Boolean) = withTimeout(3_000) { vm.uiState.first(predicate) }
        suspend fun settled(): HomeUiState = state { it.categoryStates[it.currentCategory]?.isLoading == false }
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun until(predicate: () -> Boolean) = runBlocking {
            withTimeout(3_000) {
                while (!predicate()) { scheduler.runCurrent(); yield() }
            }
        }
        fun initialJob(): Job = vm.javaClass.getDeclaredField("categoryInitialLoadJob").also { it.isAccessible = true }.get(vm) as Job
        fun refreshJob(): Job {
            val request = vm.javaClass.getDeclaredField("desktopHomeRefresh").also { it.isAccessible = true }.get(vm)
            return request.javaClass.getDeclaredField("caller").also { it.isAccessible = true }.get(request) as Job
        }
        override fun close() {
            if (::vm.isInitialized) vm.close()
            scope.cancel()
            folder.toFile().deleteRecursively()
        }
    }
    companion object { private fun video(bvid: String) = VideoItem(bvid = bvid, title = "Synthetic discovery item") }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun duplicatePaginationBeforeDispatchAdmitsOnlyOneActualRequest() {
        Harness().use { h ->
            h.start(); h.until { h.initialJob().isCompleted }
            val gate = CompletableDeferred<Result<List<VideoItem>>>()
            h.recommend = { gate.await() }
            h.vm.loadMore(); h.vm.loadMore()
            h.scheduler.runCurrent()
            assertEquals(2, h.requests.count { it.first == "recommend" }, "initial plus one append")
            gate.complete(Result.success(listOf(video("BV_page"))))
            h.scheduler.runCurrent()
            h.scheduler.advanceTimeBy(100); h.scheduler.runCurrent()
            h.until { h.vm.uiState.value.categoryStates.getValue(HomeCategory.RECOMMEND).videos.size == 2 }
            assertEquals(listOf("BV_initial", "BV_page"), h.vm.uiState.value.categoryStates.getValue(HomeCategory.RECOMMEND).videos.map { it.bvid })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun refreshWaitsForActualCancelledInitialCleanup() {
        Harness().use { h ->
            val cleanup = CompletableDeferred<Unit>()
            var enteredCleanup = false
            var calls = 0
            h.recommend = {
                calls++
                if (calls == 1) {
                    try { awaitCancellation() } finally {
                        enteredCleanup = true
                        withContext(NonCancellable) { cleanup.await() }
                    }
                }
                Result.success(listOf(video("BV_refresh")))
            }
            h.start(); h.scheduler.runCurrent()
            h.vm.refresh(); h.scheduler.runCurrent()
            assertTrue(enteredCleanup, "refresh should cancel initial caller")
            assertEquals(1, calls, "successor must wait for the old finally")
            cleanup.complete(Unit); h.until { calls == 2 && h.vm.uiState.value.categoryStates.getValue(HomeCategory.RECOMMEND).videos.any { it.bvid == "BV_refresh" } }
            assertEquals(2, calls)
            assertEquals("BV_refresh", h.vm.uiState.value.categoryStates.getValue(HomeCategory.RECOMMEND).videos.single().bvid)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancelledBeforeDispatchReleasesAdmissionForRefresh() {
        Harness().use { h ->
            h.start()
            h.vm.refresh()
            h.until { h.requests.any { it.first == "recommend" } && h.vm.uiState.value.categoryStates[HomeCategory.RECOMMEND]?.videos?.isNotEmpty() == true && !h.vm.isRefreshing.value }
            assertEquals(1, h.requests.count { it.first == "recommend" })
            assertEquals("BV_initial", h.vm.uiState.value.categoryStates.getValue(HomeCategory.RECOMMEND).videos.single().bvid)
            h.vm.loadMore(); h.scheduler.runCurrent()
            assertEquals(2, h.requests.count { it.first == "recommend" })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun anotherCategoryStartsWhileOldCategoryCancellationCleansUp() {
        Harness().use { h ->
            val cleanup = CompletableDeferred<Unit>()
            var enteredCleanup = false
            h.recommend = {
                try { awaitCancellation() } finally {
                    enteredCleanup = true
                    withContext(NonCancellable) { cleanup.await() }
                }
            }
            h.start(); h.scheduler.runCurrent()
            h.vm.switchCategory(HomeCategory.GAME)
            h.until { h.vm.uiState.value.categoryStates[HomeCategory.GAME]?.videos?.isNotEmpty() == true }
            assertTrue(enteredCleanup)
            assertTrue(h.requests.any { it.first == "region" })
            assertEquals("BV_region", h.vm.uiState.value.categoryStates.getValue(HomeCategory.GAME).videos.single().bvid)
            cleanup.complete(Unit); h.scheduler.runCurrent()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun queuedPopularSubcategoryDoesNotRunForLaterSelection() {
        Harness().use { h ->
            h.start(); h.until { h.initialJob().isCompleted }
            h.vm.switchCategory(HomeCategory.POPULAR)
            h.until { h.vm.uiState.value.popularCategoryStates[PopularSubCategory.COMPREHENSIVE]?.videos?.isNotEmpty() == true }
            h.vm.switchPopularSubCategory(PopularSubCategory.RANKING)
            h.vm.switchPopularSubCategory(PopularSubCategory.WEEKLY)
            h.until { h.vm.uiState.value.popularCategoryStates[PopularSubCategory.WEEKLY]?.videos?.isNotEmpty() == true }
            assertFalse(h.requests.any { it.first == "ranking" })
            assertEquals(1, h.requests.count { it.first == "weekly" })
            assertEquals("BV_weekly", h.vm.uiState.value.popularCategoryStates.getValue(PopularSubCategory.WEEKLY).videos.single().bvid)
        }
    }
}
