package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeFeedCardWidthPreset
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.feature.home.resolveHomeFeedPinchColumnBounds
import com.android.purebilibili.feature.space.resolveSpaceContentGridColumnCount
import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import kotlin.math.max

/*
 * The user space page and the profile page in the desktop layout (TON-1060). The patches of SpaceScreen.kt and
 * ProfileScreen.kt call these functions instead of the vendored width, column and split decisions.
 *
 * The space page keeps its profile header above the grid (a full-line item of the one grid of each tab). With the
 * home_layout flag at DESKTOP its contribution grid is the Home feed grid: the same measured width (the window minus the
 * rail, held at the vendored 1280 dp cap when the columns are delegated), the same column function and the same card layout
 * (desktopResolveHomeFeedCardLayout) with its side padding, the same pinch bounds and the same stored counts (the raw stored
 * count decides the delegation, the effective one the columns, as in the generated Home screen), so after the column
 * preference of each page a space page and Home show the same columns and the same card width in the same window. The
 * vendored page held its own 1280 dp cap over the whole window and at least 16 dp of side padding, so it showed fewer,
 * wider cards than Home, or as many but narrower. The dynamic tab keeps its readable minimum width (360 dp) but
 * takes as many columns as the grid width holds instead of at most 3 of a capped page, and every tab uses the side padding
 * of the contribution grid, so the header does not change width when the tab changes. CLASSIC is the vendored page exactly.
 *
 * The profile page in a wide window was a split: the profile column (300 to 360 dp) on the left and the feed on the
 * right. With the flag at DESKTOP the profile header and the shortcuts sit in one band above the feed, which takes the
 * full width (desktopProfileHeaderOnTop).
 */

/** The readable minimum width of a dynamic card, the vendored SPACE_DYNAMIC_MIN_COLUMN_WIDTH_DP (private there). */
internal const val DESKTOP_SPACE_DYNAMIC_MIN_CARD_DP = 360

/** What the space page grid is for one window: the width the grid measures, the cap of the page, the columns and the pinch bounds. */
internal data class DesktopSpaceFeedGrid(
    val layout: DesktopWindowsUxHomeLayout,
    /** The columns belong to the vendored function (a pinned count): the whole grid is the vendored grid of a capped width. */
    val delegated: Boolean,
    /** The width the contribution columns are resolved for, in dp. */
    val contentWidthDp: Int,
    /** The cap of the page box (responsiveContentWidth); Dp.Infinity takes the whole content slot. */
    val maxWidth: Dp,
    /** The contribution columns the page starts with (the default of the column preference). */
    val columns: Int,
    /** The pinch and preference bounds, the ones the Home screen gives its column preference for the same width. */
    val bounds: IntRange,
) {
    /** The density rule decides the grid (DESKTOP, not delegated). */
    val desktop: Boolean get() = layout == DesktopWindowsUxHomeLayout.DESKTOP && !delegated

    /**
     * The columns of the dynamic tab: the vendored count (vendoredDynamicColumns) unless the density rule decides; then as many
     * cards of at least the readable minimum as the grid width holds (the content width less the side space of the contribution
     * grid), with the gutter between them.
     */
    fun dynamicColumns(vendoredDynamicColumns: Int, sideInsetDp: Int): Int = if (desktop) {
        val usable = contentWidthDp - 2 * sideInsetDp
        max((usable + DESKTOP_HOME_GUTTER_DP) / (DESKTOP_SPACE_DYNAMIC_MIN_CARD_DP + DESKTOP_HOME_GUTTER_DP), 1)
    } else {
        vendoredDynamicColumns
    }

    /** The columns the card layout of a tab is resolved for: the contribution columns on every tab when the rule decides, so the side padding (and the header) is one value for the page. */
    fun cardLayoutColumns(pageColumns: Int, contributionColumns: Int): Int = if (desktop) contributionColumns else pageColumns

    /** The side padding of the grid: the card layout's own (as Home draws it) in the desktop layout, the vendored at least 16 dp for CLASSIC. */
    fun outerPaddingDp(cardLayoutPaddingDp: Int): Int =
        if (layout == DesktopWindowsUxHomeLayout.DESKTOP) cardLayoutPaddingDp else maxOf(16, cardLayoutPaddingDp)
}

