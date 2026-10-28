package com.android.purebilibili.feature.video.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.PopupPositionProvider as MiuixPopupPositionProvider
import top.yukonga.miuix.kmp.window.WindowListPopup
import kotlin.math.roundToInt

enum class PlayerListPopupPlacement { CENTER, START, END, END_BOTTOM, ANCHOR_END }

/** 同窗玻璃宿主可用时使用统一菜单；关闭玻璃及独立窗口保持原生组件。 */
@Composable
fun PlayerSelectionPopup(
    title: String,
    showTitle: Boolean = true,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    placement: PlayerListPopupPlacement = PlayerListPopupPlacement.CENTER,
    maxHeight: Dp? = 440.dp,
    minWidth: Dp = 120.dp,
    anchorBounds: IntRect? = null,
    scrollState: ScrollState = rememberScrollState(),
    maxWidth: Dp = 288.dp,
    sourceLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null,
    source: com.android.purebilibili.core.ui.components.AppGlassMenuSource? =
        com.android.purebilibili.core.ui.components.LocalAppGlassMenuSource.current,
    onSourceVisibilityChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val glassSource = source
    val anchorToGlassSource = glassSource?.isAvailable == true &&
        com.android.purebilibili.core.ui.components.appGlassMenuAvailable()
    if (placement == PlayerListPopupPlacement.ANCHOR_END && anchorBounds == null && !anchorToGlassSource) return
    // 仅玻璃列表使用播放器菜单上限；原生分支继续保留调用方的尺寸与定位。
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val glassPolicy = resolvePlayerSettingsMenuLayoutPolicy(
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
    )
    val glassMaxWidth = maxWidth.coerceAtMost(glassPolicy.maxWidthDp.dp)
    val glassMaxHeight = maxHeight?.coerceAtMost(glassPolicy.maxHeightDp.dp) ?: glassPolicy.maxHeightDp.dp
    if (com.android.purebilibili.core.ui.components.AppGlassMenu(
            expanded = true,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            title = title,
            // 真实触发源仅改变玻璃菜单的定位，原生布局继续使用调用方 placement。
            placement = if (anchorToGlassSource) com.android.purebilibili.core.ui.components.AppGlassMenuPlacement.ANCHOR_END else when (placement) {
                PlayerListPopupPlacement.CENTER -> com.android.purebilibili.core.ui.components.AppGlassMenuPlacement.CENTER
                PlayerListPopupPlacement.START -> com.android.purebilibili.core.ui.components.AppGlassMenuPlacement.START
                PlayerListPopupPlacement.END -> com.android.purebilibili.core.ui.components.AppGlassMenuPlacement.END
                PlayerListPopupPlacement.END_BOTTOM -> com.android.purebilibili.core.ui.components.AppGlassMenuPlacement.END_BOTTOM
                PlayerListPopupPlacement.ANCHOR_END -> com.android.purebilibili.core.ui.components.AppGlassMenuPlacement.ANCHOR_END
            },
            anchorBounds = anchorBounds?.let { androidx.compose.ui.geometry.Rect(
                it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) },
            minWidth = minWidth.coerceAtMost(glassMaxWidth),
            maxWidth = glassMaxWidth,
            maxHeight = glassMaxHeight,
            scrollState = scrollState,
            showScrollbar = false,
            fitWholeItems = true,
            useAvailableHeight = true,
            source = source, sourceLayer = sourceLayer, onSourceVisibilityChange = onSourceVisibilityChange,
        ) {
            Column {
                if (showTitle) Text(title, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                content()
            }
        }) return
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val size = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing
    val left = insets.getLeft(density, direction).coerceIn(0, size.width)
    val top = insets.getTop(density).coerceIn(0, size.height)
    val safe = IntRect(
        left, top,
        (size.width - insets.getRight(density, direction)).coerceIn(left, size.width),
        (size.height - insets.getBottom(density)).coerceIn(top, size.height),
    )
    val gap = with(density) { 4.dp.roundToPx() }
    val requestedMaxHeight = with(density) { maxHeight?.roundToPx() }
    val viewport = resolvePlayerPopupViewport(
        safeWindowBounds = safe,
        requestedMaxHeight = requestedMaxHeight,
        horizontalMargin = gap,
        verticalMargin = gap,
        anchorBounds = anchorBounds.takeIf { placement == PlayerListPopupPlacement.ANCHOR_END },
        anchorGap = gap,
    )
    if (viewport.bounds.width <= 0 || viewport.maxHeight <= 0) return
    val width = with(density) { viewport.bounds.width.toDp() }.coerceAtMost(maxWidth)
    val height = with(density) { viewport.maxHeight.toDp() }
    val menuModifier = modifier
        .widthIn(min = minWidth.coerceAtMost(width), max = width)
        .heightIn(max = height)
        .semantics { paneTitle = title }

    if (LocalAppUiStyle.current == AppUiStyle.MIUIX) {
        val provider = remember(placement, anchorBounds, gap, requestedMaxHeight) {
            val measuredAnchor = anchorBounds
            object : MiuixPopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowBounds: IntRect,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                    popupMargin: IntRect,
                    alignment: MiuixPopupPositionProvider.Align,
                ): IntOffset {
                    val nativeViewport = resolvePlayerPopupViewport(
                        safeWindowBounds = windowBounds,
                        requestedMaxHeight = requestedMaxHeight,
                        horizontalMargin = gap,
                        verticalMargin = gap,
                        anchorBounds = measuredAnchor.takeIf {
                            placement == PlayerListPopupPlacement.ANCHOR_END
                        },
                        anchorGap = gap,
                    )
                    return resolvePlayerPopupOffset(
                        viewport = nativeViewport.bounds,
                        popupSize = popupContentSize,
                        placement = placement,
                        anchorBounds = measuredAnchor ?: anchorBounds,
                        layoutDirection = layoutDirection,
                        anchorGap = gap,
                    )
                }

                override fun getMargins(): PaddingValues = PaddingValues(4.dp)
            }
        }
        WindowListPopup(
            show = true,
            popupModifier = menuModifier,
            popupPositionProvider = provider,
            onDismissRequest = onDismissRequest,
            enableWindowDim = placement == PlayerListPopupPlacement.CENTER,
            maxHeight = height,
            minWidth = minWidth.coerceAtMost(width),
        ) {
            Box(Modifier.heightIn(max = height).widthIn(min = 200.dp.coerceAtMost(width), max = width)) {
                Column(Modifier.verticalScroll(scrollState).padding(end = 12.dp)) {
                    if (showTitle) SmallTitle(text = title)
                    content()
                }
                Box(Modifier.matchParentSize()) {
                    com.android.purebilibili.core.ui.components.AppMenuScrollbar(
                        scrollState = scrollState,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
                    )
                }
            }
        }
    } else if (placement == PlayerListPopupPlacement.CENTER) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {},
            title = if (showTitle) { { Text(title) } } else null,
            text = {
                Column(modifier = menuModifier.verticalScroll(scrollState)) { content() }
            },
        )
    } else {
        // 原生菜单从实际按钮边界定位；窗口坐标只在布局阶段换算，避免覆盖层偏移。
        val target = anchorBounds ?: when (placement) {
            PlayerListPopupPlacement.START -> IntRect(safe.left, safe.center.y, safe.left, safe.center.y)
            PlayerListPopupPlacement.END -> IntRect(safe.right, safe.center.y, safe.right, safe.center.y)
            PlayerListPopupPlacement.END_BOTTOM -> IntRect(safe.right, safe.bottom, safe.right, safe.bottom)
            PlayerListPopupPlacement.CENTER,
            PlayerListPopupPlacement.ANCHOR_END -> return
        }
        val origin = remember { mutableStateOf(Offset.Zero) }
        // 这层 fillMaxSize 只是为了拿窗口原点来做绝对定位，但调用方把本组件放在底栏的
        // Column 里，直接 fillMaxSize 会把 Column 撑到整屏高（198 -> 1260），导致
        // align(BottomStart) 的底栏跑到顶部。用 Popup 承载后，fillMaxSize 只作用于 Popup
        // 自己的全屏窗口，不再参与父容器的测量。
        Popup(
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = true,
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { origin.value = it.positionInWindow() },
            ) {
                Box(
                    modifier = Modifier
                        .align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset {
                            IntOffset(
                                (target.left - origin.value.x).roundToInt(),
                                (target.top - origin.value.y).roundToInt(),
                            )
                        }
                        .size(
                            width = with(density) { target.width.coerceAtLeast(0).toDp() },
                            height = with(density) { target.height.coerceAtLeast(0).toDp() },
                        ),
                ) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = onDismissRequest,
                        modifier = menuModifier,
                        scrollState = scrollState,
                        // 播放器浮层内的菜单不要抢焦点：focusable = true 会让窗口重新分发
                        // insets，全屏页里依赖 BottomStart 对齐的底栏会被压到顶部。
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true,
                        ),
                    ) {
                        if (showTitle) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        content()
                    }
                }
            }
        }
    }
}

