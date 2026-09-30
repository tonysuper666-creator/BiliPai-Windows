package com.bilipai.desktop.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

data class DesktopWindowBounds(val screenHeightDp: Int)

/** Read the enclosing Compose window, including live resizes and the host's density. */
object DesktopWindowConfiguration {
    val current: DesktopWindowBounds
        @Composable @ReadOnlyComposable get() {
            val pixels = LocalWindowInfo.current.containerSize.height
            return DesktopWindowBounds((pixels / LocalDensity.current.density).toInt())
        }
}
