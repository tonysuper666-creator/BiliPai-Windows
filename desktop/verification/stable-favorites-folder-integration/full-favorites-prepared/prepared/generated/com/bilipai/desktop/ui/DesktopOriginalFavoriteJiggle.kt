package com.bilipai.desktop.ui
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
@Composable
fun Modifier.jiggleOnDissolve(
    cardId: String,
    enabled: Boolean = true,
    isCurrentCardDissolving: Boolean = false
): Modifier {
    // 🚀 [性能优化] 提前检查是否需要抖动，避免不必要的状态读取和动画创建
    if (!enabled) return this
    
    val isDissolving by DissolveAnimationManager.isAnyCardDissolving
    val dissolvingId by DissolveAnimationManager.dissolvingCardId
    val shouldJiggle = shouldJiggleOnDissolve(
        enabled = enabled,
        isAnyCardDissolving = isDissolving,
        dissolvingCardId = dissolvingId,
        cardId = cardId,
        isCurrentCardDissolving = isCurrentCardDissolving
    )
    
    // 🚀 [关键优化] 不抖动时直接返回，不创建任何动画对象
    if (!shouldJiggle) return this
    
    val infiniteTransition = rememberInfiniteTransition(label = "jiggle")
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jiggleRotation"
    )
    
    val offsetX by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(60, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "jiggleOffset"
    )
    
    return this.graphicsLayer {
        rotationZ = rotation
        translationX = offsetX
    }
}

// ==================== OpenGL 粒子消散动画 ====================
