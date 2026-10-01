package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlurEffect
import dev.chrisbanes.haze.HazeState

/** Root must provide its actual displayability/background state; Android SDK is not a capability. */
internal val LocalDesktopDetailForeground = staticCompositionLocalOf<Boolean> {
    error("Dynamic detail foreground owner is not mounted")
}

internal fun desktopDetailRenderEffectsSupported(): Boolean =
    runCatching { BlurEffect(radiusX = 1f, radiusY = 1f).isSupported() }.getOrDefault(false)

/** Only UI graphics state; no account, store, list or transport is created. */
@Composable internal fun rememberDesktopDetailHazeState(): HazeState {
    val foreground = LocalDesktopDetailForeground.current
    return remember(foreground) { HazeState() }
}
