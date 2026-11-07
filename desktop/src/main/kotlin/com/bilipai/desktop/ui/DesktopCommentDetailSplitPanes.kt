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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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
 * the root sits in the context pane. Reply items keep the same key and index either way. A point
 * inside the original comment is only a pixel guess here: its text reflows with the pane width, so
 * [DesktopCommentDetailSplitState] re-reads it as a [DesktopCommentDetailRootPoint] once laid out. */
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

/** A reading point in the original comment that survives reflow: the [ordinal]th text with the same
 * [text], the line holding character [char] and [intoLine] px below that line's top (negative
 * above the text, e.g. in the author row). Past the last text it is [fromEnd] px above the end,
 * which also reaches into the section header below. */
internal sealed interface DesktopCommentDetailRootPoint {
    data class Text(val text: String, val ordinal: Int, val char: Int, val intoLine: Float) : DesktopCommentDetailRootPoint
    data class FromEnd(val fromEnd: Int) : DesktopCommentDetailRootPoint
}

/** One laid-out text of the original comment, [top] px below the comment's own top. */
internal class DesktopCommentDetailRootText(val text: String, val top: Float, val layout: TextLayoutResult)

internal fun DesktopCommentDetailRootPoint.Text.ordinalIn(texts: List<DesktopCommentDetailRootText>) =
    texts.filter { it.text == text }.getOrNull(ordinal)

/** Reading point [y] px below the original comment's top, in the texts laid out at the old width. */
internal fun desktopCommentDetailRootPointAt(
    y: Int,
    height: Int,
    texts: List<DesktopCommentDetailRootText>,
): DesktopCommentDetailRootPoint {
    val index = texts.indexOfFirst { y < it.top + it.layout.size.height }
    if (index < 0) return DesktopCommentDetailRootPoint.FromEnd(height - y)
    val found = texts[index]
    val line = found.layout.getLineForVerticalPosition((y - found.top).coerceAtLeast(0f))
    val ordinal = texts.subList(0, index).count { it.text == found.text }
    return DesktopCommentDetailRootPoint.Text(found.text, ordinal, found.layout.getLineStart(line),
        y - found.top - found.layout.getLineTop(line))
}

/** Where [point] is now, px below the original comment's top; null if its text is gone. */
internal fun DesktopCommentDetailRootPoint.offsetIn(height: Int, texts: List<DesktopCommentDetailRootText>): Int? =
    when (this) {
        is DesktopCommentDetailRootPoint.FromEnd -> height - fromEnd
        is DesktopCommentDetailRootPoint.Text -> ordinalIn(texts)?.let { found ->
            val line = found.layout.getLineForOffset(char.coerceIn(0, found.layout.layoutInput.text.length))
            val lineHeight = found.layout.getLineBottom(line) - found.layout.getLineTop(line)
            (found.top + found.layout.getLineTop(line) + intoLine.coerceAtMost(lineHeight)).roundToInt()
        }
    }

/** Per root comment; owned by the one SubReplyDetailContent so the entry jump can reach it. */
@Stable
internal class DesktopCommentDetailSplitState {
    var contextScroll: ScrollState = ScrollState(0)
        private set
    private var split: Boolean? = null
    internal var rootHeight = 0
    private var pendingRoot: DesktopCommentDetailRootPoint? = null
    private var pendingContext: DesktopCommentDetailRootPoint? = null
    internal var rootTexts: DesktopCommentDetailRootTexts? = null
    internal val rootItem = DesktopCommentDetailRootItemStates()

    /** The original comment at the old width is still laid out while the new width composes. */
    private fun pointAt(y: Int): DesktopCommentDetailRootPoint =
        desktopCommentDetailRootPointAt(y, rootHeight, rootTexts?.texts().orEmpty())

