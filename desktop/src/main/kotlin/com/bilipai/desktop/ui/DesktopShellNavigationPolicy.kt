package com.bilipai.desktop.ui

import com.android.purebilibili.core.util.AppFoldPosture
import com.android.purebilibili.core.util.WindowSizeClass
import com.android.purebilibili.core.util.shouldEnableHomeDrawer
import com.android.purebilibili.core.util.shouldUseExpandedNavigationRailForLayout
import com.android.purebilibili.core.util.shouldUseSidebarNavigationForLayout

/** Where the Windows shell paints navigation. Home's profile drawer stays inside HomeScreen. */
internal enum class DesktopShellNavigationPlacement {
    SIDEBAR,
    BOTTOM_DOCK,
    HIDDEN,
}

internal fun resolveDesktopShellNavigationPlacement(
    videoDestination: Boolean,
    windowSizeClass: WindowSizeClass,
    tabletUseSidebar: Boolean,
    foldPosture: AppFoldPosture,
): DesktopShellNavigationPlacement {
    if (videoDestination) return DesktopShellNavigationPlacement.HIDDEN
    val useSideNavigation = shouldUseSidebarNavigationForLayout(windowSizeClass, tabletUseSidebar, foldPosture)
    return if (useSideNavigation) {
        DesktopShellNavigationPlacement.SIDEBAR
    } else if (shouldEnableHomeDrawer(useSideNavigation)) {
        DesktopShellNavigationPlacement.BOTTOM_DOCK
    } else {
        DesktopShellNavigationPlacement.HIDDEN
    }
}

internal fun resolveDesktopShellSidebarExpanded(
    sidebarExpanded: Boolean,
    windowSizeClass: WindowSizeClass,
    foldPosture: AppFoldPosture,
): Boolean = sidebarExpanded && shouldUseExpandedNavigationRailForLayout(windowSizeClass, foldPosture)
