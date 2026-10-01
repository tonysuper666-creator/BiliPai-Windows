package com.android.purebilibili.core.util
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.android.purebilibili.core.theme.AppUiStyle
import com.android.purebilibili.core.theme.LocalAppUiStyle
@Composable
fun VideoGridItemSkeleton(coverAspectRatio: Float = 4f / 3f) {
    val pulse = com.android.purebilibili.core.ui.skeleton.rememberContentSkeletonPulse()
    val blockColor = com.android.purebilibili.core.ui.skeleton.rememberContentSkeletonBlockColor(pulse)
    com.android.purebilibili.core.ui.skeleton.ContentVideoGridItemSkeleton(
        coverAspectRatio = coverAspectRatio,
        blockColor = blockColor,
    )
}

// =============================================================================
//  Android 特有功能：触觉反馈 + 弹性点击
// =============================================================================

/**
 *  触觉反馈类型枚举
 */
