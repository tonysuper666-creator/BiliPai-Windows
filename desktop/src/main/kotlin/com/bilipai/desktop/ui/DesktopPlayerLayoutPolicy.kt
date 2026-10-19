package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxDetailsPanel

/*
 * Pure player layout policy (spec 4.3). No Compose, AWT or Windows dependency. The video is the largest aspect rectangle
 * in the stage (window width minus the docked panel) times the height, centred; the tier follows the exact video width.
 * Every length is a Long numerator over a common denominator and only the results are rounded, half to even, so the
 * numbers equal the contract tables (exact rational arithmetic in ux_model.py) at every tie. Mirrors
 * desktop-ux-contract.json ("player"); test_ux_player_policy.py and DesktopPlayerLayoutPolicyTest check the agreement.
 */

// UX-PLAYER-LAYOUT-BEGIN
internal const val DESKTOP_PLAYER_ASPECT_WIDTH = 16
internal const val DESKTOP_PLAYER_ASPECT_HEIGHT = 9
internal const val DESKTOP_PLAYER_PANEL_DEFAULT_OPEN_FROM_WIDTH_DP = 1280
internal const val DESKTOP_PLAYER_DOCK_MIN_WINDOW_WIDTH_DP = 640
internal const val DESKTOP_PLAYER_FULLSCREEN_MIN_ASPECT = 2
internal const val DESKTOP_PLAYER_PANEL_MIN_DP = 320
internal const val DESKTOP_PLAYER_PANEL_MAX_DP = 560
internal const val DESKTOP_PLAYER_PANEL_DEFAULT_PERCENT = 22
internal const val DESKTOP_PLAYER_PANEL_DEFAULT_LOW_DP = 340
internal const val DESKTOP_PLAYER_PANEL_DEFAULT_HIGH_DP = 480
internal const val DESKTOP_PLAYER_MIN_STAGE_DP = 480
internal const val DESKTOP_PLAYER_TIER_L_MIN_DP = 960
internal const val DESKTOP_PLAYER_TIER_M_MIN_DP = 640
internal const val DESKTOP_PLAYER_DOCK_CAPSULE_MAX_DP = 1280
internal const val DESKTOP_PLAYER_DOCK_CAPSULE_MARGIN_DP = 32
// UX-PLAYER-LAYOUT-END

/** Input limits that keep every product below inside a Long; anything outside gets the empty layout. */
internal const val DESKTOP_PLAYER_MAX_EXTENT_DP = 100000
internal const val DESKTOP_PLAYER_MAX_ASPECT_PART = 10000

internal enum class DesktopPlayerTier { L, M, S }

/** Where the letterbox bars are; contractName is the key of the contract tables. */
internal enum class DesktopPlayerBarAxis(val contractName: String) { NONE("none"), LEFT_RIGHT("left_right"), TOP_BOTTOM("top_bottom") }

/** dp values rounded half to even from the exact result; barDp is the size of one bar (0 with none). */
internal data class DesktopPlayerLayout(
    val docked: Boolean,
    val panelWidthDp: Int,
    val videoWidthDp: Int,
    val videoHeightDp: Int,
    val tier: DesktopPlayerTier,
    val barAxis: DesktopPlayerBarAxis,
    val barDp: Int,
    val dockWidthDp: Int,
)

private val desktopPlayerEmptyLayout = DesktopPlayerLayout(false, 0, 0, 0, DesktopPlayerTier.S, DesktopPlayerBarAxis.NONE, 0, 0)

/** Half to even of numerator / denominator, for a non-negative numerator and a positive denominator. */
internal fun desktopPlayerRoundHalfEven(numerator: Long, denominator: Long): Long {
    val quotient = numerator / denominator
    val twiceRemainder = 2L * (numerator % denominator)
    return when {
        twiceRemainder < denominator -> quotient
        twiceRemainder > denominator -> quotient + 1L
        else -> if (quotient % 2L == 0L) quotient else quotient + 1L
    }
}

/**
 * Whether AUTO opens the panel, decided once from the window width at page entry. The caller remembers the result for the whole
 * page and hands it to every layout call, so a window drag can never flip the panel: the layout has no width to re-read.
 */
internal fun desktopPlayerPanelDefaultOpen(entryWidthDp: Int): Boolean = entryWidthDp >= DESKTOP_PLAYER_PANEL_DEFAULT_OPEN_FROM_WIDTH_DP

/** Whether the panel is wanted: OPEN and CLOSED are the user's choice, AUTO follows the remembered panelDefaultOpen. */
internal fun desktopPlayerPanelWanted(panel: DesktopWindowsUxDetailsPanel, panelDefaultOpen: Boolean): Boolean = when (panel) {
    DesktopWindowsUxDetailsPanel.OPEN -> true
    DesktopWindowsUxDetailsPanel.CLOSED -> false
    DesktopWindowsUxDetailsPanel.AUTO -> panelDefaultOpen
}

