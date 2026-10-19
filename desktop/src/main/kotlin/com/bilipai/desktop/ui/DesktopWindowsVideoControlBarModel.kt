package com.bilipai.desktop.ui

import com.bilipai.desktop.ui.kit.DESKTOP_KIT_CONTROL_HEIGHT_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ROW_LIST_DP

/*
 * Pure rules of the LEGACY player control bar (ui/DesktopWindowsVideoControlBar.kt, slice V2b, spec 4.5, 4.13 and 5.1). No
 * Compose, AWT or Windows dependency: the composable reads the width breakpoints, the shortcut hint of each button, the height
 * of each menu window and the quality label from here. Every number between the markers is mirrored by the "control_bar" section
 * of desktop/tools/ux/desktop-ux-contract.json; test_ux_control_bar.py and DesktopWindowsVideoControlBarModelTest compare them.
 * The two breakpoints and the menu limits are the numbers the bar had before the kit restyle, kept so the layout does not move.
 *
 * The menu heights carry a rounding slack on top of the exact content height. Compose rounds each padding to whole pixels, so at
 * a fractional density the sum of the rounded paddings can exceed the sum of the dp budgets: at 1.875 (Windows 150% times the 125%
 * app zoom) a 4 dp padding is 7.5 px and rounds to 8, so the two paddings of the menu host cost 16 px against a budget of 15, and
 * a menu that fits to the dp would show a scrollbar for one pixel. The slack is 4 dp (7.5 px at that density), more than the
 * rounding of all the paddings of any menu: half a pixel at most for each of the eight paddings of the compact more menu (the two of
 * the host and the two of each of three divider blocks). The rows themselves are whole pixels at 1.875 (32 dp is 60 px, 40 dp is 75 px).
 * The volume slider row of a menu is 40 dp by construction: the bar gives it that height (DesktopControlBarVolumeRow).
 */

// UX-CONTROL-BAR-BEGIN
internal const val DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP = 940
internal const val DESKTOP_CONTROL_BAR_STATUS_MIN_DP = 520
internal const val DESKTOP_CONTROL_BAR_ROW_MIN_HEIGHT_DP = 40
internal const val DESKTOP_CONTROL_BAR_SIDE_PADDING_DP = 8
internal const val DESKTOP_CONTROL_BAR_BUTTON_GAP_DP = 2
internal const val DESKTOP_CONTROL_BAR_PILL_PADDING_DP = 10
internal const val DESKTOP_CONTROL_BAR_MENU_HOST_PADDING_DP = 8
internal const val DESKTOP_CONTROL_BAR_MENU_ROUNDING_SLACK_DP = 4
internal const val DESKTOP_CONTROL_BAR_MENU_START_INSET_DP = 12
internal const val DESKTOP_CONTROL_BAR_MENU_DIVIDER_BLOCK_DP = 9
internal const val DESKTOP_CONTROL_BAR_MENU_MIN_DP = 64
internal const val DESKTOP_CONTROL_BAR_MENU_MAX_DP = 420
internal const val DESKTOP_CONTROL_BAR_MENU_COMPACT_MORE_MAX_DP = 564
internal const val DESKTOP_CONTROL_BAR_MORE_BASE_ROWS = 6
internal const val DESKTOP_CONTROL_BAR_MORE_COMPACT_FIXED_ROWS = 4
internal const val DESKTOP_CONTROL_BAR_MORE_COMPACT_DIVIDERS = 3
internal const val DESKTOP_CONTROL_BAR_MORE_COMPACT_SLIDER_ROWS = 1
// UX-CONTROL-BAR-END

/** The full control set (previous, next, volume, speed, quality, mini player) shows from this bar width. */
internal fun desktopControlBarExpanded(widthDp: Float): Boolean = widthDp >= DESKTOP_CONTROL_BAR_EXPANDED_MIN_DP

/** The NVIDIA slot shows its status text, and the chapter button shows, from this bar width. */
internal fun desktopControlBarShowsStatus(widthDp: Float): Boolean = widthDp >= DESKTOP_CONTROL_BAR_STATUS_MIN_DP

/** The buttons that name a key in their tooltip. */
internal enum class DesktopControlBarHintTarget { PLAY_PAUSE, PREVIOUS, NEXT, MUTE, MINI_PLAYER, DETAILS, FULLSCREEN }

internal data class DesktopControlBarHint(
    val target: DesktopControlBarHintTarget,
    val chord: String,
    val action: DesktopPlayerAction,
)

// Release coupling: the player table claims P (mini player) and Ctrl+B (details panel) as active rows, but the LEGACY root key handler
// does not run them until slice V6 composes the window-level shortcut input. The tooltips of those two buttons name keys that work only
// once V6 is in the build, so V6 ships in the same release as V2b (spec 9.3, release R1). The active-row rule below is not relaxed.
// UX-CONTROL-BAR-HINTS-BEGIN
internal val DESKTOP_CONTROL_BAR_HINTS: List<DesktopControlBarHint> = listOf(
    DesktopControlBarHint(DesktopControlBarHintTarget.PLAY_PAUSE, "Space", DesktopPlayerAction(DesktopPlayerActionKind.PLAY_PAUSE)),
    DesktopControlBarHint(DesktopControlBarHintTarget.PREVIOUS, "LeftBracket", DesktopPlayerAction(DesktopPlayerActionKind.PART, -1)),
    DesktopControlBarHint(DesktopControlBarHintTarget.NEXT, "RightBracket", DesktopPlayerAction(DesktopPlayerActionKind.PART, 1)),
    DesktopControlBarHint(DesktopControlBarHintTarget.MUTE, "M", DesktopPlayerAction(DesktopPlayerActionKind.MUTE)),
    DesktopControlBarHint(DesktopControlBarHintTarget.MINI_PLAYER, "P", DesktopPlayerAction(DesktopPlayerActionKind.MINI_PLAYER)),
    DesktopControlBarHint(DesktopControlBarHintTarget.DETAILS, "Ctrl+B", DesktopPlayerAction(DesktopPlayerActionKind.SIDE_PANEL_OR_CINEMA)),
    DesktopControlBarHint(DesktopControlBarHintTarget.FULLSCREEN, "F", DesktopPlayerAction(DesktopPlayerActionKind.FULLSCREEN_TOGGLE)),
)
// UX-CONTROL-BAR-HINTS-END

