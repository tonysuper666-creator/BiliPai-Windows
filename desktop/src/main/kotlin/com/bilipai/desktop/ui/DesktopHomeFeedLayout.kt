package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.HomeFeedCardStyle
import com.android.purebilibili.core.store.HomeFeedCardWidthPreset
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.core.util.WindowWidthSizeClass
import com.android.purebilibili.feature.home.HomeFeedCardLayout
import com.android.purebilibili.feature.home.resolveHomeFeedCardLayout
import com.android.purebilibili.feature.home.resolveHomeFeedGridColumns
import com.android.purebilibili.feature.home.resolveHomeFeedMaxContentWidth
import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import com.bilipai.desktop.appearance.LocalDesktopWindowsUx

/*
 * The wrappers that the generated Home screen and category page call instead of the vendored layout functions
 * (spec 3.6, slice H1). desktop/tools/ux_home_layout.py replaces the call sites of the vendored feed width, grid
 * column and card layout functions in the generated text with calls to these functions, and adds the delegation value
 * (desktopHomeFeedColumnsDelegated) that the screen computes once and hands to the width, the card layout and the
 * category page; the parameter lists repeat the vendored ones (named arguments, the same defaults), with the layout flag
 * added to the column function and the delegation added, last and defaulted, to the width and the card layout.
 *
 * The home_layout flag decides. CLASSIC runs the vendored function itself, so the classic layout is the vendored
 * behaviour exactly. DESKTOP takes the whole width the rail leaves (the density rule of DesktopWindowsLayoutMetrics.kt)
 * and gives the column count from it, with one exception: a pinned (or pinched) column count and the single-column
 * display mode belong to the vendored behaviour (spec 3.6, "delegate to the original function"). When the screen says the
 * columns are delegated, the whole feed is the vendored feed: the vendored column function, the vendored card layout (the
 * same instance, 6 dp padding and spacing) and the vendored 1280 dp cap on the width, which is what bounds those modes (a
 * Story card or a pinned two-column grid would otherwise be as wide as half of a 2500 dp feed). The cap is applied to
 * the width the rail leaves, so the delegated feed is the vendored feed of the room the shell gives the page. Without the
 * delegation the DESKTOP card layout carries what the feed draws: the side padding (which centres a grid whose cards the
 * rule holds at the maximum width), the 16 dp gutter and the metadata density. The numbers are in
 * DesktopHomeFeedLayoutPolicy.kt.
 */

/** The home_layout flag in force, read in composable scope so that the generated screen can use it as a remember key. */
@Composable
internal fun desktopHomeFeedLayoutKey(): DesktopWindowsUxHomeLayout {
    val settings by LocalDesktopWindowsUx.current.settings.collectAsState()
    return settings.homeLayout
}

/**
 * Whether the vendored layout keeps the feed in the desktop layout: the single-column display mode or a stored column count
 * (the delegation of the column function, desktopHomeDensityDelegation, for the desktop layout). The stored count is the one
 * the vendored screen resolves for the window width class; a pinch that has not ended is not stored yet and is not counted.
 */
internal fun desktopHomeFeedColumnsDelegated(displayMode: Int, fixedColumnCount: Int): Boolean =
    desktopHomeDensityDelegation(DesktopWindowsUxHomeLayout.DESKTOP, displayMode, fixedColumnCount) != null

/**
 * The width of the feed for a window width, as a plain value: the vendored cap for CLASSIC, window minus rail for DESKTOP,
 * and window minus rail held at the vendored cap for a DESKTOP feed whose columns are delegated.
 */
internal fun desktopHomeFeedMeasuredWidth(
    windowWidth: Dp,
    railFootprintDp: Int,
    homeLayout: DesktopWindowsUxHomeLayout,
    delegated: Boolean = false,
): Dp = when (homeLayout) {
    DesktopWindowsUxHomeLayout.CLASSIC -> minOf(windowWidth, resolveHomeFeedMaxContentWidth())
    DesktopWindowsUxHomeLayout.DESKTOP -> if (delegated) {
        minOf(desktopHomeFeedDesktopWidthDp(windowWidth.value, railFootprintDp).dp, resolveHomeFeedMaxContentWidth())
    } else {
        desktopHomeFeedDesktopWidthDp(windowWidth.value, railFootprintDp).dp
    }
}

/** Replaces minOf(windowSizeClass.widthDp, resolveHomeFeedMaxContentWidth()) in the generated Home screen. */
@Composable
internal fun desktopHomeFeedContentWidth(windowWidth: Dp, delegated: Boolean = false): Dp =
    desktopHomeFeedMeasuredWidth(windowWidth, LocalDesktopShellInsets.current.leadingDp, desktopHomeFeedLayoutKey(), delegated)

/** Replaces resolveHomeFeedGridColumns in the generated Home screen; layout is the flag that desktopHomeFeedLayoutKey read. */
internal fun desktopResolveHomeFeedGridColumns(
    layout: DesktopWindowsUxHomeLayout,
    contentWidthDp: Int,
    displayMode: Int,
    fixedColumnCount: Int,
    cardWidthPreset: HomeFeedCardWidthPreset,
    widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Medium,
): Int = if (desktopHomeDensityDelegation(layout, displayMode, fixedColumnCount) != null) {
    resolveHomeFeedGridColumns(
        contentWidthDp = contentWidthDp,
        displayMode = displayMode,
        fixedColumnCount = fixedColumnCount,
        cardWidthPreset = cardWidthPreset,
        widthSizeClass = widthSizeClass,
    )
} else {
    resolveDesktopHomeDensity(contentWidthDp, cardWidthPreset.minCardWidthDp).columns
}

/**
 * The card layout of the feed for a flag: the vendored layout itself for CLASSIC and for a DESKTOP feed whose columns are
 * delegated (the same instance), the layout with the geometry of the density rule otherwise. Pure, so that it is tested
 * without a composition.
 */
internal fun desktopSelectHomeFeedCardLayout(
    layout: DesktopWindowsUxHomeLayout,
    original: HomeFeedCardLayout,
    contentWidthDp: Int,
    gridColumns: Int,
    delegated: Boolean = false,
): HomeFeedCardLayout = when (layout) {
    DesktopWindowsUxHomeLayout.CLASSIC -> original
    DesktopWindowsUxHomeLayout.DESKTOP -> if (delegated) original else desktopHomeDensityCardLayout(original, contentWidthDp, gridColumns)
}

/**
 * Replaces resolveHomeFeedCardLayout in the generated Home screen (two call sites) and category page (one). It reads the
 * flag and the width the feed measures itself and remembers the result, so the three call sites need no extra key; they
 * pass the delegation of the screen.
 */
@Composable
internal fun desktopResolveHomeFeedCardLayout(
    style: HomeFeedCardStyle,
    gridColumns: Int = 2,
    widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    delegated: Boolean = false,
): HomeFeedCardLayout {
    val layout = desktopHomeFeedLayoutKey()
    val contentWidthDp = desktopHomeFeedMeasuredWidth(
        LocalWindowSizeClass.current.widthDp,
        LocalDesktopShellInsets.current.leadingDp,
        layout,
        delegated,
    ).value.toInt()
    return remember(layout, style, gridColumns, widthSizeClass, contentWidthDp, delegated) {
        desktopSelectHomeFeedCardLayout(
            layout,
            resolveHomeFeedCardLayout(
                style = style,
                gridColumns = gridColumns,
                widthSizeClass = widthSizeClass,
            ),
            contentWidthDp,
            gridColumns,
            delegated,
        )
    }
}
