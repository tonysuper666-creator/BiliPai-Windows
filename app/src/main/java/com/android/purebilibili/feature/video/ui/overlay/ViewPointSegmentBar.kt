// 文件路径: feature/video/ui/overlay/ViewPointSegmentBar.kt
package com.android.purebilibili.feature.video.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.purebilibili.core.ui.components.AppText
import com.android.purebilibili.data.model.response.ViewPoint

/**
 * 分段章节条（对齐 PiliPlus 的 ViewPointSegmentProgressBar）：
 * 进度条下方按章节跨度分段的命名条，点击某段直接跳到该章节起点。
 */
@Composable
fun ViewPointSegmentBar(
    viewPoints: List<ViewPoint>,
    durationMs: Long,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(viewPoints, durationMs) {
        normalizeViewPointSegments(viewPoints, durationMs)
    }
    if (segments.isEmpty() || durationMs <= 0L) return

    val activeSegment = remember(segments, currentPositionMs) {
        findViewPointSegmentAt(segments, currentPositionMs)
    }
    val trackColor = Color.White.copy(alpha = 0.24f)
    val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val separatorColor = Color.Black.copy(alpha = 0.55f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(trackColor)
            .pointerInput(segments, durationMs) {
                detectTapGestures { offset ->
                    val positionMs = (offset.x / size.width.toFloat() * durationMs)
                        .toLong()
                        .coerceIn(0L, durationMs)
                    findViewPointSegmentAt(segments, positionMs)?.let { segment ->
                        onSeek(segment.fromMs)
                    }
                }
            },
    ) {
        var cursorMs = 0L
        segments.forEachIndexed { index, segment ->
            if (segment.fromMs > cursorMs) {
                // 章节之间的空隙保持轨道底色
                Box(
                    modifier = Modifier
                        .weight((segment.fromMs - cursorMs).toFloat())
                        .fillMaxHeight()
                )
            }
            val isActive = segment === activeSegment
            Box(
                modifier = Modifier
                    .weight((segment.toMs - segment.fromMs).toFloat())
                    .fillMaxHeight()
                    .background(if (isActive) activeColor else Color.Transparent)
                    .then(
                        if (index != 0) {
                            Modifier.padding(start = 1.dp)
                        } else {
                            Modifier
                        }
                    )
                    .background(if (index != 0) separatorColor else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                AppText(
                    text = segment.content,
                    color = Color.White,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 7.sp,
                        maxFontSize = 10.sp,
                        stepSize = 0.5.sp,
                    ),
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            cursorMs = segment.toMs
        }
    }
}
