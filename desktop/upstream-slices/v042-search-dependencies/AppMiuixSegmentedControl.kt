package com.android.purebilibili.core.ui.renderer.miuix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import com.android.purebilibili.core.ui.components.resolveMeasuredTabVisibilityDelta
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.LayoutDirection
import com.android.purebilibili.core.ui.LocalImmersiveTopChromeActive
import com.android.purebilibili.core.ui.AppSurfaceTokens
import com.android.purebilibili.core.ui.components.shouldUseOpaqueMiuixTabBackdrop
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.android.purebilibili.core.ui.adaptiveSquircleBackground
import com.android.purebilibili.core.ui.AppChromeSizeTokens
import com.android.purebilibili.core.ui.AppSpacingTokens
import com.android.purebilibili.core.ui.components.AppSegmentOption
import com.android.purebilibili.core.ui.components.AppSegmentedControlColors
import com.android.purebilibili.core.ui.components.resolveAppMiuixSegmentedColors
import com.android.purebilibili.core.ui.components.resolveAppSegmentedSelectionIndex
import com.android.purebilibili.core.ui.resolveRoundedControlVisualGeometry
import com.android.purebilibili.core.ui.resolveMiuixNonGlassControlGeometry
import com.android.purebilibili.core.ui.isMiuixNonGlassEnabled
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.core.ui.components.resolveMiuixNonGlassContentTabItemWidths
import com.android.purebilibili.core.ui.components.resolveElasticTabIndicatorBounds


@Composable
internal fun <T> AppMiuixSegmentedControl(
    options: List<AppSegmentOption<T>>,
    selectedValue: T,
    enabled: Boolean,
    colors: AppSegmentedControlColors,
    height: Dp? = null,
    modifier: Modifier,
    indicatorPositionProvider: (() -> Float)? = null,
    itemWidth: Dp? = null,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    containerHorizontalPadding: Dp = 0.dp,
    onSelectionChange: (T) -> Unit,
) {
    // Compact choices use the same native geometry and transition as other Miuix tabs.
    AppMiuixNonGlassTabs(
        options = options,
        selectedValue = selectedValue,
        enabled = enabled,
        compact = true,
        scrollable = false,
        minTabWidth = 0.dp,
        colors = colors,
        height = height,
        modifier = if (itemWidth == null) modifier.fillMaxWidth() else modifier,
        fixedItemWidth = itemWidth,
        labelFontSize = labelFontSize,
        containerHorizontalPadding = containerHorizontalPadding,
        indicatorPositionProvider = indicatorPositionProvider,
        onSelectionChange = onSelectionChange,
    )
}

