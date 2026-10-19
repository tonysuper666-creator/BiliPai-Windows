package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import com.android.purebilibili.core.ui.AppAlertDialog
import com.android.purebilibili.core.ui.AppContentDialogLayoutPolicy
import com.android.purebilibili.core.ui.resolveAppCompactContentDialogLayoutPolicy

internal const val DESKTOP_FOLLOW_CONFIRMATION_TITLE = "取消关注"
// Tall enough for a three-line message and both buttons plus the title bar; the window stays resizable.
internal const val DESKTOP_FOLLOW_CONFIRMATION_HEIGHT_DP = 300

/** True when the confirmation must live in the owned native player dialog: the Main window exists and is on screen.
 * Without a showing Main window there is nothing a heavyweight video canvas could cover, so the plain alert is used. */
internal fun desktopFollowConfirmationUsesOwnedWindow(mainWindowShowing: Boolean?): Boolean = mainWindowShowing == true

/**
 * Windows presentation of the carried upstream unfollow confirmation (rememberFollowActionWithConfirmation).
 *
 * The generated copy of FollowActionConfirmation.kt imports this function under the name AppAlertDialog, so the
 * upstream body stays byte-for-byte except that one import. The video detail page, the UP preview sheet and the
 * portrait overlay sit above the heavyweight MPV canvas of the Main window, which paints over any in-canvas Compose
 * layer: an ordinary AppAlertDialog there can be partly or fully covered and the user could not confirm an unfollow.
 * The same alert is therefore presented inside the existing fixed-size owned native dialog (the pattern of
 * DesktopHotDanmakuAlertDialog and DesktopWindowsVideoFollowGroupSection) whenever the Main window is showing, and as
 * the plain alert otherwise. The dialog holds no follow authority: the confirm button still runs the caller's own,
 * already guarded follow callback, and closing the window only dismisses the pending choice.
 */
@Composable
internal fun DesktopWindowsFollowConfirmationDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    shape: Shape? = null,
    containerColor: Color? = null,
    tonalElevation: Dp? = null,
    properties: DialogProperties = DialogProperties(),
    contentLayout: AppContentDialogLayoutPolicy = resolveAppCompactContentDialogLayoutPolicy(),
) {
    val mainWindow = LocalDesktopWindowsPlayerWindow.current
    val original: @Composable () -> Unit = {
        AppAlertDialog(onDismissRequest, modifier, icon, title, text, confirmButton,
            dismissButton, shape, containerColor, tonalElevation, properties, contentLayout)
    }
    if (desktopFollowConfirmationUsesOwnedWindow(mainWindow?.isShowing)) {
        DesktopWindowsPlayerDialog(DESKTOP_FOLLOW_CONFIRMATION_TITLE, onDismissRequest,
            dismissOnEscape = properties.dismissOnBackPress,
            preferredHeightDp = DESKTOP_FOLLOW_CONFIRMATION_HEIGHT_DP, content = original)
    } else original()
}
