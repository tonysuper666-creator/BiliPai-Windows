// Original source app/src/main/java/com/android/purebilibili/core/util/HingeLayoutPolicy.kt
// LF SHA256 8d1ab46276cf63d495ec59fbe95ebe8b80133429fdc61df8b76a586dbeb490b0
package com.android.purebilibili.core.util

import androidx.compose.ui.unit.IntRect

data class AppHingeFeature(
    val orientation: AppHingeOrientation,
    val bounds: IntRect,
    val isSeparating: Boolean,
    val isOccluding: Boolean,
    val isFlat: Boolean,
)
