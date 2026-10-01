package com.android.purebilibili.core.util
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
data class ResponsiveSpacing(
    val small: Dp,
    val medium: Dp,
    val large: Dp,
    val extraLarge: Dp = large * 1.5f
)

/**
 * 📏 获取响应式间距
 * 根据屏幕尺寸返回适当的间距值
 */

@Composable
fun rememberResponsiveSpacing(): ResponsiveSpacing {
    val windowSizeClass = LocalWindowSizeClass.current
    return remember(windowSizeClass.widthSizeClass) {
        when (windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> ResponsiveSpacing(
                small = 8.dp,
                medium = 12.dp,
                large = 16.dp
            )
            WindowWidthSizeClass.Medium -> ResponsiveSpacing(
                small = 12.dp,
                medium = 16.dp,
                large = 24.dp
            )
            WindowWidthSizeClass.Expanded -> ResponsiveSpacing(
                small = 16.dp,
                medium = 24.dp,
                large = 32.dp
            )
            WindowWidthSizeClass.Large -> ResponsiveSpacing(
                small = 20.dp,
                medium = 28.dp,
                large = 36.dp
            )
            WindowWidthSizeClass.ExtraLarge -> ResponsiveSpacing(
                small = 24.dp,
                medium = 32.dp,
                large = 40.dp
            )
        }
    }
}

/**
 * 🔤 响应式字体大小
 * 
 * @param compactSize 紧凑模式字体大小
 * @param mediumScale 中等模式缩放比例（相对于 compact）
 * @param expandedScale 展开模式缩放比例（相对于 compact）
 */

fun resolveSingleColumnFeedMaxWidth(): Dp = 840.dp

/**
 * 📐 响应式值选择器
 * 根据当前窗口尺寸选择合适的值
 *
 * @param compact 紧凑模式值（手机）
 * @param medium 中等模式值（平板竖屏），默认使用 compact 值
 * @param expanded 展开模式值（平板横屏），默认使用 medium 值
 */
