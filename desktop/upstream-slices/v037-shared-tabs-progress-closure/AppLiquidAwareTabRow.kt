package com.android.purebilibili.core.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.ui.components.AppTabRowIndicatorPresentation
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.feature.home.components.resolveCompactDockScaleOverflowDp
import com.android.purebilibili.feature.home.components.BottomBarLiquidSegmentedControl
import top.yukonga.miuix.kmp.blur.Backdrop

private val beta21AdaptiveTabMinWidth = 72.dp

internal fun resolveAppAdaptiveTabMinWidth(
    requestedMinTabWidth: Dp,
    uiStyle: AppUiStyle,
    liquidGlassEnabled: Boolean,
): Dp {
    if (requestedMinTabWidth.isSpecified) return requestedMinTabWidth
    return if (uiStyle == AppUiStyle.MIUIX && !liquidGlassEnabled) {
        AppChromeSizeTokens.MinimumTouchTarget
    } else {
        beta21AdaptiveTabMinWidth
    }
}

/** Required intent for MD3 tabs when glass is disabled; never inferred from row order. */
enum class AppTabRowRole {
    PRIMARY,
    SECONDARY,
    FILTER,
}

/**
 * App-wide tabs: primary navigation and filters use tonal pills, secondary
 * categories use underlines. Glass and Miuix retain their own renderers.
 */
@Composable
fun <T> AppThemeAdaptiveTabRow(
    options: List<AppSegmentOption<T>>,
    selectedValue: T,
    onSelectionChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scrollable: Boolean = false,
    minTabWidth: Dp = Dp.Unspecified,
    compactMiuixWhenTwoOptions: Boolean = true,
    height: Dp = AppChromeSizeTokens.BottomBarMatchedSegmentedControlHeightDp.dp,
    indicatorHeight: Dp = AppChromeSizeTokens.BottomBarMatchedSegmentedIndicatorHeightDp.dp,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    dragSelectionEnabled: Boolean? = null,
    tapPressRefractionEnabled: Boolean = true,
    miuixBackdrop: Backdrop? = null,
    preferInlineContentStyle: Boolean = false,
    centerContent: Boolean = true,
    role: AppTabRowRole,
    indicatorPositionProvider: (() -> Float)? = null,
    isScrollInProgressProvider: () -> Boolean = { false },
) {
    AppLiquidAwareTabRow(
        options = options,
        selectedValue = selectedValue,
        onSelectionChange = onSelectionChange,
        modifier = modifier,
        enabled = enabled,
        scrollable = scrollable,
        minTabWidth = minTabWidth,
        compactMiuixWhenTwoOptions = compactMiuixWhenTwoOptions,
        height = height,
        indicatorHeight = indicatorHeight,
        labelFontSize = labelFontSize,
        dragSelectionEnabled = dragSelectionEnabled,
        tapPressRefractionEnabled = tapPressRefractionEnabled,
        miuixBackdrop = miuixBackdrop,
        preferInlineContentStyle = preferInlineContentStyle,
        centerContent = centerContent,
        role = role,
        indicatorPositionProvider = indicatorPositionProvider,
        isScrollInProgressProvider = isScrollInProgressProvider,
    )
}

/**
 * App-level tab row that reuses the same liquid indicator and interaction contract as the
 * floating home dock. Scrollable rows keep horizontal scrolling and expose long-press drag
 * selection so drag-to-scroll and drag-to-select do not compete for the same gesture.
 */
