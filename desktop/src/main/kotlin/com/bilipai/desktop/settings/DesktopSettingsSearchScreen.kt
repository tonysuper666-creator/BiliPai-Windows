package com.bilipai.desktop.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.*
import com.android.purebilibili.feature.settings.*
import com.bilipai.desktop.appearance.LocalDesktopStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Windows page chrome around the actual original search/history/result renderers. */
@Composable
fun DesktopSettingsSearchScreen(controller: DesktopSettingsSearchController, onBack: () -> Unit,
    onCategoryClick: (SettingsRootCategory) -> Unit, onResultClick: (SettingsSearchResult) -> Unit,
    modifier: Modifier = Modifier, historyWritesScope: CoroutineScope? = null) {
    val query by controller.query.collectAsState()
    val results by controller.results.collectAsState()
    val itemResults by controller.itemResults.collectAsState()
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
            else DesktopSettingsSearchResultsBody(query, itemResults, results,
                onRowClick = { row ->
                    scope.launch { controller.record(query) }
                    controller.activateItem(row, onCategoryClick, onResultClick)
                },
                onSectionClick = { result ->
                    // Original ViewModel writes history asynchronously; navigation is immediate.
                    scope.launch { controller.record(query) }
                    controller.activate(result, onCategoryClick, onResultClick)
                })
            Spacer(Modifier.height(16.dp))
        }
      }
    }
}

/**
 * Attach to a real existing field/section; desktop layout, not Android lazy-list indices. When the request came from an
 * item result (desktopSettingsItemFocus), the group also holds a faded highlight after it is scrolled into view (the
 * upstream v0.3.9 behaviour, at group granularity: the rows of the shared settings controls take no anchor of their own).
 */
@Composable
fun Modifier.desktopSettingsSearchFocusAnchor(target: SettingsSearchTarget, focusId: String): Modifier {
    val request by SettingsSearchFocusController.request.collectAsState()
    val requester = remember { BringIntoViewRequester() }
    // Set once the item request is claimed. Clearing the original request restarts the effect below, so the hold is its own effect.
    var highlighted by remember { mutableStateOf<DesktopSettingsItemFocusRequest?>(null) }
    LaunchedEffect(request?.token, target, focusId) {
        val current = request ?: return@LaunchedEffect
        if (current.target == target && current.focusId == focusId) {
            val item = desktopSettingsItemFocus.claim(target, focusId)
            withFrameNanos { }
            requester.bringIntoView()
            SettingsSearchFocusController.clear(current.token)
            if (item != null) highlighted = item
        }
    }
    LaunchedEffect(highlighted) {
        val item = highlighted ?: return@LaunchedEffect
        try {
            delay(DesktopSettingsItemHighlightTiming.HOLD_MILLIS)
        } finally {
            if (highlighted === item) highlighted = null
            desktopSettingsItemFocus.finish(item)
        }
    }
    val progress = animateFloatAsState(
        targetValue = if (highlighted != null) 1f else 0f,
        animationSpec = tween(DesktopSettingsItemHighlightTiming.FADE_MILLIS),
        label = "desktopSettingsItemHighlight",
    )
    val accent = MaterialTheme.colorScheme.primary
    return bringIntoViewRequester(requester).drawWithContent {
        drawContent()
        // Drawn above the content so the row backgrounds cannot hide it.
        val alpha = progress.value
        if (alpha > 0f) {
            val radius = CornerRadius(12.dp.toPx())
            drawRoundRect(color = accent.copy(alpha = 0.12f * alpha), cornerRadius = radius)
            drawRoundRect(color = accent.copy(alpha = 0.45f * alpha), cornerRadius = radius, style = Stroke(width = 1.dp.toPx()))
        }
    }
}
