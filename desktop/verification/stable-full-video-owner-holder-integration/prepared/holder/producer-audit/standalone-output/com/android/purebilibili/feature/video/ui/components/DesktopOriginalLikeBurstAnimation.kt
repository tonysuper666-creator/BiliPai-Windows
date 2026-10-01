package com.android.purebilibili.feature.video.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ThumbUp
import com.android.purebilibili.core.ui.components.AppIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.plugin.skin.LocalUiSkinState
import com.bilipai.desktop.ui.DesktopOriginalPlayerSkinAsset as UiSkinAnimatedAsset
import com.android.purebilibili.core.plugin.skin.UiSkinSurface
import com.android.purebilibili.core.plugin.skin.assetPath
import kotlinx.coroutines.delay

@Composable
fun LikeBurstAnimation(
    visible: Boolean,
    reducedMotion: Boolean = false,
    onAnimationEnd: () -> Unit = {}
) {
    if (!visible) return

    val uiSkinState = LocalUiSkinState.current
    val skinLikeEffectPath = uiSkinState.assetPath(UiSkinSurface.LIKE_EFFECT) {
        it.likeEffectAnimation ?: it.likeEffectPreview
    }
    if (skinLikeEffectPath != null) {
        LaunchedEffect(skinLikeEffectPath, visible) {
            delay(if (reducedMotion) 300 else 800)
            onAnimationEnd()
        }
        UiSkinAnimatedAsset(
            path = skinLikeEffectPath,
            size = if (reducedMotion) 72.dp else 88.dp,
            iterations = 1,
            contentDescription = null,
        )
        return
    }

    val progress = remember { Animatable(0f) }
    val durationMillis = if (reducedMotion) 260 else 420

    LaunchedEffect(visible, reducedMotion) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowInEasing)
        )
        delay(80)
        onAnimationEnd()
    }

    val primary = MaterialTheme.colorScheme.primary
    val currentProgress = progress.value
    val iconScale by animateFloatAsState(
        targetValue = if (currentProgress < 0.45f) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "likeBurstScale"
    )

    Box(
        modifier = Modifier.size(if (reducedMotion) 72.dp else 88.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val pulseRadius = size.minDimension * (0.18f + currentProgress * 0.24f)
            val ringRadius = size.minDimension * (0.2f + currentProgress * 0.28f)
            val glowAlpha = (1f - currentProgress).coerceIn(0f, 1f) * if (reducedMotion) 0.28f else 0.5f
            val ringAlpha = (1f - currentProgress).coerceIn(0f, 1f) * 0.9f

            drawCircle(
                color = primary.copy(alpha = glowAlpha),
                radius = pulseRadius,
                center = center
            )
            drawCircle(
                color = primary.copy(alpha = ringAlpha),
                radius = ringRadius,
                center = center,
                style = Stroke(width = size.minDimension * 0.045f)
            )
        }

        AppIcon(
            imageVector = Icons.Rounded.ThumbUp,
            contentDescription = null,
            tint = primary.copy(alpha = 0.98f),
            modifier = Modifier
                .size(if (reducedMotion) 26.dp else 30.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                    alpha = 1f - (currentProgress * 0.14f)
                }
        )
    }
}

