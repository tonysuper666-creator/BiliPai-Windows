// GENERATED from app/src/main/java/com/android/purebilibili/core/ui/skeleton/ContentLoadingSkeletons.kt; do not edit.
// LF-normalized SHA-256: 26bae6dfaa2f92b4418f37012821ca1a6c0bf5a7fcb82a47cd0369c86dc4b5f2
package com.android.purebilibili.core.ui.skeleton
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.*
import androidx.compose.material3.MaterialTheme
import com.android.purebilibili.core.ui.*
@Composable
fun rememberContentSkeletonPulse(): Float {
    if (com.android.purebilibili.core.ui.skeleton.rememberSkeletonBreathingEnabled()) {
        return com.android.purebilibili.core.ui.skeleton.rememberGentleSkeletonPulse().value
    }
    val transition = rememberInfiniteTransition(label = "contentSkeletonPulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = CONTENT_SKELETON_PULSE_DURATION_MILLIS,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "contentSkeletonPulseAlpha",
    )
    return pulse
}

@Composable
fun rememberContentSkeletonBlockColor(pulse: Float): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val onSurface = MaterialTheme.colorScheme.onSurface
    return remember(pulse, isDark, onSurface) {
        val alpha = if (isDark) {
            CONTENT_SKELETON_DARK_MIN_ALPHA +
                (CONTENT_SKELETON_DARK_MAX_ALPHA - CONTENT_SKELETON_DARK_MIN_ALPHA) * pulse
        } else {
            CONTENT_SKELETON_LIGHT_MIN_ALPHA +
                (CONTENT_SKELETON_LIGHT_MAX_ALPHA - CONTENT_SKELETON_LIGHT_MIN_ALPHA) * pulse
        }
        onSurface.copy(alpha = alpha)
    }
}

@Composable
fun ContentSkeletonBlock(
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.container(ContainerLevel.Tag),
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color),
    )
}

@Composable
fun CommentListItemSkeleton(
    modifier: Modifier = Modifier,
    blockColor: Color? = null,
) {
    val pulse = if (blockColor == null) rememberContentSkeletonPulse() else 0f
    val color = blockColor ?: rememberContentSkeletonBlockColor(pulse)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ContentSkeletonBlock(
            color = color,
            shape = CircleShape,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ContentSkeletonBlock(
                color = color,
                modifier = Modifier
                    .fillMaxWidth(0.34f)
                    .height(14.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            ContentSkeletonBlock(
                color = color,
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .height(14.dp),
            )
            Spacer(modifier = Modifier.height(7.dp))
            ContentSkeletonBlock(
                color = color,
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(14.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            ContentSkeletonBlock(
                color = color,
                modifier = Modifier
                    .fillMaxWidth(0.42f)
                    .height(12.dp),
            )
        }
    }
}

@Composable
fun CommentListColumnSkeleton(
    modifier: Modifier = Modifier,
    itemCount: Int = 5,
) {
    val pulse = rememberContentSkeletonPulse()
    val blockColor = rememberContentSkeletonBlockColor(pulse)
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(itemCount.coerceAtLeast(0)) {
            CommentListItemSkeleton(blockColor = blockColor)
        }
    }
}

private const val CONTENT_SKELETON_PULSE_DURATION_MILLIS = 2_000

private const val CONTENT_SKELETON_LIGHT_MIN_ALPHA = 0.06f

private const val CONTENT_SKELETON_LIGHT_MAX_ALPHA = 0.11f

private const val CONTENT_SKELETON_DARK_MIN_ALPHA = 0.10f

private const val CONTENT_SKELETON_DARK_MAX_ALPHA = 0.16f