/** How a chord of the player table reads in a tooltip: the Space key by its name, the brackets as themselves, the rest as written. */
internal fun desktopControlBarKeyLabel(chord: String): String = when (chord) {
    "Space" -> DesktopWindowsVideoControlBarStrings.KEY_SPACE
    "LeftBracket" -> "["
    "RightBracket" -> "]"
    else -> chord
}

/**
 * The key a tooltip names for a button, or null. A hint is shown only while the player table of the contract still binds its chord
 * to the same action as an active row, so a chord that is dropped, rebound or not yet claimed never shows. shortcuts and hints are
 * parameters so a test can ask about a synthetic table.
 */
internal fun desktopControlBarShortcutHint(
    target: DesktopControlBarHintTarget,
    shortcuts: List<DesktopPlayerShortcut> = DESKTOP_PLAYER_SHORTCUTS,
    hints: List<DesktopControlBarHint> = DESKTOP_CONTROL_BAR_HINTS,
): String? {
    val hint = hints.firstOrNull { it.target == target } ?: return null
    val bound = shortcuts.any {
        it.chord == hint.chord && it.action == hint.action && it.status == DesktopPlayerShortcutStatus.ACTIVE
    }
    return if (bound) desktopControlBarKeyLabel(hint.chord) else null
}

/**
 * The content height of a player menu window: the host padding of DesktopWindowsPlayerMenu (4 dp above and below), the rounding
 * slack, the 32 dp rows of the kit, the 40 dp slider rows and the divider blocks. A negative count counts as none.
 */
internal fun desktopControlBarMenuContentDp(rows: Int, dividers: Int = 0, sliderRows: Int = 0): Int =
    DESKTOP_CONTROL_BAR_MENU_HOST_PADDING_DP +
        DESKTOP_CONTROL_BAR_MENU_ROUNDING_SLACK_DP +
        rows.coerceAtLeast(0) * DESKTOP_KIT_CONTROL_HEIGHT_DP +
        sliderRows.coerceAtLeast(0) * DESKTOP_KIT_ROW_LIST_DP +
        dividers.coerceAtLeast(0) * DESKTOP_CONTROL_BAR_MENU_DIVIDER_BLOCK_DP

/**
 * The height the bar asks of DesktopWindowsPlayerMenu: the content height raised to the 64 dp floor and capped at the limit (420 dp, or
 * 564 dp for the compact more menu). The cap is applied last, so a limit under the floor wins instead of throwing.
 */
internal fun desktopControlBarMenuHeightDp(
    rows: Int,
    dividers: Int = 0,
    sliderRows: Int = 0,
    maxDp: Int = DESKTOP_CONTROL_BAR_MENU_MAX_DP,
): Int = desktopControlBarMenuContentDp(rows, dividers, sliderRows).coerceAtLeast(DESKTOP_CONTROL_BAR_MENU_MIN_DP).coerceAtMost(maxDp)

/**
 * The height the bar asks of the more menu. The wide bar lists the six rows the menu always holds (send danmaku, share, download
 * audio, save cover, screenshot, introduction) and the chapter, collection and queue rows that apply. The compact bar adds the previous,
 * next, mute and mini player rows, the volume slider row, the speed and quality rows and three divider blocks, and may grow to 564 dp.
 * The rows that sponsorMoreActions adds come from another file and are not counted: the menu scrolls for them, as it always did.
 */
internal fun desktopControlBarMoreMenuHeightDp(
    expanded: Boolean,
    speedRows: Int,
    qualityRows: Int,
    chapterRow: Boolean,
    collectionRow: Boolean,
    queueRow: Boolean,
): Int {
    val optional = (if (chapterRow) 1 else 0) + (if (collectionRow) 1 else 0) + (if (queueRow) 1 else 0)
    val tail = DESKTOP_CONTROL_BAR_MORE_BASE_ROWS + optional
    return if (expanded) {
        desktopControlBarMenuHeightDp(tail)
    } else {
        desktopControlBarMenuHeightDp(
            DESKTOP_CONTROL_BAR_MORE_COMPACT_FIXED_ROWS + speedRows.coerceAtLeast(0) + qualityRows.coerceAtLeast(0) + tail,
            dividers = DESKTOP_CONTROL_BAR_MORE_COMPACT_DIVIDERS,
            sliderRows = DESKTOP_CONTROL_BAR_MORE_COMPACT_SLIDER_ROWS,
            maxDp = DESKTOP_CONTROL_BAR_MENU_COMPACT_MORE_MAX_DP,
        )
    }
}

/** The text of the quality pill: the label of the selected quality, or the plain quality word when none is selected. */
internal fun desktopControlBarQualityLabel(qualities: List<Pair<Int, String>>, selected: Int?): String =
    DesktopPlayerStrings.qualityPill(qualities.firstOrNull { it.first == selected }?.second, switching = false)
