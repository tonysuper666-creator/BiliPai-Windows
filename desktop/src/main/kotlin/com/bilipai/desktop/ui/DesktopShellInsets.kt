package com.bilipai.desktop.ui

import androidx.compose.runtime.compositionLocalOf

/*
 * What the shell takes away from the content area (spec 3.1): the width of the navigation rail, margins included, in dp
 * after zoom. DesktopWindowsShellFrame provides it around the one content slot, and the Home feed reads it to measure
 * the width the rail leaves (spec 3.6, Wc = W minus this). The default is zero, so a screen or a fixture that is not
 * inside the frame sees the whole window.
 *
 * It is a dynamic local, not a static one: when the rail toggles only the readers of the value recompose, not the whole
 * content subtree, which a static local would invalidate.
 */
internal data class DesktopShellInsets(val leadingDp: Int = 0)

internal val LocalDesktopShellInsets = compositionLocalOf { DesktopShellInsets() }
