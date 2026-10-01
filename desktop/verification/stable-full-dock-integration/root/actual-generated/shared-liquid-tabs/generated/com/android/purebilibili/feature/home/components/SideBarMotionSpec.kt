// OriginalSource: app/src/main/java/com/android/purebilibili/feature/home/components/SideBarMotionSpec.kt
// OriginalSHA256: 0b89a76d5302fc73dc5a7f8bb4dce299f512afc6ad29620d5c2b60d40bd2d9cf
package com.android.purebilibili.feature.home.components


internal const val FloatingBottomBarSelectionScale = 1.1f

internal fun resolveNavigationIconCrossScale(
    enabled: Boolean,
    coverage: Float,
): Float {
    if (!enabled) return 1f
    val progress = coverage.coerceIn(0f, 1f)
    // Cross-scale is a transition accent, not a persistent selected state. A sine arc keeps
    // both endpoints at the icon's authored size and reaches the enlargement peak only while
    // the indicator is travelling between destinations.
    val transitionArc = kotlin.math.sin(Math.PI.toFloat() * progress)
    return androidx.compose.ui.util.lerp(1f, FloatingBottomBarSelectionScale, transitionArc)
}
