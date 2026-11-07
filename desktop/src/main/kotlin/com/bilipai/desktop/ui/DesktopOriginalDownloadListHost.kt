package com.bilipai.desktop.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.store.DesktopOriginalDownloadListSettings
import com.android.purebilibili.feature.download.DownloadListScreen

val LocalDesktopOriginalDownloadListBindings = staticCompositionLocalOf<DesktopOriginalDownloadListBindings> {
    error("Root must supply its actual download queue, settings and entry owner")
}

private val LocalDownloadContentWidth = staticCompositionLocalOf { 0f }

@Composable
fun desktopOriginalDownloadListScreenWidthDp(): Float = LocalDownloadContentWidth.current

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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            BoxWithConstraints(Modifier.widthIn(max = 1120.dp).fillMaxWidth().fillMaxHeight()) {
                CompositionLocalProvider(
                    LocalDesktopOriginalDownloadListBindings provides bindings,
                    LocalDownloadContentWidth provides maxWidth.value,
                ) {
                    DownloadListScreen(
                onBack = { if (bindings.isOwned()) onBack() },
                onVideoClick = { bvid -> if (bindings.isOwned()) onVideoClick(bvid) },
                onOfflineVideoClick = { taskId -> if (bindings.isOwned()) onOfflineVideoClick(taskId) },
                    )
                }
            }
        }
    }
}
