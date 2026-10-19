package com.bilipai.desktop.ui

import com.bilipai.desktop.appearance.DesktopWindowsUxPlayerLayout

/*
 * Which player layout the ordinary-video leaf shows (spec 4.13, slice V2). No Compose, AWT or Windows dependency.
 *
 * The player_layout flag of the windows_ux store says what the user asked for. DESKTOP is shown only while the carrier
 * overlay exists to show it. Until then the leaf falls back to the LEGACY layout (the control bar below the video)
 * whatever the flag says, so a stored DESKTOP key cannot switch an unfinished overlay on. The slice that mounts the
 * overlay (V4a) flips DESKTOP_PLAYER_OVERLAY_AVAILABLE in the same commit, and the settings row of the flag
 * (DesktopWindowsUxSettings, PLAYER_LAYOUT_HAS_CONSUMER) turns on with it. The default of the flag stays LEGACY until
 * the flip gate of the spec (section 9.4). Mirrored by desktop/tools/ux/ux_leaf_model.py and checked against the
 * contract by DesktopPlayerLayoutSelectionTest and test_ux_leaf_layout_binding.py.
 */

/** False in slice V2: the leaf has no carrier overlay, so every request resolves to LEGACY. */
internal const val DESKTOP_PLAYER_OVERLAY_AVAILABLE = false

/** The layout to show for a requested one: DESKTOP only when it was requested and an overlay exists, LEGACY in every other case. */
internal fun desktopPlayerEffectiveLayout(
    requested: DesktopWindowsUxPlayerLayout,
    overlayAvailable: Boolean,
): DesktopWindowsUxPlayerLayout =
    if (requested == DesktopWindowsUxPlayerLayout.DESKTOP && overlayAvailable) DesktopWindowsUxPlayerLayout.DESKTOP
    else DesktopWindowsUxPlayerLayout.LEGACY
