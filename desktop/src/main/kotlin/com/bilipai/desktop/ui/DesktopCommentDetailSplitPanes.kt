package com.bilipai.desktop.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Spec §6: two columns from 960dp of available width, each column at most 760dp. */
internal const val DESKTOP_COMMENT_DETAIL_SPLIT_MIN_WIDTH_DP = 960
internal const val DESKTOP_COMMENT_DETAIL_PANE_MAX_WIDTH_DP = 760
internal const val DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG = "comment_detail_context_pane"
internal const val DESKTOP_COMMENT_DETAIL_REPLIES_PANE_TAG = "comment_detail_replies_pane"

/** Only the standalone comment-detail NavEntry opts in; video and dynamic sheets keep their one column. */
internal val LocalDesktopCommentDetailSplitPanes = staticCompositionLocalOf { false }

internal fun isDesktopCommentDetailSplit(availableWidthDp: Float): Boolean =
    availableWidthDp >= DESKTOP_COMMENT_DETAIL_SPLIT_MIN_WIDTH_DP

/** Where the reader is: the shared reply list (index/offset) and the context pane scroll. */
internal data class DesktopCommentDetailReadingPosition(
    val listIndex: Int,
    val listOffset: Int,
    val contextScroll: Int,
)

/** Narrow lazy item 0 is root ([rootHeight]) + section header; wide item 0 is only the header and
 * the root sits in the context pane. Reply items keep the same key and index either way. */
internal fun mapDesktopCommentDetailReadingPosition(
    position: DesktopCommentDetailReadingPosition,
    toSplit: Boolean,
    rootHeight: Int,
): DesktopCommentDetailReadingPosition = when {
    position.listIndex != 0 -> position
    toSplit && position.listOffset < rootHeight -> DesktopCommentDetailReadingPosition(0, 0, position.listOffset)
    toSplit -> DesktopCommentDetailReadingPosition(0, position.listOffset - rootHeight, 0)
    position.listOffset > 0 -> DesktopCommentDetailReadingPosition(0, rootHeight + position.listOffset, position.contextScroll)
    else -> DesktopCommentDetailReadingPosition(0, position.contextScroll, position.contextScroll)
}

/** Per root comment; owned by the one SubReplyDetailContent so the entry jump can reach it. */
@Stable
internal class DesktopCommentDetailSplitState {
    var contextScroll: ScrollState = ScrollState(0)
        private set
    private var split: Boolean? = null
    internal var rootHeight = 0
    private var pendingRootOffset: Int? = null
    internal val rootItem = DesktopCommentDetailRootItemStates()

    /** Applied in composition, before the list measures with its new item 0. */
    fun onWidthClass(split: Boolean, listState: LazyListState) {
        val previous = this.split
        this.split = split
        if (previous == null || previous == split) return
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        val mapped = mapDesktopCommentDetailReadingPosition(
            DesktopCommentDetailReadingPosition(index, offset, contextScroll.value), split, rootHeight)
        if (split) contextScroll = ScrollState(mapped.contextScroll)
        pendingRootOffset = null
        when {
            mapped.listIndex == index && mapped.listOffset == offset -> Unit
            // The width is only known while measuring, so lazy item 0 still holds the header alone
            // for this frame; an offset into the original comment would spill into reply #1.
            !split && mapped.listIndex == 0 && mapped.listOffset > offset -> pendingRootOffset = mapped.listOffset
            else -> listState.requestScrollToItem(mapped.listIndex, mapped.listOffset)
        }
    }

    /** The original comment is back in lazy item 0: continue at the line read in the context pane. */
    internal fun onRootPlacedInList(listState: LazyListState) {
        val offset = pendingRootOffset ?: return
        pendingRootOffset = null
        if (listState.firstVisibleItemIndex == 0) listState.requestScrollToItem(0, offset)
    }
}

/** The original comment's item states (expanded hated body, sheets, translation) by name. Lazy
 * item 0 and the context pane are separate compositions, so the renderer is recomposed rather than
 * moved; the original `remember` keys still decide when a value resets. */
