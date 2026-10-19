package com.bilipai.desktop.ui

import kotlin.math.abs

/*
 * Pure seek rail model of the desktop player (spec 4.6). No Compose, AWT or Windows dependency: the rail composable (slice
 * V4b) draws what these functions say. Chapters are gaps in the rail, not labels; dragging the pointer away from the rail
 * vertically slows the scrub (fine scrub). Mirrors desktop-ux-contract.json ("pointer"); test_ux_player_policy.py and
 * DesktopPlayerSeekModelTest check the agreement.
 */

/** The width of the gap that marks a chapter start on the rail, in dp. */
internal const val DESKTOP_PLAYER_CHAPTER_GAP_DP = 2.0

/** The knots (distance in dp, factor) of the fine scrub factor: linear between them, flat outside. */
internal val DESKTOP_PLAYER_FINE_SCRUB_KNOTS: List<Pair<Double, Double>> =
    listOf(48.0 to 1.0, 96.0 to 0.5, 192.0 to 0.25, 288.0 to 0.1)

/**
 * The end of the buffered range as a fraction of the rail (the playhead plus the forward cache), or null when unknown: a null
 * or non-finite cache figure, a non-finite position or no duration. Null draws nothing. A negative figure counts as no cache.
 */
internal fun desktopPlayerBufferedFraction(bufferedForwardSeconds: Double?, positionSeconds: Double, durationSeconds: Double): Double? {
    if (bufferedForwardSeconds == null || !bufferedForwardSeconds.isFinite() || !positionSeconds.isFinite()) return null
    if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return null
    return ((positionSeconds.coerceAtLeast(0.0) + bufferedForwardSeconds.coerceAtLeast(0.0)) / durationSeconds).coerceIn(0.0, 1.0)
}

/**
 * The scrub speed factor for a vertical distance from the rail: 1.0 within 48 dp, 0.5 at 96, 0.25 at 192, 0.1 from 288, linear
 * in between. It never rises with the distance and never falls below 0.1; an unknown distance counts as none.
 */
internal fun desktopPlayerFineScrubFactor(verticalDistanceDp: Double): Double {
    val distance = abs(verticalDistanceDp)
    val knots = DESKTOP_PLAYER_FINE_SCRUB_KNOTS
    if (distance.isNaN() || distance <= knots.first().first) return knots.first().second
    for (index in 1 until knots.size) {
        val (x1, y1) = knots[index]
        if (distance <= x1) {
            val (x0, y0) = knots[index - 1]
            val t = (distance - x0) / (x1 - x0)
            return if (t >= 1.0) y1 else (y0 + (y1 - y0) * t).coerceIn(knots.last().second, knots.first().second)
        }
    }
    return knots.last().second
}

/**
 * The chapter gaps as fractions of the rail: the distinct starts strictly inside the video, ascending. A start closer than one
 * gap width to the previous gap is dropped, so two gaps never overlap.
 */
internal fun desktopPlayerChapterGapFractions(
    startsMs: List<Long>,
    durationMs: Long,
    railWidthDp: Double,
    gapDp: Double = DESKTOP_PLAYER_CHAPTER_GAP_DP,
): List<Double> {
    if (durationMs <= 0L || !railWidthDp.isFinite() || railWidthDp <= 0.0) return emptyList()
    val gaps = ArrayList<Double>()
    for (start in startsMs.filter { it > 0L && it < durationMs }.distinct().sorted()) {
        val fraction = start.toDouble() / durationMs
        if (gaps.isEmpty() || (fraction - gaps.last()) * railWidthDp >= gapDp) gaps.add(fraction)
    }
    return gaps
}
