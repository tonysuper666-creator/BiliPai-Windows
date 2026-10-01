package com.bilipai.desktop.danmaku

import kotlin.math.abs

data class PositionedDanmaku(val comment: DanmakuComment, val x: Double, val baseline: Double, val textWidth: Int)

/** Media-time scheduler: pause freezes positions; seeking rebuilds only the visible window. */
class DanmakuScheduler(comments: List<DanmakuComment>, settings: DanmakuSettings = DanmakuSettings(displayAreaRatio = 1f, scrollDurationSeconds = 8f, staticDurationSeconds = 5f)) {
    private val originalComments = comments.sortedWith(compareBy<DanmakuComment> { it.timeSeconds }.thenBy { it.id })
    private var settings = settings.normalized()
    private var comments = prepareComments()
    private data class Scheduled(val comment: DanmakuComment, val lane: Int, val width: Int, val duration: Double) {
        val end get() = comment.timeSeconds + duration
    }
    private val active = mutableListOf<Scheduled>()
    private var cursor = 0
    private var previousTime = Double.NaN
    private var viewport = Triple(0, 0, 0)

    fun applySettings(settings: DanmakuSettings) {
        val normalized = settings.normalized()
        if (normalized == this.settings) return
        this.settings = normalized
        comments = prepareComments()
        previousTime = Double.NaN
    }

    private fun prepareComments(): List<DanmakuComment> {
        val visible = originalComments.filter(settings::allows)
        return if (settings.mergeDuplicates) mergeDuplicateDanmaku(visible, settings.duplicateMergeWindowMs, settings.duplicateMergeCountThreshold)
            else visible
    }

    fun frame(time: Double, width: Int, height: Int, rowHeight: Int, measure: (DanmakuComment) -> Int): List<PositionedDanmaku> {
        if (!time.isFinite() || time < 0 || width <= 0 || height <= 0 || rowHeight <= 0) return emptyList()
        val geometry = Triple(width, height, rowHeight)
        if (!previousTime.isFinite() || time < previousTime || abs(time - previousTime) > 1.0 || viewport != geometry) {
            active.clear()
            val longestDuration = maxOf(settings.scrollDurationSeconds * settings.speedFactor, settings.staticDurationSeconds).toDouble()
            cursor = lowerBound((time - longestDuration).coerceAtLeast(0.0))
            viewport = geometry
        }
        val availableHeight = (height * settings.displayAreaRatio).toInt()
        if (availableHeight < rowHeight) return emptyList()
        val laneCount = (availableHeight / rowHeight).coerceIn(1, 50)
        while (cursor < comments.size && comments[cursor].timeSeconds <= time) {
            val comment = comments[cursor++]
            active.removeAll { it.end <= comment.timeSeconds }
            val textWidth = measure(comment).coerceAtLeast(1)
            val fixed = comment.mode == 4 || comment.mode == 5
            val duration = if (fixed) settings.staticDurationSeconds.toDouble()
                else (settings.scrollDurationSeconds * settings.speedFactor).toDouble()
            val lanes = if (comment.mode == 4) (laneCount - 1 downTo 0).toList() else (0 until laneCount).toList()
            val lane = lanes.firstOrNull { candidate ->
                val occupants = active.filter { it.lane == candidate }
                if (fixed) occupants.isEmpty() else occupants.all { previous ->
                    if (previous.comment.mode == 4 || previous.comment.mode == 5 ||
                        (previous.comment.mode == 6) != (comment.mode == 6)) false else {
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
            val progress = (time - scheduled.comment.timeSeconds) / scheduled.duration
            val x = when (scheduled.comment.mode) {
                4, 5 -> (width - scheduled.width) / 2.0
                6 -> -scheduled.width + (width + scheduled.width) * progress
                else -> width - (width + scheduled.width) * progress
            }
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
