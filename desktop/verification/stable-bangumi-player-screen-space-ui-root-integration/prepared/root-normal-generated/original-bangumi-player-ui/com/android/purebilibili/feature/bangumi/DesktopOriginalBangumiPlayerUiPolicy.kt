package com.android.purebilibili.feature.bangumi

internal fun resolveBangumiPlayerTopControlsPaddingTopDp(
    isFullscreen: Boolean,
    statusBarsInsetDp: Float
): Float {
    return 8f
}

internal fun resolveBangumiDanmakuTopInsetDp(
    isFullscreen: Boolean,
    statusBarsInsetDp: Float
): Float {
    return if (isFullscreen) 0f else 52f
}

internal fun resolveBangumiPortraitPlayerContainerTopPaddingDp(
    statusBarsInsetDp: Float
): Float {
    return statusBarsInsetDp.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
}

internal fun resolveBangumiToggleOrientationTarget(
    isFullscreen: Boolean,
    isTablet: Boolean,
    usesInWindowFullscreen: Boolean,
): Int? {
    if (isTablet || usesInWindowFullscreen) return null
    return if (isFullscreen) {
        1 /* Original portrait intent; Windows adapter maps presentation */
    } else {
        6 /* Original sensor-landscape intent; no physical rotation fabricated */
    }
}

