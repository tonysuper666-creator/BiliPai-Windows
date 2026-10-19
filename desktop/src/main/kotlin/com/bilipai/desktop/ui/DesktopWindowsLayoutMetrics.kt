package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxHomeLayout
import kotlin.math.max
import kotlin.math.min

/*
 * Pure Home density rule of the desktop shell (spec 3.6). No Compose, AWT or Windows dependency.
 *
 * Wc is the content width: the window width minus the rail footprint. The usable width is Wc minus the side
 * padding on both sides. The column count is how many cards of the wanted minimum width fit with the gutter
 * between them, clamped to 2 and to the column limit of the width. A card that would exceed the maximum is held
 * at the maximum and the grid is centred. The home_layout flag, a pinned column count and the single-column
 * display mode keep the vendored behaviour: they are reported as a delegation and the caller runs the original
 * function. The numbers are mirrored by desktop/tools/ux/desktop-ux-contract.json ("feed"); the Python suite
 * test_ux_layout_golden.py and DesktopWindowsLayoutMetricsTest check that the two agree.
 */

// UX-HOME-DENSITY-BEGIN
internal const val DESKTOP_HOME_SIDE_PADDING_DP = 24
internal const val DESKTOP_HOME_GUTTER_DP = 16
internal const val DESKTOP_HOME_MIN_CARD_DP = 216
internal const val DESKTOP_HOME_MAX_CARD_DP = 320
internal const val DESKTOP_HOME_CARD_FLOOR_DP = 160
internal const val DESKTOP_HOME_MIN_COLUMNS = 2
internal const val DESKTOP_HOME_MAX_COLUMNS_BELOW_WIDE = 8
internal const val DESKTOP_HOME_MAX_COLUMNS_WIDE = 10
internal const val DESKTOP_HOME_WIDE_FROM_CONTENT_DP = 2800
internal const val DESKTOP_HOME_COMPACT_METADATA_OFF_FROM_CARD_DP = 200
internal const val DESKTOP_HOME_MIN_CONTENT_DP = 884
internal const val DESKTOP_HOME_LEGACY_CAP_DP = 1280
// UX-HOME-DENSITY-END

/** The vendored display mode value for single-column cards (resolveHomeFeedGridColumns treats displayMode 1 so). */
internal const val DESKTOP_HOME_SINGLE_COLUMN_DISPLAY_MODE = 1

/** The Home grid for one content width. cardWidthDp is exact; contract rows round it to one decimal. */
internal data class DesktopHomeDensityLayout(
    val contentWidthDp: Int,
    val columns: Int,
    val cardWidthDp: Double,
    val gridWidthDp: Int,
    val capped: Boolean,
) {
    /** The space on each side of the centred grid, side padding included: exactly the side padding unless the grid is capped. */
    val sideInsetDp: Double get() = (contentWidthDp - gridWidthDp) / 2.0
}

/**
 * The Home grid for a content width in dp (positive; a smaller one is not a shell state). minCardDp is the
 * user's card-width preset in dp, or null for the default; it is raised to the floor.
 */
internal fun resolveDesktopHomeDensity(contentWidthDp: Int, minCardDp: Int? = null): DesktopHomeDensityLayout {
    val usable = contentWidthDp - 2 * DESKTOP_HOME_SIDE_PADDING_DP
    val wanted = max(minCardDp ?: DESKTOP_HOME_MIN_CARD_DP, DESKTOP_HOME_CARD_FLOOR_DP)
    val columnLimit = if (contentWidthDp >= DESKTOP_HOME_WIDE_FROM_CONTENT_DP) {
        DESKTOP_HOME_MAX_COLUMNS_WIDE
    } else {
        DESKTOP_HOME_MAX_COLUMNS_BELOW_WIDE
    }
    val columns = ((usable + DESKTOP_HOME_GUTTER_DP) / (wanted + DESKTOP_HOME_GUTTER_DP))
        .coerceIn(DESKTOP_HOME_MIN_COLUMNS, columnLimit)
    val room = usable - (columns - 1) * DESKTOP_HOME_GUTTER_DP
    return if (room > columns * DESKTOP_HOME_MAX_CARD_DP) {
        DesktopHomeDensityLayout(
            contentWidthDp = contentWidthDp,
            columns = columns,
            cardWidthDp = DESKTOP_HOME_MAX_CARD_DP.toDouble(),
            gridWidthDp = columns * DESKTOP_HOME_MAX_CARD_DP + (columns - 1) * DESKTOP_HOME_GUTTER_DP,
            capped = true,
        )
    } else {
        DesktopHomeDensityLayout(
            contentWidthDp = contentWidthDp,
            columns = columns,
            cardWidthDp = room.toDouble() / columns,
            gridWidthDp = usable,
            capped = false,
        )
    }
}

/**
 * The content width the Home feed measures. The desktop layout takes the whole width the rail leaves; the classic
 * layout keeps the vendored expression: the window width held at the legacy cap.
 */
internal fun desktopHomeDensityContentWidthDp(
    windowWidthDp: Int,
    railFootprintDp: Int,
    homeLayout: DesktopWindowsUxHomeLayout,
): Int = when (homeLayout) {
    DesktopWindowsUxHomeLayout.DESKTOP -> windowWidthDp - railFootprintDp
    DesktopWindowsUxHomeLayout.CLASSIC -> min(windowWidthDp, DESKTOP_HOME_LEGACY_CAP_DP)
}

/** Why the vendored column function keeps the decision. */
internal enum class DesktopHomeDensityDelegation { CLASSIC_LAYOUT, SINGLE_COLUMN_MODE, PINNED_COLUMN_COUNT }

/**
 * Null when the density rule decides. The vendored function ignores a pinned count in single-column mode, so that
 * reason comes first.
 */
internal fun desktopHomeDensityDelegation(
    homeLayout: DesktopWindowsUxHomeLayout,
    displayMode: Int,
    fixedColumnCount: Int,
): DesktopHomeDensityDelegation? = when {
    homeLayout == DesktopWindowsUxHomeLayout.CLASSIC -> DesktopHomeDensityDelegation.CLASSIC_LAYOUT
    displayMode == DESKTOP_HOME_SINGLE_COLUMN_DISPLAY_MODE -> DesktopHomeDensityDelegation.SINGLE_COLUMN_MODE
    fixedColumnCount > 0 -> DesktopHomeDensityDelegation.PINNED_COLUMN_COUNT
    else -> null
}

/** Compact metadata stays as the vendored layout decided it, except that a card of at least the limit width shows full metadata. */
internal fun desktopHomeDensityCompactMetadata(originalCompactMetadata: Boolean, cardWidthDp: Double): Boolean =
    originalCompactMetadata && cardWidthDp < DESKTOP_HOME_COMPACT_METADATA_OFF_FROM_CARD_DP
