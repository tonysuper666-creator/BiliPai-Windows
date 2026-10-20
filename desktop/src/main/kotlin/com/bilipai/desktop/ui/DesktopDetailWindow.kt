package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.android.purebilibili.core.util.AppDisplayContext
import com.android.purebilibili.core.util.AppFoldingFeatureInfo
import com.android.purebilibili.core.util.AppWindowAdaptiveInfo
import com.android.purebilibili.core.util.LocalAppWindowAdaptiveInfo
import com.android.purebilibili.core.util.LocalWindowSizeClass
import com.android.purebilibili.core.util.WindowSizeClass
import com.android.purebilibili.core.util.resolveWindowHeightSizeClass
import com.android.purebilibili.core.util.resolveWindowWidthSizeClass

/** Window measurement is actual Compose constraints; this host has no Android hinge sensor.
 *
 * Input flags (spec 3.8, slice X5, decision D15). A Windows desktop always has a mouse, so [precisePointerConnected] defaults to
 * true: the vendored input policy derives hover effects and pointer focus from it, and a page that starts reading either should
 * find them on. [hardwareKeyboardConnected] stays false by default: its only readers are the key handlers of the vendored
 * VideoPlayerSection and FullscreenPlayerOverlay, and the root site that takes these defaults can mount the original video
 * route that hosts the former, which would then compete with the window-level player shortcuts. The Bangumi player leaf passes
 * both flags as true on its own. The inventory behind both defaults is desktop/tools/ux/test_ux_detail_window_inventory.py.
 */
@Composable
fun DesktopDetailWindow(
    modifier: Modifier = Modifier,
    precisePointerConnected: Boolean = true,
    hardwareKeyboardConnected: Boolean = false,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        require(maxWidth.value.isFinite() && maxHeight.value.isFinite()) {
            "Dynamic detail requires a bounded window"
        }
        val size = remember(maxWidth,maxHeight) {
            WindowSizeClass(resolveWindowWidthSizeClass(maxWidth),
                resolveWindowHeightSizeClass(maxHeight),maxWidth,maxHeight)
        }
        val adaptive = remember(size,precisePointerConnected,hardwareKeyboardConnected) {
            AppWindowAdaptiveInfo(size, AppFoldingFeatureInfo(),
                AppDisplayContext(size.widthDp.value.toInt(),size.heightDp.value.toInt()),
                precisePointerConnected,hardwareKeyboardConnected)
        }
        CompositionLocalProvider(LocalWindowSizeClass provides size,
            LocalAppWindowAdaptiveInfo provides adaptive,content = content)
    }
}
