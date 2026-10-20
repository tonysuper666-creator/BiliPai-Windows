package com.bilipai.desktop.ui

import com.android.purebilibili.feature.home.HomeFeedCardLayout
import kotlin.math.max

/*
 * Pure numbers behind the Home feed wrappers of the desktop layout (spec 3.6, slice H1). No Compose, AWT or Windows
 * dependency; the only outside types are the vendored Home layout values.
 *
 * The density rule itself (the column count, the card width, the delegation reasons, the compact metadata limit) is in
 * DesktopWindowsLayoutMetrics.kt. This file adds the small functions that the wrappers in DesktopHomeFeedLayout.kt need
 * and that the rule leaves open: the content width of the desktop layout, the space on each side of the grid, the width a
 * card has in a grid of a given column count, and the card layout that follows from it. The Python reference model
 * desktop/tools/ux/ux_home_layout_model.py mirrors them line by line, and DesktopHomeFeedLayoutTest checks the contract
 * rows through them.
 *
 * The feed draws what the card layout says: the vendored Home screen takes the start and end content padding of the grid
 * from outerPaddingDp and the spacing between the cards from itemSpacingDp and verticalItemSpacingDp (the vendored values
 * are 6 dp for all three). So the desktop card layout carries the geometry of the density rule in those three fields, the
 * 24 dp side padding and the 16 dp gutter, and that is also how a card that the rule holds at the maximum width gets its
 * centred grid: the side padding grows until the grid is exactly the capped width.
 */

/**
 * The width the Home feed measures in the desktop layout, in dp: the window width minus the footprint of the rail
 * (spec 3.6, Wc). The vendored 1280 dp cap is gone. A footprint that would leave nothing (a window that is not a shell
 * state) keeps the whole window width, so the feed never measures a zero or negative width.
 */
internal fun desktopHomeFeedDesktopWidthDp(windowWidthDp: Float, railFootprintDp: Int): Float {
    val rest = windowWidthDp - railFootprintDp
    return if (rest > 0f) rest else windowWidthDp
}

/**
 * The space on each side of a grid of columns columns on a feed of contentWidthDp, in whole dp (the vendored layout takes an
 * Int): the side padding, or, when the cards would be wider than the maximum card, the larger space that leaves a grid of
 * exactly columns maximum cards and the gutters between them, centred. An odd rest is rounded up, so the grid is at most
 * one dp narrower than the capped width and a card is never wider than the maximum. A column count below one counts as one.
 * For the columns of the density rule it is the rule's side inset (resolveDesktopHomeDensity) rounded up.
 */
internal fun desktopHomeDensitySideInsetDp(contentWidthDp: Int, columns: Int): Int {
    val count = max(columns, 1)
    val gutters = (count - 1) * DESKTOP_HOME_GUTTER_DP
    val room = contentWidthDp - 2 * DESKTOP_HOME_SIDE_PADDING_DP - gutters
    return if (room > count * DESKTOP_HOME_MAX_CARD_DP) {
        (contentWidthDp - count * DESKTOP_HOME_MAX_CARD_DP - gutters + 1) / 2
    } else {
        DESKTOP_HOME_SIDE_PADDING_DP
    }
}

/**
 * The width a card is drawn with in a grid of columns columns on a feed of contentWidthDp: the content width minus the side
 * space on both sides (desktopHomeDensitySideInsetDp) and the gutters, divided by the columns. It is the card width of the
 * density rule when the columns are the rule's own and the card is not held at the maximum; when it is held, it is the
 * maximum card (or a card a fraction of a dp narrower after the rounding of the side space). A column count below one
 * counts as one. It is a model value of the whole-dp content width: the real window width has a fraction of a dp, which
 * can make the drawn card wider by less than one dp divided by the columns.
 */
internal fun desktopHomeDensityCardWidthDp(contentWidthDp: Int, columns: Int): Double {
    val count = max(columns, 1)
    val room = contentWidthDp - 2 * desktopHomeDensitySideInsetDp(contentWidthDp, count) - (count - 1) * DESKTOP_HOME_GUTTER_DP
    return room.toDouble() / count
}

/**
 * The vendored card layout with the geometry of the density rule: the side padding is the side space of the grid, the
 * horizontal and vertical spacing are the gutter, and the compact metadata is decided by the card width. Only that one value
 * of the density policy can change (to false); the cover ratio, the story card padding and the line counts are the vendored
 * ones. The density object is the vendored instance when the compact metadata does not change.
 */
internal fun desktopHomeDensityCardLayout(
    original: HomeFeedCardLayout,
    contentWidthDp: Int,
    gridColumns: Int,
): HomeFeedCardLayout {
    val compact = desktopHomeDensityCompactMetadata(
        original.compactMetadata,
        desktopHomeDensityCardWidthDp(contentWidthDp, gridColumns),
    )
    return original.copy(
        outerPaddingDp = desktopHomeDensitySideInsetDp(contentWidthDp, gridColumns),
        itemSpacingDp = DESKTOP_HOME_GUTTER_DP,
        verticalItemSpacingDp = DESKTOP_HOME_GUTTER_DP,
        density = if (compact == original.compactMetadata) original.density else original.density.copy(compactMetadata = compact),
    )
}
