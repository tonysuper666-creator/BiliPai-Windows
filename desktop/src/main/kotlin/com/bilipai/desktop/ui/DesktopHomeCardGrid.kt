package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.FeedVerticalStaggeredGrid
import com.android.purebilibili.core.util.resolveWindowWidthSizeClass
import com.android.purebilibili.feature.home.*
import com.bilipai.desktop.settings.LocalDesktopHomeCardPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/** Original Home grid mode/policy on the actual desktop viewport; no synthetic fold posture. */
@Composable
internal fun DesktopHomeCardGrid(state:LazyStaggeredGridState,modifier:Modifier=Modifier,
    content:LazyStaggeredGridScope.(HomeFeedCardLayout)->Unit) {
    val preferences=checkNotNull(LocalDesktopHomeCardPreferences.current){"Root shared Home card preferences are not mounted"}
    val settings by preferences.settings.collectAsState(preferences.initialSettings())
    val windowWidthDp=(LocalWindowInfo.current.containerSize.width/LocalDensity.current.density).dp
    val widthClass=resolveWindowWidthSizeClass(windowWidthDp)
    val scope=rememberCoroutineScope()
    val navigation=remember(state,scope){DesktopHomeGridNavigation(state,scope)}
    BoxWithConstraints(modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
        // Desktop navigation occupies part of the window; use its real feed viewport, not hidden sidebar pixels.
        val contentWidth=maxWidth.coerceAtMost(resolveHomeFeedMaxContentWidth())
        val columns=resolveHomeFeedGridColumns(contentWidth.value.toInt(),displayMode=0,
            fixedColumnCount=resolveHomeFeedStoredColumnCount(widthClass,settings.gridColumnCountCompact,settings.gridColumnCount),
            cardWidthPreset=settings.homeFeedCardWidthPreset,widthSizeClass=widthClass)
        val layout=resolveHomeFeedCardLayout(settings.homeFeedCardStyle,columns,widthClass)
        CompositionLocalProvider(LocalDesktopHomeGridNavigation provides navigation) {
            FeedVerticalStaggeredGrid(columns=StaggeredGridCells.Fixed(columns),state=state,
                modifier=Modifier.width(contentWidth).fillMaxHeight().desktopFeedKeyboardPaging(state),
                contentPadding=PaddingValues(layout.outerPaddingDp.dp),
                verticalItemSpacing=layout.verticalItemSpacingDp.dp,
                horizontalArrangement=Arrangement.spacedBy(layout.itemSpacingDp.dp),content={content(layout)})
        }
        // Roadmap H3: the approximate feed scrollbar, at the end edge of the feed viewport.
        DesktopFeedScrollbar(state)
    }
}
/**
 * Only covers in this Grid participate. Coordinates are read afresh after every layout/scroll,
 * so neither a column count nor a second copy of the feed is needed.
 */
internal val LocalDesktopHomeGridNavigation = staticCompositionLocalOf<DesktopHomeGridNavigation?> { null }

internal class DesktopHomeGridNavigation(
    private val state: LazyStaggeredGridState,
    private val scope: CoroutineScope,
) {
    private class Cover(val focus: DesktopHomeCoverFocus, val requester: FocusRequester,
        val reveal: BringIntoViewRequester) {
        fun bounds(): Rect? {
            val coordinates = focus.coverCoordinates?.takeIf { it.isAttached } ?: return null
            return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)
                .takeIf { it.width > 0f && it.height > 0f }
        }
    }
    private val covers = linkedMapOf<DesktopHomeCoverFocus, Cover>()
    private val moves = Mutex()

    fun register(focus: DesktopHomeCoverFocus, requester: FocusRequester, reveal: BringIntoViewRequester) {
        covers[focus] = Cover(focus, requester, reveal)
    }

    fun unregister(focus: DesktopHomeCoverFocus) { covers.remove(focus) }

    fun onKey(focus: DesktopHomeCoverFocus, event: KeyEvent): Boolean {
        if (!focus.focus.focused || event.type != KeyEventType.KeyDown ||
            event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed ||
            event.key !in listOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown)
        ) return false
        val direction = event.key
        scope.launch {
            moves.withLock {
                // A queued repeat follows the current cover, but never steals focus back from another control.
                val source = covers.values.firstOrNull { it.focus.focus.focused } ?: return@withLock
                move(source, direction)
            }
        }
        // Consume boundaries too: Compose's general search would otherwise enter a quick button or leave the Grid.
        return true
    }

    private suspend fun move(source: Cover, direction: Key) {
        val vertical = direction == Key.DirectionUp || direction == Key.DirectionDown
        val forward = direction == Key.DirectionDown
        while (covers[source.focus] === source && source.focus.focus.focused) {
            val origin = source.bounds() ?: return
            val measured = covers.values.mapNotNull { cover ->
                if (cover === source) null else cover.bounds()?.let { cover to it }
            }
            val candidates = measured.filter { (_, r) ->
                when (direction) {
                    Key.DirectionLeft -> r.center.x < origin.left &&
                        r.top < origin.bottom && r.bottom > origin.top
                    Key.DirectionRight -> r.center.x > origin.right &&
                        r.top < origin.bottom && r.bottom > origin.top
                    Key.DirectionUp -> r.bottom <= origin.top + 1f
                    else -> r.top >= origin.bottom - 1f
                }
            }
            val beam = candidates.filter { (_, r) -> origin.center.x in r.left..r.right }
            val canScroll = if (forward) state.canScrollForward else state.canScrollBackward
            val target = if (!vertical) {
                candidates.minWithOrNull(compareBy<Pair<Cover, Rect>> { abs(it.second.center.x - origin.center.x) }
                    .thenBy { abs(it.second.center.y - origin.center.y) })
            } else {
                beam.minByOrNull { abs(it.second.center.y - origin.center.y) }
                    ?: if (!canScroll) candidates.minWithOrNull(
                        compareBy<Pair<Cover, Rect>> { abs(it.second.center.x - origin.center.x) }
                            .thenBy { abs(it.second.center.y - origin.center.y) }) else null
            }
            if (target != null) {
                target.first.requester.requestFocus()
                target.first.focus.focus = target.first.focus.focus.onNavigationKey()
                target.first.reveal.bringIntoView()
                return
            }
            if (!vertical || !canScroll) return
            // Compose the next portion through the existing state. The focusable source is pinned by the lazy layout.
            val viewport = state.layoutInfo.viewportSize.height
            if (viewport <= 0) return
            val consumed = state.scrollBy(viewport * if (forward) 0.5f else -0.5f)
            if (abs(consumed) < 0.5f) return
            withFrameNanos { }
        }
    }
}
