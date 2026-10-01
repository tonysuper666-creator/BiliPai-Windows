// Original source app/src/main/java/com/android/purebilibili/core/util/WindowSizeUtils.kt
// LF SHA256 cda5b8ae2ba18958739f2d51648683ed33150cec4c1db70b5c232005bd140c62
package com.android.purebilibili.core.util

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.*
import kotlin.math.min

enum class WindowHeightSizeClass {
    /** 紧凑高度 (< 480dp) */
    Compact,
    /** 中等高度 (480dp - 900dp) */
    Medium,
    /** 展开高度 (> 900dp) */
    Expanded
}
internal fun resolveWindowHeightSizeClass(heightDp: Dp): WindowHeightSizeClass {
    return when {
        heightDp < 480.dp -> WindowHeightSizeClass.Compact
        heightDp < 900.dp -> WindowHeightSizeClass.Medium
        else -> WindowHeightSizeClass.Expanded
    }
}
data class WindowSizeClass(
    val widthSizeClass: WindowWidthSizeClass,
    val heightSizeClass: WindowHeightSizeClass,
    val widthDp: Dp,
    val heightDp: Dp,
    val deviceWidthSizeClass: WindowWidthSizeClass = widthSizeClass
) {
    /**
     * A foldable cover display can be narrow even though the maximum (inner) display is a
     * tablet-sized window.  Orientation handling must follow the currently visible display.
     */
    val isFoldableCoverScreen: Boolean
        get() = isTabletDevice && min(widthDp.value, heightDp.value) < 600f

    /** 是否为当前窗口意义上的平板宽度布局 */
    val isTablet: Boolean
        get() = widthSizeClass != WindowWidthSizeClass.Compact

    /** 是否为稳定意义上的手机宽度设备 */
    val isCompactDevice: Boolean
        get() = deviceWidthSizeClass == WindowWidthSizeClass.Compact

    /** 是否为稳定意义上的平板宽度设备 */
    val isTabletDevice: Boolean
        get() = !isCompactDevice
    
    /** 是否为大屏设备（平板横屏） */
    val isExpandedScreen: Boolean
        get() = widthSizeClass >= WindowWidthSizeClass.Expanded

    val isExtraLargeScreen: Boolean
        get() = widthSizeClass == WindowWidthSizeClass.ExtraLarge
    
    /** 是否应该使用分栏布局 */
    val shouldUseSplitLayout: Boolean
        get() = isTablet && heightSizeClass != WindowHeightSizeClass.Compact
    
    /** 是否应该使用侧边导航栏（仅大屏） */
    val shouldUseSideNavigation: Boolean
        get() = widthSizeClass >= WindowWidthSizeClass.Expanded

    val shouldUseExpandedNavigationRail: Boolean
        get() = widthSizeClass >= WindowWidthSizeClass.Large

    val shouldUseThreePaneLayout: Boolean
        get() = isExtraLargeScreen && heightSizeClass != WindowHeightSizeClass.Compact
}

enum class AppFoldPosture {
    None,
    Flat,
    Book,
    Tabletop,
}

enum class AppHingeOrientation {
    None,
    Vertical,
    Horizontal,
}

data class AppFoldingFeatureInfo(
    val posture: AppFoldPosture = AppFoldPosture.None,
    val hingeOrientation: AppHingeOrientation = AppHingeOrientation.None,
    val hingeBounds: IntRect? = null,
    val isSeparating: Boolean = false,
    val isOccluding: Boolean = false,
    val hinges: List<AppHingeFeature> = emptyList(),
) {
    val hasObstructingHinge: Boolean
        get() = isSeparating || isOccluding ||
            hinges.any { hinge -> hinge.isSeparating || hinge.isOccluding }
}

data class AppWindowAdaptiveInfo(
    val windowSizeClass: WindowSizeClass,
    val foldingFeature: AppFoldingFeatureInfo = AppFoldingFeatureInfo(),
    val displayContext: AppDisplayContext = AppDisplayContext(
        currentWindowWidthDp = windowSizeClass.widthDp.value.toInt(),
        currentWindowHeightDp = windowSizeClass.heightDp.value.toInt(),
    ),
    val precisePointerConnected: Boolean = false,
    val hardwareKeyboardConnected: Boolean = false,
) {
    val posture: AppFoldPosture
        get() = foldingFeature.posture

    val shouldAvoidHinge: Boolean
        get() = foldingFeature.hasObstructingHinge &&
            windowSizeClass.heightSizeClass != WindowHeightSizeClass.Compact
}

/**
 * 📦 CompositionLocal 提供全局 WindowSizeClass 访问
 */
private val DefaultWindowSizeClass = WindowSizeClass(
    widthSizeClass = WindowWidthSizeClass.Compact,
    heightSizeClass = WindowHeightSizeClass.Medium,
    widthDp = 360.dp,
    heightDp = 800.dp,
)

val LocalWindowSizeClass = compositionLocalOf { DefaultWindowSizeClass }

val LocalAppWindowAdaptiveInfo = compositionLocalOf {
    AppWindowAdaptiveInfo(windowSizeClass = DefaultWindowSizeClass)
}
