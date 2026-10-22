package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure scrollbar estimate and paging policy of roadmap H3. No window, GPU or network. */
class DesktopLazyScrollbarsTest {
    private fun sample(
        total: Int, lanes: Int = 2, first: Int = 0, firstOffset: Int = 0, heights: List<Int> = List(4) { 300 },
        spacing: Int = 20, viewport: Int = 800, before: Int = 0, after: Int = 0, atEnd: Boolean = false,
    ) = DesktopStaggeredScrollSample(total, lanes, first, firstOffset, heights, spacing, viewport, before, after, atEnd)

    @Test fun anEmptyListIsExactlyOneViewportAndHasNoOffset() {
        val estimate = estimateDesktopStaggeredScroll(sample(total = 0, heights = emptyList()))
        assertEquals(DesktopScrollEstimate(0.0, 800.0, 800.0, 0.0), estimate)
        assertEquals(DesktopScrollTarget(0, 0), resolveDesktopStaggeredScrollTarget(100.0, estimate, 0, 2))
    }

    @Test fun itemsNotMeasuredYetAreTreatedAsEmpty() {
        val estimate = estimateDesktopStaggeredScroll(sample(total = 10, heights = listOf(0, 0)))
        assertEquals(800.0, estimate.contentSize)
        assertEquals(0.0, estimate.scrollOffset)
    }

    @Test fun aSingleRowThatFitsIsNoTallerThanTheViewport() {
        val estimate = estimateDesktopStaggeredScroll(sample(total = 2, heights = listOf(300, 300)))
        // One row of 300 px is shorter than the viewport, so the content is the viewport and nothing scrolls.
        assertEquals(800.0, estimate.contentSize)
        assertEquals(0.0, estimate.scrollOffset)
        assertEquals(320.0, estimate.rowExtentPx)
    }

    @Test fun manyRowsUseTheAverageItemExtentTimesTheRowCountPlusPadding() {
        // 10 items in 2 lanes = 5 rows of (average 250 + 20 spacing); the last spacing is not content; 16 + 24 padding.
        val estimate = estimateDesktopStaggeredScroll(sample(total = 10, heights = listOf(200, 300), before = 16, after = 24))
        assertEquals(5 * 270.0 - 20.0 + 40.0, estimate.contentSize)
        assertEquals(270.0, estimate.rowExtentPx)
        val scrolled = estimateDesktopStaggeredScroll(sample(total = 20, first = 5, firstOffset = 30, heights = listOf(200, 300)))
        // Item 5 is in row 2 of a two-lane grid.
        assertEquals(2 * 270.0 + 30.0, scrolled.scrollOffset)
    }

    @Test fun theOffsetIsClampedToTheScrollableRangeUntilTheRealEnd() {
        val near = estimateDesktopStaggeredScroll(sample(total = 6, lanes = 1, first = 5, firstOffset = 500))
        assertEquals(near.contentSize - near.viewportSize, near.scrollOffset)
        val negative = estimateDesktopStaggeredScroll(sample(total = 6, lanes = 1, first = -3, firstOffset = -10))
        assertEquals(0.0, negative.scrollOffset)
    }

    @Test fun atTheRealEndTheThumbReachesTheBottomOfTheTrack() {
        val end = estimateDesktopStaggeredScroll(sample(total = 40, first = 34, firstOffset = 0, atEnd = true))
        assertEquals(end.contentSize - end.viewportSize, end.scrollOffset)
    }

    @Test fun aThumbOffsetMapsBackToTheFirstItemOfItsRow() {
        val estimate = estimateDesktopStaggeredScroll(sample(total = 9, lanes = 3, heights = listOf(300)))
        // 3 rows of 320 px; 700 px is row 2 (index 6), 60 px into it, but the range ends at 940 - 800 = 140.
        assertEquals(DesktopScrollTarget(0, 140), resolveDesktopStaggeredScrollTarget(700.0, estimate, 9, 3))
        val tall = estimateDesktopStaggeredScroll(sample(total = 30, lanes = 3, heights = listOf(300)))
        assertEquals(DesktopScrollTarget(6, 60), resolveDesktopStaggeredScrollTarget(700.0, tall, 30, 3))
        assertEquals(DesktopScrollTarget(0, 0), resolveDesktopStaggeredScrollTarget(-50.0, tall, 30, 3))
        val bottom = resolveDesktopStaggeredScrollTarget(tall.contentSize, tall, 30, 3)
        assertEquals(true, bottom.index in 0..29 && bottom.index % 3 == 0)
        assertEquals(DesktopScrollTarget(0, 0), resolveDesktopStaggeredScrollTarget(Double.NaN, tall, 30, 3))
    }

    @Test fun onlyPlainPagingKeysArePagedAndCtrlIsNeverIntercepted() {
        assertEquals(DesktopFeedPageKey.PageUp, resolveDesktopFeedPageKey(Key.PageUp, false, false, false, false))
        assertEquals(DesktopFeedPageKey.PageDown, resolveDesktopFeedPageKey(Key.PageDown, false, false, false, false))
        assertEquals(DesktopFeedPageKey.Home, resolveDesktopFeedPageKey(Key.MoveHome, false, false, false, false))
        assertEquals(DesktopFeedPageKey.End, resolveDesktopFeedPageKey(Key.MoveEnd, false, false, false, false))
        for (key in listOf(Key.PageUp, Key.PageDown, Key.MoveHome, Key.MoveEnd)) {
            assertNull(resolveDesktopFeedPageKey(key, true, false, false, false), "Ctrl+$key passes through")
            assertNull(resolveDesktopFeedPageKey(key, false, true, false, false), "Alt+$key passes through")
            assertNull(resolveDesktopFeedPageKey(key, false, false, true, false), "Meta+$key passes through")
            assertNull(resolveDesktopFeedPageKey(key, false, false, false, true), "Shift+$key passes through")
        }
        assertNull(resolveDesktopFeedPageKey(Key.DirectionDown, false, false, false, false))
    }

    @Test fun pagingTargetsMoveOneScreenOfItemsAndStayInsideTheList() {
        assertNull(resolveDesktopFeedPageTarget(DesktopFeedPageKey.PageDown, 0, 6, 0))
        assertEquals(0, resolveDesktopFeedPageTarget(DesktopFeedPageKey.Home, 12, 6, 40))
        assertEquals(39, resolveDesktopFeedPageTarget(DesktopFeedPageKey.End, 12, 6, 40))
        assertEquals(18, resolveDesktopFeedPageTarget(DesktopFeedPageKey.PageDown, 12, 6, 40))
        assertEquals(6, resolveDesktopFeedPageTarget(DesktopFeedPageKey.PageUp, 12, 6, 40))
        assertEquals(39, resolveDesktopFeedPageTarget(DesktopFeedPageKey.PageDown, 36, 6, 40))
        assertEquals(0, resolveDesktopFeedPageTarget(DesktopFeedPageKey.PageUp, 2, 6, 40))
        // No visible items measured yet still moves by one item, never by zero.
        assertEquals(13, resolveDesktopFeedPageTarget(DesktopFeedPageKey.PageDown, 12, 0, 40))
    }
}
