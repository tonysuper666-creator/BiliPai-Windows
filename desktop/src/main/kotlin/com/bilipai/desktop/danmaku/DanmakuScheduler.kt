package com.bilipai.desktop.danmaku

import kotlin.math.abs

data class PositionedDanmaku(val comment: DanmakuComment, val x: Double, val baseline: Double, val textWidth: Int)

/** Media-time scheduler: pause freezes positions; seeking rebuilds only the visible window. */
class DanmakuScheduler(comments: List<DanmakuComment>) {
    private val comments = comments.sortedWith(compareBy<DanmakuComment> { it.timeSeconds }.thenBy { it.id })
    private data class Scheduled(val comment: DanmakuComment, val lane: Int, val width: Int, val duration: Double) {
        val end get() = comment.timeSeconds + duration
    }
    private val active = mutableListOf<Scheduled>()
    private var cursor = 0
    private var previousTime = Double.NaN
    private var viewport = Triple(0, 0, 0)

    fun frame(time: Double, width: Int, height: Int, rowHeight: Int, measure: (DanmakuComment) -> Int): List<PositionedDanmaku> {
        if (!time.isFinite() || time < 0 || width <= 0 || height <= 0 || rowHeight <= 0) return emptyList()
        val geometry = Triple(width, height, rowHeight)
        if (!previousTime.isFinite() || time < previousTime || abs(time - previousTime) > 1.0 || viewport != geometry) {
            active.clear()
            cursor = lowerBound((time - SCROLL_DURATION).coerceAtLeast(0.0))
            viewport = geometry
        }
        val laneCount = (height / rowHeight).coerceIn(1, 30)
        while (cursor < comments.size && comments[cursor].timeSeconds <= time) {
            val comment = comments[cursor++]
            active.removeAll { it.end <= comment.timeSeconds }
            val textWidth = measure(comment).coerceAtLeast(1)
            val fixed = comment.mode == 4 || comment.mode == 5
            val duration = if (fixed) FIXED_DURATION else SCROLL_DURATION
            val lanes = if (comment.mode == 4) (laneCount - 1 downTo 0).toList() else (0 until laneCount).toList()
            val lane = lanes.firstOrNull { candidate ->
                val occupants = active.filter { it.lane == candidate }
                if (fixed) occupants.isEmpty() else occupants.all { previous ->
                    if (previous.comment.mode == 4 || previous.comment.mode == 5) false else {
                        val age = comment.timeSeconds - previous.comment.timeSeconds
                        val oldSpeed = (width + previous.width) / previous.duration
                        val newSpeed = (width + textWidth) / duration
                        val gap = oldSpeed * age - previous.width
                        val catchUpDistance = (newSpeed - oldSpeed).coerceAtLeast(0.0) * (previous.duration - age)
                        gap >= MIN_GAP + catchUpDistance
                    }
                }
            }
            if (lane != null) active += Scheduled(comment, lane, textWidth, duration)
        }
        active.removeAll { it.end <= time }
        previousTime = time
        return active.map { scheduled ->
            val x = if (scheduled.comment.mode == 4 || scheduled.comment.mode == 5) (width - scheduled.width) / 2.0
                else width - (width + scheduled.width) * (time - scheduled.comment.timeSeconds) / scheduled.duration
            PositionedDanmaku(scheduled.comment, x, (scheduled.lane + 1) * rowHeight - 6.0, scheduled.width)
        }
    }

    private fun lowerBound(time: Double): Int {
        var low = 0
        var high = comments.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (comments[middle].timeSeconds < time) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        const val SCROLL_DURATION = 8.0
        const val FIXED_DURATION = 5.0
        private const val MIN_GAP = 24.0
    }
}
