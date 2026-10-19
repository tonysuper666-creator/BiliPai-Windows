package com.bilipai.desktop.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp

/*
 * Pointer and keyboard affordances of every kit control (spec 5.1): hover wash, pressed wash, hand cursor, a focus
 * ring that shows for keyboard focus only, and pixel-snapped hairlines. The vendored appDesktopInteractionVisuals
 * is not reused: it draws its ring on any focus (a mouse click included), at an unsnapped 2 dp, with a fixed 12 dp
 * radius. The vendored switch and slider keep using it internally.
 *
 * The kit does not read LocalInputModeManager. Whether a Compose Desktop scene ever leaves keyboard mode cannot be
 * shown without a window, so the ring follows DesktopKitFocusState, a pointer-press and navigation-key heuristic.
 */

/** Reduced motion, read once by the host and passed down. The kit never polls the system setting per control. */
internal val LocalDesktopKitReducedMotion = staticCompositionLocalOf { false }

private val DESKTOP_KIT_NAVIGATION_KEYS = setOf(
    Key.Tab, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.Spacebar, Key.Enter,
    Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown,
)

private fun desktopKitNowMs(): Long = System.nanoTime() / 1_000_000L

/** Observes primary and secondary presses on the Initial pass without consuming them. */
@Composable
internal fun Modifier.desktopKitOnPointerPress(onPress: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onPress)
    return pointerInput(Unit) {
        awaitEachGesture {
            var event = awaitPointerEvent(PointerEventPass.Initial)
            while (event.changes.none { it.pressed }) event = awaitPointerEvent(PointerEventPass.Initial)
            latest()
            while (event.changes.any { it.pressed }) event = awaitPointerEvent(PointerEventPass.Initial)
        }
    }
}

/** Hand cursor, hover and pressed wash, and the selected fill (primary at the selected percent). */
@Composable
internal fun Modifier.desktopKitHoverWash(
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp,
    enabled: Boolean = true,
    selected: Boolean = false,
): Modifier {
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val reduced = LocalDesktopKitReducedMotion.current
    val wash by animateFloatAsState(
        targetValue = desktopKitWashPercent(enabled, hovered, pressed) / 100f,
        animationSpec = tween(desktopKitMotionMs(reduced, DESKTOP_KIT_MOTION_FAST_MS)),
        label = "desktopKitWash",
    )
    val washColor = MaterialTheme.colorScheme.onSurface
    val selectedColor = MaterialTheme.colorScheme.primary.copy(alpha = DESKTOP_KIT_SELECTED_FILL_PERCENT / 100f)
    return this
        .hoverable(interactionSource, enabled)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        .drawBehind {
            val radius = CornerRadius(cornerRadius.toPx())
            if (selected) drawRoundRect(color = selectedColor, cornerRadius = radius)
            if (wash > 0f) drawRoundRect(color = washColor.copy(alpha = wash), cornerRadius = radius)
        }
}

/** A two-part ring (outer onSurface, inner surface) drawn over the content, only for keyboard focus. */
@Composable
internal fun Modifier.desktopKitFocusRing(
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp,
    enabled: Boolean = true,
): Modifier {
    var focus by remember(interactionSource) { mutableStateOf(DesktopKitFocusState()) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is FocusInteraction.Focus) {
                focus = focus.onFocus(desktopKitNowMs())
            } else if (interaction is FocusInteraction.Unfocus) {
                focus = focus.onUnfocus()
            }
        }
    }
    val outerColor = MaterialTheme.colorScheme.onSurface
    val innerColor = MaterialTheme.colorScheme.surface
    return this
        .desktopKitOnPointerPress { focus = focus.onPointerPress(desktopKitNowMs()) }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in DESKTOP_KIT_NAVIGATION_KEYS) focus = focus.onNavigationKey()
            false
        }
        .drawWithContent {
            drawContent()
            if (enabled && focus.ringVisible) drawDesktopKitRing(outerColor, innerColor, cornerRadius.toPx())
        }
}

/** Hover wash, hand cursor, selected fill and keyboard focus ring in one modifier. */
@Composable
internal fun Modifier.desktopKitInteraction(
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp,
    enabled: Boolean = true,
    selected: Boolean = false,
): Modifier = this
    .desktopKitHoverWash(interactionSource, cornerRadius, enabled, selected)
    .desktopKitFocusRing(interactionSource, cornerRadius, enabled)

private fun DrawScope.drawDesktopKitRing(outerColor: Color, innerColor: Color, radiusPx: Float) {
    val outer = desktopKitSnapStrokePx(DESKTOP_KIT_FOCUS_RING_OUTER_DP * density)
    val inner = desktopKitSnapStrokePx(DESKTOP_KIT_FOCUS_RING_INNER_DP * density)
    drawRoundRect(
        color = outerColor,
        topLeft = Offset(outer / 2f, outer / 2f),
        size = Size((size.width - outer).coerceAtLeast(0f), (size.height - outer).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((radiusPx - outer / 2f).coerceAtLeast(0f)),
        style = Stroke(width = outer),
    )
    drawRoundRect(
        color = innerColor,
        topLeft = Offset(outer + inner / 2f, outer + inner / 2f),
        size = Size((size.width - 2f * outer - inner).coerceAtLeast(0f), (size.height - 2f * outer - inner).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((radiusPx - outer - inner / 2f).coerceAtLeast(0f)),
        style = Stroke(width = inner),
    )
}

/** A one-pixel border: stroke width 1 px, inset half a pixel so the line covers exactly one pixel row and column. */
internal fun Modifier.desktopKitHairlineBorder(color: Color, cornerRadius: Dp): Modifier = drawWithContent {
    drawContent()
    drawRoundRect(
        color = color,
        topLeft = Offset(0.5f, 0.5f),
        size = Size((size.width - 1f).coerceAtLeast(0f), (size.height - 1f).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((cornerRadius.toPx() - 0.5f).coerceAtLeast(0f)),
        style = Stroke(width = 1f),
    )
}
