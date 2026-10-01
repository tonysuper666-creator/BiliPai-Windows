// GENERATED from app/src/main/java/com/android/purebilibili/core/ui/animation/ParticleDissolveEffect.kt; do not edit.
// LF-normalized SHA-256: f38fb8de539140729eb7beda30ddc9889a67eae15256c54f9a121bfa06cb76f7
package com.bilipai.desktop.ui
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
object DissolveAnimationManager {
    private val _isAnyCardDissolving = mutableStateOf(false)
    val isAnyCardDissolving: State<Boolean> = _isAnyCardDissolving
    
    private val _dissolvingCardId = mutableStateOf<String?>(null)
    val dissolvingCardId: State<String?> = _dissolvingCardId
    
    fun startDissolving(cardId: String) {
        _dissolvingCardId.value = cardId
        _isAnyCardDissolving.value = true
    }
    
    fun stopDissolving() {
        _dissolvingCardId.value = null
        _isAnyCardDissolving.value = false
    }
}

enum class DissolveAnimationPreset {
    CLASSIC,
    TELEGRAM_FAST
}

internal fun shouldWrapWithDissolveAnimation(isDissolving: Boolean): Boolean {
    return isDissolving
}

internal fun shouldJiggleOnDissolve(
    enabled: Boolean,
    isAnyCardDissolving: Boolean,
    dissolvingCardId: String?,
    cardId: String,
    isCurrentCardDissolving: Boolean
): Boolean {
    if (!enabled || isCurrentCardDissolving) return false
    return isAnyCardDissolving && dissolvingCardId != cardId
}

internal fun shouldPublishGlobalDissolveState(
    publishGlobalState: Boolean
): Boolean {
    return publishGlobalState
}

internal fun shouldCreateDissolveBitmap(
    width: Int,
    height: Int
): Boolean {
    return width > 0 && height > 0
}

internal fun shouldDispatchDissolveCompletion(
    hasCompletedCurrentDissolve: Boolean
): Boolean {
    return !hasCompletedCurrentDissolve
}

private data class DissolveAnimationParams(
    val durationSec: Float,
    val particleStep: Int,
    val waveDurationSec: Float,
    val waveRandomSec: Float,
    val collapseDurationMs: Int
)

private fun DissolveAnimationPreset.params(): DissolveAnimationParams {
    return when (this) {
        DissolveAnimationPreset.CLASSIC -> DissolveAnimationParams(
            durationSec = 1.8f,
            particleStep = 2,
            waveDurationSec = 0.35f,
            waveRandomSec = 0.08f,
            collapseDurationMs = 200
        )
        DissolveAnimationPreset.TELEGRAM_FAST -> DissolveAnimationParams(
            durationSec = 0.82f,
            particleStep = 3,
            waveDurationSec = 0.16f,
            waveRandomSec = 0.05f,
            collapseDurationMs = 135
        )
    }
}


