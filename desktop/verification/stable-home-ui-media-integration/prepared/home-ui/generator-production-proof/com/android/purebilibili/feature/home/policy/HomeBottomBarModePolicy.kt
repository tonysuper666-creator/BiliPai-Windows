// Original source app/src/main/java/com/android/purebilibili/feature/home/policy/HomeBottomBarModePolicy.kt
// LF SHA256 aa10cd816875f4a76a505df2fdc66d6b8d6ce0172da239365b4d3b939a98e11f
package com.android.purebilibili.feature.home.policy


internal fun resolveHomeBottomBarBaseVisibility(
    useSideNavigation: Boolean,
    mode: com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode
): Boolean? {
    if (useSideNavigation) return false
    return when (mode) {
        com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.SCROLL_HIDE -> null
        com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.ALWAYS_VISIBLE -> true
        com.bilipai.desktop.ui.DesktopFavoriteNavigationTypes.BottomBarVisibilityMode.ALWAYS_HIDDEN -> false
    }
}
