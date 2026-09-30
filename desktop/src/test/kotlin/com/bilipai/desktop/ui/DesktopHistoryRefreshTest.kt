package com.bilipai.desktop.ui

import androidx.compose.foundation.lazy.LazyListState
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import com.android.purebilibili.core.refresh.HistoryRefreshSuppression
import com.bilipai.desktop.data.reportDesktopPlaybackHeartbeat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.parallel.ResourceLock
import kotlin.test.*

@ResourceLock("upstream-history-refresh-bus")
class DesktopHistoryRefreshTest {
    @Test fun invalidationPreservesRowsCursorAndActualScrollPositionUntilSuccess() {
        val scroll = LazyListState(3, 27)
        val page = CommunityFeedState<String, Int>(scroll)
        page.acceptBatch(0, CommunityBatch(listOf("old"), 2), true) { it }
        page.invalidate()
        assertEquals(listOf("old"), page.rows); assertEquals(2, page.next)
        assertSame(scroll, page.scroll); assertEquals(3, page.scroll.firstVisibleItemIndex); assertEquals(27, page.scroll.firstVisibleItemScrollOffset)
        assertFalse(page.initialized); assertEquals(1L, page.reloadRevision)
        assertTrue(page.acceptBatch(1, CommunityBatch(listOf("latest"), 3), true) { it })
        assertEquals(listOf("latest"), page.rows); assertEquals(3, page.next); assertTrue(page.initialized)
        assertSame(scroll, page.scroll); assertEquals(3, page.scroll.firstVisibleItemIndex)
    }

    @Test fun historyInvalidationDoesNotInvalidateSearchFavoritesOrAnotherAccountsMemory() {
        val current = DesktopBrowseMemory(); val previousAccount = DesktopBrowseMemory()
        val history = current.feeds.page<String, Int>(Pair(PersonalSection.HISTORY, "current-account"))
        val favorites = current.feeds.page<String, Int>(Pair(PersonalSection.FAVORITES, "current-account"))
        val search = current.feeds.page<String, Int>(Pair(CommunitySection.SEARCH, "current-account"))
        val previousHistory = previousAccount.feeds.page<String, Int>(Pair(PersonalSection.HISTORY, "previous-account"))
        listOf(history, favorites, search, previousHistory).forEach { it.acceptBatch(0, CommunityBatch(listOf("saved"), 2), true) { row -> row } }
        current.invalidateCloudHistory()
        assertFalse(history.initialized); assertEquals(1L, history.reloadRevision)
        listOf(favorites, search, previousHistory).forEach { assertTrue(it.initialized); assertEquals(0L, it.reloadRevision) }
    }

    @Test fun staleResponsesCannotOverwriteTheInvalidatedPageBeforeRecomposition() {
        val page = CommunityFeedState<String, Int>()
        page.acceptBatch(0, CommunityBatch(listOf("old"), 2), true) { it }
        val requestRevision = page.reloadRevision
        page.invalidate()
        assertFalse(page.acceptBatch(requestRevision, CommunityBatch(listOf("stale server response"), 99), false) { it })
        assertFalse(page.acceptFailure(requestRevision, IllegalStateException("stale failure"), 2, false))
        assertEquals(listOf("old"), page.rows); assertEquals(2, page.next); assertNull(page.failure)
    }

    @Test fun reloadFailurePreservesTheOldRowsAndCanBeRetriedWithCurrentRevision() {
        val page = CommunityFeedState<String, Int>()
        page.acceptBatch(0, CommunityBatch(listOf("old"), 2), true) { it }
        page.invalidate()
        val error = java.io.IOException("fixture")
        assertTrue(page.acceptFailure(1, error, 1, true))
        assertSame(error, page.failure); assertEquals(listOf("old"), page.rows); assertEquals(2, page.next); assertFalse(page.initialized)
        assertEquals(1, page.failedCursor); assertTrue(page.failedReplace)
        assertTrue(page.acceptBatch(1, CommunityBatch(listOf("fresh", "fresh"), null), true) { it })
        assertEquals(listOf("fresh"), page.rows); assertNull(page.next); assertNull(page.failure); assertNull(page.failedCursor)
    }

    @Test fun repeatedInvalidationsLeaveOneFreshRevisionAndRejectEveryOlderRequest() {
        val page = CommunityFeedState<String, Int>()
        repeat(4) { page.invalidate() }
        (0L..3L).forEach { revision -> assertFalse(page.acceptBatch(revision, CommunityBatch(listOf("stale"), 2), true) { it }) }
        assertTrue(page.acceptBatch(4, CommunityBatch(listOf("current"), null), true) { it })
        assertEquals(listOf("current"), page.rows)
    }

    @Test fun persistentShellSubscriberInvalidatesHistoryWhileHistoryScreenIsAbsent(): Unit = runBlocking {
        val memory = DesktopBrowseMemory()
        val page = memory.feeds.page<String, Int>(Pair(PersonalSection.HISTORY, 1L))
        page.acceptBatch(0, CommunityBatch(listOf("history retained after navigation"), 2), true) { it }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { HistoryRefreshBus.changes.collect { memory.invalidateCloudHistory() } }
        try {
            HistoryRefreshBus.notifyChanged()
            withTimeout(1_000) { while (page.reloadRevision == 0L) yield() }
            assertFalse(page.initialized); assertEquals(listOf("history retained after navigation"), page.rows)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun originalNestedDetailsSuppressionCoalescesEventsUntilTheLastDetailsCloses(): Unit = runBlocking {
        var changes = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { HistoryRefreshBus.changes.collect { changes++ } }
        try {
            HistoryRefreshSuppression.suppress(); HistoryRefreshSuppression.suppress()
            repeat(5) { HistoryRefreshBus.notifyChanged() }
            yield(); assertEquals(0, changes)
            HistoryRefreshSuppression.resume(); yield(); assertEquals(0, changes)
            HistoryRefreshSuppression.resume()
            withTimeout(1_000) { while (changes == 0) yield() }
            assertEquals(1, changes)
        } finally {
            while (HistoryRefreshSuppression.isSuppressed) HistoryRefreshSuppression.resume()
            collector.cancelAndJoin()
        }
    }

    @Test fun successfulAuthenticatedReportsNotifyButPrivacyFailureAndStaleSessionsDoNot(): Unit = runBlocking {
        var changes = 0
        val notify = { changes++; Unit }
        assertTrue(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { 10 }, { "csrf" }, "BV1", 3, 10, 9, 123,
            onReported = notify) { 0 })
        assertEquals(1, changes)
        assertTrue(reportDesktopPlaybackHeartbeat({ true }, 1, { 1 }, { 10 }, { "csrf" }, "BV1", 3, 10, 9, 123,
            onReported = notify) { fail("Privacy must not send") })
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { 10 }, { "csrf" }, "BV1", 3, 10, 9, 123,
            onReported = notify) { -101 })
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { null }, { "csrf" }, "BV1", 3, 10, 9, 123,
            onReported = notify) { fail("Guest must not send") })
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { 1 }, { 10 }, { "" }, "BV1", 3, 10, 9, 123,
            onReported = notify) { fail("Missing csrf must not send") })
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { 2 }, { 10 }, { "csrf" }, "BV1", 3, 10, 9, 123,
            onReported = notify) { fail("Stale account must not send") })
        var epoch = 1L
        assertFalse(reportDesktopPlaybackHeartbeat({ false }, 1, { epoch }, { 10 }, { "csrf" }, "BV1", 3, 10, 9, 123,
            onReported = notify) { epoch = 2; 0 })
        assertEquals(1, changes)
    }
}
