package com.bilipai.desktop.ui.kit

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.PlainTooltip as MiuixPlainTooltip
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox as MiuixTooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults as MiuixTooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState as rememberMiuixTooltipState

/*
 * Tooltip of the kit (spec 5.1): the Miuix TooltipBox, shown after a delay while the pointer is over the content or
 * the keyboard focus is inside it, and hidden on leave, on blur and on a press. The Miuix box runs with
 * enableUserInput = false and a persistent state that this wrapper drives, which is the shape of the existing
 * player icon button. That keeps its long-press action semantics out of the tree: the wrapper adds no action, so a
 * control inside it keeps exactly the one clickable it declares.
 *
 * The wrapper's own structure never changes with its arguments (enabled, text), so a content node that is already
 * focused or hovered is never remounted when a tooltip becomes available.
 */
@Composable
internal fun DesktopTooltipBox(
    text: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    enabled: Boolean = true,
    delayMillis: Int = DESKTOP_KIT_TOOLTIP_DELAY_MS,
    positioning: TooltipAnchorPosition = TooltipAnchorPosition.Below,
    content: @Composable () -> Unit,
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val tooltipState = rememberMiuixTooltipState(isPersistent = true)
    val active = enabled && text.isNotEmpty() && (hovered || focused)
    LaunchedEffect(active) { if (!active) dismissed = false }
    val visible = active && !dismissed
    LaunchedEffect(visible, delayMillis) {
        if (visible) {
            delay(delayMillis.toLong())
            tooltipState.show()
        } else {
            tooltipState.dismiss()
        }
    }
    val tooltipColor = MiuixTooltipDefaults.plainTooltipContentColor
    MiuixTooltipBox(
        positionProvider = MiuixTooltipDefaults.rememberTooltipPositionProvider(positioning, 6.dp),
        tooltip = {
            MiuixPlainTooltip {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text, color = tooltipColor, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (shortcut != null) {
                        Text(
                            shortcut, color = tooltipColor.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        state = tooltipState,
        modifier = modifier
            .hoverable(hoverSource)
            .onFocusChanged { focused = it.hasFocus }
            .desktopKitOnPointerPress { dismissed = true },
        focusable = false,
        enableUserInput = false,
        content = content,
    )
}
