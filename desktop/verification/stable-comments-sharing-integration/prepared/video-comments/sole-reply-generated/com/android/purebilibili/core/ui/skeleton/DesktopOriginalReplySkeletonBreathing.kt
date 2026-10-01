// GENERATED from app/src/main/java/com/android/purebilibili/core/ui/skeleton/SkeletonBreathing.kt; do not edit.
// LF-normalized SHA-256: fa072619c7ee3d3118834cebe1983d081a8efcf085512fb3d8ea4ee19a299b0a
package com.android.purebilibili.core.ui.skeleton

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import com.bilipai.desktop.settings.LocalDesktopDynamicTimelinePreferences
import com.android.purebilibili.core.store.SkeletonSettingsStore

@Composable
fun rememberSkeletonBreathingEnabled(): Boolean {
    val context = checkNotNull(LocalDesktopDynamicTimelinePreferences.current).context
    return remember(context) { SkeletonSettingsStore.breathingEnabled(context) }
        .collectAsState(initial = true).value
}

/** A visible, smooth luminance breath over a 2.8 second cycle; never changes layout size. */
@Composable
fun rememberGentleSkeletonPulse(): State<Float> {
    if (com.bilipai.desktop.ui.rememberDesktopDynamicReduceMotion()) {
        return rememberUpdatedState(0.5f)
    }
    return rememberInfiniteTransition(label = "gentleSkeleton").animateFloat(
        initialValue = 0.05f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "gentleSkeletonPulse",
    )
}
