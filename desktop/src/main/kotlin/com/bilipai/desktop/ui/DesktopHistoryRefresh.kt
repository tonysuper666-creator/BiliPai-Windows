package com.bilipai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.android.purebilibili.core.refresh.HistoryRefreshSuppression

/** Call once in the persistent window shell.
 *
 * History lists subscribe to the refresh bus themselves: the original HistoryViewModel refreshes watch
 * progress in place from the local progress cache and reads the server order once per browsing session
 * (see DesktopHistoryBrowsingSession). The shell therefore no longer invalidates any retained history
 * page on a signal, which was the pre-0.3.9 reload-on-change behaviour. It only holds the original
 * nested suppression while video details are visible: signals are then kept and replayed once. */
@Composable
fun DesktopHistoryRefreshEffects(videoDetailsVisible: Boolean) {
    DisposableEffect(videoDetailsVisible) {
        if (videoDetailsVisible) HistoryRefreshSuppression.suppress()
        onDispose { if (videoDetailsVisible) HistoryRefreshSuppression.resume() }
    }
}
