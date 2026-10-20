package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Search targets/focus/category remain the original declarations, not a second schema. */
class DesktopSettingsSearchController(private val repository: DesktopSettingsSearchRepository) {
    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()
    private val mutableResults = MutableStateFlow(emptyList<SettingsSearchResult>())
    val results: StateFlow<List<SettingsSearchResult>> = mutableResults.asStateFlow()
    // The rows of the Windows item directory that match the query; the section results above are not changed by them.
    private val mutableItemResults = MutableStateFlow(emptyList<DesktopSettingsItemHit>())
    internal val itemResults: StateFlow<List<DesktopSettingsItemHit>> = mutableItemResults.asStateFlow()
    val history = repository.history
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    fun setQuery(query: String) {
        mutableQuery.value = query
        mutableResults.value = resolveDesktopWindowsSettingsSearchResults(query)
        mutableItemResults.value = resolveDesktopSettingsItemSearch(query)
        mutableError.value = null
    }

    suspend fun record(query: String = this.query.value) = historyChange { repository.record(query) }
    suspend fun deleteHistory(query: String) = historyChange { repository.delete(query) }
    suspend fun clearHistory() = historyChange { repository.clear() }

    /** Exact original SearchScreen dispatch; opening an action result does not perform it. */
    fun activate(result: SettingsSearchResult, onCategoryClick: (SettingsRootCategory) -> Unit,
        onResultClick: (SettingsSearchResult) -> Unit) {
        val category = resolveSettingsRootCategoryForSearchTarget(result.target)
        if (isSceneSettingsSearchTarget(result.target) && category != null) {
            onCategoryClick(category)
        } else {
            SettingsSearchFocusController.submit(result.target, result.focusId)
            onResultClick(result)
        }
    }

    /**
     * Opens a matched row through the same dispatch as a section result. When the row has a group anchor, the item request
     * goes in first, so the anchor that scrolls to the group also finds the row to highlight. Nothing is changed or performed.
     */
    internal fun activateItem(hit: DesktopSettingsItemHit, onCategoryClick: (SettingsRootCategory) -> Unit,
        onResultClick: (SettingsSearchResult) -> Unit) {
        desktopSettingsItemFocus.submit(hit.item, query.value)
        activate(hit.toSearchResult(), onCategoryClick, onResultClick)
    }

    private suspend fun historyChange(action: suspend () -> Unit) {
        try { action(); mutableError.value = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableError.value = failure.message ?: "设置搜索历史无法保存" }
    }
}
