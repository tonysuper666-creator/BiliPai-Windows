package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.*
import com.android.purebilibili.feature.dynamic.*
import com.bilipai.desktop.data.DesktopDynamicCache
import com.bilipai.desktop.plugins.DesktopPluginContext
import com.bilipai.desktop.plugins.DesktopPluginStore
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*

/** Real timeline/UID owners and original pagination repositories; their injected
 * in-memory transports record every actual offset. No account, GUI or player. */
class DesktopDynamicFailedPaginationTest {
    @Test fun appendFailureStopsAutomaticRequestsAndManualRetryKeepsTheSameTail(): Unit = runBlocking {
        val offsets = mutableListOf<String>()
        var fail = true
        val first = fixtureDynamic("first", 2)
        val last = fixtureDynamic("last", 1)
        val state = DesktopDynamicTimelineState("all", { _, offset, _ ->
            offsets += offset
            when {
                offset.isEmpty() -> response(listOf(first), "tail", true)
                fail -> DynamicFeedResponse(code = -352, message = "fixture append failure")
                else -> response(listOf(last), "end", false)
            }
        })
        assertTrue(state.initialize(false))
        val kept = state.page.items
        assertFalse(state.loadMore(false))
        assertNotNull(state.error)
        assertEquals(DynamicFeedErrorSource.APPEND, state.page.errorSource)
        assertTrue(state.page.hasMore)
        assertSame(kept, state.page.items)
        val failedRequestCount = offsets.size
        repeat(20) { assertFalse(state.loadMore(false)) }
        assertEquals(failedRequestCount, offsets.size)
        assertSame(kept, state.page.items)
        fail = false
        assertTrue(state.fetch(refresh = false, incrementalRefresh = false))
        assertEquals("tail", offsets.last())
        assertEquals(listOf("first", "last"), state.page.items.map { it.id_str })
        assertSame(first, state.page.items.first())
        assertNull(state.error)
        assertFalse(state.page.hasMore)
    }

    @Test fun initialFailureWithNoMessageAlsoStopsAutomaticPagingUntilExplicitRefresh(): Unit = runBlocking {
        var requests = 0
        var fail = true
        val state = DesktopDynamicTimelineState("video", { _, _, _ ->
            requests++
            if (fail) throw IllegalStateException()
            response(listOf(fixtureDynamic("recovered", 1)), "", false)
        })
        assertFalse(state.initialize(false))
        assertNotNull(state.error)
        assertNotNull(state.page.error)
        val failedRequestCount = requests
        repeat(20) { assertFalse(state.loadMore(false)) }
        assertEquals(failedRequestCount, requests)
        assertTrue(state.page.items.isEmpty())
        fail = false
        assertTrue(state.fetch(refresh = true, incrementalRefresh = false))
        assertEquals(listOf("recovered"), state.page.items.map { it.id_str })
        assertNull(state.error)
    }

    @Test fun selectedUserFailureStopsAutomaticPagingAndExplicitRetryPreservesRowsAndOffset(): Unit = runBlocking {
        val caches = mutableListOf<DesktopDynamicCache>()
        val directory = Files.createTempDirectory("bilipai-failed-dynamic-user-")
        val preferences = dynamicAccountTestPreferences(DesktopPluginContext(DesktopPluginStore(directory))) {
            caches += it
        }
        val offsets = mutableListOf<String>()
        var fail = true
        val retryEntered = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val state = DesktopDynamicUsersState(this, preferences, 42,
            { FollowingsData() }, { emptyList() }, { null }, { params ->
                val offset = params["offset"].orEmpty()
                offsets += offset
                when {
                    offset.isEmpty() -> response(listOf(tabsDynamic("first", 81)), "tail", true)
                    fail -> DynamicFeedResponse(code = -352, message = "fixture user append failure")
                    else -> {
                        retryEntered.complete(Unit)
                        releaseRetry.await()
                        response(listOf(tabsDynamic("last", 81)), "end", false)
                    }
                }
            }, { true })
        try {
            state.selectUser(81)
            eventually { !state.userLoading }
            assertNull(state.userError)
            assertTrue(state.hasUserMore)
            val kept = state.userItems
            state.loadMoreUser()
            eventually { !state.userLoading && state.userError != null }
            assertSame(kept, state.userItems)
            val failedRequestCount = offsets.size
            repeat(20) { state.loadMoreUser() }
            assertFalse(state.userLoading)
            assertEquals(failedRequestCount, offsets.size)
            fail = false
            state.retryUser()
            withTimeout(5_000) { retryEntered.await() }
            assertEquals("tail", offsets.last())
            assertSame(kept, state.userItems)
            releaseRetry.complete(Unit)
            eventually { !state.userLoading }
            assertEquals(listOf("first", "last"), state.userItems.map { it.id_str })
            assertNull(state.userError)
            assertFalse(state.hasUserMore)
        } finally {
            releaseRetry.complete(Unit)
            state.close()
            caches.forEach { it.shutdownForRestore() }
        }
    }

    @Test
    fun `dynamic feed pauses automatic pagination after a request error`() {
        assertFalse(
            shouldLoadMoreDynamicFeed(
                furthestVisibleItemIndex = 2,
                totalItemsCount = 3,
                allowAutomaticLoadMore = true,
                isLoading = false,
                hasMore = true,
                error = "请求数据发生错误",
            )
        )
        assertTrue(
            shouldLoadMoreDynamicFeed(
                furthestVisibleItemIndex = 2,
                totalItemsCount = 3,
                allowAutomaticLoadMore = true,
                isLoading = false,
                hasMore = true,
                error = null,
            )
        )
    }

    private fun response(items: List<DynamicItem>, offset: String, more: Boolean) =
        DynamicFeedResponse(data = DynamicFeedData(items, offset, more))

    private suspend fun eventually(ready: () -> Boolean) = withTimeout(5_000) {
        while (!ready()) delay(5)
    }
}
