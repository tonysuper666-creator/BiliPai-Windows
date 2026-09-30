package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.android.purebilibili.core.refresh.HistoryRefreshBus
import com.android.purebilibili.core.refresh.HistoryRefreshSuppression

/** Call once in the persistent window shell, alongside its per-account browse memory. */
@Composable
fun DesktopHistoryRefreshEffects(memory: DesktopBrowseMemory, videoDetailsVisible: Boolean) {
    LaunchedEffect(memory) { HistoryRefreshBus.changes.collect { memory.invalidateCloudHistory() } }
    DisposableEffect(videoDetailsVisible) {
        if (videoDetailsVisible) HistoryRefreshSuppression.suppress()
        onDispose { if (videoDetailsVisible) HistoryRefreshSuppression.resume() }
    }
}
