package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** These original control-bar callsites use once or forever. Same installed decoder/lifecycle. */
@Composable
internal fun DesktopOriginalPlayerSkinAsset(
    path: String,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    iterations: Int = Int.MAX_VALUE,
    contentDescription: String? = null,
) {
    require(iterations == 1 || iterations == Int.MAX_VALUE) { "Finite multi-loop skin transport is unavailable" }
    DesktopUiSkinAsset(path, modifier.size(size), contentScale = ContentScale.Fit, loop = iterations == Int.MAX_VALUE)
}
