package com.android.purebilibili.feature.bangumi.ui.player

/** 每次用户拖动独立计数；播放进度回填不能启动触感会话。 */
internal class BangumiSeekHapticPolicy {
    private var durationMs = 0L
    private var previousSecond = 0L
    private var lastFeedbackMs = 0L

    fun start(positionMs: Long, durationMs: Long, nowMs: Long): Boolean {
        this.durationMs = durationMs.coerceAtLeast(0L)
        previousSecond = positionMs.coerceIn(0L, this.durationMs) / 1_000L
        lastFeedbackMs = nowMs
        return this.durationMs > 0L
    }

    fun update(positionMs: Long, nowMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val second = positionMs.coerceIn(0L, durationMs) / 1_000L
        val crossed = second != previousSecond
        previousSecond = second
        if (!crossed || nowMs - lastFeedbackMs < 60L) return false
        lastFeedbackMs = nowMs
        return true
    }

    fun finish() {
        durationMs = 0L
    }
}
