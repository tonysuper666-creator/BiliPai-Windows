// Original source app/src/main/java/com/android/purebilibili/feature/home/components/FloatingBottomBarGeometry.kt
// OriginalLF_SHA256 78b6de6cbe2e8f401d20fa43d3ae9a8d48d95bcd0f7f47c1d4a068e271316ef6
package com.android.purebilibili.feature.home.components



internal const val MIUIX_UPSTREAM_DOCK_SHELL_HEIGHT_DP = 64f

internal fun resolveFloatingDockGeometryScale(
    shellHeightDp: Float,
    referenceShellHeightDp: Float = MIUIX_UPSTREAM_DOCK_SHELL_HEIGHT_DP,
): Float {
    if (shellHeightDp <= 0f || referenceShellHeightDp <= 0f) return 0f
    return (shellHeightDp / referenceShellHeightDp).coerceIn(0f, 1f)
}

internal fun resolveFloatingDockEffectPaddingDp(
    refractionAmountDp: Float,
    pressBloomDp: Float,
): Float = refractionAmountDp.coerceAtLeast(0f) + pressBloomDp.coerceAtLeast(0f)
