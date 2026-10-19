package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxRailMode

/*
 * Pure model of the desktop navigation rail (spec 3.2 and 3.3). No Compose, AWT or Windows dependency.
 *
 * The footprints, the mode thresholds, the group order, the fixed destinations and the badge cap are
 * mirrored by desktop/tools/ux/desktop-ux-contract.json ("rail"). The Python suite test_ux_layout_golden.py
 * and DesktopWindowsRailModelTest check that the two agree. The rows are the items of the vendored
 * BottomNavItem enum (matched by constant name, STORY excluded) plus the two rows the rail adds itself.
 * Every user-facing text lives in DesktopShellStrings.
 */

// UX-RAIL-METRICS-BEGIN
internal const val DESKTOP_RAIL_FOOTPRINT_EXPANDED_DP = 216
internal const val DESKTOP_RAIL_FOOTPRINT_COLLAPSED_DP = 72
internal const val DESKTOP_RAIL_FOOTPRINT_CLASSIC_DP = 172
internal const val DESKTOP_RAIL_BADGE_CAP = 99
// UX-RAIL-METRICS-END

/** Window widths (dp after zoom) at which a mode expands, collapses and re-expands. */
internal data class DesktopRailThresholds(val expandAt: Int, val collapseBelow: Int, val reexpandAt: Int)

// UX-RAIL-THRESHOLDS-BEGIN
internal val DESKTOP_RAIL_AUTO_THRESHOLDS = DesktopRailThresholds(expandAt = 1280, collapseBelow = 1264, reexpandAt = 1280)
internal val DESKTOP_RAIL_EXPANDED_THRESHOLDS = DesktopRailThresholds(expandAt = 1100, collapseBelow = 1084, reexpandAt = 1116)
// UX-RAIL-THRESHOLDS-END

/** What the rail looks like. The footprint is the panel plus its margins: the width the content loses. */
internal enum class DesktopRailPresentation(val contractName: String, val footprintDp: Int) {
    EXPANDED("expanded", DESKTOP_RAIL_FOOTPRINT_EXPANDED_DP),
    COLLAPSED("collapsed", DESKTOP_RAIL_FOOTPRINT_COLLAPSED_DP),
    CLASSIC("classic", DESKTOP_RAIL_FOOTPRINT_CLASSIC_DP),
}

/**
 * The presentation for a window width. previous is the presentation the window had before (the hysteresis
 * memory), or null for a fresh start. COLLAPSED and CLASSIC_TEXT ignore the width.
 */
internal fun desktopRailPresentation(
    mode: DesktopWindowsUxRailMode,
    windowWidthDp: Int,
    previous: DesktopRailPresentation? = null,
): DesktopRailPresentation = when (mode) {
    DesktopWindowsUxRailMode.COLLAPSED -> DesktopRailPresentation.COLLAPSED
    DesktopWindowsUxRailMode.CLASSIC_TEXT -> DesktopRailPresentation.CLASSIC
    DesktopWindowsUxRailMode.AUTO -> desktopRailWithHysteresis(DESKTOP_RAIL_AUTO_THRESHOLDS, windowWidthDp, previous)
    DesktopWindowsUxRailMode.EXPANDED -> desktopRailWithHysteresis(DESKTOP_RAIL_EXPANDED_THRESHOLDS, windowWidthDp, previous)
}

private fun desktopRailWithHysteresis(
    limits: DesktopRailThresholds,
    windowWidthDp: Int,
    previous: DesktopRailPresentation?,
): DesktopRailPresentation {
    val expanded = when (previous) {
        DesktopRailPresentation.EXPANDED -> windowWidthDp >= limits.collapseBelow
        DesktopRailPresentation.COLLAPSED -> windowWidthDp >= limits.reexpandAt
        else -> windowWidthDp >= limits.expandAt
    }
    return if (expanded) DesktopRailPresentation.EXPANDED else DesktopRailPresentation.COLLAPSED
}

/**
 * The explicit mode a hamburger click or Ctrl+B writes (spec 3.3): the opposite of what the rail shows now, so an
 * expanded rail writes COLLAPSED and a collapsed one EXPANDED. Null when there is nothing to write: the classic block
 * has no collapsed form and keeps its explicit choice, and a mode that already is the opposite changes nothing.
 */
