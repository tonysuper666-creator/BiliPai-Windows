package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.android.purebilibili.core.ui.components.AppSurface

/** Local replacement of the pool's sheet host inside its actual owned DialogWindow.
 * The complete original pool (including its action/report dialogs) stays in that Window.
 * The bounded native client owns height; no sheet layer is placed above the MPV Canvas. */
@Composable
internal fun DesktopWindowsDanmakuPoolSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DesktopCommentDialogNavigationHost {
        val back = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(state = back, isBackEnabled = true, onBackCompleted = onDismissRequest)
        AppSurface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxSize(), content = content)
        }
    }
}
