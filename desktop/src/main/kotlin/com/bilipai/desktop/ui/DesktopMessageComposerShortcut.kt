package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.input.TextFieldValue

/** K5 input safety, as on the dynamic inline reply: one Ctrl+Enter press sends once (auto-repeat
 * and its typed newline are swallowed), never while the IME is composing, never while busy. A
 * plain Enter stays the field's own newline. */
internal class DesktopMessageSendShortcut {
    private var enterDown = false
    private var enterClaimed = false

    fun onPreviewKeyEvent(event: KeyEvent, text: TextFieldValue, busy: Boolean, send: () -> Unit): Boolean {
        if (enterClaimed && event.type == KeyEventType.Unknown &&
            (event.utf16CodePoint == '\n'.code || event.utf16CodePoint == '\r'.code)) return true
        if (event.key != Key.Enter && event.key != Key.NumPadEnter) return false
        return when (event.type) {
            KeyEventType.KeyUp -> enterClaimed.also { enterDown = false; enterClaimed = false }
            KeyEventType.KeyDown -> {
                val first = !enterDown
                enterDown = true
                val shortcut = event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
                if (shortcut) enterClaimed = true
                if (first && shortcut && !busy && text.composition == null && text.text.isNotBlank()) send()
                enterClaimed
            }
            else -> enterClaimed
        }
    }
}

/** For an ancestor of the private-message input only, so focus elsewhere never sends. */
@Composable
internal fun rememberDesktopMessageSendShortcut(text: TextFieldValue, busy: Boolean, onSend: () -> Unit): Modifier {
    val shortcut = remember { DesktopMessageSendShortcut() }
    val currentText by rememberUpdatedState(text)
    val currentBusy by rememberUpdatedState(busy)
    val currentSend by rememberUpdatedState(onSend)
    return Modifier.onPreviewKeyEvent { shortcut.onPreviewKeyEvent(it, currentText, currentBusy, currentSend) }
}
