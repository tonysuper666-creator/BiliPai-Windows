package com.bilipai.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_FOCUS_RING_INNER_DP
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_FOCUS_RING_OUTER_DP
import com.bilipai.desktop.ui.kit.DesktopKitFocusState
import com.bilipai.desktop.ui.kit.desktopKitOnPointerPress
import com.bilipai.desktop.ui.kit.desktopKitSnapStrokePx

/*
 * Keyboard focus of the Home grid card cover (desktop UX program, slice K3; spec 2 focus ring, spec 4 Home item 4).
 *
 * The cover of the generated ElegantVideoCard takes the focus target: Tab and Shift+Tab reach it, Enter opens the video
 * through the card's own tap path (triggerCardClick), and Shift+F10 or the context menu key open the card menu that the
 * right click and the overflow button open, anchored on the cover. The ring follows the kit heuristic
 * (DesktopKitFocusState): it shows for keyboard focus only, never after a pointer press.
 *
 * The ring sits outside the cover, which the card shell clips: it is drawn by the card root Box, which does not clip,
 * at the cover rectangle measured in the root. Geometry from the cover edge outwards: a gap of
 * DESKTOP_HOME_COVER_RING_GAP_DP, the inner ring (DESKTOP_KIT_FOCUS_RING_INNER_DP, background colour), then the outer
 * ring (DESKTOP_KIT_FOCUS_RING_OUTER_DP, primary colour), so the primary ring is offset 3 dp from the cover. Widths are
 * snapped to whole pixels. The corners are concentric with the cover shape (top corners rounded, bottom corners square).
 *
 * Both modifiers return the receiver unchanged while card_interactions is OFF: the vendored card has no cover focus.
 */

/** The transparent gap between the cover edge and the inner ring. Gap plus inner ring is the 3 dp offset of the spec. */
internal const val DESKTOP_HOME_COVER_RING_GAP_DP = 2

private val DESKTOP_HOME_COVER_NAVIGATION_KEYS = setOf(
    Key.Tab, Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.Spacebar, Key.Enter,
    Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown,
)

/** java.awt.event.KeyEvent.VK_CONTEXT_MENU (525): the menu key of a Windows keyboard. Compose Key.Menu is a different code. */
private val DESKTOP_HOME_CONTEXT_MENU_KEY = Key(525)

private fun desktopHomeCoverNowMs(): Long = System.nanoTime() / 1_000_000L

/** Focus state, the cover rectangle in the card root and the cover corner radius, shared by the two modifiers. */
@Stable
internal class DesktopHomeCoverFocus {
    var focus by mutableStateOf(DesktopKitFocusState())
    var coverRect by mutableStateOf(Rect.Zero)
    var cornerRadius by mutableStateOf(0.dp)
    internal var rootCoordinates: LayoutCoordinates? = null
    internal var coverCoordinates: LayoutCoordinates? = null

    /** Measures the cover in the root. Only while focused: the ring is the only reader, and scrolling moves both. */
    internal fun measureCover() {
        if (!focus.focused) return
        val root = rootCoordinates?.takeIf { it.isAttached } ?: return
        val cover = coverCoordinates?.takeIf { it.isAttached } ?: return
        val rect = root.localBoundingBoxOf(cover, clipBounds = false)
        if (rect != coverRect) coverRect = rect
    }
}

@Composable
internal fun rememberDesktopHomeCoverFocus(): DesktopHomeCoverFocus = remember { DesktopHomeCoverFocus() }

/** The key action of a focused cover: OPEN for Enter, MENU for Shift+F10 and the menu key, null for anything else. */
internal enum class DesktopHomeCoverKeyAction { OPEN, MENU }

internal fun desktopHomeCoverKeyAction(event: KeyEvent): DesktopHomeCoverKeyAction? {
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
    val key = event.key
    return when {
        !event.isShiftPressed && (key == Key.Enter || key == Key.NumPadEnter) -> DesktopHomeCoverKeyAction.OPEN
        event.isShiftPressed && key == Key.F10 -> DesktopHomeCoverKeyAction.MENU
        !event.isShiftPressed && (key == DESKTOP_HOME_CONTEXT_MENU_KEY || key == Key.Menu) -> DesktopHomeCoverKeyAction.MENU
        else -> null
    }
}

