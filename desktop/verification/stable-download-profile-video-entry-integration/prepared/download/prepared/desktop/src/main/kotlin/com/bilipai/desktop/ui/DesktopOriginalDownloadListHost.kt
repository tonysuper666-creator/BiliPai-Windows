package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.android.purebilibili.core.store.DesktopOriginalDownloadListSettings
import com.android.purebilibili.feature.download.DownloadListScreen

val LocalDesktopOriginalDownloadListBindings = staticCompositionLocalOf<DesktopOriginalDownloadListBindings> {
    error("Root must supply its actual download queue, settings and entry owner")
}

@Composable
fun desktopOriginalDownloadListScreenWidthDp(): Float =
    LocalWindowInfo.current.containerSize.width / LocalDensity.current.density

@Composable
fun DesktopOriginalDownloadListHost(
    bindings: DesktopOriginalDownloadListBindings,
    onBack: () -> Unit,
    onVideoClick: (String) -> Unit,
    onOfflineVideoClick: (String) -> Unit,
) {
    if (!bindings.isOwned()) return
    val customPath by DesktopOriginalDownloadListSettings.getDownloadPath(bindings.context).collectAsState(initial = null)
    val exportUri by DesktopOriginalDownloadListSettings.getDownloadExportTreeUri(bindings.context).collectAsState(initial = null)
    LaunchedEffect(bindings, customPath, exportUri) {
        bindings.reportUnavailableLocation(customPath, exportUri)
    }
    key(bindings) {
        CompositionLocalProvider(LocalDesktopOriginalDownloadListBindings provides bindings) {
            DownloadListScreen(
                onBack = { if (bindings.isOwned()) onBack() },
                onVideoClick = { bvid -> if (bindings.isOwned()) onVideoClick(bvid) },
                onOfflineVideoClick = { taskId -> if (bindings.isOwned()) onOfflineVideoClick(taskId) },
            )
        }
    }
}