@Composable
internal fun <T> AppMiuixTabRow(
    options: List<AppSegmentOption<T>>,
    selectedValue: T,
    enabled: Boolean,
    scrollable: Boolean,
    minTabWidth: Dp,
    colors: AppSegmentedControlColors,
    preferredCornerRadius: Dp,
    height: Dp? = null,
    modifier: Modifier,
    indicatorPositionProvider: (() -> Float)? = null,
    equalizeScrollableItemWidths: Boolean = false,
    centerContent: Boolean = false,
    contentSizedNonGlassItems: Boolean = false,
    contentSizedNonGlassMaxItemWidth: Dp = 320.dp,
    drawNonGlassTrack: Boolean = false,
    fixedItemWidth: Dp? = null,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    containerHorizontalPadding: Dp = 0.dp,
    onSelectionChange: (T) -> Unit,
) {
    if (isMiuixNonGlassEnabled() || fixedItemWidth != null || labelFontSize.isSpecified || containerHorizontalPadding != 0.dp) {
        AppMiuixNonGlassTabs(
            options = options,
            selectedValue = selectedValue,
            enabled = enabled,
            compact = options.size <= 2 && !scrollable,
            scrollable = scrollable,
            minTabWidth = minTabWidth,
            colors = colors,
            height = height,
            modifier = modifier,
            fixedItemWidth = fixedItemWidth,
            labelFontSize = labelFontSize,
            containerHorizontalPadding = containerHorizontalPadding,
            indicatorPositionProvider = indicatorPositionProvider,
            drawTrack = drawNonGlassTrack,
            centerContent = centerContent,
            equalizeScrollableItemWidths = equalizeScrollableItemWidths,
            contentSizedMaxItemWidth = contentSizedNonGlassMaxItemWidth,
            onSelectionChange = onSelectionChange,
        )
        return
    }
    if (contentSizedNonGlassItems && scrollable) {
        AppMiuixNonGlassTabs(
            options = options,
            selectedValue = selectedValue,
            enabled = enabled,
            compact = false,
            scrollable = true,
            minTabWidth = minTabWidth,
            colors = colors,
            height = height,
            modifier = modifier,
            equalizeScrollableItemWidths = equalizeScrollableItemWidths,
            centerContent = centerContent,
            contentSizedItems = true,
            contentSizedMaxItemWidth = contentSizedNonGlassMaxItemWidth,
            drawTrack = drawNonGlassTrack,
            onSelectionChange = onSelectionChange,
        )
        return
    }
    if (isMiuixNonGlassEnabled()) {
        if (options.size <= 2 && !scrollable) {
            AppMiuixSegmentedControl(
                options = options,
                selectedValue = selectedValue,
                enabled = enabled,
                colors = colors,
                height = height,
                modifier = modifier,
                indicatorPositionProvider = indicatorPositionProvider,
                onSelectionChange = onSelectionChange,
            )
            return
        }
        AppMiuixNonGlassTabs(
            options = options,
            selectedValue = selectedValue,
            enabled = enabled,
            compact = !scrollable && options.size <= 2,
            scrollable = scrollable,
            minTabWidth = minTabWidth,
            colors = colors,
            height = height,
            modifier = modifier,
            equalizeScrollableItemWidths = equalizeScrollableItemWidths,
            centerContent = centerContent,
            contentSizedItems = contentSizedNonGlassItems,
            contentSizedMaxItemWidth = contentSizedNonGlassMaxItemWidth,
            drawTrack = drawNonGlassTrack,
            onSelectionChange = onSelectionChange,
        )
        return
    }
    val selectedIndex = resolveAppSegmentedSelectionIndex(options, selectedValue)
    val scrollState = rememberLazyListState()
    val tabColors = resolveAppMiuixSegmentedColors(colors)
    val geometry = resolveRoundedControlVisualGeometry(
        preferredCornerRadius = preferredCornerRadius,
        nativeMinimumHeight = height ?: AppChromeSizeTokens.MinimumTouchTarget,
    )
    TabRow(
        tabs = options.map { it.label },
        selectedTabIndex = selectedIndex,
        onTabSelected = { index ->
            if (enabled) options.getOrNull(index)?.let { onSelectionChange(it.value) }
        },
        // Respect the caller's measured width so compact two-option controls do not
        // expand to the full parent and consume the adjacent action area.
        // Upstream paints a rectangular track and only rounds the selected item.
        // Clip the stationary viewport as well, including during horizontal scrolling.
        modifier = modifier.squircleClip(geometry.cornerRadius),
        colors = TabRowDefaults.tabRowColors(
            backgroundColor = tabColors.backgroundColor,
            contentColor = tabColors.contentColor,
            selectedBackgroundColor = tabColors.selectedBackgroundColor,
            selectedContentColor = tabColors.selectedContentColor,
        ),
        // 非 scrollable（如频道/状态切换）：交给 Miuix 按容器宽度均分，与 Material TabRow
        // 一致；scrollable（如时间表/分类）：minTabWidth 兜底保证可读。
        minWidth = if (scrollable) minTabWidth else 0.dp,
        maxWidth = Dp.Infinity,
        height = height ?: geometry.height,
        cornerRadius = geometry.cornerRadius,
        itemSpacing = 12.dp,
        listState = if (scrollable) scrollState else null,
    )
}

