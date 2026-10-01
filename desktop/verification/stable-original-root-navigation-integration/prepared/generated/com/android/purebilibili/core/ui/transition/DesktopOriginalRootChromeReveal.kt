// Original source app/src/main/java/com/android/purebilibili/core/ui/transition/VideoCardTransitionBackgroundPolicy.kt
// LF SHA256 e7ee5766b05d05a39e81f0a81774c0870eb340879493df4397f94864b9d386f8
package com.android.purebilibili.core.ui.transition
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

internal fun Modifier.videoCardTransitionChromeReveal(
    revealProvider: () -> Float,
    slideDown: Boolean,
): Modifier = this
    .graphicsLayer {
        alpha = revealProvider().coerceIn(0f, 1f)
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val reveal = revealProvider().coerceIn(0f, 1f)
        layout(placeable.width, placeable.height) {
            val dy = ((1f - reveal) * placeable.height).roundToInt()
            placeable.place(0, if (slideDown) dy else -dy)
        }
    }
