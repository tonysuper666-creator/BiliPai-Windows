package com.bilipai.desktop.ui
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.android.purebilibili.core.ui.AppAlertDialog

/** Windows dialog window; exact upstream text-selection content stays inside. */
@Composable internal fun DesktopDynamicTextSelectionSheet(onDismissRequest:()->Unit,content:@Composable ColumnScope.()->Unit) {
    AppAlertDialog(onDismissRequest=onDismissRequest,text={Column(content=content)},confirmButton={})
}

/**
 * Drag-to-copy text for comments, dynamic bodies and chat bubbles. The selection takes focus from a
 * pointer press, so Ctrl+C copies what was dragged, but Tab never stops on the text: keyboard focus
 * keeps moving between the visible buttons and links.
 */
@Composable fun DesktopSelectionContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    SelectionContainer(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Press -> pressed = true
                            PointerEventType.Release -> if (!focused) pressed = false
                        }
                    }
                }
            }
            .onFocusChanged { focused = it.hasFocus; if (!it.hasFocus) pressed = false }
            .focusProperties { canFocus = pressed || focused },
        content = content,
    )
}
