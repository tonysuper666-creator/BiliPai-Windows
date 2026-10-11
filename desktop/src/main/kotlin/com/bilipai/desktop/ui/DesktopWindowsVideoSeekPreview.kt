package com.bilipai.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.purebilibili.feature.video.ui.components.SeekPreviewBubble
import com.android.purebilibili.feature.video.ui.components.SeekPreviewBubblePlacement
import com.android.purebilibili.feature.video.ui.components.SeekPreviewBubbleSimple
import com.android.purebilibili.feature.video.viewmodel.VideoPlaybackUiState

/** Transient input projection only; source ownership remains with the existing Leaf. */
internal data class DesktopWindowsVideoSeekPreview(
    val source: DesktopOriginalVideoAcceptedPublication,
    val targetPositionMs: Long,
    val railFraction: Float,
)

/** Whole original sprite/time/fallback renderers on the existing bounded video carrier. */
@Composable
internal fun DesktopWindowsVideoSeekPreviewHost(
    preview: DesktopWindowsVideoSeekPreview?,
    source: DesktopOriginalVideoAcceptedPublication?,
    success: VideoPlaybackUiState.Success?,
    current: Boolean,
    viewportSize: IntSize,
    currentPositionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    if (!current || preview == null || source == null || preview.source !== source || success == null ||
        success.info.bvid != source.request.bvid || success.info.cid != source.request.cid || success.isQualitySwitching ||
        viewportSize.width <= 0 || viewportSize.height <= 0 || durationMs <= 0L) return
    val widthPx = viewportSize.width.toFloat()
    val offsetX = widthPx * preview.railFraction.coerceIn(0f, 1f)
    val imageFits = with(LocalDensity.current) { viewportSize.width.toDp() >= 240.dp && viewportSize.height.toDp() >= 280.dp }
    key(source) {
        Box(modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.BottomStart) {
            val bubbleModifier = Modifier.desktopCommandHitRegion("seek-preview")
            if (imageFits && success.videoshotData?.isValid == true) {
                SeekPreviewBubble(success.videoshotData, preview.targetPositionMs,
                    currentPositionMs = currentPositionMs, durationMs = durationMs,
                    offsetX = offsetX, containerWidth = widthPx, placement = SeekPreviewBubblePlacement.Anchored,
                    modifier = bubbleModifier)
            } else {
                SeekPreviewBubbleSimple(preview.targetPositionMs, currentPositionMs = currentPositionMs,
                    offsetX = offsetX, containerWidth = widthPx, placement = SeekPreviewBubblePlacement.Anchored,
                    modifier = bubbleModifier)
            }
        }
    }
}
