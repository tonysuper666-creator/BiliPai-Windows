package com.bilipai.desktop.ui

import com.android.purebilibili.core.events.*
import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.following.*
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.bilipai.desktop.plugins.DesktopPluginStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.test.*

/** Actual generated actions/Following VM and the mounted folder adapter. Only
 * protocol responses are synthetic; no HTTP, default profile, DLL or Window. */
class DesktopBrandSuccessBusinessTest {
    private class Harness {
        val directory = Files.createTempDirectory("brand-business-")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val events = java.util.concurrent.CopyOnWriteArrayList<BrandSuccessFeedback>()
        val changes = java.util.concurrent.CopyOnWriteArrayList<com.android.purebilibili.data.repository.FollowStateChange>()
        var account = 1L
        var source: Any = Any()
        private val accepted = source
        val bus = BrandSuccessEvents { scope.isActive }
        var response: (String, List<Any?>) -> Any = { name, _ -> when (name) {
            "getFavFolders" -> FavFolderResponse(data = FavFolderList(list = listOf(FavFolder(id = 9))))
            "getFollowings" -> FollowingsResponse(data = FollowingsData(listOf(FollowingUser(1), FollowingUser(2)), 2))
            "getRelationTags" -> RelationTagsResponse()
            "modifyRelation", "dealFavorite" -> SimpleApiResponse()
            else -> error("Unexpected synthetic protocol: $name")
        } }
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader, arrayOf(BilibiliApi::class.java)) {
            _, method, args -> response(method.name, args.orEmpty().toList())
        } as BilibiliApi
        fun owns() = scope.isActive && account == 1L && source === accepted
        fun commit(block: () -> Unit): Boolean = if (owns()) { block(); true } else false
        val favorites = DesktopFavoriteEnvironment(scope, api, null, null, null, ::owns,
            { "fixture-csrf" }, { 17L }, {}, null, null, null, null, null, null, { changes += it })
            .also { it.mountBrandFeedback(bus, ::commit) }
        val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { events += it } }
        fun folder(save: suspend (Long, Set<Long>, Set<Long>) -> Result<Boolean>) = DesktopFavoriteFolderEnvironment(
            scope, ::owns, { 17L }, { 1 }, { Result.success(emptyList()) }, save,
            { _, _, _ -> Result.success(true) }, {}, { _, _ -> }, {})
            .also { it.mountBrandFeedback(bus, favorites::captureBrandFeedback) }
        fun following() = FollowingListViewModel(DesktopFollowingEnvironment(favorites,
            DesktopFollowingCacheContext(DesktopPluginStore(directory), ::owns, ::commit)))
        suspend fun close() {
            bus.close(); scope.cancel(); scope.coroutineContext.job.join()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun actualQuickFavoriteCelebratesOnlyConfirmedAddition(): Unit = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.favorites.actions.favoriteVideo(17, true).getOrThrow())
            assertFalse(h.favorites.actions.favoriteVideo(17, false).getOrThrow())
            h.response = { _, _ -> SimpleApiResponse(code = -1, message = "synthetic failure") }
            assertTrue(h.favorites.actions.favoriteVideo(17, true, folderId = 9).isFailure)
            assertEquals(listOf(BrandSuccessKind.FAVORITE), h.events.map { it.kind })
        } finally { h.close() }
    }

    @Test fun actualFolderSaveDoesNotCelebrateRemoveOnlyOrEmptyAdd(): Unit = runBlocking {
        val h = Harness()
        try {
            val folder = h.folder { _, _, _ -> Result.success(true) }
            assertTrue(folder.updateFavoriteFolders(17, emptySet(), setOf(9)).isSuccess)
            assertTrue(folder.updateFavoriteFolders(17, emptySet(), emptySet()).isSuccess)
            assertTrue(h.events.isEmpty())
            assertTrue(folder.updateFavoriteFolders(17, setOf(10), setOf(9)).isSuccess)
            folder.confirmFavoriteSave(true, 1)
            assertEquals(listOf(BrandSuccessKind.FAVORITE), h.events.map { it.kind })
            folder.close()
        } finally { h.close() }
    }

    @Test fun failedFolderProtocolProducesNoDecorativeSuccess(): Unit = runBlocking {
        val h = Harness()
        try {
            val folder = h.folder { _, _, _ -> Result.failure(IllegalStateException("synthetic")) }
            assertTrue(folder.updateFavoriteFolders(17, setOf(9), emptySet()).isFailure)
            assertTrue(h.events.isEmpty()); folder.close()
        } finally { h.close() }
    }

    @Test fun actualSingleFollowAndUnfollowKeepOriginalStateEvents(): Unit = runBlocking {
        val h = Harness()
        try {
            assertTrue(h.favorites.actions.followUser(33, true).getOrThrow())
            assertFalse(h.favorites.actions.followUser(33, false).getOrThrow())
            assertEquals(listOf(true, false), h.changes.map { it.isFollowing })
            assertEquals(listOf(BrandSuccessKind.FOLLOW, BrandSuccessKind.UNFOLLOW), h.events.map { it.kind })
        } finally { h.close() }
    }

    @Test fun completeFollowingVmPartialBatchEmitsOnceAndRemovesOnlySuccesses(): Unit = runBlocking {
        val h = Harness()
        try {
            val previous = h.response
            h.response = { name, args -> if (name == "modifyRelation" && args[0] == 2L)
                SimpleApiResponse(code = -1, message = "permanent synthetic failure") else previous(name, args) }
            val vm = h.following()
            vm.loadFollowingList(17, forceRefresh = true)
            withTimeout(2_000) { vm.uiState.first { it is FollowingListUiState.Success } }
            val result = vm.batchUnfollow(listOf(FollowingUser(1), FollowingUser(2)))
            assertEquals(1, result.successCount); assertEquals(1, result.failedCount)
            assertEquals(listOf(2L), (vm.uiState.value as FollowingListUiState.Success).users.map { it.mid })
            assertEquals(listOf(1L), h.changes.map { it.mid })
            assertEquals(1, h.events.size)
            assertEquals(BrandSuccessKind.UNFOLLOW, h.events.single().kind)
            assertEquals("已取关 1 位，1 位失败", h.events.single().detail)
        } finally { h.close() }
    }

    @Test fun emptyAndAllFailedActualBatchProduceNoFeedback(): Unit = runBlocking {
        val h = Harness()
        try {
            h.response = { _, _ -> SimpleApiResponse(code = -1, message = "permanent failure") }
            val vm = h.following()
            assertEquals(0, vm.batchUnfollow(emptyList()).successCount)
            assertEquals(0, vm.batchUnfollow(listOf(FollowingUser(1))).successCount)
            assertTrue(h.events.isEmpty()); assertTrue(h.changes.isEmpty())
        } finally { h.close() }
    }

    @Test fun oldSourceResponseCannotPublishBrandSuccess(): Unit = runBlocking {
        val h = Harness()
        try {
            h.response = { name, _ ->
                assertEquals("dealFavorite", name); h.source = Any(); SimpleApiResponse()
            }
            assertTrue(h.favorites.actions.favoriteVideo(17, true, folderId = 9).isFailure)
            assertTrue(h.events.isEmpty())
        } finally { h.close() }
    }

    @Test fun actualCanceledApiCallerCannotPublishLateFeedback(): Unit = runBlocking {
        val h = Harness()
        try {
            val pending = CompletableDeferred<Continuation<Any?>>()
            h.response = { name, args ->
                assertEquals("dealFavorite", name)
                @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Any?>
                pending.complete(continuation.intercepted()); COROUTINE_SUSPENDED
            }
            val caller = h.scope.async { h.favorites.actions.favoriteVideo(17, true, folderId = 9) }
            val continuation = withTimeout(2_000) { pending.await() }
            caller.cancel(); continuation.resumeWith(Result.success(SimpleApiResponse())); caller.join()
            assertTrue(h.events.isEmpty())
        } finally { h.close() }
    }

    @Test fun accountRetirementAlsoInvalidatesAlreadyQueuedReceipt(): Unit = runBlocking {
        val h = Harness()
        try {
            h.favorites.actions.followUser(33, true).getOrThrow()
            val event = h.events.single()
            assertTrue(h.bus.isCurrent(event)); h.account = 2
            assertFalse(h.bus.isCurrent(event)); h.account = 1
            assertFalse(h.bus.isCurrent(event)) // no ABA resurrection
        } finally { h.close() }
    }

    @Test fun originalNoReplayAndBoundedDownloadIdentityAreRetained(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bus = BrandSuccessEvents { scope.isActive }
        val origin = DesktopBrandSuccessOrigin(coroutineContext.job, { scope.isActive }, { action -> action(); true })
        try {
            bus.favoriteSaved(origin)
            assertNull(withTimeoutOrNull(30) { bus.events.first() })
            val seen = mutableListOf<BrandSuccessFeedback>()
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { bus.events.collect { seen += it } }
            bus.downloadCompleted(origin, "task", 1L, "first")
            bus.downloadCompleted(origin, "task", 1L, "duplicate")
            bus.downloadCompleted(origin, "task", 2L, "different creation")
            assertEquals(listOf("first", "different creation"), seen.map { it.detail })
            repeat(256) { bus.downloadCompleted(origin, "other-$it", 1L, "other") }
            bus.downloadCompleted(origin, "task", 1L, "evicted oldest")
            assertEquals("evicted oldest", seen.last().detail)
            collector.cancelAndJoin()
        } finally { bus.close(); scope.cancel(); scope.coroutineContext.job.join() }
    }

    @Test fun decorationFailureCannotTurnActualFavoriteSuccessIntoFailure(): Unit = runBlocking {
        val h = Harness()
        try {
            h.favorites.mountBrandFeedback(h.bus) { error("synthetic carrier admission failure") }
            assertTrue(h.favorites.actions.favoriteVideo(17, true, folderId = 9).getOrThrow())
            assertTrue(h.events.isEmpty())
        } finally { h.close() }
    }
}