@Composable
fun <T> AppLiquidAwareTabRow(
    options: List<AppSegmentOption<T>>,
    selectedValue: T,
    onSelectionChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scrollable: Boolean = false,
    minTabWidth: Dp = Dp.Unspecified,
    compactMiuixWhenTwoOptions: Boolean = true,
    height: Dp = AppChromeSizeTokens.BottomBarMatchedSegmentedControlHeightDp.dp,
    indicatorHeight: Dp = AppChromeSizeTokens.BottomBarMatchedSegmentedIndicatorHeightDp.dp,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    dragSelectionEnabled: Boolean? = null,
    tapPressRefractionEnabled: Boolean = true,
    miuixBackdrop: Backdrop? = null,
    preferInlineContentStyle: Boolean = false,
    centerContent: Boolean = true,
    role: AppTabRowRole,
    indicatorPositionProvider: (() -> Float)? = null,
    isScrollInProgressProvider: () -> Boolean = { false },
) {
    if (options.isEmpty()) return
    val uiStyle = LocalAppUiStyle.current
    val liquidGlassEnabled = com.android.purebilibili.core.ui.LocalAppThemeConfig.current.liquidGlassEnabled
    val resolvedMinTabWidth = resolveAppAdaptiveTabMinWidth(
        requestedMinTabWidth = minTabWidth,
        uiStyle = uiStyle,
        liquidGlassEnabled = liquidGlassEnabled,
    )
    if (!liquidGlassEnabled) {
        if (uiStyle == AppUiStyle.MIUIX && options.size <= 2 && compactMiuixWhenTwoOptions) {
            AppNativeSegmentedControl(
                options = options,
                selectedValue = selectedValue,
                onSelectionChange = onSelectionChange,
                modifier = modifier,
                enabled = enabled,
                indicatorPositionProvider = indicatorPositionProvider,
            )
        } else {
            AppNativeTabRow(
                options = options,
                selectedValue = selectedValue,
                onSelectionChange = onSelectionChange,
                modifier = modifier,
                enabled = enabled,
                scrollable = scrollable,
                minTabWidth = resolvedMinTabWidth,
                compactMiuixWhenTwoOptions = compactMiuixWhenTwoOptions,
                height = height,
                centerContent = centerContent,
                allowLabelOverflow = true,
                indicatorPresentation = when (role) {
                    AppTabRowRole.PRIMARY, AppTabRowRole.FILTER -> AppTabRowIndicatorPresentation.TONAL_PILL
                    AppTabRowRole.SECONDARY -> AppTabRowIndicatorPresentation.UNDERLINE
                },
                indicatorPositionProvider = indicatorPositionProvider,
            )
        }
        return
    }
    val selectedIndex = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)
    // All enabled liquid docks support direct dragging, including scrollable rails.
    val resolvedDragSelectionEnabled = dragSelectionEnabled ?: (enabled && options.size > 1)
    // Give every tab enough room for its longest label. The row itself remains
    // horizontally scrollable, so labels are never ellipsized or clipped on
    // narrow phones; this also applies to shared rows such as UP space tabs.
    // Keep liquid-glass sizing on its established width contract. Content-measured
    // widths belong to the MIUIX non-glass native rail only.
    val readableTabWidth = resolveLiquidGlassTabMinWidth(
        requestedMinWidth = resolvedMinTabWidth,
        labels = options.map { it.label },
        allowLabelOverflow = true,
    )
    val isCompact = (compactMiuixWhenTwoOptions && options.size <= 2) ||
        (minTabWidth.isSpecified && !scrollable)
    BoxWithConstraints(modifier = modifier, contentAlignment = if (centerContent) Alignment.Center else Alignment.CenterStart) {
        val contentWidth = readableTabWidth * options.size + AppSpacingTokens.ExtraSmall * 2
        // A wide label is not itself overflow. Fitting rails must stay outside
        // the rounded scroll viewport so a pressed glass lens can bloom freely.
        val needsHorizontalScroll = scrollable && !isCompact &&
            constraints.hasBoundedWidth && contentWidth > maxWidth
        if (needsHorizontalScroll) {
            val indicatorOverflow = resolveCompactDockScaleOverflowDp(
                shellHeightDp = height.value,
                indicatorHeightDp = indicatorHeight.value,
            ).dp
            val scrollState = rememberScrollState()
            val density = LocalDensity.current
            BoxWithConstraints(
                modifier = Modifier.liquidDockViewport(verticalOverflow = indicatorOverflow),
                contentAlignment = Alignment.CenterStart,
            ) {
                val viewportWidthPx = with(density) { maxWidth.toPx() }
                val itemWidthPx = with(density) { readableTabWidth.toPx() }
                val dragFollowEdgePaddingPx = with(density) { AppSpacingTokens.Medium.toPx() }
                val pagerPositionProvider = indicatorPositionProvider
                val pagerMotionActiveProvider = isScrollInProgressProvider
                KeepScrollableTabSelectionVisible(
                    scrollState = scrollState,
                    selectedIndex = selectedIndex,
                    itemWidthPx = itemWidthPx,
                    contentPaddingPx = with(density) { AppSpacingTokens.ExtraSmall.toPx() },
                    focusPosition = {
                        pagerPositionProvider?.invoke() ?: selectedIndex.toFloat()
                    },
                    continuousFollow = {
                        pagerPositionProvider != null && pagerMotionActiveProvider()
                    },
                )
                BottomBarLiquidSegmentedControl(
                    items = options.map { it.label },
                    selectedIndex = selectedIndex,
                    onSelected = { index ->
                        options.getOrNull(index)?.let { onSelectionChange(it.value) }
                    },
                    modifier = Modifier.liquidDockViewport(verticalOverflow = indicatorOverflow),
                    scrollState = scrollState,
                    enabled = enabled,
                    itemWidth = readableTabWidth,
                    height = height,
                    indicatorHeight = indicatorHeight,
                    labelFontSize = labelFontSize,
                    liquidGlassEffectsEnabled = true,
                    dragSelectionEnabled = resolvedDragSelectionEnabled,
                    tapPressRefractionEnabled = tapPressRefractionEnabled,
                    miuixBackdrop = miuixBackdrop,
                    preferInlineContentStyle = preferInlineContentStyle,
                    indicatorPositionProvider = indicatorPositionProvider,
                    onIndicatorPositionChanged = { position ->
                        // During pager motion the shared scroll helper lock-steps the rail
                        // with the continuous indicator position. Keep edge-follow for idle
                        // indicator nudges so two scroll owners never compete.
                        if (pagerPositionProvider == null || !pagerMotionActiveProvider()) {
                            scrollState.dispatchRawDelta(
                                resolveScrollableTabIndicatorFollowDeltaPx(
                                    indicatorPosition = position,
                                    itemWidthPx = itemWidthPx,
                                    viewportWidthPx = viewportWidthPx,
                                    currentScrollPx = scrollState.value.toFloat(),
                                    contentPaddingPx = with(density) {
                                        AppSpacingTokens.ExtraSmall.toPx()
                                    },
                                    edgePaddingPx = dragFollowEdgePaddingPx,
                                )
                            )
                        }
                    },
                    isScrollInProgressProvider = isScrollInProgressProvider,
                    externalPagerMotionEffectsEnabled = indicatorPositionProvider != null,
                )
            }
        } else {
            val rowModifier = if (isCompact || scrollable) {
                // A scrollable rail keeps its leading edge even when its contents fit.
                // Centering it in a weighted slot shifts detail tabs away from the page edge.
                // Standalone rails center; callers with adjacent actions retain the start anchor.
                Modifier
                    .align(if (isCompact || centerContent) Alignment.Center else Alignment.CenterStart)
                    .wrapContentWidth(Alignment.CenterHorizontally)
            } else {
                Modifier
            }
            // A short scrollable rail still owns fixed glass slots, even when it
            // fits without scrolling. Do not redistribute them across the viewport.
            val rowItemWidth = if (isCompact || scrollable) {
                readableTabWidth
            } else {
                null
            }
            BottomBarLiquidSegmentedControl(
                items = options.map { it.label },
                selectedIndex = selectedIndex,
                onSelected = { index ->
                    options.getOrNull(index)?.let { onSelectionChange(it.value) }
                },
                modifier = rowModifier,
                enabled = enabled,
                itemWidth = rowItemWidth,
                height = height,
                indicatorHeight = indicatorHeight,
                labelFontSize = labelFontSize,
                liquidGlassEffectsEnabled = true,
                dragSelectionEnabled = resolvedDragSelectionEnabled,
                tapPressRefractionEnabled = tapPressRefractionEnabled,
                miuixBackdrop = miuixBackdrop,
                preferInlineContentStyle = preferInlineContentStyle,
                indicatorPositionProvider = indicatorPositionProvider,
                isScrollInProgressProvider = isScrollInProgressProvider,
                externalPagerMotionEffectsEnabled = indicatorPositionProvider != null,
            )
        }
    }
}
