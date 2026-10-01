package com.android.purebilibili.feature.video.ui.section
import com.android.purebilibili.feature.video.ui.components.GesturePercentMotionDefaults
enum class VideoGestureMode { None, Brightness, Volume, Seek, SwipeToFullscreen }

internal data class VideoGestureMotionSpec(
    val digitInitialBlurRadiusDp: Float,
    val digitInitialAlpha: Float,
    val digitBlurHoldDurationMillis: Int,
    val digitBlurResetDurationMillis: Int,
    val digitAlphaResetDurationMillis: Int,
    val digitEnterFadeDurationMillis: Int,
    val digitExitFadeDurationMillis: Int,
    val digitScaleDurationMillis: Int,
    val digitSlideSpringDampingRatio: Float,
    val digitSlideSpringStiffness: Float,
    val levelOverlayEnterFadeDurationMillis: Int,
    val levelOverlayEnterTransformDurationMillis: Int,
    val levelOverlayExitDurationMillis: Int,
    val levelProgressDurationMillis: Int,
    val levelIconScaleDurationMillis: Int,
    val levelValueScaleDurationMillis: Int,
    val levelIconEnterFadeDurationMillis: Int,
    val levelIconExitFadeDurationMillis: Int,
    val levelIconContentScaleDurationMillis: Int,
    val orientationHintEnterFadeDurationMillis: Int,
    val orientationHintEnterTransformDurationMillis: Int,
    val orientationHintExitDurationMillis: Int,
    val longPressHintDurationMillis: Int,
    val longPressArrowCycleDurationMillis: Int,
    val longPressArrowPhaseStepDurationMillis: Int
)

internal fun resolveVideoGestureMotionSpec(): VideoGestureMotionSpec {
    return VideoGestureMotionSpec(
        digitInitialBlurRadiusDp = GesturePercentMotionDefaults.InitialBlurRadiusDp,
        digitInitialAlpha = GesturePercentMotionDefaults.InitialAlpha,
        digitBlurHoldDurationMillis = GesturePercentMotionDefaults.BlurHoldDurationMillis,
        digitBlurResetDurationMillis = GesturePercentMotionDefaults.BlurResetDurationMillis,
        digitAlphaResetDurationMillis = GesturePercentMotionDefaults.AlphaResetDurationMillis,
        digitEnterFadeDurationMillis = GesturePercentMotionDefaults.EnterFadeDurationMillis,
        digitExitFadeDurationMillis = GesturePercentMotionDefaults.ExitFadeDurationMillis,
        digitScaleDurationMillis = 0,
        digitSlideSpringDampingRatio = GesturePercentMotionDefaults.SlideSpringDampingRatio,
        digitSlideSpringStiffness = GesturePercentMotionDefaults.SlideSpringStiffness,
        levelOverlayEnterFadeDurationMillis = 160,
        levelOverlayEnterTransformDurationMillis = 220,
        levelOverlayExitDurationMillis = 200,
        levelProgressDurationMillis = 130,
        levelIconScaleDurationMillis = 180,
        levelValueScaleDurationMillis = 140,
        levelIconEnterFadeDurationMillis = 120,
        levelIconExitFadeDurationMillis = 110,
        levelIconContentScaleDurationMillis = 180,
        orientationHintEnterFadeDurationMillis = 150,
        orientationHintEnterTransformDurationMillis = 230,
        orientationHintExitDurationMillis = 200,
        longPressHintDurationMillis = 200,
        longPressArrowCycleDurationMillis = 900,
        longPressArrowPhaseStepDurationMillis = 300
    )
}

