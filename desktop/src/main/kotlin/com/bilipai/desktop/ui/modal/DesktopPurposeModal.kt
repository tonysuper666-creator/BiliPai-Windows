@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.bilipai.desktop.ui.modal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.purebilibili.core.ui.motion.AppMotionEasing
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_MOTION_SLOW_MS
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_MENU_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_PANEL_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP
import com.bilipai.desktop.ui.kit.LocalDesktopKitReducedMotion

/** A separate focusable Compose layer; the existing untagged upstream sheet is never replaced. */
@Composable
internal fun DesktopPurposeModal(
    purpose: DesktopModalPurpose,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: IntRect? = null,
    designWidth: Dp = 480.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    Popup(
        popupPositionProvider = remember { object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        } },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        DesktopModalLayer(purpose, onDismissRequest,
            modifier.size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() }),
            anchor, designWidth, containerColor, footer = footer, content = content)
    }
}

/** Shared by the popup and the owned native reading window, including real key/scroll handling. */
@Composable
internal fun DesktopModalLayer(
    purpose: DesktopModalPurpose,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: IntRect? = null,
    designWidth: Dp = 480.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    scrollBody: Boolean = true,
    closingRequested: Boolean = false,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    require(purpose != DesktopModalPurpose.OPTIONS || anchor != null)
    val density = LocalDensity.current.density
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    val reduced = LocalDesktopKitReducedMotion.current
    val slide = remember { Animatable(if (reduced) 0f else 1f) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(closingRequested) { if (closingRequested) closing = true }
    LaunchedEffect(closing, reduced) {
        if (purpose == DesktopModalPurpose.READING_PANEL && !reduced) {
            slide.animateTo(if (closing) 1f else 0f,
                tween(DESKTOP_KIT_MOTION_SLOW_MS, easing = AppMotionEasing.Md3Standard))
        } else slide.snapTo(0f)
        if (closing) latestDismiss()
    }
    fun dismiss() {
        if (purpose == DesktopModalPurpose.READING_PANEL) closing = true else latestDismiss()
    }
    LaunchedEffect(Unit) {
        // A native window's first composition can precede its first measured frame.
        withFrameNanos { }
        requester.requestFocus()
        focus.moveFocus(FocusDirection.Next)
    }
    Box(modifier.clipToBounds()
        .focusRequester(requester)
        .onPreviewKeyEvent { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.Escape -> { dismiss(); true }
                event.key == Key.Tab -> {
                    val direction = if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
                    focus.moveFocus(direction)
                    // The root is only the keyboard fallback for a panel with no controls.
                    if (rootFocused) focus.moveFocus(direction)
                    true
                }
                else -> false
            }
        }.onFocusChanged { rootFocused = it.isFocused }.focusable()
        .onPointerEvent(PointerEventType.Scroll) { e -> e.changes.forEach { it.consume() } },
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = .32f))
            .focusProperties { canFocus = false }
            .clickable(remember { MutableInteractionSource() }, indication = null) { dismiss() }
            .testTag("desktop-modal-backdrop"))
        Layout(content = {
            val radius = if (purpose == DesktopModalPurpose.OPTIONS) DESKTOP_KIT_RADIUS_MENU_DP else DESKTOP_KIT_RADIUS_PANEL_DP
            Surface(color = containerColor, shape = RoundedCornerShape(radius.dp),
                modifier = Modifier.testTag("desktop-modal-surface").graphicsLayer {
                    if (purpose == DesktopModalPurpose.READING_PANEL) translationX = size.width * slide.value
                }) {
                Column {
                    val body = Modifier.weight(1f, fill = purpose == DesktopModalPurpose.READING_PANEL).fillMaxWidth()
                        .testTag("desktop-modal-body")
                    Column(if (scrollBody) body.verticalScroll(rememberScrollState()) else body, content = content)
                    if (footer != null) Row(Modifier.fillMaxWidth().testTag("desktop-modal-footer"),
                        horizontalArrangement = Arrangement.End, content = footer)
                }
            }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val viewport = IntSize(constraints.maxWidth, constraints.maxHeight)
            val limit = desktopModalBounds(purpose, viewport, density, anchor = anchor, designWidthDp = designWidth.value)
            val child = measurables.single().measure(Constraints(
                minWidth = limit.width, maxWidth = limit.width,
                minHeight = if (purpose == DesktopModalPurpose.READING_PANEL) limit.height else 0,
                maxHeight = limit.height,
            ))
            val bounds = desktopModalBounds(purpose, viewport, density, child.height, anchor, designWidth.value)
            layout(viewport.width, viewport.height) { child.place(bounds.left, bounds.top) }
        }
    }
}

/** OPTIONS rows use the compact settings row token (36 dp); callers retain their own action and label. */
@Composable
internal fun DesktopModalOption(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(DESKTOP_KIT_ROW_SETTINGS_COMPACT_DP.dp).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = content)
}
