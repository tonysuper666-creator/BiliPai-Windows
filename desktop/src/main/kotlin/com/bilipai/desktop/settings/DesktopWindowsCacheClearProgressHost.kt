package com.bilipai.desktop.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.android.purebilibili.feature.settings.CacheClearAnimationDialog
import com.android.purebilibili.feature.settings.CacheClearProgress
import com.bilipai.desktop.ui.DesktopDetailWindow
import com.bilipai.desktop.ui.DesktopWindowsPlayerDialog

/** Presentation only. The real storage owner publishes progress after its clear
 * operation; neither character completion nor closing a window can clear caches.
 */
@Composable
internal fun DesktopWindowsCacheClearProgressHost(
    progress: CacheClearProgress,
    stillOwned: () -> Boolean,
    isCurrent: () -> Boolean,
    onDismiss: () -> Unit,
) {
    if (!stillOwned() || !isCurrent()) return
    val dismiss = {
        if (canDismissDesktopCacheClearProgress(progress, stillOwned, isCurrent)) onDismiss()
    }
    DesktopWindowsPlayerDialog(
        title = "清理缓存",
        onDismissRequest = dismiss,
        dismissOnEscape = progress.isComplete,
        preferredHeightDp = 480,
    ) {
        DesktopDetailWindow {
            // Each actual progress publication owns its original completion timer.
            // Retirement disposes this presentation, including that delayed callback.
            key(DesktopCacheClearProgressIdentity(progress)) {
                CacheClearAnimationDialog(progress = progress, onDismiss = dismiss)
            }
        }
    }
}

internal fun canDismissDesktopCacheClearProgress(
    progress: CacheClearProgress,
    stillOwned: () -> Boolean,
    isCurrent: () -> Boolean,
): Boolean = progress.isComplete && stillOwned() && isCurrent()

private class DesktopCacheClearProgressIdentity(private val progress: CacheClearProgress) {
    override fun equals(other: Any?): Boolean = other is DesktopCacheClearProgressIdentity && progress === other.progress
    override fun hashCode(): Int = System.identityHashCode(progress)
}