/**
 * Makes the cover a keyboard focus target. onOpen is the card's tap action; onMenu opens the card menu, or is null for a
 * card without one (the right click is disabled for that card as well).
 */
@Composable
internal fun Modifier.desktopHomeCoverFocusTarget(
    state: DesktopHomeCoverFocus,
    cornerRadius: Dp,
    onOpen: () -> Unit,
    onMenu: (() -> Unit)?,
): Modifier {
    if (!desktopCardInteractionsOn()) return this
    val open by rememberUpdatedState(onOpen)
    val menu by rememberUpdatedState(onMenu)
    SideEffect { state.cornerRadius = cornerRadius }
    return this
        .desktopKitOnPointerPress { state.focus = state.focus.onPointerPress(desktopHomeCoverNowMs()) }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key in DESKTOP_HOME_COVER_NAVIGATION_KEYS) {
                state.focus = state.focus.onNavigationKey()
            }
            false
        }
        .onKeyEvent { event ->
            when (desktopHomeCoverKeyAction(event)) {
                DesktopHomeCoverKeyAction.OPEN -> {
                    open()
                    true
                }
                DesktopHomeCoverKeyAction.MENU -> menu?.let { it(); true } ?: false
                null -> false
            }
        }
        .onFocusChanged { focusState ->
            if (focusState.isFocused && !state.focus.focused) {
                state.focus = state.focus.onFocus(desktopHomeCoverNowMs())
                state.measureCover()
            } else if (!focusState.isFocused && state.focus.focused) {
                state.focus = state.focus.onUnfocus()
            }
        }
        .focusable()
        .onGloballyPositioned { coordinates ->
            state.coverCoordinates = coordinates
            state.measureCover()
        }
}

/** Draws the ring of the cover over the card root. Goes on the card root Box, which must not clip. */
@Composable
internal fun Modifier.desktopHomeCoverFocusRing(state: DesktopHomeCoverFocus): Modifier {
    if (!desktopCardInteractionsOn()) return this
    val outerColor = MaterialTheme.colorScheme.primary
    val innerColor = MaterialTheme.colorScheme.background
    return this
        .onPlaced { coordinates -> state.rootCoordinates = coordinates }
        .drawWithContent {
            drawContent()
            val cover = state.coverRect
            if (state.focus.ringVisible && cover.width > 0f && cover.height > 0f) {
                drawDesktopHomeCoverRing(cover, state.cornerRadius.toPx(), outerColor, innerColor)
            }
        }
}

private fun DrawScope.drawDesktopHomeCoverRing(cover: Rect, radiusPx: Float, outerColor: Color, innerColor: Color) {
    val gap = desktopKitSnapStrokePx(DESKTOP_HOME_COVER_RING_GAP_DP * density)
    val inner = desktopKitSnapStrokePx(DESKTOP_KIT_FOCUS_RING_INNER_DP * density)
    val outer = desktopKitSnapStrokePx(DESKTOP_KIT_FOCUS_RING_OUTER_DP * density)
    drawCoverRingStroke(cover, radiusPx, gap + inner / 2f, inner, innerColor)
    drawCoverRingStroke(cover, radiusPx, gap + inner + outer / 2f, outer, outerColor)
}

/** One stroke centred at distance outside the cover, its corners concentric with the top-rounded cover shape. */
private fun DrawScope.drawCoverRingStroke(cover: Rect, radiusPx: Float, distance: Float, width: Float, color: Color) {
    val top = CornerRadius(radiusPx + distance)
    val bottom = CornerRadius(distance)
    val path = Path().apply {
        addRoundRect(RoundRect(cover.inflate(distance), topLeft = top, topRight = top, bottomRight = bottom, bottomLeft = bottom))
    }
    drawPath(path, color, style = Stroke(width = width))
}
