package com.android.purebilibili.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Content states can be prominent; short windows keep room for copy and retry controls. */
@Composable
internal fun maidStateIllustrationSize(): Dp {
    val window = com.android.purebilibili.core.util.LocalAppWindowAdaptiveInfo.current.windowSizeClass
    return when {
        window.heightDp < 480.dp -> 128.dp
        window.widthDp >= 600.dp -> 220.dp
        else -> 200.dp
    }
}
