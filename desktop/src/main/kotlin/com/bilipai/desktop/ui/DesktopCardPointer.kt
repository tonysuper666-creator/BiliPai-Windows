package com.bilipai.desktop.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput

/*
 * The pointer modifiers that the generated Home VideoCard gets (spec 3.6 and 6.7, slice H2), both behind the card_interactions flag:
 * while it is OFF each returns the modifier it was called on, so OFF is the vendored behaviour.
 */

/**
 * Opens the card menu on a right click. The handler watches the Initial pass, which reaches this node before any child: it takes a
 * press of the secondary button alone (desktopCardOwnsSecondaryPress), consumes it, calls onSecondaryClick with the press position
 * in the coordinates of this node, and then consumes the release of the same pointer. A consumed press and release do not reach the
 * tap detector of the cover or of the title (their awaitFirstDown skips consumed changes), so a right click is never read as a tap
 * that would open the video. Any other press is left alone and the watch ends at once. enabled is false for a card without a menu,
 * which keeps the vendored behaviour for it.
 */
@Composable
internal fun Modifier.desktopCardSecondaryClick(enabled: Boolean, onSecondaryClick: (Offset) -> Unit): Modifier {
    val latest by rememberUpdatedState(onSecondaryClick)
    if (!desktopCardSecondaryClickActive(enabled, desktopCardInteractionsOn())) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val buttons = currentEvent.buttons
            if (!desktopCardOwnsSecondaryPress(buttons.isPrimaryPressed, buttons.isSecondaryPressed)) return@awaitEachGesture
            down.consume()
            latest(down.position)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                when (desktopCardSecondaryFollow(change != null, change?.pressed == true)) {
                    DesktopCardSecondaryFollow.WAIT -> Unit
                    DesktopCardSecondaryFollow.CONSUME_AND_END -> {
                        change?.consume()
                        return@awaitEachGesture
                    }
                    DesktopCardSecondaryFollow.END -> return@awaitEachGesture
                }
            }
        }
    }
}

/** The hand cursor over the whole card. The vendored card sets no cursor of its own, so this is the one icon of the card. */
@Composable
internal fun Modifier.desktopCardHandCursor(): Modifier =
    if (desktopCardInteractionsOn()) pointerHoverIcon(PointerIcon.Hand) else this