@Composable
fun DissolvableVideoCard(
    isDissolving: Boolean,
    onDissolveComplete: () -> Unit,
    modifier: Modifier = Modifier,
    cardId: String = "",
    preset: DissolveAnimationPreset = DissolveAnimationPreset.CLASSIC,
    collapseAfterDissolve: Boolean = true,
    publishGlobalDissolveState: Boolean = true,
    keepInvisibleAfterDissolve: Boolean = false,
    content: @Composable () -> Unit
) {
    val animationParams = remember(preset) { preset.params() }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var shouldCollapse by remember { mutableStateOf(false) }
    var keepContentHidden by remember { mutableStateOf(false) }
    var hasCompletedCurrentDissolve by remember(cardId) { mutableStateOf(false) }
    val publishGlobalState = shouldPublishGlobalDissolveState(publishGlobalDissolveState)

    fun dispatchDissolveCompletionOnce() {
        if (!shouldDispatchDissolveCompletion(hasCompletedCurrentDissolve)) return
        hasCompletedCurrentDissolve = true
        onDissolveComplete()
        if (publishGlobalState) {
            DissolveAnimationManager.stopDissolving()
        }
    }

    val finishWithoutParticle = {
        if (!shouldDispatchDissolveCompletion(hasCompletedCurrentDissolve)) {
            Unit
        } else if (collapseAfterDissolve) {
            shouldCollapse = true
        } else {
            keepContentHidden = keepInvisibleAfterDissolve
            dispatchDissolveCompletionOnce()
        }
    }

    val collapseSpec: AnimationSpec<Float> = remember(animationParams.collapseDurationMs, preset) {
        when (preset) {
            DissolveAnimationPreset.CLASSIC -> spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh
            )
            DissolveAnimationPreset.TELEGRAM_FAST -> tween(
                durationMillis = animationParams.collapseDurationMs,
                easing = FastOutSlowInEasing
            )
        }
    }

    // 动画：完成/收起
    val heightMultiplier by animateFloatAsState(
        targetValue = if (shouldCollapse) 0f else 1f,
        animationSpec = collapseSpec,
        label = "heightCollapse",
        finishedListener = {
             if (shouldCollapse && collapseAfterDissolve) {
                 // Animation fully done, NOW we dismiss
                 dispatchDissolveCompletionOnce()
             }
        }
    )

    val isGLContentReady = false
    LaunchedEffect(isDissolving) {
        if (isDissolving) {
            hasCompletedCurrentDissolve = false
            keepContentHidden = false
            finishWithoutParticle()
        } else {
            shouldCollapse = false
            if (publishGlobalState && cardId.isNotEmpty() &&
                DissolveAnimationManager.dissolvingCardId.value == cardId) {
                DissolveAnimationManager.stopDissolving()
            }
        }
    }

    DisposableEffect(cardId) {
        onDispose {
            if (
                publishGlobalState &&
                cardId.isNotEmpty() &&
                DissolveAnimationManager.dissolvingCardId.value == cardId
            ) {
                DissolveAnimationManager.stopDissolving()
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { cardSize = it }
            .then(
                if (shouldCollapse) {
                    Modifier.height(
                        with(LocalDensity.current) {
                            (cardSize.height * heightMultiplier).toDp()
                        }
                    )
                } else {
                    Modifier
                }
            )
    ) {
        // 1. Content Layer
        // Keep visible until GL view renders first frame (seamless transition)
        Box(
            modifier = Modifier.alpha(
                if (isGLContentReady || keepContentHidden) 0f else 1f
            )
        ) {
            content()
        }

    }
}

@Composable
internal fun DesktopReplyDissolvableContainer(
    isDissolving: Boolean,
    onDissolveComplete: () -> Unit,
    modifier: Modifier = Modifier,
    cardId: String = "",
    preset: DissolveAnimationPreset = DissolveAnimationPreset.CLASSIC,
    collapseAfterDissolve: Boolean = true,
    publishGlobalDissolveState: Boolean = true,
    keepInvisibleAfterDissolve: Boolean = false,
    preserveContentLayerWhenIdle: Boolean = false,
    content: @Composable () -> Unit
) {
    if (shouldWrapWithDissolveAnimation(isDissolving)) {
        DissolvableVideoCard(
            isDissolving = true,
            onDissolveComplete = onDissolveComplete,
            modifier = modifier,
            cardId = cardId,
            preset = preset,
            collapseAfterDissolve = collapseAfterDissolve,
            publishGlobalDissolveState = publishGlobalDissolveState,
            keepInvisibleAfterDissolve = keepInvisibleAfterDissolve,
            content = content
        )
    } else {
        Box(modifier = modifier) {
            if (preserveContentLayerWhenIdle) {
                // SharedBounds records its source relative to the card's existing graphics layer.
                // Keep the same idle hierarchy as DissolvableVideoCard for transition-enabled
                // cards, without restoring its per-frame size/window coordinate tracking.
                Box(modifier = Modifier.alpha(1f)) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}
