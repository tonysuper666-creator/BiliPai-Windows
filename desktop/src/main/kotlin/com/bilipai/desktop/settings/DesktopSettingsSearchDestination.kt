package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*

/**
 * Shell-only navigation boundary, using original targets/categories/focus policy.
 * A category result stays a category; its searchTarget must not be resolved again.
 * Opening an action result never invokes that action.
 */
internal fun dispatchDesktopSettingsSearchDestination(
    result: SettingsSearchResult,
    onCategory: (SettingsRootCategory) -> Unit,
    onDetail: (SettingsSearchTarget, String?) -> Unit,
) {
    val category = resolveSettingsRootCategoryForSearchTarget(result.target)
    if (isSceneSettingsSearchTarget(result.target) && category != null) {
        onCategory(canonicalSettingsRootCategory(category))
        return
    }
    resolveSettingsSceneDetailFocus(result.target)?.let { focus ->
        onDetail(focus.target, focus.focusId)
        return
    }
    // The original SettingsSearchNavigationPolicy direct BiliPaiNavKey branches.
    // Preserve their route kind instead of projecting a category to its searchTarget.
    when (result.target) {
        SettingsSearchTarget.APPEARANCE,
        SettingsSearchTarget.ANIMATION,
        SettingsSearchTarget.PLAYBACK,
        SettingsSearchTarget.BOTTOM_BAR,
        SettingsSearchTarget.PERMISSION,
        SettingsSearchTarget.MESSAGE_NOTIFICATION,
        SettingsSearchTarget.PLUGINS,
        SettingsSearchTarget.SETTINGS_SHARE,
        SettingsSearchTarget.WEBDAV_BACKUP,
        SettingsSearchTarget.OPEN_SOURCE_LICENSES,
        SettingsSearchTarget.TIPS -> onDetail(result.target, result.focusId)
        else -> category?.let { onCategory(canonicalSettingsRootCategory(it)) }
    }
}
