// Original source app/src/main/java/com/android/purebilibili/core/util/NavigationUiPolicy.kt
// LF SHA256 e13e473aedf4d6846f60aa4ef184773242bb9989d5a5427ba2600f298fdc3a18
package com.android.purebilibili.core.util



internal fun shouldUseSidebarNavigationForLayout(
    windowSizeClass: WindowSizeClass,
    tabletUseSidebar: Boolean,
    foldPosture: AppFoldPosture = AppFoldPosture.None
): Boolean {
    // Tabletop posture (horizontal hinge across the vertical rail) disables the side rail.
    // Book posture has a vertical hinge in the screen center; the side rail stays safely
    // on the far-left edge in the natural thumb zone away from the crease.
    if (foldPosture == AppFoldPosture.Tabletop) {
        return false
    }
    return tabletUseSidebar && windowSizeClass.shouldUseSideNavigation
}

/**
 * 首页侧边抽屉仅在底栏导航模式下启用，避免与平板侧栏模式叠层冲突。
 */

internal fun shouldEnableHomeDrawer(useSideNavigation: Boolean): Boolean {
    return !useSideNavigation
}

/**
 * 是否使用展开式 NavigationRail（仅 Large/ExtraLarge 且无折叠姿态）。
 */

internal fun shouldUseExpandedNavigationRailForLayout(
    windowSizeClass: WindowSizeClass,
    foldPosture: AppFoldPosture = AppFoldPosture.None
): Boolean {
    if (foldPosture == AppFoldPosture.Book || foldPosture == AppFoldPosture.Tabletop) {
        return false
    }
    return windowSizeClass.shouldUseExpandedNavigationRail
}