internal fun desktopRailToggleMode(
    mode: DesktopWindowsUxRailMode,
    presentation: DesktopRailPresentation,
): DesktopWindowsUxRailMode? {
    val target = when (presentation) {
        DesktopRailPresentation.EXPANDED -> DesktopWindowsUxRailMode.COLLAPSED
        DesktopRailPresentation.COLLAPSED -> DesktopWindowsUxRailMode.EXPANDED
        DesktopRailPresentation.CLASSIC -> null
    }
    return if (target == mode) null else target
}

/** An entry of the rail context menu (spec 3.2). Choosing it writes the explicit mode it carries. */
internal enum class DesktopRailMenuAction(val mode: DesktopWindowsUxRailMode) {
    COLLAPSE(DesktopWindowsUxRailMode.COLLAPSED),
    EXPAND(DesktopWindowsUxRailMode.EXPANDED),
    RESTORE_AUTO(DesktopWindowsUxRailMode.AUTO),
}

/**
 * The menu entries for the mode in force and what the rail shows: the toggle that is the opposite of the presentation,
 * then the way back to AUTO. An entry that would write the mode already in force is left out, so every entry changes
 * something. The classic block has no menu.
 */
internal fun desktopRailMenuActions(
    mode: DesktopWindowsUxRailMode,
    presentation: DesktopRailPresentation,
): List<DesktopRailMenuAction> {
    if (presentation == DesktopRailPresentation.CLASSIC) return emptyList()
    val toggle = if (presentation == DesktopRailPresentation.EXPANDED) {
        DesktopRailMenuAction.COLLAPSE
    } else {
        DesktopRailMenuAction.EXPAND
    }
    return listOf(toggle, DesktopRailMenuAction.RESTORE_AUTO).filter { it.mode != mode }
}

internal enum class DesktopRailGroup { BROWSE, LIBRARY, FOOTER }

/**
 * A row the rail can show, in display order. bottomNavName is the name of the vendored BottomNavItem constant
 * the row stands for; DOWNLOADS and ACCOUNT_SWITCH are rows of the rail itself and have none.
 */
internal enum class DesktopRailItem(val group: DesktopRailGroup, val bottomNavName: String?) {
    HOME(DesktopRailGroup.BROWSE, "HOME"),
    DYNAMIC(DesktopRailGroup.BROWSE, "DYNAMIC"),
    LIVE(DesktopRailGroup.BROWSE, "LIVE"),
    LISTEN_VIDEO(DesktopRailGroup.BROWSE, "LISTEN_VIDEO"),
    HISTORY(DesktopRailGroup.LIBRARY, "HISTORY"),
    FAVORITE(DesktopRailGroup.LIBRARY, "FAVORITE"),
    WATCHLATER(DesktopRailGroup.LIBRARY, "WATCHLATER"),
    DOWNLOADS(DesktopRailGroup.LIBRARY, null),
    ACCOUNT_SWITCH(DesktopRailGroup.FOOTER, null),
    PROFILE(DesktopRailGroup.FOOTER, "PROFILE"),
    PLUGINS(DesktopRailGroup.FOOTER, "PLUGINS"),
    SETTINGS(DesktopRailGroup.FOOTER, "SETTINGS"),
}

/** BottomNavItem constants that never get a row. */
internal val DESKTOP_RAIL_EXCLUDED_BOTTOM_NAV: Set<String> = setOf("STORY")

// UX-RAIL-FIXED-BEGIN
/** Ctrl+1 to Ctrl+9 go to these, in this order. The conditional account row and the plugin and settings rows never shift the numbers. */
internal val DESKTOP_RAIL_FIXED_DESTINATIONS: List<DesktopRailItem> = listOf(
    DesktopRailItem.HOME, DesktopRailItem.DYNAMIC, DesktopRailItem.LIVE, DesktopRailItem.LISTEN_VIDEO,
    DesktopRailItem.HISTORY, DesktopRailItem.FAVORITE, DesktopRailItem.WATCHLATER, DesktopRailItem.DOWNLOADS,
    DesktopRailItem.PROFILE,
)
// UX-RAIL-FIXED-END

