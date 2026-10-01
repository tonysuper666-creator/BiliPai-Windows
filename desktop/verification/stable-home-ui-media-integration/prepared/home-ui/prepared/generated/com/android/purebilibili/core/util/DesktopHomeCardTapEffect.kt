package com.android.purebilibili.core.util
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import com.android.purebilibili.core.theme.*
import androidx.compose.ui.graphics.graphicsLayer
fun Modifier.iOSCardTapEffect(
    pressScale: Float = 0.96f,
    pressTranslationY: Float = 8f,
    hapticEnabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val uiStyle = LocalAppUiStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = rememberHapticFeedback()
    val targetScale = if (isPressed) {
        if (uiStyle == AppUiStyle.MATERIAL3) 0.985f else pressScale
    } else {
        1f
    }
    val targetTranslation = if (isPressed) {
        if (uiStyle == AppUiStyle.MATERIAL3) 2f else pressTranslationY
    } else {
        0f
    }
    
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = if (uiStyle == AppUiStyle.MATERIAL3) 0.92f else if (isPressed) 0.75f else 0.55f,
            stiffness = if (uiStyle == AppUiStyle.MATERIAL3) 700f else if (isPressed) 600f else 300f
        ),
        label = "card_tap_scale"
    )
    
    val animatedTranslationY by animateFloatAsState(
        targetValue = targetTranslation,
        animationSpec = spring(
            dampingRatio = if (uiStyle == AppUiStyle.MATERIAL3) 0.95f else if (isPressed) 0.85f else 0.5f,
            stiffness = if (uiStyle == AppUiStyle.MATERIAL3) 850f else if (isPressed) 800f else 250f
        ),
        label = "card_tap_translationY"
    )
    
    this
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
            translationY = animatedTranslationY
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null
        ) {
            if (hapticEnabled) {
                haptic(HapticType.LIGHT)
            }
            onClick()
        }
}