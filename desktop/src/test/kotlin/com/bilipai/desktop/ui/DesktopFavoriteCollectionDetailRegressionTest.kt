package com.bilipai.desktop.ui

import com.android.purebilibili.core.network.BilibiliApi
import com.android.purebilibili.core.network.SpaceApi
import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.list.DesktopFavoriteEnvironment
import com.android.purebilibili.feature.space.SeasonSeriesDetailViewModel
import com.android.purebilibili.feature.video.player.PlaylistItem
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.test.*

/** Real generated original VM/repository and Root's guarded API. Protocol replies
 * are bounded in memory; no window, network, account, device or native media. */
class DesktopFavoriteCollectionDetailRegressionTest {
    private class Fixture {
        private val owner = SupervisorJob()
        private val scope = CoroutineScope(owner + Dispatchers.Default)
        @Volatile var presented = true
        var reply: (String, List<Any?>) -> Any = { name, _ -> error("Unprepared protocol reply: $name") }
        val calls = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
        val feedback = CopyOnWriteArrayList<String>()
        private val api = Proxy.newProxyInstance(BilibiliApi::class.java.classLoader,
            arrayOf(BilibiliApi::class.java)) { _, method, arguments ->
            check(method.name in setOf("getFavoriteList", "getFavoriteSeasonList", "batchDelFavResource",
                "copyFavResources", "moveFavResources")) { "Unexpected protocol: ${method.name}" }
            val values = arguments.orEmpty().filterNot { it is Continuation<*> }
            calls += method.name to values
            reply(method.name, values)
        } as BilibiliApi
        private val space = Proxy.newProxyInstance(SpaceApi::class.java.classLoader,
            arrayOf(SpaceApi::class.java)) { _, method, _ -> error("Favorite detail called Space API: ${method.name}") } as SpaceApi
        val environment = DesktopFavoriteEnvironment(scope, api, space, null, null,
            stillOwned = { presented && owner.isActive }, readCsrf = { "fixture-csrf" }, readMid = { 101L },
            feedback = { feedback += it }, historyChanges = null, getCachedPosition = null,
            privacyModeEnabled = null, watchLaterChanged = null, readAccessToken = null, readAccessTokenPlatform = null)
        val vm = SeasonSeriesDetailViewModel(environment)
        suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
        suspend fun close() = owner.cancelAndJoin()
    }

    private fun page(first: Int, last: Int, more: Boolean, owner: Long = 202L, total: Int = 65) =
        FavoriteResourceResponse(data = FavoriteResourceData(
            info = FavoriteInfo(mid = owner, upper = Upper(mid = owner, name = "fixture"), media_count = total),
            medias = (first..last).map { FavoriteData(id = it.toLong(), type = 2, title = "video $it", bvid = "BV-fixture-$it") },
            has_more = more))

