// Original source app/src/main/java/com/android/purebilibili/feature/video/ui/components/VideoCommentSheetHost.kt
// LF SHA256 2e3005bb927f08d2cb6c07a32efb7ee11fa6eda69f926bf29cd6cca1e9eea614
package com.android.purebilibili.feature.video.ui.components


internal fun resolveCommentThreadPredictiveBackOffsetY(
    progress: Float,
    heightPx: Float,
): Float = progress.coerceIn(0f, 1f) * heightPx

internal fun resolveCommentThreadCoveredBlurProgress(threadBackProgress: Float): Float {
    val coveredDepth = (1f - threadBackProgress.coerceIn(0f, 1f))
    return coveredDepth * coveredDepth
}