/**
 * The space grid for a window, pure. windowWidthDp is the window width in dp, railFootprintDp the shell inset, expanded the
 * vendored isExpandedScreen (the Home screen measures the rail only on an expanded window), fixedColumnCount the stored
 * column count the vendored page resolves (the effective one, 0 when customization is off), delegationColumnCount the raw
 * stored count the generated Home screen decides the delegation on (desktopHomeFeedColumnsDelegated), and
 * vendoredContentMaxWidthDp the cap of resolveSpaceAdaptiveLayoutSpec.
 */
internal fun desktopSpaceFeedGrid(
    layout: DesktopWindowsUxHomeLayout,
    windowWidthDp: Int,
    railFootprintDp: Int,
    expanded: Boolean,
    widthSizeClass: WindowWidthSizeClass,
    fixedColumnCount: Int,
    delegationColumnCount: Int,
    cardWidthPreset: HomeFeedCardWidthPreset,
    vendoredContentMaxWidthDp: Int,
): DesktopSpaceFeedGrid = when (layout) {
    DesktopWindowsUxHomeLayout.CLASSIC -> DesktopSpaceFeedGrid(
        layout = layout,
        delegated = true,
        contentWidthDp = minOf(windowWidthDp, vendoredContentMaxWidthDp),
        maxWidth = vendoredContentMaxWidthDp.dp,
        columns = resolveSpaceContentGridColumnCount(
            widthDp = windowWidthDp,
            fixedColumnCount = fixedColumnCount,
            cardWidthPreset = cardWidthPreset,
            contentMaxWidthDp = vendoredContentMaxWidthDp,
            widthSizeClass = widthSizeClass,
        ),
        bounds = resolveHomeFeedPinchColumnBounds(widthSizeClass, windowWidthDp),
    )
    DesktopWindowsUxHomeLayout.DESKTOP -> {
        val delegated = desktopHomeFeedColumnsDelegated(displayMode = 0, fixedColumnCount = delegationColumnCount)
        // The generated Home screen: the measured width on an expanded window, the window width otherwise.
        val contentWidthDp = if (expanded) {
            desktopHomeFeedMeasuredWidth(windowWidthDp.dp, railFootprintDp, layout, delegated).value.toInt()
        } else {
            windowWidthDp
        }
        val columns = desktopResolveHomeFeedGridColumns(
            layout = layout,
            contentWidthDp = contentWidthDp,
            displayMode = 0,
            fixedColumnCount = fixedColumnCount,
            cardWidthPreset = cardWidthPreset,
            widthSizeClass = widthSizeClass,
        )
        DesktopSpaceFeedGrid(
            layout = layout,
            delegated = delegated,
            contentWidthDp = contentWidthDp,
            // The cap of a delegated Home feed (the vendored 1280 dp), read through the Home wrapper: its width for an unbounded window.
            maxWidth = if (delegated) desktopHomeFeedMeasuredWidth(Dp.Infinity, railFootprintDp, layout, delegated = true) else Dp.Infinity,
            columns = columns,
            bounds = resolveHomeFeedPinchColumnBounds(widthSizeClass, contentWidthDp),
        )
    }
}

/** desktopSpaceFeedGrid for the flag in force and the rail of the shell, for the patched space page. */
@Composable
internal fun desktopSpaceFeedGrid(
    windowWidthDp: Int,
    expanded: Boolean,
    widthSizeClass: WindowWidthSizeClass,
    fixedColumnCount: Int,
    delegationColumnCount: Int,
    cardWidthPreset: HomeFeedCardWidthPreset,
    vendoredContentMaxWidthDp: Int,
): DesktopSpaceFeedGrid = desktopSpaceFeedGrid(
    layout = desktopHomeFeedLayoutKey(),
    windowWidthDp = windowWidthDp,
    railFootprintDp = LocalDesktopShellInsets.current.leadingDp,
    expanded = expanded,
    widthSizeClass = widthSizeClass,
    fixedColumnCount = fixedColumnCount,
    delegationColumnCount = delegationColumnCount,
    cardWidthPreset = cardWidthPreset,
    vendoredContentMaxWidthDp = vendoredContentMaxWidthDp,
)

/** Whether the profile page puts its header above the feed instead of the vendored split; pure for the tests. */
internal fun desktopProfileHeaderOnTop(layout: DesktopWindowsUxHomeLayout, splitLayout: Boolean): Boolean =
    splitLayout && layout == DesktopWindowsUxHomeLayout.DESKTOP

/** desktopProfileHeaderOnTop for the flag in force, for the patched profile page. */
@Composable
internal fun desktopProfileHeaderOnTop(splitLayout: Boolean): Boolean =
    desktopProfileHeaderOnTop(desktopHomeFeedLayoutKey(), splitLayout)
