// Original source app/src/main/java/com/android/purebilibili/feature/home/components/HomeTopTabMotionSpec.kt
// LF SHA256 a30e34a0b0c4693edf8e3e73abd7dd175d9ce2c40fe4ac4f009b0b16f61773a8
package com.android.purebilibili.feature.home.components

import androidx.compose.animation.core.TweenSpec
import com.android.purebilibili.core.ui.motion.iosMorphTween

internal fun <T> iosTopTabCapsuleMotionSpec(): TweenSpec<T> = iosMorphTween(260)
