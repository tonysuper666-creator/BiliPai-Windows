package com.bilipai.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.ui.components.AppDropdownMenu
import com.android.purebilibili.core.ui.components.AppDropdownMenuItem
import com.bilipai.desktop.appearance.DesktopWindowsUxCardInteractions
import com.bilipai.desktop.appearance.LocalDesktopWindowsUx
import com.bilipai.desktop.ui.kit.DESKTOP_KIT_RADIUS_CONTROL_DP
import com.bilipai.desktop.ui.kit.DesktopContextMenu
import com.bilipai.desktop.ui.kit.desktopKitDisabledAlpha
import com.bilipai.desktop.ui.kit.desktopKitInteraction

/*
 * The desktop-owned pieces that the generated Home VideoCard calls instead of the vendored dropdown menu (spec 3.6, slice H2).
 * desktop/tools/ux_home_card.py swaps the two imports of the generated card for these two functions, as import aliases, so the
 * call text of the card is the vendored call text (the argument names and the ColumnScope trailing lambda), and it routes the
 * menu labels and the overflow button size through desktopCardMenuLabel and desktopCardOverflowTarget below.
 *
 * The card_interactions flag decides. While it is OFF each function runs the vendored composable or returns the vendored value, so
 * OFF is the vendored behaviour. While it is ON the menu is a compact popup of the kit (a Popup on AppPopupSurface(MENU), no dim and
 * no scrim, flipped and clamped at the window edges) with 36 dp rows. The numbers are in DesktopCardInteractionPolicy.kt.
 */

/** The card_interactions flag in force. A fixture that provides no flags store sees the default, which is ON. */
@Composable
internal fun desktopCardInteractionsOn(): Boolean {
    val settings by LocalDesktopWindowsUx.current.settings.collectAsState()
    return settings.cardInteractions == DesktopWindowsUxCardInteractions.ON
}

/** The text of a menu entry for the flag in force: the plain label while ON, the vendored label while OFF. */
@Composable
internal fun desktopCardMenuLabel(original: String, plain: String): String =
    desktopCardMenuLabelFor(desktopCardInteractionsOn(), original, plain)

/** The edge of the overflow button for the flag in force: the 32 dp target while ON, the vendored size (original) while OFF. */
@Composable
internal fun desktopCardOverflowTarget(original: Dp): Dp =
    desktopCardOverflowTargetDpFor(desktopCardInteractionsOn(), original.value).dp

/**
 * The card menu. The call shape is the one of the vendored AppDropdownMenu call in the generated card: expanded, onDismissRequest,
 * offset and the ColumnScope content. The popup opens at the anchor of the call (the small local box of the card) shifted by offset.
 */
@Composable
internal fun DesktopCardContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!desktopCardInteractionsOn()) {
        AppDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            content = content,
        )
        return
    }
    val density = LocalDensity.current
    val pointer = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    DesktopContextMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        pointer = pointer,
        modifier = modifier,
        width = DESKTOP_CARD_MENU_WIDTH_DP.dp,
        content = content,
    )
}

/** A card menu row: one 36 dp button around the caller's text, with the kit hover wash, hand cursor and keyboard focus ring. */
@Composable
internal fun DesktopCardContextMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!desktopCardInteractionsOn()) {
        AppDropdownMenuItem(text = text, onClick = onClick, modifier = modifier, enabled = enabled)
        return
    }
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .height(DESKTOP_CARD_MENU_ROW_DP.dp)
            .alpha(desktopKitDisabledAlpha(enabled))
            .desktopKitInteraction(source, DESKTOP_KIT_RADIUS_CONTROL_DP.dp, enabled)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        text()
    }
}
