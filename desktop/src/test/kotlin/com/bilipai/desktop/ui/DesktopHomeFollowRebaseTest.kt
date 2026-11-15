package com.bilipai.desktop.ui

import com.android.purebilibili.feature.home.HomeCategory
import com.android.purebilibili.data.repository.DynamicFeedFetchResult
import kotlinx.coroutines.*
import kotlin.test.*
import com.bilipai.desktop.ui.DesktopHomeFollowTodayFixture.Companion.feed

/** Real generated VM, admitted follow consumer and the UI's dissolve/removal action. */
class DesktopHomeFollowRebaseTest {
    private suspend fun DesktopHomeFollowTodayFixture.follow(incrementalEnabled: Boolean) {
        incremental.value = incrementalEnabled
        start(); settled()
        onOwner { loggedIn = true; vm.switchCategory(HomeCategory.FOLLOW) }
        state { it.currentCategory == HomeCategory.FOLLOW && it.videos.map { v -> v.bvid } == listOf("a", "b") }
    }

    @Test fun requestTimeRemovalSurvivesPrepend() = runBlocking { requestTimeRemoval(true) }
    @Test fun requestTimeRemovalSurvivesReplacement() = runBlocking { requestTimeRemoval(false) }

    private suspend fun requestTimeRemoval(prepend: Boolean) {
        DesktopHomeFollowTodayFixture().use { h ->
            h.follow(prepend)
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Result<DynamicFeedFetchResult>>()
            h.followResponse = { reached.complete(Unit); response.await() }
            h.onOwner { h.vm.refresh() }; withTimeout(3_000) { reached.await() }
            h.onOwner { h.vm.startVideoDissolve("a"); h.vm.completeVideoDissolve("a") }
            assertEquals(listOf("b"), h.vm.uiState.value.videos.map { it.bvid })
            response.complete(Result.success(feed("n", "a", "b")))
            h.state { !it.isLoading && it.videos.any { v -> v.bvid == "n" } }
            assertEquals(listOf("n", "b"), h.vm.uiState.value.videos.map { it.bvid }, "prepend=$prepend")
        }
    }

    @Test fun baselineProbeFullReplacementAlsoKeepsRequestTimeRemoval() = runBlocking {
        DesktopHomeFollowTodayFixture().use { h ->
            h.follow(false); h.baseline = "baseline"
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Result<DynamicFeedFetchResult>>()
            var calls = 0
            h.followResponse = {
                if (++calls == 1) Result.success(feed("n", usedBaseline = true, updateNum = 2))
                else { reached.complete(Unit); response.await() }
            }
            h.onOwner { h.vm.refresh() }; withTimeout(3_000) { reached.await() }
            h.onOwner { h.vm.completeVideoDissolve("a") }
            response.complete(Result.success(feed("n", "a", "b")))
            h.state { !it.isLoading && it.videos.any { v -> v.bvid == "n" } }
            assertEquals(listOf("n", "b"), h.vm.uiState.value.videos.map { it.bvid })
            assertEquals(listOf(true, false), h.followCalls.takeLast(2))
        }
    }

    @Test fun changingIncrementalFlagCannotChangeAnInFlightMerge() = runBlocking {
        for (requestFlag in listOf(true, false)) DesktopHomeFollowTodayFixture().use { h ->
            h.follow(requestFlag)
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Result<DynamicFeedFetchResult>>()
            h.followResponse = { reached.complete(Unit); response.await() }
            h.onOwner { h.vm.refresh() }; withTimeout(3_000) { reached.await() }
            h.onOwner { h.incremental.value = !requestFlag }
            // Let the actual flow collector consume the new flag before releasing the HTTP result.
            h.onOwner { }
            response.complete(Result.success(feed("n", "a")))
            h.state { !it.isLoading && it.videos.any { v -> v.bvid == "n" } }
            assertEquals(if (requestFlag) listOf("n", "a", "b") else listOf("n", "a"), h.vm.uiState.value.videos.map { it.bvid })
            assertEquals(requestFlag, h.followCalls.last())
        }
    }

    @Test fun flagChangeCannotSuppressTheRequiredFullPageAfterBaselineProbe() = runBlocking {
        DesktopHomeFollowTodayFixture().use { h ->
            h.follow(false); h.baseline = "baseline"
            val reached = CompletableDeferred<Unit>()
            val probe = CompletableDeferred<Result<DynamicFeedFetchResult>>()
            var calls = 0
            h.followResponse = {
                if (++calls == 1) { reached.complete(Unit); probe.await() }
                else Result.success(feed("full"))
            }
            h.onOwner { h.vm.refresh() }; withTimeout(3_000) { reached.await() }
            h.onOwner { h.incremental.value = true }; h.onOwner { }
            probe.complete(Result.success(feed("probe", usedBaseline = true, updateNum = 1)))
            h.state { !it.isLoading }
            assertEquals(listOf("full"), h.vm.uiState.value.videos.map { it.bvid })
            assertEquals(listOf(true, false), h.followCalls.takeLast(2))
        }
    }

    @Test fun retiredAccountAndCancelledCallerCannotPublishLateFollowResults() = runBlocking {
        for (retire in listOf(true, false)) DesktopHomeFollowTodayFixture().use { h ->
            h.follow(true)
            val reached = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Result<DynamicFeedFetchResult>>()
            h.followResponse = { withContext(NonCancellable) { reached.complete(Unit); response.await() } }
            h.onOwner { h.vm.refresh() }; withTimeout(3_000) { reached.await() }
            val caller = h.onOwner { h.refreshJob() }
            h.onOwner { if (retire) synchronized(h.gate) { h.current = false } else caller.cancel() }
            response.complete(Result.success(feed("stale")))
            withTimeout(3_000) { caller.join() }
            assertEquals(listOf("a", "b"), h.vm.uiState.value.videos.map { it.bvid })
            if (retire) assertFalse(h.vm.isCurrentOwner()) else assertFalse(h.vm.isRefreshing.value)
        }
    }
}
