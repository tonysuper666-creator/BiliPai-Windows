package com.android.purebilibili.feature.video.screen

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor

/** Full cover at rest; continuously restore the list card's crop during its pixel handoff. */
internal class VideoDetailCoverContentScale(
    private val sourceProgressProvider: () -> Float,
) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val fit = ContentScale.Fit.computeScaleFactor(srcSize, dstSize).scaleX
        val crop = ContentScale.Crop.computeScaleFactor(srcSize, dstSize).scaleX
        val progress = sourceProgressProvider().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val scale = fit + (crop - fit) * progress
        return ScaleFactor(scale, scale)
    }
}
