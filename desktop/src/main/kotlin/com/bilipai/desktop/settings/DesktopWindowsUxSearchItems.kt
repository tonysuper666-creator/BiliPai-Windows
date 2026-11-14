package com.bilipai.desktop.settings

import com.android.purebilibili.feature.settings.SettingsRootCategory
import com.android.purebilibili.feature.settings.SettingsSearchTarget
import com.bilipai.desktop.ui.DesktopWindowsUxSettingsStrings

/*
 * Only enabled controls in the Windows layout group may be included in search. The player layout row, the details panel row and
 * the details panel width control join the four rows that were already here once the video leaf honours them: the player row
 * turns on together with DESKTOP_PLAYER_OVERLAY_AVAILABLE, and the mounted dock reads the details panel choice and its width
 * (slice V4a, PR #196). The three pointer actions (wheel, single click, double click) still have neither a control nor a
 * consumer, so no search entry may name them: a hit has to land on a control that changes its value.
 */
internal val desktopSearchableWindowsUxIds = setOf(
    "windows_ux.rail_mode",
    "windows_ux.player_layout",
    "windows_ux.details_panel",
    "windows_ux.details_panel_width",
    "windows_ux.home_layout",
    "windows_ux.card_interactions",
    "windows_ux.reset_layout",
)

/** A row of the layout group of the display-scale card: the card is the group anchor, so every row scrolls to the group. */
private fun layoutItem(id: String, title: String) = DesktopSettingsItem(
    id, title, "", SettingsSearchTarget.APPEARANCE, SettingsRootCategory.APPEARANCE_THEME,
    null, "windows_display_scale",
)

internal val desktopWindowsUxSearchItems = listOf(
    layoutItem("windows_ux.rail_mode", DesktopWindowsUxSettingsStrings.RAIL),
    layoutItem("windows_ux.player_layout", DesktopWindowsUxSettingsStrings.PLAYER),
    layoutItem("windows_ux.details_panel", DesktopWindowsUxSettingsStrings.DETAILS),
    layoutItem("windows_ux.details_panel_width", DesktopWindowsUxSettingsStrings.DETAILS_WIDTH),
    layoutItem("windows_ux.home_layout", DesktopWindowsUxSettingsStrings.HOME),
    layoutItem("windows_ux.card_interactions", DesktopWindowsUxSettingsStrings.CARDS),
    layoutItem("windows_ux.reset_layout", DesktopWindowsUxSettingsStrings.RESET),
).also { items ->
    check(items.map { it.settingId }.toSet() == desktopSearchableWindowsUxIds && items.size == desktopSearchableWindowsUxIds.size) {
        "Windows UX search items must match the enabled-controls allowlist"
    }
}