/** Opaque controls measure labels before applying the caller's size preferences. */
@Composable
private fun <T> AppMiuixNonGlassTabs(
    options: List<AppSegmentOption<T>>,
    selectedValue: T,
    enabled: Boolean,
    compact: Boolean,
    scrollable: Boolean = false,
    minTabWidth: Dp,
    colors: AppSegmentedControlColors,
    height: Dp? = null,
    modifier: Modifier,
    equalizeScrollableItemWidths: Boolean = false,
    centerContent: Boolean = false,
    contentSizedItems: Boolean = false,
    contentSizedMaxItemWidth: Dp = 320.dp,
    drawTrack: Boolean = true,
    fixedItemWidth: Dp? = null,
    labelFontSize: TextUnit = TextUnit.Unspecified,
    containerHorizontalPadding: Dp = 0.dp,
    indicatorPositionProvider: (() -> Float)? = null,
    onSelectionChange: (T) -> Unit,
) {
    val labels = options.map { it.label }
    val selectedIndex = resolveAppSegmentedSelectionIndex(options, selectedValue)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val tabColors = resolveAppMiuixSegmentedColors(colors)
    val style = MiuixTheme.textStyles.main.copy(
        fontSize = if (labelFontSize.isSpecified) labelFontSize else MiuixTheme.textStyles.body1.fontSize,
        fontWeight = FontWeight.Bold,
    )
    val labelSizes = remember(labels, style, measurer, density) {
        labels.map { measurer.measure(AnnotatedString(it), style, maxLines = 1).size }
    }
    val textHeight = with(density) { (labelSizes.maxOfOrNull { it.height } ?: 0).toDp() }
    val geometry = resolveMiuixNonGlassControlGeometry(compact, textHeight)
    if (isMiuixNonGlassEnabled() || fixedItemWidth != null || labelFontSize.isSpecified || containerHorizontalPadding != 0.dp) {
        AppMiuixSizedTabs(
            options = options,
            selectedIndex = selectedIndex,
            enabled = enabled,
            scrollable = scrollable,
            fixedItemWidth = fixedItemWidth,
            measuredItemWidths = resolveMiuixNonGlassContentTabItemWidths(
                labelSizes.map { with(density) { it.width.toDp() } },
                maxOf(minTabWidth, AppChromeSizeTokens.MinimumTouchTarget),
                contentSizedMaxItemWidth,
            ),
            equalizeItemWidths = equalizeScrollableItemWidths,
            colors = colors,
            height = maxOf(height ?: geometry.height, geometry.height),
            cornerRadius = geometry.cornerRadius,
            labelStyle = style,
            containerHorizontalPadding = containerHorizontalPadding,
            indicatorPositionProvider = indicatorPositionProvider,
            modifier = modifier,
            drawTrack = drawTrack,
            centerContent = centerContent,
            onSelectionChange = onSelectionChange,
        )
        return
    }
    if (contentSizedItems && scrollable) {
        AppMiuixContentSizedNonGlassTabs(
            options = options,
            selectedValue = selectedValue,
            selectedIndex = selectedIndex,
            enabled = enabled,
            itemWidths = resolveMiuixNonGlassContentTabItemWidths(
                labelWidths = labelSizes.map { with(density) { it.width.toDp() } },
                minTabWidth = minTabWidth,
                maxTabWidth = contentSizedMaxItemWidth,
            ),
            colors = colors,
            height = height ?: geometry.height,
            modifier = modifier,
            centerContent = centerContent,
            drawTrack = drawTrack,
            onSelectionChange = onSelectionChange,
        )
        return
    }
    // Share upstream's item geometry with the drawing layer; read offsets only during draw.
    val listState = rememberLazyListState()
    val opaqueItems = shouldUseOpaqueMiuixTabBackdrop(true, LocalImmersiveTopChromeActive.current)
    val itemBackground = AppSurfaceTokens.groupedListContainer()
    // Keep the upstream TabRow defaults for a scrollable rail. The app-level 48dp
    // accessibility minimum is too narrow once upstream's 12dp item padding is
    // applied, which turns otherwise readable Chinese labels into ellipses.
    val tabRowMinWidth = if (scrollable) {
        maxOf(minTabWidth, TabRowDefaults.TabRowMinWidth)
    } else {
        0.dp
    }
    val tabRowMaxWidth = if (scrollable) {
        maxOf(TabRowDefaults.TabRowMaxWidth, tabRowMinWidth)
    } else {
        Dp.Infinity
    }
    TabRow(
        tabs = labels,
        selectedTabIndex = selectedIndex,
        onTabSelected = { index ->
            if (enabled) options.getOrNull(index)?.let { onSelectionChange(it.value) }
        },
        modifier = modifier
            .squircleClip(geometry.cornerRadius)
            .drawBehind {
                if (opaqueItems) clipRect {
                    listState.layoutInfo.visibleItemsInfo.forEach { item ->
                        val left = if (layoutDirection == LayoutDirection.Rtl) {
                            size.width - item.offset - item.size
                        } else item.offset.toFloat()
                        val path = Path().apply {
                            addSquircleRect(item.size.toFloat(), size.height, geometry.cornerRadius.toPx())
                        }
                        translate(left = left) { drawPath(path, itemBackground) }
                    }
                }
            }
            .then(if (!enabled) Modifier.semantics { disabled() } else Modifier),
        colors = TabRowDefaults.tabRowColors(
            backgroundColor = if (drawTrack) tabColors.backgroundColor else Color.Transparent,
            contentColor = tabColors.contentColor,
            selectedBackgroundColor = tabColors.selectedBackgroundColor,
            selectedContentColor = tabColors.selectedContentColor,
        ),
        minWidth = tabRowMinWidth,
        maxWidth = tabRowMaxWidth,
        height = height ?: geometry.height,
        cornerRadius = geometry.cornerRadius,
        itemSpacing = 12.dp,
        listState = listState,
    )
}

