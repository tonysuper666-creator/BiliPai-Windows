// Original source app/src/main/java/com/android/purebilibili/feature/home/components/BottomBarMatchedLiquidChrome.kt
// LF SHA256 a952a41fc91d694bdc0410f3bba89806acf0b2f33272c071aca97500bcbeb5ad
package com.android.purebilibili.feature.home.components
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import com.android.purebilibili.core.ui.motion.emphasizedEnterTween
import com.android.purebilibili.core.ui.motion.emphasizedExitTween
import com.android.purebilibili.core.ui.motion.softLandingSpring

@Composable
internal fun BottomBarMatchedDockVisibility(
    visible: Boolean,
    edge: BottomBarMatchedDockEdge,
    modifier: Modifier = Modifier,
    enterFadeDurationMillis: Int = 255,
    exitFadeDurationMillis: Int = 160,
    animateScale: Boolean = true,
    content: @Composable () -> Unit
) {
    val direction = if (edge == BottomBarMatchedDockEdge.BOTTOM) 1 else -1
    val transformOrigin = if (edge == BottomBarMatchedDockEdge.BOTTOM) {
        TransformOrigin(0.5f, 1f)
    } else {
        TransformOrigin(0.5f, 0f)
    }
    val enterTransition = slideInVertically(
        animationSpec = softLandingSpring(),
        initialOffsetY = { height -> direction * height }
    ) + fadeIn(animationSpec = emphasizedEnterTween(enterFadeDurationMillis))
    val exitTransition = slideOutVertically(
        animationSpec = emphasizedExitTween(exitFadeDurationMillis),
        targetOffsetY = { height -> direction * height }
    ) + fadeOut(animationSpec = emphasizedExitTween(exitFadeDurationMillis))
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (animateScale) {
            enterTransition + scaleIn(
                animationSpec = softLandingSpring(),
                initialScale = 0.96f,
                transformOrigin = transformOrigin
            )
        } else {
            enterTransition
        },
        exit = if (animateScale) {
            exitTransition + scaleOut(
                animationSpec = emphasizedExitTween(exitFadeDurationMillis),
                targetScale = 0.92f,
                transformOrigin = transformOrigin
            )
        } else {
            exitTransition
        },
        content = { content() }
    )
}

@Composable
internal fun BottomBarMatchedDockVisibility(
    visibleState: MutableTransitionState<Boolean>,
    edge: BottomBarMatchedDockEdge,
    modifier: Modifier = Modifier,
    enterFadeDurationMillis: Int = 255,
    exitFadeDurationMillis: Int = 160,
    animateScale: Boolean = true,
    content: @Composable () -> Unit
) {
    val direction = if (edge == BottomBarMatchedDockEdge.BOTTOM) 1 else -1
    val transformOrigin = if (edge == BottomBarMatchedDockEdge.BOTTOM) {
        TransformOrigin(0.5f, 1f)
    } else {
        TransformOrigin(0.5f, 0f)
    }
    val enterTransition = slideInVertically(
        animationSpec = softLandingSpring(),
        initialOffsetY = { height -> direction * height }
    ) + fadeIn(animationSpec = emphasizedEnterTween(enterFadeDurationMillis))
    val exitTransition = slideOutVertically(
        animationSpec = emphasizedExitTween(exitFadeDurationMillis),
        targetOffsetY = { height -> direction * height }
    ) + fadeOut(animationSpec = emphasizedExitTween(exitFadeDurationMillis))
    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = if (animateScale) {
            enterTransition + scaleIn(
                animationSpec = softLandingSpring(),
                initialScale = 0.96f,
                transformOrigin = transformOrigin
            )
        } else {
            enterTransition
        },
        exit = if (animateScale) {
            exitTransition + scaleOut(
                animationSpec = emphasizedExitTween(exitFadeDurationMillis),
                targetScale = 0.92f,
                transformOrigin = transformOrigin
            )
        } else {
            exitTransition
        },
        content = { content() }
    )
}
