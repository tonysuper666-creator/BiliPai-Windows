package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Desktop frames bind the original categories, destinations and focus requests. */
internal sealed interface DesktopSettingsPage {
    data object Root : DesktopSettingsPage
    data class Search(val entryToken: Long) : DesktopSettingsPage
    data class Category(val category: SettingsRootCategory) : DesktopSettingsPage
    data class Detail(val target: SettingsSearchTarget, val focusId: String?) : DesktopSettingsPage
    data class CommentFraudHistory(val entryToken: Long) : DesktopSettingsPage
}

internal data class DesktopSettingsNavigationState(val stack: List<DesktopSettingsPage>) {
    val current get() = stack.last()
    val searchEntryToken get() = stack.filterIsInstance<DesktopSettingsPage.Search>().lastOrNull()?.entryToken
}

internal class DesktopSettingsNavigator(private val canOpenDetail: (SettingsSearchTarget) -> Boolean = { true }) {
    private val mutableState = MutableStateFlow(DesktopSettingsNavigationState(listOf(DesktopSettingsPage.Root)))
    val state = mutableState.asStateFlow()
    private var nextSearchToken = 0L
    private var nextLocalPageToken = 0L

    fun openRoot() {
        SettingsSearchFocusController.clear()
        mutableState.value = DesktopSettingsNavigationState(listOf(DesktopSettingsPage.Root))
    }

    fun openSearch() {
        SettingsSearchFocusController.clear()
        push(DesktopSettingsPage.Search(++nextSearchToken))
    }

    /** A returning actual SettingsSearch NavDisplay entry keeps its original query/history owner. */
    fun activateSearch() {
        val stack = mutableState.value.stack
        val index = stack.indexOfLast { it is DesktopSettingsPage.Search }
        if (index < 0) openSearch()
        else {
            SettingsSearchFocusController.clear()
            mutableState.value = DesktopSettingsNavigationState(stack.take(index + 1))
        }
    }

    fun openCategory(category: SettingsRootCategory) {
        val canonical = canonicalSettingsRootCategory(category)
        val direct = resolveDesktopSettingsCategoryDirectTarget(canonical)
        if (direct != null) openDetail(direct, null)
        else {
            SettingsSearchFocusController.clear()
            push(DesktopSettingsPage.Category(canonical))
        }
    }

    fun openDetail(target: SettingsSearchTarget, focusId: String?) {
        if (!canOpenDetail(target)) return
        SettingsSearchFocusController.submit(target, focusId)
        push(DesktopSettingsPage.Detail(target, focusId))
    }

    fun openCommentFraudHistory() {
        SettingsSearchFocusController.clear()
        push(DesktopSettingsPage.CommentFraudHistory(++nextLocalPageToken))
    }

    fun openSearchResult(result: SettingsSearchResult) = dispatchDesktopSettingsSearchDestination(
        result, ::openCategory, ::openDetail,
    )

    fun pop(): Boolean {
        val stack = mutableState.value.stack
        if (stack.size <= 1) return false
        SettingsSearchFocusController.clear()
        mutableState.value = DesktopSettingsNavigationState(stack.dropLast(1))
        return true
    }

    fun leave() = openRoot()

    private fun push(page: DesktopSettingsPage) {
        mutableState.value = DesktopSettingsNavigationState(mutableState.value.stack + page)
    }
}
