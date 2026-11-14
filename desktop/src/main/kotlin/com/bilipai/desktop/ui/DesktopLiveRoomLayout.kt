package com.bilipai.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.live.LiveDanmakuItem
import com.bilipai.desktop.appearance.DesktopWindowsUxDetailsPanel

internal const val DESKTOP_LIVE_STAGE_TAG = "desktop-live-stage"
internal const val DESKTOP_LIVE_CHAT_TAG = "desktop-live-chat"
internal const val DESKTOP_LIVE_CHAT_SHOW = "展开聊天"
internal const val DESKTOP_LIVE_CHAT_HIDE = "收起聊天"

/** Whether the room's own chat editor holds focus. A Compose editor takes the typed
 * character, not KeyDown, so the room shortcut must ask the editor before acting. */
internal class DesktopLiveChatInputFocus {
    var focused by mutableStateOf(false)
        internal set
}

/** The pointed row only: never the newest message or the current reply target. */
internal fun desktopLiveDanmakuCopyText(item: LiveDanmakuItem) = "uid=${item.uid}, uname=${item.uname}, text=${item.text}"

internal fun ClipboardManager.copyDesktopLiveDanmaku(item: LiveDanmakuItem) =
    setText(AnnotatedString(desktopLiveDanmakuCopyText(item)))

/** Panel width of the live chat: spec 6 keeps it 340..480 (22% of the window), then the player
 * policy fits it beside a stage of at least 480 or reports that it cannot dock. */
internal fun desktopLiveChatLayout(widthDp: Int, heightDp: Int, panelDefaultOpen: Boolean, panel: DesktopWindowsUxDetailsPanel) =
    resolveDesktopPlayerLayout(widthDp, heightDp, panelDefaultOpen, panel = panel,
        userPanelWidthDp = (widthDp.toLong() * DESKTOP_PLAYER_PANEL_DEFAULT_PERCENT / 100).toInt()
            .coerceIn(DESKTOP_PLAYER_PANEL_DEFAULT_LOW_DP, DESKTOP_PLAYER_PANEL_DEFAULT_HIGH_DP))

/**
 * The live room: stage plus chat (spec 6). AUTO opens the chat only when the room is entered in a wide
 * window (desktopPlayerPanelDefaultOpen); a window drag never flips it, the button does. An open chat that
 * cannot dock goes below the stage. Stage and chat are siblings, so the chat list scrolls on its own.
 * Space toggles playback while focus is anywhere in the room except the chat editor. The native video
 * surface owns AWT focus once clicked; that key path belongs to the window fallback, not to this host.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopLiveRoomStage(
    roomKey: Any,
    onTogglePause: (() -> Unit)?,
    modifier: Modifier = Modifier,
    chat: (@Composable (Modifier, DesktopLiveChatInputFocus) -> Unit)?,
    stage: @Composable (Modifier) -> Unit,
) {
    val inputFocus = remember(roomKey) { DesktopLiveChatInputFocus() }
    val stageFocus = remember(roomKey) { FocusRequester() }
    val togglePause by rememberUpdatedState(onTogglePause)
    var panel by remember(roomKey) { mutableStateOf(DesktopWindowsUxDetailsPanel.AUTO) }
    BoxWithConstraints(modifier.onKeyEvent { event ->
        val toggle = togglePause
        if (event.key != Key.Spacebar || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
            event.isShiftPressed || inputFocus.focused || toggle == null) false
        else { if (event.type == KeyEventType.KeyDown) toggle(); true }
    }) {
        val widthDp = maxWidth.value.toInt()
        val heightDp = maxHeight.value.toInt()
        val panelDefaultOpen = remember(roomKey) { desktopPlayerPanelDefaultOpen(widthDp) }
        val wanted = chat != null && desktopPlayerPanelWanted(panel, panelDefaultOpen)
        val chatLayout = desktopLiveChatLayout(widthDp, heightDp, panelDefaultOpen, panel)
        val stageModifier = Modifier.testTag(DESKTOP_LIVE_STAGE_TAG).focusRequester(stageFocus).focusable()
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { stageFocus.requestFocus() }
        LaunchedEffect(roomKey) { stageFocus.requestFocus() }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat != null) Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    panel = if (wanted) DesktopWindowsUxDetailsPanel.CLOSED else DesktopWindowsUxDetailsPanel.OPEN
                }) { Text(if (wanted) DESKTOP_LIVE_CHAT_HIDE else DESKTOP_LIVE_CHAT_SHOW) }
            }
            // The stage is always the first child of one Layout, so opening, closing or re-docking the chat
            // never recreates the player surface; only the chat child comes and goes.
            Layout(content = {
                Box(stageModifier) { stage(Modifier.fillMaxSize()) }
                if (wanted) chat!!(Modifier.testTag(DESKTOP_LIVE_CHAT_TAG), inputFocus)
            }, modifier = Modifier.fillMaxWidth().weight(1f)) { measurables, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val gap = 12.dp.roundToPx()
                val chatMeasurable = measurables.getOrNull(1)
                when {
                    chatMeasurable == null -> {
                        val stagePlaceable = measurables[0].measure(Constraints.fixed(w, h))
                        layout(w, h) { stagePlaceable.place(0, 0) }
                    }
                    chatLayout.docked -> {
                        val chatWidth = chatLayout.panelWidthDp.dp.roundToPx().coerceAtMost(w)
                        val stageWidth = (w - chatWidth - gap).coerceAtLeast(0)
                        val stagePlaceable = measurables[0].measure(Constraints.fixed(stageWidth, h))
                        val chatPlaceable = chatMeasurable.measure(Constraints.fixed(chatWidth, h))
                        layout(w, h) { stagePlaceable.place(0, 0); chatPlaceable.place(w - chatWidth, 0) }
                    }
                    else -> {
                        val stageHeight = ((h - gap) / 2).coerceAtLeast(0)
                        val chatHeight = (h - stageHeight - gap).coerceAtLeast(0)
                        val stagePlaceable = measurables[0].measure(Constraints.fixed(w, stageHeight))
                        val chatPlaceable = chatMeasurable.measure(Constraints.fixed(w, chatHeight))
                        layout(w, h) { stagePlaceable.place(0, 0); chatPlaceable.place(0, h - chatHeight) }
                    }
                }
            }
        }
        if (!wanted) SideEffect { inputFocus.focused = false }
    }
}

/** The live chat editor. It reports its own focus so the room shortcut never acts while typing. */
@Composable
internal fun DesktopLiveChatInput(
    message: String, onMessageChange: (String) -> Unit, enabled: Boolean, maxLength: Int?,
    onSend: () -> Unit, inputFocus: DesktopLiveChatInputFocus?, modifier: Modifier = Modifier,
) {
    val focus = inputFocus
    androidx.compose.material3.OutlinedTextField(message, onMessageChange,
        modifier.fillMaxWidth().onFocusChanged { focus?.focused = it.isFocused },
        enabled = enabled, singleLine = true, label = { Text("发送直播弹幕") },
        supportingText = { if (maxLength != null) Text("${message.length}/$maxLength") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }))
}
