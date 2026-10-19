package com.bilipai.desktop.ui.kit

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Design tokens of the desktop component kit (spec 5.1 and 5.4), in dp after zoom: the app multiplies the Windows
 * scale by its own zoom percent, so these numbers follow Ctrl+wheel for free. Every number between the markers is
 * mirrored by desktop/tools/ux/desktop-ux-contract.json ("tokens" and "rail.metrics"); test_ux_kit_model.py and
 * DesktopKitModelTest compare them, so a drifting number fails in one of the two runs.
 */

// UX-KIT-TOKENS-BEGIN
internal const val DESKTOP_KIT_CONTROL_HEIGHT_DP = 32
internal const val DESKTOP_KIT_ROW_LIST_DP = 40
internal const val DESKTOP_KIT_ROW_NAV_DP = 40
internal const val DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP = 36
internal const val DESKTOP_KIT_ROW_SETTINGS_DP = 40
internal const val DESKTOP_KIT_ROW_SETTINGS_COMFORTABLE_DP = 48
internal const val DESKTOP_KIT_ICON_DP = 20
internal const val DESKTOP_KIT_ICON_HIT_DP = 32
internal const val DESKTOP_KIT_ICON_HIT_LARGE_DP = 36
internal const val DESKTOP_KIT_HIT_FLOOR_DP = 24
internal const val DESKTOP_KIT_RADIUS_CONTROL_DP = 8
internal const val DESKTOP_KIT_RADIUS_ROW_DP = 10
internal const val DESKTOP_KIT_RADIUS_MENU_DP = 12
internal const val DESKTOP_KIT_RADIUS_FLYOUT_DP = 12
internal const val DESKTOP_KIT_RADIUS_PANEL_DP = 16
internal const val DESKTOP_KIT_FOCUS_RING_OUTER_DP = 2
internal const val DESKTOP_KIT_FOCUS_RING_INNER_DP = 1
internal const val DESKTOP_KIT_MOTION_FAST_MS = 100
internal const val DESKTOP_KIT_MOTION_SLOW_MS = 160
internal const val DESKTOP_KIT_TOOLTIP_DELAY_MS = 500
internal const val DESKTOP_KIT_TOOLTIP_DELAY_PLAYER_MS = 400
internal const val DESKTOP_KIT_TOOLTIP_DELAY_RAIL_EXPANDED_MS = 700
internal const val DESKTOP_KIT_HOVER_PERCENT = 8
internal const val DESKTOP_KIT_PRESSED_PERCENT = 12
internal const val DESKTOP_KIT_SELECTED_FILL_PERCENT = 16
internal const val DESKTOP_KIT_NAV_ICON_GAP_DP = 12
internal const val DESKTOP_KIT_NAV_PILL_WIDTH_DP = 3
internal const val DESKTOP_KIT_NAV_PILL_HEIGHT_DP = 16
internal const val DESKTOP_KIT_BADGE_DP = 18
internal const val DESKTOP_KIT_BADGE_DOT_DP = 8
// UX-KIT-TOKENS-END

/** Dp view of the tokens for composables of other slices (settings, rail, menus). */
internal object DesktopKitTokens {
    val controlHeight: Dp = DESKTOP_KIT_CONTROL_HEIGHT_DP.dp
    val rowList: Dp = DESKTOP_KIT_ROW_LIST_DP.dp
    val rowNav: Dp = DESKTOP_KIT_ROW_NAV_DP.dp
    val icon: Dp = DESKTOP_KIT_ICON_DP.dp
    val iconHit: Dp = DESKTOP_KIT_ICON_HIT_DP.dp
    val iconHitLarge: Dp = DESKTOP_KIT_ICON_HIT_LARGE_DP.dp
    val hitFloor: Dp = DESKTOP_KIT_HIT_FLOOR_DP.dp
    val radiusControl: Dp = DESKTOP_KIT_RADIUS_CONTROL_DP.dp
    val radiusRow: Dp = DESKTOP_KIT_RADIUS_ROW_DP.dp
    val radiusMenu: Dp = DESKTOP_KIT_RADIUS_MENU_DP.dp
    val radiusFlyout: Dp = DESKTOP_KIT_RADIUS_FLYOUT_DP.dp
    val radiusPanel: Dp = DESKTOP_KIT_RADIUS_PANEL_DP.dp
}
