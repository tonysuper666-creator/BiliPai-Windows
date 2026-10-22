package com.bilipai.desktop.ui

import androidx.compose.ui.input.key.Key
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/*
 * Roadmap H3, part 1: the pure position estimate behind the feed scrollbar. The Compose desktop scrollbar has no
 * adapter for a staggered grid, and a staggered grid does not know the height of rows it has not measured. The
 * estimate is the average measured item extent (item height plus the main-axis spacing) times the row count, where a
 * row is one item per lane. The thumb is therefore approximate; at the real end of the list it is pinned to the bottom
 * so it can always reach the end of the track. No Compose state is read here.
 */

/** One measured frame of a staggered grid, in pixels. [visibleItemHeightsPx] are the main-axis sizes of the visible items. */
internal data class DesktopStaggeredScrollSample(
    val totalItems: Int,
    val lanes: Int,
    val firstVisibleIndex: Int,
    val firstVisibleOffsetPx: Int,
    val visibleItemHeightsPx: List<Int>,
    val itemSpacingPx: Int,
    val viewportPx: Int,
    val beforePaddingPx: Int,
    val afterPaddingPx: Int,
    val atEnd: Boolean,
)

/** The values a v2 scrollbar adapter reports, plus the row extent used to map a thumb offset back to an item. */
internal data class DesktopScrollEstimate(
    val scrollOffset: Double,
    val contentSize: Double,
    val viewportSize: Double,
    val rowExtentPx: Double,
)

/** Where to scroll for a requested thumb offset: an item index and the pixel offset inside it. */
internal data class DesktopScrollTarget(val index: Int, val offsetPx: Int)

internal fun estimateDesktopStaggeredScroll(sample: DesktopStaggeredScrollSample): DesktopScrollEstimate {
    val viewport = sample.viewportPx.coerceAtLeast(0).toDouble()
    val heights = sample.visibleItemHeightsPx.filter { it > 0 }
    if (sample.totalItems <= 0 || heights.isEmpty()) {
        // Nothing measured: the content is exactly the viewport, so the scrollbar is not shown.
        return DesktopScrollEstimate(scrollOffset = 0.0, contentSize = viewport, viewportSize = viewport, rowExtentPx = 0.0)
    }
    val lanes = sample.lanes.coerceAtLeast(1)
    val spacing = sample.itemSpacingPx.coerceAtLeast(0).toDouble()
    val rows = ceil(sample.totalItems.toDouble() / lanes)
    val rowExtent = heights.average() + spacing
    val padding = sample.beforePaddingPx.coerceAtLeast(0).toDouble() + sample.afterPaddingPx.coerceAtLeast(0).toDouble()
    val content = max(viewport, rows * rowExtent - spacing + padding)
    val maxOffset = content - viewport
    val firstRow = sample.firstVisibleIndex.coerceIn(0, sample.totalItems - 1) / lanes
    val raw = firstRow * rowExtent + sample.firstVisibleOffsetPx.coerceAtLeast(0)
    val offset = if (sample.atEnd) maxOffset else raw.coerceIn(0.0, maxOffset)
    return DesktopScrollEstimate(scrollOffset = offset, contentSize = content, viewportSize = viewport, rowExtentPx = rowExtent)
}

/** The inverse of [estimateDesktopStaggeredScroll] for a thumb drag: the first item of the estimated row and the rest in pixels. */
internal fun resolveDesktopStaggeredScrollTarget(
    requestedOffset: Double,
    estimate: DesktopScrollEstimate,
    totalItems: Int,
    lanes: Int,
): DesktopScrollTarget {
    if (totalItems <= 0 || estimate.rowExtentPx <= 0.0 || requestedOffset.isNaN()) return DesktopScrollTarget(0, 0)
    val laneCount = lanes.coerceAtLeast(1)
    val maxOffset = (estimate.contentSize - estimate.viewportSize).coerceAtLeast(0.0)
    val clamped = requestedOffset.coerceIn(0.0, maxOffset)
    val lastRow = (totalItems - 1) / laneCount
    val row = floor(clamped / estimate.rowExtentPx).toInt().coerceIn(0, lastRow)
    val index = (row * laneCount).coerceAtMost(totalItems - 1)
    val inside = (clamped - row * estimate.rowExtentPx).toInt().coerceAtLeast(0)
    return DesktopScrollTarget(index, inside)
}

/** The four paging keys of the feed. */
internal enum class DesktopFeedPageKey { PageUp, PageDown, Home, End }

/**
 * Plain PageUp, PageDown, Home and End page the feed. Any combination with Ctrl, Alt or Meta is left alone so the
 * shell and text shortcuts keep working; Shift combinations are left alone too (selection in text fields).
 */
internal fun resolveDesktopFeedPageKey(key: Key, ctrl: Boolean, alt: Boolean, meta: Boolean, shift: Boolean): DesktopFeedPageKey? {
    if (ctrl || alt || meta || shift) return null
    return when (key) {
        Key.PageUp -> DesktopFeedPageKey.PageUp
        Key.PageDown -> DesktopFeedPageKey.PageDown
        Key.MoveHome -> DesktopFeedPageKey.Home
        Key.MoveEnd -> DesktopFeedPageKey.End
        else -> null
    }
}

/** The item a paging key moves to: one screen of visible items per page, clamped to the list; null for an empty list. */
internal fun resolveDesktopFeedPageTarget(action: DesktopFeedPageKey, firstVisibleIndex: Int, visibleCount: Int, totalItems: Int): Int? {
    if (totalItems <= 0) return null
    val last = totalItems - 1
    val first = firstVisibleIndex.coerceIn(0, last)
    val page = visibleCount.coerceAtLeast(1)
    return when (action) {
        DesktopFeedPageKey.Home -> 0
        DesktopFeedPageKey.End -> last
        DesktopFeedPageKey.PageDown -> (first + page).coerceAtMost(last)
        DesktopFeedPageKey.PageUp -> (first - page).coerceAtLeast(0)
    }
}
