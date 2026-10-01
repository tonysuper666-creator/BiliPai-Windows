package com.android.purebilibili.navigation
import com.android.purebilibili.core.util.BilibiliNavigationTarget
import com.android.purebilibili.feature.home.components.BottomNavItem
import kotlinx.coroutines.channels.SendChannel
/** Source AppNavigation callback closure; Root owns all channels and navigation. */
internal fun submitDesktopOriginalBottomBarSearchKeyword(
    keyword: String,
    bottomBarSearchEnabled: Boolean,
    listScopedSearchEnabled: Boolean,
    isMainHost: Boolean,
    currentBottomNavItem: BottomNavItem,
    historyListScopedSearchChannel: SendChannel<String>?,
    favoriteListScopedSearchChannel: SendChannel<String>?,
    watchLaterListScopedSearchChannel: SendChannel<String>?,
    onOpenSearch: (String) -> Unit,
    onOpenNativeTarget: (BilibiliNavigationTarget) -> Unit,
    isOwned: () -> Boolean,
) {
    if (!isOwned()) return
val submitSearchKeywordInNavigation3: (String) -> Unit = { keyword ->
    when (val action = resolveSearchSubmitAction(keyword)) {
        SearchSubmitAction.Ignore -> Unit
        is SearchSubmitAction.OpenSearch -> onOpenSearch(action.keyword)
        is SearchSubmitAction.OpenNativeTarget -> onOpenNativeTarget(action.target)
    }
}

val submitBottomBarSearchKeyword: (String) -> Unit = { keyword ->
    val listScopedSearchActive = com.android.purebilibili.feature.list.isListScopedSearchActive(
        bottomBarSearchEnabled = bottomBarSearchEnabled,
        listScopedSearchEnabled = listScopedSearchEnabled,
    )
    val scopedChannel = if (
        listScopedSearchActive &&
        isMainHost
    ) {
        when (currentBottomNavItem) {
            BottomNavItem.HISTORY -> historyListScopedSearchChannel
            BottomNavItem.FAVORITE -> favoriteListScopedSearchChannel
            BottomNavItem.WATCHLATER -> watchLaterListScopedSearchChannel
            else -> null
        }
    } else {
        null
    }
    if (scopedChannel != null) {
        scopedChannel.trySend(keyword.trim())
    } else {
        submitSearchKeywordInNavigation3(keyword)
    }
}
    submitBottomBarSearchKeyword(keyword)
}
