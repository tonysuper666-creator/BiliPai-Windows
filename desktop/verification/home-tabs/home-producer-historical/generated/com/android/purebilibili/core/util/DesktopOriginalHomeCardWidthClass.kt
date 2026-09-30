// GENERATED from app/src/main/java/com/android/purebilibili/core/util/WindowSizeUtils.kt; do not edit.
// LF-normalized SHA-256: cda5b8ae2ba18958739f2d51648683ed33150cec4c1db70b5c232005bd140c62
package com.android.purebilibili.core.util
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowWidthSizeClass {
    /** 手机竖屏 (< 600dp) */
    Compact,
    /** 平板竖屏/手机横屏 (600dp - 840dp) */
    Medium,
    /** 平板横屏/小型桌面窗口 (840dp - 1200dp) */
    Expanded,
    /** 大型平板/桌面窗口 (1200dp - 1600dp) */
    Large,
    /** 超宽桌面窗口 (>= 1600dp) */
    ExtraLarge,
}

/**
 * 🖥️ 窗口高度尺寸类型
 */

internal fun resolveWindowWidthSizeClass(widthDp: Dp): WindowWidthSizeClass {
    return when {
        widthDp < 600.dp -> WindowWidthSizeClass.Compact
        widthDp < 840.dp -> WindowWidthSizeClass.Medium
        widthDp < 1200.dp -> WindowWidthSizeClass.Expanded
        widthDp < 1600.dp -> WindowWidthSizeClass.Large
        else -> WindowWidthSizeClass.ExtraLarge
    }
}
