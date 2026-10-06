package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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

/** Only the native client keeps attachments in its existing toolbar budget.
 * The original tool/send slots and image callbacks retain their original owners.
 * Both scroll viewports receive finite Row weight; no lazy list is nested inside
 * an unbounded horizontal scroller. Legacy callers retain the original row. */
@Composable
internal fun DesktopCommentComposerToolbar(
    modifier: Modifier,
    toolHeight: Dp,
    toolSpacing: Dp,
    attachments: (@Composable RowScope.() -> Unit)?,
    tools: @Composable RowScope.() -> Unit,
    send: @Composable () -> Unit,
) {
    val native = LocalDesktopNativeCommentComposerClient.current
    Row(modifier.then(if (native) Modifier.height(toolHeight) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(toolSpacing), content = tools)
        if (native && attachments != null) {
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(.6f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp), content = attachments)
        }
        Spacer(Modifier.width(8.dp))
        send()
    }
}

@Composable
internal fun desktopCommentComposerImagePanelBudget(imageCount: Int): Int =
    if (LocalDesktopNativeCommentComposerClient.current || imageCount == 0) 0 else 112
