package com.bilipai.desktop.ui

import com.android.purebilibili.data.model.response.DynamicFeedData
import com.android.purebilibili.data.model.response.DynamicFeedResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cold-start flash: the empty text must wait for the first settled fetch; no network, GPU or window involved. */
class DesktopDynamicEmptyStateTest {
    private fun state(
        firstFetchSettled: Boolean = false, busy: Boolean = false, failed: Boolean = false,
        hasPageItems: Boolean = false, hasDisplayedItems: Boolean = false,
    ) = resolveDesktopDynamicEmptyState(firstFetchSettled, busy, failed, hasPageItems, hasDisplayedItems)

    private fun author(
        loading: Boolean = false, failed: Boolean = false, hasMore: Boolean = false, hasDisplayedItems: Boolean = false,
    ) = resolveDesktopDynamicAuthorEmptyState(loading, failed, hasMore, hasDisplayedItems)

    private fun settledSlot(timeline: DesktopDynamicTimelineState) = resolveDesktopDynamicEmptyState(
        timeline.firstFetchSettled, timeline.busy, timeline.error != null, timeline.page.items.isNotEmpty(), false)

    @Test
    fun beforeTheFirstFetchTheSlotIsLoadingNotEmpty() {
        // The window between the first composition and the first request (busy still false) used to show the empty text.
        assertEquals(DesktopDynamicEmptyState.LOADING, state())
        assertEquals(DesktopDynamicEmptyState.LOADING, state(hasPageItems = true))
    }

    @Test
    fun anActiveRequestAFailureOrRowsOwnTheSlot() {
        assertEquals(DesktopDynamicEmptyState.NONE, state(busy = true))
        assertEquals(DesktopDynamicEmptyState.NONE, state(firstFetchSettled = true, busy = true))
        assertEquals(DesktopDynamicEmptyState.NONE, state(failed = true))
        assertEquals(DesktopDynamicEmptyState.NONE, state(firstFetchSettled = true, failed = true))
        assertEquals(DesktopDynamicEmptyState.NONE, state(hasDisplayedItems = true, hasPageItems = true))
        assertEquals(DesktopDynamicEmptyState.NONE, state(firstFetchSettled = true, hasDisplayedItems = true, hasPageItems = true))
    }

    @Test
    fun afterTheFirstFetchAnEmptyFeedAndAFilteredFeedAreDistinguished() {
        assertEquals(DesktopDynamicEmptyState.EMPTY, state(firstFetchSettled = true))
        assertEquals(DesktopDynamicEmptyState.FILTERED, state(firstFetchSettled = true, hasPageItems = true))
    }

    @Test
    fun theSelectedAuthorSlotIsLoadingWhileMorePagesExistAndEmptyOnlyWhenNothingIsLeft() {
        // A category page without a row of the author is a normal state: no empty text between two automatic pages.
        assertEquals(DesktopDynamicEmptyState.LOADING, author(hasMore = true))
        assertEquals(DesktopDynamicEmptyState.EMPTY, author(hasMore = false))
    }

    @Test
    fun theSelectedAuthorSlotIsOwnedByRowsAFailureOrARunningRequest() {
        for (hasMore in listOf(true, false)) {
            assertEquals(DesktopDynamicEmptyState.NONE, author(loading = true, hasMore = hasMore))
            assertEquals(DesktopDynamicEmptyState.NONE, author(failed = true, hasMore = hasMore))
            assertEquals(DesktopDynamicEmptyState.NONE, author(hasDisplayedItems = true, hasMore = hasMore))
            assertEquals(DesktopDynamicEmptyState.NONE,
                author(loading = true, failed = true, hasMore = hasMore, hasDisplayedItems = true))
        }
    }

    @Test
    fun aFirstResponseThatTheFollowGuardDiscardsStillSettlesSoTheSlotIsNotAnEndlessSpinner(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val timeline = DesktopDynamicTimelineState("video", { _, _, _ ->
            entered.complete(Unit)
            release.await()
            DynamicFeedResponse(data = DynamicFeedData())
        })
        assertFalse(timeline.firstFetchSettled)
        val first = async { timeline.initialize(false) }
        entered.await()
        // An author unfollow accepted while the first request is in flight wins over that pre-confirmation response.
        timeline.applyAuthorUnfollow(7L)
        release.complete(Unit)
        assertFalse(first.await())
        assertFalse(timeline.initialized)
        assertTrue(timeline.firstFetchSettled)
        assertFalse(timeline.busy)
        assertNull(timeline.error)
        assertEquals(DesktopDynamicEmptyState.EMPTY, settledSlot(timeline))
    }

    @Test
    fun aCancelledFirstFetchIsNoVerdictAndAFailedOrAcceptedOneIs(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val cancelled = DesktopDynamicTimelineState("video", { _, _, _ ->
            entered.complete(Unit)
            gate.await()
            DynamicFeedResponse(data = DynamicFeedData())
        })
        val job = launch { cancelled.initialize(false) }
        entered.await()
        job.cancelAndJoin()
        assertFalse(cancelled.firstFetchSettled)
        assertFalse(cancelled.busy)
        assertEquals(DesktopDynamicEmptyState.LOADING, settledSlot(cancelled))

        val failed = DesktopDynamicTimelineState("video", { _, _, _ -> DynamicFeedResponse(code = -352, message = "fixture failure") })
        assertFalse(failed.initialize(false))
        assertTrue(failed.firstFetchSettled)
        assertNotNull(failed.error)
        assertEquals(DesktopDynamicEmptyState.NONE, settledSlot(failed))

        val accepted = DesktopDynamicTimelineState("video", { _, _, _ -> DynamicFeedResponse(data = DynamicFeedData()) })
        assertTrue(accepted.initialize(false))
        assertTrue(accepted.firstFetchSettled)
        assertTrue(accepted.initialized)
        assertEquals(DesktopDynamicEmptyState.EMPTY, settledSlot(accepted))
    }
}
