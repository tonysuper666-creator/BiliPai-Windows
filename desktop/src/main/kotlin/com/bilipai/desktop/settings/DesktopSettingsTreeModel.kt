package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.*

/*
 * Pure model of the Windows settings tree (spec 3.7, slice X3): which categories the rail lists, which rail entry a
 * page belongs to, which categories open straight to their detail, and how a rail click rewrites the page stack.
 * No Compose, AWT or Windows dependency. Mirrored by desktop/tools/ux/ux_settings_model.py and checked against the
 * "settings" section of desktop-ux-contract.json by DesktopSettingsTreeModelTest and test_ux_settings_model.py.
 */

/** The categories of the rail, in rail order. The titles are in DesktopWindowsSettingsStrings.railTitle. */
// UX-SETTINGS-RAIL-BEGIN
internal val DESKTOP_SETTINGS_RAIL_CATEGORIES: List<SettingsRootCategory> = listOf(
    SettingsRootCategory.PLAYBACK_QUALITY,
    SettingsRootCategory.HOME_RECOMMENDATION,
    SettingsRootCategory.APPEARANCE_THEME,
    SettingsRootCategory.STORAGE_BACKUP,
    SettingsRootCategory.PRIVACY_PERMISSION,
    SettingsRootCategory.PLUGINS_EXTENSIONS,
    SettingsRootCategory.SYSTEM_ABOUT,
)

/** Where a fresh visit of the settings lands when no category was used yet. */
internal val DESKTOP_SETTINGS_DEFAULT_CATEGORY: SettingsRootCategory = SettingsRootCategory.PLAYBACK_QUALITY
// UX-SETTINGS-RAIL-END

/**
 * The rail entry a page belongs to, or null when the page belongs to none (the root, the search page, and the pages of
 * the categories that the Windows settings do not list). A detail belongs to the category the original settings put
 * its target in.
 */
internal fun desktopSettingsPageCategory(page: DesktopSettingsPage): SettingsRootCategory? {
    val category = when (page) {
        DesktopSettingsPage.Root -> null
        is DesktopSettingsPage.Search -> null
        is DesktopSettingsPage.Category -> canonicalSettingsRootCategory(page.category)
        is DesktopSettingsPage.Detail -> resolveSettingsRootCategoryForSearchTarget(page.target)?.let { canonicalSettingsRootCategory(it) }
        is DesktopSettingsPage.CommentFraudHistory -> SettingsRootCategory.PRIVACY_PERMISSION
    }
    return category?.takeIf { it in DESKTOP_SETTINGS_RAIL_CATEGORIES }
}

/**
 * The rail entry a fresh visit may land on: every listed category but the system one, which is a route of its own
 * (the shell pushes it as a separate page, so the tree never opens it by itself).
 */
internal fun desktopSettingsLandableCategory(page: DesktopSettingsPage): SettingsRootCategory? =
    desktopSettingsPageCategory(page)?.takeIf { it != SettingsRootCategory.SYSTEM_ABOUT }

/**
 * The detail a category opens straight to, or null when it has a category page. The original rule sends appearance and
 * plugins straight to their detail; the Windows settings send playback and home there as well, because their category
 * page held nothing but a button (playback) or a button and the recommendation fields (home, which the home detail
 * now carries).
 */
internal fun desktopSettingsDirectDetail(category: SettingsRootCategory): SettingsSearchTarget? {
    val canonical = canonicalSettingsRootCategory(category)
    return resolveDesktopSettingsCategoryDirectTarget(canonical) ?: when (canonical) {
        SettingsRootCategory.PLAYBACK_QUALITY -> SettingsSearchTarget.PLAYBACK
        SettingsRootCategory.HOME_RECOMMENDATION -> SettingsSearchTarget.HOME_FEED
        else -> null
    }
}

/** The page a rail click opens: the direct detail, or the category page. */
internal fun desktopSettingsRailTarget(category: SettingsRootCategory): DesktopSettingsPage {
    val canonical = canonicalSettingsRootCategory(category)
    val detail = desktopSettingsDirectDetail(canonical)
    return if (detail != null) DesktopSettingsPage.Detail(detail, null) else DesktopSettingsPage.Category(canonical)
}

/**
 * Whether a rail click would open the page that is already open, so it does nothing. A page under the clicked category (a
 * detail of the privacy category, say) is not that page: the click replaces it with the category page. This check matters
 * for the system category, whose click pushes a route of the shell (a second push would stack the same page); a click
 * through replaceCategory is already idempotent for every other category.
 */
internal fun desktopSettingsRailClickIsRedundant(page: DesktopSettingsPage, category: SettingsRootCategory): Boolean =
    page == desktopSettingsRailTarget(category)

/**
 * The stack after a rail click: the category and detail pages on top of the stack go, and the target takes their
 * place, so Back never walks the categories that were visited on the way. The root stays at the bottom, and a page
 * that is neither a category nor a detail (the search page) stays under the target.
 */
internal fun desktopSettingsReplaceStack(stack: List<DesktopSettingsPage>, target: DesktopSettingsPage): List<DesktopSettingsPage> {
    var end = stack.size
    while (end > 1 && (stack[end - 1] is DesktopSettingsPage.Category || stack[end - 1] is DesktopSettingsPage.Detail)) end -= 1
    return stack.subList(0, end) + target
}
