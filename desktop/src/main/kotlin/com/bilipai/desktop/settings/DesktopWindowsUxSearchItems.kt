package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.bilipai.desktop.ui.DesktopWindowsUxSettingsStrings

/** Only enabled controls in the Windows layout group may be included in search. */
internal val desktopSearchableWindowsUxIds = setOf(
    "windows_ux.rail_mode",
    "windows_ux.home_layout",
    "windows_ux.card_interactions",
    "windows_ux.reset_layout",
)

private fun layoutItem(id: String, title: String) = DesktopSettingsItem(
    id, title, "", SettingsSearchTarget.APPEARANCE, SettingsRootCategory.APPEARANCE_THEME,
    null, "windows_display_scale",
)

internal val desktopWindowsUxSearchItems = listOf(
    layoutItem("windows_ux.rail_mode", DesktopWindowsUxSettingsStrings.RAIL),
    layoutItem("windows_ux.home_layout", DesktopWindowsUxSettingsStrings.HOME),
    layoutItem("windows_ux.card_interactions", DesktopWindowsUxSettingsStrings.CARDS),
    layoutItem("windows_ux.reset_layout", DesktopWindowsUxSettingsStrings.RESET),
).also { items ->
    check(items.map { it.settingId }.toSet() == desktopSearchableWindowsUxIds && items.size == desktopSearchableWindowsUxIds.size) {
        "Windows UX search items must match the enabled-controls allowlist"
    }
}
