package com.android.purebilibili.core.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.feature.home.components.BottomBarMatchedReusableLiquidDock
import com.android.purebilibili.feature.home.components.resolveFloatingDockGeometryScale
import top.yukonga.miuix.kmp.blur.Backdrop

/** Native search input with an optional liquid glass surface behind it. */
@Composable
fun AppLiquidAwareSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    onSearch: () -> Unit = {},
    onClear: () -> Unit = { onQueryChange("") },
    autoFocusEnabled: Boolean = false,
    focusRequester: FocusRequester? = null,
    interactionSource: MutableInteractionSource? = null,
    backdrop: Backdrop? = null,
    liquidContentContainerColor: Color = Color.Transparent,
    isScrollInProgressProvider: () -> Boolean = { false },
    leadingIconHorizontalOffset: Dp = 0.dp,
) {
    BottomBarMatchedReusableLiquidDock(
        shape = CircleShape,
        modifier = modifier,
        backdrop = backdrop,
        reuseEnabled = true,
        useNeutralLiquidContainer = true,
        drawShellLens = true,
        shellLensIntensity = resolveFloatingDockGeometryScale(
            AppChromeSizeTokens.BottomBarMatchedSegmentedControlHeightDp.toFloat()
        ),
        isScrollInProgressProvider = isScrollInProgressProvider,
    ) { liquidChromeActive ->
        AppSearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder,
            onSearch = onSearch,
            onClear = onClear,
            autoFocusEnabled = autoFocusEnabled,
            focusRequester = focusRequester,
            interactionSource = interactionSource,
            leadingIconHorizontalOffset = leadingIconHorizontalOffset,
            containerColor = if (liquidChromeActive) {
                liquidContentContainerColor
            } else {
                Color.Unspecified
            },
        )
    }
}
