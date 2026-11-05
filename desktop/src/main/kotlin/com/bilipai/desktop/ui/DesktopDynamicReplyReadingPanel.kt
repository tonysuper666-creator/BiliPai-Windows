package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.android.purebilibili.core.ui.AppModalBottomSheet
import com.bilipai.desktop.ui.modal.DesktopModalPurpose

/** Replaces only the dynamic detail reply's container; its original content and session stay mounted. */
@Composable
internal fun DesktopDynamicReplyReadingPanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val owner = remember { DesktopCommentDialogNavigation() }
    DisposableEffect(owner) { onDispose { owner.close() } }
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    AppModalBottomSheet(purpose = DesktopModalPurpose.READING_PANEL, onDismissRequest = onDismiss) {
        CompositionLocalProvider(
            LocalDesktopCommentDialogOwner provides owner,
            LocalNavigationEventDispatcherOwner provides owner,
        ) {
            com.bilipai.desktop.appearance.DesktopPlatformClipboardScope {
                // K2 supplies a scrolling body. The original reply owns its LazyColumn, so give
                // it the finite viewport and leave all scrolling/pagination inside that list.
                Box(Modifier.fillMaxWidth().height(height)) { content() }
            }
        }
    }
}
