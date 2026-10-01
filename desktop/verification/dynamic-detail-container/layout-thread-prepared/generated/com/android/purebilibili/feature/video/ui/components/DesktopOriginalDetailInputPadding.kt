// Original source app/src/main/java/com/android/purebilibili/feature/video/ui/components/BottomInputBar.kt
// LF SHA256 11bded8eb078551356ee7c392102810f6703552786074e5574a751d1d5b2f4c4
package com.android.purebilibili.feature.video.ui.components

import androidx.compose.ui.unit.*
import com.android.purebilibili.core.store.resolveGlobalLiquidGlassReuseEnabled

internal fun shouldUseFloatingLiquidBottomInputBar(
    androidNativeLiquidGlassEnabled: Boolean
): Boolean = resolveGlobalLiquidGlassReuseEnabled(androidNativeLiquidGlassEnabled)

/** The comment bar follows the bottom-bar blur preference when liquid glass is not active. */

internal fun resolveBottomInputBarContentBottomPadding(
    showBar: Boolean,
    floatingLiquidGlass: Boolean,
    showActionButtonsFallback: Boolean
): Dp {
    if (!showBar) {
        return if (showActionButtonsFallback) 84.dp else 12.dp
    }
    return if (floatingLiquidGlass) 112.dp else 96.dp
}
