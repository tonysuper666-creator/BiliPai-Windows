// OriginalSource: app/src/main/java/com/android/purebilibili/feature/home/components/miuix/InteractiveHighlightMotionSpec.kt
// OriginalSHA256: 39f7825572d2b404d6552e80946958fb60747c87255f094cbbc0fd18cc6afd63
package com.android.purebilibili.feature.home.components.miuix

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset

internal fun interactiveHighlightPressSpec(): SpringSpec<Float> = spring(
    dampingRatio = 0.5f,
    stiffness = 300f,
    visibilityThreshold = 0.001f,
)

internal fun interactiveHighlightPositionSpec(): SpringSpec<Offset> = spring(
    dampingRatio = 0.5f,
    stiffness = 300f,
    visibilityThreshold = Offset.VisibilityThreshold,
)
