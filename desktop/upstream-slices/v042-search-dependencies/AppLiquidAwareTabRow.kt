package com.android.purebilibili.core.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.ui.components.AppTabRowIndicatorPresentation
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.feature.home.components.resolveFloatingDockCaptureInsets
import com.android.purebilibili.feature.home.components.FloatingBottomBarGeometryMode
import com.android.purebilibili.feature.home.components.BottomBarLiquidSegmentedControl
import top.yukonga.miuix.kmp.blur.Backdrop


internal fun resolveAppAdaptiveTabMinWidth(requestedMinTabWidth: Dp): Dp =
    if (requestedMinTabWidth.isSpecified) requestedMinTabWidth else AppChromeSizeTokens.CompactTabMinWidth

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
    liquidGlassLabelHorizontalPadding: Dp? = null,
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
    liquidGlassAnimateLabelWidthChanges: Boolean = false,
) {
    AppLiquidAwareTabRow(
        options = options,
        selectedValue = selectedValue,
        onSelectionChange = onSelectionChange,
        modifier = modifier,
        enabled = enabled,
        scrollable = scrollable,
        minTabWidth = minTabWidth,
        liquidGlassLabelHorizontalPadding = liquidGlassLabelHorizontalPadding,
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
        liquidGlassAnimateLabelWidthChanges = liquidGlassAnimateLabelWidthChanges,
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
    liquidGlassLabelHorizontalPadding: Dp? = null,
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
    liquidGlassAnimateLabelWidthChanges: Boolean = false,
) {
    if (options.isEmpty()) return
    // Material boundary: glass-only spacing, wrapping and typography must never change
    // the disabled MD3/Miuix paths. Keep their original defaults when adding glass tuning.
    val liquidGlassEnabled = com.android.purebilibili.core.ui.LocalAppThemeConfig.current.liquidGlassEnabled
    // Opaque Miuix rails own their measurement and scrolling; glass capture
    // margins must not consume the space reserved for neighbouring actions.
    if (com.android.purebilibili.core.ui.isMiuixNonGlassEnabled()) {
        AppNativeTabRow(
            options = options,
            selectedValue = selectedValue,
            onSelectionChange = onSelectionChange,
            modifier = modifier,
            enabled = enabled,
            scrollable = scrollable,
            centerContent = centerContent,
            minTabWidth = resolveAppAdaptiveTabMinWidth(minTabWidth),
            compactMiuixWhenTwoOptions = compactMiuixWhenTwoOptions,
            labelFontSize = if (labelFontSize.isSpecified) labelFontSize else
                com.android.purebilibili.core.ui.appTabLabelFontSize(),
            indicatorPositionProvider = indicatorPositionProvider,
        )
        return
    }
    val labels = remember(options) { options.map { it.label } }
    val resolvedMinTabWidth = resolveAppAdaptiveTabMinWidth(minTabWidth)
    val resolvedLabelFontSize = com.android.purebilibili.core.ui.appTabLabelFontSize(labelFontSize)
    // Measure once before choosing the material. Glass and native renderers own
    // the same slots, including font scaling and long labels.
    val measuredTabWidth = rememberMeasuredTabMinWidth(
        requestedMinWidth = resolvedMinTabWidth,
        labels = labels,
        textStyle = MaterialTheme.typography.labelLarge.copy(
            fontSize = resolvedLabelFontSize,
            fontWeight = FontWeight.SemiBold,
        ),
        horizontalPadding = if (liquidGlassEnabled) {
            liquidGlassLabelHorizontalPadding ?: resolveLiquidGlassTabSlotHorizontalPadding(options.size)
        } else 12.dp,
    )
    val selectedIndex = options.indexOfFirst { it.value == selectedValue }.coerceAtLeast(0)
    val resolvedDragSelectionEnabled = dragSelectionEnabled ?: (enabled && options.size > 1)
    // Data-driven width changes are opt-in and glass-only. Native MD3/Miuix keep
    // their measured slots and defaults, even when a caller enables this hint.
    val animateLabelWidths = liquidGlassEnabled && liquidGlassAnimateLabelWidthChanges &&
        com.android.purebilibili.core.ui.LocalComponentMotionEnabled.current &&
        !com.android.purebilibili.core.ui.LocalSystemReduceMotion.current
    val readableTabWidth = if (animateLabelWidths) {
        animateDpAsState(
            targetValue = measuredTabWidth,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            label = "glassAsyncAdaptiveTabWidth",
        ).value
    } else measuredTabWidth
    val nativePresentation = when (role) {
        AppTabRowRole.PRIMARY, AppTabRowRole.FILTER -> AppTabRowIndicatorPresentation.TONAL_PILL
        AppTabRowRole.SECONDARY -> AppTabRowIndicatorPresentation.UNDERLINE
    }
    val isCompact = (compactMiuixWhenTwoOptions && options.size <= 2) ||
        (minTabWidth.isSpecified && !scrollable)
    // Secondary categories and filters wrap their labels; primary navigation may fill its track.
    val contentSizedRail = isCompact || scrollable ||
        (liquidGlassEnabled && role != AppTabRowRole.PRIMARY)
    BoxWithConstraints(modifier = modifier, contentAlignment = if (centerContent) Alignment.Center else Alignment.CenterStart) {
        val contentWidth = readableTabWidth * options.size + AppSpacingTokens.ExtraSmall * 2
        // A wide label is not itself overflow. Fitting rails must stay outside
        // the rounded scroll viewport so a pressed glass lens can bloom freely.
        val needsHorizontalScroll = (if (liquidGlassEnabled) contentSizedRail else scrollable) &&
            constraints.hasBoundedWidth && contentWidth > maxWidth
        if (needsHorizontalScroll) {
            val indicatorCaptureInsets = resolveFloatingDockCaptureInsets(
                shellHeightDp = height.value,
                requestedIndicatorHeightDp = indicatorHeight.value,
                indicatorWidthDp = readableTabWidth.value,
                geometryMode = FloatingBottomBarGeometryMode.Segmented,
            )
            val indicatorOverflow = indicatorCaptureInsets.verticalDp.dp
            val indicatorHorizontalOverflow = indicatorCaptureInsets.horizontalDp.dp
            val scrollState = rememberScrollState()
            val density = LocalDensity.current
            BoxWithConstraints(
                modifier = Modifier.liquidDockViewport(
                    verticalOverflow = indicatorOverflow,
                    horizontalOverflow = indicatorHorizontalOverflow,
                ),
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
                    items = labels,
                    liquidGlassAnimateLabelWidthChanges = liquidGlassAnimateLabelWidthChanges,
                    liquidGlassLabelHorizontalPadding = liquidGlassLabelHorizontalPadding,
                    selectedIndex = selectedIndex,
                    onSelected = { index ->
                        options.getOrNull(index)?.let { onSelectionChange(it.value) }
                    },
                    modifier = Modifier.liquidDockViewport(
                    verticalOverflow = indicatorOverflow,
                    horizontalOverflow = indicatorHorizontalOverflow,
                ),
                    scrollState = scrollState,
                    enabled = enabled,
                    itemWidth = readableTabWidth,
                    height = height,
                    indicatorHeight = indicatorHeight,
                    labelFontSize = resolvedLabelFontSize,
                    allowNativeLabelOverflow = true,
                    liquidGlassEffectsEnabled = true,
                    dragSelectionEnabled = resolvedDragSelectionEnabled,
                    tapPressRefractionEnabled = tapPressRefractionEnabled,
                    miuixBackdrop = miuixBackdrop,
                    preferInlineContentStyle = preferInlineContentStyle,
                    compactMiuixWhenTwoOptions = compactMiuixWhenTwoOptions,
                    nativeIndicatorPresentation = nativePresentation,
                    indicatorPositionProvider = indicatorPositionProvider,
                    onIndicatorPositionChanged = { position ->
                        // During pager motion the shared scroll helper lock-steps the rail
                        // with the continuous indicator position. Keep edge-follow for idle
                        // indicator nudges so two scroll owners never compete.
                        if (!scrollState.isScrollInProgress &&
                            (pagerPositionProvider == null || !pagerMotionActiveProvider())
                        ) {
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
            val rowModifier = if (contentSizedRail) {
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
            val rowItemWidth = if (contentSizedRail) {
                readableTabWidth
            } else {
                null
            }
            BottomBarLiquidSegmentedControl(
                items = labels,
                liquidGlassAnimateLabelWidthChanges = liquidGlassAnimateLabelWidthChanges,
                liquidGlassLabelHorizontalPadding = liquidGlassLabelHorizontalPadding,
                selectedIndex = selectedIndex,
                onSelected = { index ->
                    options.getOrNull(index)?.let { onSelectionChange(it.value) }
                },
                modifier = rowModifier,
                enabled = enabled,
                itemWidth = rowItemWidth,
                height = height,
                indicatorHeight = indicatorHeight,
                labelFontSize = resolvedLabelFontSize,
                allowNativeLabelOverflow = true,
                liquidGlassEffectsEnabled = true,
                dragSelectionEnabled = resolvedDragSelectionEnabled,
                tapPressRefractionEnabled = tapPressRefractionEnabled,
                miuixBackdrop = miuixBackdrop,
                preferInlineContentStyle = preferInlineContentStyle,
                compactMiuixWhenTwoOptions = compactMiuixWhenTwoOptions,
                nativeIndicatorPresentation = nativePresentation,
                indicatorPositionProvider = indicatorPositionProvider,
                isScrollInProgressProvider = isScrollInProgressProvider,
                externalPagerMotionEffectsEnabled = indicatorPositionProvider != null,
            )
        }
    }
}
