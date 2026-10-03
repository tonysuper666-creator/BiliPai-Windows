package com.bilipai.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Actual page/window capability. Root supplies native GetSystemPowerStatus, not Android context. */
internal interface DesktopOriginalPlayerControlsPlatform {
    fun readBatteryPercent(): Int?
    fun readAmbientEnvironment(): com.android.purebilibili.feature.video.ambient.AmbientEnvironment
}
internal val LocalDesktopOriginalPlayerControlsPlatform = staticCompositionLocalOf<DesktopOriginalPlayerControlsPlatform> {
    error("Full original player controls require the actual Root Windows capability")
}

/** Original Media3 persisted resize integer identities; these are not an emulated player/view. */
internal object DesktopOriginalMediaResizeModes {
    const val RESIZE_MODE_FIT = 0
    const val RESIZE_MODE_FIXED_WIDTH = 1
    const val RESIZE_MODE_FIXED_HEIGHT = 2
    const val RESIZE_MODE_FILL = 3
    const val RESIZE_MODE_ZOOM = 4
}
