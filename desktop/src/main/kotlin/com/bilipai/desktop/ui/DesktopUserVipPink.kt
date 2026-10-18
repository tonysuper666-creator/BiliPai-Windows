package com.bilipai.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The pinned design-system UserVipBadge predates upstream v0.4.6's shared VIP pink. These are the
 * two values and the dark-surface rule of upstream resolveUserVipPink/userVipPink
 * (design-system UserVipBadge.kt at d6bf8b1655ae910999860bf13e65c8fcea5b345b); the colour is
 * independent of the accent. Remove this file when UserVipBadge.kt itself is advanced.
 */
internal fun resolveDesktopUserVipPink(isDark: Boolean): Color =
    if (isDark) Color(0xFFD44E7D) else Color(0xFFFF6699)

@Composable
internal fun desktopUserVipPink(): Color =
    resolveDesktopUserVipPink(MaterialTheme.colorScheme.surface.luminance() < 0.5f)
