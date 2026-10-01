// Original source design-system/src/main/java/com/android/purebilibili/core/ui/TopReadabilityChromePolicy.kt
// LF SHA256 21687f91a4d37f999584d0390f40c19a8e321a98700f7a5ddf727a80a9ce655d
package com.android.purebilibili.core.ui

import androidx.compose.ui.graphics.Color

data class TopReadabilityChromeSpec(
    val heightDp: Int,
    val surfaceColor: Color,
    val surfaceAlpha: Float,
    val bottomAlpha: Float,
    val drawGradient: Boolean,
    val useHaze: Boolean,
)

/** The renderer that is actually safe to expose for a top chrome surface. */
enum class TopChromeRenderMode {
    SOLID,
    HAZE,
    PROGRESSIVE,
}

/**
 * Resolve the effective top chrome renderer from requested preferences and runtime readiness.
 *
 * Header haze has precedence over the progressive renderer because the two settings are persisted
 * as mutually exclusive choices. A missing/unsupported source always resolves to SOLID so callers
 * never make a surface transparent merely because an effect was requested.
 */
fun resolveTopChromeRenderMode(
    headerBlurRequested: Boolean,
    progressiveBlurRequested: Boolean,
    hazeAvailable: Boolean,
    progressiveAvailable: Boolean,
): TopChromeRenderMode = when {
    headerBlurRequested && hazeAvailable -> TopChromeRenderMode.HAZE
    !headerBlurRequested && progressiveBlurRequested && progressiveAvailable ->
        TopChromeRenderMode.PROGRESSIVE
    else -> TopChromeRenderMode.SOLID
}

fun resolveTopReadabilityChromeSpec(
    requestedHeightDp: Int,
    surfaceColor: Color,
    surfaceAlpha: Float,
    hazeRequested: Boolean,
    hasHazeState: Boolean,
    drawGradient: Boolean = true,
): TopReadabilityChromeSpec {
    val height = requestedHeightDp.coerceAtLeast(0)
    val alpha = surfaceAlpha.coerceIn(0f, 1f)
    return TopReadabilityChromeSpec(
        heightDp = height,
        surfaceColor = surfaceColor,
        surfaceAlpha = alpha,
        bottomAlpha = 0f,
        drawGradient = drawGradient && height > 0,
        useHaze = hazeRequested && hasHazeState && height > 0,
    )
}
