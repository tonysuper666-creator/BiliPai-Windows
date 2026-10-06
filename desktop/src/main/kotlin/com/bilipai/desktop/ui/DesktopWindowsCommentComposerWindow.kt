package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.android.purebilibili.core.util.LocalWindowSizeClass
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
                DesktopDetailWindow {
                    CompositionLocalProvider(LocalDesktopNativeCommentComposerClient provides true, content = content)
                }
            }
        }
    }
}

/** Presentation geometry only. Set inside the actual native comment client;
 * other callers retain the complete original bottom-sheet sizing. */
internal val LocalDesktopNativeCommentComposerClient = staticCompositionLocalOf { false }

@Composable
internal fun desktopCommentComposerSurfaceHeight(): Modifier =
    if (LocalDesktopNativeCommentComposerClient.current) Modifier.fillMaxHeight()
    else Modifier.wrapContentHeight()

@Composable
internal fun desktopCommentComposerColumnHeight(): Modifier =
    if (LocalDesktopNativeCommentComposerClient.current) Modifier.fillMaxHeight() else Modifier

@Composable
internal fun ColumnScope.desktopCommentComposerInputHeight(min: Dp, max: Dp): Modifier =
    if (LocalDesktopNativeCommentComposerClient.current) Modifier.weight(1f)
    else Modifier.heightIn(min = min, max = max)

@Composable
internal fun desktopCommentComposerClientHeightDp(originalHeightDp: Int): Int =
    if (LocalDesktopNativeCommentComposerClient.current) LocalWindowSizeClass.current.heightDp.value.toInt()
    else originalHeightDp
