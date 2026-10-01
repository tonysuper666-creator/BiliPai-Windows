package com.android.purebilibili.feature.home.components
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.ui.*
internal fun resolveHomeTopSearchPillHeight(
    chromePolicy: AppTopChromePolicy,
): Dp = chromePolicy.compactChromeSpec.primaryHeightDp.dp

internal fun resolveHomeTopSearchRowHorizontalPadding(
    chromePolicy: AppTopChromePolicy,
): Dp {
    return resolveHomeTopPresetStyle(chromePolicy, labelMode = 2).searchRowHorizontalPadding
}

internal fun resolveHomeTopTabRowHeight(
    isTabFloating: Boolean,
    chromePolicy: AppTopChromePolicy,
    labelMode: Int = 2
): Dp {
    if (isTabFloating) return FloatingBottomBarDefaultShellHeight
    val style = resolveHomeTopPresetStyle(chromePolicy, labelMode)
    return style.tabRowHeightDocked
}
