package com.bilipai.desktop.ui
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
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
            // Only the container's own focus: while a link inside it is focused, Shift+Tab must not stop on the text.
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) pressed = false }
            .focusProperties { canFocus = pressed || focused },
        content = content,
    )
}

/**
 * Right-click on selectable text with nothing selected goes to [onWithoutSelection] (a chat bubble's own message
 * actions); with a selection, or without [onWithoutSelection], the text menu opens as usual.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun DesktopTextSecondaryClick(onWithoutSelection: (() -> Unit)?, content: @Composable () -> Unit) {
    val outer = LocalTextContextMenu.current
    val action by rememberUpdatedState(onWithoutSelection)
    val menu = remember(outer) {
        object : TextContextMenu {
            @Composable override fun Area(textManager: TextContextMenu.TextManager, state: ContextMenuState,
                content: @Composable () -> Unit) = outer.Area(textManager, state) {
                // Inside the menu's own right-click detector, so it sees the press first and can keep the menu shut.
                Box(Modifier.pointerInput(textManager) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val onClick = action
                            if (onClick != null && event.type == PointerEventType.Press && event.buttons.isSecondaryPressed &&
                                event.changes.none { it.isConsumed } && textManager.selectedText.isEmpty()) {
                                onClick()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }) { content() }
            }
        }
    }
    CompositionLocalProvider(LocalTextContextMenu provides menu, content = content)
}
