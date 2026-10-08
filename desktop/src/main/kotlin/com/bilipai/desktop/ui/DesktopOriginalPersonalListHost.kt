package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.feature.list.*
import com.android.purebilibili.navigation3.*
import com.android.purebilibili.navigation.ScreenRoutes
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** All callbacks are concrete current Root routes. This class stores no navigation model. */
internal class DesktopPersonalListNavigation(
    val push: (BiliPaiNavKey) -> Unit,
    val pushRoute: (String) -> Unit,
    val articleTarget: suspend (Long) -> ArticleNavigationTarget?,
    val navigateVideo: (BiliPaiNavKey.VideoDetail) -> Unit,
) {
    fun video(bvid: String, cid: Long, coverUrl: String, resumePositionMs: Long = 0L,
        initialVertical: Boolean = false, sourceRoute: String?) =
        navigateVideo(BiliPaiNavKey.VideoDetail(bvid, cid, coverUrl,
            resumePositionMs = resumePositionMs, initialVertical = initialVertical, sourceRoute = sourceRoute))
}

/** Whole original CommonListScreen is reused unchanged, with the same actual global prefs,
 * category port and Root queue/share consumer. History entry loading follows original
 * AppNavigation's active-page effect, not constructor eager loading. */
@Composable internal fun DesktopOriginalPersonalListHost(
    entry: DesktopPersonalListEntry,
    bindings: DesktopFavoriteBindings,
    navigation: DesktopPersonalListNavigation,
    onBack: () -> Unit,
    onUp: (Long) -> Unit,
    onOpenHistorySearch: (String) -> Unit,
    revealQueue: (String, Long, Boolean) -> Boolean,
    onPlayAllAudio: (String, Long) -> Unit,
    historySearchChannel: Channel<String>,
    historyScrollToTopChannel: Channel<Unit>,
    globalHazeState: HazeState?,
    isCurrentPage: Boolean,
    routes: DesktopOriginalRootRouteAssembly? = null,
    actualRouteKey: BiliPaiNavKey = entry.key,
) {
    if (!entry.owns()) return
    val model = entry.viewModel
    val key = entry.key
    val historySearch = key as? BiliPaiNavKey.HistorySearch
    val latestCurrentPage by rememberUpdatedState(isCurrentPage)
    val firstReadAuthentication: ((ListUiState) -> (() -> Unit)?)? =
        if (routes != null && model is HistoryViewModel) { displayed ->
            DesktopHistoryFailureLoginIntent.capture(routes, entry, actualRouteKey, model, displayed) {
                latestCurrentPage
            }?.let { intent -> { routes.loginFromReadFailure(intent); Unit } }
        } else null
    LaunchedEffect(model, isCurrentPage) {
        if (isCurrentPage && model is HistoryViewModel)
            model.loadData(showLoading = model.uiState.value.items.isEmpty())
    }
    val historyClick = remember(entry, navigation) {
        (model as? HistoryViewModel)?.let {
            desktopOriginalHistoryVideoClick(it, entry.scope, entry::owns, navigation)
        }
    }
    val window = LocalWindowSizeClass.current
    val recap = remember(entry) { if (model is HistoryViewModel) entry.recap else null }
    CompositionLocalProvider(LocalDesktopPersonalRecapBindings provides recap,
        LocalDesktopFavoriteBindings provides bindings,
        LocalDesktopFavoriteViewport provides DesktopFavoriteViewport(window.widthDp.value.toInt(),window.heightDp.value.toInt())) {
        CommonListScreen(model, onBack = { if (entry.owns()) onBack() },
            onVideoClick = { bvid, cid, cover, vertical ->
                if (entry.owns() && !revealQueue(bvid, cid, false)) {
                    if (historyClick != null) historyClick(bvid, cid, cover, vertical)
                    else navigation.video(bvid, cid, cover, initialVertical = vertical,
                        sourceRoute = key.toLegacyRoute())
                }
            },
            onUpClick = if (model is HistoryViewModel) ({ mid -> if (entry.owns()) onUp(mid) }) else null,
            initialSearchQuery = historySearch?.query.orEmpty(),
            isSearchDestination = historySearch != null,
            onOpenSearchDestination = if (model is HistoryViewModel && historySearch == null)
                ({ query -> if (entry.owns()) onOpenHistorySearch(query) }) else null,
            listScopedSearchChannel = if (model is HistoryViewModel && historySearch == null) historySearchChannel else null,
            scrollToTopChannel = if (model is HistoryViewModel) historyScrollToTopChannel else null,
            onPlayAllAudioClick = { bvid, cid -> if (entry.owns()) onPlayAllAudio(bvid, cid) },
            globalHazeState = globalHazeState, isCurrentPage = isCurrentPage,
            onFirstReadAuthenticationRequired = firstReadAuthentication)
    }
}
