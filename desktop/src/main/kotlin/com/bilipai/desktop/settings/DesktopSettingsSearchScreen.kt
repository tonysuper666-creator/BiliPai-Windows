package com.bilipai.desktop.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.appearance.LocalDesktopStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Windows page chrome around the actual original search/history/result renderers. */
@Composable
fun DesktopSettingsSearchScreen(controller: DesktopSettingsSearchController, onBack: () -> Unit,
    onCategoryClick: (SettingsRootCategory) -> Unit, onResultClick: (SettingsSearchResult) -> Unit,
    modifier: Modifier = Modifier, historyWritesScope: CoroutineScope? = null) {
    val query by controller.query.collectAsState()
    val results by controller.results.collectAsState()
    val history by controller.history.collectAsState(initial = emptyList())
    val error by controller.error.collectAsState()
    val screenScope = rememberCoroutineScope()
    // Original ViewModel history jobs survive navigation to a settings detail.
    val scope = historyWritesScope ?: screenScope
    AppSurface(modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            AppTextButton(onBack) { AppText(LocalDesktopStrings.current["common_back"]) }
            AppText(LocalDesktopStrings.current["settings_search_results_title"], Modifier.weight(1f))
        }
        SettingsSearchBarSection(query, controller::setQuery, onSearch = { scope.launch { controller.record(query) } })
        error?.let { AppText(it, Modifier.padding(horizontal = 16.dp)) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (query.isBlank()) SettingsSearchHistorySection(history,
                onQueryClick = { controller.setQuery(it); scope.launch { controller.record(it) } },
                onDelete = { scope.launch { controller.deleteHistory(it) } },
                onClear = { scope.launch { controller.clearHistory() } })
            else SettingsSearchResultsSection(results) { result ->
                // Original ViewModel writes history asynchronously; navigation is immediate.
                scope.launch { controller.record(query) }
                controller.activate(result, onCategoryClick, onResultClick)
            }
            Spacer(Modifier.height(16.dp))
        }
      }
    }
}

/** Attach to a real existing field/section; desktop layout, not Android lazy-list indices. */
@Composable
fun Modifier.desktopSettingsSearchFocusAnchor(target: SettingsSearchTarget, focusId: String): Modifier {
    val request by SettingsSearchFocusController.request.collectAsState()
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(request?.token, target, focusId) {
        val current = request ?: return@LaunchedEffect
        if (current.target == target && current.focusId == focusId) {
            withFrameNanos { }
            requester.bringIntoView()
            SettingsSearchFocusController.clear(current.token)
        }
    }
    return bringIntoViewRequester(requester)
}
