package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import com.bilipai.desktop.ui.kit.DesktopKitVerticalScrollbar
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion
import kotlinx.coroutines.launch

/*
 * Roadmap H3, part 2: the feed scrollbar overlay and keyboard paging on top of the pure estimate in
 * DesktopLazyScrollbars.kt. The overlay is a BoxScope child of the Box that holds FeedVerticalStaggeredGrid and uses
 * the shared kit scrollbar, so style, hit strip and reduced motion fade come from the kit. The thumb is approximate;
 * a drag scrolls with scrollToItem to the estimated row.
 */

/** A v2 scrollbar adapter for a staggered grid, reading the grid's measured layout through the pure estimate. */
internal class DesktopStaggeredGridScrollbarAdapter(private val state: LazyStaggeredGridState) : ScrollbarAdapter {
    private fun sample(): DesktopStaggeredScrollSample {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        return DesktopStaggeredScrollSample(
            totalItems = info.totalItemsCount,
            lanes = (visible.maxOfOrNull { it.lane } ?: 0) + 1,
            firstVisibleIndex = state.firstVisibleItemIndex,
            firstVisibleOffsetPx = state.firstVisibleItemScrollOffset,
            visibleItemHeightsPx = visible.map { it.size.height },
            itemSpacingPx = info.mainAxisItemSpacing,
            viewportPx = info.viewportSize.height,
            beforePaddingPx = info.beforeContentPadding,
            afterPaddingPx = info.afterContentPadding,
            atEnd = visible.isNotEmpty() && !state.canScrollForward,
        )
    }

    override val scrollOffset: Double get() = estimateDesktopStaggeredScroll(sample()).scrollOffset
    override val contentSize: Double get() = estimateDesktopStaggeredScroll(sample()).contentSize
    override val viewportSize: Double get() = estimateDesktopStaggeredScroll(sample()).viewportSize

    override suspend fun scrollTo(scrollOffset: Double) {
        val sample = sample()
        val target = resolveDesktopStaggeredScrollTarget(scrollOffset, estimateDesktopStaggeredScroll(sample),
            sample.totalItems, sample.lanes)
        state.scrollToItem(target.index, target.offsetPx)
    }
}

/** The feed scrollbar overlay. Place it in the Box that holds the feed grid; it hides itself while the feed fits. */
@Composable
internal fun BoxScope.DesktopFeedScrollbar(state: LazyStaggeredGridState, modifier: Modifier = Modifier) {
    val adapter = remember(state) { DesktopStaggeredGridScrollbarAdapter(state) }
    DesktopKitVerticalScrollbar(adapter = adapter, modifier = modifier)
}

/**
 * Plain PageUp, PageDown, Home and End page the feed grid. It is a bubbling key handler, so a focused text field or
 * card that handles the key first keeps it, and Ctrl, Alt, Meta and Shift combinations are never taken. Reduced motion
 * comes from the shell's single reading (LocalDesktopKitReducedMotion) and jumps instead of animating.
 */
@Composable
internal fun Modifier.desktopFeedKeyboardPaging(state: LazyStaggeredGridState): Modifier {
    val scope = rememberCoroutineScope()
    val reduced = LocalDesktopKitReducedMotion.current
    return this.onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val action = resolveDesktopFeedPageKey(event.key, event.isCtrlPressed, event.isAltPressed, event.isMetaPressed,
            event.isShiftPressed) ?: return@onKeyEvent false
        val info = state.layoutInfo
        val target = resolveDesktopFeedPageTarget(action, state.firstVisibleItemIndex, info.visibleItemsInfo.size,
            info.totalItemsCount) ?: return@onKeyEvent false
        scope.launch { if (reduced) state.scrollToItem(target) else state.animateScrollToItem(target) }
        true
    }
}