/** The destination of Ctrl+number, or null when the number is not 1 to 9. */
internal fun desktopRailDestinationForNumber(number: Int): DesktopRailItem? =
    DESKTOP_RAIL_FIXED_DESTINATIONS.getOrNull(number - 1)

/** The Ctrl+number that reaches the item, or null when the item is not a fixed destination. */
internal fun desktopRailNumberOf(item: DesktopRailItem): Int? {
    val index = DESKTOP_RAIL_FIXED_DESTINATIONS.indexOf(item)
    return if (index < 0) null else index + 1
}

/** One row: a known item, or (item null) a BottomNavItem the table does not know, which joins the browse group. */
internal data class DesktopRailEntry(val item: DesktopRailItem?, val bottomNavName: String?) {
    val group: DesktopRailGroup get() = item?.group ?: DesktopRailGroup.BROWSE
}

/**
 * The rows in display order. visibleBottomNavNames are the names of the BottomNavItem constants the shell
 * shows (STORY is dropped here too). Rows of the table appear in table order, the downloads row always, the
 * account row only when accountSwitcherAvailable, and an item the table does not know is appended to the browse group.
 */
internal fun desktopRailEntries(
    visibleBottomNavNames: List<String>,
    accountSwitcherAvailable: Boolean,
): List<DesktopRailEntry> {
    val visible = visibleBottomNavNames.filter { it !in DESKTOP_RAIL_EXCLUDED_BOTTOM_NAV }
    val known = DesktopRailItem.entries.filter { item ->
        val name = item.bottomNavName
        when {
            item == DesktopRailItem.ACCOUNT_SWITCH -> accountSwitcherAvailable
            name == null -> true
            else -> name in visible
        }
    }.map { DesktopRailEntry(it, it.bottomNavName) }
    val unknown = visible
        .filter { name -> DesktopRailItem.entries.none { it.bottomNavName == name } }
        .map { DesktopRailEntry(null, it) }
    val (browse, rest) = known.partition { it.group == DesktopRailGroup.BROWSE }
    return browse + unknown + rest
}

/**
 * The selection rule the shell already uses: the main host shows the current item, or the current route is the
 * item's route. Rows without a BottomNavItem (downloads, account) are never selected by it.
 */
internal fun desktopRailItemSelected(
    onMainHost: Boolean,
    currentItemName: String,
    itemName: String,
    currentLegacyRoute: String,
    itemRoute: String,
): Boolean = onMainHost && itemName == currentItemName || currentLegacyRoute == itemRoute

internal enum class DesktopRailClick { NAVIGATE, HOME_DOUBLE_TAP, DYNAMIC_DOUBLE_TAP }

/** Clicking the row that is already selected scrolls to the top and refreshes for HOME and DYNAMIC; everything else navigates. */
internal fun desktopRailClick(item: DesktopRailItem?, selected: Boolean): DesktopRailClick = when {
    !selected -> DesktopRailClick.NAVIGATE
    item == DesktopRailItem.HOME -> DesktopRailClick.HOME_DOUBLE_TAP
    item == DesktopRailItem.DYNAMIC -> DesktopRailClick.DYNAMIC_DOUBLE_TAP
    else -> DesktopRailClick.NAVIGATE
}

internal enum class DesktopRailBadgeKind { NONE, PILL, DOT }

/** The unread count as badge text: nothing for zero, the number up to the cap, then the cap with a plus sign. */
internal fun desktopRailBadgeText(unreadCount: Int): String? = when {
    unreadCount <= 0 -> null
    unreadCount > DESKTOP_RAIL_BADGE_CAP -> "$DESKTOP_RAIL_BADGE_CAP+"
    else -> unreadCount.toString()
}

/** An expanded rail shows the pill with the text, a collapsed rail only a dot. */
internal fun desktopRailBadgeKind(unreadCount: Int, presentation: DesktopRailPresentation): DesktopRailBadgeKind = when {
    unreadCount <= 0 -> DesktopRailBadgeKind.NONE
    presentation == DesktopRailPresentation.COLLAPSED -> DesktopRailBadgeKind.DOT
    else -> DesktopRailBadgeKind.PILL
}
