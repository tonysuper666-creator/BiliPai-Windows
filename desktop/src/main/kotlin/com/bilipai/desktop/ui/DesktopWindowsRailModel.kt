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
internal const val DESKTOP_RAIL_PANEL_EXPANDED_DP = 200
internal const val DESKTOP_RAIL_PANEL_COLLAPSED_DP = 56
internal const val DESKTOP_RAIL_MARGIN_DP = 8
internal const val DESKTOP_RAIL_PANEL_RADIUS_DP = 16
internal const val DESKTOP_RAIL_HEADER_DP = 48
internal const val DESKTOP_RAIL_SEARCH_ROW_DP = 36
internal const val DESKTOP_RAIL_SEARCH_COLLAPSED_DP = 40
internal const val DESKTOP_RAIL_NOW_PLAYING_BAR_DP = 56
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

/**
 * Whether the toggle can change what the rail shows at this window width: the explicit mode it would write must give another
 * presentation than the one on screen. A window too narrow for the rail to expand shows the collapsed rail whichever of AUTO
 * and EXPANDED is in force (the default window is such a window), so there the toggle must not write EXPANDED, which would
 * change nothing on screen and leave the toggle without a target afterwards; the shell tells the user instead. False when
 * there is nothing to write.
 */
internal fun desktopRailToggleEffective(
    mode: DesktopWindowsUxRailMode,
    presentation: DesktopRailPresentation,
    windowWidthDp: Int,
): Boolean {
    val target = desktopRailToggleMode(mode, presentation) ?: return false
    return desktopRailPresentation(target, windowWidthDp, presentation) != presentation
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

/*
 * Slice S1: what the rail composables need beyond the rows. Roving focus makes the rail one tab stop, the zoom functions
 * decide the toast after a zoom change, and the write-failure rule decides when the rail owns a save problem.
 */

/** The keys that move the roving focus of the rail (spec 3.2: Up, Down, Home, End). Enter and Space are the rows' own. */
internal enum class DesktopRailRovingKey { UP, DOWN, HOME, END }

/**
 * The stop that gets the focus after a key. Stops are numbered in tab order (the header button, the search row, then the
 * rows); count is how many there are and current is the stop that has the focus. A step stops at the first and the last
 * stop, it never wraps. Returns -1 when there is no stop at all.
 */
internal fun desktopRailRovingTarget(count: Int, current: Int, key: DesktopRailRovingKey): Int {
    if (count <= 0) return -1
    val last = count - 1
    return when (key) {
        DesktopRailRovingKey.UP -> (current - 1).coerceIn(0, last)
        DesktopRailRovingKey.DOWN -> (current + 1).coerceIn(0, last)
        DesktopRailRovingKey.HOME -> 0
        DesktopRailRovingKey.END -> last
    }
}

/**
 * The one stop that Tab can reach: the stop the user moved to last (roving), else the stop of the selected row, else the
 * first. An index outside the stops is pulled to the nearest one. Returns -1 when there is no stop at all.
 */
internal fun desktopRailTabStop(count: Int, roving: Int?, selected: Int?): Int {
    if (count <= 0) return -1
    return (roving ?: selected ?: 0).coerceIn(0, count - 1)
}

/**
 * The app zoom in percent, read back from the two densities the display-scale scope provides: the scaled density the
 * content sees and the system density it started from. Null when the system density is unknown or either value is not a
 * positive finite number.
 */
internal fun desktopZoomPercent(density: Float, systemDensity: Float?): Int? {
    if (systemDensity == null) return null
    if (!(density > 0f) || !(systemDensity > 0f)) return null
    if (density.isInfinite() || systemDensity.isInfinite()) return null
    return kotlin.math.round(density.toDouble() / systemDensity.toDouble() * 100.0).toInt()
}

/** The percent the zoom toast shows: the new one, and only when both reads are known and differ, so the first read stays silent. */
internal fun desktopZoomToastPercent(previous: Int?, current: Int?): Int? =
    if (previous != null && current != null && previous != current) current else null

/** A write error that arrives this long after the rail wrote its mode is taken to be the rail's own. */
internal const val DESKTOP_RAIL_SAVE_WINDOW_MS = 5000L

/**
 * How long the frame waits before it looks at the published write error again, after a rail write that found an error already
 * standing. A second failure in a row leaves the published error as it was, so no new value arrives to tell about it; a write that
 * succeeds clears the error well inside this time.
 */
internal const val DESKTOP_RAIL_SAVE_RECHECK_MS = 750L

/**
 * Whether the rail names its keys (Ctrl+B on the toggle, Ctrl+K on the search row, Ctrl+1 to Ctrl+9 on the rows) in tooltips and
 * in the search hint. They are named only while the shell shortcut host that handles them exists: slice S2 sets this to true in
 * the same change that adds that host, and not before, so the rail never names a key that does nothing.
 */
internal const val DESKTOP_RAIL_SHORTCUT_HINTS = false

/**
 * Whether a published write error is shown as "the rail setting could not be saved". The store reports one error for the
 * whole windows_ux namespace, so the rail claims it only when it wrote a mode just before (writeIssuedAtMs, null when it
 * never did) and the error is there.
 */
internal fun desktopRailWriteFailureShown(writeIssuedAtMs: Long?, nowMs: Long, errorPublished: Boolean): Boolean {
    if (!errorPublished || writeIssuedAtMs == null) return false
    return (nowMs - writeIssuedAtMs) in 0L..DESKTOP_RAIL_SAVE_WINDOW_MS
}
