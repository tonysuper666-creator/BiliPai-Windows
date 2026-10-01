package com.android.purebilibili.core.ui.adaptive
import com.android.purebilibili.core.util.*
data class DeviceUiProfile(
    val widthSizeClass: WindowWidthSizeClass,
    val isTablet: Boolean,
    val motionTier: MotionTier,
    val foldPosture: AdaptiveFoldPosture = AdaptiveFoldPosture.None,
)

fun resolveDeviceUiProfile(
    widthSizeClass: WindowWidthSizeClass,
    foldPosture: AppFoldPosture = AppFoldPosture.None,
): DeviceUiProfile {
    val spec = resolveDeviceUiProfileSpec(
        widthClass = widthSizeClass.toAdaptiveWidthClass(),
        foldPosture = foldPosture.toAdaptiveFoldPosture(),
    )
    return DeviceUiProfile(
        widthSizeClass = widthSizeClass,
        isTablet = spec.isTablet,
        motionTier = spec.motionTier,
        foldPosture = spec.foldPosture,
    )
}

internal fun AppFoldPosture.toAdaptiveFoldPosture(): AdaptiveFoldPosture = when (this) {
    AppFoldPosture.None -> AdaptiveFoldPosture.None
    AppFoldPosture.Flat -> AdaptiveFoldPosture.Flat
    AppFoldPosture.Book -> AdaptiveFoldPosture.Book
    AppFoldPosture.Tabletop -> AdaptiveFoldPosture.Tabletop
}
