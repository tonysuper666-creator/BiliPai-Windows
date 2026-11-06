"""Windows drag-to-copy for the dynamic body text.

On a PC, text is selected by dragging the mouse and copied with Ctrl+C or the right-click menu.
The phone source opens a full-text selection sheet on long press instead, so the body cannot be
selected in place. The dynamic card producer applies this edit to its generated source; a changed
upstream anchor fails generation instead of silently dropping the Windows behaviour. (Private-message
bubbles compile from upstream ChatScreen.kt, so their counterpart is the M0 patch of that file.)
"""


def once(text, old, new):
    if text.count(old) != 1:
        raise ValueError('Windows text selection anchor changed: ' + old.strip().splitlines()[0][:90])
    return text.replace(old, new)


DYNAMIC_RICH_TEXT = '''    val textGestureModifier = Modifier.pointerInput(annotatedText, onBlankTap, copyText) {
        detectTapWithSelectionFriendly(
            onLongPress = {
                if (copyText.isNotBlank()) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showTextSelectionSheet = true
                }
            },
            onTap = onBlankTap?.let { callback -> { _: Offset -> callback() } },
        )
    }
    AppText(
        text = annotatedText,
        inlineContent = inlineContent,
        fontSize = fontSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = overflow,
        color = textColor,
        modifier = modifier.then(textGestureModifier)
    )
'''

WINDOWS_DYNAMIC_RICH_TEXT = '''    // Windows：正文直接拖选，Ctrl+C 或右键「复制」；链接仍由 BasicText 处理，轻触空白仍走转发回调。
    // 全文选择面板只留给触屏长按，鼠标按住停顿后再拖不会被弹窗打断。
    var pressedByMouse by remember { mutableStateOf(false) }
    val textGestureModifier = Modifier
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        .changes.firstOrNull { it.pressed && !it.previousPressed }
                        ?.let { pressedByMouse = it.type == androidx.compose.ui.input.pointer.PointerType.Mouse }
                }
            }
        }
        .pointerInput(annotatedText, onBlankTap, copyText) {
            detectTapWithSelectionFriendly(
                onLongPress = {
                    if (copyText.isNotBlank() && !pressedByMouse) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showTextSelectionSheet = true
                    }
                },
                onTap = onBlankTap?.let { callback -> { _: Offset -> callback() } },
            )
        }
    androidx.compose.foundation.text.selection.SelectionContainer(modifier = modifier) {
        AppText(
            text = annotatedText,
            inlineContent = inlineContent,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
            maxLines = maxLines,
            overflow = overflow,
            color = textColor,
            modifier = textGestureModifier
        )
    }
'''


def dynamic_rich_text(text):
    """RichTextContent in DynamicCard.kt: selectable body, long-press sheet kept for touch."""
    return once(text, DYNAMIC_RICH_TEXT, WINDOWS_DYNAMIC_RICH_TEXT)
