package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Spec §6 (K7): conversations beside the chat from 960dp of available width; the list is
 * 280–320dp wide and the chat at most 760dp, in one column below the breakpoint as well. */
internal const val DESKTOP_MESSAGE_SPLIT_MIN_WIDTH_DP = 960
internal const val DESKTOP_MESSAGE_LIST_MIN_WIDTH_DP = 280
internal const val DESKTOP_MESSAGE_LIST_MAX_WIDTH_DP = 320
internal const val DESKTOP_MESSAGE_CHAT_MAX_WIDTH_DP = 760
internal const val DESKTOP_MESSAGE_LIST_PANE_TAG = "message_list_pane"
internal const val DESKTOP_MESSAGE_CHAT_PANE_TAG = "message_chat_pane"
private const val DESKTOP_MESSAGE_LIST_STATE_KEY = "message_list"

/** Widths in dp for [availableWidthDp]; below the breakpoint the list or the chat fills one column. */
internal data class DesktopMessageSplitLayout(val split: Boolean, val listWidthDp: Float, val chatWidthDp: Float)

/** The chat grows to its 760dp cap first; only the width left over widens the list from 280 to 320. */
internal fun desktopMessageSplitLayout(availableWidthDp: Float): DesktopMessageSplitLayout {
    val chatCap = DESKTOP_MESSAGE_CHAT_MAX_WIDTH_DP.toFloat()
    if (availableWidthDp < DESKTOP_MESSAGE_SPLIT_MIN_WIDTH_DP) {
        return DesktopMessageSplitLayout(false, availableWidthDp, minOf(availableWidthDp, chatCap))
    }
    val list = (availableWidthDp - chatCap).coerceIn(DESKTOP_MESSAGE_LIST_MIN_WIDTH_DP.toFloat(),
        DESKTOP_MESSAGE_LIST_MAX_WIDTH_DP.toFloat())
    return DesktopMessageSplitLayout(true, list, minOf(availableWidthDp - list, chatCap))
}

/**
 * The message center's one list-detail container at every width. [chatKey] names the selected
 * conversation the page owner still retains (null: none). The list and the chat keep their
 * composition slots across the breakpoint, so crossing it neither restarts the chat nor drops its
 * draft; a slot that leaves (the list behind a narrow chat, conversation A while B is open) parks
 * its saveable state — list scroll, A's draft — until it comes back, for [stateKey] only.
 */
@Composable
internal fun DesktopMessageListDetail(
    stateKey: Any,
    chatKey: String?,
    list: @Composable () -> Unit,
    chat: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    // A new account starts a new container, so no slot or parked state carries over to it.
    key(stateKey) {
        val holder = rememberSaveableStateHolder()
        BoxWithConstraints(modifier) {
            val layout = desktopMessageSplitLayout(maxWidth.value)
            val listShown = layout.split || chatKey == null
            Layout(
                content = {
                    Box(Modifier.testTag(DESKTOP_MESSAGE_LIST_PANE_TAG).drawBehind {
                        if (layout.split) drawLine(divider, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
                    }) {
                        if (listShown) holder.SaveableStateProvider(DESKTOP_MESSAGE_LIST_STATE_KEY) { list() }
                    }
                    Box(Modifier.testTag(DESKTOP_MESSAGE_CHAT_PANE_TAG)) {
                        if (chatKey != null) holder.SaveableStateProvider(chatKey) { chat() }
                    }
                },
            ) { measurables, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val listWidth = if (!listShown) 0 else if (layout.split) layout.listWidthDp.dp.roundToPx().coerceAtMost(width) else width
                val chatWidth = if (chatKey == null) 0 else layout.chatWidthDp.dp.roundToPx().coerceAtMost(width)
                val listPlaceable = measurables[0].measure(Constraints.fixed(listWidth, height))
                val chatPlaceable = measurables[1].measure(Constraints.fixed(chatWidth, height))
                layout(width, height) {
                    listPlaceable.place(0, 0)
                    val chatRegion = if (layout.split) listWidth else 0
                    chatPlaceable.place(chatRegion + ((width - chatRegion - chatWidth) / 2f).roundToInt(), 0)
                }
            }
        }
    }
}
