// Original source app/src/main/java/com/android/purebilibili/core/ui/CommentWindowNavigation.kt
// LF SHA256 31d4d3ca4c3f9e2bb35b70cc4f4cf03b542c92e3e62ca785a1f8a2cb4ec15a1b
package com.android.purebilibili.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.bilipai.desktop.ui.LocalDesktopCommentDialogOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/** Bind comment handlers to the dialog receiving the gesture, not the underlying route. */
@Composable
internal fun CommentWindowNavigation(content: @Composable () -> Unit) {
    val owner = checkNotNull(LocalDesktopCommentDialogOwner.current) {
        "Comment dialog must provide a window navigation dispatcher"
    }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner, content = content)
}
