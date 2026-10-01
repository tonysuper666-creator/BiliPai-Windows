package com.android.purebilibili.feature.video.share

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import com.android.purebilibili.core.ui.AppModalPresentation
import com.android.purebilibili.core.ui.motion.AppMotionTokens
import com.android.purebilibili.core.ui.motion.rememberSystemReduceMotion
import kotlinx.coroutines.flow.first

internal fun videoSharePresentation(isLandscape: Boolean): AppModalPresentation? =
    if (isLandscape) AppModalPresentation.BottomSheet else null

@Composable
internal fun isLandscapeVideoShare(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/** Adds a small bottom-anchored Folme rebound after the Material sheet finishes rising. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberVideoShareSheetBounce(
    sheetState: SheetState,
    isLandscape: Boolean,
): Modifier {
    val reduceMotion = rememberSystemReduceMotion()
    val bounceEnabled = isLandscape && !reduceMotion
    val scale = remember(sheetState, bounceEnabled) { Animatable(if (bounceEnabled) 0.94f else 1f) }
    LaunchedEffect(sheetState, bounceEnabled) {
        if (bounceEnabled) {
            snapshotFlow { sheetState.currentValue }
                .first { it == SheetValue.Expanded }
            scale.animateTo(1f, AppMotionTokens.folmeBottomSheetSpring())
        }
    }
    return if (bounceEnabled) {
        Modifier.graphicsLayer {
            transformOrigin = TransformOrigin(0.5f, 1f)
            scaleY = scale.value
        }
    } else Modifier
}
