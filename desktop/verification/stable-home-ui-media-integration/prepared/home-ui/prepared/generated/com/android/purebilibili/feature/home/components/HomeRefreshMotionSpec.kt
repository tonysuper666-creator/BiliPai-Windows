// Original source app/src/main/java/com/android/purebilibili/feature/home/components/HomeRefreshMotionSpec.kt
// LF SHA256 7cc4ed9a6ebeb2fa2a3eb2d12f7157b70c02da7b04998452eeaf13cf188fce6f
package com.android.purebilibili.feature.home.components

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

internal fun <T> md3RefreshAlphaMotionSpec(): SpringSpec<T> = spring(dampingRatio = 0.82f)

internal fun <T> md3RefreshScaleMotionSpec(): SpringSpec<T> = spring(
    dampingRatio = 0.7f,
    stiffness = 360f,
)

internal fun <T> iosRefreshArrowMotionSpec(): SpringSpec<T> = spring(
    dampingRatio = 0.9f,
    stiffness = 540f,
)

internal fun <T> iosRefreshAlphaMotionSpec(): SpringSpec<T> = spring(
    dampingRatio = 0.92f,
    stiffness = 620f,
)

internal fun <T> iosRefreshScaleMotionSpec(): SpringSpec<T> = spring(
    dampingRatio = 0.9f,
    stiffness = 620f,
)
