package com.bilipai.desktop.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import com.android.purebilibili.feature.dynamic.DynamicScrollRequest
import com.android.purebilibili.feature.dynamic.resolveDynamicScrollActionPlan
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The real main-entry event channel. It contains no feed, page, account or persistent state. */
internal val LocalDesktopRootDynamicScroll = compositionLocalOf<Channel<DynamicScrollRequest>?> { null }

/** DynamicScreen scrollDynamicFeedToTop's exact plan and top boundary, applied to the
 * Windows consumer's ACTUAL current lazy grid. The original fetch/merge owner remains intact. */
internal suspend fun applyDesktopRootDynamicScroll(
    request: DynamicScrollRequest,
    actualList: LazyStaggeredGridState,
    refresh: suspend () -> Unit,
) {
    val isAtTop = actualList.firstVisibleItemIndex == 0 && actualList.firstVisibleItemScrollOffset < 50
    val plan = resolveDynamicScrollActionPlan(request = request, isAtTop = isAtTop)
    if (plan.shouldScrollToTop) actualList.animateScrollToItem(0)
    currentCoroutineContext().ensureActive()
    if (plan.shouldRefresh) refresh()
}
