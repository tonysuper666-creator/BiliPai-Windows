package com.android.purebilibili.core.ui
import androidx.compose.runtime.*
import androidx.compose.ui.unit.*
val LocalBottomBarContentPadding = compositionLocalOf<Dp> { 0.dp }

/**
 * 全局“预测性返回手势”设置：关闭后仍可边缘返回，但不上报跟手进度、不显示预测预览。
 * 由 [com.android.purebilibili.navigation.AppNavigation] 从用户偏好提供。
 */

val LocalBottomBarVisible = compositionLocalOf<Boolean> { true }

/** Final bottom content padding resolved by the app shell for top-level pages. */

val LocalSetBottomBarVisible = compositionLocalOf<(Boolean) -> Unit> { 
    error("No SetBottomBarVisible provided") 
}

/**
 * 用于获取当前全局底栏可见性的 CompositionLocal (可选)
 */
