// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeWallpaperBackdrop.kt
// LF SHA256 d966dbd9074fdf29b97785ebf7a724e5635e8098abf2f44c9d2ccf31eb38d2f6
package com.android.purebilibili.feature.home
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.android.purebilibili.core.ui.adaptive.MotionTier
import com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion as rememberSystemReduceMotion
import com.android.purebilibili.core.ui.transition.*

@Composable
internal fun DepthSyncedGlobalHomeWallpaperBackdrop(
    wallpaperUri: String,
    appearance: HomeWallpaperBackdropAppearance,
    baseColor: Color,
    depthProgressProvider: () -> Float,
    depthPhaseProvider: () -> VideoCardTransitionBackgroundPhase,
    depthGestureRestoreProvider: () -> Boolean,
    sourceBoundsProvider: () -> Rect? = { null },
    isDataSaverActive: Boolean = false,
    isLightBackground: Boolean = false,
    realtimeBlurEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val phase = depthPhaseProvider()
    val applyDepth = shouldApplyVideoCardDepthToGlobalHomeWallpaper(
        wallpaperVisible = appearance.visible,
        phase = phase,
    )
    val motionTier =
        if (rememberSystemReduceMotion()) MotionTier.Reduced else MotionTier.Normal
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (applyDepth) {
                    Modifier.videoCardTransitionBackgroundEffect(
                        progressProvider = depthProgressProvider,
                        phaseProvider = depthPhaseProvider,
                        exposureProvider = {
                            resolveVideoCardTransitionExposure(
                                phase = depthPhaseProvider(),
                                predictiveBackInProgress = false,
                                gestureRestoreInProgress = depthGestureRestoreProvider(),
                            )
                        },
                        isGestureRestoreInProgressProvider = depthGestureRestoreProvider,
                        motionTierProvider = { motionTier },
                        isLightBackgroundProvider = { isLightBackground },
                        realtimeBlurEnabledProvider = { realtimeBlurEnabled },
                        sourceBoundsProvider = sourceBoundsProvider,
                    )
                } else {
                    Modifier
                }
            )
    ) {
        HomeWallpaperBackdrop(
            wallpaperUri = wallpaperUri,
            appearance = appearance,
            baseColor = baseColor,
            isDataSaverActive = isDataSaverActive,
        )
    }
}
