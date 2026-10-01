// Original source app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoCommentSheetHost.kt
// LF SHA256 927d1eb0ef4b75e5f2467392d22b9fd5f067e0016b7628d97decf59ababcdd56
package com.android.purebilibili.feature.video.ui.components


internal fun resolveCommentThreadPredictiveBackOffsetY(
    progress: Float,
    heightPx: Float,
): Float = progress.coerceIn(0f, 1f) * heightPx

internal fun resolveCommentThreadCoveredBlurProgress(threadBackProgress: Float): Float {
    val coveredDepth = (1f - threadBackProgress.coerceIn(0f, 1f))
    return coveredDepth * coveredDepth
}
