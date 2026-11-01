package com.bilipai.desktop.danmaku

/** Desktop's existing scrolling policy predicts catch-up only over the previous item's remaining lifetime. */
internal fun desktopDanmakuLineAdmits(
    viewportWidth: Int,
    previousWidth: Int,
    nextWidth: Int,
    age: Double,
    previousDuration: Double,
    nextDuration: Double,
    margin: Float,
): Boolean {
    val oldSpeed = (viewportWidth + previousWidth) / previousDuration
    val newSpeed = (viewportWidth + nextWidth) / nextDuration
    val gap = oldSpeed * age - previousWidth
    val catchUpDistance = (newSpeed - oldSpeed).coerceAtLeast(0.0) * (previousDuration - age)
    return gap >= margin + catchUpDistance
}
