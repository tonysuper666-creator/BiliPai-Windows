// Source: app/src/main/java/com/android/purebilibili/navigation/AppTopLevelNavigationPolicy.kt
// Stable target: 3d5d19a2f994daccd0e2f8b5f522b6d82f43d589
package com.android.purebilibili.navigation

internal fun resolveVideoCardSourceRouteForNavigation(
    currentRoute: String?,
    videoBvid: String,
    lastClickedVideoSourceKey: String?,
    visibleBottomBarRoutes: Set<String>
): String? {
    if (videoBvid.isBlank() || lastClickedVideoSourceKey.isNullOrBlank()) return null
    val routeBase = normalizeVideoCardNavigationSourceRoute(currentRoute)
    val currentRouteMatch = routeBase
        ?.takeIf { route -> lastClickedVideoSourceKey == "$route:$videoBvid" }
    if (currentRouteMatch != null) return currentRouteMatch

    // Bottom-bar tabs first (MainHost top is not the card host).
    visibleBottomBarRoutes.firstOrNull { route ->
        lastClickedVideoSourceKey == "$route:$videoBvid"
    }?.let { return it }

    // Search / Space / History / video-related / collection hosts are not bottom-bar routes.
    // Still honor the recorded card key so predictive-back sharedBounds land on the same route.
    return resolveClickedVideoSourceRoute(lastClickedVideoSourceKey, videoBvid)
}

private fun normalizeVideoCardNavigationSourceRoute(route: String?): String? {
    val normalized = route?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return if (normalized.startsWith("home?category=")) {
        ScreenRoutes.Home.route
    } else {
        normalized.substringBefore("?")
    }
}

private fun resolveClickedVideoSourceRoute(
    lastClickedVideoSourceKey: String,
    videoBvid: String
): String? {
    val expectedSuffix = ":$videoBvid"
    return lastClickedVideoSourceKey
        .takeIf { it.endsWith(expectedSuffix) }
        ?.removeSuffix(expectedSuffix)
        ?.takeIf { it.isNotBlank() }
}