/**
 * The panel docks windowed from a width of 640 and in fullscreen only from an aspect of 2. Its width is the user value
 * clamped to 320..560, else min(560, max(clamp(22% of the width, 340, 480), width - height * aspect)); then at most
 * width - 480, and below 320 it does not dock. panelDefaultOpen has no default value on purpose: it is the page-entry decision
 * of desktopPlayerPanelDefaultOpen, remembered by the caller. Never throws: bad input gives the empty layout.
 */
internal fun resolveDesktopPlayerLayout(
    widthDp: Int,
    heightDp: Int,
    panelDefaultOpen: Boolean,
    fullscreen: Boolean = false,
    panel: DesktopWindowsUxDetailsPanel = DesktopWindowsUxDetailsPanel.AUTO,
    userPanelWidthDp: Int? = null,
    aspectWidth: Int = DESKTOP_PLAYER_ASPECT_WIDTH,
    aspectHeight: Int = DESKTOP_PLAYER_ASPECT_HEIGHT,
): DesktopPlayerLayout {
    if (widthDp !in 1..DESKTOP_PLAYER_MAX_EXTENT_DP || heightDp !in 1..DESKTOP_PLAYER_MAX_EXTENT_DP) return desktopPlayerEmptyLayout
    val sane = aspectWidth in 1..DESKTOP_PLAYER_MAX_ASPECT_PART && aspectHeight in 1..DESKTOP_PLAYER_MAX_ASPECT_PART
    val aw = (if (sane) aspectWidth else DESKTOP_PLAYER_ASPECT_WIDTH).toLong()
    val ah = (if (sane) aspectHeight else DESKTOP_PLAYER_ASPECT_HEIGHT).toLong()
    val w = widthDp.toLong()
    val h = heightDp.toLong()
    val d = 100L * ah // the common denominator of the panel and the stage
    val allowed = if (fullscreen) w >= DESKTOP_PLAYER_FULLSCREEN_MIN_ASPECT * h else w >= DESKTOP_PLAYER_DOCK_MIN_WINDOW_WIDTH_DP
    var panelN = 0L
    if (allowed && desktopPlayerPanelWanted(panel, panelDefaultOpen)) {
        val candidate = if (userPanelWidthDp != null) {
            userPanelWidthDp.coerceIn(DESKTOP_PLAYER_PANEL_MIN_DP, DESKTOP_PLAYER_PANEL_MAX_DP) * d
        } else {
            val share = (DESKTOP_PLAYER_PANEL_DEFAULT_PERCENT * w * ah)
                .coerceIn(DESKTOP_PLAYER_PANEL_DEFAULT_LOW_DP * d, DESKTOP_PLAYER_PANEL_DEFAULT_HIGH_DP * d)
            minOf(DESKTOP_PLAYER_PANEL_MAX_DP * d, maxOf(share, w * d - h * aw * 100L))
        }
        val fitted = minOf(candidate, (w - DESKTOP_PLAYER_MIN_STAGE_DP) * d)
        if (fitted >= DESKTOP_PLAYER_PANEL_MIN_DP * d) panelN = fitted
    }
    val stageN = w * d - panelN
    val heightBound = stageN * ah >= h * aw * d // the stage is at least as wide as the aspect rectangle of full height
    val videoWidthN = if (heightBound) h * aw else stageN
    val videoWidthD = if (heightBound) ah else d
    val videoHeightN = if (heightBound) h else stageN * ah
    val videoHeightD = if (heightBound) 1L else d * aw
    val sideN = if (heightBound) stageN * ah - h * aw * d else 0L
    val verticalN = if (heightBound) 0L else h * d * aw - stageN * ah
    val (axis, bar) = when {
        sideN > 0L -> DesktopPlayerBarAxis.LEFT_RIGHT to desktopPlayerRoundHalfEven(sideN, 2L * d * ah)
        verticalN > 0L -> DesktopPlayerBarAxis.TOP_BOTTOM to desktopPlayerRoundHalfEven(verticalN, 2L * d * aw)
        else -> DesktopPlayerBarAxis.NONE to 0L
    }
    val tier = when {
        videoWidthN >= DESKTOP_PLAYER_TIER_L_MIN_DP * videoWidthD -> DesktopPlayerTier.L
        videoWidthN >= DESKTOP_PLAYER_TIER_M_MIN_DP * videoWidthD -> DesktopPlayerTier.M
        else -> DesktopPlayerTier.S
    }
    val dockN = minOf(
        DESKTOP_PLAYER_DOCK_CAPSULE_MAX_DP * videoWidthD,
        videoWidthN - DESKTOP_PLAYER_DOCK_CAPSULE_MARGIN_DP * videoWidthD,
    ).coerceAtLeast(0L)
    return DesktopPlayerLayout(
        docked = panelN > 0L,
        panelWidthDp = desktopPlayerRoundHalfEven(panelN, d).toInt(),
        videoWidthDp = desktopPlayerRoundHalfEven(videoWidthN, videoWidthD).toInt(),
        videoHeightDp = desktopPlayerRoundHalfEven(videoHeightN, videoHeightD).toInt(),
        tier = tier,
        barAxis = axis,
        barDp = bar.toInt(),
        dockWidthDp = desktopPlayerRoundHalfEven(dockN, videoWidthD).toInt(),
    )
}
