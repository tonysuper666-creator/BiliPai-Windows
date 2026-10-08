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
class DesktopHomeLoadFailureTest {
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T

    private inner class Harness : AutoCloseable {
        val folder = Files.createTempDirectory("actual-home-load-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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

    @Test fun initialFailureUsesOriginalFullPageErrorAndManualRefreshClearsIt() = runBlocking<Unit> {
        Harness().use { h ->
            h.recommend = { Result.failure(Exception("initial unavailable")) }; h.start()
            val failed = h.state { it.categoryStates[HomeCategory.RECOMMEND]?.error == "initial unavailable" }
            val content = failed.categoryStates.getValue(HomeCategory.RECOMMEND)
            assertTrue(content.videos.isEmpty()); assertNull(content.loadMoreError); assertNull(content.refreshError)
            h.recommend = { Result.success(listOf(video("BV_retry"))) }
            h.vm.refresh()
            val loaded = h.state { it.categoryStates[HomeCategory.RECOMMEND]?.videos?.singleOrNull()?.bvid == "BV_retry" }
            assertNull(loaded.categoryStates.getValue(HomeCategory.RECOMMEND).error)
            assertEquals(2, h.requests.size)
        }
    }

    @Test fun actualPaginationFailurePreservesContentIndexAndManualRetryAppendsOnce() = runBlocking<Unit> {
        Harness().use { h ->
            h.start(); h.state { it.categoryStates[HomeCategory.RECOMMEND]?.videos?.isNotEmpty() == true }
            val before = h.vm.uiState.value.categoryStates.getValue(HomeCategory.RECOMMEND)
            h.recommend = { Result.failure(Exception("next page unavailable")) }; h.vm.loadMore()
            val failed = h.state { it.categoryStates[HomeCategory.RECOMMEND]?.loadMoreError != null }.categoryStates.getValue(HomeCategory.RECOMMEND)
            assertEquals(before.videos, failed.videos); assertEquals(before.pageIndex, failed.pageIndex)
            assertEquals(before.hasMore, failed.hasMore); assertFalse(failed.isLoading); assertNull(failed.error)
            assertEquals(2, h.requests.size)
            h.recommend = { Result.success(listOf(video("BV_next"))) }; h.vm.loadMore()
            val retried = h.state { it.categoryStates[HomeCategory.RECOMMEND]?.videos?.size == 2 }.categoryStates.getValue(HomeCategory.RECOMMEND)
            assertEquals(listOf("BV_initial", "BV_next"), retried.videos.map { it.bvid })
            assertNull(retried.loadMoreError); assertNull(retried.refreshError); assertEquals(3, h.requests.size)
        }
    }

    @Test fun actualRefreshFailureKeepsEndOfFeedAndDoesNotPublishSuccessOrUndo() = runBlocking<Unit> {
        Harness().use { h ->
            h.start(); h.state { it.categoryStates[HomeCategory.RECOMMEND]?.videos?.isNotEmpty() == true }
            h.recommend = { Result.success(emptyList()) }; h.vm.loadMore()
            val ended = h.state { it.categoryStates[HomeCategory.RECOMMEND]?.hasMore == false }
            val before = ended.categoryStates.getValue(HomeCategory.RECOMMEND)
            h.recommend = { Result.failure(Exception("refresh unavailable")) }; h.vm.refresh()
            val failed = h.state { it.categoryStates[HomeCategory.RECOMMEND]?.refreshError == "refresh unavailable" }
            val content = failed.categoryStates.getValue(HomeCategory.RECOMMEND)
            assertEquals(before.videos, content.videos); assertEquals(before.pageIndex, content.pageIndex)
            assertFalse(content.hasMore); assertNull(content.error); assertNull(content.loadMoreError)
            withTimeout(3_000) { h.vm.isRefreshing.first { !it } }
            assertFalse(h.vm.uiState.value.undoAvailable); assertNull(h.vm.uiState.value.refreshMessage)
            h.recommend = { Result.success(listOf(video("BV_refreshed"))) }; h.vm.refresh()
            val good = h.state { it.categoryStates[HomeCategory.RECOMMEND]?.videos?.singleOrNull()?.bvid == "BV_refreshed" }
            assertNull(good.categoryStates.getValue(HomeCategory.RECOMMEND).refreshError)
        }
    }

    @Test fun oldCategoryResponseCannotPublishAcrossSelectionAbaOrClearNewBusy() = runBlocking<Unit> {
        Harness().use { h ->
            val old = CompletableDeferred<Result<List<VideoItem>>>()
            h.recommend = { withContext(NonCancellable) { old.await() } }; h.start()
            val oldJob = h.initialJob()
            h.vm.switchCategory(HomeCategory.GAME)
            h.state { it.currentCategory == HomeCategory.GAME && it.categoryStates[HomeCategory.GAME]?.videos?.isNotEmpty() == true }
            val next = CompletableDeferred<Result<List<VideoItem>>>()
            h.recommend = { next.await() }; h.vm.switchCategory(HomeCategory.RECOMMEND)
            h.state { it.currentCategory == HomeCategory.RECOMMEND && it.categoryStates[HomeCategory.RECOMMEND]?.isLoading == true }
            old.complete(Result.failure(Exception("stale initial error"))); withTimeout(3_000) { oldJob.join() }
            val current = h.vm.uiState.value.categoryStates.getValue(HomeCategory.RECOMMEND)
            assertTrue(current.isLoading); assertNull(current.error); assertTrue(current.videos.isEmpty())
            next.complete(Result.success(listOf(video("BV_successor"))))
            h.state { it.categoryStates[HomeCategory.RECOMMEND]?.videos?.singleOrNull()?.bvid == "BV_successor" }
        }
    }

    @Test fun retiredActualEntryAccountReceiptRejectsUncooperativeLateResponse() = runBlocking<Unit> {
        Harness().use { h ->
            val response = CompletableDeferred<Result<List<VideoItem>>>()
            h.recommend = { withContext(NonCancellable) { response.await() } }; h.start()
            val job = h.initialJob()
            val before = h.vm.uiState.value
            synchronized(h.gate) { h.current = false }
            response.complete(Result.success(listOf(video("BV_old_account"))))
            withTimeout(3_000) { job.join() }
            assertSame(before, h.vm.uiState.value)
            assertFalse(h.vm.isCurrentOwner())
        }
    }

    @Test fun retainedCategoryRetryCallbacksCannotRefreshOrPageDifferentSelection() = runBlocking<Unit> {
        Harness().use { h ->
            h.start(); h.state { it.categoryStates[HomeCategory.RECOMMEND]?.videos?.isNotEmpty() == true }
            h.vm.switchCategory(HomeCategory.GAME)
            h.state { it.currentCategory == HomeCategory.GAME && it.categoryStates[HomeCategory.GAME]?.videos?.isNotEmpty() == true }
            val before = h.requests.size
            h.vm.refreshIfSelected(HomeCategory.RECOMMEND, PopularSubCategory.COMPREHENSIVE)
            h.vm.loadMoreIfSelected(HomeCategory.RECOMMEND, PopularSubCategory.COMPREHENSIVE)
            assertEquals(before, h.requests.size)
            assertEquals(HomeCategory.GAME, h.vm.uiState.value.currentCategory)
        }
    }

    @Test fun cancelledActualRefreshReleasesItsBusyBitsWithoutPublishingAndCanRetry() = runBlocking<Unit> {
        Harness().use { h ->
            h.start(); h.state { it.categoryStates[HomeCategory.RECOMMEND]?.videos?.isNotEmpty() == true }
            val before = h.vm.uiState.value.categoryStates.getValue(HomeCategory.RECOMMEND)
            val blocked = CompletableDeferred<Result<List<VideoItem>>>()
            h.recommend = { withContext(NonCancellable) { blocked.await() } }
            h.vm.refresh(); h.state { it.categoryStates[HomeCategory.RECOMMEND]?.isLoading == true }
            val caller = h.refreshJob(); caller.cancel()
            blocked.complete(Result.success(listOf(video("BV_cancelled"))))
            withTimeout(3_000) { caller.join() }
            val after = h.vm.uiState.value.categoryStates.getValue(HomeCategory.RECOMMEND)
            assertEquals(before.videos, after.videos); assertEquals(before.pageIndex, after.pageIndex)
            assertFalse(after.isLoading); assertFalse(h.vm.isRefreshing.value)
            assertNull(after.error); assertNull(after.refreshError)
            h.recommend = { Result.success(listOf(video("BV_after_cancel"))) }; h.vm.refresh()
            h.state { it.categoryStates[HomeCategory.RECOMMEND]?.videos?.singleOrNull()?.bvid == "BV_after_cancel" }
        }
    }
}
