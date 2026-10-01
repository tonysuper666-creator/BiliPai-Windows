// 文件路径: feature/video/ui/overlay/ViewPointSegmentBarPolicy.kt
package com.android.purebilibili.feature.video.ui.overlay

import com.android.purebilibili.data.model.response.ViewPoint

/**
 * 分段章节条（view_points）的归一化与命中策略，对齐 PiliPlus
 * 的 ViewPointSegmentProgressBar 行为：按 from 排序、裁掉越界与重叠、
 * 间隙以透明占位呈现，点击命中所覆盖的章节并跳到其起点。
 */
internal data class NormalizedViewPointSegment(
    val fromMs: Long,
    val toMs: Long,
    val content: String
)

internal fun normalizeViewPointSegments(
    viewPoints: List<ViewPoint>,
    durationMs: Long
): List<NormalizedViewPointSegment> {
    if (durationMs <= 0L) return emptyList()
    return viewPoints
        .asSequence()
        .filter { it.content.isNotBlank() && it.toMs > it.fromMs }
        .sortedBy { it.fromMs }
        .fold(mutableListOf<NormalizedViewPointSegment>()) { acc, point ->
            val from = maxOf(point.fromMs, acc.lastOrNull()?.toMs ?: 0L)
                .coerceIn(0L, durationMs)
            val to = point.toMs.coerceIn(0L, durationMs)
            if (to > from) {
                acc += NormalizedViewPointSegment(fromMs = from, toMs = to, content = point.content)
            }
            acc
        }
        .toList()
}

/** 命中 positionMs 所在的章节（最后一段允许延伸到视频结尾） */
internal fun findViewPointSegmentAt(
    segments: List<NormalizedViewPointSegment>,
    positionMs: Long
): NormalizedViewPointSegment? {
    if (segments.isEmpty()) return null
    val containing = segments.lastOrNull { positionMs >= it.fromMs && positionMs < it.toMs }
    if (containing != null) return containing
    val last = segments.last()
    return last.takeIf { positionMs >= it.toMs }
}
