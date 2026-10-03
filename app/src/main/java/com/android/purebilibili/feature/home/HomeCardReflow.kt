package com.android.purebilibili.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Survives aligned-row regrouping, whose parent keys change after a card is removed. */
internal class HomeCardReflowState {
    private val cards = mutableMapOf<String, CardMotion>()

    internal class CardMotion {
        var position: Offset? = null
        val offset = Animatable(Offset.Zero, Offset.VectorConverter)
        var job: Job? = null
        var detachedAt: Long? = null
    }

    fun card(key: String): CardMotion = cards.getOrPut(key) { CardMotion() }

    fun retainKeys(keys: Set<String>) {
        val removed = cards.keys - keys
        removed.forEach { cards.remove(it)?.job?.cancel() }
    }
}

@Composable
internal fun homeCardReflowModifier(
    key: String,
    state: HomeCardReflowState,
    scope: CoroutineScope,
    enabledProvider: () -> Boolean,
): Modifier {
    val motion = remember(key, state) { state.card(key) }
    DisposableEffect(motion) {
        onDispose { motion.detachedAt = android.os.SystemClock.uptimeMillis() }
    }
    return Modifier
        .onPlaced { coordinates ->
            val position = coordinates.positionInRoot()
            val previous = motion.position
            motion.position = position
            val detachedAt = motion.detachedAt
            motion.detachedAt = null
            val canAnimate = enabledProvider() && previous != null &&
                (detachedAt == null || android.os.SystemClock.uptimeMillis() - detachedAt < 200L)
            // Observe this only in drawing. Reading normally here would re-run placement
            // on every animation frame, even while the measured position is unchanged.
            val offset = Snapshot.withoutReadObservation { motion.offset.value }
            val delta = if (canAnimate) offset + checkNotNull(previous) - position else Offset.Zero
            if (canAnimate && previous == position) return@onPlaced
            if (!canAnimate && offset == Offset.Zero && motion.job?.isActive != true) return@onPlaced
            motion.job?.cancel()
            // Apply the compensating transform before this frame draws, then ease to the
            // real position. The page owns the job so reparenting a row cannot cancel it.
            motion.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                motion.offset.snapTo(delta)
                if (delta != Offset.Zero) {
                    motion.offset.animateTo(Offset.Zero, tween(240, easing = LinearOutSlowInEasing))
                }
            }
        }
        .graphicsLayer {
            translationX = motion.offset.value.x
            translationY = motion.offset.value.y
        }
}