/** 选中态、禁用原因和点击行为共享，行的颜色、尺寸和交互由主题原生组件提供。 */
@Composable
fun PlayerSelectionItem(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    summary: String? = null,
    modifier: Modifier = Modifier,
    index: Int = 0,
    optionCount: Int = 1,
) {
    PlayerNativeDropdownItem(
        item = DropdownItem(text = text, summary = summary),
        optionSize = optionCount,
        isSelected = selected,
        index = index,
        enabled = enabled,
        modifier = modifier,
        onSelectedIndexChange = { onClick() },
    )
}

@Composable
internal fun PlayerNativeDropdownItem(
    item: DropdownItem,
    optionSize: Int,
    isSelected: Boolean,
    index: Int,
    enabled: Boolean = item.enabled,
    modifier: Modifier = Modifier,
    onSelectedIndexChange: (Int) -> Unit,
) {
    val itemModifier = modifier.semantics(mergeDescendants = true) { selected = isSelected }
    if (com.android.purebilibili.core.ui.components.LocalAppGlassMenuScope.current != null) {
        com.android.purebilibili.core.ui.components.AppDropdownMenuItem(
            modifier = itemModifier,
            text = {
                Column {
                    Text(item.text)
                    item.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
            enabled = enabled,
            trailingIcon = if (isSelected) { { Icon(Icons.Default.Check, contentDescription = null) } } else null,
            onClick = { onSelectedIndexChange(index) },
        )
        return
    }
    if (LocalAppUiStyle.current == AppUiStyle.MIUIX) {
        Box(modifier = itemModifier) {
            DropdownImpl(
                item = item,
                optionSize = optionSize,
                isSelected = isSelected,
                index = index,
                enabled = enabled,
                onSelectedIndexChange = onSelectedIndexChange,
            )
        }
    } else {
        DropdownMenuItem(
            modifier = itemModifier,
            text = {
                Column {
                    Text(text = item.text)
                    item.summary?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            enabled = enabled,
            trailingIcon = if (isSelected) {
                { Icon(Icons.Default.Check, contentDescription = null) }
            } else null,
            onClick = { onSelectedIndexChange(index) },
        )
    }
}

@Composable
internal fun Modifier.playerPopupSelectedOption(selected: Boolean): Modifier {
    if (!selected) return this
    // 整项视口从列表顶部开始，不在打开时因选中项自动滚动而露出半行。
    if (com.android.purebilibili.core.ui.components.LocalAppGlassMenuScope.current?.fitWholeItems == true) return this
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(requester) { withFrameNanos { }; requester.bringIntoView() }
    return bringIntoViewRequester(requester)
}
