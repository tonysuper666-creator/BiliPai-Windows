package com.bilipai.desktop.ui

/** Original inline renderer has one slot per raw reply plus its header. The
 * single-pane screen additionally has its existing card slot. This checks only
 * physical measurement convergence; all paging/cursor decisions remain original. */
internal fun desktopDynamicCommentSlotsMeasured(
    measuredSlotCount: Int,
    currentRawReplyCount: Int,
    splitLayout: Boolean,
): Boolean = currentRawReplyCount > 0 &&
    measuredSlotCount >= currentRawReplyCount.toLong() + if (splitLayout) 1L else 2L
