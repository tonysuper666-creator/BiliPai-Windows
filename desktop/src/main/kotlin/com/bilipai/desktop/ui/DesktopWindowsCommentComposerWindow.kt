package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The sole original CommentInputDialog calls this instead of its Compose
 * layer only for the captured ordinary-video presentation. Legacy callers
 * retain the exact original dialog; complete original content is reused. */
@Composable
internal fun DesktopWindowsCommentComposerWindow(
    onDismissRequest: () -> Unit,
    properties: DialogProperties,
    content: @Composable () -> Unit,
) {
    val presentation = LocalDesktopWindowsCommentPresentation.current
    if (presentation == null) Dialog(onDismissRequest, properties = properties, content = content)
    else if (presentation.canPresentNative()) {
        CompositionLocalProvider(LocalDesktopWindowsPlayerWindow provides presentation.nativeOwner) {
            DesktopWindowsPlayerDialog("发表评论", { presentation.dispatch(onDismissRequest) },
                dismissOnEscape = properties.dismissOnBackPress) {
                // Existing measured owner publishes THIS actual client and
                // inherited density; no second display/configuration authority.
                DesktopDetailWindow(content = content)
            }
        }
    }
}
