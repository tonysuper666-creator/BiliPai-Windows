@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.android.bilipai.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import com.android.bilipai.tv.ui.components.TvVideoCard
import com.android.bilipai.tv.TvCatalogState
import com.android.bilipai.tv.tvId
import com.android.purebilibili.data.model.response.VideoItem
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

@Composable
fun TvVideoGrid(
    state: TvCatalogState, contentFocus: FocusRequester, navigationFocus: FocusRequester,
    onOpen: (VideoItem) -> Unit, onFocused: (String) -> Unit,
    onScroll: (Int, Int) -> Unit, modifier: Modifier = Modifier,
    canLoadMore: Boolean = false, onLoadMore: () -> Unit = {},
) {
    val ids = state.items.map { it.tvId() }
    val restoreIndex = remember(ids) { resolveTvFocusIndex(ids, state.focusedId, state.focusedIndex) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    ids.forEach { requesters.getOrPut(it) { FocusRequester() } }
    val gridState = remember { LazyGridState(state.firstVisibleIndex, state.firstVisibleOffset) }
    val preferredEntry = resolveTvFocusIndex(ids, state.focusedId, state.focusedIndex)
    val visible = gridState.layoutInfo.visibleItemsInfo
    val entryIndex = if (visible.isEmpty() || visible.any { it.index == preferredEntry }) preferredEntry
        else visible.firstOrNull()?.index

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) -> onScroll(index, offset) }
    }
    // Request only once per mounted list. Appending pages must not steal focus from the user.
    LaunchedEffect(gridState) {
        val index = restoreIndex ?: return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.totalItemsCount > 0 }.first { it }
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) gridState.scrollToItem(index)
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.index == index } }.first { it }
        requesters[ids[index]]?.requestFocus()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val availableWidth = (maxWidth - TvUiTokens.gridPadding * 2).value
        val columns = ((availableWidth + TvUiTokens.cardGap.value) /
            (TvUiTokens.minimumCardWidth.value + TvUiTokens.cardGap.value)).toInt().coerceIn(1, 6)
        // 滚动近末行即自动追加下一页（分页不抢焦点；失败后由 ViewModel 阻断自动重试）
        LaunchedEffect(gridState, canLoadMore) {
            snapshotFlow {
                val info = gridState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (canLoadMore && total > 0 && lastVisible >= total - columns) onLoadMore()
            }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(columns), state = gridState,
            contentPadding = PaddingValues(TvUiTokens.gridPadding), horizontalArrangement = Arrangement.spacedBy(TvUiTokens.cardGap),
            verticalArrangement = Arrangement.spacedBy(TvUiTokens.cardGap), modifier = Modifier.fillMaxSize().testTag("tv-grid")) {
            itemsIndexed(state.items, key = { _, item -> item.tvId() }) { index, item ->
                val requester = requesters.getValue(item.tvId())
                TvVideoCard(video = item, onClick = { onOpen(item) }, modifier = Modifier
                    .focusRequester(if (index == entryIndex) contentFocus else requester)
                    .then(if (index == entryIndex) Modifier.focusRequester(requester) else Modifier)
                    .focusProperties { if (index % columns == 0) left = navigationFocus }
                    .onFocusChanged { if (it.isFocused) onFocused(item.tvId()) }
                    .testTag("video:${item.tvId()}"))
            }
        }
    }
}
