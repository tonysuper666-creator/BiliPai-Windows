// Original source app/src/main/java/com/android/purebilibili/navigation/AppNavigationPlaybackPolicy.kt
// LF SHA256 e0165d4a922cdd613fcda87100f8344f572ba8e5afd22ce6c1c603eba8fec758
package com.android.purebilibili.navigation

internal fun shouldDeferBottomBarRevealOnVideoReturn(
    isReturningFromDetail: Boolean,
    activeBottomTabRoute: String?,
    cardTransitionEnabled: Boolean
): Boolean {
    return false
}

internal fun shouldDelayBottomBarRevealAfterVideoReturn(
    isReturningFromDetail: Boolean,
    isBottomBarDestination: Boolean,
    cardTransitionEnabled: Boolean
): Boolean {
    return isReturningFromDetail &&
        isBottomBarDestination &&
        cardTransitionEnabled
}

internal fun resolveVideoReturnBottomBarRevealDelayMs(
    cardTransitionEnabled: Boolean,
    isQuickReturnFromDetail: Boolean
): Long {
    if (!cardTransitionEnabled) return 0L
    return if (isQuickReturnFromDetail) 120L else 160L
}
