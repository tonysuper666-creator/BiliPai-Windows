package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout

/** Explicit DESKTOP requests use the Leaf's retained viewport and existing foreground carrier.
 * The persisted default remains LEGACY; explicit requests opt into the mounted branch. */
internal const val DESKTOP_PLAYER_OVERLAY_AVAILABLE = true

/** Availability is independent of the stored request; an unavailable carrier still falls back safely. */
internal fun desktopPlayerEffectiveLayout(
    requested: DesktopWindowsUxPlayerLayout,
    overlayAvailable: Boolean,
): DesktopWindowsUxPlayerLayout =
    if (requested == DesktopWindowsUxPlayerLayout.DESKTOP && overlayAvailable) DesktopWindowsUxPlayerLayout.DESKTOP
    else DesktopWindowsUxPlayerLayout.LEGACY
