// OriginalSource: app/src/main/java/com/android/purebilibili/feature/home/components/HomeSelectionIndicatorPolicy.kt
// OriginalSHA256: ab972367ac0047d7d832269660e91aef7e0c1351d314eb047a445bdfcfebf4b5
package com.android.purebilibili.feature.home.components

import com.android.purebilibili.core.theme.AppUiStyle

internal enum class HomeSelectionIndicatorStyle {
    CAPSULE,
    MD3_UNDERLINE,
}

internal fun resolveHomeSelectionIndicatorStyle(
    uiStyle: AppUiStyle,
    liquidGlassEnabled: Boolean,
    forceMaterialUnderline: Boolean = false,
): HomeSelectionIndicatorStyle = when {
    forceMaterialUnderline -> HomeSelectionIndicatorStyle.MD3_UNDERLINE
    liquidGlassEnabled -> HomeSelectionIndicatorStyle.CAPSULE
    uiStyle == AppUiStyle.MIUIX -> HomeSelectionIndicatorStyle.CAPSULE
    else -> HomeSelectionIndicatorStyle.MD3_UNDERLINE
}