internal class DesktopCommentDetailRootItemStates {
    private val states = HashMap<String, Pair<List<Any?>, MutableState<*>>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> state(name: String, keys: List<Any?>, initial: () -> T): MutableState<T> {
        val held = states[name]
        if (held != null && held.first == keys) return held.second as MutableState<T>
        return mutableStateOf(initial()).also { states[name] = keys to it }
    }
}

private val LocalDesktopCommentDetailRootItemStates =
    staticCompositionLocalOf<DesktopCommentDetailRootItemStates?> { null }

/** The original `remember(keys) { mutableStateOf(initial) }`, unless inside the split root slot. */
@Composable
internal fun <T> rememberDesktopCommentDetailItemState(name: String, vararg keys: Any?, initial: () -> T): MutableState<T> {
    val held = LocalDesktopCommentDetailRootItemStates.current
        ?: return remember(*keys) { mutableStateOf(initial()) }
    return remember(held, *keys) { held.state(name, keys.toList(), initial) }
}

/** Wraps the original comment in both widths: the mapping knows where it ends, and its item
 * states live with the split owner. */
@Composable
internal fun DesktopCommentDetailRootSlot(
    state: DesktopCommentDetailSplitState?,
    listState: LazyListState?,
    content: @Composable () -> Unit,
) {
    if (state == null) {
        Box { content() }
        return
    }
    CompositionLocalProvider(LocalDesktopCommentDetailRootItemStates provides state.rootItem) {
        Box(modifier = Modifier.onSizeChanged { state.rootHeight = it.height }.onPlaced {
            if (listState != null) state.onRootPlacedInList(listState)
        }) { content() }
    }
}

@Composable
internal fun DesktopCommentDetailSplitPanes(
    state: DesktopCommentDetailSplitState?,
    listState: LazyListState,
    active: Boolean,
    dividerColor: Color,
    context: @Composable () -> Unit,
    replies: @Composable (split: Boolean) -> Unit,
) {
    if (state == null) {
        replies(false)
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val split = isDesktopCommentDetailSplit(maxWidth.value)
        if (active) state.onWidthClass(split, listState)
        Layout(modifier = Modifier.fillMaxSize(), content = {
            // Same slot in both widths: the reply list and its item states survive the switch.
            Box(modifier = Modifier.testTag(DESKTOP_COMMENT_DETAIL_REPLIES_PANE_TAG)) { replies(split) }
            if (split) {
                // An outgoing page during the cascade must not drive the shared context scroll.
                val scroll = if (active) state.contextScroll else rememberScrollState()
                // Measured after the list, so its new item 0 has already released the original comment.
                BoxWithConstraints(modifier = Modifier.testTag(DESKTOP_COMMENT_DETAIL_CONTEXT_PANE_TAG).verticalScroll(scroll)) {
                    context()
                }
                Box(modifier = Modifier.background(dividerColor))
            }
        }) { measurables, constraints ->
            val divider = if (split) 0.5.dp.roundToPx().coerceAtLeast(1) else 0
            val panes = if (split) 2 else 1
            val pane = minOf(DESKTOP_COMMENT_DETAIL_PANE_MAX_WIDTH_DP.dp.roundToPx(), (constraints.maxWidth - divider) / panes)
            val height = constraints.maxHeight
            val fixed = { width: Int -> Constraints.fixed(width, height) }
            val replies = measurables[0].measure(fixed(pane))
            val context = measurables.getOrNull(1)?.measure(fixed(pane))
            val line = measurables.getOrNull(2)?.measure(fixed(divider))
            layout(constraints.maxWidth, height) {
                val start = (constraints.maxWidth - pane * panes - divider) / 2
                if (context != null && line != null) {
                    context.place(start, 0)
                    line.place(start + pane, 0)
                    replies.place(start + pane + divider, 0)
                } else replies.place(start, 0)
            }
        }
    }
}
