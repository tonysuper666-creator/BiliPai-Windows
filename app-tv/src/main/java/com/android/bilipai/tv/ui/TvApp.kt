@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.android.bilipai.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.android.bilipai.tv.TvAppViewModel
import com.android.bilipai.tv.TvRoute
import com.android.bilipai.tv.TvScreen
import com.android.bilipai.tv.TvUiState
import com.android.bilipai.tv.ui.components.TvAppButton

@Composable
fun TvApp(viewModel: TvAppViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.start() }
    if (state.route.screen == TvScreen.Player) {
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            key(state.route.key) { TvPlayerRoute(state.route, state.quality, state.autoContinue, state.danmakuEnabled, onCheckpoint = viewModel::checkpointPlayback, onBack = viewModel::finishPlayback) }
        }
        return
    }
    val navigationFocus = remember { FocusRequester() }
    val contentFocus = remember(state.route.key) { FocusRequester() }
    var railHasFocus by remember { mutableStateOf(false) }
    var ambientUrl by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = state.route.screen != TvScreen.Home && state.route.screen != TvScreen.Player) { viewModel.back() }
    // 侧栏持有焦点时返回先回到内容（分层返回；后注册的 BackHandler 优先）
    BackHandler(enabled = railHasFocus) { contentFocus.requestFocus() }

    Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        TvAmbientBackdrop(ambientUrl)
        Box(Modifier.fillMaxSize()) {
            key(state.route.key) {
                when (state.route.screen) {
                    TvScreen.Detail -> TvDetailContent(state, contentFocus, viewModel::play, viewModel::addWatchLater, viewModel::refresh, onBack = { viewModel.back() })
                    TvScreen.Login -> TvLoginContent(state, contentFocus, viewModel::refreshQr, viewModel::signOut)
                    TvScreen.Settings -> TvSettingsContent(state, contentFocus, viewModel::updateQuality,
                        viewModel::toggleAutoContinue, viewModel::toggleDanmaku, viewModel::togglePrivacy, viewModel::clearSearchHistory)
                    TvScreen.Home -> TvHomeContent(state, navigationFocus, contentFocus, viewModel, onAmbientChange = { ambientUrl = it })
                    else -> TvCatalogContent(state, contentFocus, navigationFocus, viewModel)
                }
            }
        }
        TvSideRail(
            visible = railHasFocus,
            account = state.account?.uname,
            selectedScreen = state.rootScreen,
            contentFocus = contentFocus,
            navigationFocus = navigationFocus,
            onRailFocusChanged = { railHasFocus = it },
            onNavigate = { screen -> viewModel.navigate(TvRoute(screen), root = true) },
            modifier = Modifier.align(Alignment.CenterStart),
        )
    }
}

/** 首页：全幅轮播 banner + 信息流；加载/错误/空态退回目录布局（保留可操作的刷新入口）。 */
@Composable
private fun TvHomeContent(
    state: TvUiState,
    navigationFocus: FocusRequester,
    contentFocus: FocusRequester,
    model: TvAppViewModel,
    onAmbientChange: (String?) -> Unit,
) {
    if (state.catalog.items.isEmpty()) {
        TvCatalogContent(state, contentFocus, navigationFocus, model)
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TvHeroCarousel(
            items = state.catalog.items,
            navigationFocus = navigationFocus,
            onPlay = model::playItem,
            onOpen = model::open,
            onAmbientChange = onAmbientChange,
        )
        state.catalog.error?.let { message ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = TvUiTokens.pagePadding)) {
                Text(message, modifier = Modifier.weight(1f), color = Color(0xFFFFA3B6))
                TvAppButton(onClick = model::refresh) { Text("重试") }
            }
        }
        TvVideoGrid(state.catalog, contentFocus, navigationFocus,
            model::open, { id -> model.focusItem(id, state.route.key) },
            { index, offset -> model.saveScroll(index, offset, state.route.key) }, Modifier.weight(1f),
            canLoadMore = state.catalog.hasMore && !state.catalog.loading, onLoadMore = model::loadMore)
    }
}

