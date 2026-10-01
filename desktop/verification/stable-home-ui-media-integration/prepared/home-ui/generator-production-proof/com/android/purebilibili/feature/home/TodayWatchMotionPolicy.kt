// Original source app/src/main/java/com/android/purebilibili/feature/home/TodayWatchMotionPolicy.kt
// LF SHA256 2c366f533a555da0f98f156e8b176c8d6a2319edfac33d0d8d6c7f7e7bd1b523
package com.android.purebilibili.feature.home

import kotlin.math.pow

internal fun nonLinearWaterfallDelayMillis(
    index: Int,
    baseDelayMs: Int = 52,
    exponent: Float = 1.38f,
    maxDelayMs: Int = 620
): Int {
    if (index <= 0) return 0
    val normalizedBase = baseDelayMs.coerceAtLeast(1)
    val normalizedExponent = exponent.coerceIn(1.0f, 2.4f)
    val delay = (normalizedBase * index.toDouble().pow(normalizedExponent.toDouble())).toInt()
    return delay.coerceAtMost(maxDelayMs.coerceAtLeast(normalizedBase))
}
