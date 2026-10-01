// Original source design-system/src/main/java/com/android/purebilibili/core/ui/AdaptiveScaffoldPolicy.kt
// LF SHA256 520d1a4ec22d77c9a7460cd647a37e35603eb85980c21bf63ddb9cb433a5769f
package com.android.purebilibili.core.ui

import com.android.purebilibili.core.theme.AppUiStyle

enum class AdaptiveScaffoldRenderer {
    MATERIAL3_SCAFFOLD,
    MIUIX_SCAFFOLD_WITH_POPUP_HOST
}

fun resolveAdaptiveScaffoldRenderer(
    uiStyle: AppUiStyle
): AdaptiveScaffoldRenderer = when (uiStyle) {
    AppUiStyle.MIUIX -> AdaptiveScaffoldRenderer.MIUIX_SCAFFOLD_WITH_POPUP_HOST
    AppUiStyle.MATERIAL3 -> AdaptiveScaffoldRenderer.MATERIAL3_SCAFFOLD
}

fun shouldMountMiuixPopupHostOnAdaptiveScaffold(
    uiStyle: AppUiStyle
): Boolean = resolveAdaptiveScaffoldRenderer(uiStyle) ==
    AdaptiveScaffoldRenderer.MIUIX_SCAFFOLD_WITH_POPUP_HOST
