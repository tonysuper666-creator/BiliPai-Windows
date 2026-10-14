package com.android.purebilibili.core.ui.animation

import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** One worker per continuous motion; samples replace its target, and settling ends all frame work. */
class LiquidIndicatorVelocity(private val scope: CoroutineScope) {
    private val spec = LiquidIndicatorMotion.velocitySpec()
    private val spring = FloatSpringSpec(spec.dampingRatio, spec.stiffness, 0.01f)
    private val rateThreshold = 0.01f * sqrt(spec.stiffness)
    private var worker: Job? = null
    private var target = 0f
    private var rate = 0f
    var value by mutableFloatStateOf(0f)
        private set

    fun updateTarget(next: Float) {
        if (!next.isFinite() || next == target) return
        target = next
        if (worker?.isActive != true) {
            worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (!isSettled()) {
                    var previous = withFrameNanos { it }
                    var remainder = 0L
                    while (!isSettled()) {
                        val now = withFrameNanos { it }
                        // Carry fractional milliseconds across high refresh rate frames.
                        val elapsed = (now - previous).coerceAtLeast(0L) + remainder
                        remainder = elapsed % 1_000_000L
                        val initial = value
                        val initialRate = rate
                        value = spring.getValueFromNanos(elapsed, initial, target, initialRate)
                        rate = spring.getVelocityFromNanos(elapsed, initial, target, initialRate)
                        previous = now
                    }
                }
                value = target
                rate = 0f
            }
        }
    }

    /** Synchronous reset also interrupts the current spring without replacing its worker. */
    fun snapTo(next: Float) {
        if (!next.isFinite()) return
        target = next
        value = next
        rate = 0f
    }

    fun freeze() = snapTo(value)

    private fun isSettled(): Boolean = abs(value - target) <= 0.01f && abs(rate) <= rateThreshold
}
