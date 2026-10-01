// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeWaterfallMotionSpec.kt
// LF SHA256 e72f94d4ffd337b587b387707f5da301599bbee44d16d2ea6daa3f70b52e9ac3
package com.android.purebilibili.feature.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

internal fun <T> homeWaterfallFadeInSpec(delayMillis: Int): TweenSpec<T> = tween(
    durationMillis = 280,
    delayMillis = delayMillis,
    easing = LinearOutSlowInEasing,
)

internal fun <T> homeWaterfallExpandSpec(delayMillis: Int): TweenSpec<T> = tween(
    durationMillis = 420,
    delayMillis = delayMillis,
    easing = FastOutSlowInEasing,
)

internal fun <T> homeWaterfallFadeOutSpec(): TweenSpec<T> = tween(durationMillis = 120)