@Composable
private fun <T> AppMiuixContentSizedNonGlassTabs(
    options: List<AppSegmentOption<T>>,
    selectedValue: T,
    selectedIndex: Int,
    enabled: Boolean,
    itemWidths: List<Dp>,
    colors: AppSegmentedControlColors,
    height: Dp,
    modifier: Modifier,
    centerContent: Boolean,
    drawTrack: Boolean,
    onSelectionChange: (T) -> Unit,
) {
    val tabColors = resolveAppMiuixSegmentedColors(colors)
    val inactiveItemBackground = if (shouldUseOpaqueMiuixTabBackdrop(true, LocalImmersiveTopChromeActive.current)) {
        AppSurfaceTokens.groupedListContainer()
    } else Color.Transparent
    val listState = rememberLazyListState()
    val initialSelectionPositioned = remember(listState) { object { var value = false } }
    val motionEnabled = com.android.purebilibili.core.ui.LocalComponentMotionEnabled.current
    val density = LocalDensity.current
    LaunchedEffect(selectedIndex, itemWidths, motionEnabled, density) {
        // Observe resize/measurement, not scroll offsets, so manual scrolling stays free.
        snapshotFlow { listState.layoutInfo.viewportSize.width to listState.layoutInfo.totalItemsCount }
            .filter { (width, count) -> width > 0 && count > 0 }
            .collectLatest {
                val animateSelection = motionEnabled && initialSelectionPositioned.value
                val target = selectedIndex.coerceIn(0, options.lastIndex)
                val info = listState.layoutInfo
                val viewportWidth = info.viewportEndOffset - info.viewportStartOffset
                val itemWidthPx = with(density) {
                    itemWidths.getOrElse(target) { 48.dp }.roundToPx()
                }
                // Center the selected category so adjacent categories retain usable
                // hit targets even when the selected button was already fully visible.
                // LazyRow clamps the movement at either end of the rail.
                val leadingSpace = ((viewportWidth - itemWidthPx) / 2).coerceAtLeast(0)
                val item = info.visibleItemsInfo.firstOrNull { it.index == target }
                if (item == null) {
                    if (animateSelection) {
                        listState.animateScrollToItem(target, scrollOffset = -leadingSpace)
                    } else {
                        listState.scrollToItem(target, scrollOffset = -leadingSpace)
                    }
                } else {
                    val delta = if (item.size <= viewportWidth) {
                        (item.offset - info.viewportStartOffset - leadingSpace).toFloat()
                    } else {
                        resolveMeasuredTabVisibilityDelta(
                            item.offset, item.offset + item.size,
                            info.viewportStartOffset, info.viewportEndOffset,
                        ).toFloat()
                    }
                    if (delta != 0f) {
                        if (animateSelection) listState.animateScrollBy(delta) else listState.scrollBy(delta)
                    }
                }
                initialSelectionPositioned.value = true
            }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .squircleClip(8.dp)
            .background(if (drawTrack) tabColors.backgroundColor else Color.Transparent)
            .then(if (!enabled) Modifier.semantics { disabled() } else Modifier),
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = options.size) },
            horizontalArrangement = Arrangement.spacedBy(
                12.dp,
                if (centerContent) Alignment.CenterHorizontally else Alignment.Start,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(options) { index, option ->
                val selected = option.value == selectedValue
                Box(
                    modifier = Modifier
                        .width(itemWidths.getOrElse(index) { 48.dp })
                        .height(height)
                        .adaptiveSquircleBackground(
                            color = if (selected) tabColors.selectedBackgroundColor else inactiveItemBackground,
                            cornerRadius = 8.dp,
                        )
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onSelectionChange(option.value) },
                        )
                        .semantics {
                            collectionItemInfo = CollectionItemInfo(
                                rowIndex = 0, rowSpan = 1, columnIndex = index, columnSpan = 1,
                            )
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        text = option.label,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                        color = if (selected) {
                            tabColors.selectedContentColor
                        } else {
                            tabColors.contentColor
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> AppMiuixSizedTabs(
    options: List<AppSegmentOption<T>>,
    selectedIndex: Int,
    enabled: Boolean,
    scrollable: Boolean,
    fixedItemWidth: Dp?,
    measuredItemWidths: List<Dp>,
    equalizeItemWidths: Boolean,
    colors: AppSegmentedControlColors,
    height: Dp,
    cornerRadius: Dp,
    labelStyle: androidx.compose.ui.text.TextStyle,
    containerHorizontalPadding: Dp,
    indicatorPositionProvider: (() -> Float)?,
    modifier: Modifier,
    drawTrack: Boolean,
    centerContent: Boolean,
    onSelectionChange: (T) -> Unit,
) {
    val tabColors = resolveAppMiuixSegmentedColors(colors)
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val motionEnabled = com.android.purebilibili.core.ui.LocalComponentMotionEnabled.current
    val animatedPosition by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (!motionEnabled || indicatorPositionProvider != null) snap() else spring(),
        label = "miuixTabPosition",
    )
    val position = indicatorPositionProvider?.invoke() ?: animatedPosition
    val inactiveBackground = if (shouldUseOpaqueMiuixTabBackdrop(true, LocalImmersiveTopChromeActive.current)) {
        AppSurfaceTokens.groupedListContainer()
    } else Color.Transparent
    BoxWithConstraints(
        modifier = modifier
            .then(if (fixedItemWidth != null) {
                Modifier.width(fixedItemWidth * options.size + containerHorizontalPadding * 2)
            } else Modifier.fillMaxWidth())
            .height(height),
        contentAlignment = if (centerContent) Alignment.Center else Alignment.CenterStart,
    ) {
        val padding = minOf(containerHorizontalPadding, maxWidth / 2)
        val gap = if (fixedItemWidth != null) 0.dp else AppSpacingTokens.Small
        val readableWidths = measuredItemWidths.map { maxOf(it, fixedItemWidth ?: 0.dp) }
        val preferredWidths = if (equalizeItemWidths) {
            List(options.size) { readableWidths.maxOrNull() ?: 48.dp }
        } else readableWidths
        val availableWidth = (maxWidth - padding * 2 - gap * (options.size - 1)).coerceAtLeast(0.dp)
        val equalSlotWidth = availableWidth / options.size
        // A constrained detail toolbar can be narrower than two readable labels.
        // Keep the labels intact and scroll instead of silently ellipsizing them.
        val needsScroll = scrollable || preferredWidths.any { it > equalSlotWidth }
        val widths = if (needsScroll) preferredWidths else List(options.size) { equalSlotWidth }
        val lefts = remember(widths, padding, gap) {
            var left = padding
            widths.map { width -> left.also { left += width + gap } }
        }
        val contentWidth = widths.fold(padding * 2 + gap * (options.size - 1)) { total, width -> total + width }
        val viewportWidth = minOf(contentWidth, maxWidth)
        LaunchedEffect(selectedIndex, widths, viewportWidth, scrollState.maxValue, motionEnabled, density, needsScroll) {
            if (needsScroll) {
                val left = with(density) { lefts[selectedIndex].roundToPx() }
                val right = left + with(density) { widths[selectedIndex].roundToPx() }
                val viewport = with(density) { viewportWidth.roundToPx() }
                val target = when {
                    right - left >= viewport -> left
                    left < scrollState.value -> left
                    right > scrollState.value + viewport -> right - viewport
                    else -> scrollState.value
                }.coerceIn(0, scrollState.maxValue)
                if (motionEnabled) scrollState.animateScrollTo(target) else scrollState.scrollTo(target)
            }
        }
        val bounds = resolveElasticTabIndicatorBounds(
            position = position,
            tabLeftsDp = lefts.map { it.value },
            tabWidthsDp = widths.map { it.value },
            contentWidthsDp = widths.map { it.value },
            matchContentSize = false,
        )
        Box(
            modifier = Modifier
                .width(viewportWidth)
                .height(height)
                .squircleClip(cornerRadius)
                .background(if (drawTrack) tabColors.backgroundColor else Color.Transparent)
                .horizontalScroll(scrollState, enabled = needsScroll)
                .selectableGroup()
                .semantics { collectionInfo = CollectionInfo(1, options.size) },
        ) {
            Row(
                modifier = Modifier.height(height).padding(horizontal = padding),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                widths.forEach { width ->
                    Box(Modifier.width(width).height(height).adaptiveSquircleBackground(inactiveBackground, cornerRadius))
                }
            }
            Box(
                Modifier
                    .offset(x = bounds.leftDp.dp)
                    .width(bounds.widthDp.dp)
                    .height(height)
                    .adaptiveSquircleBackground(tabColors.selectedBackgroundColor, cornerRadius),
            )
            Row(
                modifier = Modifier.height(height).padding(horizontal = padding),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                options.forEachIndexed { index, option ->
                    val selected = index == selectedIndex
                    Box(
                        modifier = Modifier
                            .width(widths[index])
                            .height(height)
                            .squircleClip(cornerRadius)
                            .selectable(selected, enabled = enabled, role = Role.Tab) { onSelectionChange(option.value) }
                            .semantics { collectionItemInfo = CollectionItemInfo(0, 1, index, 1) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppText(
                            text = option.label,
                            color = if (selected) tabColors.selectedContentColor else tabColors.contentColor,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            style = labelStyle,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
