package com.android.purebilibili.core.util

import com.bilipai.desktop.ui.DesktopOriginalVideoOrientationRequest as ActivityInfo

internal data class PlayerWindowOrientationPolicy(
    val currentWindowWidthDp: Int,
    val currentWindowHeightDp: Int,
    val maximumWindowWidthDp: Int?,
    val maximumWindowHeightDp: Int?,
    val displayModeWidthPx: Int?,
    val displayModeHeightPx: Int?,
    val displayRotation: Int?,
    val isFoldableCoverWindow: Boolean,
    val isLandscapeNaturalDisplay: Boolean,
) {
    val usesInWindowFullscreen: Boolean
        get() = isFoldableCoverWindow && isLandscapeNaturalDisplay
}

internal fun AppDisplayContext.toPlayerWindowOrientationPolicy(): PlayerWindowOrientationPolicy {
    return PlayerWindowOrientationPolicy(
        currentWindowWidthDp = currentWindowWidthDp,
        currentWindowHeightDp = currentWindowHeightDp,
        maximumWindowWidthDp = maximumWindowWidthDp,
        maximumWindowHeightDp = maximumWindowHeightDp,
        displayModeWidthPx = displayModeWidthPx,
        displayModeHeightPx = displayModeHeightPx,
        displayRotation = displayRotation,
        isFoldableCoverWindow = isFoldableCoverWindow,
        isLandscapeNaturalDisplay =
            naturalOrientation == AppDisplayNaturalOrientation.Landscape,
    )
}

internal fun resolvePlayerWindowOrientationPolicy(
    displayContext: AppDisplayContext,
): PlayerWindowOrientationPolicy = displayContext.toPlayerWindowOrientationPolicy()

private fun isPlayerAxisOrientationRequest(requestedOrientation: Int): Boolean {
    return requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED &&
        requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LOCKED &&
        requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_BEHIND
}

internal fun resolveEffectivePlayerRequestedOrientation(
    requestedOrientation: Int,
    usesInWindowFullscreen: Boolean,
): Int {
    return if (usesInWindowFullscreen && isPlayerAxisOrientationRequest(requestedOrientation)) {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    } else {
        requestedOrientation
    }
}

/**
 * Android 16+ ignores orientation restrictions for target-36+ apps on large screens,
 * and target 37 removes the manifest opt-out. Older Android releases still honor
 * requestedOrientation on tablets, so do not discard a user's fullscreen request there.
 */
