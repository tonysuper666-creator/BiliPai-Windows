package com.bilipai.desktop.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.purebilibili.core.ui.AppPopupSurface
import com.android.purebilibili.core.ui.AppPopupSurfaceType

/*
 * Menus and popovers of the kit (spec 5.1 and 4.9), part 2. A Compose Popup on AppPopupSurface(MENU): no dim and no
 * scrim, the surface is the glass or solid popup material of the app, and the popup flips and clamps at the window edges
 * through the pure desktopKitPlaceMenu. Popup is an overlay of the host window, so these are for pages of the shell. A
 * menu over the native video lives in the player flyout window, never here.
 *
 * A row is one button and the caller owns the open state: a row calls its own onClick and the caller closes the menu,
 * as the vendored card menu does. Escape and a press outside call onDismissRequest. Up and Down move the focus between
 * the enabled rows (a disabled row is not focusable); Enter and Space activate the focused row. Separate groups with
 * DesktopKitDivider.
 */

/** A menu row: one 32 dp button with an optional icon and a shortcut hint. A disabled row stays visible and dimmed. */
@Composable
internal fun DesktopMenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shortcut: String? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(DESKTOP_KIT_CONTROL_HEIGHT_DP.dp)
            .alpha(desktopKitDisabledAlpha(enabled))
            .desktopKitInteraction(source, DESKTOP_KIT_RADIUS_CONTROL_DP.dp, enabled)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(DESKTOP_KIT_ICON_DP.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Text(
            label, Modifier.weight(1f), color = colors.onSurface, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (shortcut != null) {
            // Decorative: the accessible name of the row is its label alone.
            Text(
                shortcut, Modifier.clearAndSetSemantics { }, color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 1,
            )
        }
    }
}

/**
 * A context menu that opens at a pointer position. pointer is relative to the parent layout of this call (the Box that
 * received the press), in pixels. The menu is a fixed width and scrolls when it is taller than the window.
 */
@Composable
internal fun DesktopContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    pointer: IntOffset,
    modifier: Modifier = Modifier,
    width: Dp = DESKTOP_KIT_MENU_WIDTH_DP.dp,
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DesktopKitPopupPanel(
        expanded, onDismissRequest, DesktopKitMenuPlacement.AT_POINT, pointer, modifier, width,
        DESKTOP_KIT_MENU_PADDING_DP.dp, label, content,
    )
}

/**
 * A popover anchored to the parent layout of this call (the control that opened it): under it, aligned to its end edge
 * by default, flipped above it when there is no room below. It holds any content, for example a small settings form.
 */
@Composable
internal fun DesktopPopover(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    placement: DesktopKitMenuPlacement = DesktopKitMenuPlacement.BELOW_END,
    width: Dp = DESKTOP_KIT_POPOVER_WIDTH_DP.dp,
    label: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DesktopKitPopupPanel(expanded, onDismissRequest, placement, IntOffset.Zero, modifier, width, 12.dp, label, content)
}

@Composable
private fun DesktopKitPopupPanel(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    placement: DesktopKitMenuPlacement,
    pointer: IntOffset,
    modifier: Modifier,
    width: Dp,
    contentPadding: Dp,
    label: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val gapPx = with(density) { DESKTOP_KIT_MENU_GAP_DP.dp.roundToPx() }
    val maxHeightPx = desktopKitMenuMaxHeightPx(LocalWindowInfo.current.containerSize.height, gapPx)
    val provider = remember(placement, pointer, gapPx) { DesktopKitMenuPositionProvider(placement, pointer, gapPx) }
    var layerFocus by remember { mutableStateOf<FocusManager?>(null) }
    val colors = MaterialTheme.colorScheme
    val radius = DESKTOP_KIT_RADIUS_MENU_DP.dp
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
        onPreviewKeyEvent = { event -> desktopKitMenuKey(event, layerFocus) },
    ) {
        // The popup layer has its own focus owner: the manager of the popup content is the one that moves focus here.
        val focusManager = LocalFocusManager.current
        SideEffect { layerFocus = focusManager }
        AppPopupSurface(
            type = AppPopupSurfaceType.MENU,
            shape = RoundedCornerShape(radius),
            containerColor = colors.surface,
            modifier = modifier
                .width(width)
                .heightIn(max = with(density) { maxHeightPx.toDp() })
                .desktopKitHairlineBorder(colors.outlineVariant, radius)
                .semantics { if (label != null) contentDescription = label },
        ) {
            val scroll = rememberScrollState()
            val endPadding = if (scroll.maxValue > 0) DESKTOP_KIT_SCROLLBAR_HIT_STRIP_DP.dp else contentPadding
            Box {
                Column(
                    Modifier
                        .verticalScroll(scroll)
                        .padding(start = contentPadding, top = contentPadding, end = endPadding, bottom = contentPadding),
                    content = content,
                )
                DesktopKitVerticalScrollbar(scroll)
            }
        }
    }
}

/** Up and Down (unmodified) move the focus between the rows; everything else, Alt+Left included, passes on. */
private fun desktopKitMenuKey(event: KeyEvent, focus: FocusManager?): Boolean {
    if (focus == null || event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || event.isShiftPressed) return false
    return when (event.key) {
        Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
        Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
        else -> false
    }
}

/** Places the popup with the pure flip and clamp. A pointer is relative to the anchor layout, so it is added to its corner. */
private class DesktopKitMenuPositionProvider(
    private val placement: DesktopKitMenuPlacement,
    private val pointer: IntOffset,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val atPoint = placement == DesktopKitMenuPlacement.AT_POINT
        val left = if (atPoint) anchorBounds.left + pointer.x else anchorBounds.left
        val top = if (atPoint) anchorBounds.top + pointer.y else anchorBounds.top
        val position = desktopKitPlaceMenu(
            placement, left, top, if (atPoint) left else anchorBounds.right, if (atPoint) top else anchorBounds.bottom,
            popupContentSize.width, popupContentSize.height, windowSize.width, windowSize.height, gapPx,
        )
        return IntOffset(position.x, position.y)
    }
}
