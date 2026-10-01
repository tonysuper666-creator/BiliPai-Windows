// Original source app/src/main/java/com/android/purebilibili/core/util/FoldableDisplayPolicy.kt
// LF SHA256 3ab8f1a1a56ec6c82bdbc08bf4087cb3d6edd99c37d2faa46f9050ba0dfff0b5
package com.android.purebilibili.core.util


enum class AppFoldableDisplayRole {
    Standard,
    Cover,
    Inner,
    UnknownFoldable,
}

enum class AppDisplayNaturalOrientation {
    Portrait,
    Landscape,
    Unknown,
}

enum class AppFoldableDetectionBasis {
    None,
    CurrentFoldingFeature,
    HingeAngleSensor,
    WindowMetricsFallback,
}

data class AppDisplayContext(
    val currentWindowWidthDp: Int,
    val currentWindowHeightDp: Int,
    val maximumWindowWidthDp: Int? = null,
    val maximumWindowHeightDp: Int? = null,
    val displayModeWidthPx: Int? = null,
    val displayModeHeightPx: Int? = null,
    val displayRotation: Int? = null,
    val foldableDisplayRole: AppFoldableDisplayRole = AppFoldableDisplayRole.Standard,
    val naturalOrientation: AppDisplayNaturalOrientation = AppDisplayNaturalOrientation.Unknown,
    val detectionBasis: AppFoldableDetectionBasis = AppFoldableDetectionBasis.None,
    val isInMultiWindowMode: Boolean = false,
) {
    val isFoldableCoverWindow: Boolean
        get() = foldableDisplayRole == AppFoldableDisplayRole.Cover

    val isKnownFoldableDevice: Boolean
        get() = foldableDisplayRole != AppFoldableDisplayRole.Standard

    val usesInWindowFullscreen: Boolean
        get() = isFoldableCoverWindow &&
            naturalOrientation == AppDisplayNaturalOrientation.Landscape
}
