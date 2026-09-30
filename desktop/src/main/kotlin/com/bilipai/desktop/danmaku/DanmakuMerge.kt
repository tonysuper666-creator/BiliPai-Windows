package com.bilipai.desktop.danmaku

/** Upstream's time-cluster duplicate merging, keeping different text styles separate. */
fun mergeDuplicateDanmaku(comments: List<DanmakuComment>, intervalMs: Int = 500, countThreshold: Int = 2): List<DanmakuComment> {
    if (comments.isEmpty()) return comments
    val merged = mutableListOf<DanmakuComment>()
    val groups = comments.groupBy { listOf(it.text, it.mode, it.color, it.size) }
    val interval = intervalMs.coerceAtLeast(0) / 1_000.0
    groups.values.forEach { values ->
        val items = values.sortedBy { it.timeSeconds }
        var first = items.first()
        var previous = first
        var count = 1
        fun appendBatch() {
            merged += if (count >= countThreshold.coerceAtLeast(2)) first.copy(text = "${first.text} x$count") else first
        }
        items.drop(1).forEach { comment ->
            if (comment.timeSeconds - previous.timeSeconds <= interval) count++
            else {
                appendBatch()
                first = comment
                count = 1
            }
            previous = comment
        }
        appendBatch()
    }
    return merged.sortedWith(compareBy<DanmakuComment> { it.timeSeconds }.thenBy { it.id })
}
