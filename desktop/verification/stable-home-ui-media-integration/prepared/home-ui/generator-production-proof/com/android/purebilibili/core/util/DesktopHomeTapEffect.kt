// Original source app/src/main/java/com/android/purebilibili/core/util/ModifierExt.kt
// LF SHA256 caa784820d58714dc5cec7415ee3f67b2a11ab1d31e63d062bc1701e4f1f4bfa
package com.android.purebilibili.core.util
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle

fun Modifier.iOSTapEffect(
    scale: Float = 0.96f,
    hapticEnabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val uiStyle = LocalAppUiStyle.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = rememberHapticFeedback()
    val targetScale = if (isPressed) {
        if (uiStyle == AppUiStyle.MATERIAL3) 0.985f else scale
    } else {
        1f
    }
    
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = if (uiStyle == AppUiStyle.MATERIAL3) 0.9f else 0.6f,
            stiffness = if (uiStyle == AppUiStyle.MATERIAL3) 650f else 400f
        ),
        label = "ios_tap_scale"
    )
    
    this
        .graphicsLayer {
            scaleX = animatedScale
            scaleY = animatedScale
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

/**
 *  iOS 风格点击效果 (仅动画，不处理点击事件)
 * 
 * 用于需要自定义点击处理的场景
 */