    @Test fun subscribedSeasonContinuesPastFortyUntilServerEnd(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.reply = { name, args ->
                assertEquals("getFavoriteSeasonList", name)
                assertEquals(900L, args[0])
                val pn = args[1] as Int
                page((pn - 1) * 20 + 1, minOf(pn * 20, 65), pn < 4)
            }
            f.vm.init("favorite_season", 900L, 202L, "教程")
            f.await { !f.vm.uiState.value.isLoading && f.vm.uiState.value.items.size == 20 }
            for (pn in 2..4) {
                assertTrue(f.vm.hasMoreState.value)
                f.vm.loadMore()
                f.await { !f.vm.isLoadingMoreState.value && f.vm.favoriteDetailProgressState.value.currentPage == pn }
            }
            assertEquals((1L..65L).toList(), f.vm.uiState.value.items.map { it.aid })
            assertFalse(f.vm.hasMoreState.value)
            assertNull(f.vm.uiState.value.loadMoreError)
            f.vm.loadMore()
            assertEquals(listOf(1, 2, 3, 4), f.calls.map { it.second[1] })
        } finally { f.close() }
    }

    @Test fun nextPageFailureKeepsItemsAndPageUntilExplicitSamePageRetry(): Unit = runBlocking {
        val f = Fixture()
        try {
            var failSecond = true
            f.reply = { _, args ->
                if (args[1] == 1) page(1, 20, true)
                else if (failSecond) FavoriteResourceResponse(code = -799, message = "第二页失败")
                else page(21, 40, false)
            }
            f.vm.init("favorite_season", 900L, 202L, "教程")
            f.await { !f.vm.uiState.value.isLoading && f.vm.uiState.value.items.size == 20 }
            val first = f.vm.uiState.value.items
            f.vm.loadMore()
            f.await { !f.vm.isLoadingMoreState.value && f.vm.uiState.value.loadMoreError != null }
            assertEquals(first, f.vm.uiState.value.items)
            assertEquals(1, f.vm.favoriteDetailProgressState.value.currentPage)
            assertTrue(f.vm.hasMoreState.value)
            f.vm.loadMore()
            assertEquals(listOf(1, 2), f.calls.map { it.second[1] })
            failSecond = false
            f.vm.retryLoadMore()
            f.await { !f.vm.isLoadingMoreState.value && f.vm.uiState.value.items.size == 40 }
            assertEquals(listOf(1, 2, 2), f.calls.map { it.second[1] })
            assertEquals(2, f.vm.favoriteDetailProgressState.value.currentPage)
            assertNull(f.vm.uiState.value.loadMoreError)
        } finally { f.close() }
    }

    @Test fun seasonIdsNeverReachFavoriteFolderMutationEndpoints(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.reply = { _, _ -> page(1, 2, false) }
            f.vm.init("favorite_season", 900L, 101L, "合集")
            f.await { !f.vm.uiState.value.isLoading }
            assertFalse(f.vm.canCopyFavoriteResources)
            assertFalse(f.vm.canManageFavoriteResources)
            assertFalse(f.vm.uiState.value.canRemoveItems)
            f.vm.removeFavoriteResources(setOf(1L))
            f.vm.copyOrMoveFavoriteResources(setOf(1L), 777L, copy = true)
            f.vm.copyOrMoveFavoriteResources(setOf(1L), 777L, copy = false)
            assertEquals(listOf("getFavoriteSeasonList"), f.calls.map { it.first })
        } finally { f.close() }
    }

    @Test fun foreignFolderAllowsCopyWhileBlockingMoveAndRemoval(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.reply = { name, _ -> if (name == "getFavoriteList") page(1, 2, false, owner = 202L) else SimpleApiResponse() }
            f.vm.init("favorite", 501L, 101L, "别人的收藏")
            f.await { !f.vm.uiState.value.isLoading }
            assertTrue(f.vm.canCopyFavoriteResources)
            assertFalse(f.vm.canManageFavoriteResources)
            f.vm.removeFavoriteResources(setOf(1L))
            f.vm.copyOrMoveFavoriteResources(setOf(1L), 777L, copy = false)
            assertEquals(listOf("getFavoriteList"), f.calls.map { it.first })
            f.vm.copyOrMoveFavoriteResources(setOf(1L), 777L, copy = true)
            f.await { !f.vm.isManagingState.value && f.calls.size == 2 }
            assertEquals("copyFavResources", f.calls.last().first)
            assertEquals(listOf<Any?>(501L, 777L, 202L), f.calls.last().second.take(3))
            assertEquals(2, f.vm.uiState.value.items.size)
        } finally { f.close() }
    }

    @Test fun authoritativeResponseOwnerEnablesOnlyActualOwnFolderRemoval(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.reply = { name, _ -> if (name == "getFavoriteList") page(1, 2, false, owner = 101L) else SimpleApiResponse() }
            f.vm.init("favorite", 501L, 202L, "我的收藏")
            f.await { !f.vm.uiState.value.isLoading }
            assertTrue(f.vm.canManageFavoriteResources)
            assertTrue(f.vm.uiState.value.canRemoveItems)
            f.vm.removeFavoriteResources(setOf(1L))
            f.await { !f.vm.isManagingState.value && f.calls.any { it.first == "batchDelFavResource" } }
            val remove = f.calls.single { it.first == "batchDelFavResource" }.second
            assertEquals(501L, remove[0])
            assertEquals("1:2", remove[1])
        } finally { f.close() }
    }

    @Test fun retiredOwnerRejectsLatePageBeforeItCanReplaceVisibleItems(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.reply = { _, args ->
                if (args[1] == 1) page(1, 20, true)
                else { f.presented = false; page(21, 40, false) }
            }
            f.vm.init("favorite_season", 900L, 202L, "教程")
            f.await { !f.vm.uiState.value.isLoading && f.vm.uiState.value.items.size == 20 }
            val first = f.vm.uiState.value.items
            f.vm.loadMore()
            f.await { f.calls.size == 2 && !f.vm.isLoadingMoreState.value }
            assertEquals(first, f.vm.uiState.value.items)
            assertEquals(1, f.vm.favoriteDetailProgressState.value.currentPage)
            assertNull(f.vm.uiState.value.loadMoreError)
        } finally { f.close() }
    }

    @Test fun playlistKeepsLegacyDefaultsAndRoundTripsCurrentOriginalMetadata() {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val old = json.decodeFromString<PlaylistItem>("""{"bvid":"BV-fixture","title":"old","cover":"","owner":"up"}""")
        assertNull(old.playCount); assertNull(old.commentCount); assertNull(old.publishedAt)
        val current = old.copy(playCount = 123L, commentCount = 45L, publishedAt = 1_792_000_000L)
        assertEquals(current, json.decodeFromString<PlaylistItem>(json.encodeToString(current)))
    }
}