    /** Applied in composition, before the list measures with its new item 0. */
    fun onWidthClass(split: Boolean, listState: LazyListState) {
        val previous = this.split
        this.split = split
        if (previous == null || previous == split) return
        val index = listState.firstVisibleItemIndex
        val offset = listState.firstVisibleItemScrollOffset
        val mapped = mapDesktopCommentDetailReadingPosition(
            DesktopCommentDetailReadingPosition(index, offset, contextScroll.value), split, rootHeight)
        pendingRoot = null
        pendingContext = if (split && index == 0 && offset < rootHeight) pointAt(offset) else null
        if (split) contextScroll = ScrollState(mapped.contextScroll)
        when {
            // The width is only known while measuring, so lazy item 0 still holds the header alone
            // for this frame; an offset into the original comment would spill into reply #1.
            !split && index == 0 -> pendingRoot =
                if (offset > 0) DesktopCommentDetailRootPoint.FromEnd(-offset) else pointAt(contextScroll.value)
            mapped.listIndex == index && mapped.listOffset == offset -> Unit
            else -> listState.requestScrollToItem(mapped.listIndex, mapped.listOffset)
        }
    }

    /** The original comment is laid out at the new width: continue at the text being read. */
    internal fun onRootPlaced(texts: DesktopCommentDetailRootTexts, listState: LazyListState?, height: Int) {
        rootTexts = texts
        rootHeight = height
        val point = (if (listState == null) pendingContext else pendingRoot) ?: return
        // Not every text is placed yet: the last one to be placed comes back here.
        val laidOut = texts.texts() ?: return
        if (listState == null) pendingContext = null else pendingRoot = null
        // The text being read is gone (e.g. its renderer changed): keep the pixel mapping.
        val offset = point.offsetIn(height, laidOut) ?: return
        if (listState == null) contextScroll.dispatchRawDelta((offset - contextScroll.value).toFloat())
        else if (listState.firstVisibleItemIndex == 0) listState.requestScrollToItem(0, offset.coerceAtLeast(0))
    }
}

/** The original comment's texts in one root slot, in composition order, as they are drawn. */
internal class DesktopCommentDetailRootTexts(
    private val state: DesktopCommentDetailSplitState,
    private val listState: LazyListState?,
) {
    internal val hooks = mutableListOf<DesktopCommentDetailRootTextHook>()
    private var coordinates: LayoutCoordinates? = null

    fun onSlotPlaced(coordinates: LayoutCoordinates) {
        this.coordinates = coordinates
        state.onRootPlaced(this, listState, coordinates.size.height)
    }

    fun onTextPlaced() {
        coordinates?.let { state.onRootPlaced(this, listState, it.size.height) }
    }

    /** Null until every text has a layout and a place at the current width. */
    fun texts(): List<DesktopCommentDetailRootText>? {
        val root = coordinates?.takeIf { it.isAttached } ?: return null
        return hooks.map { hook ->
            val layout = hook.layout
            val placed = hook.coordinates?.takeIf { it.isAttached }
            if (layout == null || placed == null) return null
            DesktopCommentDetailRootText(hook.text, root.localPositionOf(placed, Offset.Zero).y, layout)
        }
    }
}

/** What the original comment's rich text passes to its own `AppText`. */
internal class DesktopCommentDetailRootTextHook(val text: String, texts: DesktopCommentDetailRootTexts) {
    internal var layout: TextLayoutResult? = null
    internal var coordinates: LayoutCoordinates? = null
    val onTextLayout: (TextLayoutResult) -> Unit = { layout = it }
    val modifier: Modifier = Modifier.onPlaced {
        coordinates = it
        texts.onTextPlaced()
    }
}

private val LocalDesktopCommentDetailRootTexts = staticCompositionLocalOf<DesktopCommentDetailRootTexts?> { null }

/** Inside the split root slot the rich text reports its lines; everywhere else it is unchanged. */
@Composable
internal fun rememberDesktopCommentDetailRootText(text: String): DesktopCommentDetailRootTextHook? {
    val texts = LocalDesktopCommentDetailRootTexts.current ?: return null
    val hook = remember(texts, text) { DesktopCommentDetailRootTextHook(text, texts) }
    DisposableEffect(hook) {
        texts.hooks += hook
        onDispose { texts.hooks -= hook }
    }
    return hook
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
    val texts = remember(state, listState) { DesktopCommentDetailRootTexts(state, listState) }
    DisposableEffect(texts) {
        onDispose { if (state.rootTexts === texts) state.rootTexts = null }
    }
    CompositionLocalProvider(
        LocalDesktopCommentDetailRootItemStates provides state.rootItem,
        LocalDesktopCommentDetailRootTexts provides texts,
    ) {
        Box(modifier = Modifier.onPlaced(texts::onSlotPlaced)) { content() }
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