@Composable
private fun TvCatalogContent(state: TvUiState, contentFocus: FocusRequester, navigationFocus: FocusRequester, model: TvAppViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = TvUiTokens.pagePadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(when (state.route.screen) {
                TvScreen.Home -> "为你推荐"
                TvScreen.Search -> "搜索"
                TvScreen.History -> "观看历史"
                TvScreen.Folders -> "我的收藏夹"
                TvScreen.Favorites -> state.route.label
                TvScreen.WatchLater -> "稍后再看"
                else -> "BiliPai"
            }, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TvAppButton(onClick = model::refresh, enabled = state.route.screen != TvScreen.Search || state.query.isNotBlank(), modifier = if (state.route.screen != TvScreen.Search && state.catalog.items.isEmpty() && state.catalog.folders.isEmpty()) Modifier.focusRequester(contentFocus) else Modifier) { Text("刷新") }
        }
        if (state.route.screen == TvScreen.Search) TvSearchInput(state, contentFocus, model::search)
        state.catalog.error?.let { message ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(message, modifier = Modifier.weight(1f), color = Color(0xFFFFA3B6))
                TvAppButton(onClick = { model.navigate(TvRoute(TvScreen.Login)) }) { Text("扫码登录") }
                TvAppButton(onClick = model::refresh) { Text("重试") }
            }
        }
        if (state.catalog.loading && state.catalog.items.isEmpty()) {
            Text("正在加载…")
            LaunchedEffect(contentFocus) { if (state.route.screen != TvScreen.Search) contentFocus.requestFocus() }
        }
        when {
            state.route.screen == TvScreen.Folders && state.catalog.folders.isNotEmpty() -> {
                val first = remember { FocusRequester() }
                val restoreIndex = remember(state.catalog.folders) {
                    resolveTvFocusIndex(state.catalog.folders.map { "folder:${it.id}" }, state.catalog.focusedId, 0)
                }
                LaunchedEffect(first) { first.requestFocus() }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    state.catalog.folders.forEachIndexed { index, folder ->
                        TvAppButton(onClick = { model.navigate(TvRoute(TvScreen.Favorites, folderId = folder.id, label = folder.title)) },
                            modifier = Modifier.fillMaxWidth().then(if (index == restoreIndex) Modifier.focusRequester(first).focusRequester(contentFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused) model.focusItem("folder:${folder.id}", state.route.key) }) {
                            Text("${folder.title} · ${folder.media_count} 个视频")
                        }
                    }
                }
            }
            state.catalog.items.isNotEmpty() -> key(state.catalog.resetVersion) { TvVideoGrid(state.catalog, if (state.route.screen == TvScreen.Search) remember { FocusRequester() } else contentFocus, navigationFocus,
                model::open, { id -> model.focusItem(id, state.route.key) },
                { index, offset -> model.saveScroll(index, offset, state.route.key) }, Modifier.weight(1f),
                canLoadMore = state.catalog.hasMore && !state.catalog.loading, onLoadMore = model::loadMore) }
            !state.catalog.loading && state.catalog.error == null && state.route.screen != TvScreen.Search -> {
                FocusButton("暂无内容，刷新试试", model::refresh, remember { FocusRequester() })
            }
            state.route.screen == TvScreen.Search && !state.catalog.loading && state.catalog.error == null && state.catalog.page > 0 -> Text("没有找到相关视频，换个关键词试试")
            state.catalog.error != null && state.catalog.items.isEmpty() -> FocusButton("重新加载", model::refresh, remember { FocusRequester() })
        }
    }
}

@Composable
internal fun FocusButton(label: String, onClick: () -> Unit, requester: FocusRequester, modifier: Modifier = Modifier) {
    LaunchedEffect(requester) { requester.requestFocus() }
    TvAppButton(onClick, modifier.focusRequester(requester)) { Text(label) }
}
